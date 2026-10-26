<template>
  <el-dialog
    v-model="visible"
    :fullscreen="isFullscreen"
    :width="isFullscreen ? '100%' : 'min(1120px, 94vw)'"
    :top="isFullscreen ? '0' : '4vh'"
    :before-close="handleBeforeClose"
    append-to-body
    destroy-on-close
    class="ssh-file-editor-dialog"
  >
    <!-- Header -->
    <template #header>
      <div class="editor-header">
        <div class="editor-title-group">
          <el-icon class="file-icon"><Document /></el-icon>
          <span class="file-title" :title="file?.name">{{ file?.name || '未知文件' }}</span>
          <span v-if="isDirty" class="dirty-badge" title="有未保存的修改">● 未保存</span>
          <span class="path-badge" :title="fullRemotePath">{{ fullRemotePath }}</span>
        </div>

        <div class="editor-toolbar">
          <el-select
            v-model="selectedEncoding"
            size="small"
            class="encoding-select"
            @change="changeEncoding"
            title="选择文件读取解析编码"
          >
            <el-option
              v-for="item in ENCODING_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>

          <el-select
            v-model="selectedLanguage"
            size="small"
            class="lang-select"
            @change="onLanguageChange"
            title="选择代码语法高亮"
          >
            <el-option
              v-for="item in LANGUAGE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>

          <el-tooltip content="弹出独立窗口查看 (脱离主会话界面)" placement="bottom">
            <el-button
              link
              class="toolbar-btn"
              :icon="TopRight"
              @click="openInStandaloneWindow"
            />
          </el-tooltip>

          <el-tooltip :content="isFullscreen ? '还原窗口' : '最大化窗口'" placement="bottom">
            <el-button
              link
              class="toolbar-btn"
              :icon="isFullscreen ? ScaleToOriginal : FullScreen"
              @click="isFullscreen = !isFullscreen"
            />
          </el-tooltip>
        </div>
      </div>
    </template>

    <!-- Content / Editor Body -->
    <div
      v-loading="loading"
      element-loading-text="正在从远程服务器加载文件内容..."
      class="editor-body"
      :class="{ 'is-fullscreen': isFullscreen }"
    >
      <div ref="editorContainerRef" class="cm-wrapper" />

      <!-- Status Bar -->
      <div class="editor-status-bar">
        <div class="status-left">
          <span class="status-item">第 {{ cursorLine }} 行，第 {{ cursorCol }} 列</span>
          <span class="status-sep">|</span>
          <span class="status-item">共 {{ lineCount }} 行，{{ docLength }} 字符</span>
          <span class="status-sep">|</span>
          <span class="status-item">{{ formatFileSize(file?.size) }}</span>
        </div>
        <div class="status-right">
          <el-dropdown trigger="click" @command="changeEncoding">
            <span class="status-encoding-btn" title="点击切换文件读取编码">
              {{ selectedEncoding }}
              <span class="caret-arrow">▾</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu class="encoding-dropdown-menu">
                <el-dropdown-item
                  v-for="enc in ENCODING_OPTIONS"
                  :key="enc.value"
                  :command="enc.value"
                  :class="{ 'is-active-enc': selectedEncoding === enc.value }"
                >
                  {{ enc.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span class="status-sep">|</span>
          <span class="status-tip">快捷键: <kbd>Ctrl+S</kbd> / <kbd>⌘S</kbd> 保存</span>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <template #footer>
      <div class="editor-footer">
        <div class="footer-left">
          <el-button
            v-if="isDirty"
            text
            size="small"
            type="info"
            @click="revertChanges"
          >
            放弃修改重置
          </el-button>
          <el-button
            v-if="file?.size > 2 * 1024 * 1024"
            size="small"
            type="info"
            plain
            title="切换为大文件只读查看模式"
            @click="switchToStream"
          >
            查看
          </el-button>
        </div>
        <div class="footer-right">
          <el-button @click="attemptClose">关闭</el-button>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="!isDirty && !saving"
            @click="saveFile"
          >
            保存修改
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, FullScreen, ScaleToOriginal, TopRight } from '@element-plus/icons-vue'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView, highlightActiveLine, highlightSpecialChars, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { bracketMatching, defaultHighlightStyle, indentOnInput, StreamLanguage, syntaxHighlighting } from '@codemirror/language'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@/composables/useTheme'
import { sftpBlob, sftpUpload } from '@/api/sshConsole'
import {
  getLanguageName,
  detectFileEncoding,
  decodeFileBuffer,
  ENCODING_OPTIONS
} from '@/utils/fileTypeHelper'

// Legacy modes for syntax highlighting
import { shell } from '@codemirror/legacy-modes/mode/shell'
import { javascript } from '@codemirror/legacy-modes/mode/javascript'
import { python } from '@codemirror/legacy-modes/mode/python'
import { yaml } from '@codemirror/legacy-modes/mode/yaml'
import { nginx } from '@codemirror/legacy-modes/mode/nginx'
import { sql } from '@codemirror/legacy-modes/mode/sql'
import { properties } from '@codemirror/legacy-modes/mode/properties'
import { dockerFile } from '@codemirror/legacy-modes/mode/dockerfile'
import { clike } from '@codemirror/legacy-modes/mode/clike'
import { go } from '@codemirror/legacy-modes/mode/go'
import { rust } from '@codemirror/legacy-modes/mode/rust'
import { xml } from '@codemirror/legacy-modes/mode/xml'
import { css } from '@codemirror/legacy-modes/mode/css'
import { diff } from '@codemirror/legacy-modes/mode/diff'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  file: { type: Object, default: () => null },
  dir: { type: String, default: '/' },
  sessionToken: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'saved', 'switch-to-stream'])

function switchToStream() {
  visible.value = false
  emit('switch-to-stream', props.file)
}

const LANGUAGE_OPTIONS = [
  { label: 'Shell 脚本', value: 'shell' },
  { label: 'JavaScript / JSON', value: 'javascript' },
  { label: 'Python', value: 'python' },
  { label: 'YAML', value: 'yaml' },
  { label: 'Nginx 配置', value: 'nginx' },
  { label: 'SQL 脚本', value: 'sql' },
  { label: 'Properties / Env', value: 'properties' },
  { label: 'Dockerfile', value: 'dockerfile' },
  { label: 'C / C++ / Java', value: 'clike' },
  { label: 'Go', value: 'go' },
  { label: 'Rust', value: 'rust' },
  { label: 'HTML / XML / SVG', value: 'xml' },
  { label: 'CSS / SCSS', value: 'css' },
  { label: 'Diff / Patch', value: 'diff' },
  { label: '纯文本 (Plain Text)', value: 'text' }
]

const MODE_MAP = {
  shell,
  javascript,
  python,
  yaml,
  nginx,
  sql,
  properties,
  dockerfile: dockerFile,
  clike,
  go,
  rust,
  xml,
  css,
  diff
}

const visible = computed({
  get: () => props.modelValue,
  set: val => emit('update:modelValue', val)
})

const editorContainerRef = ref(null)
const isFullscreen = ref(false)
const loading = ref(false)
const saving = ref(false)
const isDirty = ref(false)
const selectedLanguage = ref('text')
const selectedEncoding = ref('UTF-8')
const originalContent = ref('')
const rawFileBuffer = ref(null)

const cursorLine = ref(1)
const cursorCol = ref(1)
const lineCount = ref(1)
const docLength = ref(0)

const { effectiveDark: isDark } = useTheme()
const themeCompartment = new Compartment()
const languageCompartment = new Compartment()

let editorView = null

const fullRemotePath = computed(() => {
  if (!props.file?.name) return ''
  const base = props.dir || '/'
  return base.endsWith('/') ? `${base}${props.file.name}` : `${base}/${props.file.name}`
})

const router = useRouter()

function openInStandaloneWindow() {
  const filePath = fullRemotePath.value
  const fileName = props.file?.name || ''
  const fileSize = props.file?.size || 0
  const token = props.sessionToken

  if (!token || !filePath) {
    ElMessage.warning('缺少有效的文件路径或会话 Token')
    return
  }

  const routeData = router.resolve({
    path: '/file-viewer',
    query: {
      token,
      path: filePath,
      name: fileName,
      size: fileSize,
      enc: selectedEncoding.value
    }
  })

  const width = Math.min(1280, Math.floor(window.screen.availWidth * 0.88))
  const height = Math.min(860, Math.floor(window.screen.availHeight * 0.88))
  const left = Math.max(0, Math.floor((window.screen.availWidth - width) / 2))
  const top = Math.max(0, Math.floor((window.screen.availHeight - height) / 2))

  window.open(
    routeData.href,
    `ssh_viewer_${Date.now()}`,
    `width=${width},height=${height},left=${left},top=${top},menubar=no,toolbar=no,location=no,status=no,resizable=yes,scrollbars=yes`
  )

  visible.value = false
}

function formatFileSize(size) {
  const n = Number(size) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}

function getModeExtension(lang) {
  const mode = MODE_MAP[lang]
  if (mode) {
    return StreamLanguage.define(mode)
  }
  return []
}

function getThemeExtension() {
  return isDark.value ? oneDark : syntaxHighlighting(defaultHighlightStyle)
}

function initEditor(content = '') {
  if (editorView) {
    editorView.destroy()
    editorView = null
  }
  if (!editorContainerRef.value) return

  const saveKeyBinding = {
    key: 'Mod-s',
    run: () => {
      saveFile()
      return true
    }
  }

  const startState = EditorState.create({
    doc: content,
    extensions: [
      lineNumbers(),
      history(),
      highlightActiveLine(),
      highlightSpecialChars(),
      bracketMatching(),
      indentOnInput(),
      languageCompartment.of(getModeExtension(selectedLanguage.value)),
      themeCompartment.of(getThemeExtension()),
      keymap.of([saveKeyBinding, indentWithTab, ...defaultKeymap, ...historyKeymap]),
      EditorView.contentAttributes.of({
        'aria-label': 'SFTP 文件在线编辑',
        spellcheck: 'false',
        autocapitalize: 'off',
        autocomplete: 'off'
      }),
      EditorView.updateListener.of(update => {
        if (update.docChanged) {
          const currentText = update.state.doc.toString()
          isDirty.value = currentText !== originalContent.value
          docLength.value = currentText.length
          lineCount.value = update.state.doc.lines
        }
        if (update.selectionSet || update.docChanged) {
          const pos = update.state.selection.main.head
          const line = update.state.doc.lineAt(pos)
          cursorLine.value = line.number
          cursorCol.value = pos - line.from + 1
        }
      })
    ]
  })

  editorView = new EditorView({
    state: startState,
    parent: editorContainerRef.value
  })

  docLength.value = content.length
  lineCount.value = editorView.state.doc.lines
  cursorLine.value = 1
  cursorCol.value = 1
}

async function loadFileContent() {
  if (!props.sessionToken || !props.file?.name) return
  loading.value = true
  isDirty.value = false

  // Detect language mode by filename
  selectedLanguage.value = getLanguageName(props.file.name)

  try {
    const blob = await sftpBlob(props.sessionToken, fullRemotePath.value)
    const arrayBuffer = await blob.arrayBuffer()
    rawFileBuffer.value = arrayBuffer

    // Automatically detect encoding from buffer bytes & BOM
    const detectedEnc = detectFileEncoding(arrayBuffer)
    selectedEncoding.value = detectedEnc

    const text = decodeFileBuffer(arrayBuffer, detectedEnc)
    originalContent.value = text
    await nextTick()
    initEditor(text)
  } catch (err) {
    ElMessage.error(err.message || '加载文件内容失败')
  } finally {
    loading.value = false
  }
}

async function changeEncoding(newEncoding) {
  if (!newEncoding || (newEncoding === selectedEncoding.value && editorView)) return

  if (isDirty.value) {
    try {
      await ElMessageBox.confirm(
        `切换读取编码至「${newEncoding}」将根据文件原始字节重新解析并覆盖当前未保存的修改，确定继续吗？`,
        '切换文件解析编码',
        {
          confirmButtonText: '确定切换',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  }

  selectedEncoding.value = newEncoding

  if (rawFileBuffer.value) {
    const text = decodeFileBuffer(rawFileBuffer.value, newEncoding)
    originalContent.value = text
    isDirty.value = false
    if (editorView) {
      editorView.dispatch({
        changes: { from: 0, to: editorView.state.doc.length, insert: text }
      })
    }
    ElMessage.success(`已切换为 ${newEncoding} 重新解析文件`)
  }
}

async function saveFile() {
  if (!editorView || saving.value) return
  saving.value = true

  const content = editorView.state.doc.toString()
  const fileName = props.file?.name || 'file.txt'
  const parentDir = props.dir || '/'

  try {
    const uploadFileObj = new File([content], fileName, { type: 'text/plain;charset=utf-8' })
    await sftpUpload(props.sessionToken, parentDir, uploadFileObj)

    // Update in-memory buffer to latest saved content
    rawFileBuffer.value = new TextEncoder().encode(content).buffer

    originalContent.value = content
    isDirty.value = false
    ElMessage.success('保存成功')
    emit('saved', { path: parentDir, name: fileName })
  } catch (err) {
    ElMessage.error(err.message || '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

function revertChanges() {
  if (!editorView) return
  const text = rawFileBuffer.value
    ? decodeFileBuffer(rawFileBuffer.value, selectedEncoding.value)
    : originalContent.value
  editorView.dispatch({
    changes: { from: 0, to: editorView.state.doc.length, insert: text }
  })
  originalContent.value = text
  isDirty.value = false
  ElMessage.info('已恢复为初始内容')
}

function onLanguageChange(lang) {
  if (editorView) {
    editorView.dispatch({
      effects: languageCompartment.reconfigure(getModeExtension(lang))
    })
  }
}

async function attemptClose() {
  if (isDirty.value) {
    try {
      await ElMessageBox.confirm(
        '当前文件存在未保存的修改，直接关闭将丢失已修改的内容。确定要关闭吗？',
        '未保存修改确认',
        {
          confirmButtonText: '放弃修改并关闭',
          cancelButtonText: '继续编辑',
          type: 'warning'
        }
      )
      visible.value = false
    } catch {
      // Stay in dialog
    }
  } else {
    visible.value = false
  }
}

async function handleBeforeClose(done) {
  if (isDirty.value) {
    try {
      await ElMessageBox.confirm(
        '当前文件存在未保存的修改，直接关闭将丢失已修改的内容。确定要关闭吗？',
        '未保存修改确认',
        {
          confirmButtonText: '放弃修改并关闭',
          cancelButtonText: '继续编辑',
          type: 'warning'
        }
      )
      done()
    } catch {
      // Keep open
    }
  } else {
    done()
  }
}

// Global Ctrl+S / Cmd+S prevention and save trigger
function onGlobalKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    if (visible.value) {
      e.preventDefault()
      saveFile()
    }
  }
}

watch(
  () => props.modelValue,
  val => {
    if (val && props.file) {
      loadFileContent()
    } else {
      if (editorView) {
        editorView.destroy()
        editorView = null
      }
      isDirty.value = false
      originalContent.value = ''
      rawFileBuffer.value = null
      selectedEncoding.value = 'UTF-8'
    }
  }
)

watch(isDark, () => {
  editorView?.dispatch({ effects: themeCompartment.reconfigure(getThemeExtension()) })
})

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  if (editorView) {
    editorView.destroy()
    editorView = null
  }
})
</script>

<style scoped>
.ssh-file-editor-dialog :deep(.el-dialog__header) {
  margin: 0;
  padding: 12px 18px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.ssh-file-editor-dialog :deep(.el-dialog__body) {
  padding: 0;
}

.ssh-file-editor-dialog :deep(.el-dialog__footer) {
  padding: 10px 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 28px;
}

.editor-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
}

.file-icon {
  font-size: 18px;
  color: var(--el-color-primary);
  flex-shrink: 0;
}

.file-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}

.dirty-badge {
  font-size: 12px;
  color: var(--el-color-warning);
  font-weight: 600;
  white-space: nowrap;
  animation: pulse-badge 1.8s infinite;
}

@keyframes pulse-badge {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

.path-badge {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  padding: 2px 8px;
  border-radius: 4px;
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.encoding-select {
  width: 140px;
}

.lang-select {
  width: 148px;
}

.toolbar-btn {
  font-size: 16px;
  color: var(--el-text-color-regular);
}

.toolbar-btn:hover {
  color: var(--el-color-primary);
}

.editor-body {
  display: flex;
  flex-direction: column;
  height: 65vh;
  min-height: 420px;
  position: relative;
  background: var(--el-bg-color);
  transition: height 0.2s ease;
}

.editor-body.is-fullscreen {
  height: calc(100vh - 120px);
}

.cm-wrapper {
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow: hidden;
}

.cm-wrapper :deep(.cm-editor) {
  height: 100%;
  font-size: 13.5px;
  line-height: 1.6;
}

.cm-wrapper :deep(.cm-scroller) {
  overflow: auto;
  font-family: 'JetBrains Mono', Consolas, Menlo, Monaco, monospace;
}

.cm-wrapper :deep(.cm-focused) {
  outline: none;
}

.editor-status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 28px;
  padding: 0 14px;
  background: var(--el-fill-color-light);
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  user-select: none;
}

.status-left, .status-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-sep {
  opacity: 0.35;
}

.status-encoding-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  cursor: pointer;
  padding: 1px 6px;
  border-radius: 4px;
  color: var(--el-text-color-regular);
  font-family: var(--font-mono, monospace);
  font-size: 11.5px;
  transition: all 0.15s ease;
}

.status-encoding-btn:hover {
  background: var(--el-fill-color);
  color: var(--el-color-primary);
}

.caret-arrow {
  font-size: 10px;
  opacity: 0.7;
}

:global(.encoding-dropdown-menu .is-active-enc) {
  color: var(--el-color-primary) !important;
  font-weight: 600;
}

.status-tip kbd {
  display: inline-block;
  padding: 1px 4px;
  font-size: 11px;
  font-family: inherit;
  background: var(--el-fill-color);
  border: 1px solid var(--el-border-color);
  border-radius: 3px;
  box-shadow: 0 1px 1px rgba(0, 0, 0, 0.08);
}

.editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.footer-left {
  display: flex;
  align-items: center;
}

.footer-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
