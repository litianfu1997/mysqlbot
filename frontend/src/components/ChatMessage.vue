<template>
  <div class="message" :class="{ 'message--user': message.role === 'user', 'message--assistant': message.role === 'assistant' }">
    <!-- Avatar -->
    <div class="message__avatar" :class="message.role === 'user' ? 'message__avatar--user' : 'message__avatar--bot'">
      <template v-if="message.role === 'user'">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
      </template>
      <template v-else>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5"/><path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3"/>
        </svg>
      </template>
    </div>

    <div class="message__body">
      <!-- Role Label -->
      <div class="message__role">{{ message.role === 'user' ? $t('chat.user') : $t('chat.assistant') }}</div>

      <!-- Thinking Section (collapsible) -->
      <div v-if="thinkingText" class="thinking-block">
        <button class="thinking-toggle" @click="thinkingExpanded = !thinkingExpanded">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 2a8 8 0 0 1 8 8c0 3.1-1.8 5.8-4.4 7.1-.4.2-.6.6-.6 1V20a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-1.9c0-.4-.2-.8-.6-1A8 8 0 0 1 12 2z"/>
            <line x1="9" y1="21" x2="15" y2="21"/>
          </svg>
          <span class="thinking-label">{{ thinkingExpanded ? '收起思考' : '查看思考' }}</span>
          <span class="thinking-duration">({{ thinkingText.length }} 字)</span>
          <svg class="thinking-chevron" :class="{ 'thinking-chevron--open': thinkingExpanded }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        <div v-if="thinkingExpanded" class="thinking-content" v-html="renderMarkdown(thinkingText)"></div>
      </div>

      <!-- Text Content -->
      <div v-if="message.content" class="markdown-body" :class="{ 'markdown-body--streaming': isStreaming }">
        <div v-html="renderMarkdown(message.content)"></div>
        <span v-if="isStreaming" class="streaming-cursor"></span>
      </div>
      <div v-else-if="isStreaming" class="markdown-body markdown-body--streaming markdown-body--placeholder">
        <span>{{ chatStore.streamingStatus || '正在处理...' }}</span>
        <span class="streaming-cursor"></span>
      </div>

      <!-- Clarification -->
      <div v-if="clarifyOptions.length > 0" class="clarify-block">
        <div class="clarify-block__header">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
          <span>{{ $t('chat.clarifyTitle') }}</span>
        </div>
        <div class="clarify-block__options">
          <button v-for="opt in clarifyOptions" :key="opt" class="clarify-chip" @click="$emit('ask', opt)">{{ opt }}</button>
        </div>
      </div>

      <!-- SQL Block -->
      <div v-if="message.sqlQuery" class="sql-block">
        <div class="sql-block__header">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
          <span>{{ $t('chat.generatedSql') }}</span>
          <button class="sql-block__copy" @click="copySql(message.sqlQuery || '')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
        </div>
        <pre class="sql-block__code"><code class="language-sql">{{ message.sqlQuery }}</code></pre>
      </div>

      <!-- Error -->
      <el-alert v-if="message.errorMsg" :title="message.errorMsg" type="error" :closable="false" show-icon class="mt-3" />

      <!-- Data Analysis -->
      <div v-if="message.analysis" class="analysis-block">
        <div class="analysis-block__header">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.21 15.89A10 10 0 1 1 8 2.83"/><path d="M22 12A10 10 0 0 0 12 2v10z"/></svg>
          <span>{{ $t('chat.analysis') }}</span>
        </div>
        <div v-html="renderMarkdown(message.analysis)" class="analysis-block__content"></div>
      </div>

      <!-- Table -->
      <div v-if="parsedResult && parsedResult.rows && parsedResult.rows.length > 0" class="table-block">
        <div class="table-block__wrapper">
          <el-table :data="parsedResult.rows" border style="width: 100%" height="300" stripe size="small">
            <el-table-column v-for="col in parsedResult.columns" :key="col" :prop="col" :label="col" show-overflow-tooltip />
          </el-table>
        </div>
        <div class="table-block__footer">{{ $t('chat.rowCount', { count: parsedResult.rowCount }) }}</div>
      </div>

      <!-- Chart -->
      <div v-if="hasRenderableChart || canManualAnalyze" class="chart-block">
        <!-- Not analyzed yet (legacy manual entry) -->
        <div v-if="canManualAnalyze && !hasRenderableChart && !analyzing" class="chart-placeholder">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M18 20V10M12 20V4M6 20v-6"/></svg>
          <span class="chart-placeholder__text">{{ $t('chat.generateChart') }}</span>
          <el-button type="primary" size="small" @click="handleAnalyze">
            {{ $t('chat.generateChart') }}
          </el-button>
        </div>

        <!-- Analyzing -->
        <div v-else-if="analyzing" class="chart-placeholder">
          <el-icon class="is-loading" style="font-size:24px;color:var(--brand-500)"><Loading /></el-icon>
          <span class="chart-placeholder__text">{{ $t('chat.analyzing') }}</span>
        </div>

        <!-- Chart ready but hidden -->
        <div v-else-if="!showChart" class="chart-placeholder">
          <span class="chart-placeholder__text">{{ $t('chat.chartGenerated', { chartType: message.chartType }) }}</span>
          <el-button type="primary" size="small" @click="showChart = true">
            {{ $t('chat.showChart') }}
          </el-button>
        </div>

        <!-- Chart visible -->
        <div v-else ref="chartRef" class="chart-canvas"></div>
      </div>

      <!-- Suggested Questions -->
      <div v-if="suggestedQuestions.length > 0" class="suggestions">
        <button
          v-for="q in suggestedQuestions"
          :key="q"
          class="suggestion-chip"
          @click="$emit('ask', q)"
        >
          {{ q }}
        </button>
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
import { ElMessage } from 'element-plus'

const props = defineProps<{ message: ChatMessage }>()
const emit = defineEmits(['ask'])
const chatStore = useChatStore()
const analyzing = ref(false)
const thinkingExpanded = ref(false)

const md = new MarkdownIt({
  html: true,
  linkify: true,
  highlight: function (str: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(str, { language: lang }).value } catch (__) {}
    }
    return ''
  }
})

function renderMarkdown(text: string) { return md.render(text || '') }

function copySql(sql: string) {
  navigator.clipboard.writeText(sql).then(() => {
    ElMessage.success('Copied!')
  }).catch(() => {})
}

const parsedResult = computed(() => {
  if (!props.message.sqlResult) return null
  try { return JSON.parse(props.message.sqlResult) } catch { return null }
})

const suggestedQuestions = computed(() => {
  if (!props.message.suggestQuestions) return []
  try {
    const qs = JSON.parse(props.message.suggestQuestions)
    return Array.isArray(qs) ? qs : []
  } catch { return [] }
})

// Thinking content: from the thinkingContent field set during streaming
const thinkingText = computed(() => props.message.thinkingContent || '')

// Streaming state: show cursor while loading and content is being streamed
const isStreaming = computed(() => {
  return chatStore.loading && props.message.role === 'assistant' && !props.message.id
})

watch(thinkingText, (value) => {
  if (value && isStreaming.value) {
    thinkingExpanded.value = true
  }
}, { immediate: true })

// Clarification options (主动澄清的可点选项)
const clarifyOptions = computed<string[]>(() => {
  if (!props.message.clarifyOptions) return []
  try { const o = JSON.parse(props.message.clarifyOptions); return Array.isArray(o) ? o : [] } catch { return [] }
})

// Chart Logic
const chartRef = ref<HTMLElement | null>(null)
let chartInstance: echarts.ECharts | null = null

const chartTypeStr = computed(() => props.message.chartType || '')
const isTableType = computed(() => chartTypeStr.value.toLowerCase() === 'table')
// 可直接渲染的图表：制图 Agent 直出 option，或（历史消息）非 Table 的 chartType
const hasRenderableChart = computed(() => !!props.message.chartOption || (!!chartTypeStr.value && !isTableType.value))
// 尚未分析、但有数据 → 显示手动"生成图表"入口（兼容旧消息）
const canManualAnalyze = computed(() => !chartTypeStr.value && !props.message.chartOption && !!parsedResult.value?.rows?.length)

const showChart = ref(hasRenderableChart.value)

// 自动出图：分析结果到达后自动展开图表
watch(hasRenderableChart, (val) => {
    if (val) { analyzing.value = false; showChart.value = true }
})
watch(() => props.message, () => {
    if (showChart.value) nextTick(() => renderChart())
}, { flush: 'post', deep: true })
watch(showChart, (val) => { if (val) nextTick(() => renderChart()) })
onMounted(() => { if (showChart.value) nextTick(() => renderChart()) })

async function handleAnalyze() {
    if (!props.message.id) return
    analyzing.value = true
    await chatStore.analyzeMessage(props.message.id)
    analyzing.value = false
}

function renderChart() {
  if (!showChart.value || !chartRef.value) return
  const data = parsedResult.value?.rows || []

  // 优先：制图 Agent 直出的完整 ECharts option（前端注入 dataset.source）
  if (props.message.chartOption) {
    try {
      const option = JSON.parse(props.message.chartOption)
      if (Array.isArray(option.dataset)) {
        option.dataset = [{ source: data }, ...option.dataset.slice(1)]
      } else {
        if (!option.dataset) option.dataset = {}
        option.dataset.source = data
      }
      if (chartInstance) chartInstance.dispose()
      chartInstance = echarts.init(chartRef.value)
      chartInstance.setOption(option)
      window.addEventListener('resize', () => chartInstance?.resize())
      return
    } catch (e) {
      console.error('Failed to render chartOption', e)
    }
  }

  // 回退：历史消息的单 series 渲染
  if (!chartTypeStr.value || isTableType.value || !parsedResult.value) return
  if (chartInstance) chartInstance.dispose()
  chartInstance = echarts.init(chartRef.value)

  const columns = parsedResult.value.columns || []
  const xAxisName = props.message.xAxis || columns[0]
  const yAxisName = props.message.yAxis || columns[1]
  const type = chartTypeStr.value.toLowerCase()

  const option: any = {
    color: ['#3b82f6', '#60a5fa', '#93c5fd', '#bfdbfe', '#1d4ed8'],
    tooltip: { trigger: type === 'pie' ? 'item' : 'axis', backgroundColor: '#fff', borderColor: '#e2e8f0', textStyle: { color: '#1e293b', fontSize: 13 } },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'category', data: data.map((row: any) => row[xAxisName]), axisLabel: { color: '#64748b' }, axisLine: { lineStyle: { color: '#e2e8f0' } } },
    yAxis: { type: 'value', axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
    series: [{
      name: yAxisName,
      data: data.map((row: any) => row[yAxisName]),
      type: type === 'pie' ? 'pie' : (type === 'line' ? 'line' : 'bar'),
      smooth: true,
      barWidth: '40%',
    }]
  }

  if (type === 'pie') {
    option.xAxis = undefined; option.yAxis = undefined; option.grid = undefined
    option.series[0].radius = ['35%', '65%']
    option.series[0].itemStyle = { borderRadius: 6, borderColor: '#fff', borderWidth: 2 }
    option.series[0].data = data.map((row: any) => ({ value: row[yAxisName], name: row[xAxisName] }))
  }

  chartInstance.setOption(option)
  window.addEventListener('resize', () => chartInstance?.resize())
}
</script>

<style scoped>
/* ===== Message Row ===== */
.message {
  display: flex;
  gap: 12px;
  margin-bottom: 28px;
  align-items: flex-start;
}
.message--user { flex-direction: row-reverse; }

/* Avatar */
.message__avatar {
  width: 32px; height: 32px;
  border-radius: var(--radius-full);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}
.message__avatar--user {
  background: var(--brand-100);
  color: var(--brand-600);
}
.message__avatar--bot {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

/* Body */
.message__body {
  max-width: 80%;
  min-width: 0;
}
.message__role {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: .03em;
}
.message--user .message__role { text-align: right; }

/* Markdown body */
.markdown-body {
  background: var(--bg-primary);
  padding: 14px 18px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-light);
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);
}
.message--user .markdown-body {
  background: var(--brand-50);
  border-color: var(--brand-100);
}

/* Streaming cursor animation */
.markdown-body--streaming {
  display: flex;
  align-items: flex-end;
}
.markdown-body--streaming > div {
  min-width: 0;
}
.markdown-body--placeholder {
  color: var(--text-muted);
}
.streaming-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--brand-500);
  margin-left: 2px;
  animation: blink 1s step-end infinite;
  flex-shrink: 0;
  vertical-align: text-bottom;
}
@keyframes blink {
  50% { opacity: 0; }
}

/* ===== Thinking Block ===== */
.thinking-block {
  margin-bottom: 10px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-light);
  background: var(--bg-secondary);
  overflow: hidden;
}
.thinking-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 12px;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-muted);
  transition: background .15s;
}
.thinking-toggle:hover {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}
.thinking-toggle svg:first-child {
  color: #f59e0b;
}
.thinking-label {
  font-weight: 500;
}
.thinking-duration {
  color: var(--text-muted);
  opacity: .7;
}
.thinking-chevron {
  margin-left: auto;
  transition: transform .2s;
}
.thinking-chevron--open {
  transform: rotate(180deg);
}
.thinking-content {
  padding: 10px 14px;
  border-top: 1px solid var(--border-light);
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
  max-height: 300px;
  overflow-y: auto;
}

/* ===== SQL Block ===== */
.sql-block {
  margin-top: 10px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid #2d2d3f;
}
.sql-block__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: #1e1e2e;
  color: #7c8db5;
  font-size: 12px;
  font-weight: 600;
}
.sql-block__header svg { color: #60a5fa; }
.sql-block__copy {
  margin-left: auto;
  background: none;
  border: none;
  color: #64748b;
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  transition: color .15s;
}
.sql-block__copy:hover { color: #e2e8f0; }
.sql-block__code {
  margin: 0;
  background: var(--bg-code);
  color: #abb2bf;
  padding: 14px 18px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  overflow-x: auto;
}

/* ===== Analysis Block ===== */
.analysis-block {
  margin-top: 10px;
  border-radius: var(--radius-md);
  border: 1px solid #fde68a;
  background: #fffbeb;
  overflow: hidden;
}
.analysis-block__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  font-size: 12px;
  font-weight: 600;
  color: #b45309;
  border-bottom: 1px solid #fde68a;
}
.analysis-block__header svg { color: #f59e0b; }
.analysis-block__content {
  padding: 12px 14px;
  font-size: 14px;
  line-height: 1.6;
  color: #78350f;
}

/* ===== Table Block ===== */
.table-block {
  margin-top: 10px;
}
.table-block__wrapper {
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--border-default);
}
.table-block__footer {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

/* ===== Chart Block ===== */
.chart-block { margin-top: 10px; }
.chart-canvas {
  width: 100%;
  height: 320px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-light);
  background: var(--bg-primary);
}
.chart-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--border-default);
  background: var(--bg-secondary);
  color: var(--text-muted);
}
.chart-placeholder__text { font-size: 13px; }

/* ===== Clarification Block ===== */
.clarify-block {
  margin-top: 10px;
  border-radius: var(--radius-md);
  border: 1px solid var(--brand-200);
  background: var(--brand-50);
  overflow: hidden;
}
.clarify-block__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-600);
  border-bottom: 1px solid var(--brand-100);
}
.clarify-block__options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 14px;
}
.clarify-chip {
  padding: 6px 14px;
  border-radius: var(--radius-full);
  border: 1px solid var(--brand-200);
  background: var(--bg-primary);
  color: var(--brand-600);
  font-size: 13px;
  cursor: pointer;
  transition: all .15s;
}
.clarify-chip:hover {
  background: var(--brand-100);
  border-color: var(--brand-500);
}

/* ===== Suggestions ===== */
.suggestions {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.suggestion-chip {
  padding: 6px 14px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-default);
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all .15s;
}
.suggestion-chip:hover {
  background: var(--brand-50);
  border-color: var(--brand-200);
  color: var(--brand-600);
}

.mt-3 { margin-top: 12px; }

/* ===== Responsive ===== */
@media (max-width: 768px) {
  .message__body { max-width: 90%; }
}
</style>
