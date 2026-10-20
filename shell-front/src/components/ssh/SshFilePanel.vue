<template>
  <div class="ssh-file-panel" :class="{ embedded }">
    <div v-if="!sessionToken" class="no-session">
      <el-empty description="尚未建立 SSH 会话">
        <el-button type="primary" :loading="openingSession" @click="$emit('request-session')">
          建立文件会话
        </el-button>
      </el-empty>
      <p class="hint">也可先在「终端」Tab 连接，连接成功后会自动复用 sessionToken。</p>
      <el-button size="small" @click="showTransfers = true">{{ transferButtonLabel }}</el-button>
    </div>
    <template v-else>
      <div class="toolbar">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>
            <a href="#" @click.prevent="goRoot">根</a>
          </el-breadcrumb-item>
          <el-breadcrumb-item v-for="(seg, idx) in breadcrumbs" :key="idx">
            <a href="#" @click.prevent="goToIndex(idx)">{{ seg }}</a>
          </el-breadcrumb-item>
        </el-breadcrumb>
        <div class="toolbar-actions">
          <el-input
            v-model="pathInput"
            size="small"
            class="path-input"
            placeholder="绝对路径"
            @keyup.enter="navigateToPath"
          />
          <el-button size="small" @click="navigateToPath">前往</el-button>
          <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
          <el-button size="small" :type="activeTransferCount ? 'primary' : 'default'" plain @click="showTransfers = true">{{ transferButtonLabel }}</el-button>
          <el-button v-if="embedded" size="small" :aria-pressed="showDetails" @click="showDetails=!showDetails">{{ showDetails ? '精简列表' : '详细信息' }}</el-button>
          <el-button size="small" type="primary" @click="showMkdir = true">新建目录</el-button>
          <input ref="uploadInput" type="file" hidden multiple @change="uploadFiles" />
          <el-button size="small" type="success" @click="uploadInput.click()">上传文件</el-button>
          <FolderTransfer ref="folderTransfer" :session-token="sessionToken" :path="currentPath" @uploaded="refresh" />
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="items"
        size="small"
        height="100%"
        class="file-table"
        @row-dblclick="onRowDblClick"
      >
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }">
            <el-icon class="row-ico">
              <Folder v-if="row.dir" />
              <Document v-else />
            </el-icon>
            <span>{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">
            {{ row.dir ? '—' : formatSize(row.size) }}
          </template>
        </el-table-column>
        <el-table-column v-if="!embedded || showDetails" prop="owner" label="属主" width="88" show-overflow-tooltip />
        <el-table-column v-if="!embedded || showDetails" prop="group" label="属组" width="88" show-overflow-tooltip />
        <el-table-column v-if="!embedded || showDetails" prop="permissions" label="权限" width="108">
          <template #default="{ row }">
            <span class="perm-cell">{{ row.permissions || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!embedded || showDetails" label="修改时间" width="168">
          <template #default="{ row }">
            {{ formatMtime(row.mtime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="embedded ? 210 : 260" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.dir" link type="primary" size="small" @click="downloadRow(row)">
              下载
            </el-button>
            <el-button v-else link type="primary" size="small" @click="enterDir(row.name)">
              打开
            </el-button>
            <el-button v-if="row.dir" link type="primary" size="small" @click="folderTransfer.openDownload(row)">下载</el-button>
            <el-button link size="small" @click="openRename(row)">重命名</el-button>
            <el-button link type="danger" size="small" @click="confirmDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-dialog v-model="showTransfers" title="文件传输 · 当前服务器" width="min(1080px, 94vw)" top="8vh" append-to-body destroy-on-close>
      <p v-if="transferHistoryWarning" role="alert" class="transfer-error">{{ transferHistoryWarning }}</p>
      <div class="transfer-heading"><strong>共 {{ visibleTransfers.length }} 项<span v-if="activeTransferCount"> · {{ activeTransferCount }} 项进行中</span></strong><el-button text size="small" @click="clearVisibleTransfers">清除已结束</el-button></div>
      <p class="transfer-hint">关闭弹窗不影响传输。{{ transferNotice }}</p>
      <el-table :data="visibleTransfers" size="small" max-height="55vh" empty-text="当前服务器暂无传输记录">
        <el-table-column prop="direction" label="方向" width="65" />
        <el-table-column label="账户" width="80"><template #default="{row}">#{{ row.accountId }}</template></el-table-column>
        <el-table-column prop="name" label="文件" min-width="150" show-overflow-tooltip />
        <el-table-column prop="path" label="远程目录" min-width="140" show-overflow-tooltip />
        <el-table-column label="进度" min-width="180"><template #default="{row}">
          <span v-if="row.restored" class="transfer-hint">历史记录 · 最后记录进度</span>
          <template v-if="!['已交给浏览器','交给浏览器中'].includes(row.status)">
            <el-progress :percentage="row.status === '已完成' ? 100 : Math.min(99, Math.floor(row.loaded / Math.max(row.total,1) * 100))" :status="['失败','无法恢复'].includes(row.status) ? 'exception' : row.status === '已完成' ? 'success' : undefined" />
            <span>{{ formatSize(row.loaded) }} / {{ formatSize(row.total) }}<template v-if="row.status === '传输中'"> · {{ formatSize(row.speed) }}/s</template></span>
          </template><span v-else>请在浏览器下载列表查看</span>
        </template></el-table-column>
        <el-table-column label="状态" min-width="130"><template #default="{row}">{{ row.status }}<span v-if="row.error" class="transfer-error">{{ row.error }}</span></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{row}"><el-button v-if="['等待中','传输中'].includes(row.status)" link type="danger" @click="row.cancel()">取消</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="showTransfers = false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="showMkdir" title="新建目录" width="400px" destroy-on-close @closed="mkdirName = ''"><el-scrollbar max-height="65vh">
      <el-input v-model="mkdirName" placeholder="目录名" maxlength="255" @keyup.enter="doMkdir" />
      </el-scrollbar><template #footer>
        <el-button @click="showMkdir = false">取消</el-button>
        <el-button type="primary" :loading="mkdirLoading" @click="doMkdir">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showRename" title="重命名" width="400px" destroy-on-close @closed="resetRename"><el-scrollbar max-height="65vh">
      <el-input v-model="renameName" placeholder="新名称" maxlength="255" @keyup.enter="doRename" />
      </el-scrollbar><template #footer>
        <el-button @click="showRename = false">取消</el-button>
        <el-button type="primary" :loading="renameLoading" @click="doRename">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onDeactivated, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Folder } from '@element-plus/icons-vue'
import { sftpDelete, sftpDownloadLarge, sftpList, sftpMkdir, sftpRename, sftpUploadLarge } from '@/api/sshConsole'
import { transfers, transferHistoryWarning, enqueueTransfer, delegateBrowserDownload, clearFinishedTransfers, assignTransferHost } from '@/composables/useTransfers'
import { fileLocation } from '@/composables/useWorkspaceContext'
import { isTransferActive, transferErrorMessage, transfersForServer } from '@/composables/transferHistory'
import FolderTransfer from './FolderTransfer.vue'
import { useAiChanges } from '@/composables/useAiChanges'
useAiChanges(/^(file_|ssh_command)/, () => { if (props.sessionToken) return refresh() })

const props = defineProps({
  accountId: { type: Number, default: null },
  hostId: { type: Number, default: null },
  active: { type: Boolean, default: true },
  embedded: { type: Boolean, default: false },
  sessionToken: { type: String, default: '' },
  defaultCwd: { type: String, default: '' },
  openingSession: { type: Boolean, default: false }
})
const transferNotice = '切换页面可继续传输。刷新或关闭页面会中断页面内传输，重开后仅保留最近 200 条历史记录和最后进度，无法自动续传。SSH 断开后需重新发起。浏览器接管的下载请在浏览器下载列表查看。'

const emit = defineEmits(['request-session', 'session-invalid'])

watch(() => [props.accountId, props.hostId], ([accountId, hostId]) => {
  // Old records predate host metadata; assign only when the account's actual server is known.
  assignTransferHost(accountId, hostId)
}, { immediate: true })
const visibleTransfers = computed(() => transfersForServer(transfers, props.hostId))
const showTransfers = ref(false)
const activeTransferCount = computed(() => visibleTransfers.value.filter(isTransferActive).length)
const transferButtonLabel = computed(() => activeTransferCount.value
  ? `传输列表（${activeTransferCount.value} 项进行中）`
  : `传输列表（${visibleTransfers.value.length}）`)
watch(() => props.active, active => { if (!active) showTransfers.value = false })
onDeactivated(() => { showTransfers.value = false })
function clearVisibleTransfers() {
  const visible = new Set(visibleTransfers.value)
  clearFinishedTransfers(task => visible.has(task))
}

const currentPath = ref('')
const pathInput = ref('')
const items = ref([])
const loading = ref(false)
const uploadInput = ref()
const folderTransfer = ref()
const showMkdir = ref(false)
const showDetails = ref(false)
const mkdirName = ref('')
const mkdirLoading = ref(false)
const showRename = ref(false)
const renameName = ref('')
const renameTarget = ref(null)
const renameLoading = ref(false)

const breadcrumbs = computed(() => {
  const p = currentPath.value
  if (!p || p === '/') return []
  return p.split('/').filter(Boolean)
})

let handledLocation=null,lastAccount=null,listRequest=0
watch(
  () => [props.sessionToken, props.active, fileLocation.value, props.accountId],
  ([tok, active, location, accountId]) => {
    ++listRequest // Invalidate a response even when the next view has no session.
    loading.value = false
    if (accountId !== lastAccount) {
      currentPath.value='';pathInput.value=props.defaultCwd||'';lastAccount=accountId
      handledLocation=null;items.value=[]
    }
    if (!active) return
    if (tok) {
      if(location&&location!==handledLocation&&location.accountId===props.accountId){currentPath.value=location.path;pathInput.value=location.path;handledLocation=location}
      refresh()
    } else {
      items.value = []
    }
  },
  { immediate: true }
)

function goRoot() {
  currentPath.value = props.defaultCwd || '/'
  pathInput.value = currentPath.value
  refresh()
}

function goToIndex(idx) {
  const segs = breadcrumbs.value.slice(0, idx + 1)
  currentPath.value = '/' + segs.join('/')
  pathInput.value = currentPath.value
  refresh()
}

function enterDir(name) {
  const base = currentPath.value || props.defaultCwd || '/'
  const next = base.endsWith('/') ? base + name : `${base}/${name}`
  currentPath.value = next
  pathInput.value = next
  refresh()
}

function navigateToPath() {
  currentPath.value = pathInput.value.trim()
  refresh()
}

function isSessionExpiredError(err) {
  const d = err?.response?.data
  const msg = [d?.externMessage, d?.message, err?.message].filter(Boolean).join(' ')
  return /会话不存在|已过期/.test(msg)
}

async function refresh() {
  if (!props.sessionToken) return
  const request = ++listRequest, token = props.sessionToken
  loading.value = true
  try {
    const pathArg = currentPath.value || pathInput.value || ''
    const data = await sftpList(token, pathArg)
    if (request !== listRequest || token !== props.sessionToken) return
    currentPath.value = data.path || currentPath.value
    pathInput.value = currentPath.value
    items.value = data.items || []
  } catch (err) {
    if (request !== listRequest || token !== props.sessionToken) return
    items.value = []
    if (isSessionExpiredError(err)) {
      ElMessage.warning('SSH 会话已失效，请重新建立文件会话或打开终端')
      emit('session-invalid')
    }
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function onRowDblClick(row) {
  if (row.dir) enterDir(row.name)
}

async function downloadRow(row) {
  const base = currentPath.value || '/'
  const remote = base.endsWith('/') ? base + row.name : `${base}/${row.name}`
  const token = props.sessionToken
  const accountId = props.accountId
  const hostId = props.hostId
  try {
    // Ask for permission in the original click event, before queueing any work.
    const handle = window.showSaveFilePicker ? await window.showSaveFilePicker({ suggestedName: row.name }) : null
    if (!handle) {
      delegateBrowserDownload({ name: row.name, path: base, total: row.size, accountId, hostId,
        run: () => sftpDownloadLarge(token, remote) })
      return
    }
    enqueueTransfer({ name: row.name, direction: '下载', path: base, total: row.size, accountId, hostId,
      run: options => sftpDownloadLarge(token, remote, { ...options, handle }) })
  } catch (error) {
    if (error.name !== 'AbortError') ElMessage.error(transferErrorMessage(error, '下载'))
  }
}

function uploadFiles(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = '' // File references belong to the queue and are released when each task ends.
  if (!props.sessionToken) return
  const dir = currentPath.value || pathInput.value || ''
  const token = props.sessionToken
  const accountId = props.accountId, hostId = props.hostId
  for (const file of files) enqueueTransfer({ name: file.name, direction: '上传', path: dir, total: file.size, accountId, hostId, run: async transferOptions => {
    await sftpUploadLarge(token, dir, file, transferOptions)
    if (props.sessionToken === token && currentPath.value === dir) void refresh()
  } })
}

function remotePathForRow(row) {
  const base = currentPath.value || '/'
  return base.endsWith('/') ? base + row.name : `${base}/${row.name}`
}

function openRename(row) {
  renameTarget.value = row
  renameName.value = row.name
  showRename.value = true
}

function resetRename() {
  renameTarget.value = null
  renameName.value = ''
}

async function doRename() {
  const row = renameTarget.value
  const name = renameName.value.trim()
  if (!row || !name || name === row.name) {
    showRename.value = false
    return
  }
  renameLoading.value = true
  try {
    await sftpRename(props.sessionToken, remotePathForRow(row), name)
    ElMessage.success('已重命名')
    showRename.value = false
    await refresh()
  } finally {
    renameLoading.value = false
  }
}

async function confirmDelete(row) {
  const tip = row.dir
    ? `删除空目录「${row.name}」？非空目录请先清空。`
    : `删除文件「${row.name}」？`
  try {
    await ElMessageBox.confirm(tip, '确认删除', { type: 'warning' })
    await sftpDelete(props.sessionToken, remotePathForRow(row))
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* cancel or error */
  }
}

async function doMkdir() {
  const name = mkdirName.value.trim()
  if (!name) {
    ElMessage.warning('请输入目录名')
    return
  }
  const base = currentPath.value || props.defaultCwd || '/'
  const remote = base.endsWith('/') ? base + name : `${base}/${name}`
  mkdirLoading.value = true
  try {
    await sftpMkdir(props.sessionToken, remote)
    ElMessage.success('目录已创建')
    showMkdir.value = false
    await refresh()
  } finally {
    mkdirLoading.value = false
  }
}

function formatSize(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  if (v < 1024 * 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)} MB`
  return `${(v / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function formatMtime(sec) {
  const s = Number(sec) || 0
  if (s <= 0) return '—'
  return new Date(s * 1000).toLocaleString()
}
</script>

<style scoped>
.ssh-file-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  gap: 8px;
  overflow: hidden;
}
.no-session {
  padding: 24px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
  margin-top: 8px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}
.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.path-input {
  width: 220px;
}
.upload-drop {
  flex-shrink: 0;
}
.upload-drop :deep(.el-upload-dragger) {
  padding: 8px 0;
}
.upload-ico {
  font-size: 28px;
  color: var(--el-color-primary);
}
.file-table {
  flex: 1;
  min-height: 0;
}
.embedded { gap:4px; }
.embedded .toolbar { gap:4px; flex-shrink:0; }
.embedded .toolbar-actions { gap:4px; }
.embedded .toolbar-actions :deep(.el-button + .el-button) { margin-left:0; }
.embedded .path-input { width:170px; }
.embedded .file-table { min-height:70px; }
.embedded .no-session { padding:0; overflow:auto; }
.embedded .no-session :deep(.el-empty) { padding:4px; }
.embedded .no-session :deep(.el-empty__image) { display:none; }
.transfer-heading{display:flex;align-items:center;justify-content:space-between;gap:6px;flex-wrap:wrap}.transfer-server-label{font-size:12px;color:var(--el-text-color-secondary)}.transfer-hint{font-size:12px;color:var(--el-text-color-secondary);margin:4px 0 8px}.transfer-error{display:block;color:var(--el-color-danger);overflow-wrap:anywhere}
.row-ico {
  vertical-align: middle;
  margin-right: 4px;
}
.perm-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
</style>
