import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi, type ChatSession, type ChatMessage, type SseEvent } from '@/api'

export const useChatStore = defineStore('chat', () => {
    const sessions = ref<ChatSession[]>([])
    const currentSessionId = ref<string | null>(null)
    const messages = ref<ChatMessage[]>([])
    const loading = ref(false)
    const streamingStatus = ref<string>('')

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
        streamingStatus.value = 'Processing...'

        // Prepare a placeholder assistant message for streaming updates
        const assistantMsg: ChatMessage = {
            sessionId: currentSessionId.value,
            role: 'assistant',
            content: ''
        }
        messages.value.push(assistantMsg)

        try {
            chatApi.sendMessageStream(
                currentSessionId.value,
                content,
                (event: SseEvent) => {
                    switch (event.type) {
                        case 'status':
                            streamingStatus.value = event.data?.message || 'Processing...'
                            break
                        case 'sql_generated':
                            assistantMsg.sqlQuery = event.data?.sql || ''
                            if (event.data?.explanation) {
                                assistantMsg.content = event.data.explanation
                            }
                            break
                        case 'sql_executed':
                            assistantMsg.sqlResult = JSON.stringify(event.data)
                            break
                        case 'suggest_questions':
                            if (event.data) {
                                assistantMsg.suggestQuestions = JSON.stringify(event.data)
                            }
                            break
                        case 'complete':
                            // Replace placeholder with the final message from server
                            const finalMsg = event.data as ChatMessage
                            if (finalMsg) {
                                Object.assign(assistantMsg, finalMsg)
                            }
                            break
                        case 'error':
                            assistantMsg.content = event.data?.message || 'An error occurred'
                            assistantMsg.errorMsg = event.data?.message
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
                    loading.value = false
                    streamingStatus.value = ''
                }
            )
        } catch (e) {
            console.error(e)
            assistantMsg.content = 'Error: Failed to send message.'
            assistantMsg.errorMsg = String(e)
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
        sessions, currentSessionId, messages, loading, streamingStatus,
        fetchSessions, createSession, selectSession, sendMessage, deleteSession, analyzeMessage
    }
})
