<template>
  <div class="snippet-bar" role="toolbar" aria-label="快捷常用命令架">
    <div class="snippet-label">
      <el-icon class="snippet-icon"><Lightning /></el-icon>
      <span>常用预设:</span>
    </div>

    <div class="snippet-list">
      <button
        v-for="item in allSnippets"
        :key="item.name"
        type="button"
        class="snippet-chip font-mono-code"
        :title="`${item.name}\n${item.cmd}`"
        @click="$emit('run', item.cmd)"
      >
        <span class="chip-name">{{ item.name }}</span>
      </button>

      <el-popover
        v-model:visible="addPopoverVisible"
        trigger="click"
        width="280"
        placement="bottom-end"
      >
        <template #reference>
          <button type="button" class="snippet-add-btn" title="添加自定义命令">
            <el-icon><Plus /></el-icon>
            <span>自定义</span>
          </button>
        </template>

        <div class="snippet-form">
          <div class="form-title">添加快捷指令</div>
          <el-input
            v-model="newSnippetName"
            size="small"
            placeholder="展示名称 (如: 重启服务)"
            class="mb-2"
          />
          <el-input
            v-model="newSnippetCmd"
            size="small"
            type="textarea"
            :rows="2"
            placeholder="Shell 指令 (如: systemctl restart app)"
            class="mb-2"
          />
          <div class="form-actions">
            <el-button size="small" @click="addPopoverVisible = false">取消</el-button>
            <el-button size="small" type="primary" :disabled="!newSnippetName || !newSnippetCmd" @click="addCustomSnippet">
              保存
            </el-button>
          </div>
        </div>
      </el-popover>
    </div>

    <!-- 关闭按钮 -->
    <button
      type="button"
      class="snippet-close-btn"
      title="收起快捷命令架 (ESC)"
      @click="$emit('close')"
    >
      <el-icon><Close /></el-icon>
    </button>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Close, Lightning, Plus } from '@element-plus/icons-vue'

const emit = defineEmits(['run', 'close'])

const STORAGE_KEY = 'doumi-custom-snippets'

const defaultSnippets = [
  { name: 'kubectl get pods -A', cmd: 'kubectl get pods -A -o wide' },
  { name: 'docker ps', cmd: 'docker ps --format "table {{.Names}}\\t{{.Status}}\\t{{.Ports}}"' },
  { name: 'tail syslog', cmd: 'tail -n 50 -f /var/log/syslog' },
  { name: 'df -h (磁盘)', cmd: 'df -hT --exclude-type=tmpfs' },
  { name: 'free -m (内存)', cmd: 'free -m -h' },
  { name: 'systemctl nginx', cmd: 'systemctl status nginx' },
  { name: 'netstat 端口', cmd: 'netstat -tulpn' },
  { name: 'top 进程', cmd: 'top -b -n 1 | head -n 25' }
]

const customSnippets = ref([])
try {
  const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
  if (Array.isArray(saved)) customSnippets.value = saved
} catch {
  /* use empty */
}

const allSnippets = computed(() => [
  ...defaultSnippets,
  ...customSnippets.value
])

const addPopoverVisible = ref(false)
const newSnippetName = ref('')
const newSnippetCmd = ref('')

function addCustomSnippet() {
  if (!newSnippetName.value.trim() || !newSnippetCmd.value.trim()) return
  customSnippets.value.push({
    name: newSnippetName.value.trim(),
    cmd: newSnippetCmd.value.trim()
  })
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(customSnippets.value))
  } catch {
    /* ignore */
  }
  newSnippetName.value = ''
  newSnippetCmd.value = ''
  addPopoverVisible.value = false
}

function onKeydown(e) {
  if (e.key === 'Escape' && !addPopoverVisible.value) {
    emit('close')
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.snippet-bar {
  position: absolute;
  top: 36px;
  left: 0;
  right: 0;
  z-index: 25;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 10px;
  background: var(--ws-bg-card);
  background: color-mix(in srgb, var(--ws-bg-card) 92%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--ws-border);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.16);
  flex-shrink: 0;
  overflow: hidden;
  font-size: 11px;
}

.snippet-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
  color: var(--ws-accent);
  white-space: nowrap;
  flex-shrink: 0;
  font-size: 11px;
}
.snippet-icon {
  font-size: 13px;
}

.snippet-list {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  white-space: nowrap;
  flex: 1;
  scrollbar-width: none;
}
.snippet-list::-webkit-scrollbar {
  display: none;
}

.snippet-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: var(--ws-radius-xs);
  background: var(--ws-bg-hover);
  border: 1px solid var(--ws-border);
  color: var(--ws-text-regular);
  font-size: 11px;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
  flex-shrink: 0;
}
.snippet-chip:hover {
  background: var(--ws-bg-active);
  border-color: var(--ws-accent);
  color: var(--ws-accent);
}

.snippet-add-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  border-radius: var(--ws-radius-xs);
  background: transparent;
  border: 1px dashed var(--ws-border);
  color: var(--ws-text-muted);
  font-size: 11px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all var(--ws-transition-fast);
}
.snippet-add-btn:hover {
  border-color: var(--ws-accent);
  color: var(--ws-accent);
}

.snippet-close-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border-radius: var(--ws-radius-xs);
  background: transparent;
  border: none;
  color: var(--ws-text-muted);
  cursor: pointer;
  flex-shrink: 0;
  transition: all var(--ws-transition-fast);
}
.snippet-close-btn:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-color-danger, #ef4444);
}

.snippet-form {
  padding: 4px;
}
.form-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--ws-text-primary);
}
.mb-2 {
  margin-bottom: 8px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
</style>
