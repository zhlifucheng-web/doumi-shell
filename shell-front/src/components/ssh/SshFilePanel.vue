<template>
  <div class="ssh-file-panel">
    <div v-if="!sessionToken" class="no-session">
      <el-empty description="尚未建立 SSH 会话">
        <el-button type="primary" :loading="openingSession" @click="$emit('request-session')">
          建立文件会话
        </el-button>
      </el-empty>
      <p class="hint">也可先在「终端」Tab 连接，连接成功后会自动复用 sessionToken。</p>
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
          <el-button size="small" type="primary" @click="showMkdir = true">新建目录</el-button>
          <el-upload
            class="upload-btn"
            :show-file-list="false"
            multiple
            :http-request="uploadRequest"
          >
            <el-button size="small" type="success">上传文件</el-button>
          </el-upload>
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
        <el-table-column prop="owner" label="属主" width="88" show-overflow-tooltip />
        <el-table-column prop="group" label="属组" width="88" show-overflow-tooltip />
        <el-table-column prop="permissions" label="权限" width="108">
          <template #default="{ row }">
            <span class="perm-cell">{{ row.permissions || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="修改时间" width="168">
          <template #default="{ row }">
            {{ formatMtime(row.mtime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
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
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Folder } from '@element-plus/icons-vue'
import { sftpDelete, sftpDownload, sftpList, sftpMkdir, sftpRename, sftpUpload } from '@/api/sshConsole'
import { fileLocation } from '@/composables/useWorkspaceContext'
import FolderTransfer from './FolderTransfer.vue'

const props = defineProps({
  accountId: { type: Number, default: null },
  active: { type: Boolean, default: true },
  sessionToken: { type: String, default: '' },
  defaultCwd: { type: String, default: '' },
  openingSession: { type: Boolean, default: false }
})

const emit = defineEmits(['request-session', 'session-invalid'])

const currentPath = ref('')
const pathInput = ref('')
const items = ref([])
const loading = ref(false)
const folderTransfer = ref()
const showMkdir = ref(false)
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

let handledLocation=null,lastToken=''
watch(
  () => [props.sessionToken, props.active, fileLocation.value],
  ([tok, active, location]) => {
    if (!active) return
    if (tok) {
      if(tok!==lastToken){currentPath.value='';pathInput.value=props.defaultCwd||'';lastToken=tok}
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
  loading.value = true
  try {
    const pathArg = currentPath.value || pathInput.value || ''
    const data = await sftpList(props.sessionToken, pathArg)
    currentPath.value = data.path || currentPath.value
    pathInput.value = currentPath.value
    items.value = data.items || []
  } catch (err) {
    items.value = []
    if (isSessionExpiredError(err)) {
      ElMessage.warning('SSH 会话已失效，请重新建立文件会话或打开终端')
      emit('session-invalid')
    }
  } finally {
    loading.value = false
  }
}

function onRowDblClick(row) {
  if (row.dir) enterDir(row.name)
}

async function downloadRow(row) {
  const base = currentPath.value || '/'
  const remote = base.endsWith('/') ? base + row.name : `${base}/${row.name}`
  try {
    await sftpDownload(props.sessionToken, remote, row.name)
    ElMessage.success('下载已开始')
  } catch {
    /* apiHandler 已提示 */
  }
}

/** Element Plus 自定义上传：拖拽与点击均通过 options.file 拿到原始 File */
async function uploadRequest(options) {
  const file = options?.file
  if (!file || !props.sessionToken) {
    options?.onError?.(new Error('请选择要上传的文件'))
    return
  }
  const dir = currentPath.value || pathInput.value || ''
  try {
    await sftpUpload(props.sessionToken, dir, file)
    ElMessage.success(`已上传 ${file.name}`)
    options?.onSuccess?.({})
    await refresh()
  } catch (err) {
    options?.onError?.(err)
  }
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
.row-ico {
  vertical-align: middle;
  margin-right: 4px;
}
.perm-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
</style>
