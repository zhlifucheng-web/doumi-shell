import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { createPwaInstaller, registerPwa } from '../src/utils/pwa.js'

function browser({ secure = true, standalone = false } = {}) {
  const win = new EventTarget()
  const displayMode = new EventTarget()
  displayMode.matches = standalone
  win.matchMedia = () => displayMode
  win.isSecureContext = secure
  win.navigator = {}
  return win
}

function installEvent(prompt = async () => {}, outcome = 'accepted') {
  const event = new Event('beforeinstallprompt', { cancelable: true })
  event.prompt = prompt
  event.userChoice = Promise.resolve({ outcome })
  return event
}

test('install prompt is offered only after browser eligibility and consumed once', async () => {
  const win = browser()
  let state
  const installer = createPwaInstaller(win, next => { state = next })
  assert.equal(state.available, false)
  assert.equal(await installer.install(), false)
  let prompted = 0
  const event = installEvent(async () => { prompted++ })
  win.dispatchEvent(event)
  assert.equal(event.defaultPrevented, true)
  assert.equal(state.available, true)
  assert.equal(await installer.install(), true)
  assert.equal(state.available, false)
  assert.equal(state.installed, false)
  assert.equal(await installer.install(), false)
  assert.equal(prompted, 1)
  win.dispatchEvent(new Event('appinstalled'))
  assert.equal(state.installed, true)
  installer.dispose()
})

test('repeated clicks do not invoke a pending prompt twice; dismissals allow later browser offers', async () => {
  const win = browser()
  let state, finish
  const installer = createPwaInstaller(win, next => { state = next })
  const event = installEvent(() => new Promise(resolve => { finish = resolve }), 'dismissed')
  win.dispatchEvent(event)
  const pending = installer.install()
  assert.equal(state.installing, true)
  assert.equal(await installer.install(), false)
  finish()
  assert.equal(await pending, true)
  assert.equal(state.installing, false)
  assert.equal(state.installed, false)
  win.dispatchEvent(installEvent())
  assert.equal(state.available, true)
  installer.dispose()
})

test('prompt errors recover and disposed controllers stop listening', async () => {
  const win = browser()
  let state
  const installer = createPwaInstaller(win, next => { state = next })
  win.dispatchEvent(installEvent(async () => { throw new Error('blocked') }))
  assert.equal(await installer.install(), false)
  assert.equal(state.installing, false)
  installer.dispose()
  const event = installEvent()
  win.dispatchEvent(event)
  assert.equal(event.defaultPrevented, false)
  assert.equal(state.available, false)
})

test('standalone desktop and iOS apps are already installed', () => {
  for (const ios of [false, true]) {
    const win = browser({ standalone: !ios })
    win.navigator.standalone = ios
    let state
    const installer = createPwaInstaller(win, next => { state = next })
    assert.equal(state.installed, true)
    installer.dispose()
  }
})

test('worker registration requires a secure context and bypasses cached update checks', async t => {
  t.mock.method(console, 'warn', () => {})
  const win = browser({ secure: false })
  const registrations = []
  win.navigator.serviceWorker = { register: async (...args) => { registrations.push(args); return 'registered' } }
  assert.equal(await registerPwa(win), null)
  assert.deepEqual(registrations, [])
  win.isSecureContext = true
  assert.equal(await registerPwa(win), 'registered')
  assert.deepEqual(registrations, [['/sw.js', { scope: '/', updateViaCache: 'none' }]])
  win.navigator.serviceWorker.register = async () => { throw new Error('certificate rejected') }
  assert.equal(await registerPwa(win), null)
})

test('worker leaves API, transfers and assets alone; navigations use the network', async () => {
  const handlers = {}
  const requests = []
  vm.runInNewContext(await readFile(new URL('../public/sw.js', import.meta.url), 'utf8'), {
    self: { addEventListener: (name, handler) => { handlers[name] = handler } },
    fetch: request => { requests.push(request); return Promise.resolve('network response') }
  })
  for (const url of ['/api/executions', '/api/sftp/download', '/version.json', '/assets/index.js']) {
    handlers.fetch({ request: { mode: 'cors', url }, respondWith: () => assert.fail('must not intercept data') })
  }
  assert.deepEqual(requests, [])
  const request = { mode: 'navigate', url: '/' }
  let response
  handlers.fetch({ request, respondWith: value => { response = value } })
  assert.equal(await response, 'network response')
  assert.deepEqual(requests, [request])
})
