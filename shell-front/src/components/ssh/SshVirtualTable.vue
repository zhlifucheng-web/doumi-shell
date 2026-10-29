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
            :class="{
              'is-compact': isCompact,
              'is-dir': row.dir,
              'is-context-selected': contextMenuRow?.name === row.name && contextMenuVisible
            }"
            :style="{ height: rowHeight + 'px' }"
            @dblclick="onRowDblClick(row)"
            @contextmenu.prevent="onRowContextMenu(row, $event)"
          >
            <!-- Name Cell -->
            <div class="v-td col-name" :title="row.name">
              <el-icon class="row-ico" :class="[getFileTypeClass(row), { 'is-dir': row.dir }]">
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
            <div class="v-td col-actions sticky-right" @dblclick.stop>
              <template v-if="row.dir">
                <el-button link type="primary" size="small" @click.stop="$emit('open', row)">
                  打开
                </el-button>
                <el-button link type="primary" size="small" @click.stop="$emit('folder-download', row)">
                  下载
                </el-button>
              </template>
              <template v-else>
                <el-button link type="primary" size="small" @click.stop="$emit('edit-file', row)">
                  {{ row.size > 5 * 1024 * 1024 ? '查看' : '编辑' }}
                </el-button>
                <el-tooltip content="在独立窗口中打开查看 (脱离主会话界面)" placement="top" :show-after="300">
                  <el-button
                    link
                    size="small"
                    class="popout-action-btn"
                    :icon="TopRight"
                    @click.stop="$emit('view-standalone', row)"
                  />
                </el-tooltip>
                <el-button link type="primary" size="small" @click.stop="$emit('download', row)">
                  下载
                </el-button>
              </template>
              <el-button link type="primary" size="small" @click.stop="$emit('copy-to', row)">复制到</el-button>
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

    <!-- Context Menu (Teleported to body for reliable stacking & clipping immunity) -->
    <teleport to="body">
      <div
        v-if="contextMenuVisible"
        ref="contextMenuRef"
        class="ssh-virtual-table-context-menu"
        :style="{ left: contextMenuPosition.x + 'px', top: contextMenuPosition.y + 'px' }"
        @click.stop
        @contextmenu.prevent
      >
        <div class="ctx-menu-header">
          <span class="ctx-menu-filename" :title="contextMenuRow?.name">
            {{ contextMenuRow?.name }}
          </span>
        </div>
        <div class="ctx-menu-divider" />
        <template v-if="contextMenuRow?.dir">
          <div class="ctx-menu-item" @click="handleContextMenuAction('open')">
            <el-icon><FolderOpened /></el-icon>
            <span>进入目录</span>
          </div>
          <div class="ctx-menu-item" @click="handleContextMenuAction('folder-download')">
            <el-icon><Download /></el-icon>
            <span>下载目录 (ZIP)</span>
          </div>
        </template>
        <template v-else>
          <div class="ctx-menu-item is-primary" @click="handleContextMenuAction('edit')">
            <el-icon><Edit /></el-icon>
            <span>{{ contextMenuRow?.size > 5 * 1024 * 1024 ? '查看' : '在线编辑 / 查看' }}</span>
          </div>
          <div class="ctx-menu-item" @click="handleContextMenuAction('view-standalone')">
            <el-icon><TopRight /></el-icon>
            <span>独立窗口查看</span>
          </div>
          <div
            v-if="contextMenuRow?.size > 5 * 1024 * 1024 && contextMenuRow?.size <= 50 * 1024 * 1024"
            class="ctx-menu-item"
            @click="handleContextMenuAction('force-edit')"
          >
            <el-icon><Edit /></el-icon>
            <span>全量在线编辑</span>
          </div>
          <div class="ctx-menu-item" @click="handleContextMenuAction('download')">
            <el-icon><Download /></el-icon>
            <span>下载文件</span>
          </div>
        </template>
        <div class="ctx-menu-divider" />
        <div class="ctx-menu-item" @click="handleContextMenuAction('copy-to')">
          <el-icon><DocumentCopy /></el-icon><span>复制到机器…</span>
        </div>
        <!-- 复制 子菜单 (路径 / 文件名) -->
        <div
          class="ctx-menu-item has-submenu"
          @mouseenter="onCopySubmenuEnter"
          @mouseleave="onCopySubmenuLeave"
          @click.stop="toggleCopySubmenu"
        >
          <div class="ctx-menu-item-left">
            <el-icon><CopyDocument /></el-icon>
            <span>复制</span>
          </div>
          <el-icon class="submenu-arrow"><ArrowRight /></el-icon>

          <!-- 二级菜单 -->
          <div
            v-if="copySubmenuVisible"
            class="ctx-submenu"
            :class="{ 'align-left': copySubmenuAlignLeft }"
            @click.stop
          >
            <div class="ctx-menu-item" @click.stop="copyPath">
              <el-icon><Link /></el-icon>
              <span>路径</span>
            </div>
            <div class="ctx-menu-item" @click.stop="copyFileName">
              <el-icon><DocumentCopy /></el-icon>
              <span>文件名</span>
            </div>
          </div>
        </div>

        <div class="ctx-menu-item" @click="handleContextMenuAction('rename')">
          <el-icon><Document /></el-icon>
          <span>重命名</span>
        </div>
        <div v-if="canChown" class="ctx-menu-item" @click="handleContextMenuAction('chown')">
          <el-icon><User /></el-icon>
          <span>修改所有者</span>
        </div>
        <div class="ctx-menu-item is-danger" @click="handleContextMenuAction('delete')">
          <el-icon><Delete /></el-icon>
          <span>删除</span>
        </div>
      </div>
    </teleport>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowRight,
  CopyDocument,
  Delete,
  Document,
  DocumentCopy,
  Download,
  Edit,
  Folder,
  FolderOpened,
  Link,
  TopRight,
  User
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { isParentPath } from '@/utils/fileTypeHelper'

const props = defineProps({
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  embedded: { type: Boolean, default: false },
  showDetails: { type: Boolean, default: false },
  sortProp: { type: String, default: 'name' },
  sortOrder: { type: String, default: 'ascending' },
  rowHeight: { type: Number, default: 36 },
  emptyDescription: { type: String, default: '当前目录为空' },
  currentPath: { type: String, default: '' },
  canChown: { type: Boolean, default: false }
})

const emit = defineEmits([
  'sort-change',
  'row-dblclick',
  'edit-file',
  'force-edit',
  'view-standalone',
  'download',
  'open',
  'folder-download',
  'copy-to',
  'rename',
  'chown',
  'delete'
])

const containerRef = ref(null)
const headerBarRef = ref(null)
const viewportRef = ref(null)
const scrollTop = ref(0)
const viewportHeight = ref(400)
const buffer = 8

const isCompact = computed(() => props.embedded && !props.showDetails)
const minTableWidth = computed(() => (isCompact.value ? '650px' : '1105px'))

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

// Path-based scroll position restoration
const pathScrollMap = new Map()
let pendingRestoreTop = null

watch(() => props.currentPath, (newPath, oldPath) => {
  if (oldPath && oldPath !== newPath) {
    pathScrollMap.set(oldPath, scrollTop.value)
  }

  if (!newPath) return

  if (oldPath === newPath) {
    // Current directory refreshed: preserve current scroll
    pendingRestoreTop = scrollTop.value
  } else if (oldPath && isParentPath(newPath, oldPath)) {
    // Navigating back up to parent or ancestor: restore previous scroll position
    pendingRestoreTop = pathScrollMap.has(newPath) ? pathScrollMap.get(newPath) : 0
  } else {
    // Navigating into a new or sub-directory: roll back to very top (0)
    pendingRestoreTop = 0
  }

  applyPendingScroll()
}, { immediate: true })

watch(() => props.items, async () => {
  await nextTick()
  applyPendingScroll()
})

function applyPendingScroll() {
  if (pendingRestoreTop === null) return
  const maxScroll = Math.max(0, totalHeight.value - viewportHeight.value)
  const clamped = Math.max(0, Math.min(pendingRestoreTop, maxScroll))
  scrollTop.value = clamped
  if (viewportRef.value) {
    viewportRef.value.scrollTop = clamped
  }
  if (props.items && props.items.length > 0) {
    pendingRestoreTop = null
  }
}

defineExpose({
  getScrollTop: () => scrollTop.value,
  scrollToTop: () => {
    scrollTop.value = 0
    if (viewportRef.value) viewportRef.value.scrollTop = 0
  },
  resetPathScrollHistory: () => {
    pathScrollMap.clear()
    pendingRestoreTop = 0
  }
})

let rafId = null
function onScroll() {
  if (contextMenuVisible.value) {
    closeContextMenu()
  }
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

const contextMenuRef = ref(null)
const contextMenuVisible = ref(false)
const contextMenuPosition = ref({ x: 0, y: 0 })
const contextMenuRow = ref(null)
const copySubmenuVisible = ref(false)
const copySubmenuAlignLeft = ref(false)
let copySubmenuTimer = null

function onRowContextMenu(row, event) {
  contextMenuRow.value = row
  copySubmenuVisible.value = false
  const menuWidth = 175
  const menuHeight = 220
  const x = Math.min(event.clientX, window.innerWidth - menuWidth - 10)
  const y = Math.min(event.clientY, window.innerHeight - menuHeight - 10)
  contextMenuPosition.value = { x, y }
  contextMenuVisible.value = true
}

function closeContextMenu() {
  clearTimeout(copySubmenuTimer)
  copySubmenuVisible.value = false
  contextMenuVisible.value = false
}

function onCopySubmenuEnter(e) {
  clearTimeout(copySubmenuTimer)
  const rect = e?.currentTarget?.getBoundingClientRect?.()
  if (rect) {
    copySubmenuAlignLeft.value = (window.innerWidth - rect.right) < 140
  }
  copySubmenuVisible.value = true
}

function onCopySubmenuLeave() {
  copySubmenuTimer = setTimeout(() => {
    copySubmenuVisible.value = false
  }, 120)
}

function toggleCopySubmenu(e) {
  if (copySubmenuVisible.value) {
    copySubmenuVisible.value = false
  } else {
    onCopySubmenuEnter(e)
  }
}

function fullPathFor(row) {
  if (!row) return ''
  const base = props.currentPath || ''
  if (!base) return row.name || ''
  return base.endsWith('/') ? `${base}${row.name}` : `${base}/${row.name}`
}

async function copyToClipboard(text, successMsg) {
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.focus()
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success(successMsg)
  } catch (err) {
    ElMessage.error('复制失败：' + (err?.message || '未知错误'))
  }
}

function copyPath() {
  const row = contextMenuRow.value
  closeContextMenu()
  if (!row) return
  const fullPath = fullPathFor(row)
  void copyToClipboard(fullPath, '已复制路径到剪贴板')
}

function copyFileName() {
  const row = contextMenuRow.value
  closeContextMenu()
  if (!row) return
  void copyToClipboard(row.name || '', '已复制文件名到剪贴板')
}

function handleContextMenuAction(action) {
  const row = contextMenuRow.value
  closeContextMenu()
  if (!row) return
  switch (action) {
    case 'open':
      emit('open', row)
      break
    case 'edit':
      emit('edit-file', row)
      break
    case 'view-standalone':
      emit('view-standalone', row)
      break
    case 'force-edit':
      emit('force-edit', row)
      break
    case 'download':
      emit('download', row)
      break
    case 'folder-download':
      emit('folder-download', row)
      break
    case 'rename':
      emit('rename', row)
      break
    case 'copy-to':
      emit('copy-to', row)
      break
    case 'chown':
      emit('chown', row)
      break
    case 'delete':
      emit('delete', row)
      break
  }
}

function onGlobalPointerDown(e) {
  if (contextMenuRef.value && !contextMenuRef.value.contains(e.target)) {
    closeContextMenu()
  }
}

function onGlobalKeyDown(e) {
  if (e.key === 'Escape') {
    closeContextMenu()
  }
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

function getFileTypeClass(row) {
  if (!row) return ''
  if (row.dir) return 'is-dir'
  const name = (row.name || '').toLowerCase()
  if (name.endsWith('.sh') || name.endsWith('.bash')) return 'is-sh'
  if (name.endsWith('.js') || name.endsWith('.ts') || name.endsWith('.vue')) return 'is-code'
  if (name.endsWith('.json') || name.endsWith('.yaml') || name.endsWith('.yml') || name.endsWith('.conf') || name.endsWith('.ini')) return 'is-config'
  if (name.endsWith('.tar.gz') || name.endsWith('.zip') || name.endsWith('.tar') || name.endsWith('.gz') || name.endsWith('.tgz')) return 'is-archive'
  if (name.endsWith('.log') || name.endsWith('.out')) return 'is-log'
  return ''
}

let resizeObserver = null
onMounted(() => {
  window.addEventListener('pointerdown', onGlobalPointerDown)
  window.addEventListener('keydown', onGlobalKeyDown)
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
  window.removeEventListener('pointerdown', onGlobalPointerDown)
  window.removeEventListener('keydown', onGlobalKeyDown)
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
  grid-template-columns: minmax(180px, 1fr) 90px 80px 80px 105px 155px 155px 260px;
  align-items: center;
  height: 38px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular, #606266);
  user-select: none;
}

.virtual-header.is-compact {
  grid-template-columns: minmax(160px, 1fr) 85px 155px 250px;
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
  grid-template-columns: minmax(180px, 1fr) 90px 80px 80px 105px 155px 155px 260px;
  align-items: center;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f2f5);
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  background: var(--el-bg-color, #ffffff);
  transition: background-color 0.1s ease;
  user-select: none;
}

.virtual-row.is-compact {
  grid-template-columns: minmax(160px, 1fr) 85px 155px 250px;
}

.virtual-row.is-context-selected {
  background-color: var(--el-color-primary-light-9, #ecf5ff) !important;
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

.virtual-row .col-actions.sticky-right {
  opacity: 0.65;
  transition: opacity 0.15s ease, background 0.15s ease;
}

.virtual-header .col-actions.sticky-right {
  background: var(--el-fill-color-light, #f5f7fa);
  z-index: 11;
}

.virtual-row:hover .col-actions.sticky-right,
.virtual-row.is-context-selected .col-actions.sticky-right {
  background: var(--el-fill-color-light, #f5f7fa);
  opacity: 1;
}

.row-ico.is-sh {
  color: #10b981;
}
.row-ico.is-code {
  color: #3b82f6;
}
.row-ico.is-config {
  color: #06b6d4;
}
.row-ico.is-archive {
  color: #a855f7;
}
.row-ico.is-log {
  color: #f59e0b;
}

.col-actions :deep(.el-button) {
  margin-left: 0;
  padding: 2px 4px;
}

.col-actions :deep(.popout-action-btn) {
  padding: 2px 2px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}
.col-actions :deep(.popout-action-btn:hover) {
  color: var(--el-color-primary, #6366f1);
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

/* ========================================================
   Context Menu (Global Teleport Target)
   ======================================================== */
:global(.ssh-virtual-table-context-menu) {
  position: fixed;
  z-index: 9999;
  min-width: 175px;
  background: var(--el-bg-color-overlay, #ffffff);
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 8px;
  box-shadow: var(--el-box-shadow-light, 0 4px 16px rgba(0, 0, 0, 0.12));
  padding: 4px 0;
  font-size: 13px;
  user-select: none;
  animation: ctx-menu-pop 0.12s cubic-bezier(0, 0, 0.2, 1);
}

@keyframes ctx-menu-pop {
  from {
    opacity: 0;
    transform: scale(0.96);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

:global(.ssh-virtual-table-context-menu .ctx-menu-header) {
  padding: 6px 12px 4px;
}

:global(.ssh-virtual-table-context-menu .ctx-menu-filename) {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 170px;
}

:global(.ssh-virtual-table-context-menu .ctx-menu-divider) {
  height: 1px;
  margin: 4px 0;
  background: var(--el-border-color-lighter, #ebeef5);
}

:global(.ssh-virtual-table-context-menu .ctx-menu-item) {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  color: var(--el-text-color-primary, #303133);
  cursor: pointer;
  transition: background-color 0.12s ease, color 0.12s ease;
}

:global(.ssh-virtual-table-context-menu .ctx-menu-item:hover) {
  background-color: var(--el-fill-color-light, #f5f7fa);
}

:global(.ssh-virtual-table-context-menu .ctx-menu-item.is-primary) {
  color: var(--el-color-primary, #409eff);
  font-weight: 500;
}

:global(.ssh-virtual-table-context-menu .ctx-menu-item.is-danger) {
  color: var(--el-color-danger, #f56c6c);
}

:global(.ssh-virtual-table-context-menu .ctx-menu-item.is-danger:hover) {
  background-color: var(--el-color-danger-light-9, #fef0f0);
}

:global(.ssh-virtual-table-context-menu .ctx-menu-item.has-submenu) {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

:global(.ssh-virtual-table-context-menu .ctx-menu-item-left) {
  display: flex;
  align-items: center;
  gap: 8px;
}

:global(.ssh-virtual-table-context-menu .submenu-arrow) {
  font-size: 12px;
  margin-left: 12px;
  color: var(--el-text-color-secondary);
}

:global(.ssh-virtual-table-context-menu .ctx-submenu) {
  position: absolute;
  top: -4px;
  left: 100%;
  margin-left: 2px;
  min-width: 130px;
  background: var(--el-bg-color-overlay, #ffffff);
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 8px;
  box-shadow: var(--el-box-shadow-light, 0 4px 16px rgba(0, 0, 0, 0.12));
  padding: 4px 0;
  font-size: 13px;
  animation: ctx-menu-pop 0.12s cubic-bezier(0, 0, 0.2, 1);
  z-index: 10000;
}

:global(.ssh-virtual-table-context-menu .ctx-submenu.align-left) {
  left: auto;
  right: 100%;
  margin-left: 0;
  margin-right: 2px;
}

:global(html.dark .ssh-virtual-table-context-menu),
:global(:root[data-theme='dark'] .ssh-virtual-table-context-menu) {
  background: var(--ws-bg-card, #141b2d);
  border-color: var(--ws-border, rgba(255, 255, 255, 0.12));
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.65);
}

:global(html.dark .ssh-virtual-table-context-menu .ctx-submenu),
:global(:root[data-theme='dark'] .ssh-virtual-table-context-menu .ctx-submenu) {
  background: var(--ws-bg-card, #141b2d);
  border-color: var(--ws-border, rgba(255, 255, 255, 0.12));
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.65);
}

:global(html.dark .ssh-virtual-table-context-menu .ctx-menu-divider),
:global(:root[data-theme='dark'] .ssh-virtual-table-context-menu .ctx-menu-divider) {
  background: var(--ws-border, rgba(255, 255, 255, 0.08));
}

:global(html.dark .ssh-virtual-table-context-menu .ctx-menu-item),
:global(:root[data-theme='dark'] .ssh-virtual-table-context-menu .ctx-menu-item) {
  color: #cbd5e1;
}

:global(html.dark .ssh-virtual-table-context-menu .ctx-menu-item:hover),
:global(:root[data-theme='dark'] .ssh-virtual-table-context-menu .ctx-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.06);
}

:global(html.dark .ssh-virtual-table-context-menu .ctx-menu-item.is-danger:hover),
:global(:root[data-theme='dark'] .ssh-virtual-table-context-menu .ctx-menu-item.is-danger:hover) {
  background-color: rgba(245, 108, 108, 0.18);
}
</style>
