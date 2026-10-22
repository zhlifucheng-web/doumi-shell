import test from 'node:test'
import assert from 'node:assert/strict'
import { createTerminalConnectionGuard, isExpiredTerminalSession } from '../src/utils/terminalConnectionGuard.js'

function setup() {
  let now = 0, seq = 0
  const timers = new Map(), failures = [], pings = []
  const guard = createTerminalConnectionGuard({
    startupTimeoutMs: 100, heartbeatIntervalMs: 30, pongTimeoutMs: 10,
    setTimer: (fn, ms) => { const id = ++seq; timers.set(id, { fn, at: now + ms }); return id },
    clearTimer: id => timers.delete(id),
    onFailure: message => failures.push(message), sendPing: () => pings.push(now)
  })
  function advance(ms) {
    const end = now + ms
    while (true) {
      const next = [...timers.entries()].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0]
      if (!next) break
      now=next[1].at; timers.delete(next[0]); next[1].fn()
    }
    now=end
  }
  return { guard, failures, pings, timers, advance }
}

test('startup without readiness fails once and clears all timers',()=>{
  const f=setup(); f.advance(100)
  assert.equal(f.failures.length,1); assert.match(f.failures[0],/超时/)
  f.guard.fail('late error'); f.advance(1000)
  assert.equal(f.failures.length,1); assert.equal(f.timers.size,0)
})
test('missing pong disconnects instead of leaving an apparently healthy terminal',()=>{
  const f=setup(); f.guard.ready(); f.advance(30)
  assert.deepEqual(f.pings,[30]); f.advance(10)
  assert.equal(f.failures.length,1); assert.match(f.failures[0],/心跳/)
})
test('pong keeps a ready interactive terminal alive beyond startup deadline',()=>{
  const f=setup(); f.guard.ready()
  for(let i=0;i<8;i++){f.advance(30);f.guard.pong()}
  assert.equal(f.failures.length,0); assert.equal(f.pings.length,8)
  f.guard.dispose(); f.advance(1000); assert.equal(f.timers.size,0)
})
test('disposed old connections cannot disconnect a replacement connection',()=>{
  const old=setup(), current=setup(); old.guard.dispose(); old.guard.fail('late close'); old.advance(1000)
  assert.equal(old.failures.length,0); current.guard.ready(); current.advance(30); current.guard.pong()
  assert.equal(current.failures.length,0)
})
test('only invalid SSH sessions stop monitoring, transient sampling errors do not',()=>{
  assert.equal(isExpiredTerminalSession(new Error('会话不存在或已过期')),true)
  assert.equal(isExpiredTerminalSession(new Error('SSH 未连接')),true)
  assert.equal(isExpiredTerminalSession(new Error('采样超时')),false)
})
