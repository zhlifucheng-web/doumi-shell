<template>
  <section ref="root" class="rdp-desktop">
    <div class="rdp-toolbar">
      <span role="status" class="rdp-status">{{ stateLabel }}</span>
      <el-select v-model="resolution" aria-label="远程桌面分辨率" class="rdp-resolution" :disabled="status.state === 'connecting'">
        <el-option label="窗口分辨率" value="window" />
        <el-option label="1280 × 720" value="1280x720" />
        <el-option label="1920 × 1080" value="1920x1080" />
      </el-select>
      <el-button @click="fit = !fit">{{ fit ? '原始大小' : '适应窗口' }}</el-button>
      <el-button :disabled="status.state !== 'connected'" @click="session.ctrlAltDelete()">Ctrl+Alt+Del</el-button>
      <el-button @click="fullscreen">全屏</el-button>
      <el-button v-if="status.state === 'connected' || status.state === 'connecting'" @click="disconnect">断开</el-button>
      <el-button v-else type="primary" @click="connect">重新连接</el-button>
    </div>
    <p v-if="fullscreenError" class="rdp-notice" role="status">{{ fullscreenError }}</p>
    <div ref="viewport" class="rdp-viewport" :class="{ 'is-fitting': fit }" v-loading="status.state === 'connecting'" element-loading-text="正在连接远程桌面…">
      <div v-if="failure" class="rdp-recovery" role="alert">
        <h3>{{ failure.title }}</h3>
        <p>{{ failure.message }}</p>
        <div class="rdp-recovery-actions">
          <el-button @click="emit('edit')">编辑连接</el-button>
          <el-button type="primary" @click="connect">重试</el-button>
        </div>
        <details v-if="failure.detail" class="rdp-error-details">
          <summary>技术详情</summary>
          <pre>{{ failure.detail }}</pre>
        </details>
      </div>
      <div v-show="!failure" ref="surface" class="rdp-surface" tabindex="0" role="application" :aria-label="`${profile.name} 远程桌面`" @keydown.stop @keyup.stop @keypress.stop @pointerdown="focusDisplay" @focus="session.setFocused(true)" @blur="releaseInput" />
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import Guacamole from 'guacamole-common-js'
import { openRdpSession, closeRdpSession } from '@/api/rdp'
import { createRdpSession, rdpDimensions } from '@/utils/rdpSession'
import { rdpTunnel, trackGuacamoleListeners } from '@/utils/rdpGuacamole'
import { bindRdpFocus } from '@/utils/rdpFocus'
import { describeRdpError } from '@/utils/rdpError'
const props = defineProps({ profile: { type: Object, required: true }, active: Boolean })
const emit = defineEmits(['edit', 'state'])
const fullscreenError = ref('')
const root = ref(), viewport = ref(), surface = ref(), fit = ref(true), resolution = ref('window')
const status = reactive({ state: 'disconnected', error: '' })
const failure = computed(() => status.error ? describeRdpError(status.error, props.profile) : null)
watch(() => [status.state, failure.value?.message], () => emit('state', { state: status.state, error: failure.value?.message || '' }), { immediate: true })
const stateLabel = computed(() => ({ disconnected: '已断开', connecting: '连接中', connected: '已连接', error: '连接失败' }[status.state]))
let display = null, keyboard = null, mouse = null, listenerScope = null, resizeObserver = null, resizeTimer = null, disposeFocus = null
function releaseInput() { session.setFocused(false); keyboard?.reset(); mouse?.reset() }
function focusDisplay() { if (props.active) surface.value?.focus({ preventScroll: true }) }
function scaleDisplay() {
  if (!display || !viewport.value || !display.getWidth() || !display.getHeight()) return
  display.scale(fit.value ? Math.min(viewport.value.clientWidth / display.getWidth(), viewport.value.clientHeight / display.getHeight(), 1) : 1)
}
function updateViewport() {
  scaleDisplay(); clearTimeout(resizeTimer)
  if (resolution.value === 'window' && props.active && status.state === 'connected') {
    resizeTimer = setTimeout(() => {
      if (resolution.value === 'window' && viewport.value?.clientWidth && viewport.value?.clientHeight) session.resize(viewport.value.clientWidth, viewport.value.clientHeight)
    }, 180)
  }
}
function removeDisplay() {
  clearTimeout(resizeTimer)
  keyboard?.reset(); mouse?.reset()
  if (keyboard) { keyboard.onkeydown = null; keyboard.onkeyup = null }
  listenerScope?.dispose(); listenerScope = null; keyboard = null; mouse = null
  if (display) display.onresize = null
  display = null; surface.value?.replaceChildren()
}
const session = createRdpSession({
  openTicket: openRdpSession, closeTicket: closeRdpSession, onChange: value => Object.assign(status, value),
  createTransport(url, callbacks) {
    removeDisplay()
    const scope = trackGuacamoleListeners([window, surface.value], () => {
      const tunnel = rdpTunnel(Guacamole, url), client = new Guacamole.Client(tunnel)
      display = client.getDisplay(); surface.value.appendChild(display.getElement())
      keyboard = new Guacamole.Keyboard(surface.value)
      keyboard.onkeydown = keysym => !session.key(1, keysym)
      keyboard.onkeyup = keysym => session.key(0, keysym)
      mouse = new Guacamole.Mouse(surface.value)
      mouse.onEach(['mousedown', 'mouseup', 'mousemove'], event => session.mouse(event.state))
      display.onresize = scaleDisplay
      client.onstatechange = state => { if (state === 3) { callbacks.state('connected'); updateViewport() } else if (state === 5) callbacks.state('disconnected') }
      client.onerror = error => callbacks.error(error.message || '远程桌面连接失败')
      return { connect: () => client.connect(''), disconnect: () => { client.onstatechange = null; client.onerror = null; try { client.disconnect() } finally { removeDisplay() } }, sendKey: (pressed, keysym) => client.sendKeyEvent(pressed, keysym), sendMouse: state => client.sendMouseState(state, true), sendSize: (width, height) => client.sendSize(width, height) }
    })
    listenerScope = scope
    return scope.value
  }
})
async function connect() {
  fullscreenError.value = ''
  releaseInput()
  await nextTick()
  const [width, height] = resolution.value === 'window' ? [viewport.value?.clientWidth, viewport.value?.clientHeight] : resolution.value.split('x').map(Number)
  await session.connect({ connectionId: props.profile.id, ...rdpDimensions(width, height) })
  session.setActive(props.active)
}
function disconnect() { releaseInput(); session.disconnect(); removeDisplay() }
async function fullscreen() {
  fullscreenError.value = ''
  try { if (document.fullscreenElement === root.value) await document.exitFullscreen(); else await root.value?.requestFullscreen() }
  catch { fullscreenError.value = '浏览器未允许全屏，请使用浏览器全屏功能' }
}
watch(() => props.active, active => { session.setActive(active); if (!active) releaseInput(); else nextTick(updateViewport) })
watch(fit, scaleDisplay)
watch(resolution, () => { if (status.state === 'connected') connect() })
onMounted(() => {
  disposeFocus = bindRdpFocus({ windowTarget: window, documentTarget: document, getSurface: () => surface.value, isActive: () => props.active, setFocused: value => session.setFocused(value), releaseInput })
  resizeObserver = new ResizeObserver(updateViewport); resizeObserver.observe(viewport.value)
  connect()
})
defineExpose({ disconnect, reconnect: connect, focusDisplay })
onDeactivated(disconnect)
onBeforeUnmount(() => {
  disconnect(); resizeObserver?.disconnect()
  disposeFocus?.()
})
</script>

<style scoped>
.rdp-desktop { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--el-bg-color); }
.rdp-toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; padding: 8px 12px; border-bottom: 1px solid var(--el-border-color); }
.rdp-toolbar :deep(.el-button + .el-button) { margin-left: 0; }
.rdp-status { margin-right: auto; font-size: 13px; color: var(--el-text-color-regular); }
.rdp-resolution { width: 150px; }
.rdp-viewport { flex: 1; min-height: 0; overflow: auto; background: #15171b; position: relative; }
.rdp-notice { margin: 0; padding: 8px 12px; color: var(--el-color-warning); font-size: 13px; }
.rdp-recovery { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); box-sizing: border-box; width: min(420px, calc(100% - 32px)); max-height: calc(100% - 32px); overflow: auto; padding: 20px; border: 1px solid var(--el-border-color); border-radius: 8px; color: var(--el-text-color-primary); background: var(--el-bg-color); }
.rdp-recovery h3 { margin: 0 0 8px; color: var(--el-color-danger); font-size: 16px; }
.rdp-recovery p { margin: 0 0 16px; line-height: 1.6; font-size: 13px; color: var(--el-text-color-regular); }
.rdp-recovery-actions { display: flex; gap: 8px; }
.rdp-recovery-actions :deep(.el-button + .el-button) { margin-left: 0; }
.rdp-error-details { margin-top: 16px; font-size: 12px; color: var(--el-text-color-secondary); }
.rdp-error-details summary { cursor: pointer; }
.rdp-error-details pre { white-space: pre-wrap; overflow-wrap: anywhere; margin-bottom: 0; }
.rdp-surface { width: fit-content; min-width: 1px; min-height: 1px; outline: none; }
.rdp-surface:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.rdp-surface :deep(> div) { transform-origin: top left; }
.is-fitting { display: flex; align-items: center; justify-content: center; }
.rdp-desktop:fullscreen { width: 100vw; height: 100vh; }
@media (max-width: 640px) { .rdp-toolbar { padding: 8px; } .rdp-toolbar :deep(.el-button) { min-height: 36px; } }
</style>
