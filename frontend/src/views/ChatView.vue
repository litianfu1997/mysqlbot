<template>
  <div class="chat-container">
    <el-container class="h-full">
      <el-aside width="260px" class="bg-gray-50 border-r flex flex-col">
        <!-- Sidebar -->
        <div class="p-4 h-full flex flex-col">
          <div class="text-xl font-bold mb-6 text-blue-600 flex items-center">
             <el-icon class="mr-2"><DataAnalysis /></el-icon> MySqlBot
          </div>
          
          <el-button type="primary" class="new-chat-btn mb-4 w-full shadow-sm" @click="createNewSession">
            <el-icon class="mr-2"><Plus /></el-icon> {{ t('chat.newChat') }}
          </el-button>
          
          <div class="flex-1 overflow-y-auto pr-2 custom-scrollbar">
             <div v-if="chatStore.loading && chatStore.sessions.length === 0" class="p-4 text-center text-gray-400">{{ t('common.loading') }}</div>
             <ul class="session-list">
               <li 
                 v-for="s in chatStore.sessions" 
                 :key="s.id"
                 class="session-item"
                 :class="{ 'active': s.id === chatStore.currentSessionId }"
                 @click="chatStore.selectSession(s.id)"
               >
                 <el-icon class="mr-2 text-gray-400"><ChatDotRound /></el-icon>
                 <span class="truncate flex-1">{{ s.title }}</span>
                 <el-icon class="delete-icon" @click.stop="handleDeleteSession(s.id)"><Delete /></el-icon>
               </li>
             </ul>
          </div>
          

          <el-tooltip :content="t('settings.title')" placement="top" :show-after="400">
            <div class="settings-btn" @click="openSettings">
              <el-icon class="mr-1"><Setting /></el-icon> MySqlBot v1.0.0
            </div>
          </el-tooltip>

        </div>
      </el-aside>
      
      <el-container class="h-full relative bg-white">
          <el-header height="60px" class="border-b bg-white flex items-center justify-between px-6 shadow-sm z-10">
             <div class="flex items-center">
                 <div class="font-medium text-gray-700 text-lg">{{ currentSessionTitle }}</div>
             </div>
             <!-- 数据源切换下拉 -->
             <el-dropdown @command="switchDataSource" trigger="click">
               <div class="datasource-badge">
                 <span class="ds-dot"></span>
                 <el-icon class="mr-1"><DataBoard /></el-icon>
                 <span>{{ currentDataSourceName }}</span>
                 <el-icon class="ml-1 arrow-icon"><ArrowDown /></el-icon>
               </div>
               <template #dropdown>
                 <el-dropdown-menu>
                   <div class="px-3 pt-2 pb-1 text-xs text-gray-400 font-medium">{{ t('chat.switchDataSource') }}</div>
                   <el-dropdown-item
                     v-for="ds in dataSources"
                     :key="ds.id"
                     :command="ds.id"
                   >
                     <el-icon class="mr-1"><DataLine /></el-icon>
                     <span style="flex:1">{{ ds.name }}</span>
                     <el-icon v-if="ds.id === currentSessionDataSourceId" style="margin-left:8px;color:#3b82f6"><Check /></el-icon>
                   </el-dropdown-item>
                   <el-dropdown-item divided command="__manage__">
                     <el-icon class="mr-1"><Setting /></el-icon> {{ t('chat.manageDataSources') }}
                   </el-dropdown-item>
                 </el-dropdown-menu>
               </template>
             </el-dropdown>
          </el-header>
          
          <el-main class="p-0 relative flex flex-col">
            <div class="chat-scroll-area flex-1 p-6 pb-32" ref="scrollRef">
                 <!-- Empty State -->
                 <div v-if="chatStore.messages.length === 0" class="empty-state">
                    <div class="text-5xl mb-6 text-blue-200"><el-icon><Monitor /></el-icon></div>
                    <div class="text-2xl font-semibold text-gray-700 mb-2">{{ t('chat.welcomeTitle') }}</div>
                    <div class="text-gray-500 mb-10 max-w-md text-center">
                        {{ t('chat.welcomeText') }}
                    </div>
                    
                    <div class="grid grid-cols-2 gap-4 max-w-2xl w-full">
                       <div class="suggestion-card" @click="send('List top 5 products by sales')">
                           <div class="font-medium mb-1">{{ t('chat.exampleTitles.topProducts') }}</div>
                           <div class="text-xs text-gray-500">{{ t('chat.examples.topProducts') }}</div>
                       </div>
                       <div class="suggestion-card" @click="send('Trend analysis for last year')">
                           <div class="font-medium mb-1">{{ t('chat.exampleTitles.trendAnalysis') }}</div>
                           <div class="text-xs text-gray-500">{{ t('chat.examples.trendAnalysis') }}</div>
                       </div>
                       <div class="suggestion-card" @click="send('Analyze user retention rate')">
                           <div class="font-medium mb-1">{{ t('chat.exampleTitles.userGrowth') }}</div>
                           <div class="text-xs text-gray-500">{{ t('chat.examples.userGrowth') }}</div>
                       </div>
                       <div class="suggestion-card" @click="send('Show revenue distribution by region')">
                           <div class="font-medium mb-1">{{ t('chat.exampleTitles.revenue') }}</div>
                           <div class="text-xs text-gray-500">{{ t('chat.examples.revenue') }}</div>
                       </div>
                    </div>
                 </div>
            
                 <ChatMessage 
                    v-for="(msg, index) in chatStore.messages" 
                    :key="index" 
                    :message="msg"
                    @ask="send"
                 />
                 
                 <div v-if="chatStore.loading" class="loading-indicator">
                    <el-icon class="is-loading mr-2"><Loading /></el-icon> {{ t('chat.analyzing') }}
                 </div>
            </div>
            
            <div class="input-area">
                <div class="max-w-4xl mx-auto relative input-wrapper">
                    <el-input
                        v-model="input"
                        type="textarea"
                        :autosize="{ minRows: 1, maxRows: 6 }"
                        :placeholder="t('chat.inputPlaceholder')"
                        resize="none"
                        class="chat-input shadow-lg"
                        @keydown.enter.prevent="handleEnter"
                    />
                    <el-button 
                        type="primary" 
                        circle 
                        class="send-btn" 
                        :disabled="!input.trim() || chatStore.loading"
                        @click="handleSend"
                    >
                        <el-icon><Position /></el-icon>
                    </el-button>
                </div>
            </div>
          </el-main>
      </el-container>
    </el-container>
    
    <!-- DataSource Selection Dialog -->
    <el-dialog v-model="dialogVisible" :title="t('chat.selectDb')" width="30%" align-center>
        <div class="p-4">
            <div class="mb-2 text-gray-600">{{ t('chat.selectDb') }}</div>
            <el-select v-model="selectedDataSourceId" placeholder="Select a database" class="w-full">
                <el-option
                    v-for="item in dataSources"
                    :key="item.id"
                    :label="item.name"
                    :value="item.id"
                />
            </el-select>
        </div>
        <template #footer>
            <span class="dialog-footer">
                <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
                <el-button type="primary" @click="confirmCreateSession" :disabled="!selectedDataSourceId">
                    {{ t('chat.startChat') }}
                </el-button>
            </span>
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
import { dataSourceApi, type DataSource } from '@/api'
import { Plus, ChatDotRound, Monitor, Loading, Position, DataAnalysis, Setting, DataBoard, DataLine, ArrowDown, Check, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const chatStore = useChatStore()
const input = ref('')
const scrollRef = ref<HTMLElement | null>(null)
const settingsDialogRef = ref<InstanceType<typeof SettingsDialog> | null>(null)

// Data Source Selection
const dialogVisible = ref(false)
const dataSources = ref<DataSource[]>([])
const selectedDataSourceId = ref<number | undefined>(undefined)

onMounted(async () => {
   await chatStore.fetchSessions()
   try {
     const dsRes = await dataSourceApi.list()
     dataSources.value = dsRes.data
     // Set default selection
     if (dataSources.value.length > 0) {
         selectedDataSourceId.value = dataSources.value[0].id
     }
   } catch {
     console.error("Failed to load datasources")
   }
   
   if (chatStore.sessions.length > 0) {
       chatStore.selectSession(chatStore.sessions[0].id)
   } else {
       // If no sessions, prompt to create one after a delay
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

async function switchDataSource(command: number | string) {
    if (command === '__manage__') {
        openSettings()
        return
    }
    const dsId = command as number
    if (dsId === currentSessionDataSourceId.value) return
    
    // 找到当前会话
    const session = chatStore.sessions.find(s => s.id === chatStore.currentSessionId)
    if (!session) {
        // 没有活跃会话，直接用该数据源创建新会话
        await chatStore.createSession(dsId)
        ElMessage.success('已切换数据源并创建新对话')
        return
    }
    
    // 有活跃会话，询问用户是否新建对话
    try {
        await chatStore.createSession(dsId, `New Chat - ${dataSources.value.find(d => d.id === dsId)?.name}`)
        const dsName = dataSources.value.find(d => d.id === dsId)?.name
        ElMessage.success(`已切换到数据源：${dsName}`)
    } catch(e) {
        ElMessage.error('切换数据源失败')
    }
}

function createNewSession() {
    dialogVisible.value = true
}

function openSettings() {
    if (settingsDialogRef.value) {
        settingsDialogRef.value.open()
    }
}

async function confirmCreateSession() {
    if (selectedDataSourceId.value) {
        await chatStore.createSession(selectedDataSourceId.value)
        dialogVisible.value = false
    }
}

async function handleDeleteSession(id: string) {
    try {
        await ElMessageBox.confirm('Are you sure you want to delete this chat?', 'Delete Session', {
            confirmButtonText: 'Delete',
            cancelButtonText: 'Cancel',
            type: 'warning'
        })
        await chatStore.deleteSession(id)
        ElMessage.success('Session deleted')
    } catch {
        // Cancelled
    }
}

function handleEnter(e: KeyboardEvent) {
    if (!e.shiftKey) {
        handleSend()
    }
}

async function handleSend() {
    const text = input.value.trim()
    if (!text || chatStore.loading) return
    
    input.value = ''
    await chatStore.sendMessage(text)
}

function send(text: string) {
    input.value = '' // clear input if any
    chatStore.sendMessage(text)
}

// Auto scroll
watch(() => chatStore.messages.length, () => {
    nextTick(() => {
        if (scrollRef.value) {
            scrollRef.value.scrollTop = scrollRef.value.scrollHeight
        }
    })
})
</script>

<style scoped>
.chat-container {
  height: 100vh;
  display: flex;
  background-color: white;
}
.new-chat-btn {
  width: 100%;
}
.session-list {
    list-style: none;
    padding: 0;
    margin: 0;
}
.session-item {
    padding: 12px 16px;
    border-radius: 8px;
    cursor: pointer;
    margin-bottom: 6px;
    color: #4b5563;
    display: flex;
    align-items: center;
    transition: all 0.2s;
    font-size: 14px;
    border: 1px solid transparent;
}
.session-item:hover {
    background-color: #eef2ff;
    border-color: #dbeafe;
}
.session-item.active {
    background-color: #dbeafe;
    color: #2563eb;
    border-color: #bfdbfe;
    font-weight: 500;
}
.session-item .delete-icon {
    opacity: 0;
    transition: opacity 0.2s, color 0.2s;
    margin-left: 8px;
}
.session-item:hover .delete-icon {
    opacity: 1;
}
.session-item .delete-icon:hover {
    color: #ef4444;
}
.chat-scroll-area {
    overflow-y: auto;
    scroll-behavior: smooth;
    background-color: #f9fafb;
}
.input-area {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    padding: 24px;
    background: linear-gradient(to top, rgba(249,250,251,1) 80%, rgba(249,250,251,0));
    z-index: 20;
}
.chat-input :deep(.el-textarea__inner) {
    padding-right: 50px;
    border-radius: 16px;
    box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
    resize: none;
    padding: 14px 20px;
    border: 1px solid #e5e7eb;
    font-size: 15px;
}
.chat-input :deep(.el-textarea__inner):focus {
    box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.2);
    border-color: #2563eb;
}
.send-btn {
    position: absolute;
    right: 12px;
    bottom: 8px;
    width: 36px;
    height: 36px;
}
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 80%;
}
.suggestion-card {
    background: white;
    border: 1px solid #e5e7eb;
    padding: 16px;
    border-radius: 12px;
    cursor: pointer;
    transition: all 0.2s;
    color: #4b5563;
    font-size: 14px;
    text-align: left;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}
.suggestion-card:hover {
    background: #f0f9ff;
    border-color: #bae6fd;
    transform: translateY(-3px);
    box-shadow: 0 4px 6px rgba(0,0,0,0.05);
}
.loading-indicator {
    padding: 20px;
    text-align: center;
    color: #9ca3af;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 14px;
}
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background-color: rgba(156, 163, 175, 0.3);
  border-radius: 20px;
  border: 3px solid transparent;
  background-clip: content-box;
}
.custom-scrollbar:hover::-webkit-scrollbar-thumb {
    background-color: rgba(156, 163, 175, 0.5);
}

/* Tailwind Utilities simulation */
.w-full { width: 100%; }
.h-full { height: 100%; }
.flex { display: flex; }
.flex-col { flex-direction: column; }
.justify-between { justify-content: space-between; }
.items-center { align-items: center; }
.mr-1 { margin-right: 0.25rem; }
.mr-2 { margin-right: 0.5rem; }
.mb-1 { margin-bottom: 0.25rem; }
.mb-2 { margin-bottom: 0.5rem; }
.mb-4 { margin-bottom: 1rem; }
.mb-6 { margin-bottom: 1.5rem; }
.mb-10 { margin-bottom: 2.5rem; }
.mt-4 { margin-top: 1rem; }
.p-0 { padding: 0; }
.p-4 { padding: 1rem; }
.p-6 { padding: 1.5rem; }
.pr-2 { padding-right: 0.5rem; }
.px-3 { padding-left: 0.75rem; padding-right: 0.75rem; }
.px-6 { padding-left: 1.5rem; padding-right: 1.5rem; }
.py-1 { padding-top: 0.25rem; padding-bottom: 0.25rem; }
.pb-32 { padding-bottom: 8rem; }
.pt-4 { padding-top: 1rem; }
.bg-gray-50 { background-color: #f9fafb; }
.bg-white { background-color: white; }
.bg-gray-100 { background-color: #f3f4f6; }
.bg-green-500 { background-color: #22c55e; }
.border-r { border-right: 1px solid #e5e7eb; }
.border-b { border-bottom: 1px solid #e5e7eb; }
.border-t { border-top: 1px solid #e5e7eb; }
.text-xl { font-size: 1.25rem; line-height: 1.75rem; }
.text-2xl { font-size: 1.5rem; line-height: 2rem; }
.text-5xl { font-size: 3rem; line-height: 1; }
.font-bold { font-weight: 700; }
.font-semibold { font-weight: 600; }
.font-medium { font-weight: 500; }
.text-blue-600 { color: #2563eb; }
.text-blue-200 { color: #bfdbfe; }
.text-gray-400 { color: #9ca3af; }
.text-gray-500 { color: #6b7280; }
.text-gray-600 { color: #4b5563; }
.text-gray-700 { color: #374151; }
.text-xs { font-size: 0.75rem; line-height: 1rem; }
.text-sm { font-size: 0.875rem; line-height: 1.25rem; }
.text-lg { font-size: 1.125rem; line-height: 1.75rem; }
.text-center { text-align: center; }
.truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.relative { position: relative; }
.absolute { position: absolute; }
.max-w-4xl { max-width: 56rem; }
.max-w-2xl { max-width: 42rem; }
.max-w-md { max-width: 28rem; }
.mx-auto { margin-left: auto; margin-right: auto; }
.grid { display: grid; }
.grid-cols-2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.gap-4 { gap: 1rem; }
.flex-1 { flex: 1 1 0%; }
.overflow-y-auto { overflow-y: auto; }
.rounded-full { border-radius: 9999px; }
.shadow-sm { box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05); }
.shadow-lg { box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05); }
.z-10 { z-index: 10; }

/* 数据源切换按钟 */
.datasource-badge {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 14px;
    border-radius: 20px;
    background: #f3f4f6;
    border: 1px solid #e5e7eb;
    cursor: pointer;
    font-size: 13px;
    color: #374151;
    transition: all 0.2s;
    user-select: none;
}
.datasource-badge:hover {
    background: #e0e7ff;
    border-color: #a5b4fc;
    color: #3730a3;
}
.datasource-badge:focus-visible {
    outline: none;
}
.ds-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background-color: #22c55e;
    flex-shrink: 0;
}
.arrow-icon {
    font-size: 11px;
    color: #9ca3af;
}

/* 设置按钮 */
.settings-btn {
    margin-top: 1rem;
    padding: 8px 12px;
    border-top: 1px solid #e5e7eb;
    font-size: 12px;
    text-align: center;
    color: #9ca3af;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 8px;
    transition: all 0.2s ease;
    user-select: none;
}
.settings-btn:hover {
    color: #3b82f6;
    background: #eff6ff;
}
.settings-btn:active {
    transform: scale(0.96);
    background: #dbeafe;
    color: #2563eb;
}
.settings-btn:hover .el-icon {
    animation: spin-slow 2s linear infinite;
}
@keyframes spin-slow {
    from { transform: rotate(0deg); }
    to   { transform: rotate(360deg); }
}

</style>
