import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi, type ChatSession, type ChatMessage } from '@/api'

export const useChatStore = defineStore('chat', () => {
    const sessions = ref<ChatSession[]>([])
    const currentSessionId = ref<string | null>(null)
    const messages = ref<ChatMessage[]>([])
    const loading = ref(false)

    async function fetchSessions() {
        try {
            const res = await chatApi.getSessions()
            sessions.value = res.data
        } catch (e) {
            console.error(e)
        }
    }

    async function createSession(dataSourceId: number, title?: string) {
        const res = await chatApi.createSession(dataSourceId, title)
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
        try {
            const res = await chatApi.sendMessage(currentSessionId.value, content)
            // Check if message already exists (rare race condition but good to handle if backend echoes)
            // Actually backend returns the ASSISTANT message. So we just push it.
            messages.value.push(res.data)
        } catch (e) {
            console.error(e)
            messages.value.push({
                sessionId: currentSessionId.value,
                role: 'assistant',
                content: 'Error: Failed to send message.',
                errorMsg: String(e)
            })
        } finally {
            loading.value = false
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

    return { sessions, currentSessionId, messages, loading, fetchSessions, createSession, selectSession, sendMessage, deleteSession }
})
