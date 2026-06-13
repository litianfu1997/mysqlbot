import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi, type ChatSession, type ChatMessage, type SseEvent } from '@/api'

export const useChatStore = defineStore('chat', () => {
    const sessions = ref<ChatSession[]>([])
    const currentSessionId = ref<string | null>(null)
    const messages = ref<ChatMessage[]>([])
    const loading = ref(false)
    const streamingStatus = ref<string>('')
    // Deep-thinking toggle (persisted), controls whether requests use the reasoning model
    const thinkingMode = ref<boolean>(localStorage.getItem('thinkingMode') === '1')

    function setThinkingMode(value: boolean) {
        thinkingMode.value = value
        localStorage.setItem('thinkingMode', value ? '1' : '0')
    }

    async function fetchSessions() {
        try {
            const res = await chatApi.getSessions()
            sessions.value = res.data
        } catch (e) {
            console.error(e)
        }
    }

    async function createSession(dataSourceId: number, title?: string, llmConfigId?: number) {
        const res = await chatApi.createSession(dataSourceId, title, llmConfigId)
        sessions.value.unshift(res.data)
        currentSessionId.value = res.data.id
        messages.value = []
        loading.value = false
    }

    async function selectSession(id: string) {
        currentSessionId.value = id
        loading.value = true
        try {
            const res = await chatApi.getMessages(id)
            messages.value = res.data
        } finally {
            loading.value = false
        }
    }

    async function sendMessage(content: string) {
        if (!currentSessionId.value) return

        // Add user message immediately
        const userMsg: ChatMessage = {
            sessionId: currentSessionId.value,
            role: 'user',
            content
        }
        messages.value.push(userMsg)

        loading.value = true
        streamingStatus.value = '正在准备...'

        // Prepare a placeholder assistant message for streaming updates
        const assistantMsg: ChatMessage = {
            sessionId: currentSessionId.value!,
            role: 'assistant',
            content: '',
            thinkingContent: ''
        }
        const assistantIndex = messages.value.length
        messages.value.push(assistantMsg)

        let contentBuffer = ''

        const syncAssistantMessage = () => {
            messages.value[assistantIndex] = { ...assistantMsg }
        }

        try {
            chatApi.sendMessageStream(
                currentSessionId.value,
                content,
                thinkingMode.value,
                (event: SseEvent) => {
                    switch (event.type) {
                        case 'thinking':
                            // Accumulate thinking/reasoning tokens
                            assistantMsg.thinkingContent = (assistantMsg.thinkingContent || '') + event.data
                            syncAssistantMessage()
                            break
                        case 'content':
                            contentBuffer += String(event.data ?? '')
                            if (shouldDisplayStreamingContent(contentBuffer)) {
                                assistantMsg.content = contentBuffer
                            } else {
                                streamingStatus.value = '正在接收模型输出...'
                            }
                            syncAssistantMessage()
                            break
                        case 'status':
                            streamingStatus.value = event.data?.message || '正在处理...'
                            break
                        case 'sql_generated':
                            assistantMsg.sqlQuery = event.data?.sql || ''
                            if (event.data?.explanation && !assistantMsg.content) {
                                assistantMsg.content = event.data.explanation
                            }
                            syncAssistantMessage()
                            break
                        case 'sql_executed':
                            assistantMsg.sqlResult = JSON.stringify(event.data)
                            syncAssistantMessage()
                            break
                        case 'suggest_questions':
                            if (event.data) {
                                assistantMsg.suggestQuestions = JSON.stringify(event.data)
                                syncAssistantMessage()
                            }
                            break
                        case 'analysis':
                            if (event.data) {
                                assistantMsg.analysis = event.data.insight
                                assistantMsg.chartType = event.data.chartType
                                assistantMsg.chartOption = event.data.chartOption
                                syncAssistantMessage()
                            }
                            break
                        case 'clarification':
                            if (event.data) {
                                if (event.data.question) assistantMsg.content = event.data.question
                                if (event.data.options) assistantMsg.clarifyOptions = JSON.stringify(event.data.options)
                                syncAssistantMessage()
                            }
                            break
                        case 'complete':
                            // Replace placeholder with the final message from server
                            const finalMsg = event.data as ChatMessage
                            if (finalMsg) {
                                // Preserve thinking content if the server message doesn't have it
                                const thinkingBackup = assistantMsg.thinkingContent
                                Object.assign(assistantMsg, finalMsg)
                                if (!assistantMsg.thinkingContent && thinkingBackup) {
                                    assistantMsg.thinkingContent = thinkingBackup
                                }
                                syncAssistantMessage()
                            }
                            break
                        case 'error':
                            assistantMsg.content = event.data?.message || 'An error occurred'
                            assistantMsg.errorMsg = event.data?.message
                            syncAssistantMessage()
                            break
                    }
                },
                () => {
                    loading.value = false
                    streamingStatus.value = ''
                },
                (err) => {
                    console.error(err)
                    assistantMsg.content = 'Error: Failed to get response.'
                    assistantMsg.errorMsg = String(err)
                    syncAssistantMessage()
                    loading.value = false
                    streamingStatus.value = ''
                }
            )
        } catch (e) {
            console.error(e)
            assistantMsg.content = 'Error: Failed to send message.'
            assistantMsg.errorMsg = String(e)
            syncAssistantMessage()
            loading.value = false
            streamingStatus.value = ''
        }
    }

    async function deleteSession(id: string) {
        try {
            await chatApi.deleteSession(id)
            sessions.value = sessions.value.filter(s => s.id !== id)
            if (currentSessionId.value === id) {
                currentSessionId.value = null
                messages.value = []
            }
        } catch (e) {
            console.error(e)
            throw e
        }
    }

    function shouldDisplayStreamingContent(text: string) {
        const trimmed = text.trimStart()
        if (!trimmed) return false
        return !trimmed.startsWith('{') && !trimmed.startsWith('```json')
    }

    async function analyzeMessage(messageId: number) {
        try {
            const res = await chatApi.analyzeMessage(messageId)
            const idx = messages.value.findIndex(m => m.id === messageId)
            if (idx !== -1) {
                messages.value[idx] = res.data
            }
        } catch (e) {
            console.error(e)
        }
    }

    return {
        sessions, currentSessionId, messages, loading, streamingStatus, thinkingMode,
        setThinkingMode,
        fetchSessions, createSession, selectSession, sendMessage, deleteSession, analyzeMessage
    }
})
