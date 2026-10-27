import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { useLayoutPreference } from '../src/composables/useLayoutPreference.js'

function storageWith(initial = {}) {
  const values = new Map(Object.entries(initial))
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) }
}

const fields = [
  ['sidebarWidth', 'doumi-sidebar-width', 340, 280],
  ['sidebarCollapsed', 'doumi-sidebar-collapsed', true, false],
  ['monitorWidth', 'doumi-monitor-width-v2', 520, 380],
  ['monitorExpanded', 'doumi-monitor-expanded', true, false],
  ['terminalPercent', 'doumi-terminal-height-percent', 45, 60],
  ['filesCollapsed', 'doumi-terminal-files-collapsed', true, false]
]

for (const [name, key, changed, defaultValue] of fields) {
  test(`${name} survives closing and reopening the workspace`, () => {
    const storage = storageWith()
    const first = effectScope()
    const value = first.run(() => useLayoutPreference(name, storage))
    assert.equal(value.value, defaultValue)
    value.value = changed
    // Save synchronously, including a drag immediately followed by navigation.
    assert.equal(storage.getItem(key), String(changed))
    first.stop()
    const reopened = effectScope()
    try { assert.equal(reopened.run(() => useLayoutPreference(name, storage)).value, changed) }
    finally { reopened.stop() }
  })
}

test('layout preferences read existing browser keys without resetting user sizes', () => {
  const scope = effectScope()
  try {
    const storage = storageWith({ 'doumi-sidebar-width': '412', 'doumi-monitor-width-v2': '602', 'doumi-terminal-height-percent': '35' })
    scope.run(() => {
      assert.equal(useLayoutPreference('sidebarWidth', storage).value, 412)
      assert.equal(useLayoutPreference('monitorWidth', storage).value, 602)
      assert.equal(useLayoutPreference('terminalPercent', storage).value, 35)
    })
  } finally { scope.stop() }
})

test('missing, corrupted and out-of-range layout values use defaults', () => {
  const scope = effectScope()
  try {
    for (const bad of ['', 'NaN', 'null', '-1', '99999', 'Infinity']) {
      scope.run(() => assert.equal(useLayoutPreference('terminalPercent', storageWith({ 'doumi-terminal-height-percent': bad })).value, 60))
    }
    scope.run(() => assert.equal(useLayoutPreference('filesCollapsed', storageWith({ 'doumi-terminal-files-collapsed': 'not-a-boolean' })).value, false))
  } finally { scope.stop() }
})

test('unavailable browser storage leaves layout controls usable in memory', () => {
  const scope = effectScope()
  const storage = { getItem() { throw new Error('blocked') }, setItem() { throw new Error('quota') } }
  try {
    const value = scope.run(() => useLayoutPreference('sidebarWidth', storage))
    assert.equal(value.value, 280)
    value.value = 400
    assert.equal(value.value, 400)
  } finally { scope.stop() }
})

test('the real terminal/file layout restores both its split ratio and collapsed state', async () => {
  const { mountSfcSetup } = await import('./helpers/mountSfcSetup.js')
  const vue = await import('vue')
  const storage = storageWith()
  const mount = () => mountSfcSetup(new URL('../src/components/ssh/TerminalFileLayout.vue', import.meta.url), {
    modules: {
      '@/composables/useLayoutPreference': { useLayoutPreference: name => useLayoutPreference(name, storage) },
      pinia: { storeToRefs: store => store },
      '@/stores/workspace': { useWorkspaceStore: () => ({ fileLocation: vue.ref(null) }) }
    }
  })
  const first = mount()
  first.state.setRatio(45)
  first.state.collapsed.value = true
  first.unmount()
  const reopened = mount()
  try {
    assert.equal(reopened.state.ratio.value, 45)
    assert.equal(reopened.state.collapsed.value, true)
    reopened.state.collapsed.value = false
    assert.equal(reopened.state.ratio.value, 45)
  } finally { reopened.unmount() }
})
