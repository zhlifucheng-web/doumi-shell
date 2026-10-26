<template>
  <div
    ref="containerRef"
    class="ssh-virtual-table-container"
    :class="{ 'is-compact': isCompact }"
    v-loading="loading"
  >
    <!-- Fixed Header Bar (Completely separate from body scroll viewport, impossible to overlap) -->
    <div ref="headerBarRef" class="virtual-header-bar">
      <div class="virtual-header" :class="{ 'is-compact': isCompact }" :style="{ minWidth: minTableWidth }">
        <!-- Column: Name -->
        <div
          class="v-th col-name is-sortable"
          :class="{ 'is-sorted': sortProp === 'name' }"
          @click="handleHeaderClick('name')"
          title="点击按文件名排序"
        >
          <span class="th-label">名称</span>
          <span class="sort-carets">
            <i class="caret-asc" :class="{ 'is-active': sortProp === 'name' && sortOrder === 'ascending' }">▲</i>
            <i class="caret-desc" :class="{ 'is-active': sortProp === 'name' && sortOrder === 'descending' }">▼</i>
          </span>
        </div>

        <!-- Column: Size -->
        <div
          class="v-th col-size is-sortable"
          :class="{ 'is-sorted': sortProp === 'size' }"
          @click="handleHeaderClick('size')"
          title="点击按大小排序"
        >
          <span class="th-label">大小</span>
          <span class="sort-carets">
            <i class="caret-asc" :class="{ 'is-active': sortProp === 'size' && sortOrder === 'ascending' }">▲</i>
            <i class="caret-desc" :class="{ 'is-active': sortProp === 'size' && sortOrder === 'descending' }">▼</i>
          </span>
        </div>

        <!-- Detail Columns -->
        <template v-if="!isCompact">
          <div class="v-th col-owner">属主</div>
          <div class="v-th col-group">属组</div>
          <div class="v-th col-perm">权限</div>

          <!-- Column: Create Time -->
          <div
            class="v-th col-time is-sortable"
            :class="{ 'is-sorted': sortProp === 'createTime' }"
            @click="handleHeaderClick('createTime')"
            title="点击按新增时间排序"
          >
            <span class="th-label">新增时间</span>
            <span class="sort-carets">
              <i class="caret-asc" :class="{ 'is-active': sortProp === 'createTime' && sortOrder === 'ascending' }">▲</i>
              <i class="caret-desc" :class="{ 'is-active': sortProp === 'createTime' && sortOrder === 'descending' }">▼</i>
            </span>
          </div>
        </template>

        <!-- Column: Modify Time (Always visible) -->
        <div
          class="v-th col-time is-sortable"
          :class="{ 'is-sorted': sortProp === 'mtime' }"
          @click="handleHeaderClick('mtime')"
          title="点击按修改时间排序"
        >
          <span class="th-label">修改时间</span>
          <span class="sort-carets">
            <i class="caret-asc" :class="{ 'is-active': sortProp === 'mtime' && sortOrder === 'ascending' }">▲</i>
            <i class="caret-desc" :class="{ 'is-active': sortProp === 'mtime' && sortOrder === 'descending' }">▼</i>
          </span>
        </div>

        <!-- Column: Actions (Sticky right) -->
        <div class="v-th col-actions sticky-right">操作</div>
      </div>
    </div>

    <!-- Empty State -->
    <div v-if="!loading && totalCount === 0" class="virtual-empty">
      <el-empty :description="emptyDescription" :image-size="70" />
    </div>

    <!-- Scroll Viewport for Table Body Rows -->
    <div
      v-else
      ref="viewportRef"
      class="virtual-viewport"
      @scroll.passive="onScroll"
    >
      <div class="virtual-body" :style="{ minWidth: minTableWidth, height: totalHeight + 'px' }">
        <div
          class="virtual-render-slice"
          :style="{ transform: `translateY(${topPadding}px)` }"
        >
          <div
            v-for="row in visibleItems"
            :key="row.name"
            class="virtual-row"
            :class="{ 'is-compact': isCompact, 'is-dir': row.dir }"
            :style="{ height: rowHeight + 'px' }"
            @dblclick="onRowDblClick(row)"
          >
            <!-- Name Cell -->
            <div class="v-td col-name" :title="row.name">
              <el-icon class="row-ico" :class="{ 'is-dir': row.dir }">
                <Folder v-if="row.dir" />
                <Document v-else />
              </el-icon>
              <span class="file-name-text" :class="{ 'is-dir': row.dir }">
                {{ row.name }}
              </span>
            </div>

            <!-- Size Cell -->
            <div class="v-td col-size">
              {{ row.dir ? '—' : formatSize(row.size) }}
            </div>

            <!-- Detailed Cells -->
            <template v-if="!isCompact">
              <div class="v-td col-owner" :title="row.owner || '—'">
                {{ row.owner || '—' }}
              </div>
              <div class="v-td col-group" :title="row.group || '—'">
                {{ row.group || '—' }}
              </div>
              <div class="v-td col-perm" :title="row.permissions || '—'">
                <span class="perm-cell">{{ row.permissions || '—' }}</span>
              </div>
              <div class="v-td col-time" :title="formatTime(row.createTime || row.atime || row.mtime)">
                {{ formatTime(row.createTime || row.atime || row.mtime) }}
              </div>
            </template>

            <!-- Modify Time Cell (Always visible) -->
            <div class="v-td col-time" :title="formatTime(row.mtime)">
              {{ formatTime(row.mtime) }}
            </div>

            <!-- Actions Cell (Sticky right) -->
            <div class="v-td col-actions sticky-right">
              <template v-if="row.dir">
                <el-button link type="primary" size="small" @click.stop="$emit('open', row)">
                  打开
                </el-button>
                <el-button link type="primary" size="small" @click.stop="$emit('folder-download', row)">
                  下载
                </el-button>
              </template>
              <template v-else>
                <el-button link type="primary" size="small" @click.stop="$emit('download', row)">
                  下载
                </el-button>
              </template>
              <el-button link size="small" @click.stop="$emit('rename', row)">
                重命名
              </el-button>
              <el-button link type="danger" size="small" @click.stop="$emit('delete', row)">
                删除
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Document, Folder } from '@element-plus/icons-vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  embedded: { type: Boolean, default: false },
  showDetails: { type: Boolean, default: false },
  sortProp: { type: String, default: 'name' },
  sortOrder: { type: String, default: 'ascending' },
  rowHeight: { type: Number, default: 36 },
  emptyDescription: { type: String, default: '当前目录为空' }
})

const emit = defineEmits([
  'sort-change',
  'row-dblclick',
  'download',
  'open',
  'folder-download',
  'rename',
  'delete'
])

const containerRef = ref(null)
const headerBarRef = ref(null)
const viewportRef = ref(null)
const scrollTop = ref(0)
const viewportHeight = ref(400)
const buffer = 8

const isCompact = computed(() => props.embedded && !props.showDetails)
const minTableWidth = computed(() => (isCompact.value ? '590px' : '1020px'))

const totalCount = computed(() => props.items?.length || 0)
const totalHeight = computed(() => totalCount.value * props.rowHeight)

const visibleCount = computed(() => {
  return Math.ceil(viewportHeight.value / props.rowHeight)
})

const startIndex = computed(() => {
  return Math.max(0, Math.floor(scrollTop.value / props.rowHeight) - buffer)
})

const endIndex = computed(() => {
  return Math.min(totalCount.value, startIndex.value + visibleCount.value + 2 * buffer)
})

const visibleItems = computed(() => {
  if (totalCount.value === 0) return []
  return props.items.slice(startIndex.value, endIndex.value)
})

const topPadding = computed(() => startIndex.value * props.rowHeight)

// Reset scroll on folder change
watch(() => props.items, () => {
  if (scrollTop.value > totalHeight.value) {
    if (viewportRef.value) viewportRef.value.scrollTop = 0
    scrollTop.value = 0
  }
})

let rafId = null
function onScroll() {
  if (viewportRef.value && headerBarRef.value) {
    // Synchronously sync horizontal scroll to header bar
    headerBarRef.value.scrollLeft = viewportRef.value.scrollLeft
  }
  if (rafId) return
  rafId = requestAnimationFrame(() => {
    if (viewportRef.value) {
      scrollTop.value = viewportRef.value.scrollTop
    }
    rafId = null
  })
}

function handleHeaderClick(prop) {
  if (!['name', 'size', 'createTime', 'mtime'].includes(prop)) return
  let nextOrder = 'ascending'
  if (props.sortProp === prop) {
    nextOrder = props.sortOrder === 'ascending' ? 'descending' : 'ascending'
  } else if (prop === 'mtime' || prop === 'createTime' || prop === 'size') {
    nextOrder = 'descending'
  }
  emit('sort-change', { prop, order: nextOrder })
}

function onRowDblClick(row) {
  emit('row-dblclick', row)
}

function formatSize(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  if (v < 1024 * 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)} MB`
  return `${(v / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function formatTime(sec) {
  const s = Number(sec) || 0
  if (s <= 0) return '—'
  const d = new Date(s * 1000)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

let resizeObserver = null
onMounted(() => {
  if (viewportRef.value) {
    viewportHeight.value = viewportRef.value.clientHeight || 400
    resizeObserver = new ResizeObserver(entries => {
      for (const entry of entries) {
        if (entry.contentRect.height > 0) {
          viewportHeight.value = entry.contentRect.height
        }
      }
    })
    resizeObserver.observe(viewportRef.value)
  }
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = null
  }
})
</script>

<style scoped>
.ssh-virtual-table-container {
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
  background: var(--el-bg-color, #ffffff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: var(--ws-radius-md, 6px);
  overflow: hidden;
}

/* Header bar is completely separate and fixed at top of container */
.virtual-header-bar {
  flex-shrink: 0;
  width: 100%;
  overflow-x: hidden;
  overflow-y: hidden;
  background: var(--el-fill-color-light, #f5f7fa);
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  z-index: 10;
}

.virtual-header {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 90px 80px 80px 105px 155px 155px 220px;
  align-items: center;
  height: 38px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular, #606266);
  user-select: none;
}

.virtual-header.is-compact {
  grid-template-columns: minmax(160px, 1fr) 85px 155px 190px;
}

.v-th {
  padding: 0 10px;
  display: flex;
  align-items: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.v-th.is-sortable {
  cursor: pointer;
  transition: color 0.15s ease;
}

.v-th.is-sortable:hover {
  color: var(--el-color-primary, #409eff);
}

.v-th.is-sorted {
  color: var(--el-color-primary, #409eff);
}

.th-label {
  margin-right: 4px;
}

.sort-carets {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  line-height: 7px;
  font-size: 9px;
  color: var(--el-text-color-placeholder, #c0c4cc);
}

.caret-asc, .caret-desc {
  font-style: normal;
  display: inline-block;
  transform: scale(0.85);
}

.caret-asc.is-active, .caret-desc.is-active {
  color: var(--el-color-primary, #409eff);
  font-weight: bold;
}

/* Scroll viewport for table rows */
.virtual-viewport {
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  overflow-x: auto;
  overflow-y: auto;
  position: relative;
  background: var(--el-bg-color, #ffffff);
}

.virtual-body {
  position: relative;
  width: 100%;
}

.virtual-render-slice {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
}

.virtual-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 90px 80px 80px 105px 155px 155px 220px;
  align-items: center;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f2f5);
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  background: var(--el-bg-color, #ffffff);
  transition: background-color 0.1s ease;
  user-select: none;
}

.virtual-row.is-compact {
  grid-template-columns: minmax(160px, 1fr) 85px 155px 190px;
}

.virtual-row:hover {
  background-color: var(--el-fill-color-light, #f5f7fa);
}

.v-td {
  padding: 0 10px;
  display: flex;
  align-items: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-name {
  min-width: 0;
}

.file-name-text {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-text.is-dir {
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}

.row-ico {
  vertical-align: middle;
  margin-right: 6px;
  flex-shrink: 0;
  font-size: 15px;
}

.row-ico.is-dir {
  color: var(--el-color-primary, #409eff);
}

.col-size, .col-time {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}

.perm-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
}

.col-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.col-actions.sticky-right {
  position: sticky;
  right: 0;
  background: var(--el-bg-color, #ffffff);
  z-index: 2;
  box-shadow: -3px 0 6px -2px rgba(0, 0, 0, 0.08);
}

.virtual-header .col-actions.sticky-right {
  background: var(--el-fill-color-light, #f5f7fa);
  z-index: 11;
}

.virtual-row:hover .col-actions.sticky-right {
  background: var(--el-fill-color-light, #f5f7fa);
}

.col-actions :deep(.el-button) {
  margin-left: 0;
  padding: 2px 4px;
}

.virtual-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
}

/* ========================================================
   Dark Theme Overrides (High-Fidelity)
   ======================================================== */
html.dark .ssh-virtual-table-container,
:root[data-theme='dark'] .ssh-virtual-table-container {
  background: var(--ws-bg-root, #070a12);
  border-color: var(--ws-border, rgba(255, 255, 255, 0.08));
}

html.dark .virtual-header-bar,
:root[data-theme='dark'] .virtual-header-bar {
  background: var(--ws-bg-card, #0f1523);
  border-bottom-color: var(--ws-border, rgba(255, 255, 255, 0.08));
}

html.dark .virtual-header,
:root[data-theme='dark'] .virtual-header {
  color: #cbd5e1;
}

html.dark .virtual-header .col-actions.sticky-right,
:root[data-theme='dark'] .virtual-header .col-actions.sticky-right {
  background: var(--ws-bg-card, #0f1523);
}

html.dark .virtual-viewport,
:root[data-theme='dark'] .virtual-viewport {
  background: var(--ws-bg-root, #070a12);
}

html.dark .virtual-row,
:root[data-theme='dark'] .virtual-row {
  background: var(--ws-bg-root, #070a12);
  border-bottom-color: var(--ws-border, rgba(255, 255, 255, 0.06));
  color: #94a3b8;
}

html.dark .virtual-row:hover,
:root[data-theme='dark'] .virtual-row:hover {
  background: rgba(255, 255, 255, 0.04);
}

html.dark .virtual-row .col-actions.sticky-right,
:root[data-theme='dark'] .virtual-row .col-actions.sticky-right {
  background: var(--ws-bg-root, #070a12);
  box-shadow: -4px 0 8px -2px rgba(0, 0, 0, 0.5);
}

html.dark .virtual-row:hover .col-actions.sticky-right,
:root[data-theme='dark'] .virtual-row:hover .col-actions.sticky-right {
  background: #111726;
}

html.dark .file-name-text.is-dir,
:root[data-theme='dark'] .file-name-text.is-dir {
  color: #e2e8f0;
}
</style>
