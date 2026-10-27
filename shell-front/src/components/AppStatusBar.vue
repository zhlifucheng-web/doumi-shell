<template>
  <footer class="app-status-bar font-mono-code" role="contentinfo" aria-label="状态栏">
    <div class="status-left">
      <!-- 连通状态 -->
      <div class="status-item status-conn" :class="{ 'is-connected': isConnected }">
        <span class="online-pulse-dot"></span>
        <span class="font-sans font-medium">{{ isConnected ? 'SSH-2.0 已建立' : '会话就绪' }}</span>
      </div>

      <span class="status-sep">|</span>
      <span class="status-item">
        延迟: <strong class="text-online font-normal">18ms</strong>
      </span>

      <span class="status-sep">|</span>
      <span class="status-item">
        尺寸: <strong>{{ cols || 120 }} x {{ rows || 34 }}</strong>
      </span>

      <span class="status-sep">|</span>
      <span class="status-item">
        编码: <strong>UTF-8</strong>
      </span>
    </div>

    <div class="status-right">
      <!-- 传输队列 -->
      <button
        type="button"
        class="status-btn"
        :class="{ 'has-transfers': activeTransferCount > 0 }"
        :title="activeTransferCount > 0 ? `查看传输中心 (${activeTransferCount} 项进行中 · ${overallPercent}%)` : '查看传输中心'"
        @click="$emit('open-transfers')"
      >
        <el-icon><Upload /></el-icon>
        <span>传输: {{ activeTransferCount > 0 ? `${activeTransferCount} 进行中 · ${overallPercent}%` : '就绪' }}</span>
      </button>

      <span class="status-sep">|</span>

      <!-- AI 状态 -->
      <button
        type="button"
        class="status-btn status-ai-btn"
        title="打开 AI Copilot · Ctrl + Shift + K"
        @click="$emit('open-ai')"
      >
        <span class="ai-sparkle-dot">✨</span>
        <span class="font-sans font-medium">AI Ready</span>
      </button>
    </div>
  </footer>
</template>

<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { Upload } from '@element-plus/icons-vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useTransferStore } from '@/stores/transfers'
import { isTransferActive } from '@/composables/transferHistory'

const props = defineProps({
  cols: { type: Number, default: 0 },
  rows: { type: Number, default: 0 },
  sessionActive: { type: Boolean, default: false }
})

defineEmits(['open-transfers', 'open-ai'])

const workspaceStore = useWorkspaceStore()
const { managedServer } = storeToRefs(workspaceStore)

const transferStore = useTransferStore()
const activeTransferCount = computed(() => {
  return transferStore.transfers.filter(isTransferActive).length
})

const overallPercent = computed(() => {
  const active = transferStore.transfers.filter(isTransferActive)
  if (!active.length) return 0
  let loaded = 0
  let total = 0
  for (const t of active) {
    loaded += Math.max(0, Number(t.loaded) || 0)
    total += Math.max(0, Number(t.total) || 0)
  }
  return total > 0 ? Math.min(99, Math.floor((loaded / total) * 100)) : 0
})

const isConnected = computed(() => {
  return Boolean(props.sessionActive || managedServer.value)
})
</script>

<style scoped>
.app-status-bar {
  height: 24px;
  min-height: 24px;
  background: var(--ws-bg-status);
  border-top: 1px solid var(--ws-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  font-size: 11px;
  color: var(--ws-text-muted);
  user-select: none;
  flex-shrink: 0;
  z-index: 20;
  transition: background-color var(--ws-transition-normal), border-color var(--ws-transition-normal);
}

.status-left,
.status-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.status-item strong {
  color: var(--ws-text-regular);
  font-weight: 500;
}

.status-conn {
  color: var(--ws-text-subtle);
}
.status-conn.is-connected {
  color: var(--ws-color-online);
}

.text-online {
  color: var(--ws-color-online);
}

.status-sep {
  color: var(--ws-border);
  font-weight: 300;
  font-size: 10px;
}

.status-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  color: var(--ws-text-muted);
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
  padding: 1px 4px;
  border-radius: var(--ws-radius-xs);
  transition: all var(--ws-transition-fast);
}
.status-btn:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
}
.status-btn.has-transfers {
  color: var(--ws-accent);
  font-weight: 600;
}

.status-ai-btn {
  color: var(--ws-accent);
}
.ai-sparkle-dot {
  font-size: 11px;
}
</style>
