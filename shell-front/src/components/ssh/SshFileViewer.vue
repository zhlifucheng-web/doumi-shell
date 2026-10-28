<template>
  <section ref="rootRef" class="file-viewer" :class="{ 'is-dark': effectiveDark }" @keydown="onKeydown">
    <header class="viewer-header">
      <div class="file-title"><strong :title="name">{{ name }}</strong><span :title="path">{{ path }}</span></div>
      <div class="viewer-tools">
        <el-button size="small" :type="toolsVisible ? 'primary' : 'default'" :aria-expanded="toolsVisible" title="查找 / 筛选（Ctrl/⌘+F）" @click="toggleSearchTools">查找 / 筛选</el-button>
        <el-select v-model="selectedEncoding" size="small" class="encoding" aria-label="文件编码" @change="changeEncoding">
          <el-option v-for="enc in ENCODING_OPTIONS" :key="enc.value" :label="enc.label" :value="enc.value" />
        </el-select>
        <el-button v-if="!standalone" size="small" @click="emit('standalone', selectedEncoding)">独立窗口</el-button>
        <el-button size="small" @click="toggleTheme">{{ effectiveDark ? '浅色' : '深色' }}</el-button>
        <el-button size="small" @click="toggleFullscreen">全屏</el-button>
        <el-button size="small" @click="emit('close')">关闭</el-button>
      </div>
    </header>
    <div v-if="filter.active.value" class="filter-status" role="status">
      {{ filterStatus }} · 扫描 {{ formatSize(filter.offset.value) }} / {{ formatSize(filter.totalSize.value) }} ({{ filterProgress }}%) · 显示 {{ filter.rows.value.length }} 行（含上下文），{{ filter.rows.value.filter(r => r.matched).length }} 行匹配
      <span v-if="filter.limited.value"> · 已达结果上限，请缩小关键词范围</span>
      <span v-if="filter.error.value"> · {{ filter.error.value }}</span>
    </div>
    <div class="viewer-bar navigation-bar">
      <el-button size="small" :disabled="filter.active.value || loading" @click="jumpToHead">文件开头</el-button>
      <el-button size="small" :disabled="filter.active.value || !hasMoreBefore || loading" @click="loadEarlier">向上加载</el-button>
      <el-button size="small" :disabled="filter.active.value || !hasMoreAfter || loading" @click="loadLater">向下加载</el-button>
      <el-button size="small" :disabled="loading" @click="goBottom">{{ filter.active.value ? '结果末尾 / Tail' : '文件末尾 / Tail' }}</el-button>
      <span v-if="!filter.active.value" class="range-info">{{ formatSize(rangeStart) }} ~ {{ formatSize(rangeEnd) }} / {{ formatSize(totalSize) }}</span>
      <template v-if="!filter.active.value">
        <el-input-number v-model="jumpPercent" :min="0" :max="100" :controls="false" size="small" class="jump-input" aria-label="跳转文件百分比" @keyup.enter="jumpToPercent" />
        <span>%</span><el-button size="small" :disabled="loading" @click="jumpToPercent">前往</el-button>
      </template>
      <el-button size="small" :disabled="loading" @click="filter.active.value ? startFilter(filterKeyword) : jumpToTail()">刷新</el-button>
      <el-switch :model-value="autoFollow" active-text="Tail" :disabled="filter.active.value && !canFilterTail" @change="setFollowing" />
      <span v-if="autoFollow" class="tail-status">{{ tailStatus === 'reconnecting' ? '连接中断，正在续接…' : tailStatus === 'connecting' ? '正在连接…' : '实时追踪中' }}</span>
      <span v-else class="scope-hint">追踪暂停</span>
    </div>
    <div v-if="recoveryNotice" class="viewer-bar" role="status">
      <span>{{ recoveryNotice }}{{ recovering && !autoFollow ? '（恢复追踪后加载）' : '' }}</span>
      <el-button v-if="recovering" size="small" :disabled="!!selectedText" @click="setFollowing(true)">重新连接 / 继续</el-button>
    </div>
    <div class="viewer-content">
      <div v-if="toolsVisible" class="viewer-search" @mousedown.stop @keydown.esc.stop.prevent="closeSearchTools">
        <select v-model="searchMode" aria-label="搜索范围" title="选择查找或筛选" @change="focusSearchInput">
          <option value="find">当前内容</option>
          <option value="filter">全文件筛选</option>
        </select>
        <input ref="searchInputRef" v-model="searchKeyword" :placeholder="searchMode === 'filter' ? '筛选全文件内容' : '查找当前显示内容'" aria-label="查找和筛选关键词" @keydown.enter.stop.prevent="onSearchEnter" />
        <span class="match-status">{{ matchCount ? activeMatch + 1 : 0 }} / {{ matchCount }}</span>
        <button type="button" aria-label="上一个匹配" title="上一个匹配（Shift+Enter）" :disabled="!matchCount" @click="navigateMatch(-1)">↑</button>
        <button type="button" aria-label="下一个匹配" title="下一个匹配（Enter）" :disabled="!matchCount" @click="navigateMatch(1)">↓</button>
        <template v-if="searchMode === 'filter'">
          <el-checkbox v-model="caseSensitive" size="small">区分大小写</el-checkbox>
          <el-checkbox v-model="withContext" size="small">前后 2 行</el-checkbox>
          <button type="button" :disabled="!searchKeyword" @click="startFilter()">{{ filter.active.value ? '重新筛选' : '筛选行' }}</button>
          <button v-if="filter.scanning.value || (filter.paused.value && !filter.readyForTail.value)" type="button" @click="cancelFilter">取消扫描</button>
        </template>
        <button v-if="filter.active.value" type="button" @click="leaveFilter">恢复原文</button>
        <button type="button" aria-label="关闭查找和筛选" @click="closeSearchTools">关闭</button>
      </div>
      <div ref="viewerBodyRef" v-loading="loading" class="viewer-body" tabindex="0" aria-label="文件文本内容" @scroll.passive="onViewerScroll" @wheel.passive="holdFollow = false" @pointerdown="holdFollow = false" @keydown="holdFollow = false">
        <div v-if="!filter.active.value && hasMoreBefore" class="edge-note">前面还有内容，可向上加载</div>
        <pre class="file-content"><span v-for="block in renderedBlocks" :key="block.key" v-html="block.html" /></pre>
        <div v-if="filter.active.value && !filter.rows.value.length && !filter.scanning.value" class="edge-note">{{ filter.eof.value ? '没有匹配行' : '当前尚无结果；文件尚未扫描完毕' }}</div>
        <div v-if="!filter.active.value && hasMoreAfter" class="edge-note">当前片段后还有内容，可向下加载</div>
      </div>
    </div>
    <footer class="viewer-bar viewer-footer">
      <span class="scope-hint">{{ selectedText ? `已选 ${selectedText.length} 字符` : '可拖选文本，Ctrl/⌘+C 复制' }}</span>
      <el-button size="small" @click="goTop">置顶</el-button>
      <el-button size="small" @click="goBottom">置底</el-button>
      <el-button size="small" :disabled="!selectedText" @mousedown.prevent @click="copySelection">复制选中</el-button>
      <el-button size="small" @click="copyDisplayed">{{ filter.active.value ? '复制筛选结果' : '复制已加载文本' }}</el-button>
      <el-button v-if="!standalone && size <= 50 * 1024 * 1024" size="small" @click="emit('edit')">编辑</el-button>
      <el-button size="small" :loading="downloading" @click="downloadFile">下载文件</el-button>
    </footer>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { sftpReadChunk, sftpDownload, sftpFilterStart, sftpFilterCancel, buildFileTailWsUrl, getSessionContext, openViewerSession, closeViewerSession } from '@/api/sshConsole'
import { base64ToUint8Array, decodeFileBuffer, detectFileEncoding, ENCODING_OPTIONS } from '@/utils/fileTypeHelper'
import { createViewerBlockRenderer, highlightText, searchMatches, nextMatch, interceptFind } from '@/utils/fileViewerText'
import { createViewerTail } from '@/composables/useViewerTail'
import { createViewerSession, isViewerSessionError } from '@/composables/useViewerSession'
import { useViewerFilter } from '@/composables/useViewerFilter'
import { useTheme } from '@/composables/useTheme'

const props = defineProps({ sessionToken: String, accountId: Number, path: String, name: String, size: { type: Number, default: 0 }, encoding: { type: String, default: '' }, standalone: Boolean })
const emit = defineEmits(['close', 'standalone', 'edit'])
const { effectiveDark, setPreference } = useTheme()
const rootRef = ref(null), viewerBodyRef = ref(null), searchInputRef = ref(null)
const toolsVisible = ref(false), searchMode = ref('find')
const searchKeyword = ref(''), activeMatch = ref(-1), selectedText = ref(''), frozenHtml = ref(null)
const selectedEncoding = ref(props.encoding || 'UTF-8'), filterKeyword = ref(''), caseSensitive = ref(false), withContext = ref(false)
const loading = ref(false), downloading = ref(false), autoFollow = ref(false), chunks = ref([])
const totalSize = ref(props.size), hasMoreBefore = ref(false), hasMoreAfter = ref(false)
const jumpPercent = ref(100)
const chunkSize = 256 * 1024, maxChunks = 16
let requestGeneration = 0, tailStarted = false, disposed = false, originalScroll = 0, holdFollow = false
let followAfterScan = false
const tailStatus = ref('paused'), recoveryNotice = ref(''), recovering = ref(false)
let recoveryReason = null, recoveryTimer = null, recoveryRunning = false, recoveryVersion = 0, retryDelay = 1000
const viewerSession = createViewerSession({ token: props.sessionToken, accountId: props.accountId,
  api: { context: getSessionContext, open: openViewerSession, close: closeViewerSession }
})
const normalHistory = ref([])
let contentEpoch = 0
const normalBlocks = computed(() => [...normalHistory.value, ...chunks.value])
const combinedText = computed(() => normalBlocks.value.map(c => c.text).join(''))
const rangeStart = computed(() => chunks.value[0]?.offset || 0)
const rangeEnd = computed(() => { const c = chunks.value.at(-1); return c ? c.offset + c.length : 0 })
const filter = useViewerFilter({
  source: () => ({ sessionToken: viewerSession.token, path: props.path, encoding: selectedEncoding.value }),
  api: { start: data => viewerSession.run(async token => ({ ...await sftpFilterStart({ ...data, sessionToken: token }), sessionToken: token })), stream: options => createViewerTail({ ...options, url: buildFileTailWsUrl }), cancel: (scanId, sessionToken) => sftpFilterCancel({ scanId, sessionToken }) },
  onError: (message, frame) => {
    const code = frame?.code || recoveryCode({ message })
    if (['FILE_CHANGED', 'SESSION_EXPIRED', 'SSH_DISCONNECTED', 'TAIL_EXPIRED'].includes(code)) requestRecovery({ ...frame, code, message })
    else if (!recovering.value) { setFollowing(false); ElMessage.error(message) }
  }
})
const displayedText = computed(() => filter.active.value ? filter.displayRows.value.map(r => r.text).join('\n') : combinedText.value)
const renderBlocks = createViewerBlockRenderer()
const contentBlocks = computed(() => renderBlocks(filter.active.value ? filter.displayRows.value : normalBlocks.value, searchKeyword.value, activeMatch.value, filter.active.value))
const matchCount = computed(() => searchKeyword.value ? contentBlocks.value.matchCount : 0)
const canFilterTail = computed(() => filter.readyForTail.value && !filter.canceled.value && !filter.limited.value && !filter.error.value)
const filterProgress = computed(() => filter.totalSize.value ? Math.min(100, Math.floor(100 * filter.offset.value / filter.totalSize.value)) : filter.eof.value ? 100 : 0)
const filterStatus = computed(() => `${filter.mode.value === 'sftp' ? 'SFTP兼容扫描' : '远端扫描'} · ${filter.error.value ? '筛选中断' : filter.limited.value ? '结果已截断' : filter.canceled.value ? '已取消（部分结果）' : filter.eof.value ? '已扫描到文件末尾' : filter.paused.value ? '选择文本中，扫描暂停' : filter.scanning.value ? '全文件扫描中' : '部分扫描结果'}`)
const renderedBlocks = computed(() => frozenHtml.value ?? contentBlocks.value)
watch([searchKeyword, matchCount], () => { activeMatch.value = matchCount.value ? Math.min(Math.max(0, activeMatch.value), matchCount.value - 1) : -1 })
watch(filter.readyForTail, async ready => {
  if (ready && !recovering.value && followAfterScan && !selectedText.value && !disposed) { holdFollow = false; setFollowing(true); await nextTick(); scrollToBottom() }
})
watch(filter.limited, value => { if (value) setFollowing(false) })

function formatSize(n) { return n < 1024 ? `${n || 0} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(2)} MB` }
function toggleTheme() { setPreference(effectiveDark.value ? 'light' : 'dark') }
function toggleFullscreen() {
  const promise = document.fullscreenElement ? document.exitFullscreen?.() : rootRef.value?.requestFullscreen?.()
  promise?.catch(() => ElMessage.warning('浏览器未允许全屏'))
}
function chunk(res, streaming = false) {
  const bytes = base64ToUint8Array(res.contentBase64)
  // The backend seeds its decoder from the byte boundary for subsequent Tail data.
  let text
  try { text = streaming ? new TextDecoder(selectedEncoding.value).decode(bytes, { stream: true }) : decodeFileBuffer(bytes, selectedEncoding.value) }
  catch { text = decodeFileBuffer(bytes, selectedEncoding.value) }
  return { offset: res.offset, length: res.length, bytes, text, renderKey: `byte:${contentEpoch}:${res.offset}` }
}
function updateBounds(res) { totalSize.value = res.totalSize; hasMoreBefore.value = rangeStart.value > 0; hasMoreAfter.value = rangeEnd.value < res.totalSize }
function scrollToBottom() { const el = viewerBodyRef.value; if (el) el.scrollTop = el.scrollHeight }
function pauseForReading() { holdFollow = true; followAfterScan = false; setFollowing(false) }
function goTop() { pauseForReading(); if (viewerBodyRef.value) viewerBodyRef.value.scrollTop = 0 }

async function replaceChunk(fromEnd, offset = 0) {
  if (!props.sessionToken || !props.path) return
  stopTail()
  const gen = ++requestGeneration
  loading.value = true
  try {
    const res = await viewerSession.run(token => sftpReadChunk(token, props.path, { offset, length: chunkSize, fromEnd }))
    if (disposed || gen !== requestGeneration || filter.active.value) return
    if (!chunks.value.length && !props.encoding) selectedEncoding.value = detectFileEncoding(base64ToUint8Array(res.contentBase64))
    normalHistory.value = []; ++contentEpoch
    chunks.value = [chunk(res, fromEnd)]; updateBounds(res)
    await nextTick()
    if (fromEnd) { holdFollow = false; setFollowing(true); scrollToBottom() }
    else { pauseForReading(); if (viewerBodyRef.value) viewerBodyRef.value.scrollTop = 0 }
  } catch (err) { if (gen === requestGeneration && !disposed) ElMessage.error(err.message || '读取文件失败') }
  finally { if (gen === requestGeneration) loading.value = false }
}
function jumpToTail() { return replaceChunk(true) }
function jumpToHead() { return replaceChunk(false) }
function jumpToPercent() {
  const percent = Math.max(0, Math.min(100, Number(jumpPercent.value) || 0))
  return percent === 100 ? jumpToTail() : replaceChunk(false, Math.max(0, Math.floor(totalSize.value * percent / 100) - (percent ? chunkSize / 2 : 0)))
}
async function loadAdjacent(earlier) {
  if (loading.value || !chunks.value.length || filter.active.value) return
  pauseForReading(); stopTail()
  const gen = ++requestGeneration, el = viewerBodyRef.value
  const height = el?.scrollHeight || 0, top = el?.scrollTop || 0
  const offset = earlier ? Math.max(0, rangeStart.value - chunkSize) : rangeEnd.value
  loading.value = true
  try {
    const res = await viewerSession.run(token => sftpReadChunk(token, props.path, { offset, length: earlier ? rangeStart.value - offset : chunkSize }))
    if (disposed || gen !== requestGeneration || filter.active.value) return
    if (earlier) chunks.value.unshift(chunk(res)); else chunks.value.push(chunk(res))
    trimNormalHistory(earlier)
    updateBounds(res)
    await nextTick()
    if (earlier && el) el.scrollTop = top + el.scrollHeight - height
  } catch (err) { if (gen === requestGeneration && !disposed) ElMessage.error(err.message || '加载文件片段失败') }
  finally { if (gen === requestGeneration) loading.value = false }
}
function loadEarlier() { return loadAdjacent(true) }
function loadLater() { return loadAdjacent(false) }
async function goBottom() {
  holdFollow = false
  if (!filter.active.value) { await jumpToTail(); return }
  scrollToBottom()
  if (recoveryReason) { filter.pause(false); setFollowing(true); return }
  if (canFilterTail.value) setFollowing(true)
  else if (filter.scanning.value) { followAfterScan = true; filter.pause(false) }
}
const tail = createViewerTail({
  url: buildFileTailWsUrl,
  onStatus: value => { tailStatus.value = value },
  onRecover: requestRecovery,
  onError: message => {
    tailStarted = false; autoFollow.value = false
    if (filter.active.value) filter.error.value = message.includes('重新筛选') ? message : `${message}；请重新筛选`
    ElMessage.error(message)
  },
  onData: page => {
    if (disposed || !autoFollow.value || selectedText.value || loading.value) return false
    if (!Number.isSafeInteger(page.offset) || page.offset < 0 || !Number.isSafeInteger(page.totalSize) || page.totalSize < page.offset) throw new Error('实时追踪字节范围无效，请刷新文件')
    if (filter.active.value) {
      if (page.scanId !== filter.tailSource()?.scanId) throw new Error('筛选任务已改变，请重新筛选')
      if (!Array.isArray(page.rows) || page.rows.length > 10000 || page.rows.some(row => !Number.isSafeInteger(row.lineNumber) || row.lineNumber < 1 || typeof row.text !== 'string')) throw new Error('筛选追踪数据无效，请重新筛选')
      if (!filter.applyTail(page)) return false
    } else {
      if (typeof page.content !== 'string') throw new Error('实时追踪文本无效，请刷新文件')
      const offset = rangeEnd.value
      if (page.offset < offset || page.totalSize < offset) throw Object.assign(new Error('文件已改变或截断，正在重新读取'), { code: 'FILE_CHANGED' })
      if (page.offset > offset) {
        // An empty initial HTTP read shares its offset with the first pushed batch.
        if (chunks.value.at(-1)?.length === 0) chunks.value.pop()
        chunks.value.push({ offset, length: page.offset - offset, text: page.content || '', renderKey: `byte:${contentEpoch}:${offset}` })
        trimNormalHistory()
      }
      updateBounds(page)
    }
    void nextTick(() => { if (autoFollow.value && !selectedText.value) scrollToBottom() })
    if (page.limited) setFollowing(false)
    return true
  }
})
function trimNormalHistory(earlier = false) {
  while (normalHistory.value.length + chunks.value.length > maxChunks) {
    if (normalHistory.value.length) normalHistory.value.shift()
    else if (earlier) chunks.value.pop()
    else chunks.value.shift()
  }
}
function cancelRecovery() {
  ++recoveryVersion; recoveryReason = null; recovering.value = false; recoveryNotice.value = ''
  if (recoveryTimer != null) clearTimeout(recoveryTimer)
  recoveryTimer = null
}
function stopTail() { cancelRecovery(); tail.close(); tailStarted = false; autoFollow.value = false }
function recoveryCode(error) {
  if (isViewerSessionError(error)) return 'SESSION_EXPIRED'
  if (/文件.*(?:截断|替换|改变|不一致)/.test(error?.message || '')) return 'FILE_CHANGED'
  if (/(?:Tail|筛选任务).*(?:过期|失效|不存在)/.test(error?.message || '')) return 'TAIL_EXPIRED'
  return null
}
function requestRecovery(frame) {
  tailStarted = false
  if (followAfterScan && !selectedText.value) autoFollow.value = true
  recoveryReason = frame
  recoveryNotice.value = frame.code === 'FILE_CHANGED' ? '文件已重置，正在重新读取并继续追踪…' : '会话已失效，正在重新连接并恢复文件追踪…'
  recovering.value = true
  void recoverTail()
}
async function recoverTail() {
  if (disposed || recoveryRunning || !recoveryReason || !autoFollow.value || selectedText.value) return
  if (recoveryTimer != null) clearTimeout(recoveryTimer)
  recoveryTimer = null; recoveryRunning = true
  const version = recoveryVersion, reason = recoveryReason
  const current = () => !disposed && version === recoveryVersion && autoFollow.value && !selectedText.value
  loading.value = true
  try {
    if (['SESSION_EXPIRED', 'SSH_DISCONNECTED'].includes(reason.code) && !reason.reconnected) { await viewerSession.reconnect(); reason.reconnected = true }
    if (!current()) return
    if (filter.active.value) {
      followAfterScan = true
      if (!reason.filterLoaded) {
        await filter.start({ keyword: filterKeyword.value, caseSensitive: caseSensitive.value, contextLines: withContext.value ? 2 : 0 }, { preserveHistory: true, truncated: reason.code === 'FILE_CHANGED' })
        if (filter.error.value) throw new Error(filter.error.value)
        reason.filterLoaded = filter.readyForTail.value || filter.limited.value
      }
    } else {
      let offset = reason.code === 'FILE_CHANGED' ? 0 : rangeStart.value
      let page = await viewerSession.run(token => sftpReadChunk(token, props.path, { offset, length: chunkSize, fromEnd: false }))
      if (!current()) return
      if (page.totalSize < offset) {
        page = await viewerSession.run(token => sftpReadChunk(token, props.path, { offset: 0, length: chunkSize, fromEnd: false }))
        if (!current()) return
      }
      if (reason.code === 'FILE_CHANGED') {
        const newline = combinedText.value && !combinedText.value.endsWith('\n') ? '\n' : ''
        normalHistory.value.push(...chunks.value, { renderKey: `reset:${++contentEpoch}`, text: `${newline}===================truncated===================\n` })
      }
      chunks.value = [chunk(page, true)]; trimNormalHistory(); updateBounds(page)
    }
    if (!current()) return
    recoveryReason = null; recovering.value = false; retryDelay = 1000
    recoveryNotice.value = reason.code === 'FILE_CHANGED' ? '文件已重置，已重新加载并继续追踪' : '连接已恢复，继续追踪'
    loading.value = false; setFollowing(true)
    void nextTick(() => { if (current()) scrollToBottom() })
  } catch (error) {
    if (current()) {
      recoveryNotice.value = `恢复暂未成功，稍后自动重试：${error.message || '连接不可用'}`
      recoveryTimer = setTimeout(() => { recoveryTimer = null; void recoverTail() }, retryDelay)
      retryDelay = Math.min(30000, retryDelay * 2)
    }
  } finally {
    recoveryRunning = false
    if (version === recoveryVersion) loading.value = false
    // A pause/resume may have invalidated an in-flight reload. Never commit that result.
    if (version !== recoveryVersion && recoveryReason && autoFollow.value && !disposed) void recoverTail()
  }
}
function setFollowing(value) {
  const next = Boolean(value) && !selectedText.value && (!filter.active.value || canFilterTail.value || recoveryReason)
  if (!next) {
    autoFollow.value = false
    filter.pause(true)
    if (recoveryReason) { ++recoveryVersion; filter.pause(true); loading.value = false; if (recoveryTimer != null) clearTimeout(recoveryTimer); recoveryTimer = null }
    if (tailStarted) tail.pause()
    return
  }
  if (recoveryReason) { autoFollow.value = true; void recoverTail(); return }
  if (autoFollow.value && tailStarted) return
  autoFollow.value = true
  filter.pause(false)
  if (tailStarted) tail.resume()
  else {
    tailStarted = true
    tail.start({ sessionToken: viewerSession.token, path: props.path, encoding: selectedEncoding.value,
      offset: filter.active.value ? filter.offset.value : rangeEnd.value,
      ...(filter.active.value ? filter.tailSource() : { anchorBase64: tailAnchor() }) })
  }
}
function tailAnchor() {
  const bytes = chunks.value.at(-1)?.bytes
  return bytes?.length ? btoa(String.fromCharCode(...bytes.subarray(Math.max(0, bytes.length - 4096)))) : undefined
}
function onViewerScroll() {
  const el = viewerBodyRef.value
  if (!el || (loading.value && !filter.scanning.value) || selectedText.value) return
  const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24
  if (!atBottom) { followAfterScan = false; setFollowing(false) }
  else if (!holdFollow) {
    if (filter.active.value && filter.scanning.value) { followAfterScan = true; filter.pause(false) }
    else if (filter.active.value ? canFilterTail.value : chunks.value.length && !hasMoreAfter.value) setFollowing(true)
  }
}
function onSelectionChange() {
  const selection = window.getSelection?.(), el = viewerBodyRef.value
  const range = selection?.rangeCount ? selection.getRangeAt(0) : null
  const text = range && el?.contains(range.commonAncestorContainer) ? selection.toString() : ''
  if (text) {
    if (!selectedText.value) frozenHtml.value = contentBlocks.value
    selectedText.value = text; pauseForReading(); filter.pause(true)
  } else {
    selectedText.value = ''; frozenHtml.value = null; if (!holdFollow) filter.pause(false)
  }
}
async function openSearchTools() {
  toolsVisible.value = true; pauseForReading()
  await nextTick()
  if (!disposed && toolsVisible.value) focusSearchInput(true)
}
function focusSearchInput(select = false) { searchInputRef.value?.focus(); if (select === true) searchInputRef.value?.select() }
function onSearchEnter(event) { return searchMode.value === 'filter' && !event.shiftKey ? startFilter() : navigateMatch(event.shiftKey ? -1 : 1) }
function closeSearchTools() { toolsVisible.value = false; viewerBodyRef.value?.focus?.() }
function toggleSearchTools() { return toolsVisible.value ? closeSearchTools() : openSearchTools() }
function onKeydown(event) {
  if (interceptFind(event, () => {
    if (toolsVisible.value && event.target === searchInputRef.value) void navigateMatch(1)
    else void openSearchTools()
  })) return
}
function onDocumentKeydown(event) {
  if (props.standalone || rootRef.value?.contains(event.target)) onKeydown(event)
}
async function navigateMatch(direction) {
  pauseForReading()
  activeMatch.value = nextMatch(activeMatch.value, matchCount.value, direction)
  await nextTick()
  viewerBodyRef.value?.querySelector(`[data-search-index="${activeMatch.value}"]`)?.scrollIntoView({ block: 'center' })
}
async function copyText(text) {
  try {
    if (!navigator.clipboard?.writeText) {
      const input = document.createElement('textarea'); input.value = text
      input.style.cssText = 'position:fixed;opacity:0;pointer-events:none'
      document.body.appendChild(input); input.select()
      const copied = document.execCommand('copy'); input.remove()
      if (!copied) throw new Error('clipboard unavailable')
    } else await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch { ElMessage.warning('复制失败，请选中文本后按 Ctrl/⌘+C') }
}
function copySelection() { return copyText(selectedText.value) }
function copyDisplayed() { return copyText(displayedText.value) }
async function downloadFile() {
  downloading.value = true
  try { await viewerSession.run(token => sftpDownload(token, props.path, props.name)) }
  catch (err) { ElMessage.error(err.message || '下载失败') }
  finally { downloading.value = false }
}
async function startFilter(keyword = searchKeyword.value) {
  if (!keyword) return
  filterKeyword.value = keyword
  if (!filter.active.value) originalScroll = viewerBodyRef.value?.scrollTop || 0
  const wasFollowing = autoFollow.value || followAfterScan
  ++requestGeneration; loading.value = false; pauseForReading(); stopTail()
  followAfterScan = wasFollowing
  selectedText.value = ''; frozenHtml.value = null
  await filter.start({ keyword: filterKeyword.value, caseSensitive: caseSensitive.value, contextLines: withContext.value ? 2 : 0 })
}
function cancelFilter() { stopTail(); return filter.cancel() }
async function leaveFilter() {
  stopTail(); await filter.close()
  frozenHtml.value = null; selectedText.value = ''
  await nextTick(); if (viewerBodyRef.value) viewerBodyRef.value.scrollTop = originalScroll
}
async function changeEncoding() {
  stopTail()
  if (filter.active.value) await startFilter(filterKeyword.value)
  else await replaceChunk(false, rangeStart.value)
}
onMounted(() => {
  document.addEventListener('keydown', onDocumentKeydown, true)
  document.addEventListener('selectionchange', onSelectionChange)
  void jumpToTail()
})
onBeforeUnmount(() => {
  disposed = true; ++requestGeneration; stopTail(); viewerSession.close(); void filter.close()
  document.removeEventListener('keydown', onDocumentKeydown, true)
  document.removeEventListener('selectionchange', onSelectionChange)
})
</script>

<style scoped>
.file-viewer { display:flex; flex-direction:column; width:100%; height:100%; min-height:0; overflow:hidden; color:var(--ws-text-primary,#1e293b); background:var(--ws-bg-card,#fff); }
.viewer-header,.viewer-bar { display:flex; align-items:center; gap:8px; padding:8px 12px; flex-shrink:0; flex-wrap:wrap; border-bottom:1px solid var(--ws-border,#e2e8f0); }
.viewer-header { justify-content:space-between; }
.file-title { display:flex; gap:12px; align-items:center; min-width:0; flex:1; }
.file-title strong,.file-title span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.file-title span,.scope-hint,.range-info { font-size:12px; color:var(--ws-text-muted,#64748b); }
.viewer-tools { display:flex; align-items:center; gap:8px; }
.encoding { width:150px; }
.viewer-content { position:relative; display:flex; flex:1; min-height:0; }
.viewer-search { position:absolute; right:12px; left:12px; top:8px; z-index:7; display:flex; align-items:center; flex-wrap:wrap; gap:4px; padding:5px; border:1px solid var(--el-border-color); border-radius:6px; background:var(--el-bg-color); box-shadow:var(--el-box-shadow); }
.viewer-search input { flex:1; width:0; min-width:50px; padding:4px 7px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-bg-color); color:var(--el-text-color-primary); }
.viewer-search select { max-width:110px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-bg-color); color:var(--el-text-color-primary); }
.viewer-search button { min-width:28px; min-height:30px; flex-shrink:0; border:0; background:transparent; color:var(--el-text-color-primary); cursor:pointer; }
.viewer-search button:disabled { opacity:.5; cursor:default; }
.viewer-search :focus-visible { outline:2px solid var(--el-color-primary); outline-offset:1px; }
.viewer-search :deep(.el-checkbox) { margin-right:4px; }
.match-status { min-width:48px; font-size:12px; }
.jump-input { width:65px; }
.filter-status { padding:8px 12px; font-size:12px; background:var(--el-fill-color-light); }
.tail-status { font-size:12px; color:var(--el-color-success); }
.viewer-body { flex:1; min-height:0; overflow:auto; padding:12px 18px; background:#fafafa; color:#24292f; font:13px/1.6 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; }
.is-dark .viewer-body { background:#1e1e1e; color:#d4d4d4; }
.file-content { margin:0; font:inherit; white-space:pre-wrap; overflow-wrap:anywhere; user-select:text; -webkit-user-select:text; }
.file-content :deep(*) { user-select:text; -webkit-user-select:text; }
.file-content :deep(.search-match) { color:#111; background:#ffe082; padding:0; }
.file-content :deep(.search-match.is-current) { background:#ff9800; outline:1px solid #b45309; }
.file-content :deep(.filter-line) { display:inline-block; width:100%; padding-left:64px; position:relative; }
.file-content :deep(.filter-line::before) { content:attr(data-line); position:absolute; left:0; width:50px; text-align:right; color:#888; user-select:none; -webkit-user-select:none; }
.file-content :deep(.line-truncated-note) { color:var(--el-color-warning); user-select:none; -webkit-user-select:none; }
.file-content :deep(.filter-line.is-matched::before) { color:var(--el-color-primary); }
.file-content :deep(.line-gap) { color:#888; user-select:none; -webkit-user-select:none; }
.edge-note { text-align:center; color:#888; padding:8px; user-select:none; }
.viewer-footer { border-top:1px solid var(--ws-border,#e2e8f0); border-bottom:0; }
.viewer-footer .scope-hint { margin-right:auto; }
@media(max-width:800px) { .file-title span,.scope-hint { display:none; } .viewer-header { align-items:flex-start; } .viewer-tools { flex-wrap:wrap; } }
</style>
