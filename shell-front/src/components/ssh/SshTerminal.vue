<template>
  <div
    class="ssh-terminal-wrap"
    :class="{ 'is-dragover': isDragOver }"
    tabindex="-1"
    @dragenter="handleDragEnter"
    @dragover.prevent="handleDragOver"
    @dragleave="handleDragLeave"
    @drop.prevent="handleDrop"
    @keydown.ctrl.f.prevent.stop="openSearch"
    @keydown.meta.f.prevent.stop="openSearch"
  >
    <!-- 拖拽上传覆盖层 -->
    <div v-if="isDragOver" class="terminal-drop-overlay">
      <el-icon class="terminal-drop-ico"><Upload /></el-icon>
      <div class="terminal-drop-title">松开鼠标立即上传到当前目录</div>
      <div v-if="currentPath" class="terminal-drop-path font-mono-code">{{ currentPath }}</div>
    </div>

    <div v-if="statusMessage" class="status-overlay">{{ statusMessage }}</div>
    <div v-if="contextMenu.visible" class="terminal-context-menu" :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }" @mousedown.stop>
      <button type="button" @click="copyTerminalSelection(); closeContextMenu()">复制 <kbd>Ctrl+C</kbd></button>
      <button type="button" @click="pasteTerminalClipboard(); closeContextMenu()">粘贴 <kbd>Ctrl+Shift+V</kbd></button>
      <button type="button" @click="openSearch(); closeContextMenu()">搜索 <kbd>Ctrl+F</kbd></button>
      <div class="menu-divider"></div>
      <div class="menu-label font-mono-code">编码: {{ encoding || 'UTF-8' }}</div>
      <button type="button" :class="{ 'is-active-opt': (encoding || 'UTF-8') === 'UTF-8' }" @click="onSwitchEncoding('UTF-8')">UTF-8 (推荐)</button>
      <button type="button" :class="{ 'is-active-opt': encoding === 'GBK' }" @click="onSwitchEncoding('GBK')">GBK (简体中文)</button>
      <button type="button" :class="{ 'is-active-opt': encoding === 'GB18030' }" @click="onSwitchEncoding('GB18030')">GB18030</button>
      <button type="button" :class="{ 'is-active-opt': encoding === 'Big5' }" @click="onSwitchEncoding('Big5')">Big5 (繁体)</button>
    </div>
    <div v-if="searchVisible" class="terminal-search" @mousedown.stop>
      <select v-model="searchScope" aria-label="搜索范围" :title="tmuxStatus.historyNote || '选择搜索范围'" @change="changeSearchScope">
        <option value="connection">本次连接</option>
        <option v-if="tmuxStatus.tmux" value="history" :disabled="tmuxStatus.historySupported === false">{{ tmuxStatus.historySupported === false ? 'tmux 历史（需 1.8+）' : 'tmux 历史' }}</option>
      </select>
      <input
        ref="searchInput"
        v-model="searchText"
        :placeholder="searchScope === 'history' ? '搜索 tmux 历史内容' : '搜索本次连接内容'"
        aria-label="搜索终端内容"
        @keydown.enter.prevent="onSearchEnter"
        @keydown.esc="closeSearch"
        @keydown.ctrl.f.prevent.stop="findNext"
        @keydown.meta.f.prevent.stop="findNext"
      />
      <button type="button" aria-label="上一个匹配" @click="findPrevious">↑</button><button type="button" aria-label="下一个匹配" @click="findNext">↓</button>
      <button v-if="searchScope === 'history'" type="button" :disabled="historyLoading || disconnected" @click="loadHistory">刷新历史</button>
      <button type="button" @click="closeSearch">关闭</button>
    </div>
    <TerminalHistory v-if="searchVisible && searchScope === 'history'" ref="historyView" :text="historyText" :query="searchText" :loading="historyLoading" :error="historyError" :truncated="historyTruncated" @close="closeSearch" />
    <div ref="termContainer" class="term-container" :class="[termContainerClass]" :style="termContainerStyle" @click="focusTerminal" @contextmenu.prevent="showContextMenu" />
    <EditorScrollbar :target="scrollTarget" />

    <!-- 报错智能侦测浮层 (伴随式 AI 体验) -->
    <transition name="fade-slide">
      <div v-if="detectedError && !dismissedError" class="terminal-error-detective">
        <div class="detective-icon">✨</div>
        <div class="detective-body">
          <div class="detective-title">
            <span>终端异常捕获</span>
            <span class="detective-badge">Error</span>
          </div>
          <div class="detective-snippet" :title="detectedError">{{ detectedError }}</div>
        </div>
        <div class="detective-actions">
          <button type="button" class="detective-btn-ai" @click="handleAiTroubleshoot">
            AI 诊断
          </button>
          <button type="button" class="detective-btn-close" title="忽略" @click="dismissedError = true">
            ✕
          </button>
        </div>
      </div>
    </transition>

    <div v-if="disconnected" class="reconnect-bar">
      <span>会话已断开</span>
      <el-button size="small" type="primary" @click="reconnect">重新连接</el-button>
      <el-button v-if="!shareToken" size="small" @click="reconnectPlainSsh">改用普通 SSH</el-button>
    </div>
  </div>
</template>

<script setup>
import { createTerminalConnectionGuard } from '@/utils/terminalConnectionGuard'
import { bindTerminalInput, encodeTerminalText } from '@/utils/terminalInput'
import { bindTerminalSelection } from '@/utils/terminalSelection'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import EditorScrollbar from '@/components/EditorScrollbar.vue'
import TerminalHistory from './TerminalHistory.vue'
import { createHistoryRequest, TERMINAL_SCROLLBACK } from '@/utils/terminalHistory'
import { tmuxCompatibility } from '@/utils/tmuxCompatibility'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import { SearchAddon } from '@xterm/addon-search'
import 'xterm/css/xterm.css'
import { buildTerminalWsUrl } from '@/api/sshConsole'
import { enhanceTerminalOutput } from '@/utils/terminalColorEnhancer'
import { useBackground } from '@/composables/useBackground'
import { useTheme } from '@/composables/useTheme'
import { useTerminalTheme } from '@/composables/useTerminalTheme'

const { background } = useBackground()
const { effectiveDark } = useTheme()
const { activeTheme } = useTerminalTheme()

const props = defineProps({
  accountId: { type: [Number, String], default: 0 },
  terminalId: { type: String, default: '' },
  shareToken: { type: String, default: '' },
  readOnly: { type: Boolean, default: false },
  fontSize: { type: Number, default: 14 },
  lightTone: { type: String, default: 'paper' }, // 'paper' | 'hybrid'
  encoding: { type: String, default: 'UTF-8' },
  currentPath: { type: String, default: '' }
})

const emit = defineEmits([
  'ready',
  'disconnected',
  'connection-error',
  'size-change',
  'ask-ai',
  'change-encoding',
  'drop-files'
])

const isDragOver = ref(false)
let dragDepth = 0

function handleDragEnter(event) {
  if (props.readOnly) return
  if (!event.dataTransfer?.types?.includes('Files')) return
  dragDepth++
  isDragOver.value = true
}

function handleDragOver(event) {
  if (props.readOnly) return
  if (!event.dataTransfer?.types?.includes('Files')) return
  event.preventDefault()
  event.dataTransfer.dropEffect = 'copy'
  isDragOver.value = true
}

function handleDragLeave(event) {
  dragDepth--
  if (dragDepth <= 0) {
    dragDepth = 0
    isDragOver.value = false
  }
}

function handleDrop(event) {
  dragDepth = 0
  isDragOver.value = false
  event.preventDefault()
  if (props.readOnly) return
  if (!event.dataTransfer) return
  emit('drop-files', event.dataTransfer)
}

const termContainer = ref(null)
const scrollTarget = shallowRef(null)
const statusMessage = ref('连接中…')
const disconnected = ref(false)
const connectionMode = ref('')
const tmuxVersion = ref('')
const tmuxVersionSource = ref('unknown')
const tmuxStatus = computed(() => tmuxCompatibility(connectionMode.value, tmuxVersion.value, tmuxVersionSource.value))
const preferredTransport = ref('')
const searchVisible = ref(false)
const searchText = ref('')
const searchInput = ref(null)
const searchScope = ref('connection')
const historyView = ref(null)
const historyText = ref('')
const historyLoading = ref(false)
const historyError = ref('')
const historyTruncated = ref(false)
const historyRequest = createHistoryRequest()
let historyTimer
let terminalSelection
const contextMenu = ref({ visible: false, x: 0, y: 0 })

const detectedError = ref('')
const dismissedError = ref(false)

let terminal = null
let fitAddon = null
let ws = null
let resizeObserver = null
let connectionGuard = null
let dataBound = false
let connectGen = 0
let searchAddon = null

function createDecoder(enc) {
  try {
    return new TextDecoder(enc || 'utf-8', { stream: true })
  } catch (err) {
    console.warn(`[SshTerminal] 无法创建编码器 ${enc}，回退至 utf-8`, err)
    return new TextDecoder('utf-8', { stream: true })
  }
}

let textDecoder = createDecoder(props.encoding)

watch(() => props.encoding, (newEnc, oldEnc) => {
  textDecoder = createDecoder(newEnc)
  // tmux chooses its decoration character set when attaching. Reattach the same
  // persistent session when that choice changes; plain SSH keeps its live shell.
  if (newEnc !== oldEnc && connectionMode.value.includes('tmux') && ws?.readyState === WebSocket.OPEN) {
    emit('disconnected', {})
    connect()
  }
})

function onSwitchEncoding(enc) {
  emit('change-encoding', enc)
  contextMenu.value.visible = false
}

// 现代化调色板定义
const darkAnsiTheme = {
  background: '#080c16',
  foreground: '#e2e8f0',
  cursor: '#6366f1',
  cursorAccent: '#ffffff',
  selectionBackground: 'rgba(99, 102, 241, 0.35)',
  black: '#0f172a',
  red: '#f87171',
  green: '#34d399',
  yellow: '#fbbf24',
  blue: '#60a5fa',
  magenta: '#c084fc',
  cyan: '#38bdf8',
  white: '#f1f5f9',
  brightBlack: '#475569',
  brightRed: '#ef4444',
  brightGreen: '#10b981',
  brightYellow: '#f59e0b',
  brightBlue: '#3b82f6',
  brightMagenta: '#a855f7',
  brightCyan: '#06b6d4',
  brightWhite: '#ffffff'
}

const paperLightAnsiTheme = {
  background: '#ffffff',
  foreground: '#0f172a',
  cursor: '#4f46e5',
  cursorAccent: '#ffffff',
  selectionBackground: 'rgba(79, 70, 229, 0.2)',
  black: '#0f172a',
  red: '#dc2626',
  green: '#047857',
  yellow: '#b45309',
  blue: '#2563eb',
  magenta: '#7c3aed',
  cyan: '#0891b2',
  white: '#64748b',
  brightBlack: '#64748b',
  brightRed: '#b91c1c',
  brightGreen: '#065f46',
  brightYellow: '#92400e',
  brightBlue: '#1d4ed8',
  brightMagenta: '#6d28d9',
  brightCyan: '#0e7490',
  brightWhite: '#0f172a'
}

function pickXtermTheme(theme) {
  if (!theme) return darkAnsiTheme
  return {
    background: theme.background || '#080c16',
    foreground: theme.foreground || '#e2e8f0',
    cursor: theme.cursor || '#6366f1',
    cursorAccent: theme.cursorAccent || '#ffffff',
    selectionBackground: theme.selectionBackground || 'rgba(99, 102, 241, 0.35)',
    selectionForeground: theme.selectionForeground,
    black: theme.black,
    red: theme.red,
    green: theme.green,
    yellow: theme.yellow,
    blue: theme.blue,
    magenta: theme.magenta,
    cyan: theme.cyan,
    white: theme.white,
    brightBlack: theme.brightBlack,
    brightRed: theme.brightRed,
    brightGreen: theme.brightGreen,
    brightYellow: theme.brightYellow,
    brightBlue: theme.brightBlue,
    brightMagenta: theme.brightMagenta,
    brightCyan: theme.brightCyan,
    brightWhite: theme.brightWhite
  }
}

function terminalTheme() {
  const current = activeTheme.value || darkAnsiTheme
  let res = current
  if (background.value.enabled) {
    const foreground = effectiveDark.value ? '#e5eaf3' : (current.foreground || '#303133')
    res = {
      ...current,
      background: '#00000000',
      foreground,
      cursor: foreground
    }
  } else if (!effectiveDark.value && props.lightTone === 'hybrid' && current.category === 'light') {
    res = darkAnsiTheme
  }

  return pickXtermTheme(res)
}

const termContainerStyle = computed(() => {
  if (background.value.enabled) return { backgroundColor: 'transparent' }
  const bg = terminalTheme().background
  return { backgroundColor: bg }
})

const termContainerClass = computed(() => {
  if (background.value.enabled) return 'theme-custom-bg'
  if (effectiveDark.value) return 'theme-dark'
  return props.lightTone === 'paper' ? 'theme-paper' : 'theme-hybrid-dark'
})

watch(
  [() => background.value.enabled, effectiveDark, activeTheme, () => props.lightTone],
  () => {
    if (terminal) {
      terminal.options.theme = terminalTheme()
      if (typeof terminal.refresh === 'function' && terminal.rows) {
        terminal.refresh(0, terminal.rows - 1)
      }
    }
  }
)

watch(
  () => props.fontSize,
  (newSize) => {
    if (terminal && newSize >= 12 && newSize <= 24) {
      terminal.options.fontSize = newSize
      fitAddon?.fit()
    }
  }
)

function b64Decode(b64) {
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return textDecoder.decode(bytes, { stream: true })
}

function sendJson(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj))
  }
}

async function copyTerminalSelection() {
  const text = terminal?.getSelection?.() || ''
  if (!text) return false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const field = document.createElement('textarea')
      field.value = text
      field.style.position = 'fixed'
      field.style.opacity = '0'
      document.body.appendChild(field)
      field.focus()
      field.select()
      document.execCommand('copy')
      field.remove()
    }
    return true
  } catch {
    return false
  }
}

async function pasteTerminalClipboard() {
  try {
    if (!navigator.clipboard?.readText) return false
    const text = await navigator.clipboard.readText()
    if (text) terminal?.paste(text)
    return true
  } catch {
    return false
  }
}

function showContextMenu(event) { contextMenu.value = { visible: true, x: event.offsetX, y: event.offsetY } }
function closeContextMenu() { contextMenu.value.visible = false }
function openSearch() {
  searchVisible.value = true
  nextTick(() => {
    searchInput.value?.focus()
    searchInput.value?.select()
  })
}
function cancelHistory() {
  historyRequest.cancel()
  clearTimeout(historyTimer)
  historyLoading.value = false
}
function closeSearch() {
  searchVisible.value = false
  cancelHistory()
  historyText.value = ''
  searchScope.value = 'connection'
  searchAddon?.clearDecorations()
  terminal?.focus()
}
function changeSearchScope() {
  searchAddon?.clearDecorations()
  if (searchScope.value === 'history') loadHistory()
  else { cancelHistory(); historyText.value = '' }
  nextTick(() => searchInput.value?.focus())
}
function loadHistory() {
  if (historyLoading.value) return
  historyText.value = ''
  historyError.value = ''
  historyTruncated.value = false
  if (tmuxStatus.value.historySupported === false) {
    historyError.value = tmuxStatus.value.historyNote
    return
  }
  if (!ws || ws.readyState !== WebSocket.OPEN || disconnected.value) {
    historyError.value = '终端未连接，请重新连接后读取历史'
    return
  }
  const requestId = historyRequest.start()
  historyLoading.value = true
  sendJson({ type: 'history', requestId, encoding: props.encoding })
  historyTimer = setTimeout(() => {
    if (!historyRequest.accept(requestId)) return
    historyLoading.value = false
    historyError.value = '历史读取超时，请重试并确认后端已更新'
  }, 20_000)
}
function findNext() {
  if (!searchText.value) return
  if (searchScope.value === 'history') historyView.value?.find(1)
  else searchAddon?.findNext(searchText.value)
}
function findPrevious() {
  if (!searchText.value) return
  if (searchScope.value === 'history') historyView.value?.find(-1)
  else searchAddon?.findPrevious(searchText.value)
}
function onSearchEnter(event) { if (event.shiftKey) findPrevious(); else findNext() }

function sendResize() {
  if (!terminal || !fitAddon || !ws || ws.readyState !== WebSocket.OPEN || !dataBound) {
    return
  }
  fitAddon.fit()
  sendJson({ type: 'resize', cols: terminal.cols, rows: terminal.rows })
  emit('size-change', { cols: terminal.cols, rows: terminal.rows })
}

function bindTerminalData() {
  if (!terminal || dataBound) return
  dataBound = true
  bindTerminalInput(terminal, sendJson, () => props.encoding)
  sendResize()
}

function activateSession(frame) {
  statusMessage.value = ''
  connectionMode.value = frame?.connectionMode || ''
  tmuxVersion.value = frame?.tmuxVersion || ''
  tmuxVersionSource.value = frame?.tmuxVersionSource || 'unknown'
  bindTerminalData()
  if (frame?.type === 'ready') {
    emit('ready', { sessionToken: frame.sessionToken, label: frame.label, connectionMode: frame.connectionMode, tmuxVersion: tmuxVersion.value, tmuxVersionSource: tmuxVersionSource.value })
    connectionGuard?.ready()
  }
  nextTick(() => {
    terminal?.focus()
  })
}

function disposeTerminal() {
  terminalSelection?.dispose()
  terminalSelection = null
  cancelHistory()
  historyText.value = ''
  searchScope.value = 'connection'
  scrollTarget.value = null
  resizeObserver?.disconnect()
  resizeObserver = null
  connectionGuard?.dispose()
  connectionGuard = null
  dataBound = false
  if (ws) {
    ws.onopen = null
    ws.onmessage = null
    ws.onerror = null
    ws.onclose = null
    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
      ws.close()
    }
    ws = null
  }
  if (terminal) {
    terminal.dispose()
    terminal = null
    fitAddon = null
  }
}

// 检查输出流中的典型报错
function inspectErrorOutput(text) {
  const errorPatterns = [
    /command not found/i,
    /No such file or directory/i,
    /Permission denied/i,
    /Connection refused/i,
    /fatal: /i,
    /syntax error/i,
    /failed to start/i
  ]
  for (const pattern of errorPatterns) {
    if (pattern.test(text)) {
      const line = text.split('\n').find(l => pattern.test(l))?.trim()
      if (line && line.length < 120) {
        detectedError.value = line
        dismissedError.value = false
        break
      }
    }
  }
}

function handleWsMessage(ev) {
  let frame
  try {
    frame = JSON.parse(ev.data)
  } catch {
    return
  }
  if (frame.type === 'connecting') {
    statusMessage.value = frame.message || '正在连接终端…'
    return
  }
  if (frame.type === 'ready') {
    activateSession(frame)
    return
  }
  if (frame.type === 'history') {
    if (!historyRequest.accept(frame.requestId)) return
    clearTimeout(historyTimer)
    historyLoading.value = false
    historyError.value = frame.message || ''
    historyTruncated.value = Boolean(frame.truncated)
    historyText.value = frame.message ? '' : (frame.data || '')
    return
  }
  if (frame.type === 'output' && frame.data) {
    const decoded = b64Decode(frame.data)
    const formatted = enhanceTerminalOutput(decoded)
    terminal?.write(formatted)
    inspectErrorOutput(decoded)
    return
  }
  if (frame.type === 'pong') {
    connectionGuard?.pong()
    return
  }
  if (frame.type === 'error') {
    connectionGuard?.fail(frame.message || '连接失败')
  }
}

function connect() {
  const gen = ++connectGen
  disposeTerminal()
  disconnected.value = false
  connectionMode.value = ''
  tmuxVersion.value = ''
  tmuxVersionSource.value = 'unknown'
  statusMessage.value = '连接中…'
  dataBound = false

  terminal = new Terminal({
    cursorBlink: true,
    macOptionClickForcesSelection: true,
    fontSize: props.fontSize || 14,
    fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, 'Courier New', monospace",
    allowTransparency: true,
    theme: terminalTheme(),
    convertEol: true,
    scrollback: TERMINAL_SCROLLBACK
  })
  terminal.attachCustomKeyEventHandler((event) => {
    if ((event.ctrlKey || event.metaKey) && (event.key?.toLowerCase() === 'k' || event.code === 'KeyK') && !event.altKey) {
      event.preventDefault()
      event.stopPropagation()
      if (event.type === 'keydown' && !event.shiftKey) {
        window.dispatchEvent(new CustomEvent('doumi-open-command-palette'))
      }
      return false
    }
    if ((event.ctrlKey || event.metaKey) && (event.key?.toLowerCase() === 'f' || event.code === 'KeyF')) {
      event.preventDefault()
      event.stopPropagation()
      if (event.type === 'keydown') {
        openSearch()
      }
      return false
    }
    if (props.readOnly) {
      // 只读共享模式：允许搜索和复制快捷键，阻止向终端发送任何输入按键
      if ((event.ctrlKey || event.metaKey) && (event.key?.toLowerCase() === 'c' || event.key?.toLowerCase() === 'f')) {
        return true
      }
      return false
    }
    if (event.type !== 'keydown') return true
    if (event.key === 'Insert' && event.shiftKey) {
      void pasteTerminalClipboard()
      return false
    }
    const modifier = event.ctrlKey || event.metaKey
    if (!modifier) return true
    if (event.key?.toLowerCase() === 'c' && terminal.hasSelection()) {
      void copyTerminalSelection()
      return false
    }
    if (event.key === 'Insert' && !event.shiftKey) {
      void copyTerminalSelection()
      return false
    }
    if (event.key?.toLowerCase() === 'v' && event.shiftKey) {
      void pasteTerminalClipboard()
      return false
    }
    return true
  })
  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.loadAddon(new WebLinksAddon())
  searchAddon = new SearchAddon()
  terminal.loadAddon(searchAddon)
  terminal.open(termContainer.value)
  terminalSelection = bindTerminalSelection(terminal)
  scrollTarget.value = termContainer.value.querySelector('.xterm-viewport')
  fitAddon.fit()

  const url = buildTerminalWsUrl(props.accountId, {
    cols: terminal.cols,
    rows: terminal.rows,
    terminalId: props.terminalId,
    shareToken: props.shareToken,
    transport: preferredTransport.value,
    encoding: props.encoding
  })
  const socket = new WebSocket(url)
  ws = socket
  const isCurrent = () => gen === connectGen && ws === socket
  connectionGuard = createTerminalConnectionGuard({
    sendPing: () => { if (isCurrent()) sendJson({ type: 'ping' }) },
    onFailure: message => {
      if (!isCurrent()) return
      statusMessage.value = message
      disconnected.value = true
      dataBound = false
      emit('disconnected', {})
      emit('connection-error', { message })
      socket.close()
    }
  })
  const guard = connectionGuard
  socket.onmessage = event => { if (isCurrent() && !disconnected.value) handleWsMessage(event) }
  socket.onerror = () => { if (isCurrent()) guard.fail('WebSocket 连接失败') }
  socket.onclose = () => { if (isCurrent()) guard.fail('会话已断开') }

  resizeObserver = new ResizeObserver(() => sendResize())
  resizeObserver.observe(termContainer.value)
}

function reconnect() {
  connect()
}

function reconnectPlainSsh() {
  preferredTransport.value = 'ssh'
  connect()
}

function focusTerminal() {
  closeContextMenu()
  terminal?.focus()
}

// 供父组件调用的指令注入与清屏方法
function sendInput(cmd) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    sendJson({ type: 'input', data: encodeTerminalText(cmd + '\n'), encoding: props.encoding })
  }
}

function clearScreen() {
  terminal?.clear()
  focusTerminal()
}

function handleAiTroubleshoot() {
  const err = detectedError.value
  dismissedError.value = true
  emit('ask-ai', `请分析此终端报错并给出解决办法：${err}`)
}

defineExpose({
  sendInput,
  clearScreen,
  focusTerminal,
  reconnect
})

onMounted(() => {
  connect()
})

onBeforeUnmount(() => {
  disposeTerminal()
})

watch(
  () => props.accountId,
  () => {
    reconnect()
  }
)
</script>

<style scoped>
.ssh-terminal-wrap {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  position: relative;
  overflow: hidden;
  outline: none;
}
.terminal-drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 100;
  background: rgba(99, 102, 241, 0.16);
  backdrop-filter: blur(5px);
  border: 2px dashed var(--el-color-primary, #6366f1);
  border-radius: var(--ws-radius-md, 8px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--el-color-primary, #6366f1);
  font-weight: 600;
  pointer-events: none;
  animation: drop-fade-in 0.15s ease-out;
}
@keyframes drop-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
.terminal-drop-ico {
  font-size: 36px;
}
.terminal-drop-title {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.2px;
}
.terminal-drop-path {
  font-size: 12px;
  opacity: 0.9;
  background: rgba(0, 0, 0, 0.15);
  padding: 3px 12px;
  border-radius: 4px;
  color: var(--el-color-primary, #6366f1);
}
html.dark .terminal-drop-path,
:root[data-theme='dark'] .terminal-drop-path {
  background: rgba(255, 255, 255, 0.12);
}
.terminal-context-menu { position:absolute; z-index:8; min-width:170px; padding:4px; border:1px solid var(--el-border-color); border-radius:6px; background:var(--el-bg-color); box-shadow:var(--el-box-shadow); }
.terminal-context-menu button { display:flex; justify-content:space-between; width:100%; padding:7px 9px; border:0; background:transparent; color:var(--el-text-color-primary); cursor:pointer; text-align:left; }
.terminal-context-menu button:hover { background:var(--el-fill-color-light); }
.terminal-context-menu kbd { color:var(--el-text-color-secondary); font-size:11px; }
.terminal-context-menu .menu-divider { height: 1px; background: var(--el-border-color-lighter); margin: 4px 0; }
.terminal-context-menu .menu-label { padding: 4px 9px; font-size: 11px; color: var(--el-text-color-secondary); }
.terminal-context-menu button.is-active-opt { color: var(--el-color-primary); font-weight: 600; }
.terminal-search { position:absolute; right:12px; left:12px; top:8px; z-index:7; display:flex; gap:4px; padding:5px; border:1px solid var(--el-border-color); border-radius:6px; background:var(--el-bg-color); box-shadow:var(--el-box-shadow); }
.terminal-search input { flex:1; width:0; min-width:50px; padding:4px 7px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-bg-color); color:var(--el-text-color-primary); }
.terminal-search select { max-width:110px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-bg-color); color:var(--el-text-color-primary); }
.terminal-search button { min-width:28px; min-height:30px; flex-shrink:0; border:0; background:transparent; color:var(--el-text-color-primary); cursor:pointer; }
.terminal-search button:disabled { opacity:.5; cursor:default; }
.terminal-search :focus-visible { outline:2px solid var(--el-color-primary); outline-offset:1px; }
.term-container {
  flex: 1;
  min-height: 0;
  padding: 6px;
  overflow: hidden;
  background: var(--ws-bg-terminal);
  transition: background-color var(--ws-transition-normal);
}
.term-container.theme-dark {
  background: var(--ws-bg-terminal, #080c16);
}
.term-container.theme-paper {
  background: #ffffff;
}
.term-container.theme-hybrid-dark {
  background: #080c16;
}
.term-container.theme-custom-bg {
  background: transparent !important;
}
.status-overlay {
  position: absolute;
  left: 12px;
  top: 48px;
  z-index: 2;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  pointer-events: none;
}
.reconnect-bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
  background: var(--ws-bg-card, #ffffff);
}
html.dark .reconnect-bar {
  background: #0f1523;
  border-top-color: rgba(255, 255, 255, 0.08);
}

/* 报错伴随胶囊 */
.terminal-error-detective {
  position: absolute;
  right: 16px;
  bottom: 16px;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: var(--ws-radius-md, 8px);
  background: var(--ws-bg-card, #ffffff);
  border: 1px solid var(--el-color-primary-light-5, #a5b4fc);
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(12px);
  max-width: 440px;
}
html.dark .terminal-error-detective {
  background: #0f1523;
  border-color: rgba(99, 102, 241, 0.4);
}
.detective-icon {
  font-size: 16px;
  color: var(--el-color-primary, #6366f1);
  flex-shrink: 0;
}
.detective-body {
  min-width: 0;
  flex: 1;
}
.detective-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.detective-badge {
  font-size: 10px;
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(239, 68, 68, 0.15);
  color: #ef4444;
  font-weight: 500;
}
.detective-snippet {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  font-family: var(--font-mono, monospace);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 2px;
}
.detective-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.detective-btn-ai {
  padding: 3px 8px;
  border-radius: 4px;
  background: var(--el-color-primary, #6366f1);
  color: #ffffff;
  border: none;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s ease;
}
.detective-btn-ai:hover {
  opacity: 0.9;
}
.detective-btn-close {
  background: transparent;
  border: none;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  cursor: pointer;
  padding: 2px;
}
.detective-btn-close:hover {
  color: var(--el-text-color-primary);
}

.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.25s ease;
}
.fade-slide-enter-from,
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
</style>
