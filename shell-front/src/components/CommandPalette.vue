<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="palette-overlay"
      @click.self="close"
      @keydown.esc="close"
    >
      <div class="palette-modal" @click.stop>
        <div class="palette-input-wrap">
          <el-icon class="palette-search-ico"><Search /></el-icon>
          <input
            ref="inputRef"
            v-model="query"
            type="text"
            class="palette-input"
            placeholder="搜索主机、执行动作 (如 >split, >clear) 或输入 ? 询问 AI..."
            @keydown.down.prevent="navigate(1)"
            @keydown.up.prevent="navigate(-1)"
            @keydown.enter.prevent="selectActive"
          />
          <kbd class="palette-esc-badge" @click="close">ESC</kbd>
        </div>

        <div v-if="filteredItems.length" class="palette-list" ref="listRef">
          <div
            v-for="(item, idx) in filteredItems"
            :key="item.id || item.label"
            class="palette-item"
            :class="{ 'is-active': activeIndex === idx, 'is-header': item.isHeader }"
            @mouseenter="!item.isHeader && (activeIndex = idx)"
            @click="!item.isHeader && execute(item)"
          >
            <template v-if="item.isHeader">
              <span class="palette-group-title">{{ item.label }}</span>
            </template>
            <template v-else>
              <div class="palette-item-left">
                <el-icon v-if="item.icon" class="palette-item-ico">
                  <component :is="item.icon" />
                </el-icon>
                <span class="palette-item-label">{{ item.label }}</span>
                <span v-if="item.sub" class="palette-item-sub">{{ item.sub }}</span>
              </div>
              <div v-if="item.shortcut" class="palette-item-shortcut">
                <kbd>{{ item.shortcut }}</kbd>
              </div>
            </template>
          </div>
        </div>
        <div v-else class="palette-empty">
          <p>未找到匹配的主机或动作</p>
        </div>

        <div class="palette-footer">
          <div class="palette-footer-tips">
            <span><kbd>↑</kbd><kbd>↓</kbd> 导航</span>
            <span><kbd>Enter</kbd> 选择</span>
            <span><kbd>ESC</kbd> 关闭</span>
          </div>
          <span class="palette-footer-brand">Doumi Shell 2.0</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import {
  Aim,
  Brush,
  ChatDotRound,
  Close,
  Delete,
  Document,
  Grid,
  MagicStick,
  Monitor,
  Moon,
  Rank,
  Refresh,
  Search,
  Sunny,
  User
} from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  hosts: { type: Array, default: () => [] },
  accounts: { type: Array, default: () => [] }
})

const emit = defineEmits([
  'update:modelValue',
  'select-account',
  'action',
  'ask-ai'
])

const query = ref('')
const activeIndex = ref(0)
const inputRef = ref(null)
const listRef = ref(null)

const defaultActions = [
  { id: 'act-clear', label: '清空终端屏幕', sub: 'Clear Screen', icon: Delete, shortcut: 'Ctrl+L', action: 'clear' },
  { id: 'act-split', label: '切换终端分屏对比', sub: 'Split View', icon: Rank, shortcut: 'Ctrl+\\', action: 'split' },
  { id: 'act-snippets', label: '展开/收起常用命令架', sub: 'Snippets Shelf', icon: Document, action: 'snippets' },
  { id: 'act-terminal-theme', label: '终端与 Zsh 配色方案 (导入主题)', sub: 'Terminal & Zsh Color Schemes', icon: Brush, action: 'terminal-theme' },
  { id: 'act-ai', label: '打开 AI 运维助手', sub: 'AI Copilot', icon: MagicStick, shortcut: 'Ctrl+Shift+K', action: 'ai' },
  { id: 'act-theme', label: '切换深色/日间模式', sub: 'Toggle Dark/Light', icon: Moon, action: 'toggle-theme' }
]

const filteredItems = computed(() => {
  const q = query.value.trim().toLowerCase()
  const results = []

  // If query starts with ? ask AI directly
  if (q.startsWith('?') || q.startsWith('？')) {
    const aiPrompt = q.slice(1).trim()
    results.push({
      id: 'ai-prompt',
      label: aiPrompt ? `询问 AI: "${aiPrompt}"` : '输入问题直接发起 AI 咨询...',
      icon: MagicStick,
      sub: 'AI Copilot',
      action: 'ask-ai',
      payload: aiPrompt
    })
    return results
  }

  // Filter accounts / hosts
  const matchedAccounts = []
  for (const acc of props.accounts) {
    const text = `${acc.username || ''} ${acc.name || ''} ${acc.host || ''}`.toLowerCase()
    if (!q || text.includes(q)) {
      matchedAccounts.push({
        id: `acc-${acc.id}`,
        label: acc.username ? `${acc.username}@${acc.name || acc.host}` : (acc.name || acc.host),
        sub: acc.host ? `${acc.host}:${acc.port || 22}` : '',
        icon: User,
        account: acc,
        type: 'account'
      })
    }
  }

  if (matchedAccounts.length) {
    results.push({ isHeader: true, label: '服务器与账户' })
    results.push(...matchedAccounts.slice(0, 8))
  }

  // Filter actions
  const matchedActions = defaultActions.filter(act => {
    if (!q) return true
    const searchTarget = q.startsWith('>') ? q.slice(1).trim() : q
    return act.label.toLowerCase().includes(searchTarget) || act.sub.toLowerCase().includes(searchTarget)
  })

  if (matchedActions.length) {
    results.push({ isHeader: true, label: '快捷动作' })
    results.push(...matchedActions)
  }

  return results
})

watch(() => props.modelValue, (open) => {
  if (open) {
    query.value = ''
    activeIndex.value = 1 // skip first header if present
    nextTick(() => {
      inputRef.value?.focus()
    })
  }
})

function close() {
  emit('update:modelValue', false)
}

function navigate(direction) {
  const items = filteredItems.value
  if (!items.length) return
  let next = activeIndex.value + direction
  while (next >= 0 && next < items.length && items[next].isHeader) {
    next += direction
  }
  if (next >= 0 && next < items.length) {
    activeIndex.value = next
    ensureVisible(next)
  }
}

function ensureVisible(index) {
  nextTick(() => {
    const container = listRef.value
    if (!container) return
    const el = container.children[index]
    if (el) {
      el.scrollIntoView({ block: 'nearest' })
    }
  })
}

function selectActive() {
  const item = filteredItems.value[activeIndex.value]
  if (item && !item.isHeader) {
    execute(item)
  }
}

function execute(item) {
  close()
  if (item.type === 'account') {
    emit('select-account', item.account)
  } else if (item.action === 'ask-ai') {
    emit('ask-ai', item.payload)
  } else if (item.action) {
    emit('action', item.action)
  }
}
</script>

<style scoped>
.palette-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(8px);
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 14vh;
}
.palette-modal {
  width: 100%;
  max-width: 620px;
  background: var(--el-bg-color-overlay, #ffffff);
  border: 1px solid var(--el-border-color, #e2e8f0);
  border-radius: 12px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
html.dark .palette-modal {
  background: #0f1523;
  border-color: rgba(255, 255, 255, 0.1);
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
}
.palette-input-wrap {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f1f5f9);
  gap: 10px;
}
html.dark .palette-input-wrap {
  border-bottom-color: rgba(255, 255, 255, 0.08);
}
.palette-search-ico {
  font-size: 18px;
  color: var(--el-color-primary, #6366f1);
  flex-shrink: 0;
}
.palette-input {
  flex: 1;
  background: transparent;
  border: none;
  font-size: 15px;
  color: var(--el-text-color-primary);
  outline: none;
}
.palette-esc-badge {
  padding: 2px 6px;
  font-size: 11px;
  border-radius: 4px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  border: 1px solid var(--el-border-color);
  cursor: pointer;
}
.palette-list {
  max-height: 340px;
  overflow-y: auto;
  padding: 6px;
}
.palette-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.palette-item.is-header {
  cursor: default;
  padding: 8px 12px 4px;
}
.palette-group-title {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--el-text-color-placeholder);
}
.palette-item:not(.is-header):hover,
.palette-item.is-active {
  background: var(--el-fill-color-light);
}
html.dark .palette-item:not(.is-header):hover,
html.dark .palette-item.is-active {
  background: rgba(99, 102, 241, 0.12);
}
.palette-item-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.palette-item-ico {
  font-size: 16px;
  color: var(--el-color-primary);
  flex-shrink: 0;
}
.palette-item-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.palette-item-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.palette-item-shortcut kbd {
  font-size: 11px;
  padding: 2px 5px;
  border-radius: 4px;
  background: var(--el-fill-color-darker, rgba(0, 0, 0, 0.08));
  color: var(--el-text-color-secondary);
}
.palette-empty {
  padding: 30px 16px;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.palette-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: var(--el-fill-color-lighter, #f8fafc);
  border-top: 1px solid var(--el-border-color-lighter, #f1f5f9);
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
html.dark .palette-footer {
  background: #090d16;
  border-top-color: rgba(255, 255, 255, 0.06);
}
.palette-footer-tips {
  display: flex;
  gap: 12px;
}
.palette-footer-tips kbd {
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--el-fill-color);
  border: 1px solid var(--el-border-color);
  margin-right: 3px;
}
.palette-footer-brand {
  font-weight: 600;
  color: var(--el-color-primary);
}
</style>
