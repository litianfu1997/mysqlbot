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
    chartOption?: string
    clarifyOptions?: string
    suggestQuestions?: string
    thinkingContent?: string
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

export interface TableRelation {
    id?: number
    dataSourceId: number
    fromTable: string
    fromColumn: string
    toTable: string
    toColumn: string
    source: string  // 'fk' | 'naming' | 'llm' | 'manual'
    confidence?: number
    isActive?: number
    createdAt?: string
}

// SSE stream event types
export type SseEventType = 'user_message' | 'status' | 'thinking' | 'content' | 'sql_generated' | 'sql_executed' | 'analysis' | 'suggest_questions' | 'clarification' | 'complete' | 'error'

export interface SseEvent {
    type: SseEventType
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
     *
     * Event types:
     *   - thinking: raw text token (LLM reasoning)
     *   - content: raw text token (LLM response)
     *   - status: { message: string }
     *   - sql_generated: { sql: string, explanation: string }
     *   - sql_executed: SqlExecuteResult
     *   - suggest_questions: string[]
     *   - complete: ChatMessage
     *   - error: { message: string }
     */
    sendMessageStream: (
        sessionId: string,
        content: string,
        thinking: boolean,
        onEvent: (event: SseEvent) => void,
        onDone: () => void,
        onError: (err: any) => void
    ) => {
        const controller = new AbortController()

        fetch(`/api/chat/sessions/${sessionId}/messages/stream`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content, thinking }),
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
            let finished = false

            const finish = () => {
                if (!finished) {
                    finished = true
                    onDone()
                }
            }

            const dispatchEvent = (rawEvent: string) => {
                let eventType: SseEventType = 'status'
                const dataLines: string[] = []

                for (const rawLine of rawEvent.split('\n')) {
                    if (!rawLine || rawLine.startsWith(':')) continue
                    const colonIndex = rawLine.indexOf(':')
                    const field = colonIndex === -1 ? rawLine : rawLine.slice(0, colonIndex)
                    let value = colonIndex === -1 ? '' : rawLine.slice(colonIndex + 1)
                    if (value.startsWith(' ')) value = value.slice(1)

                    if (field === 'event') {
                        eventType = value as SseEventType
                    } else if (field === 'data') {
                        dataLines.push(value)
                    }
                }

                if (dataLines.length === 0) return

                const rawData = dataLines.join('\n')
                let data: any = rawData
                try {
                    data = JSON.parse(rawData)
                } catch {
                    // Keep raw data for defensive compatibility with old stream responses.
                }

                onEvent({ type: eventType, data })
                if (eventType === 'complete' || eventType === 'error') {
                    finish()
                }
            }

            while (true) {
                const { done, value } = await reader.read()
                if (done) break

                buffer += decoder.decode(value, { stream: true })
                buffer = buffer.replace(/\r\n/g, '\n')

                let boundary = buffer.indexOf('\n\n')
                while (boundary !== -1) {
                    const rawEvent = buffer.slice(0, boundary)
                    buffer = buffer.slice(boundary + 2)
                    dispatchEvent(rawEvent)
                    if (finished) return
                    boundary = buffer.indexOf('\n\n')
                }
            }
            if (buffer.trim()) dispatchEvent(buffer)
            finish()
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
    getSyncProgress: (id: number) => api.get(`/datasource/${id}/sync-progress`),
    getSchemaTables: (id: number) => api.get<{ table: string; columns: string[] }[]>(`/datasource/${id}/schema-tables`)
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

export const tableRelationApi = {
    list: () => api.get<TableRelation[]>('/relation'),
    listByDataSource: (dataSourceId: number) => api.get<TableRelation[]>(`/relation/datasource/${dataSourceId}`),
    create: (data: TableRelation) => api.post<TableRelation>('/relation', data),
    update: (id: number, data: TableRelation) => api.put<TableRelation>(`/relation/${id}`, data),
    delete: (id: number) => api.delete(`/relation/${id}`),
    generateAIPreview: (dataSourceId: number) => api.post<TableRelation[]>(`/relation/generate-ai/${dataSourceId}`),
    saveAIRelations: (dataSourceId: number, relations: TableRelation[]) => api.post<TableRelation[]>(`/relation/generate-ai/${dataSourceId}/save`, relations)
}
