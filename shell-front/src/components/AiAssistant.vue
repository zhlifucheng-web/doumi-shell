<template>
  <Teleport to="body">
    <Transition name="ai-panel">
      <aside v-if="modelValue" ref="panelElement" class="ai-panel" :class="{ 'ai-panel--fullscreen': fullscreen }" :style="[backgroundStyle, fullscreen ? {} : panelStyle]" aria-label="AI 助手侧边栏">
        <header class="ai-panel-header" @pointerdown="startDrag">
          <div class="ai-panel-title"><strong><el-icon><MagicStick /></el-icon> AI 助手</strong><small>Ctrl + Shift + K · 拖动标题移动窗口</small></div>
          <el-tag size="small" :type="busy ? 'warning' : 'info'">{{ stateLabel }}</el-tag>
          <el-tooltip :content="fullscreen ? '退出全屏' : '全屏'"><el-button text circle :aria-label="fullscreen ? '退出全屏' : '全屏'" @click="fullscreen = !fullscreen"><el-icon><FullScreen /></el-icon></el-button></el-tooltip>
          <el-button text circle aria-label="关闭 AI 助手" @click="$emit('update:modelValue', false)"><el-icon><Close /></el-icon></el-button>
        </header>
    <div class="ai-toolbar">
      <el-select v-model="providerId" placeholder="选择已配置模型" :disabled="busy || submitting || !!pending.length" aria-label="AI 模型" @change="changeProvider"><el-option v-for="p in providers" :key="p.id" :label="`${p.name} · ${p.model}`" :value="p.id" /></el-select>
      <el-button size="small" @click="showHistory">历史</el-button>
      <el-button size="small" :disabled="busy || uploading || submitting || !!pending.length" @click="newChat">新对话</el-button>
      <el-button size="small" :aria-expanded="controlsVisible" @click="controlsVisible = !controlsVisible">设置</el-button>
    </div>
    <div v-if="controlsVisible" class="ai-controls">
    <div class="ai-settings-actions"><el-button size="small" @click="settingsVisible = true">厂商配置</el-button><el-switch v-model="autoOpen" size="small" active-text="启动时打开" aria-label="启动时自动打开 AI 助手" @change="saveAutoOpen" /></div>
    <div class="ai-permission-row">
      <span>操作授权</span>
      <el-select v-model="authorizationMode" aria-label="AI 操作授权模式" :disabled="busy || submitting || !!pending.length" @change="changeAuthorizationMode">
        <el-option label="仅写操作询问" value="ask" />
        <el-option label="全部权限（自动执行）" value="auto" />
      </el-select>
      <small>{{ authorizationMode === 'auto' ? '本对话的读写操作均可执行，写操作不再逐项确认' : '读取操作直接执行；命令、创建、修改、删除等写操作逐项确认' }}</small>
    </div>
    </div>
    <div class="ai-host-row">
      <span>操作主机</span>
      <el-select v-model="selectedAccountId" class="ai-host-select" :loading="hostLoading" :disabled="busy || submitting || uploading || !!pending.length" placeholder="跟随当前页面主机" aria-label="AI 操作主机" @change="onHostChange">
        <el-option label="跟随当前页面主机" value="" />
        <el-option v-for="host in hostAccounts" :key="host.accountId" :label="host.label" :value="String(host.accountId)" />
      </el-select>
      <el-button title="刷新主机列表" aria-label="刷新主机列表" :loading="hostLoading" @click="loadHostAccounts">↻</el-button>
    </div>
    <div class="ai-context"><span :title="selectedServer ? `${selectedServer.username || ''}@${selectedServer.address}:${selectedServer.port}` : '未选择主机'">{{ pageName }} · {{ selectedServer ? `${selectedServer.username ? selectedServer.username + '@' : ''}${selectedServer.name}${selectedServer.terminalLabel ? ` · ${selectedServer.terminalLabel}` : ''}` : '未选择主机' }}</span><el-tooltip :content="authorizationMode === 'auto' ? '当前对话的读写操作自动执行' : '写操作执行前需要确认'"><el-button link size="small" @click="controlsVisible = !controlsVisible">{{ authorizationMode === 'auto' ? '全部权限' : '写操作询问' }}</el-button></el-tooltip></div>
    <div class="ai-conversation">
    <div ref="history" class="ai-history" aria-live="polite" @scroll="onHistoryScroll">
      <div v-if="!events.length" class="ai-empty">
        <strong>描述你的任务，AI 会调用实际业务接口完成操作</strong>
        <p>例如：查看当前服务器资源、排查进程、编写定时脚本，或分析配置文件并按目录导入。</p>
        <p v-if="authorizationMode === 'ask'">读取操作直接执行；执行命令、创建、修改、删除和导入前会请求确认。</p>
        <p v-else>当前对话的读写操作均可执行，写操作会自动执行。</p>
        <div class="ai-suggestions"><button v-for="suggestion in suggestions" :key="suggestion" type="button" @click="text = suggestion">{{ suggestion }}</button></div>
      </div>
      <article v-for="entry in events" :key="entry.messageId || entry.callId || entry.sequence" class="ai-message" :class="`ai-message--${entry.role}`">
        <div class="ai-message-title"><span>{{ roleName(entry) }}</span><el-tag v-if="entry.role === 'tool'" size="small" :type="entry.state === 'error' ? 'danger' : entry.state === 'pending' ? 'warning' : 'info'">{{ toolState(entry.state) }}</el-tag><time v-if="entry.timestamp" class="ai-message-time" :datetime="new Date(entry.timestamp).toISOString()">{{ formatMessageTime(entry.timestamp) }}</time><el-button v-if="entry.text" class="ai-copy-message" link size="small" aria-label="复制消息" title="复制消息" @click="copyMessage(entry.text)"><el-icon><CopyDocument /></el-icon></el-button></div>
        <template v-if="entry.role === 'tool'">
          <p v-if="entry.description">{{ entry.description }}</p>
          <details :open="entry.state === 'pending'"><summary>操作参数与结果</summary><pre>{{ pretty(entry.arguments) }}</pre><pre v-if="entry.text">{{ entry.text }}</pre></details>
          <div v-if="entry.state === 'pending'" class="ai-actions"><el-button type="primary" size="small" :disabled="busy || submitting" @click="approve([entry.actionId], true)">确认执行</el-button><el-button size="small" :disabled="busy || submitting" @click="approve([entry.actionId], false)">拒绝</el-button><small>10 分钟内有效</small></div>
          <a v-if="downloadUrl(entry.downloadUrl)" :href="downloadUrl(entry.downloadUrl)" target="_blank" rel="noopener">下载 {{ entry.filename || '文件' }}</a>
        </template>
        <template v-else-if="entry.role === 'assistant'"><AiMarkdown :text="entry.text" /><span v-if="entry.state === 'streaming'" class="ai-stream-status">正在输出…</span><span v-else-if="entry.state === 'interrupted'" class="ai-stream-interrupted">回答已中断，以上内容不完整</span></template>
        <pre v-else>{{ entry.text }}</pre>
        <div v-if="entry.attachments?.length" class="ai-attachments"><el-tag v-for="a in entry.attachments" :key="a.id" type="info">{{ a.name }}</el-tag></div>
      </article>
      <div v-if="busy" class="ai-working">{{ state === 'stopping' ? '正在停止后续调用，等待当前请求结束…' : 'AI 正在处理，请稍候…' }}</div>
    </div>
    <el-button v-if="awayFromBottom" class="ai-jump-latest" round size="small" @click="scrollBottom">↓ 回到最新消息</el-button>
    </div>
    <div v-if="error" class="ai-error" role="alert">{{ error }}<el-button v-if="conversationId" link @click="poll">重新连接</el-button><el-button link :disabled="busy" @click="newChat">清除并新建</el-button></div>
    <div v-if="pending.length > 1" class="ai-actions"><span>{{ pending.length }} 项待确认操作</span><el-button size="small" :disabled="busy || submitting" @click="approve(pending.map(p => p.actionId), true)">全部确认</el-button><el-button size="small" :disabled="busy || submitting" @click="approve(pending.map(p => p.actionId), false)">全部拒绝</el-button></div>
    <div class="ai-composer">
    <div v-if="attachments.length" class="ai-attachments"><el-tag v-for="a in attachments" :key="a.id" closable @close="attachments = attachments.filter(item => item.id !== a.id)">{{ a.name }}</el-tag></div>
    <el-input v-model="text" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :maxlength="24000" placeholder="描述任务… Enter 发送，Ctrl + Enter 换行" :disabled="busy || !!pending.length" @keydown.enter="onComposerEnter" />
    <div class="ai-compose-actions">
      <div><el-button size="small" :disabled="busy || !!pending.length || uploading" @click="fileInput.click()">添加文件</el-button><el-button size="small" :disabled="busy || !!pending.length || uploading" @click="folderInput.click()">文件夹</el-button><small v-if="uploading">上传 {{ uploadProgress }}%</small></div>
      <el-button v-if="busy" type="warning" :disabled="submitting || state === 'stopping'" @click="stop">停止</el-button><el-button v-else type="primary" :loading="submitting" :disabled="!providerId || !text.trim() || !!pending.length || uploading" @click="send">发送</el-button>
    </div>
    </div>
    <details class="ai-help"><summary>使用说明 · 单文件 20 MB / 每次对话 50 MB</summary><p class="ai-footnote">消息、所选附件及工具结果会发送给你配置的厂商。附件仅为临时数据，过期或服务重启后需重新选择；API Key 在服务端加密保存。历史会话可在历史会话列表中恢复或在数据管理中清理。停止不会撤回已启动的操作。</p></details>
    <input ref="fileInput" type="file" multiple hidden @change="attach"><input ref="folderInput" type="file" multiple webkitdirectory hidden @change="attach">
    <div v-if="!fullscreen" class="ai-resize-handle" role="separator" aria-label="调整 AI 助手大小" aria-orientation="diagonal" :aria-valuenow="Math.round(panelLayout.width)" tabindex="0" @pointerdown.stop.prevent="startResize" @keydown="resizeByKeyboard"><el-icon><Rank /></el-icon></div>
      </aside>
    </Transition>
  </Teleport>
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
  <el-dialog v-model="historyVisible" title="历史会话" width="min(760px, calc(100vw - 32px))" append-to-body>
    <div class="ai-history-toolbar"><el-input v-model="historySearch" clearable placeholder="搜索会话或服务器" /><el-button :loading="historyLoading" @click="loadHistory">刷新</el-button></div>
    <el-table :data="filteredHistory" v-loading="historyLoading" max-height="55vh" empty-text="暂无历史会话">
      <el-table-column label="会话" min-width="220"><template #default="scope"><strong>{{ scope.row.title || '新会话' }}</strong><small class="ai-history-server">{{ historyServerLabel(scope.row) }}</small></template></el-table-column>
      <el-table-column label="最新消息" width="170"><template #default="scope">{{ formatTime(scope.row.latestMessageAt || scope.row.updatedAt) }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="scope">{{ stateLabelFor(scope.row.state) }}</template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" :disabled="busy || submitting || uploading || !!pending.length" @click="openHistory(scope.row)">打开</el-button></template></el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, CopyDocument, FullScreen, MagicStick, Rank } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { aiApi } from '@/api/ai'
import { API_BASE } from '@/utils/apiHandler'
import { useWorkspaceStore } from '@/stores/workspace'
import AiMarkdown from '@/components/AiMarkdown.vue'
import { fetchTreeChildren } from '@/api/sshConsole'
import { notifyMonitorConfigChanged } from '@/utils/monitorConfigStore'
import { useBackground } from '@/composables/useBackground'

const props = defineProps({ modelValue: Boolean })
defineEmits(['update:modelValue'])
const route = useRoute()
const { managedServer, fileLocation } = storeToRefs(useWorkspaceStore())
const { style: backgroundStyle } = useBackground()
const providers = ref([]), providerId = ref(''), previousProviderId = ref(''), conversationId = ref(''), events = ref([]), pending = ref([])
const state = ref('idle'), busy = ref(false), text = ref(''), error = ref(''), submitting = ref(false)
const attachments = ref([]), uploading = ref(false), uploadProgress = ref(0)
const authorizationMode = ref('auto'), previousAuthorizationMode = ref('auto')
const fullscreen = ref(false), hostAccounts = ref([]), hostLoading = ref(false), selectedAccountId = ref(''), selectedServer = ref(managedServer.value), followsCurrentHost = ref(true)
const history = ref(), fileInput = ref(), folderInput = ref(), panelElement = ref()
const settingsVisible = ref(false), editingId = ref(''), saving = ref(false), testing = ref(false), settingsMessage = ref('')
const autoOpen = ref(readAutoOpen())
const historyVisible = ref(false), historyLoading = ref(false), historyRows = ref([]), historySearch = ref('')
const controlsVisible = ref(false), awayFromBottom = ref(false)
const suggestions = ['查看当前服务器资源状态', '排查 CPU 和内存占用较高的进程', '查看并整理当前账户的监控面板']
function onHistoryScroll() {
  const element = history.value
  awayFromBottom.value = !!element && element.scrollHeight - element.scrollTop - element.clientHeight > 100
}
async function copyMessage(value) {
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(value)
    else {
      const field = document.createElement('textarea')
      field.value = value; field.style.position = 'fixed'; field.style.opacity = '0'
      panelElement.value.appendChild(field)
      try { field.select(); if (!document.execCommand('copy')) throw new Error('copy failed') }
      finally { field.remove() }
    }
    ElMessage.success('已复制消息')
  } catch { ElMessage.error('复制失败，请选择文本后复制') }
}
const filteredHistory = computed(() => {
  const query = historySearch.value.trim().toLowerCase()
  return historyRows.value.filter(row => !query || `${row.title || ''} ${historyServerLabel(row)}`.toLowerCase().includes(query))
})
const emptyForm = () => ({ name: '', baseUrl: '', model: '', apiKey: '', clearApiKey: false, hasApiKey: false })
const form = ref(emptyForm())
let cursor = 0, revision = 0, snapshotVersion = 0, timer, polling = false, disposed = false, eventSource = null, suppressSnapshotEffects = false
const panelLayout = ref(readPanelLayout())
function aiSessionOwner(server) { return server?.accountId != null ? `account:${server.accountId}` : server?.hostId != null ? `host:${server.hostId}` : 'unassigned' }
let activeSessionOwner = aiSessionOwner(selectedServer.value)
let queuedServerSwitch = null
let pointerAction = null
function setActiveConversation(server,id) { return aiApi.setActive(server?.accountId,server?.accountId==null?server?.hostId:null,id||null) }
const AI_AUTO_OPEN_KEY = 'doumi-ai-auto-open'
function readAutoOpen() {
  try { return localStorage.getItem(AI_AUTO_OPEN_KEY) === 'true' } catch { return false }
}
function saveAutoOpen(value) {
  try { localStorage.setItem(AI_AUTO_OPEN_KEY, String(Boolean(value))) } catch { /* 偏好设置失败不影响助手使用 */ }
}
const panelStyle = computed(() => ({ left: `${panelLayout.value.x}px`, top: `${panelLayout.value.y}px`, width: `${panelLayout.value.width}px`, height: `${panelLayout.value.height}px` }))
function readPanelLayout() {
  const mobile = window.innerWidth <= 600
  const fallback = { x: 12, y: mobile ? 8 : 68, width: Math.min(500, window.innerWidth - (mobile ? 16 : 24)), height: mobile ? window.innerHeight - 16 : Math.min(760, window.innerHeight - 120) }
  try { return clampPanelLayout({ ...fallback, ...JSON.parse(localStorage.getItem('doumi-ai-panel-layout') || '{}') }) }
  catch { return clampPanelLayout(fallback) }
}
function clampPanelLayout(layout) {
  const maxWidth = Math.max(240, window.innerWidth - 16), maxHeight = Math.max(180, window.innerHeight - 16)
  const width = Math.min(maxWidth, Math.max(Math.min(window.innerWidth <= 600 ? 260 : 320, maxWidth), Number(layout.width) || 500))
  const height = Math.min(maxHeight, Math.max(Math.min(240, maxHeight), Number(layout.height) || maxHeight))
  return { x: Math.max(8, Math.min(window.innerWidth - width - 8, Number(layout.x) || 8)), y: Math.max(8, Math.min(window.innerHeight - height - 8, Number(layout.y) || 8)), width, height }
}
function savePanelLayout() {
  panelLayout.value = clampPanelLayout(panelLayout.value)
  try { localStorage.setItem('doumi-ai-panel-layout', JSON.stringify(panelLayout.value)) } catch { /* Layout remains usable for this session. */ }
}
function startDrag(event) {
  if (event.button !== 0 || event.target.closest('button')) return
  startPointer(event, 'drag')
}
function startResize(event) { startPointer(event, 'resize') }
function startPointer(event, type) {
  if (fullscreen.value || event.button !== 0) return
  event.preventDefault()
  pointerAction = { type, x: event.clientX, y: event.clientY, layout: { ...panelLayout.value } }
  window.addEventListener('pointermove', movePointer)
  window.addEventListener('pointerup', endPointer)
  window.addEventListener('pointercancel', endPointer)
}
function movePointer(event) {
  if (!pointerAction) return
  const { type, x, y, layout } = pointerAction, dx = event.clientX - x, dy = event.clientY - y
  panelLayout.value = type === 'drag'
    ? clampPanelLayout({ ...layout, x: layout.x + dx, y: layout.y + dy })
    : clampPanelLayout({ ...layout, width: layout.width + dx, height: layout.height + dy })
}
function endPointer() {
  if (!pointerAction) return
  pointerAction = null
  window.removeEventListener('pointermove', movePointer)
  window.removeEventListener('pointerup', endPointer)
  window.removeEventListener('pointercancel', endPointer)
  savePanelLayout()
}
function resizeByKeyboard(event) {
  const changes = { ArrowLeft: [-20, 0], ArrowRight: [20, 0], ArrowUp: [0, -20], ArrowDown: [0, 20] }[event.key]
  if (!changes) return
  event.preventDefault()
  panelLayout.value = clampPanelLayout({ ...panelLayout.value, width: panelLayout.value.width + changes[0], height: panelLayout.value.height + changes[1] })
  savePanelLayout()
}
function clampPanelOnViewportChange() { panelLayout.value = clampPanelLayout(panelLayout.value); savePanelLayout() }
onMounted(() => {
  window.addEventListener('resize', clampPanelOnViewportChange)
  if (props.modelValue) void restoreLatestServerConversation(selectedServer.value)
})
const pageName = computed(() => ({ '/terminal': '终端与监控', '/scripts': '脚本与定时任务', '/processes': '进程', '/disk-usage': '存储管理', '/data-management': '数据管理', '/about': '关于', '/help': '帮助' })[route.path] || route.path)
const stateLabel = computed(() => ({ idle: '就绪', running: '处理中', awaiting_confirmation: '等待确认', stopping: '停止中', error: '处理失败' })[state.value] || '就绪')
const pretty = value => JSON.stringify(value || {}, null, 2)
const roleName = entry => entry.role === 'tool' ? entry.name : ({ user: '你', assistant: 'AI 助手', error: '错误', notice: '提示' })[entry.role] || entry.role
const toolState = value => ({ running: '调用中', pending: '待确认', auto_authorized: '自动授权', done: '已完成', cancelled: '已取消', error: '失败' })[value] || value
function downloadUrl(url) { return /^\/api\/ssh-console\/ai\/downloads\/[a-f0-9-]{36}$/.test(url || '') ? API_BASE + url : '' }
function normalizedError(value) {
  const message = typeof value === 'string' ? value : value?.message
  return message && message !== 'undefined' && message !== 'null' ? message : ''
}
function report(e) { error.value = normalizedError(e) || '请求失败，请检查网络连接' }
async function loadProviders() {
  try { providers.value = await aiApi.providers(); if (!providerId.value || !providers.value.some(p => p.id === providerId.value)) providerId.value = providers.value[0]?.id || ''; previousProviderId.value = providerId.value }
  catch (e) { report(e) }
}
// AI 会话跟随前台终端切换。同一账户可以有多个终端标签，必须监听终端身份，
// 不能只监听 accountId；分组浏览和主机备注更新不应切换 AI 会话。
watch(() => {
  const server = managedServer.value
  return [server?.accountId ?? null, server?.tabId ?? null, server?.terminalId ?? null]
}, ([accountId]) => {
  if (!followsCurrentHost.value || accountId == null) return
  void switchServerConversation(managedServer.value)
})
watch([busy, submitting, uploading, () => pending.value.length], () => {
  if (!queuedServerSwitch || busy.value || submitting.value || uploading.value || pending.value.length) return
  const server = queuedServerSwitch
  queuedServerSwitch = null
  void switchServerConversation(server)
})
watch(() => props.modelValue, async open => { if (open) { await loadProviders(); if (!hostAccounts.value.length) await loadHostAccounts(); if (conversationId.value) { await poll(); connectEvents() } else await restoreLatestServerConversation(selectedServer.value); await scrollBottom() } })
async function loadHostAccounts() {
  hostLoading.value = true
  try {
    const accounts = []
    const groups = await fetchTreeChildren('ROOT')
    const visitGroup = async (group, parents = []) => {
      const path = [...parents, group.label]
      const children = await fetchTreeChildren('GROUP', group.id)
      for (const child of children) {
        if (child.nodeType === 'GROUP') await visitGroup(child, path)
        else if (child.nodeType === 'HOST') {
          const rows = await fetchTreeChildren('HOST', child.id)
          for (const account of rows) if (Number(account.extra?.enabled) !== 0) accounts.push({
            accountId: account.id, hostId: child.id, username: account.label,
            name: child.label, address: child.extra?.host || '', port: child.extra?.port || 22,
            label: `${path.join(' / ')} / ${child.label} (${child.extra?.endpoint || child.extra?.host}) · ${account.label}`
          })
        }
      }
    }
    for (const group of groups) await visitGroup(group)
    hostAccounts.value = accounts
    if (!followsCurrentHost.value && !hostAccounts.value.some(host => String(host.accountId) === selectedAccountId.value)) {
      selectedAccountId.value = ''; followsCurrentHost.value = true; void switchServerConversation(managedServer.value)
    }
  } catch (e) { report(e) } finally { hostLoading.value = false }
}
function onHostChange(value) {
  if (!value) { followsCurrentHost.value = true; void switchServerConversation(managedServer.value); return }
  const host = hostAccounts.value.find(item => String(item.accountId) === value)
  if (host) { followsCurrentHost.value = false; void switchServerConversation(host) }
}
async function switchServerConversation(server) {
  const nextOwner = aiSessionOwner(server)
  if (nextOwner === activeSessionOwner) { selectedServer.value = server; return }
  if (busy.value || submitting.value || uploading.value || pending.value.length) {
    queuedServerSwitch = server
    return
  }
  closeEvents()
  clearTimeout(timer)
  selectedServer.value = server
  activeSessionOwner = nextOwner
  queuedServerSwitch = null
  conversationId.value='';events.value=[];pending.value=[];attachments.value=[];cursor=0;revision=0;snapshotVersion=0
  await restoreLatestServerConversation(server)
}
async function restoreLatestServerConversation(server) {
  try {
    let latest = await aiApi.active(server?.accountId, server?.accountId == null ? server?.hostId : null)
    if (!latest?.id) latest = await aiApi.latest(server?.accountId, server?.accountId == null ? server?.hostId : null)
    if (!latest?.id || activeSessionOwner !== aiSessionOwner(server)) return
    await openConversation(latest.id)
    await setActiveConversation(server,latest.id)
  } catch (e) {
    const message = e?.message || ''
    if (/无效的响应格式|没有历史|暂无历史|未找到会话/.test(message)) error.value = ''
    else report(e)
  }
}
async function showHistory() {
  historyVisible.value = true
  await loadHistory()
}
async function loadHistory() {
  historyLoading.value = true
  try { historyRows.value = (await aiApi.history()).filter(row => row.title) }
  catch (e) { report(e) }
  finally { historyLoading.value = false }
}
function historyServerLabel(row) {
  const endpoint = row.serverAddress ? `${row.serverAddress}:${row.serverPort || 22}` : '未绑定服务器'
  return `${row.serverName || '服务器'} (${endpoint})${row.username ? ` · ${row.username}` : ''}`
}
function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
}
function formatMessageTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString()
}
function stateLabelFor(value) { return ({ idle: '已完成', running: '服务中断', stopping: '服务中断', awaiting_confirmation: '待确认', error: '中断/失败' })[value] || '已完成' }
async function openHistory(row) {
  if (busy.value || submitting.value || uploading.value || pending.value.length) return
  const server = { accountId: row.accountId ?? null, hostId: row.hostId ?? null, name: row.serverName || '', address: row.serverAddress || '', port: row.serverPort || 22, username: row.username || '' }
  if (row.accountId != null) { selectedAccountId.value = String(row.accountId); followsCurrentHost.value = false }
  else { selectedAccountId.value = ''; followsCurrentHost.value = true }
  await switchServerConversation(server)
  if (conversationId.value !== row.id) await openConversation(row.id)
  await setActiveConversation(server,row.id)
  historyVisible.value = false
  await scrollBottom()
}
async function openConversation(id) {
  closeEvents(); clearTimeout(timer)
  conversationId.value = id; events.value = []; pending.value = []; cursor = 0; revision = 0; snapshotVersion = 0
  suppressSnapshotEffects = true
  apply(await aiApi.status(id, 0)); connectEvents()
}
function schedule() { clearTimeout(timer); if (!disposed && conversationId.value && !eventSource) timer = setTimeout(poll, busy.value ? 250 : 10000) }
function closeEvents() { if (eventSource) eventSource.close(); eventSource = null }
function connectEvents() {
  if (eventSource || !conversationId.value || disposed || typeof EventSource === 'undefined') return
  const id = conversationId.value
  const source = new EventSource(aiApi.eventsUrl(id, cursor)); eventSource = source
  source.addEventListener('snapshot', event => {
    if (conversationId.value !== id || eventSource !== source) return
    try { apply(JSON.parse(event.data)); error.value = '' } catch { report(new Error('实时回复数据无效，已切换为轮询')); closeEvents(); schedule() }
  })
  source.addEventListener('expired', () => { if (eventSource !== source) return; closeEvents(); clearTimeout(timer); busy.value = false; state.value = 'error'; pending.value = []; report(new Error('AI 会话不存在或已过期，请开启新会话')) })
  source.onerror = () => {
    if (eventSource !== source) return
    closeEvents(); schedule()
  }
}
function apply(snapshot) {
  if (snapshot.id !== conversationId.value) return
  if (snapshot.providerId) { providerId.value = snapshot.providerId; previousProviderId.value = snapshot.providerId }
  if (snapshot.authorizationMode) { authorizationMode.value = snapshot.authorizationMode; previousAuthorizationMode.value = snapshot.authorizationMode }
  if (snapshot.snapshotVersion && snapshot.snapshotVersion < snapshotVersion) return
  snapshotVersion = snapshot.snapshotVersion || snapshotVersion
  const nearBottom = !history.value || history.value.scrollHeight - history.value.scrollTop - history.value.clientHeight < 100
  const suppressEffects = suppressSnapshotEffects
  let changedMonitorCardAccountId = null
  for (const entry of snapshot.events || []) {
    if (entry.sequence <= cursor) continue
    const index = events.value.findIndex(e => entry.callId ? e.callId === entry.callId : entry.messageId ? e.messageId === entry.messageId : false)
    if (index >= 0) events.value[index] = { ...events.value[index], ...entry }
    else events.value.push(entry)
    if (!suppressEffects && ['monitor_dashboard_update','monitor_card_create','monitor_card_update','monitor_card_delete'].includes(entry.name)
        && (entry.state === 'done' || (entry.state === 'auto_authorized' && entry.text))) {
      changedMonitorCardAccountId = entry.arguments?.accountId ?? entry.arguments?.accountID ?? null
    }
  }
  cursor = Math.max(cursor, snapshot.cursor || 0)
  if ((snapshot.cursor || 0) < cursor) return
  busy.value = snapshot.running; state.value = snapshot.state; pending.value = snapshot.pending || []
  if (snapshot.revision > revision) {
    revision = snapshot.revision
    const changed = (snapshot.events || []).filter(e => e.changed).map(e => e.name)
    if (!suppressEffects) {
      if (changed.some(name => /^(group_|host_(create|update|move|delete)|account_(create|update|delete)|configuration_import|cleanup_execute)/.test(name))) window.dispatchEvent(new Event('doumi-configuration-imported'))
      window.dispatchEvent(new CustomEvent('doumi-ai-changed', { detail: { operations: changed } }))
    }
  }
  // Refresh from persisted configuration when a newly received tool event completes.
  // Restoring an initial history snapshot never triggers this notification.
  if (changedMonitorCardAccountId != null) notifyMonitorConfigChanged(changedMonitorCardAccountId)
  suppressSnapshotEffects = false
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
  if (!conversationId.value) { const chat = await aiApi.create(providerId.value, authorizationMode.value, selectedServer.value); conversationId.value = chat.id; previousProviderId.value = providerId.value; previousAuthorizationMode.value = authorizationMode.value; cursor = 0; revision = 0; snapshotVersion = 0; suppressSnapshotEffects = false; await setActiveConversation(selectedServer.value,chat.id); connectEvents() }
}
async function send() {
  if (!text.value.trim() || busy.value || submitting.value || pending.value.length || uploading.value) return
  submitting.value = true; error.value = ''
  try {
    await ensureChat()
    const server = selectedServer.value
    const selectedFile = server?.accountId === fileLocation.value?.accountId ? fileLocation.value : null
    const snapshot = await aiApi.send(conversationId.value, { text: text.value, attachmentIds: attachments.value.map(a => a.id), workspace: { page: route.path, server, fileLocation: selectedFile } })
    text.value = ''; attachments.value = []; apply(snapshot)
  } catch (e) { report(e) } finally { submitting.value = false }
}
function onComposerEnter(event) {
  if (event.isComposing || event.keyCode === 229) return
  if (event.ctrlKey && !event.altKey && !event.metaKey) {
    event.preventDefault()
    const input = event.target
    const start = input.selectionStart, end = input.selectionEnd
    input.setRangeText('\n', start, end, 'end')
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return
  }
  if (event.shiftKey || event.altKey || event.metaKey) return
  event.preventDefault()
  send()
}
async function changeProvider(value) {
  const previous = previousProviderId.value
  if (!conversationId.value || value === previous) { previousProviderId.value = value; return }
  submitting.value = true
  try { apply(await aiApi.setProvider(conversationId.value, value)) }
  catch (e) { providerId.value = previous; report(e) }
  finally { submitting.value = false }
}
async function changeAuthorizationMode(value) {
  const previous = previousAuthorizationMode.value
  if (value === 'auto') {
    try {
      await ElMessageBox.confirm('全部权限仅对当前对话生效。AI 可直接执行读写操作，包括命令、文件修改、删除和导入；已开始的操作无法由切回模式撤销。', '启用全部权限', { type: 'warning', confirmButtonText: '启用全部权限', cancelButtonText: '保持仅写操作询问' })
    } catch { authorizationMode.value = previous; return }
  }
  submitting.value = true
  try {
    if (conversationId.value) apply(await aiApi.setAuthorization(conversationId.value, value))
    previousAuthorizationMode.value = value
  } catch (e) { authorizationMode.value = previous; report(e) }
  finally { submitting.value = false }
}
async function approve(ids, approved) {
  submitting.value = true; error.value = ''
  try { apply(await aiApi.approve(conversationId.value, ids, approved)) } catch (e) { report(e) } finally { submitting.value = false }
}
async function stop() { try { apply(await aiApi.stop(conversationId.value)) } catch (e) { report(e) } }
async function newChat() {
  if (busy.value || uploading.value || submitting.value || pending.value.length) return
  closeEvents(); clearTimeout(timer); conversationId.value = ''; events.value = []; pending.value = []; attachments.value = []; cursor = 0; revision = 0; snapshotVersion = 0; state.value = 'idle'; authorizationMode.value = 'auto'; previousAuthorizationMode.value = 'auto'; error.value = ''
  await setActiveConversation(selectedServer.value,null)
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
async function scrollBottom() { await nextTick(); if (history.value) history.value.scrollTop = history.value.scrollHeight; awayFromBottom.value = false }
onBeforeUnmount(() => { disposed = true; closeEvents(); clearTimeout(timer); endPointer(); window.removeEventListener('resize', clampPanelOnViewportChange) })
</script>

<style scoped>
.ai-panel{box-sizing:border-box;position:fixed;z-index:1800;top:68px;right:12px;bottom:12px;width:min(500px,calc(100vw - 24px));display:flex;flex-direction:column;padding:0 16px 14px;background:var(--el-bg-color);color:var(--el-text-color-primary);border:1px solid var(--el-border-color);border-radius:8px;box-shadow:var(--el-box-shadow-dark);overflow:hidden}.ai-panel--fullscreen{inset:12px;width:auto;height:auto;max-height:none}.ai-panel-header{display:flex;align-items:center;gap:6px;min-height:48px;border-bottom:1px solid var(--el-border-color-light);margin-bottom:10px}.ai-panel-title{display:flex;flex-direction:column;gap:2px;margin-right:auto}.ai-panel-title strong{font-size:15px}.ai-panel-title small{font-size:11px;color:var(--el-text-color-secondary)}.ai-toolbar,.ai-actions,.ai-compose-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.ai-toolbar .el-select{flex:1;min-width:150px}.ai-host-row{display:flex;align-items:center;gap:8px;margin-top:10px;font-size:12px;color:var(--el-text-color-secondary)}.ai-host-select{flex:1;min-width:0}.ai-host-row>.el-button{font-size:18px;padding:7px}.ai-context,.ai-footnote,small{font-size:12px;color:var(--el-text-color-secondary)}.ai-context{padding:8px 0;overflow-wrap:anywhere}.ai-history{flex:1;min-height:100px;overflow:auto;border:1px solid var(--el-border-color);border-radius:8px;padding:12px;background:var(--el-bg-color)}.ai-empty{padding:24px 8px;color:var(--el-text-color-secondary);line-height:1.8}.ai-empty p{margin:8px 0}.ai-message{margin:0 0 12px;padding:10px;border-radius:8px;background:var(--el-fill-color-light);overflow-wrap:anywhere}.ai-message--user{background:var(--el-color-primary-light-9);margin-left:20px}.ai-message--error,.ai-error{color:var(--el-color-danger)}.ai-message-title{font-weight:600;display:flex;gap:8px;align-items:center;margin-bottom:7px}.ai-message-time{margin-left:auto;font-weight:400;font-size:11px;color:var(--el-text-color-secondary);white-space:nowrap}.ai-message>pre{font:inherit;white-space:pre-wrap;word-break:break-word;margin:8px 0;line-height:1.6}.ai-message--tool pre{font-family:monospace;font-size:12px;max-height:200px;overflow:auto}.ai-message summary{cursor:pointer;color:var(--el-text-color-secondary)}.ai-message a{color:var(--el-color-primary)}.ai-actions{margin:9px 0}.ai-attachments{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0;max-height:72px;overflow:auto}.ai-attachments .el-tag{max-width:100%;height:auto;white-space:normal;overflow-wrap:anywhere}.ai-compose-actions{justify-content:space-between;margin-top:8px}.ai-compose-actions>div{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.ai-compose-actions .el-button+.el-button{margin-left:0}.ai-footnote{line-height:1.5;margin-bottom:0;font-size:11px}.ai-provider-form{margin-top:16px}.ai-provider-form small{line-height:1.5}.ai-working{padding:8px;color:var(--el-color-primary)}.ai-settings-message{white-space:pre-wrap;overflow-wrap:anywhere}.ai-error{padding:6px 0}.ai-stream-status,.ai-stream-interrupted{display:block;color:var(--el-text-color-secondary);font-size:11px;margin-top:6px}.ai-stream-interrupted{color:var(--el-color-danger)}.ai-history-toolbar{display:flex;gap:8px;margin-bottom:12px}.ai-history-server{display:block;margin-top:4px;font-weight:400}
</style>
<style scoped>
.ai-panel{right:auto;bottom:auto}.ai-panel--fullscreen{inset:12px;right:12px;bottom:12px}
.ai-panel-header{cursor:grab;touch-action:none}.ai-panel-header:active{cursor:grabbing}
.ai-panel,.ai-history{background:var(--doumi-surface,var(--el-bg-color))}
.ai-resize-handle{position:absolute;z-index:2;right:5px;bottom:5px;width:22px;height:22px;display:flex;align-items:center;justify-content:center;color:var(--el-text-color-secondary);cursor:nwse-resize;touch-action:none}.ai-resize-handle:hover,.ai-resize-handle:focus-visible{color:var(--el-color-primary);outline:none}
.ai-permission-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:8px 0;border-bottom:1px solid var(--el-border-color-lighter);font-size:12px}.ai-permission-row .el-select{width:140px}.ai-permission-row small{flex:1;min-width:130px}
.ai-panel-enter-active,.ai-panel-leave-active{transition:transform .16s ease,opacity .16s ease}.ai-panel-enter-from,.ai-panel-leave-to{transform:translateX(-16px);opacity:0}.ai-panel--fullscreen{transition:none}
.ai-panel{border-radius:12px;padding-bottom:12px}
.ai-panel-header{flex-shrink:0;min-height:54px;margin-bottom:10px}
.ai-panel-title strong{display:flex;align-items:center;gap:6px;font-size:16px}
.ai-panel-title strong .el-icon{color:var(--el-color-primary)}
.ai-panel-title small{font-size:10px}
.ai-toolbar{flex-shrink:0;flex-wrap:nowrap;gap:5px}
.ai-toolbar .el-select{min-width:90px}
.ai-toolbar .el-button+.el-button{margin-left:0}
.ai-controls{flex-shrink:0;max-height:150px;overflow:auto;margin-top:8px;padding:10px;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:var(--el-fill-color-light)}
.ai-settings-actions{display:flex;justify-content:space-between;align-items:center;gap:10px}
.ai-permission-row{border-bottom:0;padding-bottom:0;gap:6px}
.ai-permission-row .el-select{width:180px}
.ai-permission-row small{flex-basis:100%;line-height:1.6}
.ai-host-row{flex-shrink:0;margin-top:10px}
.ai-context{flex-shrink:0;display:flex;align-items:center;gap:8px;justify-content:space-between;padding:5px 0}
.ai-context>span{min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.ai-context>.el-button{flex-shrink:0}
.ai-conversation{position:relative;display:flex;flex:1;min-height:80px;margin-bottom:10px}
.ai-history{min-height:0;border:0;padding:12px 4px;overscroll-behavior:contain;scrollbar-width:thin}
.ai-message{margin-bottom:14px;padding:12px;border:1px solid var(--el-border-color-lighter);border-radius:10px}
.ai-message--assistant{background:transparent;border-color:transparent;padding-left:2px;padding-right:2px}
.ai-message--user{margin-left:24px;background:var(--el-color-primary-light-9)}
.ai-message--tool{padding:8px 10px;border-left:3px solid var(--el-color-primary-light-5);font-size:12px}
.ai-message--tool>p{margin:6px 0;line-height:1.6;color:var(--el-text-color-secondary)}
.ai-message-title{flex-wrap:wrap;gap:5px;font-size:12px;row-gap:3px}
.ai-copy-message{flex-shrink:0}
.ai-message--tool pre{user-select:text;white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-bg-color);padding:8px;border-radius:6px}
.ai-jump-latest{position:absolute;bottom:6px;left:50%;transform:translateX(-50%);box-shadow:var(--el-box-shadow-light)}
.ai-empty{padding:18px 8px}
.ai-empty strong{font-size:15px;color:var(--el-text-color-primary)}
.ai-suggestions{display:flex;flex-direction:column;align-items:stretch;gap:8px;margin-top:18px}
.ai-suggestions button{padding:10px 12px;text-align:left;cursor:pointer;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-bg-color);color:var(--el-text-color-regular);font:inherit}
.ai-suggestions button:hover{border-color:var(--el-color-primary);color:var(--el-color-primary)}
.ai-composer{flex-shrink:0;border:1px solid var(--el-border-color);border-radius:10px;padding:8px;background:var(--doumi-surface,var(--el-bg-color));max-height:40%;overflow:auto}
.ai-composer:focus-within{border-color:var(--el-color-primary)}
.ai-composer :deep(.el-textarea__inner){box-shadow:none;background:transparent;padding:3px 4px;resize:none}
.ai-compose-actions{margin-top:6px;gap:6px}
.ai-help{flex-shrink:0;font-size:10px;color:var(--el-text-color-secondary);margin-top:7px;max-height:85px;overflow:auto;padding-right:16px}
.ai-help summary{cursor:pointer;line-height:1.6}
.ai-error{flex-shrink:0;max-height:80px;overflow:auto;border-radius:6px;background:var(--el-color-danger-light-9);padding:7px;font-size:12px;margin-bottom:8px}
.ai-panel--fullscreen .ai-history{padding-left:max(4px,calc((100% - 900px)/2));padding-right:max(4px,calc((100% - 900px)/2))}
@media(max-width:600px){.ai-panel{top:8px;right:auto;bottom:auto;padding:0 10px 10px}.ai-panel--fullscreen{inset:4px;right:4px;bottom:4px}}
</style>
