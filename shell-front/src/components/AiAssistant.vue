<template>
  <el-dialog :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)" title="AI 助手" width="960px" class="ai-dialog" :close-on-click-modal="false">
    <div class="ai-toolbar">
      <el-select v-model="providerId" placeholder="选择 AI 厂商" :disabled="!!conversationId" aria-label="AI 厂商"><el-option v-for="p in providers" :key="p.id" :label="`${p.name} · ${p.model}`" :value="p.id" /></el-select>
      <el-button @click="settingsVisible = true">厂商配置</el-button>
      <el-button :disabled="busy || uploading" @click="newChat">新对话</el-button>
      <el-tag :type="busy ? 'warning' : 'info'">{{ stateLabel }}</el-tag>
    </div>
    <div class="ai-context">当前页面：{{ pageName }}<span v-if="managedServer"> · {{ managedServer.username ? managedServer.username + '@' : '' }}{{ managedServer.name }}</span> · 支持五个业务页面的操作</div>
    <div ref="history" class="ai-history" aria-live="polite">
      <div v-if="!events.length" class="ai-empty">
        <strong>描述你的任务，AI 会调用实际业务接口完成操作</strong>
        <p>例如：查看当前服务器资源、排查进程、编写定时脚本，或分析配置文件并按目录导入。</p>
        <p>执行命令、修改文件、导入和删除前，请核对待确认操作。</p>
      </div>
      <article v-for="entry in events" :key="entry.sequence" class="ai-message" :class="`ai-message--${entry.role}`">
        <div class="ai-message-title">{{ roleName(entry) }}<el-tag v-if="entry.role === 'tool'" size="small" :type="entry.state === 'error' ? 'danger' : entry.state === 'pending' ? 'warning' : 'info'">{{ toolState(entry.state) }}</el-tag></div>
        <template v-if="entry.role === 'tool'">
          <p v-if="entry.description">{{ entry.description }}</p>
          <details :open="entry.state === 'pending'"><summary>操作参数与结果</summary><pre>{{ pretty(entry.arguments) }}</pre><pre v-if="entry.text">{{ entry.text }}</pre></details>
          <div v-if="entry.state === 'pending'" class="ai-actions"><el-button type="primary" size="small" :disabled="busy || submitting" @click="approve([entry.actionId], true)">确认执行</el-button><el-button size="small" :disabled="busy || submitting" @click="approve([entry.actionId], false)">拒绝</el-button><small>10 分钟内有效</small></div>
          <a v-if="downloadUrl(entry.downloadUrl)" :href="downloadUrl(entry.downloadUrl)" target="_blank" rel="noopener">下载 {{ entry.filename || '文件' }}</a>
        </template>
        <pre v-else>{{ entry.text }}</pre>
        <div v-if="entry.attachments?.length" class="ai-attachments"><el-tag v-for="a in entry.attachments" :key="a.id" type="info">{{ a.name }}</el-tag></div>
      </article>
      <div v-if="busy" class="ai-working">{{ state === 'stopping' ? '正在停止后续调用，等待当前请求结束…' : 'AI 正在处理，请稍候…' }}</div>
    </div>
    <div v-if="error" class="ai-error" role="alert">{{ error }}<el-button v-if="conversationId" link @click="poll">重新连接</el-button><el-button link :disabled="busy" @click="newChat">清除并新建</el-button></div>
    <div v-if="pending.length > 1" class="ai-actions"><span>{{ pending.length }} 项待确认操作</span><el-button size="small" :disabled="busy || submitting" @click="approve(pending.map(p => p.actionId), true)">全部确认</el-button><el-button size="small" :disabled="busy || submitting" @click="approve(pending.map(p => p.actionId), false)">全部拒绝</el-button></div>
    <div class="ai-attachments"><el-tag v-for="a in attachments" :key="a.id" closable @close="attachments = attachments.filter(item => item.id !== a.id)">{{ a.name }}</el-tag></div>
    <el-input v-model="text" type="textarea" :rows="3" :maxlength="24000" placeholder="输入任务，Ctrl + Enter 发送；可附加配置文件或文件夹" :disabled="busy || !!pending.length" @keydown.ctrl.enter.prevent="send" />
    <div class="ai-compose-actions">
      <div><el-button :disabled="busy || !!pending.length || uploading" @click="fileInput.click()">添加文件</el-button><el-button :disabled="busy || !!pending.length || uploading" @click="folderInput.click()">添加文件夹</el-button><small v-if="uploading">上传 {{ uploadProgress }}%</small><small v-else>单文件 20 MB / 每次对话 50 MB</small></div>
      <el-button v-if="busy" type="warning" :disabled="submitting || state === 'stopping'" @click="stop">停止</el-button><el-button v-else type="primary" :loading="submitting" :disabled="!providerId || !text.trim() || !!pending.length || uploading" @click="send">发送</el-button>
    </div>
    <p class="ai-footnote">消息、所选附件及工具结果会发送给你配置的厂商。API Key 在服务端加密保存。对话保留至刷新页面或服务重启；闲置 2 小时后过期。停止不会撤回已启动的操作。</p>
    <input ref="fileInput" type="file" multiple hidden @change="attach"><input ref="folderInput" type="file" multiple webkitdirectory hidden @change="attach">
  </el-dialog>
  <el-dialog v-model="settingsVisible" title="AI 厂商配置" width="640px" append-to-body :close-on-click-modal="false">
    <el-select v-model="editingId" placeholder="新增厂商" clearable @change="editProvider"><el-option v-for="p in providers" :key="p.id" :label="p.name" :value="p.id" /></el-select>
    <el-form label-position="top" class="ai-provider-form" @submit.prevent="saveProvider">
      <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" placeholder="厂商或本地服务名称" /></el-form-item>
      <el-form-item label="Base URL"><el-input v-model="form.baseUrl" placeholder="https://厂商地址/v1" /><small>填写兼容 Chat Completions 的完整 API 基础地址（通常包含 /v1）。</small></el-form-item>
      <el-form-item label="模型"><el-input v-model="form.model" placeholder="支持 tools / function calling 的模型名称" /></el-form-item>
      <el-form-item label="API Key"><el-input v-model="form.apiKey" type="password" show-password autocomplete="new-password" :placeholder="form.hasApiKey ? '已保存，留空保留原密钥' : '本地无鉴权服务可留空'" /><el-checkbox v-if="form.hasApiKey" v-model="form.clearApiKey">清除原密钥</el-checkbox></el-form-item>
      <div class="ai-actions"><el-button type="primary" :loading="saving" @click="saveProvider">保存</el-button><el-button :disabled="!editingId || saving" :loading="testing" @click="testProvider">测试已保存配置</el-button><el-popconfirm title="删除此厂商配置？" @confirm="deleteProvider"><template #reference><el-button type="danger" plain :disabled="!editingId || saving || busy">删除</el-button></template></el-popconfirm></div>
      <p v-if="settingsMessage" class="ai-settings-message" role="status">{{ settingsMessage }}</p>
    </el-form>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { aiApi } from '@/api/ai'
import { API_BASE } from '@/utils/apiHandler'
import { managedServer, fileLocation } from '@/composables/useWorkspaceContext'

const props = defineProps({ modelValue: Boolean })
defineEmits(['update:modelValue'])
const route = useRoute()
const providers = ref([]), providerId = ref(''), conversationId = ref(''), events = ref([]), pending = ref([])
const state = ref('idle'), busy = ref(false), text = ref(''), error = ref(''), submitting = ref(false)
const attachments = ref([]), uploading = ref(false), uploadProgress = ref(0)
const history = ref(), fileInput = ref(), folderInput = ref()
const settingsVisible = ref(false), editingId = ref(''), saving = ref(false), testing = ref(false), settingsMessage = ref('')
const emptyForm = () => ({ name: '', baseUrl: '', model: '', apiKey: '', clearApiKey: false, hasApiKey: false })
const form = ref(emptyForm())
let cursor = 0, revision = 0, timer, polling = false, disposed = false
const pageName = computed(() => ({ '/terminal': '终端与监控', '/scripts': '脚本与定时任务', '/processes': '进程', '/disk-usage': '存储管理', '/data-management': '数据管理', '/about': '关于' })[route.path] || route.path)
const stateLabel = computed(() => ({ idle: '就绪', running: '处理中', awaiting_confirmation: '等待确认', stopping: '停止中', error: '处理失败' })[state.value] || state.value)
const pretty = value => JSON.stringify(value || {}, null, 2)
const roleName = entry => entry.role === 'tool' ? entry.name : ({ user: '你', assistant: 'AI 助手', error: '错误', notice: '提示' })[entry.role] || entry.role
const toolState = value => ({ running: '调用中', pending: '待确认', done: '已完成', cancelled: '已取消', error: '失败' })[value] || value
function downloadUrl(url) { return /^\/api\/ssh-console\/ai\/downloads\/[a-f0-9-]{36}$/.test(url || '') ? API_BASE + url : '' }
function report(e) { error.value = e.message || '请求失败，请检查网络连接' }
async function loadProviders() {
  try { providers.value = await aiApi.providers(); if (!providerId.value || !providers.value.some(p => p.id === providerId.value)) providerId.value = providers.value[0]?.id || '' }
  catch (e) { report(e) }
}
watch(() => props.modelValue, async open => { if (open) { await loadProviders(); if (conversationId.value) await poll(); await scrollBottom() } })
function schedule() { clearTimeout(timer); if (!disposed && conversationId.value) timer = setTimeout(poll, busy.value ? 1000 : 10000) }
function apply(snapshot) {
  if (snapshot.id !== conversationId.value) return
  const nearBottom = !history.value || history.value.scrollHeight - history.value.scrollTop - history.value.clientHeight < 100
  for (const entry of snapshot.events || []) {
    if (entry.sequence <= cursor) continue
    const index = entry.callId ? events.value.findIndex(e => e.callId === entry.callId) : -1
    if (index >= 0) events.value[index] = { ...events.value[index], ...entry }
    else events.value.push(entry)
  }
  cursor = Math.max(cursor, snapshot.cursor || 0)
  busy.value = snapshot.running; state.value = snapshot.state; pending.value = snapshot.pending || []
  if (snapshot.revision > revision) {
    revision = snapshot.revision
    const changed = (snapshot.events || []).filter(e => e.changed).map(e => e.name)
    if (changed.some(name => /^(group_|host_(create|update|move|delete)|account_(create|update|delete)|configuration_import|cleanup_execute)/.test(name))) window.dispatchEvent(new Event('doumi-configuration-imported'))
    window.dispatchEvent(new CustomEvent('doumi-ai-changed', { detail: { operations: changed } }))
  }
  if (nearBottom) scrollBottom()
  schedule()
}
async function poll() {
  if (polling || !conversationId.value) return
  polling = true
  try { apply(await aiApi.status(conversationId.value, cursor)); error.value = '' }
  catch (e) { report(e); clearTimeout(timer); if (/会话不存在|会话.*过期/.test(e.message || '')) { busy.value = false; state.value = 'error'; pending.value = [] } }
  finally { polling = false }
}
async function ensureChat() {
  if (!providerId.value) throw new Error('请先配置并选择 AI 厂商')
  if (!conversationId.value) { const chat = await aiApi.create(providerId.value); conversationId.value = chat.id; cursor = 0; revision = 0 }
}
async function send() {
  if (!text.value.trim() || busy.value || submitting.value || pending.value.length || uploading.value) return
  submitting.value = true; error.value = ''
  try {
    await ensureChat()
    const snapshot = await aiApi.send(conversationId.value, { text: text.value, attachmentIds: attachments.value.map(a => a.id), workspace: { page: route.path, server: managedServer.value, fileLocation: fileLocation.value } })
    text.value = ''; attachments.value = []; apply(snapshot)
  } catch (e) { report(e) } finally { submitting.value = false }
}
async function approve(ids, approved) {
  submitting.value = true; error.value = ''
  try { apply(await aiApi.approve(conversationId.value, ids, approved)) } catch (e) { report(e) } finally { submitting.value = false }
}
async function stop() { try { apply(await aiApi.stop(conversationId.value)) } catch (e) { report(e) } }
async function newChat() {
  if (busy.value || uploading.value || submitting.value) return
  try { if (conversationId.value) await aiApi.remove(conversationId.value) }
  catch (e) { report(e); if (!/不存在|过期/.test(e.message || '')) return }
  clearTimeout(timer); conversationId.value = ''; events.value = []; pending.value = []; attachments.value = []; cursor = 0; revision = 0; state.value = 'idle'; error.value = ''
}
async function attach(event) {
  const selected = Array.from(event.target.files || []); event.target.value = ''
  if (!selected.length) return
  if (selected.length > 1000 || selected.some(f => f.size > 20 * 1024 * 1024) || selected.reduce((n,f) => n + f.size, 0) > 50 * 1024 * 1024) { error.value = '附件超过限制：最多 1000 个，单文件 20 MB，单会话 50 MB'; return }
  uploading.value = true; uploadProgress.value = 0; error.value = ''
  try {
    await ensureChat()
    const body = new FormData(); selected.forEach(f => body.append('files', f)); body.append('paths', JSON.stringify(selected.map(f => f.webkitRelativePath || f.name)))
    const added = await aiApi.attach(conversationId.value, body, p => { uploadProgress.value = p.total ? Math.round(p.loaded * 100 / p.total) : 0 })
    attachments.value.push(...added)
  } catch (e) { report(e) } finally { uploading.value = false }
}
function editProvider() { const p = providers.value.find(p => p.id === editingId.value); form.value = p ? { ...emptyForm(), ...p } : emptyForm(); settingsMessage.value = '' }
async function saveProvider() {
  saving.value = true; settingsMessage.value = ''
  try {
    const saved = await aiApi.saveProvider({ id: editingId.value || undefined, name: form.value.name, baseUrl: form.value.baseUrl, model: form.value.model, apiKey: form.value.apiKey, clearApiKey: form.value.clearApiKey })
    await loadProviders(); editingId.value = saved.id; if (!conversationId.value) providerId.value = saved.id; editProvider(); settingsMessage.value = '配置已保存'
  } catch (e) { settingsMessage.value = e.message } finally { saving.value = false }
}
async function testProvider() { testing.value = true; settingsMessage.value = ''; try { const result = await aiApi.testProvider(editingId.value); settingsMessage.value = `连接成功：${result.reply || 'OK'}（工具调用能力请在对话中验证）` } catch (e) { settingsMessage.value = e.message } finally { testing.value = false } }
async function deleteProvider() {
  try { if (conversationId.value && editingId.value === providerId.value) { await newChat(); if (conversationId.value) return } await aiApi.deleteProvider(editingId.value); editingId.value = ''; editProvider(); await loadProviders() }
  catch (e) { settingsMessage.value = e.message }
}
async function scrollBottom() { await nextTick(); if (history.value) history.value.scrollTop = history.value.scrollHeight }
onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
</script>

<style scoped>
.ai-toolbar,.ai-actions,.ai-compose-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.ai-toolbar .el-select{flex:1;min-width:180px}.ai-context,.ai-footnote,small{font-size:12px;color:var(--el-text-color-secondary)}.ai-context{padding:10px 0}.ai-history{height:min(48vh,480px);min-height:160px;overflow:auto;border:1px solid var(--el-border-color);border-radius:8px;padding:14px;background:var(--el-bg-color)}.ai-empty{padding:35px 10px;color:var(--el-text-color-secondary);line-height:1.8}.ai-message{margin:0 0 14px;padding:12px;border-radius:8px;background:var(--el-fill-color-light);overflow-wrap:anywhere}.ai-message--user{background:var(--el-color-primary-light-9);margin-left:28px}.ai-message--error,.ai-error{color:var(--el-color-danger)}.ai-message-title{font-weight:600;display:flex;gap:8px;align-items:center;margin-bottom:7px}.ai-message pre{font:inherit;white-space:pre-wrap;word-break:break-word;margin:8px 0;line-height:1.6}.ai-message--tool pre{font-family:monospace;font-size:12px;max-height:240px;overflow:auto}.ai-message summary{cursor:pointer;color:var(--el-text-color-secondary)}.ai-message a{color:var(--el-color-primary)}.ai-actions{margin:10px 0}.ai-attachments{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0;max-height:90px;overflow:auto}.ai-attachments .el-tag{max-width:100%;height:auto;white-space:normal;overflow-wrap:anywhere}.ai-compose-actions{justify-content:space-between;margin-top:10px}.ai-compose-actions>div{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.ai-compose-actions .el-button+.el-button{margin-left:0}.ai-footnote{line-height:1.6;margin-bottom:0}.ai-provider-form{margin-top:16px}.ai-provider-form small{line-height:1.5}.ai-working{padding:8px;color:var(--el-color-primary)}.ai-settings-message{white-space:pre-wrap;overflow-wrap:anywhere}.ai-error{padding:8px 0}
</style>
<style>.ai-dialog{max-width:calc(100vw - 24px);margin-top:5vh!important}.ai-dialog .el-dialog__body{padding-top:10px;padding-bottom:16px}@media(max-width:600px){.ai-dialog{margin-top:12px!important}.ai-dialog .el-dialog__body{padding:10px}.ai-dialog .ai-history{height:36vh}.ai-dialog .ai-context{font-size:11px}}</style>
