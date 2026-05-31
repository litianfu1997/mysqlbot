<template>
  <div class="chat-layout">
    <!-- Sidebar -->
    <aside class="sidebar" :class="{ 'sidebar--collapsed': !sidebarVisible }">
      <div class="sidebar__inner">
        <!-- Logo -->
        <div class="sidebar__brand">
          <div class="sidebar__logo">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <ellipse cx="12" cy="5" rx="9" ry="3"/>
              <path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5"/>
              <path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3"/>
            </svg>
          </div>
          <span class="sidebar__title">MySqlBot</span>
        </div>

        <!-- New Chat Button -->
        <button class="new-chat-btn" @click="createNewSession">
          <el-icon><Plus /></el-icon>
          <span>{{ t('chat.newChat') }}</span>
        </button>

        <!-- Session List -->
        <nav class="session-list custom-scrollbar">
          <div v-if="chatStore.loading && chatStore.sessions.length === 0" class="session-list__empty">
            {{ t('common.loading') }}
          </div>
          <div
            v-for="s in chatStore.sessions"
            :key="s.id"
            class="session-item"
            :class="{ 'session-item--active': s.id === chatStore.currentSessionId }"
            @click="chatStore.selectSession(s.id)"
          >
            <el-icon class="session-item__icon"><ChatDotRound /></el-icon>
            <span class="session-item__label">{{ s.title }}</span>
            <button class="session-item__delete" @click.stop="handleDeleteSession(s.id)">
              <el-icon><Delete /></el-icon>
            </button>
          </div>
        </nav>

        <!-- Footer -->
        <div class="sidebar__footer">
          <button class="settings-btn" @click="openSettings">
            <el-icon><Setting /></el-icon>
            <span>MySqlBot v1.0</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- Main Panel -->
    <main class="main-panel">
      <!-- Header -->
      <header class="main-header">
        <div class="main-header__left">
          <button class="sidebar-toggle" @click="sidebarVisible = !sidebarVisible">
            <el-icon :size="18"><component :is="sidebarVisible ? 'DArrowLeft' : 'Menu'" /></el-icon>
          </button>
          <h1 class="main-header__title">{{ currentSessionTitle }}</h1>
        </div>

        <div class="main-header__right">
          <!-- LLM Selector -->
          <el-dropdown @command="switchLlmConfig" trigger="click">
            <div class="header-badge">
              <el-icon :size="14"><Cpu /></el-icon>
              <span>{{ currentLlmConfigName }}</span>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <div class="dropdown-hint">{{ t('chat.switchLlmConfig') }}</div>
                <el-dropdown-item
                  v-for="config in llmConfigs"
                  :key="config.id"
                  :command="config.id"
                >
                  <span>{{ config.name }}</span>
                  <el-icon v-if="config.id === currentSessionLlmConfigId" class="check-icon"><Check /></el-icon>
                </el-dropdown-item>
                <el-dropdown-item divided command="__manage__">
                  <el-icon class="mr-1"><Setting /></el-icon> {{ t('chat.manageLlmConfigs') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <!-- Data Source Selector -->
          <el-dropdown @command="switchDataSource" trigger="click">
            <div class="header-badge header-badge--datasource">
              <span class="ds-indicator"></span>
              <el-icon :size="14"><Coin /></el-icon>
              <span>{{ currentDataSourceName }}</span>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <div class="dropdown-hint">{{ t('chat.switchDataSource') }}</div>
                <el-dropdown-item
                  v-for="ds in dataSources"
                  :key="ds.id"
                  :command="ds.id"
                >
                  <el-icon class="mr-1"><Coin /></el-icon>
                  <span>{{ ds.name }}</span>
                  <el-icon v-if="ds.id === currentSessionDataSourceId" class="check-icon"><Check /></el-icon>
                </el-dropdown-item>
                <el-dropdown-item divided command="__manage__">
                  <el-icon class="mr-1"><Setting /></el-icon> {{ t('chat.manageDataSources') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- Chat Area -->
      <section class="chat-area custom-scrollbar" ref="scrollRef">
        <!-- Empty State -->
        <div v-if="chatStore.messages.length === 0" class="welcome">
          <div class="welcome__icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <ellipse cx="12" cy="5" rx="9" ry="3"/>
              <path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5"/>
              <path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3"/>
            </svg>
          </div>
          <h2 class="welcome__title">{{ t('chat.welcomeTitle') }}</h2>
          <p class="welcome__desc">{{ t('chat.welcomeText') }}</p>

          <div class="welcome__grid">
            <button
              v-for="(key, i) in ['topProducts', 'trendAnalysis', 'userGrowth', 'revenue']"
              :key="key"
              class="welcome__card"
              @click="send(t('chat.examples.' + key))"
            >
              <div class="welcome__card-title">{{ t('chat.exampleTitles.' + key) }}</div>
              <div class="welcome__card-desc">{{ t('chat.examples.' + key) }}</div>
            </button>
          </div>
        </div>

        <!-- Messages -->
        <div class="messages-container">
          <ChatMessage
            v-for="(msg, index) in chatStore.messages"
            :key="index"
            :message="msg"
            @ask="send"
          />
        </div>

        <!-- Loading -->
        <div v-if="chatStore.loading" class="loading-bar">
          <div class="loading-bar__dot"></div>
          <div class="loading-bar__dot"></div>
          <div class="loading-bar__dot"></div>
          <span class="loading-bar__text">{{ chatStore.streamingStatus || t('chat.analyzing') }}</span>
        </div>
      </section>

      <!-- Input Area -->
      <div class="input-area">
        <div class="input-area__inner">
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            :placeholder="t('chat.inputPlaceholder')"
            resize="none"
            class="chat-input"
            @keydown.enter.prevent="handleEnter"
          />
          <button
            class="think-toggle"
            :class="{ 'think-toggle--active': chatStore.thinkingMode }"
            :title="t('chat.deepThinkingHint')"
            @click="chatStore.setThinkingMode(!chatStore.thinkingMode)"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 2a8 8 0 0 1 8 8c0 3.1-1.8 5.8-4.4 7.1-.4.2-.6.6-.6 1V20a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-1.9c0-.4-.2-.8-.6-1A8 8 0 0 1 12 2z"/>
              <line x1="9" y1="21" x2="15" y2="21"/>
            </svg>
            <span>{{ t('chat.deepThinking') }}</span>
          </button>
          <button
            class="send-btn"
            :class="{ 'send-btn--active': input.trim() && !chatStore.loading }"
            :disabled="!input.trim() || chatStore.loading"
            @click="handleSend"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>
        <div class="input-area__hint">Enter 发送 &middot; Shift+Enter 换行</div>
      </div>
    </main>

    <!-- New Session Dialog -->
    <el-dialog v-model="dialogVisible" :title="t('chat.selectDb')" width="420px" align-center>
      <div class="session-dialog">
        <label class="session-dialog__label">{{ t('chat.selectDb') }}</label>
        <el-select v-model="selectedDataSourceId" :placeholder="t('chat.selectDb')" class="w-full">
          <el-option v-for="item in dataSources" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>

        <label class="session-dialog__label" style="margin-top:16px">{{ t('chat.selectLlmConfig') }}</label>
        <el-select v-model="selectedLlmConfigId" placeholder="Select LLM Config" class="w-full">
          <el-option v-for="item in llmConfigs" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="confirmCreateSession" :disabled="!selectedDataSourceId">
          {{ t('chat.startChat') }}
        </el-button>
      </template>
    </el-dialog>

    <SettingsDialog ref="settingsDialogRef" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import { useChatStore } from '@/store/chat'
import ChatMessage from '@/components/ChatMessage.vue'
import SettingsDialog from '@/components/SettingsDialog.vue'
import { dataSourceApi, llmConfigApi, type DataSource, type LlmConfig } from '@/api'
import { Plus, ChatDotRound, Setting, ArrowDown, Check, Delete, Cpu, Coin } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const chatStore = useChatStore()
const input = ref('')
const scrollRef = ref<HTMLElement | null>(null)
const sidebarVisible = ref(true)
const settingsDialogRef = ref<InstanceType<typeof SettingsDialog> | null>(null)

// Data Source Selection
const dialogVisible = ref(false)
const dataSources = ref<DataSource[]>([])
const selectedDataSourceId = ref<number | undefined>(undefined)

// LLM Config Selection
const llmConfigs = ref<LlmConfig[]>([])
const selectedLlmConfigId = ref<number | undefined>(undefined)

onMounted(async () => {
   await chatStore.fetchSessions()
   try {
     const dsRes = await dataSourceApi.list()
     dataSources.value = dsRes.data
     if (dataSources.value.length > 0) {
         selectedDataSourceId.value = dataSources.value[0].id
     }
   } catch { console.error("Failed to load datasources") }

   try {
     const llmRes = await llmConfigApi.getEnabled()
     llmConfigs.value = llmRes.data
     const defaultConfig = llmConfigs.value.find(c => c.isDefault)
     if (defaultConfig) {
         selectedLlmConfigId.value = defaultConfig.id
     } else if (llmConfigs.value.length > 0) {
         selectedLlmConfigId.value = llmConfigs.value[0].id
     }
   } catch { console.error("Failed to load LLM configs") }

   if (chatStore.sessions.length > 0) {
       chatStore.selectSession(chatStore.sessions[0].id)
   } else {
       setTimeout(() => dialogVisible.value = true, 500)
   }
})

const currentSessionTitle = computed(() => {
    const s = chatStore.sessions.find(s => s.id === chatStore.currentSessionId)
    return s ? s.title : 'New Chat'
})

const currentSessionDataSourceId = computed(() => {
    const s = chatStore.sessions.find(s => s.id === chatStore.currentSessionId)
    return s?.dataSourceId ?? null
})

const currentDataSourceName = computed(() => {
    const ds = dataSources.value.find(d => d.id === currentSessionDataSourceId.value)
    return ds ? ds.name : 'No DB'
})

const currentSessionLlmConfigId = computed(() => {
    const s = chatStore.sessions.find(s => s.id === chatStore.currentSessionId)
    return s?.llmConfigId ?? null
})

const currentLlmConfigName = computed(() => {
    const config = llmConfigs.value.find(c => c.id === currentSessionLlmConfigId.value)
    return config ? config.name : 'Default'
})

async function switchDataSource(command: number | string) {
    if (command === '__manage__') { openSettings(); return }
    const dsId = command as number
    if (dsId === currentSessionDataSourceId.value) return
    const session = chatStore.sessions.find(s => s.id === chatStore.currentSessionId)
    if (!session) {
        await chatStore.createSession(dsId, undefined, selectedLlmConfigId.value)
        ElMessage.success('Created new session')
        return
    }
    try {
        await chatStore.createSession(dsId, `New Chat - ${dataSources.value.find(d => d.id === dsId)?.name}`, selectedLlmConfigId.value)
        const dsName = dataSources.value.find(d => d.id === dsId)?.name
        ElMessage.success(`Switched to ${dsName}`)
    } catch(e) {
        ElMessage.error('Failed to switch data source')
    }
}

async function switchLlmConfig(command: number | string) {
    if (command === '__manage__') { openSettings(); return }
    const configId = command as number
    selectedLlmConfigId.value = configId
    const config = llmConfigs.value.find(c => c.id === configId)
    ElMessage.success(`Switched to ${config?.name || 'Default'}`)
}

function createNewSession() { dialogVisible.value = true }
function openSettings() { settingsDialogRef.value?.open() }

async function confirmCreateSession() {
    if (selectedDataSourceId.value) {
        await chatStore.createSession(selectedDataSourceId.value, undefined, selectedLlmConfigId.value)
        dialogVisible.value = false
    }
}

async function handleDeleteSession(id: string) {
    try {
        await ElMessageBox.confirm('Are you sure you want to delete this chat?', 'Delete Session', {
            confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning'
        })
        await chatStore.deleteSession(id)
        ElMessage.success('Session deleted')
    } catch { /* cancelled */ }
}

function handleEnter(e: KeyboardEvent) { if (!e.shiftKey) handleSend() }

async function handleSend() {
    const text = input.value.trim()
    if (!text || chatStore.loading) return
    input.value = ''
    await chatStore.sendMessage(text)
}

function send(text: string) { input.value = ''; chatStore.sendMessage(text) }

function scrollToBottom() {
    nextTick(() => {
        if (scrollRef.value) scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    })
}

watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.messages.at(-1)?.content, scrollToBottom)
watch(() => chatStore.messages.at(-1)?.thinkingContent, scrollToBottom)
watch(() => chatStore.streamingStatus, scrollToBottom)
</script>

<style scoped>
/* ===== Layout ===== */
.chat-layout {
  height: 100vh;
  display: flex;
  background: var(--bg-chat);
}

/* ===== Sidebar ===== */
.sidebar {
  width: var(--sidebar-width);
  min-width: var(--sidebar-width);
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-light);
  display: flex;
  flex-direction: column;
  transition: margin-left .28s cubic-bezier(.4,0,.2,1), opacity .2s;
  z-index: 30;
}
.sidebar--collapsed {
  margin-left: calc(var(--sidebar-width) * -1);
  opacity: 0;
  pointer-events: none;
}
.sidebar__inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 20px 14px 12px;
}

/* Brand */
.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px;
  margin-bottom: 20px;
}
.sidebar__logo {
  width: 36px; height: 36px;
  background: var(--brand-600);
  border-radius: var(--radius-md);
  display: flex; align-items: center; justify-content: center;
  color: white;
  flex-shrink: 0;
}
.sidebar__title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -.02em;
}

/* New Chat */
.new-chat-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 0;
  border: 1px dashed var(--brand-200);
  border-radius: var(--radius-md);
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all .18s;
}
.new-chat-btn:hover {
  background: var(--brand-100);
  border-color: var(--brand-500);
  box-shadow: var(--shadow-sm);
}

/* Session List */
.session-list {
  flex: 1;
  overflow-y: auto;
  margin-top: 16px;
  padding-right: 4px;
}
.session-list__empty {
  padding: 24px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 13.5px;
  transition: all .15s;
  position: relative;
  margin-bottom: 2px;
}
.session-item:hover {
  background: var(--brand-50);
  color: var(--text-primary);
}
.session-item--active {
  background: var(--brand-100);
  color: var(--brand-600);
  font-weight: 500;
}
.session-item__icon { font-size: 16px; flex-shrink: 0; }
.session-item__label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-item__delete {
  opacity: 0;
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  transition: all .15s;
  display: flex;
  align-items: center;
}
.session-item:hover .session-item__delete { opacity: 1; }
.session-item__delete:hover { color: var(--accent-error); background: #fef2f2; }

/* Sidebar Footer */
.sidebar__footer {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--border-light);
}
.settings-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  border-radius: var(--radius-sm);
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all .15s;
}
.settings-btn:hover { color: var(--brand-600); background: var(--brand-50); }
.settings-btn:active { transform: scale(.97); }

/* ===== Main Panel ===== */
.main-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
}

/* Header */
.main-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-primary);
  flex-shrink: 0;
}
.main-header__left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.sidebar-toggle {
  width: 32px; height: 32px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-default);
  background: var(--bg-primary);
  display: flex; align-items: center; justify-content: center;
  cursor: pointer;
  color: var(--text-secondary);
  transition: all .15s;
}
.sidebar-toggle:hover {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}
.main-header__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.main-header__right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Header Badge */
.header-badge {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-default);
  background: var(--bg-primary);
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all .15s;
  user-select: none;
}
.header-badge:hover {
  border-color: var(--brand-200);
  color: var(--text-primary);
  background: var(--brand-50);
}
.header-badge--datasource { /* same as default */ }
.ds-indicator {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: var(--accent-success);
  flex-shrink: 0;
}
.check-icon { color: var(--brand-500); margin-left: 8px; }
.dropdown-hint {
  padding: 8px 16px 4px;
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: .04em;
}

/* ===== Chat Area ===== */
.chat-area {
  flex: 1;
  overflow-y: auto;
  scroll-behavior: smooth;
  padding: 0;
}

/* Welcome / Empty State */
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 200px);
  padding: 40px 24px;
}
.welcome__icon {
  width: 80px; height: 80px;
  border-radius: var(--radius-xl);
  background: var(--brand-50);
  display: flex; align-items: center; justify-content: center;
  color: var(--brand-500);
  margin-bottom: 24px;
}
.welcome__title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 8px;
}
.welcome__desc {
  font-size: 15px;
  color: var(--text-secondary);
  text-align: center;
  max-width: 420px;
  margin: 0 0 36px;
  line-height: 1.6;
}
.welcome__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  max-width: 520px;
  width: 100%;
}
.welcome__card {
  background: var(--bg-primary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: 16px;
  cursor: pointer;
  text-align: left;
  transition: all .18s;
}
.welcome__card:hover {
  border-color: var(--brand-200);
  background: var(--brand-50);
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
.welcome__card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}
.welcome__card-desc {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.4;
}

/* Messages Container */
.messages-container {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px 24px 120px;
}

/* Loading Bar */
.loading-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 16px 0;
  max-width: 800px;
  margin: 0 auto;
}
.loading-bar__dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--brand-400, #60a5fa);
  animation: bounce .6s infinite alternate;
}
.loading-bar__dot:nth-child(2) { animation-delay: .15s; }
.loading-bar__dot:nth-child(3) { animation-delay: .3s; }
.loading-bar__text {
  margin-left: 8px;
  font-size: 13px;
  color: var(--text-muted);
}
@keyframes bounce {
  to { opacity: .3; transform: translateY(-4px); }
}

/* ===== Input Area ===== */
.input-area {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 16px 24px 12px;
  background: linear-gradient(to top, var(--bg-chat) 60%, transparent);
  z-index: 20;
}
.input-area__inner {
  max-width: 800px;
  margin: 0 auto;
  position: relative;
}
.chat-input :deep(.el-textarea__inner) {
  padding: 14px 52px 46px 18px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-default);
  background: var(--bg-primary);
  font-size: 14px;
  line-height: 1.5;
  box-shadow: var(--shadow-md);
  transition: border-color .15s, box-shadow .15s;
}
.chat-input :deep(.el-textarea__inner):focus {
  border-color: var(--brand-400, #60a5fa);
  box-shadow: var(--shadow-md), 0 0 0 3px rgba(59,130,246,.1);
}
.think-toggle {
  position: absolute;
  left: 10px;
  bottom: 8px;
  display: flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-default);
  background: var(--bg-primary);
  color: var(--text-muted);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all .18s;
}
.think-toggle:hover {
  border-color: var(--brand-300, #93c5fd);
  color: var(--text-secondary);
}
.think-toggle--active {
  background: var(--brand-50);
  border-color: var(--brand-300, #93c5fd);
  color: var(--brand-600);
}
.think-toggle--active svg { color: #f59e0b; }

.send-btn {
  position: absolute;
  right: 8px;
  bottom: 8px;
  width: 36px; height: 36px;
  border-radius: 50%;
  border: none;
  background: var(--bg-tertiary);
  color: var(--text-muted);
  display: flex; align-items: center; justify-content: center;
  cursor: pointer;
  transition: all .18s;
}
.send-btn--active {
  background: var(--brand-600);
  color: white;
  box-shadow: 0 2px 8px rgba(37,99,235,.3);
}
.send-btn--active:hover {
  background: var(--brand-700);
  transform: scale(1.05);
}
.input-area__hint {
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 6px;
}

/* Session Dialog */
.session-dialog { padding: 8px 0; }
.session-dialog__label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 8px;
}

/* ===== Responsive ===== */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    left: 0; top: 0; bottom: 0;
    box-shadow: var(--shadow-xl);
  }
  .welcome__grid {
    grid-template-columns: 1fr;
  }
  .messages-container { padding: 16px 12px 120px; }
  .input-area { padding: 12px 12px 8px; }
  .main-header__right .header-badge span:not(.ds-indicator) {
    max-width: 80px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
</style>
