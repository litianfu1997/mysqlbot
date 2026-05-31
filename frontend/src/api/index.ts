import axios from 'axios'

const api = axios.create({
    baseURL: '/api'
})

export default api

// Interfaces
export interface ChatSession {
    id: string
    title: string
    dataSourceId: number
    llmConfigId?: number
    createdAt: string
}

export interface LlmConfig {
    id?: number
    name: string
    baseUrl: string
    apiKey: string
    modelMap: Record<string, string>
    defaultModel: string
    temperature: number
    isDefault?: boolean
    isEnabled?: boolean
    createdAt?: string
    updatedAt?: string
}

export interface ChatMessage {
    id?: number
    sessionId: string
    role: 'user' | 'assistant'
    content: string
    sqlQuery?: string
    sqlResult?: string
    errorMsg?: string
    analysis?: string
    chartType?: string
    xAxis?: string
    yAxis?: string
    suggestQuestions?: string
    createdAt?: string
}

export interface DataSource {
    id?: number
    name: string
    description?: string
    dbType: string
    host: string
    port: number
    dbName: string
    username: string
    password?: string
    status?: number
    schemaSyncedAt?: string
}

// SSE stream event types
export interface SseEvent {
    type: 'user_message' | 'status' | 'sql_generated' | 'sql_executed' | 'suggest_questions' | 'complete' | 'error'
    data: any
}

// APIs
export const chatApi = {
    createSession: (dataSourceId: number, title?: string, llmConfigId?: number) => {
        return api.post<ChatSession>('/chat/sessions', { dataSourceId, title, llmConfigId })
    },
    getSessions: () => {
        return api.get<ChatSession[]>('/chat/sessions')
    },
    getMessages: (sessionId: string) => {
        return api.get<ChatMessage[]>(`/chat/sessions/${sessionId}/messages`)
    },
    sendMessage: (sessionId: string, content: string) => {
        return api.post<ChatMessage>(`/chat/sessions/${sessionId}/messages`, { content })
    },
    /**
     * SSE streaming: returns an EventSource-like fetch reader.
     * Calls onEvent for each SSE event, onDone when complete.
     */
    sendMessageStream: (
        sessionId: string,
        content: string,
        onEvent: (event: SseEvent) => void,
        onDone: () => void,
        onError: (err: any) => void
    ) => {
        const controller = new AbortController()

        fetch(`/api/chat/sessions/${sessionId}/messages/stream`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content }),
            signal: controller.signal
        }).then(async (response) => {
            if (!response.ok) {
                onError(new Error(`HTTP ${response.status}`))
                return
            }
            const reader = response.body?.getReader()
            if (!reader) { onError(new Error('No readable stream')); return }

            const decoder = new TextDecoder()
            let buffer = ''
            let currentEventType = 'message'

            while (true) {
                const { done, value } = await reader.read()
                if (done) break

                buffer += decoder.decode(value, { stream: true })
                const lines = buffer.split('\n')
                buffer = lines.pop() || ''

                for (const line of lines) {
                    if (line.startsWith('event:')) {
                        currentEventType = line.slice(6).trim()
                    } else if (line.startsWith('data:')) {
                        const raw = line.slice(5).trim()
                        try {
                            const data = JSON.parse(raw)
                            onEvent({ type: currentEventType as SseEvent['type'], data })
                            if (currentEventType === 'complete' || currentEventType === 'error') {
                                onDone()
                                return
                            }
                        } catch {
                            // skip malformed data lines
                        }
                    }
                }
            }
            onDone()
        }).catch((err) => {
            if (err.name !== 'AbortError') onError(err)
        })

        return controller
    },
    analyzeMessage: (messageId: number) => {
        return api.post<ChatMessage>(`/chat/messages/${messageId}/analyze`)
    },
    deleteSession: (sessionId: string) => {
        return api.delete(`/chat/sessions/${sessionId}`)
    }
}

export const configApi = {
    getLlmConfig: () => api.get<any>('/config/llm'),
    updateLlmConfig: (config: any) => api.post('/config/llm', config),
    testLlmConnection: (config: any) => api.post<any>('/config/llm/test', config),
    getSqlConfig: () => api.get<any>('/config/sql'),
    getAllConfig: () => api.get<any>('/config/all'),
    getWeComConfig: () => api.get<any>('/config/wecom'),
    updateWeComConfig: (config: any) => api.post<any>('/config/wecom', config),
    getFeishuConfig: () => api.get<any>('/config/feishu'),
    updateFeishuConfig: (config: any) => api.post<any>('/config/feishu', config)
}

export const dataSourceApi = {
    list: () => api.get<DataSource[]>('/datasource'),
    get: (id: number) => api.get<DataSource>(`/datasource/${id}`),
    create: (data: DataSource) => api.post<DataSource>('/datasource', data),
    update: (id: number, data: DataSource) => api.put<DataSource>(`/datasource/${id}`, data),
    delete: (id: number) => api.delete(`/datasource/${id}`),
    testConnection: (id: number) => api.post(`/datasource/${id}/test`),
    testAdHocConnection: (data: DataSource) => api.post('/datasource/test-connection', data),
    syncSchema: (id: number) => api.post(`/datasource/${id}/sync-schema`),
    getSyncProgress: (id: number) => api.get(`/datasource/${id}/sync-progress`)
}

export const llmConfigApi = {
    list: () => api.get<LlmConfig[]>('/llm-config'),
    getEnabled: () => api.get<LlmConfig[]>('/llm-config/enabled'),
    get: (id: number) => api.get<LlmConfig>(`/llm-config/${id}`),
    getDefault: () => api.get<LlmConfig>('/llm-config/default'),
    create: (data: LlmConfig) => api.post<LlmConfig>('/llm-config', data),
    update: (id: number, data: LlmConfig) => api.put<LlmConfig>(`/llm-config/${id}`, data),
    delete: (id: number) => api.delete(`/llm-config/${id}`),
    setDefault: (id: number) => api.post(`/llm-config/${id}/set-default`),
    test: (data: LlmConfig) => api.post<{ success: boolean; message: string }>('/llm-config/test', data)
}
