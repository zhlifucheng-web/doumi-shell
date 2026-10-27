<template>
  <div class="terminal-file-layout">
    <slot name="monitor" />
    <div
      ref="workspace"
      class="right-workspace"
      :class="{ collapsed }"
      :style="{
        gridTemplateRows: collapsed
          ? 'minmax(0, 1fr) 32px'
          : `minmax(0, ${ratio}fr) 6px minmax(0, ${100 - ratio}fr)`
      }"
    >
      <!-- 终端工作区 (Edge-to-Edge) -->
      <section class="terminal-slot" aria-label="终端">
        <div class="terminal-content">
          <slot name="terminal" />
        </div>
      </section>

      <!-- 拖拽分界条 (Splitter) -->
      <div
        v-if="!collapsed"
        class="splitter"
        role="separator"
        tabindex="0"
        aria-label="调整终端与文件区域高度"
        aria-orientation="horizontal"
        :aria-valuenow="ratio"
        :aria-valuemin="30"
        :aria-valuemax="75"
        :aria-valuetext="`终端 ${ratio}%，文件 ${100 - ratio}%`"
        title="拖动调整高度，双击恢复默认比例"
        @pointerdown="startDrag"
        @pointermove="drag"
        @pointerup="endDrag"
        @pointercancel="endDrag"
        @lostpointercapture="endDrag"
        @dblclick="setRatio(60)"
        @keydown.up.prevent="setRatio(ratio - 5)"
        @keydown.down.prevent="setRatio(ratio + 5)"
        @keydown.home.prevent="setRatio(30)"
        @keydown.end.prevent="setRatio(75)"
      />

      <!-- SFTP 文件面板 (Integrated SFTP) -->
      <section class="files-slot" :class="{ 'files-collapsed': collapsed }" aria-label="文件管理">
        <div class="files-heading">
          <div class="heading-left">
            <span class="files-title">
              <svg class="files-svg" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"></path>
              </svg>
              文件传输 (SFTP)
            </span>
          </div>
          <div class="layout-actions">
            <button v-if="!collapsed" type="button" class="layout-btn" title="恢复默认比例 (60% / 40%)" @click="setRatio(60)">
              6:4 比例
            </button>
            <button v-if="!collapsed" type="button" class="layout-btn" @click="setRatio(ratio > 40 ? 35 : 60)">
              {{ ratio > 40 ? '放大文件' : '放大终端' }}
            </button>
            <button type="button" class="layout-btn" :aria-expanded="!collapsed" @click="collapsed = !collapsed">
              {{ collapsed ? '展开文件' : '收起' }}
            </button>
          </div>
        </div>
        <div v-show="!collapsed" class="files-content">
          <slot name="files" />
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useLayoutPreference } from '@/composables/useLayoutPreference'
import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'

const workspace = ref(null)
const { fileLocation } = storeToRefs(useWorkspaceStore())
const ratio = useLayoutPreference('terminalPercent')
const collapsed = useLayoutPreference('filesCollapsed')

watch(fileLocation, (location) => {
  if (location) collapsed.value = false
})
function setRatio(value) {
  ratio.value = Math.round(Math.max(30, Math.min(75, value)))
}
let dragging = false
function startDrag(event) {
  if (event.button !== 0) return
  dragging = true
  event.currentTarget.setPointerCapture(event.pointerId)
  event.preventDefault()
}
function drag(event) {
  if (!dragging) return
  const bounds = workspace.value?.getBoundingClientRect()
  if (bounds?.height > 10) setRatio(((event.clientY - bounds.top - 3) / (bounds.height - 6)) * 100)
}
function endDrag() {
  dragging = false
}
</script>

<style scoped>
.terminal-file-layout {
  display: flex;
  gap: 0;
  padding: 0;
  margin: 0;
  box-sizing: border-box;
  height: 100%;
  width: 100%;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  background-color: var(--ws-bg-root);
}

.right-workspace {
  display: grid;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  height: 100%;
}

.terminal-slot {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  background: var(--ws-bg-terminal);
}

.terminal-content {
  display: flex;
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.terminal-content :deep(.ssh-terminal-wrap) {
  flex: 1;
  min-width: 0;
}

/* 现代 Splitter 分隔条 */
.splitter {
  height: 6px;
  background: var(--ws-bg-root);
  border-top: 1px solid var(--ws-border);
  border-bottom: 1px solid var(--ws-border);
  position: relative;
  cursor: row-resize;
  touch-action: none;
  z-index: 10;
  transition: background var(--ws-transition-fast);
}
.splitter::after {
  content: '';
  position: absolute;
  top: 1px;
  left: calc(50% - 20px);
  width: 40px;
  height: 2px;
  background: var(--ws-border);
  border-radius: 2px;
  transition: all var(--ws-transition-fast);
}
.splitter:hover,
.splitter:focus-visible {
  background: var(--ws-accent-bg);
}
.splitter:hover::after,
.splitter:focus-visible::after {
  background: var(--ws-accent);
  width: 56px;
  left: calc(50% - 28px);
}

/* SFTP 文件区域 */
.files-slot {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  background: var(--ws-bg-card);
  border-top: 1px solid var(--ws-border);
}
.files-slot.files-collapsed {
  height: 32px;
}

.files-heading {
  height: 32px;
  min-height: 32px;
  padding: 0 12px;
  background: var(--ws-bg-hover);
  border-bottom: 1px solid var(--ws-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}

.files-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--ws-text-regular);
}
.files-svg {
  width: 14px;
  height: 14px;
  color: var(--ws-accent);
}

.layout-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.layout-btn {
  padding: 2px 6px;
  border-radius: var(--ws-radius-xs);
  background: transparent;
  border: 1px solid transparent;
  color: var(--ws-text-muted);
  font-size: 11px;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
}
.layout-btn:hover {
  background: var(--ws-bg-card);
  border-color: var(--ws-border);
  color: var(--ws-text-primary);
}

.files-content {
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
