<template>
  <div class="message" :class="{ 'message-user': message.role === 'user', 'message-assistant': message.role === 'assistant' }">
    <div class="message-content">
      <!-- Role Label -->
      <div class="role-label">{{ message.role === 'user' ? $t('chat.user') : $t('chat.assistant') }}</div>

      <!-- Text Content (Markdown) -->
      <div v-html="renderMarkdown(message.content)" class="markdown-body"></div>

      <!-- SQL Query -->
      <div v-if="message.sqlQuery" class="sql-block">
        <div class="sql-header">{{ $t('chat.generatedSql') }}</div>
        <pre><code class="language-sql">{{ message.sqlQuery }}</code></pre>
      </div>

      <!-- Error Message -->
      <el-alert v-if="message.errorMsg" :title="message.errorMsg" type="error" :closable="false" show-icon class="mt-2" />

      <!-- Data Analysis -->
      <div v-if="message.analysis" class="analysis-block">
        <div class="analysis-header"><b>{{ $t('chat.analysis') }}</b></div>
        <div v-html="renderMarkdown(message.analysis)"></div>
      </div>

      <!-- Table: always show when there is data -->
      <div v-if="parsedResult && parsedResult.rows && parsedResult.rows.length > 0" class="table-block mt-2">
        <el-table :data="parsedResult.rows" border style="width: 100%" height="300" stripe>
           <el-table-column v-for="col in parsedResult.columns" :key="col" :prop="col" :label="col" show-overflow-tooltip />
        </el-table>
        <div class="text-xs text-gray-500 mt-1">{{ $t('chat.rowCount', { count: parsedResult.rowCount }) }}</div>
      </div>

      <!-- Chart (Bar/Line/Pie) -->
      <!-- Show if chartType exists, OR if we have data (to allow generation) -->
      <div v-if="(message.chartType && message.chartType !== 'Table') || (parsedResult && parsedResult.rows && parsedResult.rows.length > 0 && !message.chartType)" class="chart-block mt-2">
        
        <!-- Case 1: Analysis not done yet -->
        <div v-if="!message.chartType && !analyzing" class="flex flex-col items-center justify-center p-4 bg-gray-50 rounded border border-gray-200">
           <div class="text-sm text-gray-500 mb-2">{{ $t('chat.generateChart') }} (AI)</div>
           <el-button type="primary" size="small" @click="handleAnalyze">
             {{ $t('chat.generateChart') }}
           </el-button>
        </div>

         <!-- Case 2: Analyzing -->
        <div v-else-if="analyzing" class="flex flex-col items-center justify-center p-4 bg-gray-50 rounded border border-gray-200">
           <el-icon class="is-loading mb-2 text-blue-500" style="font-size: 24px"><Loading /></el-icon>
           <div class="text-sm text-gray-500">{{ $t('chat.analyzing') }}</div>
        </div>

        <!-- Case 3: Chart ready but hidden (toggle) -->
        <div v-else-if="!showChart" class="flex flex-col items-center justify-center p-4 bg-gray-50 rounded border border-gray-200">
           <div class="text-sm text-gray-500 mb-2">{{ $t('chat.chartGenerated', { chartType: message.chartType }) }}</div>
           <el-button type="primary" size="small" @click="showChart = true">
             {{ $t('chat.showChart') }}
           </el-button>
        </div>
        
        <!-- Case 4: Chart visible -->
        <div v-else ref="chartRef" style="width: 100%; height: 300px;"></div>
      </div>
      
       <!-- Suggested Questions -->
      <div v-if="suggestedQuestions.length > 0" class="suggestions">
        <el-tag 
          v-for="q in suggestedQuestions" 
          :key="q" 
          class="suggestion-tag cursor-pointer hover:bg-blue-100" 
          @click="$emit('ask', q)"
          effect="plain"
          round
        >
          {{ q }}
        </el-tag>
      </div>

    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import { type ChatMessage } from '@/api'
import { useChatStore } from '@/store/chat'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import * as echarts from 'echarts'
import { Loading } from '@element-plus/icons-vue'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits(['ask'])
const chatStore = useChatStore()
const analyzing = ref(false)

const md = new MarkdownIt({
  html: true,
  linkify: true,
  highlight: function (str: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(str, { language: lang }).value;
      } catch (__) {}
    }
    return ''; // use external default escaping
  }
})

function renderMarkdown(text: string) {
  return md.render(text || '')
}

const parsedResult = computed(() => {
  if (!props.message.sqlResult) return null
  try {
    return JSON.parse(props.message.sqlResult)
  } catch (e) {
    return null
  }
})

const suggestedQuestions = computed(() => {
  if (!props.message.suggestQuestions) return []
  try {
    const qs = JSON.parse(props.message.suggestQuestions)
    return Array.isArray(qs) ? qs : []
  } catch (e) {
     return []
  }
})

// Chart Logic
const chartRef = ref<HTMLElement | null>(null)
let chartInstance: echarts.ECharts | null = null
const showChart = ref(false)

// If chartType is already present (e.g. from history), default showChart to false (require click)
// Or if the user just clicked "Analyze", we might want to auto-show. 
// Let's watch for chartType changes.

watch(() => props.message.chartType, (newVal) => {
    if (newVal && newVal !== 'Table') {
        // If we were analyzing, auto show
        if (analyzing.value) {
            analyzing.value = false
            showChart.value = true
        }
    }
})

watch(() => props.message, () => {
    if (showChart.value) {
        nextTick(() => renderChart())
    }
}, { flush: 'post', deep: true })

watch(showChart, (val) => {
    if (val) {
        nextTick(() => renderChart())
    }
})

onMounted(() => {
  if (showChart.value) {
     nextTick(() => renderChart())
  }
})

async function handleAnalyze() {
    if (!props.message.id) return
    analyzing.value = true
    await chatStore.analyzeMessage(props.message.id)
    analyzing.value = false
    // showChart.value will be set to true by the watcher if successful
}

function renderChart() {
  if (!showChart.value) return
  if (!props.message.chartType || props.message.chartType === 'Table' || !parsedResult.value || !chartRef.value) return
  
  if (chartInstance) {
      chartInstance.dispose()
  }
  chartInstance = echarts.init(chartRef.value)
  
  const data = parsedResult.value.rows || []
  const columns = parsedResult.value.columns || []
  
  // Auto detect fields if not provided
  const xAxisName = props.message.xAxis || columns[0]
  const yAxisName = props.message.yAxis || columns[1]
  
  const type = props.message.chartType.toLowerCase()

  const option: any = {
    title: { text: '', left: 'center' },
    tooltip: { trigger: 'axis' },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'category', data: data.map((row: any) => row[xAxisName]) },
    yAxis: { type: 'value' },
    series: [{
      name: yAxisName,
      data: data.map((row: any) => row[yAxisName]),
      type: type === 'pie' ? 'pie' : (type === 'line' ? 'line' : 'bar')
    }]
  }
  
  // Pie chart special handling
  if (type === 'pie') {
     option.xAxis = undefined
     option.yAxis = undefined
     option.grid = undefined
     option.tooltip = { trigger: 'item' }
     option.series[0].radius = '50%'
     option.series[0].data = data.map((row: any) => ({ value: row[yAxisName], name: row[xAxisName] }))
  }

  chartInstance.setOption(option)
  window.addEventListener('resize', () => chartInstance?.resize())
}
</script>

<style scoped>
.message {
  margin-bottom: 24px;
  display: flex;
  flex-direction: column;
}
.message-user {
  align-items: flex-end;
}
.message-assistant {
  align-items: flex-start;
}
.message-content {
    background-color: white;
    padding: 16px;
    border-radius: 12px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.05);
    max-width: 90%;
    overflow-x: auto;
}
.message-user .message-content {
    background-color: #ecf5ff;
    border-bottom-right-radius: 2px;
}
.message-assistant .message-content {
    border-bottom-left-radius: 2px;
    background-color: #f6f8fa;
}
.role-label {
    font-size: 12px;
    color: #999;
    margin-bottom: 4px;
}
.sql-block {
  background: #282c34;
  color: #abb2bf;
  padding: 12px;
  border-radius: 6px;
  margin-top: 12px;
  font-family: 'Fira Code', monospace;
  font-size: 13px;
  overflow-x: auto;
}
.sql-header {
    margin-bottom: 8px;
    font-weight: bold;
    color: #61afef;
    font-size: 12px;
    text-transform: uppercase;
}
.analysis-block {
    background: #fffbe6;
    border-left: 4px solid #faad14;
    padding: 12px;
    margin-top: 12px;
    border-radius: 4px;
    font-size: 14px;
    line-height: 1.6;
}
.analysis-header {
    margin-bottom: 6px;
    color: #d48806;
}
.suggestions {
    margin-top: 16px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
}
.mt-2 { margin-top: 8px; }
.mt-1 { margin-top: 4px; }
.text-xs { font-size: 12px; }
.text-gray-500 { color: #888; }
.flex { display: flex; }
.flex-col { flex-direction: column; }
.items-center { align-items: center; }
.justify-center { justify-content: center; }
.p-4 { padding: 16px; }
.bg-gray-50 { background-color: #f9fafb; }
.rounded { border-radius: 4px; }
.border { border-width: 1px; border-style: solid; }
.border-gray-200 { border-color: #e5e7eb; }
.text-sm { font-size: 14px; }
.mb-2 { margin-bottom: 8px; }
</style>
