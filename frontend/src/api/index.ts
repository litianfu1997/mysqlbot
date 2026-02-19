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
    createdAt: string
}

export interface ChatMessage {
    id?: number
    sessionId: string
    role: 'user' | 'assistant'
    content: string
    sqlQuery?: string
    sqlResult?: string // JSON string containing parsed result
    errorMsg?: string
    analysis?: string
    chartType?: string
    xAxis?: string
    yAxis?: string
    suggestQuestions?: string // JSON string
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

// APIs
export const chatApi = {
    createSession: (dataSourceId: number, title?: string) => {
        return api.post<ChatSession>('/chat/sessions', { dataSourceId, title })
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
    deleteSession: (sessionId: string) => {
        return api.delete(`/chat/sessions/${sessionId}`)
    }
}

export const configApi = {
    getLlmConfig: () => api.get<any>('/config/llm'),
    updateLlmConfig: (config: any) => api.post('/config/llm', config),
    testLlmConnection: (config: any) => api.post<any>('/config/llm/test', config),
    getSqlConfig: () => api.get<any>('/config/sql'),
    getAllConfig: () => api.get<any>('/config/all')
}

export const dataSourceApi = {
    list: () => api.get<DataSource[]>('/datasource'),
    get: (id: number) => api.get<DataSource>(`/datasource/${id}`),
    create: (data: DataSource) => api.post<DataSource>('/datasource', data),
    update: (id: number, data: DataSource) => api.put<DataSource>(`/datasource/${id}`, data),
    delete: (id: number) => api.delete(`/datasource/${id}`),
    testConnection: (id: number) => api.post(`/datasource/${id}/test`),
    testAdHocConnection: (data: DataSource) => api.post('/datasource/test-connection', data),
    syncSchema: (id: number) => api.post(`/datasource/${id}/sync-schema`)
}
