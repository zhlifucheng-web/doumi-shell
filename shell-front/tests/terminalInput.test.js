import test from 'node:test'
import assert from 'node:assert/strict'
import { bindTerminalInput } from '../src/utils/terminalInput.js'

function fixture(encoding = 'GBK') {
  const handlers = {}, frames = []
  bindTerminalInput({
    onData(handler) { handlers.data = handler },
    onBinary(handler) { handlers.binary = handler }
  }, frame => frames.push(frame), () => encoding)
  return { handlers, frames }
}

test('text input retains selected encoding and UTF-8 transport', () => {
  const { handlers, frames } = fixture()
  handlers.data('中文\r')
  assert.deepEqual(frames, [{ type: 'input', data: Buffer.from('中文\r').toString('base64'), encoding: 'GBK' }])
})

test('legacy mouse reports preserve raw bytes outside ASCII', () => {
  const { handlers, frames } = fixture()
  handlers.binary('\x1b[M`\xc3\xa9')
  assert.deepEqual([...Buffer.from(frames[0].data, 'base64')], [27, 91, 77, 96, 195, 169])
  assert.equal(frames[0].encoding, 'UTF-8', 'mouse bytes must bypass text transcoding')
})

test('UTF-8 and SGR mouse reports bypass the selected legacy text encoding', () => {
  const { handlers, frames } = fixture()
  for (const report of ['\x1b[M`中文', '\x1b[<65;10;8M', '\x1b[<0;10;8m']) {
    handlers.data(report)
    assert.equal(frames.at(-1).encoding, 'UTF-8')
    assert.equal(Buffer.from(frames.at(-1).data, 'base64').toString('utf8'), report)
  }
  handlers.data('pasted \x1b[<65;10;8M text')
  assert.equal(frames.at(-1).encoding, 'GBK')
})

test('Enter returns a scrolled terminal to live output before sending the original input once', () => {
  const handlers = {}, events = [], buffer = { viewportY: 10, baseY: 80 }
  const terminal = {
    buffer: { active: buffer },
    scrollToBottom() { events.push('scroll'); buffer.viewportY = buffer.baseY },
    onData(handler) { handlers.data = handler },
    onBinary(handler) { handlers.binary = handler }
  }
  bindTerminalInput(terminal, frame => events.push({ frame, viewportY: buffer.viewportY }), () => 'GBK')
  for (const enter of ['\r', '\n', '\r\n']) {
    buffer.viewportY = 10; events.length = 0; handlers.data(enter)
    assert.deepEqual(events, ['scroll', { frame: { type: 'input', data: Buffer.from(enter).toString('base64'), encoding: 'GBK' }, viewportY: 80 }])
  }
  events.length = 0; handlers.data('\r')
  assert.equal(events.length, 1, 'already live: send once without unnecessary scroll')
})

test('reading history survives typing, pasted multiline text, and mouse reports until Enter', () => {
  const handlers = {}, buffer = { viewportY: 10, baseY: 80 }, frames = []
  const terminal = {
    buffer: { active: buffer }, scrollToBottom() { buffer.viewportY = buffer.baseY },
    onData(handler) { handlers.data = handler }, onBinary(handler) { handlers.binary = handler }
  }
  bindTerminalInput(terminal, frame => frames.push(frame), () => 'UTF-8')
  for (const data of ['a', 'pasted\ntext\r', '\x1b[<64;10;8M']) handlers.data(data)
  handlers.binary('\x1b[M`\x0d\x20')
  assert.equal(buffer.viewportY, 10)
  assert.equal(frames.length, 4)
  handlers.data('\r');assert.equal(buffer.viewportY, 80);assert.equal(frames.length, 5)
})
