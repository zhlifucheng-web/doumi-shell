import test from 'node:test'
import assert from 'node:assert/strict'
import Guacamole from 'guacamole-common-js'
import { rdpTunnel, trackGuacamoleListeners } from '../src/utils/rdpGuacamole.js'

test('the real Guacamole tunnel connects with its one-use ticket unchanged and the guacamole protocol', () => {
  const oldWindow = globalThis.window, oldSocket = globalThis.WebSocket
  let socketUrl, protocol
  globalThis.window = { location: { protocol: 'https:', host: 'shell.example', pathname: '/' }, setTimeout, clearTimeout }
  globalThis.WebSocket = class { constructor(url, subprotocol) { socketUrl = url; protocol = subprotocol } close() {} }
  try {
    const tunnel = rdpTunnel(Guacamole, 'wss://shell.example/ws/ssh-console/rdp?token=a%2Bb')
    tunnel.connect('')
    assert.equal(new URL(socketUrl).searchParams.get('token'), 'a+b')
    assert.equal(new URL(socketUrl).origin, 'wss://shell.example')
    assert.equal(protocol, 'guacamole'); tunnel.disconnect()
  } finally { globalThis.window = oldWindow; globalThis.WebSocket = oldSocket }
})
test('vendor DOM listeners are removed on disposal and listener methods restored after construction', () => {
  const target = new EventTarget(), original = target.addEventListener; let count = 0
  const tracked = trackGuacamoleListeners([target], () => { target.addEventListener('test', () => { count++ }); return 'client' })
  assert.equal(tracked.value, 'client'); assert.equal(target.addEventListener, original)
  target.dispatchEvent(new Event('test')); assert.equal(count, 1)
  tracked.dispose(); target.dispatchEvent(new Event('test')); assert.equal(count, 1)
})
