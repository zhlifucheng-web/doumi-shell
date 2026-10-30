<template>
  <div class="connection-actions">
    <button
      type="button"
      class="connection-button"
      :disabled="state === 'connecting'"
      :title="state === 'connected' ? '断开当前连接，保留远端 tmux 会话' : state === 'connecting' ? '正在建立连接' : '重新连接此终端'"
      @click="$emit(state === 'connected' ? 'disconnect' : 'reconnect')"
    >
      <el-icon><SwitchButton v-if="state === 'connected'" /><Refresh v-else /></el-icon>
      <span>{{ state === 'connected' ? '断开连接' : state === 'connecting' ? '连接中…' : '重新连接' }}</span>
    </button>
    <el-dropdown v-if="state === 'disconnected'" trigger="click" @command="$emit('reconnect-ssh')">
      <button type="button" class="connection-button connection-menu" title="其他连接方式" aria-label="其他连接方式">
        <el-icon><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="ssh">改用普通 SSH</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import { ArrowDown, Refresh, SwitchButton } from '@element-plus/icons-vue'

defineProps({ state: { type: String, default: 'connected' } })
defineEmits(['disconnect', 'reconnect', 'reconnect-ssh'])
</script>

<style scoped>
.connection-actions { display: inline-flex; align-items: center; gap: 2px; flex-shrink: 0; }
.connection-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  height: 26px;
  padding: 0 6px;
  border: 1px solid var(--ws-border);
  border-radius: var(--ws-radius-xs);
  background: var(--ws-bg-input);
  color: var(--ws-text-regular);
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}
.connection-button:hover { background: var(--ws-bg-hover); border-color: var(--ws-border-hover); color: var(--ws-text-primary); }
.connection-button:disabled { opacity: 0.4; cursor: not-allowed; }
.connection-menu { padding: 0 4px; }
</style>
