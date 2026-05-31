<template>
  <el-dialog
    v-model="visible"
    :title="t('settings.title')"
    width="680px"
    :close-on-click-modal="false"
    append-to-body
    class="settings-dialog"
  >
    <el-tabs v-model="activeTab" class="settings-tabs">
      <!-- LLM Config -->
      <el-tab-pane :label="t('settings.tabs.llm')" name="llm">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="openLlmConfigDialog()">
            <el-icon class="mr-1"><Plus /></el-icon> {{ t('settings.llm.add') }}
          </el-button>
        </div>
        <el-table :data="llmConfigs" style="width: 100%" v-loading="loadingLlmConfigs" size="small" class="settings-table">
          <el-table-column prop="name" :label="t('settings.llm.name')" min-width="120" />
          <el-table-column prop="baseUrl" :label="t('settings.llm.baseUrl')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="defaultModel" :label="t('settings.llm.model')" min-width="120" />
          <el-table-column :label="t('settings.llm.default')" width="70" align="center">
            <template #default="scope">
              <span v-if="scope.row.isDefault" class="status-dot status-dot--success"></span>
            </template>
          </el-table-column>
          <el-table-column prop="isEnabled" :label="t('settings.llm.status')" width="80" align="center">
            <template #default="scope">
              <el-tag :type="scope.row.isEnabled ? 'success' : 'info'" size="small" effect="light" round>
                {{ scope.row.isEnabled ? t('settings.llm.enabled') : t('settings.llm.disabled') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('settings.database.actions')" width="260" fixed="right">
            <template #default="scope">
              <div class="action-group">
                <el-button link size="small" type="warning" :loading="testingLlm[scope.row.id]" @click="testLlmConnection(scope.row)">
                  {{ t('settings.llm.testConnection') }}
                </el-button>
                <el-button link size="small" :disabled="scope.row.isDefault" @click="setDefaultLlmConfig(scope.row.id)">
                  {{ t('settings.llm.setDefault') }}
                </el-button>
                <el-button link size="small" @click="openLlmConfigDialog(scope.row)">{{ t('settings.database.edit') }}</el-button>
                <el-button link size="small" type="danger" :disabled="scope.row.isDefault" @click="deleteLlmConfig(scope.row.id)">
                  {{ t('common.delete') }}
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Data Source -->
      <el-tab-pane :label="t('settings.tabs.database')" name="datasource">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="openDataSourceDialog()">
            <el-icon class="mr-1"><Plus /></el-icon> {{ t('settings.database.add') }}
          </el-button>
        </div>
        <el-table :data="dataSources" style="width: 100%" v-loading="loadingDataSources" size="small" class="settings-table">
          <el-table-column prop="name" :label="t('settings.database.name')" min-width="120" />
          <el-table-column prop="dbType" :label="t('settings.database.dbType')" width="100" />
          <el-table-column prop="host" :label="t('settings.database.host')" min-width="140" show-overflow-tooltip />
          <el-table-column prop="dbName" :label="t('settings.database.database')" min-width="120" />
          <el-table-column :label="t('settings.database.actions')" width="280" fixed="right">
            <template #default="scope">
              <div v-if="syncing[scope.row.id]" class="sync-progress-box">
                <el-progress
                  :percentage="calculatePercentage(syncProgress[scope.row.id])"
                  :status="syncProgress[scope.row.id]?.status === 'error' ? 'exception' : (syncProgress[scope.row.id]?.status === 'done' ? 'success' : '')"
                  :stroke-width="14"
                  :text-inside="true"
                />
                <div class="sync-status">
                  <span class="sync-state-text" :class="syncProgress[scope.row.id]?.status">{{ getStatusLabel(syncProgress[scope.row.id]) }}</span>
                  <span class="sync-table-name" :title="syncProgress[scope.row.id]?.currentTable">{{ syncProgress[scope.row.id]?.currentTable || '' }}</span>
                </div>
              </div>
              <div v-else class="action-group">
                <el-button link size="small" type="warning" @click="startSyncSchema(scope.row)">{{ t('settings.database.sync') }}</el-button>
                <el-button link size="small" @click="openDataSourceDialog(scope.row)">{{ t('settings.database.edit') }}</el-button>
                <el-button link size="small" type="danger" @click="deleteDataSource(scope.row.id)">{{ t('common.delete') }}</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Table Relations -->
      <el-tab-pane :label="t('settings.tabs.relation')" name="relation">
        <div class="tab-toolbar">
          <el-select v-model="selectedDataSourceForRelations" :placeholder="t('settings.database.name')" @change="fetchRelations" style="width: 200px; margin-right: 12px">
            <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
          <el-button type="primary" size="small" @click="openRelationDialog()" :disabled="!selectedDataSourceForRelations">
            <el-icon class="mr-1"><Plus /></el-icon> {{ t('settings.relation.add') }}
          </el-button>
        </div>
        <el-table :data="tableRelations" style="width: 100%" v-loading="loadingRelations" size="small" class="settings-table">
          <el-table-column prop="fromTable" :label="t('settings.relation.fromTable')" min-width="120" />
          <el-table-column prop="fromColumn" :label="t('settings.relation.fromColumn')" min-width="120" />
          <el-table-column prop="toTable" :label="t('settings.relation.toTable')" min-width="120" />
          <el-table-column prop="toColumn" :label="t('settings.relation.toColumn')" min-width="120" />
          <el-table-column prop="source" :label="t('settings.relation.source')" width="100">
            <template #default="scope">
              {{ t(`settings.relation.sourceTypes.${scope.row.source}`) }}
            </template>
          </el-table-column>
          <el-table-column prop="confidence" :label="t('settings.relation.confidence')" width="80" align="center">
            <template #default="scope">
              {{ scope.row.confidence ? scope.row.confidence.toFixed(2) : '-' }}
            </template>
          </el-table-column>
          <el-table-column :label="t('settings.relation.actions')" width="160" fixed="right">
            <template #default="scope">
              <div class="action-group">
                <el-button link size="small" @click="openRelationDialog(scope.row)" :disabled="scope.row.source !== 'manual'">{{ t('settings.database.edit') }}</el-button>
                <el-button link size="small" type="danger" @click="deleteRelation(scope.row)" :disabled="scope.row.source !== 'manual'">{{ t('common.delete') }}</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- WeCom -->
      <el-tab-pane :label="t('settings.tabs.wecom')" name="wecom">
        <el-form label-width="140px" :model="wecomForm" v-loading="loadingWecom" class="settings-form">
          <el-form-item :label="t('settings.wecom.corpId')">
            <el-input v-model="wecomForm.corpId" :placeholder="t('settings.wecom.corpId')" />
          </el-form-item>
          <el-form-item :label="t('settings.wecom.agentId')">
            <el-input v-model="wecomForm.agentId" :placeholder="t('settings.wecom.agentId')" />
          </el-form-item>
          <el-form-item :label="t('settings.wecom.secret')">
            <el-input v-model="wecomForm.secret" type="password" show-password :placeholder="t('settings.wecom.secret')" />
          </el-form-item>
          <el-form-item label="Callback Token">
            <el-input v-model="wecomForm.token" :placeholder="t('settings.wecom.token')" />
          </el-form-item>
          <el-form-item label="AES Key">
            <el-input v-model="wecomForm.encodingAesKey" type="password" show-password placeholder="EncodingAESKey" />
          </el-form-item>
          <el-form-item :label="t('settings.wecom.enabled')">
            <el-switch v-model="wecomForm.enabled" active-value="true" inactive-value="false" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingWecom" @click="saveWecomConfig">{{ t('common.save') }}</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- Feishu -->
      <el-tab-pane :label="t('settings.tabs.feishu')" name="feishu">
        <el-form label-width="160px" :model="feishuForm" v-loading="loadingFeishu" class="settings-form">
          <el-form-item :label="t('settings.feishu.appId')">
            <el-input v-model="feishuForm.appId" :placeholder="t('settings.feishu.appId')" />
          </el-form-item>
          <el-form-item :label="t('settings.feishu.appSecret')">
            <el-input v-model="feishuForm.appSecret" type="password" show-password :placeholder="t('settings.feishu.appSecret')" />
          </el-form-item>
          <el-form-item label="Verification Token">
            <el-input v-model="feishuForm.verificationToken" placeholder="Verification Token" />
          </el-form-item>
          <el-form-item :label="t('settings.feishu.encryptKey')">
            <el-input v-model="feishuForm.encryptKey" type="password" show-password :placeholder="t('settings.feishu.encryptKey')" />
          </el-form-item>
          <el-form-item :label="t('settings.feishu.enabled')">
            <el-switch v-model="feishuForm.enabled" active-value="true" inactive-value="false" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingFeishu" @click="saveFeishuConfig">{{ t('common.save') }}</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- System -->
      <el-tab-pane :label="t('settings.tabs.system')" name="system">
        <el-form label-width="120px" class="settings-form">
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
      width="480px"
      append-to-body
    >
      <el-form :model="llmForm" label-width="110px" class="settings-form">
        <el-form-item :label="t('settings.llm.name')">
          <el-input v-model="llmForm.name" placeholder="e.g. DeepSeek, OpenAI, GLM-4" />
        </el-form-item>
        <el-form-item :label="t('settings.llm.baseUrl')">
          <el-input v-model="llmForm.baseUrl" placeholder="https://api.deepseek.com" />
        </el-form-item>
        <el-form-item :label="t('settings.llm.apiKey')">
          <el-input v-model="llmForm.apiKey" type="password" :placeholder="t('settings.llm.apiKey')" show-password />
        </el-form-item>
        <el-form-item :label="t('settings.llm.model')">
          <el-select v-model="llmForm.defaultModel" :placeholder="t('settings.llm.model')" allow-create filterable default-first-option>
            <el-option v-for="(_, alias) in llmForm.modelMap" :key="alias" :label="alias" :value="alias" />
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
        <el-button @click="llmDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="success" :loading="testingLlmConnection" @click="testLlmConfig">{{ t('settings.llm.testConnection') }}</el-button>
        <el-button type="primary" :loading="savingLlm" @click="saveLlmConfig">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- Data Source Edit Dialog -->
    <el-dialog
      v-model="dsDialogVisible"
      :title="editingDataSource ? t('settings.database.edit') : t('settings.database.add')"
      width="480px"
      append-to-body
    >
      <el-form :model="dsForm" label-width="100px" class="settings-form">
        <el-form-item :label="t('settings.database.name')">
          <el-input v-model="dsForm.name" placeholder="My Database" />
        </el-form-item>
        <el-form-item :label="t('settings.database.dbType')">
          <el-select v-model="dsForm.dbType" placeholder="Select Type">
            <el-option label="PostgreSQL" value="postgresql" />
            <el-option label="MySQL" value="mysql" />
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
        <el-button @click="dsDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="success" :loading="testingConnection" @click="testConnection">{{ t('settings.database.test') }}</el-button>
        <el-button type="primary" :loading="savingDs" @click="saveDataSource">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- Relation Edit Dialog -->
    <el-dialog
      v-model="relationDialogVisible"
      :title="editingRelation ? t('settings.relation.edit') : t('settings.relation.add')"
      width="480px"
      append-to-body
    >
      <el-form :model="relationForm" label-width="100px" class="settings-form">
        <el-form-item :label="t('settings.relation.fromTable')">
          <el-input v-model="relationForm.fromTable" placeholder="orders" />
        </el-form-item>
        <el-form-item :label="t('settings.relation.fromColumn')">
          <el-input v-model="relationForm.fromColumn" placeholder="user_id" />
        </el-form-item>
        <el-form-item :label="t('settings.relation.toTable')">
          <el-input v-model="relationForm.toTable" placeholder="users" />
        </el-form-item>
        <el-form-item :label="t('settings.relation.toColumn')">
          <el-input v-model="relationForm.toColumn" placeholder="id" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="savingRelation" @click="saveRelation">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { dataSourceApi, llmConfigApi, configApi, tableRelationApi, type DataSource, type LlmConfig, type TableRelation } from '@/api'
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
  name: '', baseUrl: '', apiKey: '',
  modelMap: { 'DeepSeek-V4-Flash': 'deepseek-v4-flash', 'DeepSeek-V4-Pro': 'deepseek-v4-pro', 'GPT-4o-mini': 'gpt-4o-mini' },
  defaultModel: 'DeepSeek-V4-Flash', temperature: 0.1, isDefault: false, isEnabled: true
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
  name: '', dbType: 'postgresql', host: 'localhost', port: 5432, dbName: '', username: '', password: ''
})

// WeCom / Feishu
const loadingWecom = ref(false); const savingWecom = ref(false)
const wecomForm = ref({ corpId: '', agentId: '', secret: '', token: '', encodingAesKey: '', enabled: 'false' })
const loadingFeishu = ref(false); const savingFeishu = ref(false)
const feishuForm = ref({ appId: '', appSecret: '', verificationToken: '', encryptKey: '', enabled: 'false' })

// Table Relations
const selectedDataSourceForRelations = ref<number | null>(null)
const tableRelations = ref<any[]>([])
const loadingRelations = ref(false)
const relationDialogVisible = ref(false)
const editingRelation = ref<any | null>(null)
const savingRelation = ref(false)

const relationForm = ref({
  dataSourceId: 0, fromTable: '', fromColumn: '', toTable: '', toColumn: '', source: 'manual'
})

const open = () => { visible.value = true; fetchConfigs() }
defineExpose({ open })

watch(() => dsForm.value.dbType, (newType) => {
  if (newType === 'mysql') dsForm.value.port = 3306
  else if (newType === 'postgresql') dsForm.value.port = 5432
})

async function fetchConfigs() { fetchLlmConfigs(); fetchDataSources(); fetchWeComConfig(); fetchFeishuConfig(); if (selectedDataSourceForRelations.value) fetchRelations() }

async function fetchLlmConfigs() {
  loadingLlmConfigs.value = true
  try { const res = await llmConfigApi.list(); llmConfigs.value = res.data }
  catch { ElMessage.error('Failed to load LLM configs') }
  finally { loadingLlmConfigs.value = false }
}

async function fetchDataSources() {
  loadingDataSources.value = true
  try { const res = await dataSourceApi.list(); dataSources.value = res.data }
  catch { ElMessage.error('Failed to load Data Sources') }
  finally { loadingDataSources.value = false }
}

function openLlmConfigDialog(row?: LlmConfig) {
  if (row) { editingLlmConfig.value = row; llmForm.value = { ...row, modelMap: { ...row.modelMap } } }
  else {
    editingLlmConfig.value = null
    llmForm.value = { name: '', baseUrl: 'https://api.deepseek.com', apiKey: '',
      modelMap: { 'DeepSeek-V4-Flash': 'deepseek-v4-flash', 'DeepSeek-V4-Pro': 'deepseek-v4-pro', 'GPT-4o-mini': 'gpt-4o-mini' },
      defaultModel: 'DeepSeek-V4-Flash', temperature: 0.1, isDefault: false, isEnabled: true }
  }
  llmDialogVisible.value = true
}

async function testLlmConfig() {
  testingLlmConnection.value = true
  try {
    const res = await llmConfigApi.test(llmForm.value)
    if (res.data?.success) ElMessage.success('LLM Connection Successful: ' + res.data.message)
    else ElMessage.error('LLM Connection Failed: ' + (res.data?.message || 'Unknown error'))
  } catch(e: any) { ElMessage.error('Connection Test Error: ' + (e.message || 'Network error')) }
  finally { testingLlmConnection.value = false }
}

async function testLlmConnection(row: LlmConfig) {
  if (!row.id) return
  testingLlm.value[row.id] = true
  try {
    const res = await llmConfigApi.test(row)
    if (res.data?.success) ElMessage.success('LLM Connection Successful: ' + res.data.message)
    else ElMessage.error('LLM Connection Failed: ' + (res.data?.message || 'Unknown error'))
  } catch(e: any) { ElMessage.error('Connection Test Error: ' + (e.message || 'Network error')) }
  finally { testingLlm.value[row.id] = false }
}

async function saveLlmConfig() {
  savingLlm.value = true
  try {
    const model = llmForm.value.defaultModel
    if (model && !llmForm.value.modelMap[model]) llmForm.value.modelMap[model] = model
    if (editingLlmConfig.value?.id) await llmConfigApi.update(editingLlmConfig.value.id, llmForm.value)
    else await llmConfigApi.create(llmForm.value)
    ElMessage.success('LLM Configuration saved successfully')
    llmDialogVisible.value = false; fetchLlmConfigs()
  } catch { ElMessage.error('Failed to save LLM configuration') }
  finally { savingLlm.value = false }
}

async function setDefaultLlmConfig(id: number) {
  try { await llmConfigApi.setDefault(id); ElMessage.success('Default LLM config updated'); fetchLlmConfigs() }
  catch { ElMessage.error('Failed to set default LLM config') }
}

async function deleteLlmConfig(id: number) {
  try { await ElMessageBox.confirm('Are you sure you want to delete this LLM config?', 'Warning', { type: 'warning' }); await llmConfigApi.delete(id); ElMessage.success('LLM config deleted'); fetchLlmConfigs() }
  catch { /* cancelled */ }
}

function openDataSourceDialog(row?: DataSource) {
  if (row) { editingDataSource.value = true; dsForm.value = { ...row } }
  else { editingDataSource.value = false; dsForm.value = { name: '', dbType: 'postgresql', host: 'localhost', port: 5432, dbName: '', username: '', password: '' } }
  dsDialogVisible.value = true
}

async function testConnection() {
  testingConnection.value = true
  try {
    const res = await dataSourceApi.testAdHocConnection(dsForm.value)
    if (res.data?.success) ElMessage.success(res.data.message || 'Connection successful')
    else ElMessage.error(res.data?.message || 'Connection failed')
  } catch (e: any) { ElMessage.error(e.response?.data?.message || 'Connection failed') }
  finally { testingConnection.value = false }
}

async function saveDataSource() {
  savingDs.value = true
  try {
    if (editingDataSource.value && dsForm.value.id) await dataSourceApi.update(dsForm.value.id, dsForm.value)
    else await dataSourceApi.create(dsForm.value)
    ElMessage.success('Data Source saved'); dsDialogVisible.value = false; fetchDataSources()
  } catch (e: any) { ElMessage.error(e.response?.data?.message || 'Failed to save data source') }
  finally { savingDs.value = false }
}

const calculatePercentage = (progress: any) => {
  if (!progress) return 0
  if (progress.completed) return 100
  if (progress.status === 'extracting') return Math.min(15, (progress.totalTables || 1) * 2)
  if (progress.totalTables && progress.totalTables > 0) return Math.min(Math.max(Math.round(((progress.processedTables || 0) / progress.totalTables) * 100), 15), 99)
  return 0
}

const getStatusLabel = (progress: any) => {
  if (!progress) return 'Preparing...'
  if (progress.status === 'extracting') return 'Extracting schema...'
  if (progress.status === 'embedding') return 'Processing embeddings...'
  if (progress.status === 'done') return 'Sync complete'
  if (progress.status === 'error') return 'Sync failed'
  return 'Processing...'
}

const pollProgress = (id: number) => {
  if (activeIntervals.value[id]) return
  activeIntervals.value[id] = window.setInterval(async () => {
    try {
      const res = await dataSourceApi.getSyncProgress(id)
      syncProgress.value[id] = res.data
      if (res.data?.completed) {
        window.clearInterval(activeIntervals.value[id]); delete activeIntervals.value[id]; syncing.value[id] = false
        if (res.data.status === 'error') ElMessage.error(`Sync failed: ${res.data.error || ''}`)
        else { ElMessage.success('Schema sync complete'); fetchDataSources() }
      }
    } catch { /* ignore */ }
  }, 1500)
}

async function startSyncSchema(row: DataSource) {
  if (!row.id) return
  syncing.value[row.id] = true; syncProgress.value[row.id] = { status: 'starting' }
  try {
    const res = await dataSourceApi.syncSchema(row.id)
    if (res.data?.success) { ElMessage.success('Sync started'); pollProgress(row.id) }
    else { ElMessage.error((res.data as any).message || 'Failed to start sync'); syncing.value[row.id] = false }
  } catch (e: any) { ElMessage.error(e.response?.data?.message || 'Failed to start sync'); syncing.value[row.id] = false }
}

async function deleteDataSource(id: number) {
  try { await ElMessageBox.confirm('Are you sure?', 'Warning', { type: 'warning' }); await dataSourceApi.delete(id); ElMessage.success('Data source deleted'); fetchDataSources() }
  catch { /* cancelled */ }
}

async function fetchWeComConfig() {
  loadingWecom.value = true
  try { const res = await configApi.getWeComConfig(); wecomForm.value = res.data }
  catch { /* ignore */ }
  finally { loadingWecom.value = false }
}
async function saveWecomConfig() {
  savingWecom.value = true
  try { const res = await configApi.updateWeComConfig(wecomForm.value); if (res.data?.success) ElMessage.success(res.data.message); else ElMessage.error(res.data?.message || 'Failed') }
  catch (e: any) { ElMessage.error(e.message || 'Failed') }
  finally { savingWecom.value = false }
}

async function fetchFeishuConfig() {
  loadingFeishu.value = true
  try { const res = await configApi.getFeishuConfig(); feishuForm.value = res.data }
  catch { /* ignore */ }
  finally { loadingFeishu.value = false }
}
async function saveFeishuConfig() {
  savingFeishu.value = true
  try { const res = await configApi.updateFeishuConfig(feishuForm.value); if (res.data?.success) ElMessage.success(res.data.message); else ElMessage.error(res.data?.message || 'Failed') }
  catch (e: any) { ElMessage.error(e.message || 'Failed') }
  finally { savingFeishu.value = false }
}

async function fetchRelations() {
  if (!selectedDataSourceForRelations.value) return
  loadingRelations.value = true
  try { const res = await tableRelationApi.listByDataSource(selectedDataSourceForRelations.value); tableRelations.value = res.data }
  catch { ElMessage.error('Failed to load relations') }
  finally { loadingRelations.value = false }
}

function openRelationDialog(row?: any) {
  if (row) { editingRelation.value = row; relationForm.value = { ...row } }
  else {
    editingRelation.value = null
    relationForm.value = { dataSourceId: selectedDataSourceForRelations.value || 0, fromTable: '', fromColumn: '', toTable: '', toColumn: '', source: 'manual' }
  }
  relationDialogVisible.value = true
}

async function saveRelation() {
  savingRelation.value = true
  try {
    if (editingRelation.value?.id) await tableRelationApi.update(editingRelation.value.id, relationForm.value)
    else await tableRelationApi.create(relationForm.value)
    ElMessage.success('Relation saved'); relationDialogVisible.value = false; fetchRelations()
  } catch (e: any) { ElMessage.error(e.response?.data?.message || 'Failed to save relation') }
  finally { savingRelation.value = false }
}

async function deleteRelation(row: any) {
  try { await ElMessageBox.confirm('Are you sure?', 'Warning', { type: 'warning' }); await tableRelationApi.delete(row.id); ElMessage.success('Relation deleted'); fetchRelations() }
  catch { /* cancelled */ }
}
</script>

<style scoped>
.settings-tabs {
  min-height: 420px;
}

.tab-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}

.settings-table {
  border-radius: var(--radius-md);
  overflow: hidden;
}

.action-group {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.settings-form {
  max-width: 520px;
}

/* Status dot */
.status-dot {
  display: inline-block;
  width: 8px; height: 8px;
  border-radius: 50%;
}
.status-dot--success { background: var(--accent-success); }

/* Sync progress */
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
  color: var(--text-muted);
  margin-top: 6px;
}
.sync-table-name {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-muted);
}
.sync-state-text { font-weight: 600; }
.sync-state-text.extracting { color: var(--accent-warning); }
.sync-state-text.embedding { color: var(--brand-500); }
.sync-state-text.done { color: var(--accent-success); }
.sync-state-text.error { color: var(--accent-error); }

.mr-1 { margin-right: 4px; }
</style>
