<template>
  <section class="terminal-history" aria-label="tmux 历史内容" @keydown.esc.stop="$emit('close')">
    <div class="history-info" role="status">
      <span v-if="loading">正在读取 tmux 历史…</span>
      <span v-else-if="error">{{ error }}</span>
      <span v-else>tmux 历史快照 · {{ lines.length }} 行 · {{ result.matches.length ? current + 1 : 0 }}/{{ result.matches.length }}{{ result.limited ? '+' : '' }} 处匹配{{ truncated ? ' · 内容超过 8 MiB，仅保留末尾完整行' : '' }} · 刷新可读取最新输出</span>
    </div>
    <div ref="viewport" class="history-viewport" tabindex="0" @scroll="scrollTop = $event.target.scrollTop">
      <div :style="{ paddingTop: `${startRow * ROW_HEIGHT}px`, paddingBottom: `${Math.max(0, lines.length - endRow) * ROW_HEIGHT}px` }">
        <div v-for="row in visibleRows" :key="row.index" class="history-line" :class="{ 'is-current': activeRow === row.index }">
          <span class="history-number" aria-hidden="true">{{ row.index + 1 }}</span><span v-for="(part, index) in row.parts" :key="index"><mark v-if="part.match" :class="{ 'is-current-match': part.active }">{{ part.text }}</mark><template v-else>{{ part.text }}</template></span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { findHistoryMatches, historyLineParts } from '@/utils/terminalHistory'

const props = defineProps({ text: { type: String, default: '' }, query: { type: String, default: '' }, loading: Boolean, error: { type: String, default: '' }, truncated: Boolean })
defineEmits(['close'])
const ROW_HEIGHT = 22
const viewport = ref(null), scrollTop = ref(0), height = ref(600), current = ref(0)
const lines = computed(() => props.text ? props.text.replace(/\r\n/g, '\n').split('\n') : [])
const result = computed(() => findHistoryMatches(lines.value, props.query))
const activeRow = computed(() => result.value.matches[current.value]?.row ?? -1)
const activeMatch = computed(() => result.value.matches[current.value])
const startRow = computed(() => Math.max(0, Math.floor(scrollTop.value / ROW_HEIGHT) - 5))
const endRow = computed(() => Math.min(lines.value.length, startRow.value + Math.ceil(height.value / ROW_HEIGHT) + 10))
const matchesByRow = computed(() => {
  const grouped = new Map()
  for (const match of result.value.matches) {
    if (!grouped.has(match.row)) grouped.set(match.row, [])
    grouped.get(match.row).push(match)
  }
  return grouped
})
const visibleRows = computed(() => lines.value.slice(startRow.value, endRow.value).map((line, offset) => {
  const index = startRow.value + offset
  let from = 0
  const parts = historyLineParts(line, matchesByRow.value.get(index) || []).map(part => {
    const active = part.match && activeMatch.value?.row === index && activeMatch.value?.start === from
    from += part.text.length
    return { ...part, active }
  })
  return { index, parts }
}))

function reveal() {
  if (!viewport.value || activeRow.value < 0) return
  viewport.value.scrollTop = Math.max(0, activeRow.value * ROW_HEIGHT - height.value / 2)
  scrollTop.value = viewport.value.scrollTop
  nextTick(() => {
    const mark = viewport.value?.querySelector('.is-current-match')
    if (mark) viewport.value.scrollLeft = Math.max(0, mark.offsetLeft - viewport.value.clientWidth / 2)
  })
}
function find(direction = 1) {
  const count = result.value.matches.length
  if (!count) return
  current.value = (current.value + direction + count) % count
  reveal()
}
watch(result, async () => {
  current.value = 0
  scrollTop.value = 0
  await nextTick()
  if (viewport.value) viewport.value.scrollTop = 0
  reveal()
})
let observer
onMounted(() => {
  observer = new ResizeObserver(() => { height.value = viewport.value?.clientHeight || 600 })
  observer.observe(viewport.value)
  reveal()
})
onBeforeUnmount(() => observer?.disconnect())
defineExpose({ find })
</script>

<style scoped>
.terminal-history { position:absolute; inset:56px 0 0; z-index:5; display:flex; flex-direction:column; min-height:0; background:var(--el-bg-color); color:var(--el-text-color-primary); }
.history-info { flex-shrink:0; padding:8px 12px; font-size:12px; border-bottom:1px solid var(--el-border-color); overflow-wrap:anywhere; }
.history-viewport { flex:1; min-height:0; overflow:auto; font:13px/22px 'JetBrains Mono', Consolas, monospace; tab-size:8; }
.history-line { position:relative; height:22px; white-space:pre; width:max-content; min-width:100%; }
.history-line.is-current { background:var(--el-color-primary-light-9); }
.history-number { display:inline-block; width:7ch; padding-right:2ch; color:var(--el-text-color-secondary); text-align:right; user-select:none; }
mark { background:var(--el-color-warning-light-7); color:var(--el-text-color-primary); }
mark.is-current-match { background:var(--el-color-primary); color:var(--el-color-white, white); }
.history-viewport:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:-2px; }
</style>
