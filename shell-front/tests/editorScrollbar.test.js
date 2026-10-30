import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick } from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'

function mountScrollbar() {
  const frames = new Map(), observers = []
  let id = 0, shown = true
  const target = Object.assign(new EventTarget(), {
    classList: { add() {}, remove() {} }, offsetLeft: 0, offsetTop: 0,
    scrollTop: 400, scrollLeft: 30, scrollWidth: 1000, scrollHeight: 2000,
    get clientWidth() { return shown ? 500 : 0 }, get clientHeight() { return shown ? 300 : 0 },
    getClientRects: () => shown ? [{}] : []
  })
  const overlay = { top: 0, left: 0, update() {}, setScrollTop(value) { this.top = value }, setScrollLeft(value) { this.left = value } }
  const Observer = class { constructor(callback) { observers.push(callback) } observe() {} disconnect() {} }
  const panel = mountSfcSetup(new URL('../src/components/EditorScrollbar.vue', import.meta.url), {
    props: { target, active: true },
    globals: { document: new EventTarget(), ResizeObserver: Observer, MutationObserver: Observer,
      requestAnimationFrame: callback => { frames.set(++id, callback); return id }, cancelAnimationFrame: key => frames.delete(key) },
    onSetup: state => { state.scrollbar.value = overlay }
  })
  return { ...panel, target, overlay, observers,
    show(value) { shown = value },
    async frame() { await nextTick(); const pending = [...frames.values()]; frames.clear(); for (const callback of pending) await callback(); await nextTick() }
  }
}

test('a hidden or inactive overlay never writes a collapsed scroll position back to the editor', async () => {
  const panel = mountScrollbar()
  try {
    await panel.frame(); await panel.frame()
    panel.show(false); panel.state.scroll({ scrollTop: 0, scrollLeft: 0 })
    assert.equal(panel.target.scrollTop, 400)
    assert.equal(panel.target.scrollLeft, 30)
    panel.show(true); panel.props.active = false; await nextTick()
    panel.state.scroll({ scrollTop: 0, scrollLeft: 0 })
    assert.equal(panel.target.scrollTop, 400)
  } finally { panel.unmount() }
})

test('synchronizing overlay geometry ignores feedback, then accepts visible user scrolling', async () => {
  const panel = mountScrollbar()
  try {
    await panel.frame(); await panel.frame()
    panel.target.scrollTop = 600
    panel.state.sync()
    panel.state.scroll({ scrollTop: 0, scrollLeft: 0 })
    assert.equal(panel.target.scrollTop, 600)
    await panel.frame()
    panel.overlay.top = 0
    panel.state.scroll({ scrollTop: 0, scrollLeft: 0 })
    await panel.frame(); await panel.frame()
    assert.equal(panel.overlay.top, 600)
    panel.state.scroll({ scrollTop: 250, scrollLeft: 50 })
    assert.equal(panel.target.scrollTop, 250)
    assert.equal(panel.target.scrollLeft, 50)
  } finally { panel.unmount() }
})

test('reactivation synchronizes from the editor instead of using stale overlay coordinates', async () => {
  const panel = mountScrollbar()
  try {
    await panel.frame(); await panel.frame()
    panel.props.active = false; await nextTick(); panel.show(false)
    panel.target.scrollTop = 800
    panel.show(true); panel.props.active = true
    await panel.frame(); await panel.frame()
    assert.equal(panel.overlay.top, 800)
    assert.equal(panel.target.scrollTop, 800)
  } finally { panel.unmount() }
})

test('dragging the thumb accepts consecutive user positions while synchronization is pending', async () => {
  const panel = mountScrollbar()
  try {
    await panel.frame(); await panel.frame()
    panel.state.beginDrag?.({ button: 0 })
    for (const top of [500, 600, 700]) {
      panel.state.sync()
      panel.state.scroll({ scrollTop: top, scrollLeft: 30 })
      assert.equal(panel.target.scrollTop, top)
    }
  } finally { panel.unmount() }
})
