import test from 'node:test'
import assert from 'node:assert/strict'
import { bindTerminalSelection } from '../src/utils/terminalSelection.js'

function fixture(platform = 'MacIntel', mode = 'vt200') {
  const doc = new EventTarget(), element = new EventTarget()
  element.ownerDocument = doc
  element.contains = () => true
  doc.defaultView = { navigator: { platform }, MouseEvent: class extends Event {
    constructor(type, { bubbles, cancelable, view, ...init }) {
      super(type, { bubbles, cancelable })
      Object.assign(this, init)
    }
  } }
  const terminal = { element, modes: { mouseTrackingMode: mode }, hasSelection: () => false }
  const binding = bindTerminalSelection(terminal)
  return { doc, element, terminal, binding }
}
function mouse(type, properties = {}) {
  const event = new Event(type, { cancelable: true })
  Object.assign(event, { button: 0, buttons: type === 'mouseup' ? 0 : 1, detail: 1,
    clientX: 50, clientY: 60, altKey: false, shiftKey: false, ctrlKey: false, metaKey: false, ...properties })
  return event
}

test('primary drags and double clicks enter local selection under mouse tracking on Mac and Windows', () => {
  for (const platform of ['MacIntel', 'Win32', 'Linux x86_64', 'iPad']) {
    const { element, binding } = fixture(platform)
    for (const detail of [1, 2, 3]) {
      const event = mouse('mousedown', { detail })
      element.dispatchEvent(event)
      assert.equal(platform === 'MacIntel' ? event.altKey : event.shiftKey, true)
      assert.equal(event.detail, detail)
    }
    binding.dispose()
  }
})

test('release finishes local selection at the document without a terminal mouse report', () => {
  const { element, doc, binding } = fixture()
  element.dispatchEvent(mouse('mousedown'))
  const delivered = []
  const events = []
  doc.addEventListener('mouseup', event => {
    events.push(event)
    delivered.push([event.clientX, event.clientY, event.altKey])
  })
  const release = mouse('mouseup', { clientX: 90, clientY: 100 })
  doc.dispatchEvent(release)
  assert.deepEqual(delivered, [[90, 100, false]], 'one unmodified release ends selection without alt-click cursor input')
  assert.notEqual(events[0], release, 'release must bypass the terminal and finish only its document selection listener')
  binding.dispose()
})

test('wheel events and modified remote clicks stay unchanged; inactive tracking stays native', () => {
  const { element, terminal, binding } = fixture()
  for (const properties of [{ button: 2 }, { button: 1 }, { ctrlKey: true }, { metaKey: true }]) {
    const event = mouse('mousedown', properties)
    element.dispatchEvent(event)
    assert.equal(event.altKey, false)
    assert.equal(event.shiftKey, false)
  }
  const wheel = mouse('wheel', { deltaY: -80 })
  element.dispatchEvent(wheel)
  assert.equal(wheel.defaultPrevented, false)
  assert.equal(wheel.altKey, false)
  terminal.modes.mouseTrackingMode = 'none'
  const ordinary = mouse('mousedown')
  element.dispatchEvent(ordinary)
  assert.equal(ordinary.altKey, false)
  binding.dispose()
  terminal.modes.mouseTrackingMode = 'vt200'
  const after = mouse('mousedown')
  element.dispatchEvent(after)
  assert.equal(after.altKey, false, 'dispose must remove all listeners')
})

test('hover reports cannot clear local selection in all-motion mode', () => {
  const { doc, terminal, binding } = fixture('MacIntel', 'any')
  terminal.hasSelection = () => true
  let reported = 0
  doc.addEventListener('mousemove', () => reported++)
  doc.dispatchEvent(mouse('mousemove', { buttons: 0 }))
  assert.equal(reported, 0)
  doc.dispatchEvent(mouse('mousemove', { buttons: 0, ctrlKey: true }))
  assert.equal(reported, 1)
  binding.dispose()
})

test('right-click press and release preserve selection while the context menu still opens', () => {
  for (const platform of ['MacIntel', 'Win32']) {
    const { element, doc, terminal, binding } = fixture(platform)
    let selected = true, menus = 0
    terminal.hasSelection = () => selected
    element.addEventListener('mousedown', () => { selected = false })
    doc.addEventListener('mouseup', () => { selected = false })
    element.addEventListener('contextmenu', () => { menus++ })
    element.dispatchEvent(mouse('mousedown', { button: 2, buttons: 2 }))
    element.dispatchEvent(mouse('contextmenu', { button: 2 }))
    doc.dispatchEvent(mouse('mouseup', { button: 2 }))
    assert.equal(selected, true, 'opening copy menu must not send mouse input that clears the selection')
    assert.equal(menus, 1)
    binding.dispose()
  }
})

test('Mac Control-click opens the local menu, but Windows Control-click remains remote input', () => {
  for (const platform of ['MacIntel', 'Win32']) {
    const { element, doc, binding } = fixture(platform)
    let reports = 0
    element.addEventListener('mousedown', () => { reports++ })
    doc.addEventListener('mouseup', () => { reports++ })
    element.dispatchEvent(mouse('mousedown', { ctrlKey: true }))
    doc.dispatchEvent(mouse('mouseup')) // The modifier may be released before the button.
    assert.equal(reports, platform === 'MacIntel' ? 0 : 2)
    binding.dispose()
  }
})
