<template>
  <el-dialog
    v-model="visible"
    :title="null"
    :width="isFullscreen ? '100vw' : 'min(1240px, 96vw)'"
    :top="isFullscreen ? '0' : '4vh'"
    :fullscreen="isFullscreen"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
    class="ssh-large-file-dialog"
    :class="{ 'is-fullscreen': isFullscreen }"
    @open="onDialogOpen"
    @opened="onDialogOpened"
    @closed="onDialogClosed"
  >
    <!-- Custom Header Bar -->
    <template #header>
      <div class="viewer-header-bar">
        <div class="header-left">
          <el-icon class="file-icon"><Document /></el-icon>
          <span class="file-name" :title="file?.name">{{ file?.name }}</span>
          <span class="file-size-tag">{{ formatFileSize(totalSize || file?.size) }}</span>
          <span
            v-if="autoFollow"
            class="live-tail-badge"
            title="已开启实时追踪，检测到新内容将自动追加并滚动到底部"
          >
            <span class="live-dot" /> 实时追加追踪中
          </span>
        </div>

        <div class="header-right">
          <!-- Encoding Selector -->
          <el-select
            v-model="selectedEncoding"
            size="small"
            class="encoding-select"
            title="选择文件读取编码"
            @change="changeEncoding"
          >
            <el-option
              v-for="enc in ENCODING_OPTIONS"
              :key="enc.value"
              :label="enc.label"
              :value="enc.value"
            />
          </el-select>

          <!-- Search In Current Loaded Content -->
          <el-input
            v-model="searchKeyword"
            size="small"
            placeholder="搜索当前已加载内容..."
            clearable
            class="search-input"
            :prefix-icon="Search"
          />

          <!-- Pop out to Standalone Window -->
          <el-tooltip content="弹出独立窗口 (脱离主界面独立查看)" placement="bottom">
            <el-button
              link
              class="toolbar-btn popout-btn"
              :icon="TopRight"
              @click="openInStandaloneWindow"
            >
              <span class="btn-text">独立窗口</span>
            </el-button>
          </el-tooltip>

          <!-- Fullscreen Toggle -->
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

    <!-- Wrapper for height constraint and internal scroll -->
    <div class="large-viewer-wrapper" :class="{ 'is-fullscreen': isFullscreen }">
      <!-- Navigation & Quick Action Sub-bar -->
      <div class="viewer-sub-bar">
        <div class="sub-bar-left">
          <el-button-group size="small">
            <el-button
              :disabled="!hasMoreBefore || loading"
              :icon="Top"
              @click="jumpToHead"
            >
              文件开头 (Head)
            </el-button>
            <el-button
              :disabled="!hasMoreBefore || loading"
              :icon="ArrowUp"
              :loading="loadingEarlier"
              @click="loadEarlierChunk"
            >
              向上加载前 {{ formatFileSize(chunkSize) }}
            </el-button>
            <el-button
              :disabled="!hasMoreAfter || loading"
              :icon="ArrowDown"
              :loading="loadingLater"
              @click="loadLaterChunk"
            >
              向下加载后 {{ formatFileSize(chunkSize) }}
            </el-button>
            <el-button
              :disabled="!hasMoreAfter || loading"
              :icon="Bottom"
              @click="jumpToTail"
            >
              文件末尾 (Tail)
            </el-button>
          </el-button-group>

          <span class="range-indicator" :title="`当前加载范围：字节 ${rangeStart} 至 ${rangeEnd}，总计 ${formatFileSize(totalSize)}`">
            偏移: {{ formatFileSize(rangeStart) }} ~ {{ formatFileSize(rangeEnd) }} / {{ formatFileSize(totalSize) }} ({{ progressPercent }}%)
          </span>
        </div>

        <div class="sub-bar-right">
          <!-- Tail -f Auto Follow Toggle -->
          <el-switch
            v-model="autoFollow"
            size="small"
            inline-prompt
            active-text="实时追踪"
            inactive-text="追踪停止"
            @change="onAutoFollowChange"
          />

          <!-- Jump to Position Popover/Input -->
          <div class="jump-control">
            <span class="jump-label">跳转:</span>
            <el-input-number
              v-model="jumpPercentInput"
              size="small"
              :min="0"
              :max="100"
              :controls="false"
              class="jump-input"
              @keyup.enter="handleJumpConfirm"
            />
            <span class="jump-unit">%</span>
            <el-button size="small" @click="handleJumpConfirm">前往</el-button>
          </div>
        </div>
      </div>

      <!-- Viewer Body (Scrolls internally) -->
      <div
        ref="viewerBodyRef"
        v-loading="loading && chunks.length === 0"
        element-loading-text="正在按需加载文件片段..."
        class="viewer-body"
        :class="{ 'is-fullscreen': isFullscreen }"
        @scroll.passive="onViewerScroll"
      >
        <!-- Load Earlier Banner if more exists -->
        <div v-if="hasMoreBefore" class="chunk-edge-banner top-banner">
          <el-button
            text
            size="small"
            type="primary"
            :loading="loadingEarlier"
            :icon="ArrowUp"
            @click="loadEarlierChunk"
          >
            已到达当前视图顶部 · 点击向上加载更早内容 (前 {{ formatFileSize(chunkSize) }})
          </el-button>
        </div>
        <div v-else class="chunk-edge-banner is-boundary">
          <span>— 已到达文件最开头 (Offset: 0) —</span>
        </div>

        <!-- Text Content Block -->
        <div class="text-stream-content">
          <pre ref="preRef" class="code-pre" v-html="highlightedContent" />
        </div>

        <!-- Load Later Banner if more exists -->
        <div v-if="hasMoreAfter" class="chunk-edge-banner bottom-banner">
          <el-button
            text
            size="small"
            type="primary"
            :loading="loadingLater"
            :icon="ArrowDown"
            @click="loadLaterChunk"
          >
            已到达当前视图末尾 · 点击向下加载后续内容 (后 {{ formatFileSize(chunkSize) }})
          </el-button>
        </div>
        <div v-else class="chunk-edge-banner is-boundary">
          <span>— 已到达文件最末尾 (Offset: {{ formatFileSize(totalSize) }}) —</span>
        </div>
      </div>
    </div>

    <!-- Status Bar & Footer -->
    <template #footer>
      <div class="viewer-footer">
        <div class="footer-left">
          <span class="status-stat">共加载 {{ chunks.length }} 个片段 (约 {{ formatFileSize(loadedTotalBytes) }})</span>
          <span class="status-sep">|</span>
          <span class="status-stat">当前视图共 {{ totalLines }} 行</span>
          <span class="status-sep">|</span>
          <span class="status-stat">编码: {{ selectedEncoding }}</span>
          <template v-if="searchKeyword">
            <span class="status-sep">|</span>
            <span class="search-stat">匹配高亮关键词: 「{{ searchKeyword }}」 (共 {{ matchCount }} 处)</span>
          </template>
        </div>

        <div class="footer-right">
          <el-button size="small" @click="copyVisibleText">复制当前文本</el-button>
          <el-button
            v-if="canSwitchToEditor"
            size="small"
            type="warning"
            plain
            title="文件较小，允许切换为全量在线代码编辑器进行修改"
            @click="promptSwitchToEditor"
          >
            转入全量编辑
          </el-button>
          <el-button size="small" type="primary" @click="visible = false">关闭</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ArrowUp,
  Bottom,
  Document,
  FullScreen,
  ScaleToOriginal,
  Search,
  Top,
  TopRight
} from '@element-plus/icons-vue'
import { sftpReadChunk } from '@/api/sshConsole'
import {
  base64ToUint8Array,
  decodeFileBuffer,
  detectFileEncoding,
  ENCODING_OPTIONS
} from '@/utils/fileTypeHelper'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  file: { type: Object, default: null },
  dir: { type: String, default: '/' },
  sessionToken: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'switch-to-editor'])

const visible = computed({
  get: () => props.modelValue,
  set: val => emit('update:modelValue', val)
})

const isFullscreen = ref(false)
const loading = ref(false)
const loadingEarlier = ref(false)
const loadingLater = ref(false)
const autoFollow = ref(false)
const searchKeyword = ref('')
const jumpPercentInput = ref(0)
const selectedEncoding = ref('UTF-8')

const totalSize = ref(0)
const chunks = ref([])
const hasMoreBefore = ref(false)
const hasMoreAfter = ref(false)

const chunkSize = ref(256 * 1024) // 256 KB per chunk
const MAX_RETAINED_CHUNKS = 12 // Keeps memory strictly under ~3MB

const viewerBodyRef = ref(null)
const preRef = ref(null)
let autoFollowTimer = null

const fullRemotePath = computed(() => {
  if (!props.file?.name) return ''
  const base = props.dir || '/'
  return base.endsWith('/') ? `${base}${props.file.name}` : `${base}/${props.file.name}`
})

const rangeStart = computed(() => {
  if (!chunks.value.length) return 0
  return chunks.value[0].offset
})

const rangeEnd = computed(() => {
  if (!chunks.value.length) return 0
  const last = chunks.value[chunks.value.length - 1]
  return last.offset + last.length
})

const loadedTotalBytes = computed(() => {
  return chunks.value.reduce((acc, c) => acc + c.length, 0)
})

const progressPercent = computed(() => {
  if (!totalSize.value) return 0
  const pct = Math.round((rangeEnd.value / totalSize.value) * 100)
  return Math.min(100, Math.max(0, pct))
})

const combinedText = computed(() => {
  return chunks.value.map(c => c.text).join('')
})

const totalLines = computed(() => {
  const t = combinedText.value
  if (!t) return 0
  return t.split('\n').length
})

const matchCount = computed(() => {
  const kw = searchKeyword.value.trim()
  if (!kw) return 0
  const text = combinedText.value
  const re = new RegExp(escapeRegExp(kw), 'gi')
  const matches = text.match(re)
  return matches ? matches.length : 0
})

const highlightedContent = computed(() => {
  const raw = combinedText.value || ''
  const kw = searchKeyword.value.trim()
  const escaped = escapeHtml(raw)
  if (!kw) return escaped
  const re = new RegExp(escapeRegExp(escapeHtml(kw)), 'gi')
  return escaped.replace(re, m => `<mark class="search-match">${m}</mark>`)
})

const canSwitchToEditor = computed(() => {
  const s = totalSize.value || props.file?.size || 0
  // Permit switching to editor only if file is within 50MB
  return s > 0 && s <= 50 * 1024 * 1024
})

function formatFileSize(size) {
  const n = Number(size) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(2)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function escapeRegExp(string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const router = useRouter()

function openInStandaloneWindow() {
  const filePath = fullRemotePath.value
  const fileName = props.file?.name || ''
  const fileSize = totalSize.value || props.file?.size || 0
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

async function onDialogOpen() {
  chunks.value = []
  searchKeyword.value = ''
  autoFollow.value = false
  totalSize.value = props.file?.size || 0
  jumpPercentInput.value = 100
  await jumpToTail()
}

function onDialogOpened() {
  // Ensure we are fully scrolled to bottom when the dialog transition finishes
  scrollToBottom()
  setTimeout(scrollToBottom, 60)
}

function onDialogClosed() {
  stopAutoFollow()
  chunks.value = []
}

async function jumpToTail() {
  loading.value = true
  try {
    const res = await sftpReadChunk(props.sessionToken, fullRemotePath.value, {
      offset: 0,
      length: chunkSize.value,
      fromEnd: true
    })
    processInitialChunk(res, true)
    await nextTick()
    scrollToBottom()
    setTimeout(scrollToBottom, 50)
  } catch (err) {
    ElMessage.error(err.message || '读取文件末尾失败')
  } finally {
    loading.value = false
  }
}

async function jumpToHead() {
  loading.value = true
  try {
    const res = await sftpReadChunk(props.sessionToken, fullRemotePath.value, {
      offset: 0,
      length: chunkSize.value,
      fromEnd: false
    })
    processInitialChunk(res, false)
    await nextTick()
    scrollToTop()
  } catch (err) {
    ElMessage.error(err.message || '读取文件开头失败')
  } finally {
    loading.value = false
  }
}

function processInitialChunk(res, fromEnd) {
  totalSize.value = res.totalSize || props.file?.size || 0
  hasMoreBefore.value = res.hasMoreBefore
  hasMoreAfter.value = res.hasMoreAfter

  const bytes = base64ToUint8Array(res.contentBase64)
  if (chunks.value.length === 0) {
    // Detect encoding on initial chunk
    selectedEncoding.value = detectFileEncoding(bytes)
  }
  const text = decodeFileBuffer(bytes, selectedEncoding.value)

  chunks.value = [
    {
      offset: res.offset,
      length: res.length,
      bytes,
      text
    }
  ]
}

async function loadEarlierChunk(event) {
  if (!hasMoreBefore.value || loadingEarlier.value || !chunks.value.length) return
  if (event?.target?.blur) event.target.blur()
  if (document.activeElement?.blur) document.activeElement.blur()

  loadingEarlier.value = true

  const el = viewerBodyRef.value
  const prevScrollHeight = el ? el.scrollHeight : 0
  const prevScrollTop = el ? el.scrollTop : 0

  const first = chunks.value[0]
  const targetOffset = Math.max(0, first.offset - chunkSize.value)
  const targetLength = first.offset - targetOffset

  try {
    const res = await sftpReadChunk(props.sessionToken, fullRemotePath.value, {
      offset: targetOffset,
      length: targetLength,
      fromEnd: false
    })
    const bytes = base64ToUint8Array(res.contentBase64)
    const text = decodeFileBuffer(bytes, selectedEncoding.value)

    chunks.value.unshift({
      offset: res.offset,
      length: res.length,
      bytes,
      text
    })

    hasMoreBefore.value = res.hasMoreBefore
    totalSize.value = res.totalSize || totalSize.value

    // If excessive chunks, prune from bottom
    if (chunks.value.length > MAX_RETAINED_CHUNKS) {
      chunks.value.pop()
      hasMoreAfter.value = true
    }

    await nextTick()
    if (viewerBodyRef.value) {
      const newScrollHeight = viewerBodyRef.value.scrollHeight
      const delta = newScrollHeight - prevScrollHeight
      viewerBodyRef.value.scrollTop = prevScrollTop + delta
    }
    requestAnimationFrame(() => {
      if (viewerBodyRef.value) {
        const newScrollHeight = viewerBodyRef.value.scrollHeight
        const delta = newScrollHeight - prevScrollHeight
        viewerBodyRef.value.scrollTop = prevScrollTop + delta
      }
    })
  } catch (err) {
    ElMessage.error(err.message || '向上加载失败')
  } finally {
    loadingEarlier.value = false
  }
}

async function loadLaterChunk() {
  if (!hasMoreAfter.value || loadingLater.value || !chunks.value.length) return
  loadingLater.value = true

  const last = chunks.value[chunks.value.length - 1]
  const targetOffset = last.offset + last.length

  try {
    const res = await sftpReadChunk(props.sessionToken, fullRemotePath.value, {
      offset: targetOffset,
      length: chunkSize.value,
      fromEnd: false
    })
    const bytes = base64ToUint8Array(res.contentBase64)
    const text = decodeFileBuffer(bytes, selectedEncoding.value)

    chunks.value.push({
      offset: res.offset,
      length: res.length,
      bytes,
      text
    })

    hasMoreAfter.value = res.hasMoreAfter
    totalSize.value = res.totalSize || totalSize.value

    // If excessive chunks, prune from top
    if (chunks.value.length > MAX_RETAINED_CHUNKS) {
      chunks.value.shift()
      hasMoreBefore.value = true
    }
  } catch (err) {
    ElMessage.error(err.message || '向下加载失败')
  } finally {
    loadingLater.value = false
  }
}

async function handleJumpConfirm() {
  const pct = Math.min(100, Math.max(0, Number(jumpPercentInput.value) || 0))
  if (pct === 0) {
    await jumpToHead()
    return
  }
  if (pct === 100) {
    await jumpToTail()
    return
  }

  const targetOffset = Math.floor((pct / 100) * totalSize.value)
  loading.value = true
  try {
    const res = await sftpReadChunk(props.sessionToken, fullRemotePath.value, {
      offset: targetOffset,
      length: chunkSize.value,
      fromEnd: false
    })
    processInitialChunk(res, false)
    await nextTick()
    scrollToTop()
  } catch (err) {
    ElMessage.error(err.message || '跳转失败')
  } finally {
    loading.value = false
  }
}

function changeEncoding(newEnc) {
  if (!newEnc) return
  selectedEncoding.value = newEnc
  // Re-decode in-memory cached chunks with new encoding
  chunks.value = chunks.value.map(c => ({
    ...c,
    text: decodeFileBuffer(c.bytes, newEnc)
  }))
  ElMessage.success(`已切换为 ${newEnc} 重新解析当前文本`)
}

function onAutoFollowChange(val) {
  if (val) {
    startAutoFollow()
    ElMessage.success('已开启实时追加追踪')
  } else {
    stopAutoFollow()
    ElMessage.info('已停止追踪')
  }
}

function startAutoFollow() {
  stopAutoFollow()
  autoFollowTimer = setInterval(async () => {
    if (!props.sessionToken || !visible.value) {
      stopAutoFollow()
      return
    }
    // Fetch latest tail
    try {
      const last = chunks.value[chunks.value.length - 1]
      const currentEnd = last ? last.offset + last.length : 0
      const res = await sftpReadChunk(props.sessionToken, fullRemotePath.value, {
        offset: currentEnd,
        length: chunkSize.value,
        fromEnd: false
      })

      if (res.totalSize < currentEnd) {
        // File was truncated or rotated
        await jumpToTail()
        return
      }

      if (res.length > 0) {
        const bytes = base64ToUint8Array(res.contentBase64)
        const text = decodeFileBuffer(bytes, selectedEncoding.value)
        chunks.value.push({
          offset: res.offset,
          length: res.length,
          bytes,
          text
        })
        hasMoreAfter.value = res.hasMoreAfter
        totalSize.value = res.totalSize

        if (chunks.value.length > MAX_RETAINED_CHUNKS) {
          chunks.value.shift()
          hasMoreBefore.value = true
        }

        await nextTick()
        scrollToBottom()
      } else {
        totalSize.value = res.totalSize
      }
    } catch {
      // Ignore background tail poll error
    }
  }, 2000)
}

function stopAutoFollow() {
  if (autoFollowTimer) {
    clearInterval(autoFollowTimer)
    autoFollowTimer = null
  }
}

function scrollToBottom() {
  if (viewerBodyRef.value) {
    viewerBodyRef.value.scrollTop = viewerBodyRef.value.scrollHeight + 100000
  }
}

function scrollToTop() {
  if (viewerBodyRef.value) {
    viewerBodyRef.value.scrollTop = 0
  }
}

function onViewerScroll() {
  // If user scrolls up manually while in auto follow, notify or pause follow
}

async function copyVisibleText() {
  const text = combinedText.value
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('当前已加载内容已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

async function promptSwitchToEditor() {
  try {
    await ElMessageBox.confirm(
      `当前文件大小约为 ${formatFileSize(totalSize.value)}。转入全量编辑器将一次性下载全部内容并在浏览器中排版，确定切换吗？`,
      '转入全量编辑提示',
      {
        confirmButtonText: '确认转入编辑',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    visible.value = false
    emit('switch-to-editor', props.file)
  } catch {
    /* cancel */
  }
}

onBeforeUnmount(() => {
  stopAutoFollow()
})
</script>

<style scoped>
.large-viewer-wrapper {
  display: flex;
  flex-direction: column;
  height: 68vh;
  min-height: 420px;
  max-height: 80vh;
  overflow: hidden;
  position: relative;
}

.large-viewer-wrapper.is-fullscreen {
  height: calc(100vh - 115px);
  min-height: 0;
  max-height: none;
}

.ssh-large-file-dialog :deep(.el-dialog__header) {
  padding: 10px 16px;
  margin-right: 0;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f2f5);
  background: var(--el-fill-color-blank, #ffffff);
}

.ssh-large-file-dialog :deep(.el-dialog__body) {
  padding: 0;
  overflow: hidden;
  background: var(--el-bg-color, #ffffff);
}

.ssh-large-file-dialog :deep(.el-dialog__footer) {
  padding: 8px 16px;
  border-top: 1px solid var(--el-border-color-lighter, #f0f2f5);
  background: var(--el-fill-color-blank, #ffffff);
}

/* Header bar */
.viewer-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.file-icon {
  font-size: 18px;
  color: var(--el-color-primary, #409eff);
  flex-shrink: 0;
}

.file-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}

.file-size-tag {
  font-size: 11px;
  font-family: var(--font-mono, monospace);
  color: var(--el-text-color-secondary, #909399);
  background: var(--el-fill-color, #f0f2f5);
  padding: 1px 6px;
  border-radius: 4px;
}

.live-tail-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success, #67c23a);
  border: 1px solid var(--el-color-success-light-7, #e1f3d8);
  padding: 1px 7px;
  border-radius: 4px;
  font-weight: 500;
}

.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-success, #67c23a);
  animation: pulse-dot 1.4s infinite ease-in-out;
}

@keyframes pulse-dot {
  0% { transform: scale(0.8); opacity: 0.7; }
  50% { transform: scale(1.3); opacity: 1; }
  100% { transform: scale(0.8); opacity: 0.7; }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.encoding-select {
  width: 140px;
}

.search-input {
  width: 180px;
}

.toolbar-btn {
  font-size: 16px;
  color: var(--el-text-color-regular, #606266);
  padding: 4px;
}
.popout-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--el-color-primary, #6366f1);
  font-weight: 500;
}
.popout-btn .btn-text {
  font-size: 12px;
}

/* Sub-bar */
.viewer-sub-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px;
  background: var(--el-fill-color-light, #f5f7fa);
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f2f5);
  font-size: 12px;
  flex-shrink: 0;
}

.sub-bar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.range-indicator {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
}

.sub-bar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.jump-control {
  display: flex;
  align-items: center;
  gap: 4px;
}

.jump-label {
  color: var(--el-text-color-secondary, #909399);
}

.jump-input {
  width: 60px;
}

.jump-unit {
  font-size: 11px;
  color: var(--el-text-color-placeholder, #c0c4cc);
}

/* Viewer body */
.viewer-body {
  flex: 1 1 0%;
  min-height: 0;
  height: 0;
  overflow-y: scroll !important;
  overflow-x: auto;
  scroll-behavior: auto !important;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Courier New", monospace;
  font-size: 12.5px;
  line-height: 1.5;
  padding: 10px 14px;
  position: relative;
}

.chunk-edge-banner {
  text-align: center;
  padding: 6px 0;
  user-select: none;
}

.chunk-edge-banner.is-boundary {
  font-size: 11px;
  color: #6a9955;
  letter-spacing: 0.5px;
}

.text-stream-content {
  min-height: 80px;
  word-break: break-all;
  white-space: pre-wrap;
}

.code-pre {
  margin: 0;
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
  white-space: pre-wrap;
  word-break: break-all;
  color: inherit;
}

:deep(.search-match) {
  background-color: #f39c12;
  color: #000;
  border-radius: 2px;
  padding: 0 2px;
  font-weight: 600;
}

/* Footer */
.viewer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  font-size: 12px;
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary, #909399);
  font-family: var(--font-mono, monospace);
}

.status-sep {
  color: var(--el-border-color, #dcdfe6);
}

.search-stat {
  color: var(--el-color-warning, #e6a23c);
  font-weight: 500;
}

.footer-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Dark mode adjustments */
html.dark .ssh-large-file-dialog :deep(.el-dialog__header),
html.dark .ssh-large-file-dialog :deep(.el-dialog__footer),
:root[data-theme='dark'] .ssh-large-file-dialog :deep(.el-dialog__header),
:root[data-theme='dark'] .ssh-large-file-dialog :deep(.el-dialog__footer) {
  background: var(--ws-bg-card, #0f1523);
  border-color: var(--ws-border, rgba(255, 255, 255, 0.08));
}

html.dark .viewer-sub-bar,
:root[data-theme='dark'] .viewer-sub-bar {
  background: var(--ws-bg-root, #070a12);
  border-color: var(--ws-border, rgba(255, 255, 255, 0.08));
}

html.dark .live-tail-badge,
:root[data-theme='dark'] .live-tail-badge {
  background: rgba(103, 194, 58, 0.15);
  border-color: rgba(103, 194, 58, 0.3);
}

/* Global dialog shell constraint */
:global(.ssh-large-file-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  margin-bottom: 2vh !important;
  max-height: 94vh;
  overflow: hidden;
}

:global(.ssh-large-file-dialog .el-dialog__body) {
  padding: 0 !important;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}
</style>
