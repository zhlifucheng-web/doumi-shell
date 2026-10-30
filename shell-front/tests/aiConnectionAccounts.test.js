import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import { ref } from 'vue'

test('SSH AI host selection excludes desktop accounts even when their numeric IDs collide', async () => {
  const { descriptor } = parse(readFileSync(new URL('../src/components/AiAssistant.vue', import.meta.url), 'utf8'))
  const compiled = compileScript(descriptor, { id: 'ai-connection-accounts' })
  const declaration = compiled.scriptSetupAst.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'loadHostAccounts')
  const production = descriptor.scriptSetup.content.slice(declaration.start, declaration.end)
  const hostAccounts = ref([])
  const dependencies = {
    hostAccounts, hostLoading: ref(false), followsCurrentHost: ref(true),
    fetchTreeChildren: async type => type === 'ROOT' ? [{ id: 1, label: 'Servers' }] : type === 'GROUP' ? [{ id: 2, nodeType: 'HOST', label: 'Machine', extra: { host: '192.0.2.1' } }] : [
      { id: 7, nodeType: 'ACCOUNT', label: 'root', extra: { enabled: 1, protocol: 'SSH' } },
      { id: 7, nodeType: 'RDP_ACCOUNT', label: 'Administrator', extra: { enabled: 1, protocol: 'RDP' } }
    ], report: error => { throw error }
  }
  await new Function(...Object.keys(dependencies), `${production}; return loadHostAccounts`)(...Object.values(dependencies))()
  assert.deepEqual(hostAccounts.value.map(item => item.username), ['root'])
})
