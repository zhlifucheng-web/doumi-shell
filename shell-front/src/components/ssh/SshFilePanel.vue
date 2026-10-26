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
        <div class="toolbar-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>
              <a href="#" @click.prevent="goRoot">根</a>
            </el-breadcrumb-item>
            <el-breadcrumb-item v-for="(seg, idx) in breadcrumbs" :key="idx">
              <a href="#" @click.prevent="goToIndex(idx)">{{ seg }}</a>
            </el-breadcrumb-item>
          </el-breadcrumb>
          <div class="quick-bookmarks">
            <button v-if="canGoParent" type="button" class="bookmark-pill is-parent-btn" title="返回上一级目录" @click="goParent">.. 上一级</button>
            <button type="button" class="bookmark-pill" title="前往家目录" @click="navigateToQuickPath('~')">~ 家目录</button>
            <button type="button" class="bookmark-pill" title="前往根目录" @click="navigateToQuickPath('/')">/ 根</button>
            <button type="button" class="bookmark-pill" title="前往日志目录" @click="navigateToQuickPath('/var/log')">/var/log</button>
            <button type="button" class="bookmark-pill" title="前往配置目录" @click="navigateToQuickPath('/etc')">/etc</button>
            <button type="button" class="bookmark-pill" title="前往临时目录" @click="navigateToQuickPath('/tmp')">/tmp</button>
          </div>
          <span v-if="items.length" class="items-count-pill" :title="`当前目录共 ${items.length} 项`">
            共 {{ items.length }} 项<template v-if="filterKeyword && sortedItems.length !== items.length"> (匹配 {{ sortedItems.length }})</template>
          </span>
        </div>
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
          <el-dropdown trigger="click" @command="handleSortCommand">
            <el-button size="small" :title="`当前排序: ${currentSortLabel}`">
              <el-icon class="el-icon--left"><Sort /></el-icon>
              <span>{{ currentSortLabel }}</span>
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="name:ascending" :class="{ 'is-active': sortProp === 'name' && sortOrder === 'ascending' }">文件名（A → Z 顺序）</el-dropdown-item>
                <el-dropdown-item command="name:descending" :class="{ 'is-active': sortProp === 'name' && sortOrder === 'descending' }">文件名（Z → A 倒序）</el-dropdown-item>
                <el-dropdown-item divided command="mtime:descending" :class="{ 'is-active': sortProp === 'mtime' && sortOrder === 'descending' }">修改时间（最新在前 倒序）</el-dropdown-item>
                <el-dropdown-item command="mtime:ascending" :class="{ 'is-active': sortProp === 'mtime' && sortOrder === 'ascending' }">修改时间（最早在前 顺序）</el-dropdown-item>
                <el-dropdown-item divided command="createTime:descending" :class="{ 'is-active': sortProp === 'createTime' && sortOrder === 'descending' }">新增时间（最新在前 倒序）</el-dropdown-item>
                <el-dropdown-item command="createTime:ascending" :class="{ 'is-active': sortProp === 'createTime' && sortOrder === 'ascending' }">新增时间（最早在前 顺序）</el-dropdown-item>
                <el-dropdown-item divided command="size:descending" :class="{ 'is-active': sortProp === 'size' && sortOrder === 'descending' }">大小（由大到小 倒序）</el-dropdown-item>
                <el-dropdown-item command="size:ascending" :class="{ 'is-active': sortProp === 'size' && sortOrder === 'ascending' }">大小（由小到大 顺序）</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-input
            v-model="filterKeyword"
            size="small"
            class="filter-input"
            placeholder="过滤文件名..."
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button size="small" :type="activeTransferCount ? 'primary' : 'default'" plain @click="showTransfers = true">{{ transferButtonLabel }}</el-button>
          <el-button v-if="embedded" size="small" :aria-pressed="showDetails" @click="showDetails=!showDetails">{{ showDetails ? '精简列表' : '详细信息' }}</el-button>
          <el-button size="small" type="primary" @click="showMkdir = true">新建目录</el-button>
          <input ref="uploadInput" type="file" hidden multiple @change="uploadFiles" />
          <el-button size="small" type="success" @click="uploadInput.click()">上传文件</el-button>
          <FolderTransfer ref="folderTransfer" :session-token="sessionToken" :path="currentPath" @uploaded="refresh" />
        </div>
      </div>

      <div
        class="file-table-container"
        :class="{ 'is-dragover': isDragOver }"
        @dragover.prevent="isDragOver = true"
        @dragleave.prevent="isDragOver = false"
        @drop.prevent="onDropFiles"
      >
        <div v-if="isDragOver" class="drop-upload-overlay">
          <el-icon class="drop-upload-ico"><Upload /></el-icon>
          <span>松开鼠标立即上传到当前目录 ({{ currentPath }})</span>
        </div>

        <SshVirtualTable
          :items="sortedItems"
          :loading="loading"
          :embedded="embedded"
          :show-details="showDetails"
          :sort-prop="sortProp"
          :sort-order="sortOrder"
          :current-path="loadedPath || currentPath"
          :empty-description="filterKeyword ? '未匹配到相关文件' : '当前目录为空'"
          @sort-change="onSortChange"
          @row-dblclick="onRowDblClick"
          @edit-file="openFileEditor"
          @force-edit="openFullEditor"
          @view-standalone="openStandaloneViewer"
          @download="downloadRow"
          @open="enterDir"
          @folder-download="folderTransfer?.openDownload($event)"
          @rename="openRename"
          @delete="confirmDelete"
        />
      </div>
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

    <SshFileEditorDialog
      v-model="showEditor"
      :file="editorFile"
      :dir="loadedPath || currentPath || defaultCwd || '/'"
      :session-token="sessionToken"
      @saved="refresh"
      @switch-to-stream="openLargeFileViewer"
    />

    <SshLargeFileViewerDialog
      v-model="showLargeViewer"
      :file="largeViewerFile"
      :dir="loadedPath || currentPath || defaultCwd || '/'"
      :session-token="sessionToken"
      @switch-to-editor="openFullEditor"
    />
  </div>
</template>

<script setup>
import { computed, onDeactivated, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Document, Folder, Search, Sort, Upload } from '@element-plus/icons-vue'
import { sftpDelete, sftpDownloadLarge, sftpList, sftpMkdir, sftpRename, sftpUploadLarge } from '@/api/sshConsole'
import { useTransfers } from '@/composables/useTransfers'
import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'
import { isTransferActive, transferErrorMessage, transfersForServer } from '@/composables/transferHistory'
import FolderTransfer from './FolderTransfer.vue'
import SshVirtualTable from './SshVirtualTable.vue'
import SshFileEditorDialog from './SshFileEditorDialog.vue'
import SshLargeFileViewerDialog from './SshLargeFileViewerDialog.vue'
import { isDefaultEditableFile, isBinaryFile, isTextFile } from '@/utils/fileTypeHelper'
import { sortFileList } from '@/utils/fileSorter'
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
const { transfers, transferHistoryWarning, enqueueTransfer, delegateBrowserDownload, clearFinishedTransfers, assignTransferHost } = useTransfers()
const { fileLocation } = storeToRefs(useWorkspaceStore())
const transferNotice = '切换页面可继续传输。刷新或关闭页面会中断页面内传输，重开后仅保留最近 200 条历史记录和最后进度，无法自动续传。SSH 断开后需重新发起。浏览器接管的下载请在浏览器下载列表查看。'

const emit = defineEmits(['request-session', 'session-invalid'])

watch(() => [props.accountId, props.hostId], ([accountId, hostId]) => {
  // Old records predate host metadata; assign only when the account's actual server is known.
  assignTransferHost(accountId, hostId)
}, { immediate: true })
const visibleTransfers = computed(() => transfersForServer(transfers?.value || transfers, props.hostId))
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
const loadedPath = ref('')
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
const isDragOver = ref(false)
const showEditor = ref(false)
const editorFile = ref(null)
const showLargeViewer = ref(false)
const largeViewerFile = ref(null)
const sortProp = ref(localStorage.getItem('ssh_file_sort_prop') || 'name')
const sortOrder = ref(localStorage.getItem('ssh_file_sort_order') || 'ascending')
const filterKeyword = ref('')

const currentSortLabel = computed(() => {
  const isAsc = sortOrder.value === 'ascending'
  switch (sortProp.value) {
    case 'name': return isAsc ? '文件名 (A-Z)' : '文件名 (Z-A)'
    case 'mtime': return isAsc ? '修改时间 (旧→新)' : '修改时间 (新→旧)'
    case 'createTime': return isAsc ? '新增时间 (旧→新)' : '新增时间 (新→旧)'
    case 'size': return isAsc ? '大小 (小→大)' : '大小 (大→小)'
    default: return '排序'
  }
})

function handleSortCommand(command) {
  if (!command) return
  const [prop, order] = command.split(':')
  onSortChange({ prop, order })
}

function onSortChange({ prop, order }) {
  sortProp.value = prop
  sortOrder.value = order
  try {
    localStorage.setItem('ssh_file_sort_prop', prop)
    localStorage.setItem('ssh_file_sort_order', order)
  } catch {
    /* ignore storage quota / sandbox */
  }
}

const sortedItems = computed(() => {
  let list = items.value || []
  const kw = filterKeyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(item => String(item.name || '').toLowerCase().includes(kw))
  }
  return sortFileList(list, { prop: sortProp.value, order: sortOrder.value })
})

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
      currentPath.value = ''
      pathInput.value = props.defaultCwd || ''
      loadedPath.value = ''
      lastAccount = accountId
      handledLocation = null
      items.value = []
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

const canGoParent = computed(() => {
  const p = (loadedPath.value || currentPath.value || '').trim()
  if (!p || p === '/') return false
  return true
})

function goParent() {
  if (!canGoParent.value) return
  const p = (loadedPath.value || currentPath.value || '/').replace(/\/+$/, '')
  const lastSlash = p.lastIndexOf('/')
  const parent = lastSlash <= 0 ? '/' : p.substring(0, lastSlash)
  currentPath.value = parent
  pathInput.value = parent
  refresh()
}

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

function enterDir(target) {
  const name = (typeof target === 'object' && target !== null ? target.name : target)?.trim()
  if (!name) return

  // Base path is the directory currently loaded and displayed in the table
  const base = loadedPath.value || currentPath.value || props.defaultCwd || '/'
  const next = name.startsWith('/')
    ? name
    : base.endsWith('/') ? `${base}${name}` : `${base}/${name}`

  // Guard against redundant duplicate navigation while a request is already loading
  if (loading.value && currentPath.value === next) {
    return
  }

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
    loadedPath.value = data.path || pathArg
    currentPath.value = loadedPath.value
    pathInput.value = loadedPath.value
    items.value = data.items || []
  } catch (err) {
    if (request !== listRequest || token !== props.sessionToken) return
    items.value = []
    if (isSessionExpiredError(err)) {
      // 页面刷新/终端重连时旧 token 会被服务端主动清理；终端 ready
      // 后会自动提供新 token，不在这个过渡窗口弹出全局告警。
      emit('session-invalid')
    } else {
      const errMsg = err?.response?.data?.externMessage || err?.response?.data?.message || err?.message || '读取远程目录失败'
      ElMessage.error(errMsg)
    }
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function onRowDblClick(row) {
  if (row.dir) {
    enterDir(row.name)
  } else if (row.size > 5 * 1024 * 1024 && !isBinaryFile(row.name)) {
    // Large text / log files (> 5MB): open with dynamic chunk streaming viewer
    openLargeFileViewer(row)
  } else if (isDefaultEditableFile(row.name, row.size)) {
    openFileEditor(row)
  } else {
    downloadRow(row)
  }
}

function openLargeFileViewer(row) {
  if (!row || row.dir) return
  largeViewerFile.value = row
  showLargeViewer.value = true
}

const router = useRouter()

function openStandaloneViewer(row) {
  if (!row || row.dir || !props.sessionToken) return
  const base = loadedPath.value || currentPath.value || props.defaultCwd || '/'
  const filePath = row.path || (base.endsWith('/') ? `${base}${row.name}` : `${base}/${row.name}`)

  const routeData = router.resolve({
    path: '/file-viewer',
    query: {
      token: props.sessionToken,
      path: filePath,
      name: row.name,
      size: row.size
    }
  })

  const width = Math.min(1280, Math.floor(window.screen.availWidth * 0.88))
  const height = Math.min(860, Math.floor(window.screen.availHeight * 0.88))
  const left = Math.max(0, Math.floor((window.screen.availWidth - width) / 2))
  const top = Math.max(0, Math.floor((window.screen.availHeight - height) / 2))

  window.open(
    routeData.href,
    `ssh_viewer_${Date.now()}`,
    `width=${width},height=${height},left=${left},top=${top},menubar=no,toolbar=no,location=no,status=no,resizable=yes,scrollbars=yes`
  )
}

async function openFullEditor(row) {
  if (!row || row.dir) return
  if (row.size > 50 * 1024 * 1024) {
    ElMessage.warning(`文件大小 (${formatSize(row.size)}) 超过全量在线编辑上限 (50 MB)，请使用查看功能或下载后编辑`)
    return
  }

  if (isBinaryFile(row.name)) {
    try {
      await ElMessageBox.confirm(
        `文件「${row.name}」疑似二进制文件，在线编辑可能出现乱码或损坏。是否仍要强制打开？`,
        '文件格式提示',
        {
          confirmButtonText: '强制打开',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  } else if (row.size > 5 * 1024 * 1024) {
    try {
      await ElMessageBox.confirm(
        `文件「${row.name}」大小约为 ${formatSize(row.size)}，全量加载将下载完整文件至浏览器排版，建议使用直接查看。是否仍要全量打开？`,
        '全量加载提示',
        {
          confirmButtonText: '全量打开',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  }

  editorFile.value = row
  showEditor.value = true
}

async function openFileEditor(row) {
  if (!row || row.dir) return
  // If large text file (> 5MB), route to large file dynamic streaming viewer directly
  if (row.size > 5 * 1024 * 1024 && !isBinaryFile(row.name)) {
    openLargeFileViewer(row)
    return
  }
  await openFullEditor(row)
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

function navigateToQuickPath(target) {
  pathInput.value = target
  void navigateToPath()
}

function onDropFiles(event) {
  isDragOver.value = false
  const files = Array.from(event.dataTransfer?.files || [])
  if (!files.length || !props.sessionToken) return
  const dir = currentPath.value || pathInput.value || ''
  const token = props.sessionToken
  const accountId = props.accountId, hostId = props.hostId
  for (const file of files) enqueueTransfer({
    name: file.name,
    direction: '上传',
    path: dir,
    total: file.size,
    accountId,
    hostId,
    run: async transferOptions => {
      await sftpUploadLarge(token, dir, file, transferOptions)
      if (props.sessionToken === token && currentPath.value === dir) void refresh()
    }
  })
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
.filter-input {
  width: 140px;
}
.items-count-pill {
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
  padding: 2px 7px;
  border-radius: 4px;
  background: var(--el-fill-color-light, rgba(0, 0, 0, 0.04));
  border: 1px solid var(--el-border-color-lighter, #e4e7ed);
  font-family: var(--font-mono, monospace);
  white-space: nowrap;
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
.embedded .filter-input { width:110px; }
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
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.quick-bookmarks {
  display: flex;
  align-items: center;
  gap: 4px;
}
.bookmark-pill {
  padding: 1px 6px;
  border-radius: var(--ws-radius-xs, 4px);
  background: var(--el-fill-color-light, rgba(0, 0, 0, 0.04));
  border: 1px solid var(--el-border-color-lighter);
  color: var(--ws-text-muted, #64748b);
  font-size: 11px;
  font-family: var(--font-mono, monospace);
  cursor: pointer;
  transition: all 0.15s ease;
}
.bookmark-pill.is-parent-btn {
  font-weight: 600;
  color: var(--el-color-primary, #6366f1);
  background: var(--ws-fill-active, rgba(99, 102, 241, 0.08));
  border-color: rgba(99, 102, 241, 0.25);
}
html.dark .bookmark-pill {
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.08);
  color: #cbd5e1;
}
.bookmark-pill:hover {
  background: var(--ws-fill-active, rgba(99, 102, 241, 0.15));
  border-color: var(--el-color-primary, #6366f1);
  color: var(--el-color-primary, #6366f1);
}
.file-table-container {
  flex: 1;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
}
.drop-upload-overlay {
  position: absolute;
  inset: 0;
  z-index: 50;
  background: rgba(99, 102, 241, 0.15);
  backdrop-filter: blur(4px);
  border: 2px dashed var(--el-color-primary, #6366f1);
  border-radius: var(--ws-radius-md, 8px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--el-color-primary, #6366f1);
  font-weight: 600;
  font-size: 13px;
  pointer-events: none;
}
.drop-upload-ico {
  font-size: 32px;
}
.file-name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.file-type-badge {
  font-size: 9px;
  font-weight: 700;
  padding: 1px 4px;
  border-radius: 3px;
  letter-spacing: 0.5px;
  font-family: var(--font-mono, monospace);
}
.badge-dir {
  background: rgba(99, 102, 241, 0.15);
  color: #6366f1;
}
.badge-log {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
}
.badge-sh {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
}
.badge-cfg {
  background: rgba(6, 182, 212, 0.15);
  color: #06b6d4;
}
.badge-zip {
  background: rgba(168, 85, 247, 0.15);
  color: #a855f7;
}
.row-ico.is-dir {
  color: var(--el-color-primary, #6366f1);
}
.file-name-text {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
.file-name-text.is-dir {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
</style>
