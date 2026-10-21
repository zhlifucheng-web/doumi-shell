<template>
  <div class="ssh-terminal-wrap">
    <div v-if="statusMessage" class="status-overlay">{{ statusMessage }}</div>
    <span v-if="connectionMode" class="connection-mode" :title="connectionMode">{{ connectionMode }}</span>
    <div v-if="contextMenu.visible" class="terminal-context-menu" :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }" @mousedown.stop>
      <button type="button" @click="copyTerminalSelection(); closeContextMenu()">复制 <kbd>Ctrl+C</kbd></button>
      <button type="button" @click="pasteTerminalClipboard(); closeContextMenu()">粘贴 <kbd>Ctrl+Shift+V</kbd></button>
      <button type="button" @click="openSearch(); closeContextMenu()">搜索 <kbd>Ctrl+F</kbd></button>
    </div>
    <div v-if="searchVisible" class="terminal-search" @mousedown.stop>
      <input ref="searchInput" v-model="searchText" placeholder="搜索终端内容" @keydown.enter.prevent="findNext" @keydown.esc="closeSearch" />
      <button type="button" @click="findPrevious">↑</button><button type="button" @click="findNext">↓</button><button type="button" @click="closeSearch">关闭</button>
    </div>
    <div ref="termContainer" class="term-container" @click="focusTerminal" @contextmenu.prevent="showContextMenu" />
    <EditorScrollbar :target="scrollTarget" />
    <div v-if="disconnected" class="reconnect-bar">
      <span>会话已断开</span>
      <el-button size="small" type="primary" @click="reconnect">重新连接</el-button>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import EditorScrollbar from '@/components/EditorScrollbar.vue'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import { SearchAddon } from '@xterm/addon-search'
import 'xterm/css/xterm.css'
import { buildTerminalWsUrl } from '@/api/sshConsole'
import { useBackground } from '@/composables/useBackground'
import { useTheme } from '@/composables/useTheme'
const {background}=useBackground()
const {effectiveDark}=useTheme()

const props = defineProps({
  accountId: { type: Number, required: true },
  terminalId: { type: String, required: true }
})

const emit = defineEmits(['ready', 'disconnected', 'connection-error'])

const termContainer = ref(null)
const scrollTarget = shallowRef(null)
const statusMessage = ref('连接中…')
const disconnected = ref(false)
const connectionMode = ref('')
const searchVisible = ref(false)
const searchText = ref('')
const searchInput = ref(null)
const contextMenu = ref({ visible: false, x: 0, y: 0 })

let terminal = null
let fitAddon = null
let ws = null
let resizeObserver = null
let pingTimer = null
let dataBound = false
let connectGen = 0
let searchAddon = null
function terminalTheme(){
  if(!background.value.enabled)return {background:'#1e1e1e',foreground:'#ffffff',cursor:'#ffffff'}
  const foreground=effectiveDark.value?'#e5eaf3':'#303133'
  return effectiveDark.value
    ? {background:'#00000000',foreground,cursor:foreground,selectionBackground:'#409eff55'}
    : {background:'#00000000',foreground,cursor:foreground,selectionBackground:'#409eff',selectionForeground:'#ffffff'}
}
watch([()=>background.value.enabled,effectiveDark],()=>{if(terminal)terminal.options.theme=terminalTheme()})

function b64Encode(str) {
  const bytes = new TextEncoder().encode(str)
  let binary = ''
  bytes.forEach((b) => {
    binary += String.fromCharCode(b)
  })
  return btoa(binary)
}

function b64Decode(b64) {
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return new TextDecoder().decode(bytes)
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
function openSearch() { searchVisible.value = true; nextTick(() => searchInput.value?.focus()) }
function closeSearch() { searchVisible.value = false; searchAddon?.clearDecorations(); terminal?.focus() }
function findNext() { if (searchText.value) searchAddon?.findNext(searchText.value) }
function findPrevious() { if (searchText.value) searchAddon?.findPrevious(searchText.value) }

function sendResize() {
  if (!terminal || !fitAddon || !ws || ws.readyState !== WebSocket.OPEN || !dataBound) {
    return
  }
  fitAddon.fit()
  sendJson({ type: 'resize', cols: terminal.cols, rows: terminal.rows })
}

function clearPing() {
  if (pingTimer) {
    clearInterval(pingTimer)
    pingTimer = null
  }
}

function startPing() {
  clearPing()
  pingTimer = setInterval(() => sendJson({ type: 'ping' }), 30_000)
}

function bindTerminalData() {
  if (!terminal || dataBound) return
  dataBound = true
  terminal.onData((data) => {
    sendJson({ type: 'input', data: b64Encode(data) })
  })
  sendResize()
}

function activateSession(frame) {
  statusMessage.value = ''
  connectionMode.value = frame?.connectionMode || ''
  bindTerminalData()
  if (frame?.type === 'ready') {
    emit('ready', { sessionToken: frame.sessionToken, label: frame.label })
    startPing()
  }
  nextTick(() => {
    terminal?.focus()
  })
}

function disposeTerminal() {
  scrollTarget.value=null
  resizeObserver?.disconnect()
  resizeObserver = null
  clearPing()
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

function handleWsMessage(ev) {
  let frame
  try {
    frame = JSON.parse(ev.data)
  } catch {
    return
  }
  if (frame.type === 'ready') {
    activateSession(frame)
    return
  }
  if (frame.type === 'output' && frame.data) {
    if (!dataBound) {
      activateSession(null)
    }
    terminal?.write(b64Decode(frame.data))
    return
  }
  if (frame.type === 'pong') {
    return
  }
  if (frame.type === 'error') {
    const msg = frame.message || '连接失败'
    statusMessage.value = msg
    emit('connection-error', { message: msg })
    disconnected.value = true
  }
}

function connect() {
  const gen = ++connectGen
  disposeTerminal()
  disconnected.value = false
  connectionMode.value = ''
  statusMessage.value = '连接中…'
  dataBound = false

  terminal = new Terminal({
    cursorBlink: true,
    fontSize: 14,
    fontFamily: 'Consolas, "Courier New", monospace',
    allowTransparency: true,
    theme: terminalTheme()
  })
  terminal.attachCustomKeyEventHandler((event) => {
    if (event.type !== 'keydown') return true
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f') {
      openSearch()
      return false
    }
    if (event.key === 'Insert' && event.shiftKey) {
      void pasteTerminalClipboard()
      return false
    }
    const modifier = event.ctrlKey || event.metaKey
    if (!modifier) return true
    if (event.key.toLowerCase() === 'c' && terminal.hasSelection()) {
      void copyTerminalSelection()
      return false
    }
    if (event.key === 'Insert' && !event.shiftKey) {
      void copyTerminalSelection()
      return false
    }
    if (event.key.toLowerCase() === 'v' && event.shiftKey) {
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
  scrollTarget.value=termContainer.value.querySelector('.xterm-viewport')
  fitAddon.fit()

  const url = buildTerminalWsUrl(props.accountId, {
    cols: terminal.cols,
    rows: terminal.rows,
    terminalId: props.terminalId
  })
  ws = new WebSocket(url)
  ws.onmessage = handleWsMessage
  ws.onerror = () => {
    if (gen !== connectGen) return
    statusMessage.value = 'WebSocket 连接失败'
    emit('connection-error', { message: 'WebSocket 连接失败' })
    disconnected.value = true
  }
  ws.onclose = () => {
    if (gen !== connectGen) return
    if (!disconnected.value) {
      disconnected.value = true
      statusMessage.value = '会话已断开'
      emit('disconnected', {})
    }
  }

  resizeObserver = new ResizeObserver(() => sendResize())
  resizeObserver.observe(termContainer.value)
}

function reconnect() {
  connect()
}

function focusTerminal() {
  closeContextMenu()
  terminal?.focus()
}

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
}
.connection-mode { position:absolute; right:10px; top:8px; z-index:2; padding:2px 7px; border:1px solid var(--el-border-color); border-radius:10px; background:var(--el-bg-color); color:var(--el-text-color-secondary); font-size:11px; pointer-events:none; }
.terminal-context-menu { position:absolute; z-index:8; min-width:170px; padding:4px; border:1px solid var(--el-border-color); border-radius:6px; background:var(--el-bg-color); box-shadow:var(--el-box-shadow); }
.terminal-context-menu button { display:flex; justify-content:space-between; width:100%; padding:7px 9px; border:0; background:transparent; color:var(--el-text-color-primary); cursor:pointer; text-align:left; }
.terminal-context-menu button:hover { background:var(--el-fill-color-light); }
.terminal-context-menu kbd { color:var(--el-text-color-secondary); font-size:11px; }
.terminal-search { position:absolute; right:12px; top:8px; z-index:7; display:flex; gap:4px; padding:5px; border:1px solid var(--el-border-color); border-radius:6px; background:var(--el-bg-color); box-shadow:var(--el-box-shadow); }
.terminal-search input { width:210px; padding:4px 7px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-bg-color); color:var(--el-text-color-primary); }
.terminal-search button { border:0; background:transparent; color:var(--el-text-color-primary); cursor:pointer; }
.term-container {
  flex: 1;
  min-height: 0;
  padding: 4px;
  background: #1e1e1e;
  overflow: hidden;
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
}
</style>
