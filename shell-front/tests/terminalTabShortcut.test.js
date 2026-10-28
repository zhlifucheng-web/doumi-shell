import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseTabSwitchShortcut,
  resolveTargetTab
} from '../src/utils/terminalTabShortcut.js'

test('parseTabSwitchShortcut matches Ctrl+1 through Ctrl+9 and Ctrl+0', () => {
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '1', code: 'Digit1' }), { type: 'index', index: 1 })
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '2', code: 'Digit2' }), { type: 'index', index: 2 })
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '3', code: 'Digit3' }), { type: 'index', index: 3 })
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '4', code: 'Digit4' }), { type: 'index', index: 4 })
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '9', code: 'Digit9' }), { type: 'index', index: 9 })
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '0', code: 'Digit0' }), { type: 'last' })
})

test('parseTabSwitchShortcut matches Mac Cmd+1~9 and Cmd+0', () => {
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, key: '1', code: 'Digit1' }), { type: 'index', index: 1 })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, key: '2', code: 'Digit2' }), { type: 'index', index: 2 })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, key: '4', code: 'Digit4' }), { type: 'index', index: 4 })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, key: '0', code: 'Digit0' }), { type: 'last' })
})

test('parseTabSwitchShortcut matches Mac Cmd+Option+1~9 without browser tab collisions', () => {
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, altKey: true, key: '1', code: 'Digit1' }), { type: 'index', index: 1 })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, altKey: true, key: '¡', code: 'Digit1' }), { type: 'index', index: 1 })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, altKey: true, key: '4', code: 'Digit4' }), { type: 'index', index: 4 })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, altKey: true, key: '0', code: 'Digit0' }), { type: 'last' })
})

test('parseTabSwitchShortcut matches Alt/Option+1~9 and Mac Option dead keys', () => {
  assert.deepEqual(parseTabSwitchShortcut({ altKey: true, key: '1', code: 'Digit1' }), { type: 'index', index: 1 })
  assert.deepEqual(parseTabSwitchShortcut({ altKey: true, key: '4', code: 'Digit4' }), { type: 'index', index: 4 })
  assert.deepEqual(parseTabSwitchShortcut({ altKey: true, key: '¡', code: 'Digit1' }), { type: 'index', index: 1 })
  assert.deepEqual(parseTabSwitchShortcut({ altKey: true, key: '™', code: 'Digit2' }), { type: 'index', index: 2 })
  assert.deepEqual(parseTabSwitchShortcut({ altKey: true, key: '0', code: 'Digit0' }), { type: 'last' })
})

test('parseTabSwitchShortcut supports numpad digits', () => {
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '1', code: 'Numpad1' }), { type: 'index', index: 1 })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, key: '3', code: 'Numpad3' }), { type: 'index', index: 3 })
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: '0', code: 'Numpad0' }), { type: 'last' })
})

test('parseTabSwitchShortcut matches PageUp and PageDown for cycle navigation', () => {
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: 'PageUp', code: 'PageUp' }), { type: 'prev' })
  assert.deepEqual(parseTabSwitchShortcut({ ctrlKey: true, key: 'PageDown', code: 'PageDown' }), { type: 'next' })
  assert.deepEqual(parseTabSwitchShortcut({ altKey: true, key: 'PageUp', code: 'PageUp' }), { type: 'prev' })
  assert.deepEqual(parseTabSwitchShortcut({ altKey: true, key: 'PageDown', code: 'PageDown' }), { type: 'next' })
  assert.deepEqual(parseTabSwitchShortcut({ metaKey: true, altKey: true, key: 'PageUp', code: 'PageUp' }), { type: 'prev' })
})

test('parseTabSwitchShortcut ignores non-tab shortcuts and modifier combinations', () => {
  assert.equal(parseTabSwitchShortcut(null), null)
  assert.equal(parseTabSwitchShortcut({ ctrlKey: true, key: 'c', code: 'KeyC' }), null)
  assert.equal(parseTabSwitchShortcut({ metaKey: true, key: 'c', code: 'KeyC' }), null)
  assert.equal(parseTabSwitchShortcut({ ctrlKey: true, key: 'k', code: 'KeyK' }), null)
  assert.equal(parseTabSwitchShortcut({ metaKey: true, key: 'k', code: 'KeyK' }), null)
  assert.equal(parseTabSwitchShortcut({ ctrlKey: true, shiftKey: true, key: '1', code: 'Digit1' }), null)
  assert.equal(parseTabSwitchShortcut({ metaKey: true, shiftKey: true, key: '1', code: 'Digit1' }), null)
  assert.equal(parseTabSwitchShortcut({ key: '1', code: 'Digit1' }), null)
})

test('resolveTargetTab resolves direct index targeting', () => {
  const tabs = [
    { tabId: 'tab-1', label: 'Host 1' },
    { tabId: 'tab-2', label: 'Host 2' },
    { tabId: 'tab-3', label: 'Host 3' }
  ]
  assert.equal(resolveTargetTab(tabs, 'tab-1', { type: 'index', index: 1 }), tabs[0])
  assert.equal(resolveTargetTab(tabs, 'tab-1', { type: 'index', index: 2 }), tabs[1])
  assert.equal(resolveTargetTab(tabs, 'tab-1', { type: 'index', index: 3 }), tabs[2])
  assert.equal(resolveTargetTab(tabs, 'tab-1', { type: 'index', index: 4 }), null)
  assert.equal(resolveTargetTab(tabs, 'tab-1', { type: 'last' }), tabs[2])
})

test('resolveTargetTab wraps around on next and prev navigation', () => {
  const tabs = [
    { tabId: 'tab-1', label: 'Host 1' },
    { tabId: 'tab-2', label: 'Host 2' },
    { tabId: 'tab-3', label: 'Host 3' }
  ]
  assert.equal(resolveTargetTab(tabs, 'tab-1', { type: 'next' }), tabs[1])
  assert.equal(resolveTargetTab(tabs, 'tab-3', { type: 'next' }), tabs[0])
  assert.equal(resolveTargetTab(tabs, 'tab-1', { type: 'prev' }), tabs[2])
  assert.equal(resolveTargetTab(tabs, 'tab-2', { type: 'prev' }), tabs[0])
})

test('resolveTargetTab handles empty tabs gracefully', () => {
  assert.equal(resolveTargetTab([], null, { type: 'index', index: 1 }), null)
  assert.equal(resolveTargetTab(null, null, { type: 'next' }), null)
})
