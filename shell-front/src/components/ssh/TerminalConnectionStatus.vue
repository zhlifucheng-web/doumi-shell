<template>
  <span class="connection-status">
    <el-popover v-if="compatibility.tmux" trigger="click" placement="bottom" :width="320">
      <template #reference>
        <button type="button" class="connection-badge" :class="{ 'has-warning': compatibility.warning }" aria-label="tmux 版本与兼容性" :title="`${compatibility.label} · 点击查看功能兼容性`">
          {{ compatibility.label }}<span v-if="compatibility.warning" class="compatibility-hint">{{ compatibility.historySupported === false ? '功能受限' : '兼容性待确认' }}</span>
        </button>
      </template>
      <div class="compatibility-details">
        <strong>tmux {{ compatibility.version }}</strong>
        <p>{{ compatibility.note }}</p>
        <table aria-label="tmux 功能版本兼容性">
          <thead><tr><th>功能</th><th>最低版本</th><th>版本支持</th></tr></thead>
          <tbody><tr v-for="feature in compatibility.features" :key="feature.key"><td>{{ feature.name }}</td><td>{{ feature.minimum }}+</td><td :class="{ 'is-warning': feature.available !== true }">{{ feature.status }}</td></tr></tbody>
        </table>
        <p v-if="compatibility.historySupported === false" class="is-warning">{{ compatibility.historyNote }}</p>
        <p>历史范围取决于远端保留量，已丢弃的输出无法恢复。</p>
      </div>
    </el-popover>
    <span v-else class="plain-mode">{{ compatibility.label }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { tmuxCompatibility } from '@/utils/tmuxCompatibility'
const props = defineProps({ connectionMode: { type: String, default: '' }, tmuxVersion: { type: String, default: '' }, tmuxVersionSource: { type: String, default: 'unknown' } })
const compatibility = computed(() => tmuxCompatibility(props.connectionMode, props.tmuxVersion, props.tmuxVersionSource))
</script>

<style scoped>
.connection-status { display:inline-flex; flex-shrink:0; align-items:center; }
.connection-badge, .plain-mode { display:inline-flex; align-items:center; gap:6px; padding:3px 7px; border:1px solid var(--el-border-color); border-radius:4px; background:var(--el-fill-color-light); color:var(--el-text-color-secondary); font:11px/1.4 var(--font-mono, monospace); white-space:nowrap; }
.connection-badge { cursor:pointer; }
.connection-badge.has-warning { border-color:var(--el-color-warning-light-5); }
.compatibility-hint { color:var(--el-color-warning-dark-2); font:11px/1.4 sans-serif; }
.connection-badge:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:2px; }
.compatibility-details { color:var(--el-text-color-primary); font-size:12px; }
.compatibility-details p { margin:8px 0; line-height:1.6; }
.compatibility-details table { width:100%; border-collapse:collapse; text-align:left; }
.compatibility-details th, .compatibility-details td { padding:6px 3px; border-bottom:1px solid var(--el-border-color-lighter); }
.compatibility-details th { color:var(--el-text-color-secondary); font-weight:500; }
.is-warning { color:var(--el-color-warning-dark-2); }
</style>
