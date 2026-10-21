<template>
  <div class="ssh-terminal-wrap">
    <div v-if="statusMessage" class="status-overlay">{{ statusMessage }}</div>
    <div ref="termContainer" class="term-container" :class="[termContainerClass]" :style="termContainerStyle" @click="focusTerminal" />
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
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import EditorScrollbar from '@/components/EditorScrollbar.vue'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import 'xterm/css/xterm.css'
import { buildTerminalWsUrl } from '@/api/sshConsole'
import { useBackground } from '@/composables/useBackground'
import { useTheme } from '@/composables/useTheme'
import { useTerminalTheme } from '@/composables/useTerminalTheme'

const { background } = useBackground()
const { effectiveDark } = useTheme()
const { activeTheme } = useTerminalTheme()

const props = defineProps({
  accountId: { type: Number, required: true },
  fontSize: { type: Number, default: 14 },
  lightTone: { type: String, default: 'paper' } // 'paper' | 'hybrid'
})

const emit = defineEmits([
  'ready',
  'disconnected',
  'connection-error',
  'size-change',
  'ask-ai'
])

const termContainer = ref(null)
const scrollTarget = shallowRef(null)
const statusMessage = ref('连接中…')
const disconnected = ref(false)

const detectedError = ref('')
const dismissedError = ref(false)

let terminal = null
let fitAddon = null
let ws = null
let resizeObserver = null
let pingTimer = null
let dataBound = false
let connectGen = 0

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

function sendResize() {
  if (!terminal || !fitAddon || !ws || ws.readyState !== WebSocket.OPEN || !dataBound) {
    return
  }
  fitAddon.fit()
  sendJson({ type: 'resize', cols: terminal.cols, rows: terminal.rows })
  emit('size-change', { cols: terminal.cols, rows: terminal.rows })
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
  scrollTarget.value = null
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
  if (frame.type === 'ready') {
    activateSession(frame)
    return
  }
  if (frame.type === 'output' && frame.data) {
    if (!dataBound) {
      activateSession(null)
    }
    const decoded = b64Decode(frame.data)
    terminal?.write(decoded)
    inspectErrorOutput(decoded)
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
  statusMessage.value = '连接中…'
  dataBound = false

  terminal = new Terminal({
    cursorBlink: true,
    fontSize: props.fontSize || 14,
    fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, 'Courier New', monospace",
    allowTransparency: true,
    theme: terminalTheme(),
    convertEol: true
  })
  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.loadAddon(new WebLinksAddon())
  terminal.open(termContainer.value)
  scrollTarget.value = termContainer.value.querySelector('.xterm-viewport')
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

// 供父组件调用的指令注入与清屏方法
function sendInput(cmd) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    sendJson({ type: 'input', data: b64Encode(cmd + '\n') })
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
}
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
