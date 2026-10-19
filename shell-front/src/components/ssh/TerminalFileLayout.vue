<template>
  <div class="terminal-file-layout" :class="{ 'files-only': !terminalActive }">
    <slot name="monitor" />
    <div ref="workspace" class="right-workspace" :class="{ collapsed }"
      :style="terminalActive ? { gridTemplateRows: collapsed ? 'minmax(0, 1fr) 32px' : `minmax(0, ${ratio}fr) 10px minmax(0, ${100-ratio}fr)` } : {}">
      <div v-show="terminalActive" class="terminal-slot"><slot name="terminal" /></div>
      <div v-if="terminalActive && !collapsed" class="splitter" role="separator" tabindex="0"
        aria-label="调整终端与文件区域高度" aria-orientation="horizontal" :aria-valuenow="ratio" :aria-valuemin="30" :aria-valuemax="75"
        :aria-valuetext="`终端 ${ratio}%，文件 ${100-ratio}%`" title="拖动调整高度，双击恢复默认比例"
        @pointerdown="startDrag" @pointermove="drag" @pointerup="endDrag" @pointercancel="endDrag" @lostpointercapture="endDrag"
        @dblclick="setRatio(60)" @keydown.up.prevent="setRatio(ratio-5)" @keydown.down.prevent="setRatio(ratio+5)"
        @keydown.home.prevent="setRatio(30)" @keydown.end.prevent="setRatio(75)" />
      <section class="files-slot" :class="{ 'files-collapsed': terminalActive && collapsed }" aria-label="文件管理">
        <div v-if="terminalActive" class="files-heading">
          <strong>文件管理</strong>
          <div class="layout-actions">
            <el-button v-if="!collapsed" text size="small" title="恢复终端 60%、文件 40%" @click="setRatio(60)">默认比例</el-button>
            <el-button v-if="!collapsed" text size="small" @click="setRatio(ratio > 40 ? 35 : 60)">{{ ratio > 40 ? '放大文件' : '放大终端' }}</el-button>
            <el-button text size="small" :aria-expanded="!collapsed" @click="collapsed=!collapsed">{{ collapsed ? '展开文件' : '收起文件' }}</el-button>
          </div>
        </div>
        <div v-show="!terminalActive || !collapsed" class="files-content"><slot name="files" :embedded="terminalActive" /></div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

defineProps({ terminalActive: { type: Boolean, default: true } })
const workspace = ref(null)
const ratio = ref(60), collapsed = ref(false)
try {
  const saved = Number(localStorage.getItem('doumi-terminal-height-percent'))
  if (saved >= 30 && saved <= 75) ratio.value = saved
  collapsed.value = localStorage.getItem('doumi-terminal-files-collapsed') === 'true'
} catch { /* Use defaults when preferences are unavailable. */ }
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
  if (bounds?.height > 10) setRatio((event.clientY - bounds.top - 5) / (bounds.height - 10) * 100)
}
function endDrag() { dragging = false }
</script>

<style scoped>
.terminal-file-layout { display:flex; height:100%; min-height:0; min-width:0; overflow:hidden; }
.right-workspace { display:grid; flex:1; min-width:0; min-height:0; overflow:hidden; }
.terminal-slot { display:flex; min-height:0; min-width:0; overflow:hidden; }
.terminal-slot :deep(.ssh-terminal-wrap) { flex:1; min-width:0; }
.splitter { position:relative; cursor:row-resize; touch-action:none; background:var(--el-fill-color-light); outline-offset:-2px; }
.splitter::after { content:''; position:absolute; top:4px; left:calc(50% - 22px); width:44px; height:2px; background:var(--el-border-color-darker); border-radius:2px; }
.splitter:hover, .splitter:focus-visible { background:var(--el-color-primary-light-8); }
.files-slot { display:flex; flex-direction:column; min-height:0; min-width:0; overflow:hidden; }
.files-heading { display:flex; align-items:center; justify-content:space-between; gap:8px; flex-shrink:0; height:32px; padding:0 8px; border-bottom:1px solid var(--el-border-color-light); font-size:13px; }
.layout-actions { display:flex; align-items:center; }
.layout-actions :deep(.el-button + .el-button) { margin-left:4px; }
.files-content { flex:1; min-height:0; min-width:0; overflow:hidden; padding:6px 8px 0; }
.files-only .right-workspace { display:flex; }
.files-only :deep(.ssh-monitor) { display:none; }
.files-only .files-slot { flex:1; }
.files-only .files-content { padding:0; }
</style>
