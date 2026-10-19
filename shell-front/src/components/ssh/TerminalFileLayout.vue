<template>
  <div class="terminal-file-layout">
    <slot name="monitor" />
    <div ref="workspace" class="right-workspace" :class="{ collapsed }"
      :style="{ gridTemplateRows: collapsed ? 'minmax(0, 1fr) 36px' : `minmax(0, ${ratio}fr) 12px minmax(0, ${100-ratio}fr)` }">
      <section class="terminal-slot" aria-label="终端"><div class="terminal-heading"><strong>终端</strong><span class="pane-caption">SSH</span></div><div class="terminal-content"><slot name="terminal" /></div></section>
      <div v-if="!collapsed" class="splitter" role="separator" tabindex="0"
        aria-label="调整终端与文件区域高度" aria-orientation="horizontal" :aria-valuenow="ratio" :aria-valuemin="30" :aria-valuemax="75"
        :aria-valuetext="`终端 ${ratio}%，文件 ${100-ratio}%`" title="拖动调整高度，双击恢复默认比例"
        @pointerdown="startDrag" @pointermove="drag" @pointerup="endDrag" @pointercancel="endDrag" @lostpointercapture="endDrag"
        @dblclick="setRatio(60)" @keydown.up.prevent="setRatio(ratio-5)" @keydown.down.prevent="setRatio(ratio+5)"
        @keydown.home.prevent="setRatio(30)" @keydown.end.prevent="setRatio(75)" />
      <section class="files-slot" :class="{ 'files-collapsed': collapsed }" aria-label="文件管理">
        <div class="files-heading">
          <strong>文件管理</strong>
          <div class="layout-actions">
            <el-button v-if="!collapsed" text size="small" title="恢复终端 60%、文件 40%" @click="setRatio(60)">默认比例</el-button>
            <el-button v-if="!collapsed" text size="small" @click="setRatio(ratio > 40 ? 35 : 60)">{{ ratio > 40 ? '放大文件' : '放大终端' }}</el-button>
            <el-button text size="small" :aria-expanded="!collapsed" @click="collapsed=!collapsed">{{ collapsed ? '展开文件' : '收起文件' }}</el-button>
          </div>
        </div>
        <div v-show="!collapsed" class="files-content"><slot name="files" /></div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

import { fileLocation } from '@/composables/useWorkspaceContext'
const workspace = ref(null)
const ratio = ref(60), collapsed = ref(false)
try {
  const saved = Number(localStorage.getItem('doumi-terminal-height-percent'))
  if (saved >= 30 && saved <= 75) ratio.value = saved
  collapsed.value = localStorage.getItem('doumi-terminal-files-collapsed') === 'true'
} catch { /* Use defaults when preferences are unavailable. */ }
watch(fileLocation, location => { if (location) collapsed.value = false })
function setRatio(value) { ratio.value = Math.round(Math.max(30, Math.min(75, value))) }
watch(ratio, value => { try { localStorage.setItem('doumi-terminal-height-percent', String(value)) } catch { /* Preference only. */ } })
watch(collapsed, value => { try { localStorage.setItem('doumi-terminal-files-collapsed', String(value)) } catch { /* Preference only. */ } })
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
  if (bounds?.height > 12) setRatio((event.clientY - bounds.top - 6) / (bounds.height - 12) * 100)
}
function endDrag() { dragging = false }
</script>

<style scoped>
.terminal-file-layout { display:flex; gap:12px; padding:1px; box-sizing:border-box; height:100%; min-height:0; min-width:0; overflow:hidden; }
.terminal-file-layout :deep(.ssh-monitor) { border:1px solid var(--el-border-color); border-radius:8px; box-shadow:0 1px 4px #0000000a; }
.terminal-file-layout :deep(.ssh-monitor .toolbar) { background:var(--el-fill-color-light); border-top:3px solid var(--el-color-primary); border-radius:7px 7px 0 0; }
.right-workspace { display:grid; flex:1; min-width:0; min-height:0; overflow:hidden; }
.terminal-slot, .files-slot { display:flex; flex-direction:column; min-height:0; min-width:0; overflow:hidden; border:1px solid var(--el-border-color); border-radius:8px; box-shadow:0 1px 4px #0000000a; }
.terminal-content { display:flex; flex:1; min-height:0; min-width:0; overflow:hidden; }
.terminal-content :deep(.ssh-terminal-wrap) { flex:1; min-width:0; }
.terminal-heading, .files-heading { display:flex; align-items:center; justify-content:space-between; gap:8px; flex-shrink:0; height:34px; box-sizing:border-box; padding:0 10px; border-bottom:1px solid var(--el-border-color); background:var(--el-fill-color-light); font-size:13px; }
.terminal-heading { border-top:3px solid var(--el-color-primary); }
.files-heading { border-top:3px solid var(--el-color-success); }
.pane-caption { font-size:11px; color:var(--el-text-color-secondary); }
.splitter { position:relative; cursor:row-resize; touch-action:none; outline-offset:-2px; border-radius:4px; }
.splitter::after { content:''; position:absolute; top:5px; left:calc(50% - 28px); width:56px; height:3px; background:var(--el-border-color-darker); border-radius:3px; }
.splitter:hover, .splitter:focus-visible { background:var(--el-color-primary-light-8); }
.splitter:hover::after, .splitter:focus-visible::after { background:var(--el-color-primary); }
.layout-actions { display:flex; align-items:center; }
.layout-actions :deep(.el-button + .el-button) { margin-left:4px; }
.files-content { flex:1; min-height:0; min-width:0; overflow:hidden; padding:6px 8px 0; }
</style>
