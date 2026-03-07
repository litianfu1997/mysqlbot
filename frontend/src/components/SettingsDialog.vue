<template>
  <el-dialog
    v-model="visible"
    :title="t('settings.title')"
    width="60%"
    :close-on-click-modal="false"
    append-to-body
  >
    <el-tabs v-model="activeTab" class="settings-tabs">
      <el-tab-pane :label="t('settings.tabs.llm')" name="llm">
        <div class="mb-4 flex justify-end">
          <el-button type="primary" @click="openLlmConfigDialog()">
            <el-icon class="mr-1"><Plus /></el-icon> {{ t('settings.llm.add') }}
          </el-button>
        </div>

        <el-table :data="llmConfigs" style="width: 100%" v-loading="loadingLlmConfigs">
          <el-table-column prop="name" :label="t('settings.llm.name')" width="150" />
          <el-table-column prop="baseUrl" :label="t('settings.llm.baseUrl')" width="200" />
          <el-table-column prop="defaultModel" :label="t('settings.llm.model')" width="120" />
          <el-table-column :label="t('settings.llm.default')" width="80">
            <template #default="scope">
              <el-tag v-if="scope.row.isDefault" type="success" size="small">{{ t('common.confirm') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="isEnabled" :label="t('settings.llm.status')" width="80">
            <template #default="scope">
              <el-tag :type="scope.row.isEnabled ? 'success' : 'info'" size="small">
                {{ scope.row.isEnabled ? t('settings.llm.enabled') : t('settings.llm.disabled') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('settings.database.actions')" width="280">
            <template #default="scope">
              <el-button
                size="small"
                type="warning"
                :loading="testingLlm[scope.row.id]"
                @click="testLlmConnection(scope.row)"
              >
                {{ t('settings.llm.testConnection') }}
              </el-button>
              <el-button
                size="small"
                :disabled="scope.row.isDefault"
                @click="setDefaultLlmConfig(scope.row.id)"
              >
                {{ t('settings.llm.setDefault') }}
              </el-button>
              <el-button size="small" @click="openLlmConfigDialog(scope.row)">{{ t('settings.database.edit') }}</el-button>
              <el-button
                size="small"
                type="danger"
                :disabled="scope.row.isDefault"
                @click="deleteLlmConfig(scope.row.id)"
              >
                {{ t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="t('settings.tabs.database')" name="datasource">
        <div class="mb-4 flex justify-end">
          <el-button type="primary" @click="openDataSourceDialog()">
            <el-icon class="mr-1"><Plus /></el-icon> {{ t('settings.database.add') }}
          </el-button>
        </div>

        <el-table :data="dataSources" style="width: 100%" v-loading="loadingDataSources">
          <el-table-column prop="name" :label="t('settings.database.name')" width="150" />
          <el-table-column prop="dbType" label="Type" width="100" />
          <el-table-column prop="host" :label="t('settings.database.host')" />
          <el-table-column prop="dbName" :label="t('settings.database.database')" />
          <el-table-column :label="t('settings.database.actions')" width="300">
            <template #default="scope">
              <div v-if="syncing[scope.row.id]" class="sync-progress-box">
                <el-progress
                   :percentage="calculatePercentage(syncProgress[scope.row.id])"
                   :status="syncProgress[scope.row.id]?.status === 'error' ? 'exception' : (syncProgress[scope.row.id]?.status === 'done' ? 'success' : '')"
                   :stroke-width="16"
                   :text-inside="true"
                />
                <div class="sync-status">
                  <span class="sync-state-text" :class="syncProgress[scope.row.id]?.status">{{ getStatusLabel(syncProgress[scope.row.id]) }}</span>
                  <span class="sync-table-name" :title="syncProgress[scope.row.id]?.currentTable">{{ syncProgress[scope.row.id]?.currentTable || '请稍候...' }}</span>
                </div>
              </div>
              <div v-else>
                <el-button
                  size="small"
                  type="warning"
                  @click="startSyncSchema(scope.row)"
                >
                  {{ t('settings.database.sync') }}
                </el-button>
                <el-button size="small" @click="openDataSourceDialog(scope.row)">{{ t('settings.database.edit') }}</el-button>
                <el-button size="small" type="danger" @click="deleteDataSource(scope.row.id)">{{ t('common.delete') }}</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="t('settings.tabs.system')" name="system">
          <el-form label-width="120px">
              <el-form-item :label="t('settings.system.language')">
                  <el-select v-model="locale">
                      <el-option label="English" value="en" />
                      <el-option label="中文" value="zh" />
                  </el-select>
              </el-form-item>
          </el-form>
      </el-tab-pane>
    </el-tabs>

    <!-- LLM Config Edit Dialog -->
    <el-dialog
      v-model="llmDialogVisible"
      :title="editingLlmConfig ? t('settings.llm.edit') : t('settings.llm.add')"
      width="500px"
      append-to-body
    >
      <el-form :model="llmForm" label-width="120px">
        <el-form-item :label="t('settings.llm.name')">
          <el-input v-model="llmForm.name" placeholder="e.g. DeepSeek, GLM-4, Ollama" />
        </el-form-item>
        <el-form-item :label="t('settings.llm.baseUrl')">
          <el-input v-model="llmForm.baseUrl" placeholder="e.g. https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item :label="t('settings.llm.apiKey')">
          <el-input
            v-model="llmForm.apiKey"
            type="password"
            :placeholder="t('settings.llm.apiKey')"
            show-password
          />
        </el-form-item>
        <el-form-item :label="t('settings.llm.model')">
          <el-select v-model="llmForm.defaultModel" :placeholder="t('settings.llm.model')" allow-create filterable default-first-option>
            <el-option
              v-for="(model, alias) in llmForm.modelMap"
              :key="alias"
              :label="alias"
              :value="alias"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('settings.llm.temperature')">
          <el-slider v-model="llmForm.temperature" :min="0" :max="1" :step="0.1" show-input />
        </el-form-item>
        <el-form-item :label="t('settings.llm.enabled')">
          <el-switch v-model="llmForm.isEnabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="llmDialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="success" :loading="testingLlmConnection" @click="testLlmConfig">{{ t('settings.llm.testConnection') }}</el-button>
          <el-button type="primary" :loading="savingLlm" @click="saveLlmConfig">{{ t('common.save') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- Data Source Edit Dialog -->
    <el-dialog
      v-model="dsDialogVisible"
      :title="editingDataSource ? t('settings.database.edit') : t('settings.database.add')"
      width="500px"
      append-to-body
    >
      <el-form :model="dsForm" label-width="100px">
        <el-form-item :label="t('settings.database.name')">
          <el-input v-model="dsForm.name" placeholder="My Database" />
        </el-form-item>
        <el-form-item label="Type">
          <el-select v-model="dsForm.dbType" placeholder="Select Type">
            <el-option label="MySQL" value="mysql" />
            <el-option label="PostgreSQL" value="postgresql" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('settings.database.host')">
          <el-input v-model="dsForm.host" placeholder="localhost" />
        </el-form-item>
        <el-form-item :label="t('settings.database.port')">
          <el-input-number v-model="dsForm.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item :label="t('settings.database.database')">
          <el-input v-model="dsForm.dbName" placeholder="db_name" />
        </el-form-item>
        <el-form-item :label="t('settings.database.username')">
          <el-input v-model="dsForm.username" placeholder="root" />
        </el-form-item>
        <el-form-item :label="t('settings.database.password')">
          <el-input v-model="dsForm.password" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dsDialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="success" :loading="testingConnection" @click="testConnection">{{ t('settings.database.test') }}</el-button>
          <el-button type="primary" :loading="savingDs" @click="saveDataSource">{{ t('common.save') }}</el-button>
        </span>
      </template>
    </el-dialog>

  </el-dialog>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { dataSourceApi, llmConfigApi, type DataSource, type LlmConfig } from '@/api'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

const { t, locale } = useI18n()

const visible = ref(false)
const activeTab = ref('llm')

// LLM Configs
const llmConfigs = ref<LlmConfig[]>([])
const loadingLlmConfigs = ref(false)
const llmDialogVisible = ref(false)
const editingLlmConfig = ref<LlmConfig | null>(null)
const savingLlm = ref(false)
const testingLlm = ref<Record<number, boolean>>({})
const testingLlmConnection = ref(false)

const llmForm = ref<LlmConfig>({
  name: '',
  baseUrl: '',
  apiKey: '',
  modelMap: { 'DeepSeek': 'deepseek-chat', 'GPT-3.5': 'gpt-3.5-turbo', 'GPT-4': 'gpt-4-turbo' },
  defaultModel: 'DeepSeek',
  temperature: 0.1,
  isDefault: false,
  isEnabled: true
})

// Data Sources
const dataSources = ref<DataSource[]>([])
const loadingDataSources = ref(false)
const dsDialogVisible = ref(false)
const editingDataSource = ref(false)
const savingDs = ref(false)
const testingConnection = ref(false)
const syncing = ref<Record<number, boolean>>({})
const syncProgress = ref<Record<number, any>>({})
const activeIntervals = ref<Record<number, number>>({})

const dsForm = ref<DataSource>({
  name: '',
  dbType: 'mysql',
  host: 'localhost',
  port: 3306,
  dbName: '',
  username: '',
  password: ''
})

// Expose open method
const open = () => {
  visible.value = true
  fetchConfigs()
}

defineExpose({
  open
})

async function fetchConfigs() {
  fetchLlmConfigs()
  fetchDataSources()
}

async function fetchLlmConfigs() {
  loadingLlmConfigs.value = true
  try {
    const res = await llmConfigApi.list()
    llmConfigs.value = res.data
  } catch (e) {
    console.error('Failed to fetch LLM configs', e)
    ElMessage.error('Failed to load LLM configs')
  } finally {
    loadingLlmConfigs.value = false
  }
}

async function fetchDataSources() {
  loadingDataSources.value = true
  try {
    const res = await dataSourceApi.list()
    dataSources.value = res.data
  } catch (e) {
    console.error('Failed to fetch data sources', e)
    ElMessage.error('Failed to load Data Sources')
  } finally {
    loadingDataSources.value = false
  }
}

// LLM Config Operations
function openLlmConfigDialog(row?: LlmConfig) {
  if (row) {
    editingLlmConfig.value = row
    llmForm.value = { ...row, modelMap: { ...row.modelMap } }
  } else {
    editingLlmConfig.value = null
    llmForm.value = {
      name: '',
      baseUrl: 'https://api.deepseek.com',
      apiKey: '',
      modelMap: { 'DeepSeek': 'deepseek-chat' },
      defaultModel: 'DeepSeek',
      temperature: 0.1,
      isDefault: false,
      isEnabled: true
    }
  }
  llmDialogVisible.value = true
}

async function testLlmConfig() {
  testingLlmConnection.value = true
  try {
    const res = await llmConfigApi.test(llmForm.value)
    if (res.data && res.data.success) {
      ElMessage.success('LLM Connection Successful: ' + res.data.message)
    } else {
      ElMessage.error('LLM Connection Failed: ' + (res.data?.message || 'Unknown error'))
    }
  } catch(e: any) {
    ElMessage.error('Connection Test Error: ' + (e.message || 'Network error'))
  } finally {
    testingLlmConnection.value = false
  }
}

async function testLlmConnection(row: LlmConfig) {
  if (!row.id) return
  testingLlm.value[row.id] = true
  try {
    const res = await llmConfigApi.test(row)
    if (res.data && res.data.success) {
      ElMessage.success('LLM Connection Successful: ' + res.data.message)
    } else {
      ElMessage.error('LLM Connection Failed: ' + (res.data?.message || 'Unknown error'))
    }
  } catch(e: any) {
    ElMessage.error('Connection Test Error: ' + (e.message || 'Network error'))
  } finally {
    testingLlm.value[row.id] = false
  }
}

async function saveLlmConfig() {
  savingLlm.value = true
  try {
    // If a custom model is entered (not in current map), add it
    const model = llmForm.value.defaultModel
    if (model && !llmForm.value.modelMap[model]) {
      llmForm.value.modelMap[model] = model
    }

    if (editingLlmConfig.value && editingLlmConfig.value.id) {
      await llmConfigApi.update(editingLlmConfig.value.id, llmForm.value)
    } else {
      await llmConfigApi.create(llmForm.value)
    }
    ElMessage.success('LLM Configuration saved successfully')
    llmDialogVisible.value = false
    fetchLlmConfigs()
  } catch (e) {
    ElMessage.error('Failed to save LLM configuration')
  } finally {
    savingLlm.value = false
  }
}

async function setDefaultLlmConfig(id: number) {
  try {
    await llmConfigApi.setDefault(id)
    ElMessage.success('Default LLM config updated')
    fetchLlmConfigs()
  } catch (e) {
    ElMessage.error('Failed to set default LLM config')
  }
}

async function deleteLlmConfig(id: number) {
  try {
    await ElMessageBox.confirm('Are you sure you want to delete this LLM config?', 'Warning', {
      type: 'warning'
    })
    await llmConfigApi.delete(id)
    ElMessage.success('LLM config deleted')
    fetchLlmConfigs()
  } catch {
    // Cancelled
  }
}

// Data Source Operations
function openDataSourceDialog(row?: DataSource) {
  if (row) {
    editingDataSource.value = true
    dsForm.value = { ...row } // Copy object
  } else {
    editingDataSource.value = false
    dsForm.value = {
      name: '',
      dbType: 'mysql',
      host: 'localhost',
      port: 3306,
      dbName: '',
      username: '',
      password: ''
    }
  }
  dsDialogVisible.value = true
}

async function testConnection() {
  testingConnection.value = true
  try {
      let res;
      if (editingDataSource.value && dsForm.value.id) {
          res = await dataSourceApi.testConnection(dsForm.value.id)
      } else {
          res = await dataSourceApi.testAdHocConnection(dsForm.value)
      }

      if (res.data && res.data.success) {
          ElMessage.success(res.data.message || 'Connection successful')
      } else {
          ElMessage.error(res.data && res.data.message ? res.data.message : 'Connection failed')
      }
  } catch (e: any) {
      ElMessage.error(e.response?.data?.message || 'Connection failed')
  } finally {
      testingConnection.value = false
  }
}

async function saveDataSource() {
  savingDs.value = true
  try {
    if (editingDataSource.value && dsForm.value.id) {
        await dataSourceApi.update(dsForm.value.id, dsForm.value)
    } else {
        await dataSourceApi.create(dsForm.value)
    }
    ElMessage.success('Data Source saved')
    dsDialogVisible.value = false
    fetchDataSources()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || 'Failed to save data source')
  } finally {
    savingDs.value = false
  }
}

const calculatePercentage = (progress: any) => {
    if (!progress) return 0
    if (progress.completed) return 100
    if (progress.status === 'extracting') {
        // 在提取阶段假进度，最多到 15%
        return Math.min(15, (progress.totalTables || 1) * 2)
    }
    if (progress.totalTables && progress.totalTables > 0) {
        let p = Math.round(((progress.processedTables || 0) / progress.totalTables) * 100)
        // 保证在 embedding 的时候基础有 15%，但不超过 99% (除非完成)
        return Math.min(Math.max(p, 15), 99)
    }
    return 0
}

const getStatusLabel = (progress: any) => {
    if (!progress) return '准备中...'
    if (progress.status === 'extracting') return '🚀 提取表结构'
    if (progress.status === 'embedding') return '🧠 向量化模型处理'
    if (progress.status === 'done') return '✅ 同步完成'
    if (progress.status === 'error') return '❌ 同步失败'
    return '处理中...'
}

const pollProgress = (id: number) => {
    if (activeIntervals.value[id]) return
    
    activeIntervals.value[id] = window.setInterval(async () => {
        try {
            const res = await dataSourceApi.getSyncProgress(id)
            syncProgress.value[id] = res.data
            
            if (res.data && res.data.completed) {
                window.clearInterval(activeIntervals.value[id])
                delete activeIntervals.value[id]
                syncing.value[id] = false
                
                if (res.data.status === 'error') {
                    ElMessage.error(`同步失败: ${res.data.error || '未知错误'}`)
                } else {
                    ElMessage.success('Schema 同步完成！')
                    fetchDataSources()
                }
            }
        } catch(e) {
            console.error("Failed to query progress", e)
        }
    }, 1500)
}

async function startSyncSchema(row: DataSource) {
  if (!row.id) return
  syncing.value[row.id] = true
  syncProgress.value[row.id] = { status: 'starting' } // initialize
  
  try {
    const res = await dataSourceApi.syncSchema(row.id)
    if (res.data && (res.data as any).success) {
      ElMessage.success('同步进程已在后台启动')
      pollProgress(row.id)
    } else {
      ElMessage.error((res.data as any).message || 'Failed to start sync')
      syncing.value[row.id] = false
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || 'Failed to start sync')
    syncing.value[row.id] = false
  }
}

async function deleteDataSource(id: number) {
    try {
        await ElMessageBox.confirm('Are you sure you want to delete this data source?', 'Warning', {
            type: 'warning'
        })
        await dataSourceApi.delete(id)
        ElMessage.success('Data source deleted')
        fetchDataSources()
    } catch {
        // Cancelled
    }
}
</script>

<style scoped>
.settings-tabs {
  height: 400px;
}
.mb-4 { margin-bottom: 1rem; }
.mr-1 { margin-right: 0.25rem; }
.flex { display: flex; }
.justify-end { justify-content: flex-end; }
.sync-progress-box {
  display: flex;
  flex-direction: column;
  width: 100%;
  margin: 4px 0;
}
.sync-status {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #666;
  margin-top: 6px;
}
.sync-table-name {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #999;
}
.sync-state-text {
  font-weight: bold;
}
.sync-state-text.extracting {
  color: #E6A23C;
}
.sync-state-text.embedding {
  color: #409EFF;
}
.sync-state-text.done {
  color: #67C23A;
}
.sync-state-text.error {
  color: #F56C6C;
}
</style>
