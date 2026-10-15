<template><div ref="container" class="script-editor" /><EditorScrollbar :target="scrollTarget" /></template>

<script setup>
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import EditorScrollbar from '@/components/EditorScrollbar.vue'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightSpecialChars } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { StreamLanguage, bracketMatching, indentOnInput, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { shell } from '@codemirror/legacy-modes/mode/shell'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@/composables/useTheme'

const props = defineProps({ modelValue: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])
const container = ref(null)
const scrollTarget = shallowRef(null)
const theme = new Compartment()
const { effectiveDark: isDark } = useTheme()
let editor
const themeExtension = () => isDark.value ? oneDark : syntaxHighlighting(defaultHighlightStyle)
onMounted(() => {
  editor = new EditorView({
    parent: container.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [lineNumbers(), history(), highlightActiveLine(), highlightSpecialChars(),
        bracketMatching(), indentOnInput(), StreamLanguage.define(shell),
        keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap]), theme.of(themeExtension()),
        EditorView.contentAttributes.of({ 'aria-label': 'Shell 脚本内容', spellcheck: 'false' }),
        EditorView.updateListener.of(update => {
          if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
        })]
    })
  })
  scrollTarget.value=editor.scrollDOM
})
watch(() => props.modelValue, value => {
  if (editor && value !== editor.state.doc.toString()) {
    editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value || '' } })
  }
})
watch(isDark, () => editor?.dispatch({ effects: theme.reconfigure(themeExtension()) }))
onBeforeUnmount(() => editor?.destroy())
</script>

<style scoped>
.script-editor { width: 100%; border: 1px solid var(--el-border-color); border-radius: 4px; overflow: hidden; }
.script-editor :deep(.cm-editor) { height: 320px; font-size: 13px; line-height: 1.6; }
.script-editor :deep(.cm-scroller) { overflow: auto; font-family: Consolas, monospace; }
.script-editor :deep(.cm-focused) { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
</style>
