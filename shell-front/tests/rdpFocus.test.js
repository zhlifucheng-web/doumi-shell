import test from 'node:test'
import assert from 'node:assert/strict'
import { bindRdpFocus } from '../src/utils/rdpFocus.js'

function fixture() {
  const windowTarget = new EventTarget(), documentTarget = new EventTarget(), child = {}, toolbar = {}
  const surface = { contains: element => element === surface || element === child }
  documentTarget.hidden = false; documentTarget.activeElement = surface
  let active = true, focused = true, releases = 0
  const dispose = bindRdpFocus({ windowTarget, documentTarget, getSurface: () => surface, isActive: () => active, setFocused: value => { focused = value }, releaseInput: () => { focused = false; releases++ } })
  return { windowTarget, documentTarget, surface, child, toolbar, dispose, setActive: value => { active = value }, get focused() { return focused }, get releases() { return releases } }
}
test('returning to an app restores input when the desktop retained DOM focus', () => {
  const f = fixture(); f.windowTarget.dispatchEvent(new Event('blur'))
  assert.equal(f.focused, false); assert.equal(f.releases, 1)
  f.windowTarget.dispatchEvent(new Event('focus')); assert.equal(f.focused, true)
  f.dispose()
})
test('a returning browser tab restores focused desktop descendants after releasing hidden input', () => {
  const f = fixture(); f.documentTarget.hidden = true; f.documentTarget.dispatchEvent(new Event('visibilitychange'))
  assert.equal(f.focused, false); assert.equal(f.releases, 1)
  f.documentTarget.activeElement = f.child; f.documentTarget.hidden = false
  f.documentTarget.dispatchEvent(new Event('visibilitychange')); assert.equal(f.focused, true)
  f.dispose()
})
test('returning focus never captures toolbar controls, hidden documents or inactive desktops', () => {
  const f = fixture(); f.windowTarget.dispatchEvent(new Event('blur'))
  f.documentTarget.activeElement = f.toolbar; f.windowTarget.dispatchEvent(new Event('focus')); assert.equal(f.focused, false)
  f.documentTarget.activeElement = f.surface; f.documentTarget.hidden = true
  f.windowTarget.dispatchEvent(new Event('focus')); assert.equal(f.focused, false)
  f.documentTarget.hidden = false; f.setActive(false)
  f.documentTarget.dispatchEvent(new Event('visibilitychange')); assert.equal(f.focused, false)
  f.dispose()
})
test('disposing focus lifecycle handlers prevents later window and document events from changing input', () => {
  const f = fixture(); f.windowTarget.dispatchEvent(new Event('blur')); f.dispose()
  f.windowTarget.dispatchEvent(new Event('focus')); assert.equal(f.focused, false)
  f.documentTarget.hidden = true; f.documentTarget.dispatchEvent(new Event('visibilitychange'))
  assert.equal(f.releases, 1)
})
