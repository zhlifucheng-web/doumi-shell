import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import { ref } from 'vue'
import { matchesConnection } from '../src/utils/connectionTabs.js'

const { descriptor } = parse(readFileSync(new URL('../src/views/ssh/SshConsole.vue', import.meta.url), 'utf8'))
const compiled = compileScript(descriptor, { id: 'connection-selection' })
const declaration = compiled.scriptSetupAst.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'loadDetail')
const production = descriptor.scriptSetup.content.slice(declaration.start, declaration.end)

for (const outcome of ['newer-selection', 'close-all', 'load-failure']) test(`new RDP connection handles ${outcome} without stale activation`, async () => {
  const opening = compiled.scriptSetupAst.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'openNewAccountTerminal')
  const source = descriptor.scriptSetup.content.slice(opening.start, opening.end)
  const detail = ref({ type: null, raw: null }), pending = new Map(), opened = [], errors = []
  const deps = {
    detail, hostAccounts: ref([]), isRdp: value => value.nodeType === 'RDP_ACCOUNT',
    getRdpConnection: id => new Promise((resolve, reject) => pending.set(id, { resolve, reject })),
    loadAccountHostMeta: async hostId => ({ id: hostId, groupId: 1 }),
    activateRdpForAccount: account => opened.push(account.id), persistAccountSelection: async () => {},
    accountTab: ref('terminal'), router: { push: async () => {} }, ElMessage: { error: value => errors.push(value) }
  }
  const harness = new Function(...Object.keys(deps), `let detailRequest = 0; ${production}; ${source}; return { loadDetail, openNewAccountTerminal, closeAll: () => detailRequest++ }`)(...Object.values(deps))
  const first = harness.openNewAccountTerminal({ nodeType: 'RDP_ACCOUNT', id: 1 })
  if (outcome === 'newer-selection') {
    const second = harness.loadDetail({ nodeType: 'RDP_ACCOUNT', id: 2 })
    pending.get(2).resolve({ id: 2, hostId: 1 }); await second
  } else if (outcome === 'close-all') harness.closeAll()
  if (outcome === 'load-failure') pending.get(1).reject(new Error('lookup failed'))
  else pending.get(1).resolve({ id: 1, hostId: 1 })
  await first
  assert.deepEqual(opened, outcome === 'newer-selection' ? [2] : [])
  assert.equal(errors.length, outcome === 'load-failure' ? 1 : 0)
})

test('a late RDP account lookup cannot replace the more recently selected connection', async () => {
  const detail = ref({ type: null, raw: null }), pending = new Map(), opened = []
  const dependencies = {
    hostAccounts: ref([]), detail,
    isRdp: value => value.nodeType === 'RDP_ACCOUNT',
    isAccountNode: value => ['ACCOUNT', 'RDP_ACCOUNT'].includes(value.nodeType),
    getRdpConnection: id => new Promise(resolve => pending.set(id, resolve)),
    loadAccountHostMeta: async hostId => ({ id: hostId, groupId: 1 }),
    activateRdpForAccount: account => opened.push(account.id),
    persistAccountSelection: async () => {}, accountTab: ref('terminal'),
    router: { push: async () => {} }, ElMessage: { error() {} }
  }
  const loadDetail = new Function(...Object.keys(dependencies), `let detailRequest = 0; ${production}; return loadDetail`)(...Object.values(dependencies))
  const first = loadDetail({ nodeType: 'RDP_ACCOUNT', id: 1 })
  const second = loadDetail({ nodeType: 'RDP_ACCOUNT', id: 2 })
  pending.get(2)({ id: 2, hostId: 1 }); await second
  pending.get(1)({ id: 1, hostId: 1 }); await first
  assert.equal(detail.value.raw.id, 2)
  assert.deepEqual(opened, [2])
})

test('late host metadata cannot replace the host of the foreground connection', async () => {
  const declaration = compiled.scriptSetupAst.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'loadAccountHostMeta')
  const production = descriptor.scriptSetup.content.slice(declaration.start, declaration.end)
  const accountHostMeta = ref({}), pending = new Map()
  const getHost = id => new Promise(resolve => pending.set(id, resolve))
  const load = new Function('accountHostMeta', 'getHost', `let hostMetaRequest = 0; ${production}; return loadAccountHostMeta`)(accountHostMeta, getHost)
  const first = load(1), second = load(2)
  pending.get(2)({ id: 2, host: '192.0.2.2', name: 'New' }); await second
  pending.get(1)({ id: 1, host: '192.0.2.1', name: 'Old' }); await first
  assert.equal(accountHostMeta.value.id, 2)
})

test('desktop header and status stay independent from SSH AI context', () => {
  const watcher = compiled.scriptSetupAst.find(node => node.type === 'ExpressionStatement' && node.expression.callee?.name === 'watch' && node.expression.arguments[0]?.elements?.[0]?.name === 'detail')
  const callback = watcher.expression.arguments[1]
  const source = descriptor.scriptSetup.content.slice(callback.start, callback.end)
  const workspaceStore = {}, managedServer = ref({ accountId: 1 })
  const activeTerminalTab = ref({ protocol: 'RDP', hostId: 2, hostName: 'Windows', hostAddress: '192.0.2.2', account: { port: 3390, username: 'Administrator' }, connectionState: 'connected' })
  const deps = { workspaceStore, managedServer, activeTerminalTab, detail: ref({ type: 'RDP_ACCOUNT' }), accountHostMeta: ref({}) }
  const update = new Function(...Object.keys(deps), `return (${source})`)(...Object.values(deps))
  update()
  assert.equal(managedServer.value, null)
  assert.equal(workspaceStore.terminalConnected, false)
  assert.deepEqual(workspaceStore.activeDesktop, { name: 'Windows', address: '192.0.2.2', port: 3390, username: 'Administrator', connected: true })
  activeTerminalTab.value.connectionState = 'error'; update()
  assert.equal(workspaceStore.activeDesktop.connected, false)
  activeTerminalTab.value = null; deps.detail.value = { type: null }; update()
  assert.equal(workspaceStore.activeDesktop, null)
})

test('saving desktop configuration reconnects every matching tab without touching SSH with the same ID', async () => {
  const declaration = compiled.scriptSetupAst.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'syncRdpTabs')
  const source = descriptor.scriptSetup.content.slice(declaration.start, declaration.end)
  const terminalTabs = ref([{ tabId: 'ssh', accountId: 7, account: { username: 'root' } }, ...['desktop1', 'desktop2'].map(tabId => ({ tabId, protocol: 'RDP', connectionId: 7, accountId: 'rdp:7' }))])
  const calls = [], terminalRefs = new Map(terminalTabs.value.map(tab => [tab.tabId, { disconnect: () => calls.push(`disconnect:${tab.tabId}`), reconnect: () => calls.push(`reconnect:${tab.tabId}`) }]))
  const deps = { terminalTabs, terminalRefs, matchesConnection, getHost: async () => ({ name: 'New host', host: '192.0.2.9', enabled: 1 }), nextTick: async () => {}, persistTerminalTabs() {} }
  const save = new Function(...Object.keys(deps), `${source}; return syncRdpTabs`)(...Object.values(deps))
  await save({ id: 7, hostId: 9, username: 'Administrator', ignoreCertificate: true, enabled: 1 })
  assert.deepEqual(calls, ['disconnect:desktop1', 'disconnect:desktop2', 'reconnect:desktop1', 'reconnect:desktop2'])
  assert.equal(terminalTabs.value[0].account.username, 'root')
  assert.ok(terminalTabs.value.slice(1).every(tab => tab.hostId === 9 && tab.account.ignoreCertificate))
})

test('tab focus dispatches to the selected connection protocol', () => {
  const declaration = compiled.scriptSetupAst.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'focusConnectionTab')
  const source = descriptor.scriptSetup.content.slice(declaration.start, declaration.end)
  const calls = [], terminalTabs = ref([{ tabId: 'ssh' }, { tabId: 'desktop', protocol: 'RDP' }])
  const terminalRefs = new Map(['ssh', 'desktop'].map(id => [id, { focusTerminal: () => calls.push(`ssh:${id}`), focusDisplay: () => calls.push(`rdp:${id}`) }]))
  const focus = new Function('terminalTabs', 'terminalRefs', 'isRdp', `${source}; return focusConnectionTab`)(terminalTabs, terminalRefs, tab => tab?.protocol === 'RDP')
  focus('desktop'); focus('ssh')
  assert.deepEqual(calls, ['rdp:desktop', 'ssh:ssh'])
})

test('Ctrl+K selection opens an RDP node without confusing a matching SSH account ID', async () => {
  const names=['onGlobalOpenAccount','connectHostAccount']
  const source=names.map(name=>{
    const node=compiled.scriptSetupAst.find(node=>node.type==='FunctionDeclaration' && node.id.name===name)
    return descriptor.scriptSetup.content.slice(node.start,node.end)
  }).join('\n')
  const selectedNode=ref(null),opened=[],routes=[]
  const deps={selectedNode,treeCurrentNodeKey:ref(''),treeRef:ref(null),detail:ref({raw:{}}),
    isRdp:value=>value.protocol==='RDP',loadDetail:async node=>opened.push(node),
    router:{meta:{},currentRoute:{},push:path=>routes.push(path)},route:{meta:{panel:'terminal'}}}
  const select=new Function(...Object.keys(deps),`${source};return onGlobalOpenAccount`)(...Object.values(deps))
  await select({detail:{accountId:7,protocol:'RDP',accountName:'财务桌面',username:'Administrator',hostId:3,groupId:1,hostName:'Windows'}})
  assert.equal(opened[0].nodeType,'RDP_ACCOUNT');assert.equal(opened[0].key,'r:7');assert.equal(opened[0].id,7)
  assert.equal(opened[0].label,'财务桌面');assert.equal(opened[0].extra.hostId,3)
  await select({detail:{accountId:7,protocol:'SSH',username:'root',hostId:3}})
  assert.equal(opened[1].nodeType,'ACCOUNT');assert.equal(opened[1].key,'a:7')
})
