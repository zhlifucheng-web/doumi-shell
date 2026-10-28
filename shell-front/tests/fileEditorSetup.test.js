import test from 'node:test'
import assert from 'node:assert/strict'
import * as vue from 'vue'
import * as cmState from '@codemirror/state'
import * as cmView from '@codemirror/view'
import * as cmLanguage from '@codemirror/language'
import * as fileTypes from '../src/utils/fileTypeHelper.js'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'

// Keep CodeMirror's actual document/transaction model; only its DOM view is replaced.
function mountEditor(bytes, { encode, upload, confirm } = {}) {
  const errors = [], uploads = [], encodings = []
  let editor
  class View {
    static contentAttributes = cmView.EditorView.contentAttributes
    static updateListener = cmView.EditorView.updateListener
    constructor({ state }) { this.state = state; editor = this }
    dispatch(spec) {
      const transaction = this.state.update(spec)
      this.state = transaction.state
      for (const listener of this.state.facet(View.updateListener)) listener({ state: this.state, docChanged: transaction.docChanged, selectionSet: true })
    }
    destroy() {}
  }
  const mounted = mountSfcSetup(new URL('../src/components/ssh/SshFileEditorDialog.vue', import.meta.url), {
    props: { modelValue: true, file: { name: 'test.txt', size: bytes.length }, dir: '/tmp', sessionToken: 'test' },
    modules: {
      'vue-router': { useRouter: () => ({ resolve: () => ({ href: '' }) }) },
      'element-plus': { ElMessage: { success() {}, error: m => errors.push(m), info() {} }, ElMessageBox: { confirm: confirm || (async () => {}) } },
      '@codemirror/state': cmState, '@codemirror/view': { ...cmView, EditorView: View }, '@codemirror/language': cmLanguage,
      '@codemirror/commands': { defaultKeymap: [], history: () => [], historyKeymap: [], indentWithTab: {} },
      '@codemirror/theme-one-dark': { oneDark: [] }, '@/composables/useTheme': { useTheme: () => ({ effectiveDark: vue.ref(false) }) },
      '@/utils/fileTypeHelper': fileTypes,
      '@/api/sshConsole': {
        sftpBlob: async () => new Blob([bytes]),
        sftpEncodeText: async (token, content, encoding, bom) => { encodings.push({ content, encoding, bom }); return encode ? encode(content, encoding, bom) : new Blob([new TextEncoder().encode(content)]) },
        sftpUpload: async (token, dir, file) => { uploads.push(file); if (upload) await upload(file) }
      }
    },
    globals: { window: { addEventListener() {}, removeEventListener() {} } },
    onSetup: state => { state.editorContainerRef.value = {} }
  })
  return { ...mounted, get editor() { return editor }, errors, uploads, encodings }
}

test('editor detects GBK, defaults saving to it and uploads the converted bytes', async () => {
  const editor = mountEditor(Uint8Array.of(0xd6, 0xd0), { encode: () => new Blob([Uint8Array.of(0xd6, 0xd0, 0xce, 0xc4)]) })
  try {
    await editor.state.loadFileContent()
    assert.equal(editor.state.selectedEncoding.value, 'GBK')
    assert.equal(editor.state.saveEncoding.value, 'GBK')
    assert.equal(editor.editor.state.doc.toString(), '中')
    editor.editor.dispatch({ changes: { from: 1, insert: '文' } })
    await editor.state.saveFile()
    assert.deepEqual(editor.encodings, [{ content: '中文', encoding: 'GBK', bom: false }])
    assert.deepEqual([...new Uint8Array(await editor.uploads[0].arrayBuffer())], [0xd6, 0xd0, 0xce, 0xc4])
    assert.equal(editor.state.isDirty.value, false)
    await editor.state.changeEncoding('UTF-8')
    await editor.state.changeEncoding('GBK')
    assert.equal(editor.editor.state.doc.toString(), '中文', 'reopening uses the bytes actually saved')
  } finally { editor.unmount() }
})

test('changing save encoding keeps edited text and makes an encoding-only change saveable', async () => {
  const editor = mountEditor(new TextEncoder().encode('hello'))
  try {
    await editor.state.loadFileContent()
    editor.state.saveEncoding.value = 'GBK'; await vue.nextTick()
    assert.equal(editor.state.isDirty.value, true)
    assert.equal(editor.editor.state.doc.toString(), 'hello')
    await editor.state.saveFile()
    assert.equal(editor.encodings[0].encoding, 'GBK')
    assert.equal(editor.state.isDirty.value, false)
    editor.editor.dispatch({ changes: { from: 5, insert: '!' } })
    editor.state.saveEncoding.value = 'UTF-8'; await vue.nextTick()
    assert.equal(editor.editor.state.doc.toString(), 'hello!')
  } finally { editor.unmount() }
})

test('failed encoding conversion never uploads or clears unsaved changes', async () => {
  const editor = mountEditor(new TextEncoder().encode('hello'), { encode: () => { throw new Error('GBK 无法表示部分字符') } })
  try {
    await editor.state.loadFileContent()
    editor.editor.dispatch({ changes: { from: 5, insert: '😀' } })
    editor.state.saveEncoding.value = 'GBK'
    await editor.state.saveFile()
    assert.equal(editor.uploads.length, 0)
    assert.equal(editor.state.isDirty.value, true)
    assert.equal(editor.editor.state.doc.toString(), 'hello😀')
    assert.match(editor.errors[0], /无法表示/)
  } finally { editor.unmount() }
})

test('BOM is retained when saving Unicode and canceled reopen keeps the previous encoding and edits', async () => {
  const editor = mountEditor(Uint8Array.of(0xff, 0xfe, 0x61, 0), { confirm: async () => { throw new Error('cancel') } })
  try {
    await editor.state.loadFileContent()
    assert.equal(editor.state.selectedEncoding.value, 'UTF-16LE')
    editor.editor.dispatch({ changes: { from: 1, insert: 'b' } })
    await editor.state.changeEncoding('GBK')
    assert.equal(editor.state.selectedEncoding.value, 'UTF-16LE')
    assert.equal(editor.editor.state.doc.toString(), 'ab')
    await editor.state.saveFile()
    assert.equal(editor.encodings[0].bom, true)
    assert.equal(editor.encodings[0].encoding, 'UTF-16LE')
  } finally { editor.unmount() }
})

test('edits typed while save is pending remain dirty after the saved snapshot completes', async () => {
  let resolve
  const editor = mountEditor(new TextEncoder().encode('hello'), { encode: () => new Promise(done => { resolve = done }) })
  try {
    await editor.state.loadFileContent()
    editor.editor.dispatch({ changes: { from: 5, insert: '!' } })
    const saving = editor.state.saveFile()
    editor.editor.dispatch({ changes: { from: 6, insert: 'later' } })
    resolve(new Blob(['hello!'])); await saving
    assert.equal(editor.editor.state.doc.toString(), 'hello!later')
    assert.equal(editor.state.isDirty.value, true)
    assert.equal(await editor.uploads[0].text(), 'hello!')
  } finally { editor.unmount() }
})

test('closing during encoding cancels the pending upload without errors or writes', async () => {
  let resolve
  const editor = mountEditor(new TextEncoder().encode('hello'), { encode: () => new Promise(done => { resolve = done }) })
  await editor.state.loadFileContent()
  const saving = editor.state.saveFile()
  editor.unmount(); resolve(new Blob(['hello'])); await saving
  assert.equal(editor.uploads.length, 0)
  assert.deepEqual(editor.errors, [])
})

test('an explicitly chosen save encoding survives reopening with a corrected read encoding', async () => {
  const editor = mountEditor(Uint8Array.of(0xd6, 0xd0))
  try {
    await editor.state.loadFileContent()
    editor.state.chooseSaveEncoding('UTF-16LE')
    await editor.state.changeEncoding('GB18030')
    assert.equal(editor.state.selectedEncoding.value, 'GB18030')
    assert.equal(editor.state.saveEncoding.value, 'UTF-16LE')
    assert.equal(editor.editor.state.doc.toString(), '中')
    await editor.state.saveFile()
    assert.equal(editor.encodings[0].encoding, 'UTF-16LE')
  } finally { editor.unmount() }
})
