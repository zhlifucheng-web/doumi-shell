<template>
  <div class="ssh-file-viewer-standalone" :class="{ 'is-dark': effectiveDark }">
    <!-- Empty / Missing Parameters State -->
    <div v-if="!sessionToken || !remotePath" class="standalone-state-container">
      <el-empty description="未指定有效的文件路径或会话 Token">
        <p class="empty-hint">请在主会话界面的文件列表中，通过「独立窗口查看」或弹窗右上角的「独立窗口」按钮打开此页面。</p>
        <el-button type="primary" @click="closeWindow">关闭窗口</el-button>
      </el-empty>
    </div>

    <!-- Active Viewer View -->
    <template v-else>
      <!-- Top Application Header (48px) -->
      <header class="viewer-top-header">
        <div class="top-left">
          <div class="brand-badge">
            <svg class="brand-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <polyline points="4 17 10 11 4 5"></polyline>
              <line x1="12" y1="19" x2="20" y2="19"></line>
            </svg>
            <span class="brand-title">Doumi Shell</span>
            <span class="brand-tag">独立查看</span>
          </div>

          <div class="header-divider" />

          <div class="file-info-group">
            <el-icon class="file-icon"><Document /></el-icon>
            <span class="file-name" :title="fileName">{{ fileName }}</span>
            <span class="file-size-pill">{{ formatFileSize(totalSize) }}</span>
            <span class="file-path-pill" :title="`远程路径: ${remotePath}`">{{ remotePath }}</span>
            <span
              v-if="autoFollow"
              class="live-tail-badge"
              title="已开启实时追踪，检测到新内容将自动追加并滚动到底部"
            >
              <span class="live-dot" /> 实时追加追踪中
            </span>
          </div>
        </div>

        <div class="top-right">
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

          <!-- Search In Loaded Content -->
          <el-input
            v-model="searchKeyword"
            size="small"
            placeholder="搜索当前已加载内容..."
            clearable
            class="search-input"
            :prefix-icon="Search"
          />

          <!-- Refresh -->
          <el-tooltip content="重新读取文件最新内容" placement="bottom">
            <el-button
              link
              class="tool-btn"
              :icon="Refresh"
              :loading="loading"
              @click="jumpToTail"
            />
          </el-tooltip>

          <!-- Theme Toggle (Dark / Light) -->
          <el-tooltip :content="effectiveDark ? '切换浅色模式' : '切换深色模式'" placement="bottom">
            <el-button
              link
              class="tool-btn"
              :icon="effectiveDark ? Sunny : Moon"
              @click="toggleTheme"
            />
          </el-tooltip>

          <!-- Fullscreen Toggle -->
          <el-tooltip :content="isFullscreen ? '退出全屏' : '全屏展示'" placement="bottom">
            <el-button
              link
              class="tool-btn"
              :icon="isFullscreen ? ScaleToOriginal : FullScreen"
              @click="toggleFullscreen"
            />
          </el-tooltip>

          <!-- Close Window -->
          <el-tooltip content="关闭独立窗口" placement="bottom">
            <el-button
              link
              class="tool-btn close-btn"
              :icon="Close"
              @click="closeWindow"
            />
          </el-tooltip>
        </div>
      </header>

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
          <!-- Tail -f Auto Follow Switch -->
          <el-switch
            v-model="autoFollow"
            size="small"
            inline-prompt
            active-text="实时追踪"
            inactive-text="追踪停止"
            @change="onAutoFollowChange"
          />

          <!-- Jump to % Percentage -->
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

      <!-- Viewer Body (Scrolls Internally) -->
      <div
        ref="viewerBodyRef"
        v-loading="loading && chunks.length === 0"
        element-loading-text="正在按需加载文件片段..."
        class="viewer-body"
        @scroll.passive="onViewerScroll"
      >
        <!-- Load Earlier Banner if more exists before -->
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

        <!-- Load Later Banner if more exists after -->
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

      <!-- Bottom Status & Action Bar -->
      <footer class="viewer-footer">
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
          <el-button size="small" @click="scrollToTop">置顶</el-button>
          <el-button size="small" @click="scrollToBottom">置底</el-button>
          <el-button size="small" @click="copyVisibleText">复制当前文本</el-button>
          <el-button size="small" type="primary" :loading="downloading" @click="downloadFullFile">
            下载文件
          </el-button>
        </div>
      </footer>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  ArrowUp,
  Bottom,
  Close,
  Document,
  FullScreen,
  Moon,
  Refresh,
  ScaleToOriginal,
  Search,
  Sunny,
  Top
} from '@element-plus/icons-vue'
import { sftpDownload, sftpReadChunk } from '@/api/sshConsole'
import { useTheme } from '@/composables/useTheme'
import {
  base64ToUint8Array,
  decodeFileBuffer,
  detectFileEncoding,
  ENCODING_OPTIONS
} from '@/utils/fileTypeHelper'

const route = useRoute()
const { effectiveDark, setPreference } = useTheme()

// Route Query Inputs
const sessionToken = computed(() => String(route.query.token || route.query.sessionToken || ''))
const remotePath = computed(() => String(route.query.path || ''))
const fileName = computed(() => {
  if (route.query.name) return String(route.query.name)
  const p = remotePath.value || ''
  return p.split('/').filter(Boolean).pop() || 'file.txt'
})
const initialSize = computed(() => Number(route.query.size) || 0)

const isFullscreen = ref(false)
const loading = ref(false)
const loadingEarlier = ref(false)
const loadingLater = ref(false)
const downloading = ref(false)
const autoFollow = ref(false)
const searchKeyword = ref('')
const jumpPercentInput = ref(100)
const selectedEncoding = ref(String(route.query.enc || 'UTF-8'))

const totalSize = ref(initialSize.value)
const chunks = ref([])
const hasMoreBefore = ref(false)
const hasMoreAfter = ref(false)

const chunkSize = ref(256 * 1024) // 256 KB
const MAX_RETAINED_CHUNKS = 16

const viewerBodyRef = ref(null)
const preRef = ref(null)
let autoFollowTimer = null

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

function toggleTheme() {
  setPreference(effectiveDark.value ? 'light' : 'dark')
}

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen?.().then(() => {
      isFullscreen.value = true
    }).catch(() => {})
  } else {
    document.exitFullscreen?.().then(() => {
      isFullscreen.value = false
    }).catch(() => {})
  }
}

function closeWindow() {
  window.close()
}

async function jumpToTail() {
  if (!sessionToken.value || !remotePath.value) return
  loading.value = true
  try {
    const res = await sftpReadChunk(sessionToken.value, remotePath.value, {
      offset: 0,
      length: chunkSize.value,
      fromEnd: true
    })
    processInitialChunk(res, true)
    await nextTick()
    scrollToBottom()
    setTimeout(scrollToBottom, 60)
  } catch (err) {
    ElMessage.error(err.message || '读取文件末尾失败')
  } finally {
    loading.value = false
  }
}

async function jumpToHead() {
  if (!sessionToken.value || !remotePath.value) return
  loading.value = true
  try {
    const res = await sftpReadChunk(sessionToken.value, remotePath.value, {
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
  totalSize.value = res.totalSize || initialSize.value || 0
  hasMoreBefore.value = res.hasMoreBefore
  hasMoreAfter.value = res.hasMoreAfter

  const bytes = base64ToUint8Array(res.contentBase64)
  if (chunks.value.length === 0 && !route.query.enc) {
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
    const res = await sftpReadChunk(sessionToken.value, remotePath.value, {
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
  const targetLength = chunkSize.value

  try {
    const res = await sftpReadChunk(sessionToken.value, remotePath.value, {
      offset: targetOffset,
      length: targetLength,
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
  if (pct >= 95) {
    await jumpToTail()
    return
  }
  if (pct <= 5) {
    await jumpToHead()
    return
  }

  const targetByte = Math.floor((totalSize.value * pct) / 100)
  loading.value = true
  try {
    const res = await sftpReadChunk(sessionToken.value, remotePath.value, {
      offset: Math.max(0, targetByte - Math.floor(chunkSize.value / 2)),
      length: chunkSize.value,
      fromEnd: false
    })
    processInitialChunk(res, false)
    await nextTick()
    scrollToTop()
  } catch (err) {
    ElMessage.error(err.message || '跳转位置失败')
  } finally {
    loading.value = false
  }
}

function changeEncoding() {
  for (const c of chunks.value) {
    if (c.bytes) {
      c.text = decodeFileBuffer(c.bytes, selectedEncoding.value)
    }
  }
}

function onAutoFollowChange(val) {
  if (val) {
    startAutoFollow()
    ElMessage.success('已开启实时追踪 (Tail -f 模式)')
  } else {
    stopAutoFollow()
    ElMessage.info('已暂停实时追踪')
  }
}

function startAutoFollow() {
  stopAutoFollow()
  autoFollowTimer = setInterval(pollTailForUpdates, 1500)
}

function stopAutoFollow() {
  if (autoFollowTimer) {
    clearInterval(autoFollowTimer)
    autoFollowTimer = null
  }
}

async function pollTailForUpdates() {
  if (!autoFollow.value || loadingLater.value || !chunks.value.length || !sessionToken.value) return

  const last = chunks.value[chunks.value.length - 1]
  const currentEnd = last.offset + last.length

  try {
    const res = await sftpReadChunk(sessionToken.value, remotePath.value, {
      offset: currentEnd,
      length: chunkSize.value,
      fromEnd: false
    })

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
      totalSize.value = res.totalSize || totalSize.value

      if (chunks.value.length > MAX_RETAINED_CHUNKS) {
        chunks.value.shift()
        hasMoreBefore.value = true
      }

      await nextTick()
      scrollToBottom()
    } else {
      totalSize.value = res.totalSize || totalSize.value
    }
  } catch {
    /* Silent retry during auto poll */
  }
}

function onViewerScroll() {
  if (!viewerBodyRef.value) return
  const { scrollTop, scrollHeight, clientHeight } = viewerBodyRef.value
  const atBottom = scrollHeight - (scrollTop + clientHeight) < 40
  if (!atBottom && autoFollow.value) {
    autoFollow.value = false
    stopAutoFollow()
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

async function copyVisibleText() {
  try {
    await navigator.clipboard.writeText(combinedText.value)
    ElMessage.success('已复制当前已加载的文本内容')
  } catch {
    ElMessage.error('复制失败，请手动选取复制')
  }
}

async function downloadFullFile() {
  if (!sessionToken.value || !remotePath.value) return
  downloading.value = true
  try {
    await sftpDownload(sessionToken.value, remotePath.value, fileName.value)
    ElMessage.success(`开始下载 ${fileName.value}`)
  } catch (err) {
    ElMessage.error(err.message || '下载文件失败')
  } finally {
    downloading.value = false
  }
}

onMounted(() => {
  if (fileName.value) {
    document.title = `${fileName.value} - 文件查看 - Doumi Shell`
  }
  if (sessionToken.value && remotePath.value) {
    jumpToTail()
  }
})

onBeforeUnmount(() => {
  stopAutoFollow()
})
</script>

<style scoped>
.ssh-file-viewer-standalone {
  display: flex;
  flex-direction: column;
  width: 100vw;
  height: 100vh;
  background-color: var(--ws-bg-root, #f8fafc);
  color: var(--ws-text-primary, #0f172a);
  overflow: hidden;
  font-family: var(--font-sans, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif);
}

.standalone-state-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}
.empty-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
  margin: 12px 0 16px;
  max-width: 460px;
  text-align: center;
}

/* 1. Top Header */
.viewer-top-header {
  height: 48px;
  min-height: 48px;
  background-color: var(--ws-bg-header, #ffffff);
  border-bottom: 1px solid var(--ws-border, #e2e8f0);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  gap: 12px;
  flex-shrink: 0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.top-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.brand-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
  font-size: 13px;
  color: var(--ws-text-primary, #0f172a);
  flex-shrink: 0;
}
.brand-svg {
  width: 16px;
  height: 16px;
  color: var(--ws-accent, #6366f1);
}
.brand-title {
  white-space: nowrap;
}
.brand-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: var(--ws-radius-xs, 4px);
  background: var(--ws-accent-bg, rgba(99, 102, 241, 0.12));
  color: var(--ws-accent, #6366f1);
  border: 1px solid var(--ws-accent-border, rgba(99, 102, 241, 0.25));
  letter-spacing: 0.5px;
}

.header-divider {
  width: 1px;
  height: 16px;
  background-color: var(--ws-border, #e2e8f0);
  flex-shrink: 0;
}

.file-info-group {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.file-icon {
  font-size: 16px;
  color: var(--el-color-primary, #6366f1);
  flex-shrink: 0;
}

.file-name {
  font-family: var(--font-mono, monospace);
  font-weight: 600;
  font-size: 13px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size-pill {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color, #f0f2f5);
  color: var(--el-text-color-secondary, #909399);
  font-family: var(--font-mono, monospace);
  flex-shrink: 0;
}

.file-path-pill {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light, #f5f7fa);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  color: var(--el-text-color-secondary, #909399);
  font-family: var(--font-mono, monospace);
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-tail-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  color: #67c23a;
  background: rgba(103, 194, 58, 0.12);
  border: 1px solid rgba(103, 194, 58, 0.3);
  padding: 1px 7px;
  border-radius: 10px;
  flex-shrink: 0;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #67c23a;
  box-shadow: 0 0 6px #67c23a;
  animation: pulse-live 1.6s infinite ease-in-out;
}

@keyframes pulse-live {
  0% { transform: scale(0.8); opacity: 0.7; }
  50% { transform: scale(1.3); opacity: 1; }
  100% { transform: scale(0.8); opacity: 0.7; }
}

.top-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.encoding-select {
  width: 135px;
}

.search-input {
  width: 180px;
}

.tool-btn {
  font-size: 16px;
  color: var(--ws-text-regular, #64748b);
  padding: 5px;
}
.tool-btn:hover {
  color: var(--ws-text-primary, #0f172a);
}
.close-btn:hover {
  color: var(--el-color-danger, #ef4444);
}

/* 2. Sub-bar */
.viewer-sub-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 16px;
  background: var(--ws-bg-card, #ffffff);
  border-bottom: 1px solid var(--ws-border, #e2e8f0);
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
  color: var(--ws-text-muted, #64748b);
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
  color: var(--ws-text-muted, #64748b);
}
.jump-input {
  width: 60px;
}
.jump-unit {
  font-size: 11px;
  color: var(--ws-text-muted, #94a3b8);
}

/* 3. Viewer Body */
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
  font-size: 13px;
  line-height: 1.55;
  padding: 12px 18px;
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

/* 4. Footer */
.viewer-footer {
  height: 38px;
  min-height: 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: var(--ws-bg-card, #ffffff);
  border-top: 1px solid var(--ws-border, #e2e8f0);
  font-size: 12px;
  flex-shrink: 0;
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ws-text-muted, #64748b);
  font-family: var(--font-mono, monospace);
}

.status-sep {
  color: var(--ws-border, #cbd5e1);
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

/* Dark Theme Adjustments */
html.dark .viewer-top-header,
:root[data-theme='dark'] .viewer-top-header {
  background-color: var(--ws-bg-card, #0f1523);
  border-color: var(--ws-border, rgba(255, 255, 255, 0.08));
}

html.dark .viewer-sub-bar,
:root[data-theme='dark'] .viewer-sub-bar,
html.dark .viewer-footer,
:root[data-theme='dark'] .viewer-footer {
  background-color: var(--ws-bg-card, #0f1523);
  border-color: var(--ws-border, rgba(255, 255, 255, 0.08));
}

html.dark .file-size-pill,
:root[data-theme='dark'] .file-size-pill {
  background: rgba(255, 255, 255, 0.06);
  color: #94a3b8;
}

html.dark .file-path-pill,
:root[data-theme='dark'] .file-path-pill {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(255, 255, 255, 0.08);
  color: #94a3b8;
}
</style>
