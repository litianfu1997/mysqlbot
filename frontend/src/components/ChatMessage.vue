<template>
  <div class="message" :class="{ 'message-user': message.role === 'user', 'message-assistant': message.role === 'assistant' }">
    <div class="message-content">
      <!-- Role Label -->
      <div class="role-label">{{ message.role === 'user' ? 'User' : 'MySqlBot' }}</div>

      <!-- Text Content (Markdown) -->
      <div v-html="renderMarkdown(message.content)" class="markdown-body"></div>

      <!-- SQL Query -->
      <div v-if="message.sqlQuery" class="sql-block">
        <div class="sql-header">Generated SQL</div>
        <pre><code class="language-sql">{{ message.sqlQuery }}</code></pre>
      </div>

      <!-- Error Message -->
      <el-alert v-if="message.errorMsg" :title="message.errorMsg" type="error" :closable="false" show-icon class="mt-2" />

      <!-- Data Analysis -->
      <div v-if="message.analysis" class="analysis-block">
        <div class="analysis-header"><b>Analysis</b></div>
        <div v-html="renderMarkdown(message.analysis)"></div>
      </div>

      <!-- Chart (Bar/Line/Pie) -->
      <div v-if="message.chartType && message.sqlResult && message.chartType !== 'Table'" class="chart-block">
        <div ref="chartRef" style="width: 100%; height: 300px;"></div>
      </div>

      <!-- Table: always show when there is data -->
      <div v-if="parsedResult && parsedResult.rows && parsedResult.rows.length > 0" class="table-block mt-2">
        <el-table :data="parsedResult.rows" border style="width: 100%" height="300" stripe>
           <el-table-column v-for="col in parsedResult.columns" :key="col" :prop="col" :label="col" show-overflow-tooltip />
        </el-table>
        <div class="text-xs text-gray-500 mt-1">共返回 {{ parsedResult.rowCount }} 行</div>
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
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import * as echarts from 'echarts'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits(['ask'])

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

watch(() => props.message, () => {
    nextTick(() => renderChart())
}, { flush: 'post', deep: true })

onMounted(() => {
  nextTick(() => renderChart())
})

function renderChart() {
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
</style>
