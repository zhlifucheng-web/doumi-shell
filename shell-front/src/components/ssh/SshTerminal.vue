<template>
  <div class="ssh-terminal-wrap">
    <div v-if="statusMessage" class="status-overlay">{{ statusMessage }}</div>
    <span v-if="connectionMode" class="connection-mode" :title="connectionMode">{{ connectionMode }}</span>
    <div ref="termContainer" class="term-container" @click="focusTerminal" />
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
import 'xterm/css/xterm.css'
import { buildTerminalWsUrl } from '@/api/sshConsole'
import { useBackground } from '@/composables/useBackground'
import { useTheme } from '@/composables/useTheme'
const {background}=useBackground()
const {effectiveDark}=useTheme()

const props = defineProps({
  accountId: { type: Number, required: true }
})

const emit = defineEmits(['ready', 'disconnected', 'connection-error'])

const termContainer = ref(null)
const scrollTarget = shallowRef(null)
const statusMessage = ref('连接中…')
const disconnected = ref(false)
const connectionMode = ref('')

let terminal = null
let fitAddon = null
let ws = null
let resizeObserver = null
let pingTimer = null
let dataBound = false
let connectGen = 0
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
  terminal.open(termContainer.value)
  scrollTarget.value=termContainer.value.querySelector('.xterm-viewport')
  fitAddon.fit()

  const url = buildTerminalWsUrl(props.accountId, {
    cols: terminal.cols,
    rows: terminal.rows
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
