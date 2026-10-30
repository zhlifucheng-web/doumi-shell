import test from 'node:test'
import assert from 'node:assert/strict'
import { createRdpSession, buildRdpWsUrl, rdpDimensions } from '../src/utils/rdpSession.js'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function fixture(openTicket = async () => ({ token: 'ticket' })) {
  const closed = [], transports = []
  const session = createRdpSession({ openTicket, closeTicket: async token => { closed.push(token) }, createTransport: (url, callbacks) => {
    const events = []
    const transport = { url, callbacks, events, connect: () => events.push('connect'), disconnect: () => events.push('disconnect'), sendKey: (...args) => events.push(['key', ...args]), sendMouse: state => events.push(['mouse', state]) }
    transports.push(transport); return transport
  }, makeUrl: token => buildRdpWsUrl(token, 'https://shell.example') })
  return { session, closed, transports }
}
test('websocket tickets are URL encoded on the same origin and contain no credentials', () => {
  assert.equal(buildRdpWsUrl('a+b /?', 'https://shell.example/path'), 'wss://shell.example/ws/ssh-console/rdp?token=a%2Bb+%2F%3F')
  assert.equal(buildRdpWsUrl('t', 'http://localhost:3100'), 'ws://localhost:3100/ws/ssh-console/rdp?token=t')
})
test('session dimensions reject invalid values and bound display size', () => {
  assert.deepEqual(rdpDimensions(0, NaN, 999), { width: 1024, height: 768, dpi: 192 })
  assert.deepEqual(rdpDimensions(8000, 200, 96), { width: 3840, height: 480, dpi: 96 })
})
test('inactive and blurred desktops release every key and mouse button and stop sending input', async () => {
  const { session, transports } = fixture(); await session.connect({ connectionId: 7 })
  session.setActive(true); session.setFocused(true)
  session.key(1, 65507); session.key(1, 97)
  session.mouse({ x: 8, y: 9, left: true, right: false, middle: false, up: false, down: false })
  session.setFocused(false); session.key(1, 98)
  assert.deepEqual(transports[0].events.slice(-3), [['key', 0, 65507], ['key', 0, 97], ['mouse', { x: 8, y: 9, left: false, right: false, middle: false, up: false, down: false }]])
  session.setFocused(true); session.key(1, 99); session.setActive(false)
  assert.deepEqual(transports[0].events.at(-1), ['key', 0, 99])
})
test('closing during ticket creation cancels that ticket without opening a socket', async () => {
  const pending = deferred(), { session, closed, transports } = fixture(() => pending.promise)
  const connecting = session.connect({ connectionId: 1 }); session.disconnect(); pending.resolve({ token: 'late' }); await connecting
  assert.deepEqual(closed, ['late']); assert.equal(transports.length, 0)
})
test('reconnect supersedes a pending ticket and ignores stale socket callbacks', async () => {
  const pending = deferred(); let count = 0
  const { session, closed, transports } = fixture(() => ++count === 1 ? pending.promise : Promise.resolve({ token: 'fresh' }))
  const old = session.connect({ connectionId: 1 }); await session.connect({ connectionId: 1 })
  pending.resolve({ token: 'old' }); await old
  assert.deepEqual(closed, ['old']); assert.equal(transports.length, 1)
  transports[0].callbacks.state('connected'); assert.equal(session.state, 'connected')
  session.disconnect(); transports[0].callbacks.state('connected'); assert.equal(session.state, 'disconnected')
  assert.deepEqual(closed, ['old', 'fresh']); assert.equal(transports[0].events.at(-1), 'disconnect')
})
test('transport failures close server sessions and show actionable error state', async () => {
  const { session, transports, closed } = fixture(); await session.connect({ connectionId: 1 })
  transports[0].callbacks.error('guacd unavailable')
  assert.equal(session.state, 'error'); assert.equal(session.error, 'guacd unavailable'); assert.deepEqual(closed, ['ticket'])
})
test('a failing socket close still cancels its server session', async () => {
  const { session, transports, closed } = fixture(); await session.connect({ connectionId: 1 })
  transports[0].disconnect = () => { throw new Error('socket already closed') }
  assert.doesNotThrow(() => session.disconnect())
  assert.deepEqual(closed, ['ticket']); assert.equal(session.state, 'disconnected')
})
test('Ctrl Alt Delete releases prior input and sends the chord in reverse release order', async () => {
  const { session, transports } = fixture(); await session.connect({ connectionId: 1 })
  transports[0].callbacks.state('connected'); session.setActive(true); session.setFocused(true); session.key(1, 97)
  session.ctrlAltDelete()
  assert.deepEqual(transports[0].events.slice(-7), [['key', 0, 97], ['key', 1, 65507], ['key', 1, 65513], ['key', 1, 65535], ['key', 0, 65535], ['key', 0, 65513], ['key', 0, 65507]])
})
test('active connected window resolution changes are bounded and hidden sessions never resize', async () => {
  const { session, transports } = fixture(); await session.connect({ connectionId: 1, width: 1024, height: 768 })
  transports[0].sendSize = (width, height) => transports[0].events.push(['size', width, height])
  session.setActive(true); session.resize(8000, 200)
  assert.equal(transports[0].events.some(event => event[0] === 'size'), false)
  transports[0].callbacks.state('connected'); session.resize(8000, 200)
  assert.deepEqual(transports[0].events.at(-1), ['size', 3840, 480])
  const count = transports[0].events.length; session.resize(8000, 200)
  assert.equal(transports[0].events.length, count)
  session.setActive(false); session.resize(1920, 1080)
  assert.equal(transports[0].events.length, count)
})

test('mouse motion coalesces per frame while wheel, button and key transitions retain order', async () => {
  const sent=[],frames=new Map();let frameId=0
  const session=createRdpSession({openTicket:async()=>({token:'ticket'}),closeTicket:async()=>{},makeUrl:()=>'',
    scheduleMouse:callback=>{frames.set(++frameId,callback);return frameId},cancelMouse:id=>frames.delete(id),
    createTransport:()=>({connect(){},disconnect(){},sendMouse:state=>sent.push(['mouse',state.x,state.y,state.down,state.left]),sendKey:(...args)=>sent.push(['key',...args])})})
  await session.connect({connectionId:1});session.setActive(true);session.setFocused(true)
  for(let x=0;x<100;x++)session.mouse({x,y:20})
  assert.equal(sent.length,0)
  assert.equal(frames.size,1)
  for(const fn of frames.values())fn();frames.clear()
  assert.deepEqual(sent,[['mouse',99,20,false,false]])
  session.mouse({x:100,y:20});session.mouse({x:100,y:20,down:true});session.mouse({x:100,y:20,down:false})
  assert.deepEqual(sent.slice(-3),[['mouse',100,20,false,false],['mouse',100,20,true,false],['mouse',100,20,false,false]])
  session.mouse({x:101,y:21});session.key(1,65)
  assert.deepEqual(sent.slice(-2),[['mouse',101,21,false,false],['key',1,65]])
  for(let i=0;i<20;i++){session.mouse({x:101,y:21,down:true});session.mouse({x:101,y:21,down:false})}
  assert.equal(sent.filter(item=>item[0]==='mouse' && item[3]).length,21)
  const count=sent.length;session.mouse({x:500,y:500});session.setFocused(false)
  for(const fn of frames.values())fn()
  assert.equal(sent.length,count+1) // only release the pressed key; no stale motion
  assert.deepEqual(sent.at(-1),['key',0,65])
})
