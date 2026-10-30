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
    <el-alert v-if="status.error" :title="status.error" type="error" :closable="false" show-icon />
    <div ref="viewport" class="rdp-viewport" :class="{ 'is-fitting': fit }" v-loading="status.state === 'connecting'" element-loading-text="正在连接远程桌面…">
      <div ref="surface" class="rdp-surface" tabindex="0" role="application" :aria-label="`${profile.name} 远程桌面`" @keydown.stop @keyup.stop @keypress.stop @pointerdown="focusDisplay" @focus="session.setFocused(true)" @blur="releaseInput" />
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
const props = defineProps({ profile: { type: Object, required: true }, active: Boolean })
const root = ref(), viewport = ref(), surface = ref(), fit = ref(true), resolution = ref('window')
const status = reactive({ state: 'disconnected', error: '' })
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
      client.onerror = error => callbacks.error(error.message || '远程桌面连接失败，请检查 guacd、Windows RDP、证书和凭据')
      return { connect: () => client.connect(''), disconnect: () => { client.onstatechange = null; client.onerror = null; try { client.disconnect() } finally { removeDisplay() } }, sendKey: (pressed, keysym) => client.sendKeyEvent(pressed, keysym), sendMouse: state => client.sendMouseState(state, true), sendSize: (width, height) => client.sendSize(width, height) }
    })
    listenerScope = scope
    return scope.value
  }
})
async function connect() {
  await nextTick()
  const [width, height] = resolution.value === 'window' ? [viewport.value?.clientWidth, viewport.value?.clientHeight] : resolution.value.split('x').map(Number)
  await session.connect({ connectionId: props.profile.id, ...rdpDimensions(width, height) })
  session.setActive(props.active)
}
function disconnect() { releaseInput(); session.disconnect(); removeDisplay() }
async function fullscreen() {
  try { if (document.fullscreenElement === root.value) await document.exitFullscreen(); else await root.value?.requestFullscreen() }
  catch { status.error = '浏览器未允许全屏，请使用浏览器全屏功能' }
}
watch(() => props.active, active => { session.setActive(active); if (!active) releaseInput(); else nextTick(updateViewport) })
watch(fit, scaleDisplay)
watch(resolution, () => { if (status.state === 'connected') connect() })
onMounted(() => {
  disposeFocus = bindRdpFocus({ windowTarget: window, documentTarget: document, getSurface: () => surface.value, isActive: () => props.active, setFocused: value => session.setFocused(value), releaseInput })
  resizeObserver = new ResizeObserver(updateViewport); resizeObserver.observe(viewport.value)
  connect()
})
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
.rdp-surface { width: fit-content; min-width: 1px; min-height: 1px; outline: none; }
.rdp-surface:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.rdp-surface :deep(> div) { transform-origin: top left; }
.is-fitting { display: flex; align-items: center; justify-content: center; }
.rdp-desktop:fullscreen { width: 100vw; height: 100vh; }
@media (max-width: 640px) { .rdp-toolbar { padding: 8px; } .rdp-toolbar :deep(.el-button) { min-height: 36px; } }
</style>
