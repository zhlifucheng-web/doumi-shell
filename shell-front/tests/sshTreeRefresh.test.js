import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import { ref, nextTick } from 'vue'

const filename = new URL('../src/views/ssh/SshConsole.vue', import.meta.url)
const { descriptor } = parse(readFileSync(filename, 'utf8'))
const compiled = compileScript(descriptor, { id: 'tree-refresh-regression' })
const declaration = compiled.scriptSetupAst.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'refreshTree')
const productionFunction = descriptor.scriptSetup.content.slice(declaration.start, declaration.end)

function workspace(current, initialDetail) {
  const detail = ref(initialDetail), selectedNode = ref(current)
  const searchTargets = ref(['old']), refreshedNodes = []
  let finishDetail
  const response = new Promise(resolve => { finishDetail = resolve })
  const dependencies = {
    selectedNode, detail, treeCurrentNodeKey: ref(null), defaultExpandedKeys: ref([]), treeVersion: ref(0),
    treeRef: ref(null), nextTick,
    resolveSshExpandedKeys: async () => [], getGroup() {}, getHost() {},
    loadSearchTargets: async () => { searchTargets.value = ['refreshed'] },
    loadDetail: async node => {
      refreshedNodes.push(node)
      await response
      detail.value = { type: node.nodeType, raw: { id: node.id, name: 'updated account' } }
    },
    restoreSavedAccount: async () => {}
  }
  const refreshTree = new Function(...Object.keys(dependencies), `${productionFunction}; return refreshTree`)(...Object.values(dependencies))
  return { refreshTree, detail, searchTargets, refreshedNodes, finishDetail }
}

test('refreshing the same account keeps its workspace mounted while detail is loading and refreshes global search', async () => {
  const oldDetail = { type: 'ACCOUNT', raw: { id: '42', name: 'existing account' } }
  const state = workspace({ nodeType: 'ACCOUNT', id: 42, key: 'ACCOUNT:42' }, oldDetail)
  await state.refreshTree()
  await nextTick()
  assert.deepEqual(state.detail.value, oldDetail)
  assert.deepEqual(state.searchTargets.value, ['refreshed'])
  assert.equal(state.refreshedNodes.length, 1)
  state.finishDetail()
  await nextTick()
  assert.equal(state.detail.value.raw.name, 'updated account')
})

for (const target of [{ nodeType: 'ACCOUNT', id: 43 }, { nodeType: 'HOST', id: 42 }]) {
  test(`switching to ${target.nodeType}:${target.id} clears the previous account workspace`, async () => {
    const state = workspace({ nodeType: 'ACCOUNT', id: 42 }, { type: 'ACCOUNT', raw: { id: 42 } })
    await state.refreshTree({ ...target, key: `${target.nodeType}:${target.id}` })
    await nextTick()
    assert.deepEqual(state.detail.value, { type: null, raw: null })
    assert.deepEqual(state.searchTargets.value, ['refreshed'])
    assert.equal(state.refreshedNodes[0].nodeType, target.nodeType)
    assert.equal(state.refreshedNodes[0].id, target.id)
    state.finishDetail()
    await nextTick()
  })
}
