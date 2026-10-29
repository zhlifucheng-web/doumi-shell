<template>
  <el-dialog :model-value="true" title="复制到机器" class="remote-copy-dialog" top="6vh" width="min(580px, 94vw)" append-to-body @close="$emit('close')">
    <el-form label-position="top" @submit.prevent="submit">
      <div class="copy-source font-mono-code" aria-label="源路径">{{ source.sourcePath }}</div>
      <el-form-item label="目标账户" required>
        <el-select v-model="form.targetAccountId" filterable :loading="loadingAccounts" placeholder="选择接收方账户" class="copy-full-width" :disabled="loadingAccounts">
          <el-option v-for="account in accounts" :key="account.id" :value="account.id" :label="accountLabel(account)" />
        </el-select>
        <div v-if="accountsError" class="copy-error" role="alert">{{ accountsError }} <el-button link type="primary" @click="loadAccounts">重试</el-button></div>
        <div v-else-if="!loadingAccounts && !accounts.length" class="copy-hint">暂无可用账户，请先在服务器管理中配置并启用目标机器的 SSH 账户。</div>
      </el-form-item>
      <el-form-item label="目标目录" required>
        <div class="copy-path-input">
          <el-input v-model="form.targetPath" placeholder="/srv/data" autocomplete="off" />
          <el-button :disabled="!form.targetAccountId" @click="openFolders">选择目录</el-button>
        </div>
      </el-form-item>
      <div class="copy-options">
        <el-form-item label="传输方式">
          <el-radio-group v-model="form.mode">
            <el-radio-button label="RELAY">本机转发</el-radio-button>
            <el-tooltip content="源机器需已配置目标账户的免交互 SSH 认证及 known_hosts。" placement="top">
              <el-radio-button label="DIRECT">直连</el-radio-button>
            </el-tooltip>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="接收方属主">
          <el-autocomplete v-model="form.owner" :fetch-suggestions="suggestOwners" clearable :placeholder="loadingOwners ? '正在读取用户…' : '默认'" class="copy-full-width" />
          <div v-if="ownerError" class="copy-error" role="alert">{{ ownerError }} <el-button link type="primary" @click="loadOwners">重试</el-button></div>
        </el-form-item>
      </div>
      <el-checkbox v-model="form.overwrite">覆盖同名文件</el-checkbox>
      <p v-if="formError" class="copy-error" role="alert">{{ formError }}</p>
    </el-form>
    <template #footer>
      <el-button @click="$emit('close')">取消</el-button>
      <el-button type="primary" :disabled="loadingAccounts || !accounts.length" @click="submit">开始复制</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="folderDialog" title="选择目标目录" width="min(580px, 94vw)" append-to-body @close="closeFolders">
    <div class="copy-folder-toolbar">
      <el-button :disabled="folderLoading || !folderPath || folderPath === '/'" @click="browseFolders(parentDirectory(folderPath))">上级目录</el-button>
      <el-button :disabled="folderLoading" @click="browseFolders('')">用户主目录</el-button>
      <el-button :disabled="folderLoading" @click="browseFolders('/')">根目录</el-button>
    </div>
    <div class="copy-folder-path font-mono-code">{{ folderPath }}</div>
    <div v-if="folderError" class="copy-error" role="alert">{{ folderError }}</div>
    <el-table :data="folders" height="300" v-loading="folderLoading" empty-text="没有子目录" @row-click="enterFolder">
      <el-table-column prop="name" label="文件夹" show-overflow-tooltip />
      <el-table-column width="70"><template #default="{ row }"><el-button link type="primary" :disabled="folderLoading" @click.stop="enterFolder(row)">进入</el-button></template></el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="closeFolders">取消</el-button>
      <el-button type="primary" :disabled="folderLoading || !!folderError || !folderPath" @click="chooseFolder">选择当前目录</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listScheduleAccounts, sftpCopy, sftpCopyTargetUsers, sftpCopyTargetDirectories } from '@/api/sshConsole'
import { useTransfers } from '@/composables/useTransfers'

const props = defineProps({ source: { type: Object, required: true } })
const emit = defineEmits(['close', 'queued'])
const { enqueueTransfer } = useTransfers()
const form = reactive({ targetAccountId: null, targetPath: '', mode: 'RELAY', owner: '', overwrite: false })
const accounts = ref([]), loadingAccounts = ref(false), accountsError = ref(''), formError = ref('')
const owners = ref([]), loadingOwners = ref(false), ownerError = ref('')
const folderDialog = ref(false), folderLoading = ref(false), folderPath = ref(''), folderError = ref(''), folders = ref([])
let accountRequest = 0
let ownerRequest = 0, folderRequest = 0
const parentDirectory = path => path?.slice(0, path.lastIndexOf('/')) || '/'
const suggestOwners = (query, callback) => callback(owners.value.filter(owner => owner.toLowerCase().includes(query.toLowerCase())).map(value => ({ value })))
const accountLabel = account => `${account.hostName} / ${account.username}`
async function loadAccounts() {
  const sequence = ++accountRequest
  loadingAccounts.value = true; accountsError.value = ''
  try { const result = await listScheduleAccounts(); if (sequence === accountRequest) accounts.value = result || [] }
  catch (error) { if (sequence === accountRequest) accountsError.value = error.message || '无法加载目标账户' }
  finally { if (sequence === accountRequest) loadingAccounts.value = false }
}
watch(() => props.source, source => {
  form.targetAccountId = null
  form.targetPath = parentDirectory(source.sourcePath)
  formError.value = ''; void loadAccounts()
}, { immediate: true })
watch(() => [props.source.sessionToken, form.targetAccountId], () => {
  closeFolders(); folders.value = []; folderPath.value = ''; folderError.value = ''
  form.owner = ''; void loadOwners()
})
async function loadOwners() {
  const sequence = ++ownerRequest, token = props.source.sessionToken, accountId = form.targetAccountId
  owners.value = []; ownerError.value = ''; loadingOwners.value = !!accountId
  if (!accountId) return
  try {
    const result = await sftpCopyTargetUsers(token, accountId)
    if (sequence === ownerRequest) owners.value = result?.users || []
  } catch (error) {
    if (sequence === ownerRequest) ownerError.value = error.message || '无法读取接收方用户，可手动输入'
  } finally { if (sequence === ownerRequest) loadingOwners.value = false }
}
function closeFolders() { folderDialog.value = false; folderRequest++; folderLoading.value = false }
async function openFolders() {
  if (!form.targetAccountId) return
  folderDialog.value = true
  await browseFolders(form.targetPath.trim().startsWith('/') ? form.targetPath.trim() : '')
}
async function browseFolders(path) {
  const sequence = ++folderRequest, token = props.source.sessionToken, accountId = form.targetAccountId
  folderLoading.value = true; folderError.value = ''; folders.value = []; folderPath.value = ''
  try {
    const result = await sftpCopyTargetDirectories(token, accountId, path)
    if (sequence !== folderRequest) return
    folderPath.value = result.path; folders.value = result.items || []
  } catch (error) { if (sequence === folderRequest) folderError.value = error.message || '无法读取接收方目录' }
  finally { if (sequence === folderRequest) folderLoading.value = false }
}
function enterFolder(row) {
  if (!folderLoading.value) return browseFolders(`${folderPath.value.replace(/\/$/, '')}/${row.name}`)
}
function chooseFolder() {
  if (folderLoading.value || folderError.value || !folderPath.value) return
  form.targetPath = folderPath.value; closeFolders()
}
onBeforeUnmount(() => { accountRequest++; ownerRequest++; folderRequest++ })
function submit() {
  formError.value = ''
  const source = { ...props.source }, targetPath = form.targetPath.trim(), owner = form.owner.trim()
  if (!source.sessionToken || !source.sourcePath?.startsWith('/')) { formError.value = '源会话或路径无效，请重新打开复制窗口'; return }
  const target = accounts.value.find(account => account.id === form.targetAccountId)
  if (!target) { formError.value = '请选择目标机器及 SSH 账户'; return }
  if (!targetPath.startsWith('/') || targetPath.includes('\0') || targetPath.split('/').includes('..')) { formError.value = '目标目录须为绝对路径，且不能包含 ..'; return }
  if (owner && !/^[a-zA-Z0-9_.][a-zA-Z0-9_.$-]*$/.test(owner)) { formError.value = '请填写有效的接收方用户名'; return }
  const snapshot = { sessionToken: source.sessionToken, sourcePath: source.sourcePath, targetAccountId: target.id,
    targetPath, owner, mode: form.mode, overwrite: form.overwrite }
  enqueueTransfer({ name: `${source.name} → ${target.hostName} / ${target.username}`, direction: '复制', path: targetPath,
    total: 0, accountId: source.accountId, hostId: source.hostId,
    run: options => sftpCopy(snapshot, options) })
  ElMessage.success('已加入复制队列')
  emit('queued'); emit('close')
}
</script>

<style scoped>
.copy-full-width { width: 100%; }
.copy-path-input { display: flex; gap: 8px; width: 100%; }
.copy-path-input .el-input { flex: 1; min-width: 0; }
.copy-folder-toolbar { display: flex; gap: 8px; flex-wrap: wrap; }
.copy-folder-toolbar .el-button + .el-button { margin-left: 0; }
.copy-folder-path { overflow-wrap: anywhere; margin: 12px 0; }
.copy-source { overflow-wrap: anywhere; color: var(--el-text-color-regular); background: var(--el-fill-color-light); border-radius: 6px; padding: 12px; margin-bottom: 20px; }
.copy-options { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 520px) { .copy-options { grid-template-columns: 1fr; gap: 0; } }
.copy-hint { width: 100%; margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.copy-error { color: var(--el-color-danger); margin-top: 8px; overflow-wrap: anywhere; }
</style>

<style>
.remote-copy-dialog.el-dialog { display: flex; flex-direction: column; max-height: 88vh; }
.remote-copy-dialog .el-dialog__body { min-height: 0; overflow-y: auto; }
.remote-copy-dialog .el-dialog__header,
.remote-copy-dialog .el-dialog__footer { flex-shrink: 0; }
</style>
