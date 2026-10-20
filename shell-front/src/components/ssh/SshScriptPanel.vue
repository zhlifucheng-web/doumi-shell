<template>
  <el-scrollbar class="page-scroll"><div class="ssh-script-panel">
    <div class="toolbar">
      <el-button type="primary" size="small" @click="openCreate">新建脚本</el-button>
      <el-button size="small" :loading="loading" @click="loadList">刷新</el-button>
      <span class="hint">全局脚本对所有主机可见；可绑定当前主机</span>
    </div>
    <el-table v-loading="loading" :data="scripts" size="small" stripe class="script-table">
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="范围" width="100">
        <template #default="{ row }">
          {{ row.hostId ? '本主机' : '全局' }}
        </template>
      </el-table-column>
      <el-table-column prop="interpreter" label="解释器" width="72" />
      <el-table-column label="工作目录" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          {{ workDirLabel(row.workDir) }}
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="100" show-overflow-tooltip />
      <el-table-column label="定时执行" min-width="180">
        <template #default="{ row }">
          <div v-if="row.schedule">
            <el-tag size="small" :type="row.schedule.enabled ? 'success' : 'info'">{{ row.schedule.enabled ? '已启用' : '已暂停' }}</el-tag>
            <code> {{ row.schedule.cron }}</code>
            <div class="schedule-detail">{{ row.schedule.zone }} · 账户 #{{ row.schedule.accountId }}</div>
            <div v-if="row.schedule.nextRunAt" class="schedule-detail">下次：{{ row.schedule.nextRunAt }}</div>
            <el-button v-if="row.schedule.lastStatus" link size="small" @click="showLastRun(row)">最近：{{ row.schedule.lastStatus }}</el-button>
          </div>
          <span v-else>未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="run(row)">执行</el-button>
          <el-button link size="small" @click="showHistory(row)">历史</el-button>
          <el-button link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑脚本' : '新建脚本'" width="min(860px, 95vw)" destroy-on-close><el-scrollbar max-height="65vh">
      <el-form :model="form" label-width="88px">
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="范围">
          <el-radio-group v-model="scopeMode">
            <el-radio label="global">全局</el-radio>
            <el-radio label="host" :disabled="!hostId">仅当前主机</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="解释器">
          <el-select v-model="form.interpreter" style="width: 120px">
            <el-option label="bash" value="bash" />
            <el-option label="sh" value="sh" />
          </el-select>
        </el-form-item>
        <el-form-item label="工作目录">
          <el-input
            v-model="form.workDir"
            placeholder="留空则在登录用户家目录执行，如 /opt/app"
            maxlength="512"
            clearable
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="内容">
          <ScriptEditor v-model="form.content" />
        </el-form-item>
        <el-form-item label="定时执行">
          <el-switch v-model="form.schedule.enabled" active-text="启用" inactive-text="暂停" />
        </el-form-item>
        <el-form-item label="Cron">
          <CronEditor v-model="form.schedule.cron" :zone="form.schedule.zone" @update:model-value="cronDates = []" />
        </el-form-item>
        <el-form-item label="时区">
          <el-input v-model="form.schedule.zone" placeholder="Asia/Shanghai" @input="cronDates = []" />
        </el-form-item>
        <el-form-item label="执行账户">
          <el-select v-model="form.schedule.accountId" filterable placeholder="选择已保存的 SSH 账户" style="width: 100%">
            <el-option v-for="account in scheduleAccounts.filter(a => scopeMode !== 'host' || a.hostId === hostId)" :key="account.id" :value="account.id" :label="`${account.username}@${account.hostName}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行预览">
          <el-button :loading="previewing" :disabled="!form.schedule.cron" @click="previewSchedule">查看未来 5 次执行时间</el-button>
          <div v-if="cronDates.length" class="cron-preview"><div v-for="date in cronDates" :key="date">{{ date }}</div></div>
        </el-form-item>
      </el-form>
      </el-scrollbar><template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="historyDrawer" title="脚本执行历史" size="90%" destroy-on-close>
      <ScriptExecutionHistory ref="historyRef" :script-id="historyScriptId" />
    </el-drawer>
  </div></el-scrollbar>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ScriptEditor from './ScriptEditor.vue'
import { useAiChanges } from '@/composables/useAiChanges'
import CronEditor from './CronEditor.vue'
import ScriptExecutionHistory from './ScriptExecutionHistory.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createScript,
  deleteScript,
  listScripts,
  listScheduleAccounts,
  previewCron,
  startExecution,
  updateScript
} from '@/api/sshConsole'

const props = defineProps({
  hostId: { type: Number, default: null },
  accountId: { type: Number, default: null },
  sessionToken: { type: String, default: '' },
  openingSession: { type: Boolean, default: false }
})

const emit = defineEmits(['request-session'])

const loading = ref(false)
const saving = ref(false)
const scripts = ref([])
const dlg = ref(false)
const form = ref({})
const scopeMode = ref('global')
const scheduleAccounts = ref([])
const cronDates = ref([])
const previewing = ref(false)
const emptySchedule = () => ({ enabled: false, cron: '', zone: 'Asia/Shanghai', accountId: props.accountId })
async function loadAccounts() { scheduleAccounts.value = await listScheduleAccounts() }
async function previewSchedule() {
  previewing.value = true
  try { cronDates.value = await previewCron(form.value.schedule.cron, form.value.schedule.zone) }
  finally { previewing.value = false }
}
const historyDrawer = ref(false)
const historyScriptId = ref(null)
const historyRef = ref(null)
function workDirLabel(workDir) { return workDir?.trim() || '~ (用户家目录)' }
function showHistory(row) {historyScriptId.value=row.id;historyDrawer.value=true}
function showLastRun(row) {showHistory(row)}

async function loadList() {
  loading.value = true
  try {
    scripts.value = await listScripts(props.hostId)
  } catch {
    scripts.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = {
    name: '',
    interpreter: 'bash',
    content: '',
    workDir: '',
    remark: '',
    sortOrder: 0
  }
  form.value.schedule = emptySchedule()
  cronDates.value = []
  loadAccounts()
  scopeMode.value = props.hostId ? 'host' : 'global'
  dlg.value = true
}

function openEdit(row) {
  form.value = { ...row, schedule: { ...emptySchedule(), ...(row.schedule || {}) } }
  cronDates.value = []
  loadAccounts()
  scopeMode.value = row.hostId ? 'host' : 'global'
  dlg.value = true
}

async function save() {
  if (!form.value.name?.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!form.value.content?.trim()) {
    ElMessage.warning('请填写脚本内容')
    return
  }
  const schedule = form.value.schedule
  if ((schedule.enabled || schedule.cron?.trim()) && (!schedule.cron?.trim() || !schedule.accountId)) {
    ElMessage.warning('请填写 cron 并选择执行账户')
    return
  }
  saving.value = true
  const body = {
    name: form.value.name,
    interpreter: form.value.interpreter || 'bash',
    content: form.value.content,
    remark: form.value.remark,
    workDir: form.value.workDir?.trim() || null,
    sortOrder: form.value.sortOrder ?? 0,
    hostId: scopeMode.value === 'host' ? props.hostId : null
  }
  body.schedule = schedule.cron?.trim() ? {
    enabled: schedule.enabled, cron: schedule.cron.trim(), zone: schedule.zone, accountId: schedule.accountId
  } : null
  try {
    if (form.value.id) {
      await updateScript(form.value.id, body)
    } else {
      await createScript(body)
    }
    dlg.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await ElMessageBox.confirm(`删除脚本「${row.name}」？`, '确认')
  await deleteScript(row.id)
  await loadList()
}

async function run(row) {
  if(!props.accountId) {ElMessage.warning('请选择执行账户');return}
  try {await ElMessageBox.confirm(`将在当前选中账户所在主机执行脚本「${row.name}」，工作目录：${workDirLabel(row.workDir)}`, '确认执行脚本', {type:'warning',confirmButtonText:'执行',cancelButtonText:'取消'})}catch{return}
  const execution=await startExecution(row.id,props.accountId)
  showHistory(row)
  await nextTick()
  await historyRef.value?.open(execution.id)
  historyRef.value?.load()
}

onMounted(loadList)
useAiChanges(/^(script_|configuration_import|cleanup_execute)/, loadList)
watch(() => props.hostId, loadList)
</script>

<style scoped>
.ssh-script-panel {
  box-sizing: border-box;
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 8px;
}
.schedule-detail { font-size: 11px; color: var(--el-text-color-secondary); line-height: 1.6; }
.cron-preview { width: 100%; font-family: Consolas, monospace; margin-top: 8px; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-bottom: 8px;
}
.hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-left: auto;
}
.script-table {
  flex: 1;
  min-height: 0;
}
.run-meta {
  font-size: 12px;
  margin-bottom: 8px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.run-status {
  color: var(--el-color-primary);
}
.run-out {
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 4px;
  height: calc(100vh - 160px);

  margin: 0;
}
</style>
