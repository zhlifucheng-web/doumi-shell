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
            :placeholder="placeholderText"
            @keydown.down.prevent="navigate(1)"
            @keydown.up.prevent="navigate(-1)"
            @keydown.enter.prevent="selectActive"
          />
          <kbd class="palette-esc-badge" title="关闭 (ESC)" @click="close">ESC</kbd>
        </div>

        <div v-if="filteredItems.length" ref="listRef" class="palette-list">
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
                <el-icon v-if="item.icon" class="palette-item-ico" :class="item.type">
                  <component :is="item.icon" />
                </el-icon>
                <div class="palette-item-meta">
                  <div class="palette-item-row-top">
                    <span class="palette-item-label">{{ item.label }}</span>
                    <span v-if="item.ip" class="palette-ip-pill" :class="{ 'is-match': item.ipMatched }">
                      {{ item.ip }}{{ item.port ? `:${item.port}` : '' }}
                    </span>
                    <span v-if="item.groupName" class="palette-group-pill">
                      {{ item.groupName }}
                    </span>
                  </div>
                  <div v-if="item.sub" class="palette-item-sub">
                    {{ item.sub }}
                  </div>
                </div>
              </div>
              <div v-if="item.shortcut" class="palette-item-shortcut">
                <kbd>{{ item.shortcut }}</kbd>
              </div>
            </template>
          </div>
        </div>

        <div v-else class="palette-empty">
          <el-icon class="palette-empty-ico"><Search /></el-icon>
          <p>未找到匹配的{{ fileContext ? '文件、' : '' }}主机、IP 或操作命令</p>
          <span class="palette-empty-hint">{{ fileContext ? '支持按当前目录文件名、主机名、IP、用户名或操作命令筛选' : '支持按主机名、IP 地址（如 192.168.x.x）、用户名、操作命令筛选' }}，或输入 ? 咨询 AI</span>
        </div>

        <div class="palette-footer">
          <div class="palette-footer-tips">
            <span><kbd>↑</kbd><kbd>↓</kbd> 导航</span>
            <span><kbd>Enter</kbd> 选择 / 直达</span>
            <span><kbd>ESC</kbd> 关闭</span>
          </div>
          <span class="palette-footer-brand">Doumi Shell 快捷检索</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  Brush,
  Cpu,
  Delete,
  Document,
  Folder,
  InfoFilled,
  MagicStick,
  Monitor,
  Moon,
  Odometer,
  PieChart,
  Rank,
  Search,
  UploadFilled,
  User
} from '@element-plus/icons-vue'
import { fetchSearchTargets } from '@/api/sshConsole'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  fileContext: { type: Object, default: null }
})

const emit = defineEmits([
  'update:modelValue',
  'select-target',
  'action',
  'ask-ai'
])

const isMac = typeof navigator !== 'undefined' && /Mac|iPod|iPhone|iPad/.test(navigator.platform)
const placeholderText = computed(() =>
  `搜索${props.fileContext ? '当前目录文件、' : ''}主机名、IP 地址、用户名、动作 (如 >split) 或输入 ? 询问 AI... (${isMac ? '⌘K' : 'Ctrl+K'})`
)

const query = ref('')
const activeIndex = ref(0)
const inputRef = ref(null)
const listRef = ref(null)
const searchTargets = ref([])
const loadingTargets = ref(false)

const defaultActions = [
  { id: 'act-clear', label: '清空终端屏幕', sub: 'Clear Terminal Screen', icon: Delete, shortcut: 'Ctrl+L', action: 'clear', keywords: ['clear', 'qingkong', 'qk'] },
  { id: 'act-split', label: '切换终端分屏对比', sub: 'Split Terminal Pane', icon: Rank, shortcut: 'Ctrl+\\', action: 'split', keywords: ['split', 'fenping', 'fp'] },
  { id: 'act-snippets', label: '展开/收起常用命令架', sub: 'Snippets Shelf Toolbar', icon: Document, action: 'snippets', keywords: ['snippet', 'mingling', 'cmd'] },
  { id: 'act-terminal-theme', label: '终端配色与 Zsh 主题', sub: 'Terminal Color Schemes & Zsh Themes', icon: Brush, action: 'terminal-theme', keywords: ['theme', 'peise', 'terminal', 'color'] },
  { id: 'act-theme', label: '切换深色 / 日间明亮模式', sub: 'Toggle Dark / Light Mode', icon: Moon, action: 'toggle-theme', keywords: ['dark', 'light', 'moshi', 'anhei'] },
  { id: 'act-ai', label: '打开 AI 运维助手', sub: 'AI Copilot Assistant', icon: MagicStick, shortcut: 'Ctrl+Shift+K', action: 'ai', keywords: ['ai', 'copilot', 'zhushou'] },
  { id: 'act-route-terminal', label: '跳转：终端与监控', sub: 'Navigate to Terminal & Monitor', icon: Monitor, action: 'route:/terminal', keywords: ['terminal', 'zhongduan'] },
  { id: 'act-route-scripts', label: '跳转：脚本任务', sub: 'Navigate to Script Execution & Schedules', icon: Cpu, action: 'route:/scripts', keywords: ['script', 'jiaoben', 'renwu'] },
  { id: 'act-route-processes', label: '跳转：进程管理', sub: 'Navigate to Host Processes', icon: Odometer, action: 'route:/processes', keywords: ['process', 'jincheng', 'top'] },
  { id: 'act-route-disk', label: '跳转：存储管理', sub: 'Navigate to Disk Space & Usage Analysis', icon: PieChart, action: 'route:/disk-usage', keywords: ['disk', 'cunchu', 'kongjian', 'df'] },
  { id: 'act-route-data', label: '跳转：数据管理', sub: 'FinalShell 导入与数据包迁移备份', icon: Folder, action: 'route:/data-management', keywords: ['data', 'shuju', 'daoru', 'import', 'finalshell'] },
  { id: 'act-route-backup', label: '跳转：远程备份', sub: 'MinIO / SFTP 定时自动云端备份', icon: UploadFilled, action: 'route:/data-management', keywords: ['backup', 'beifen', 'minio', 'sftp'] },
  { id: 'act-route-about', label: '跳转：关于 Doumi Shell', sub: 'Version Info & System About', icon: InfoFilled, action: 'route:/about', keywords: ['about', 'guanyu', 'banben'] }
]

async function loadTargets() {
  if (loadingTargets.value) return
  loadingTargets.value = true
  try {
    const list = await fetchSearchTargets()
    searchTargets.value = Array.isArray(list) ? list : []
  } catch {
    // Graceful fallback to avoid blocking modal
  } finally {
    loadingTargets.value = false
  }
}

const filteredItems = computed(() => {
  const q = query.value.trim().toLowerCase()
  const results = []

  // 1. If query starts with ? or ？ ask AI directly
  if (q.startsWith('?') || q.startsWith('？')) {
    const aiPrompt = q.slice(1).trim()
    results.push({
      id: 'ai-prompt',
      label: aiPrompt ? `询问 AI: "${aiPrompt}"` : '输入运维问题直接咨询 AI Copilot...',
      icon: MagicStick,
      sub: 'AI Copilot 智能辅助',
      action: 'ask-ai',
      payload: aiPrompt
    })
    return results
  }

  // 2. Filter Hosts & Accounts by IP address, host name, username, remarks, and groups
  const matchedTargets = []
  const terms = q.split(/\s+/).filter(Boolean)

  const context = props.fileContext
  if (context && !q.startsWith('>')) {
    const matches = (context.files || []).filter(file =>
      terms.every(term => String(file.name).toLowerCase().includes(term)))
    if (matches.length) {
      results.push({ isHeader: true, label: `当前目录文件 (${matches.length}${matches.length > 50 ? '，显示前 50 项' : ''}) · ${context.path}` })
      results.push(...matches.slice(0, 50).map(file => ({
        id: `file-${file.name}`, type: 'file', label: file.name,
        sub: context.path, icon: Document, file, fileContext: context
      })))
    }
  }


  for (const item of searchTargets.value) {
    const hostAddr = String(item.hostAddress || '').toLowerCase()
    const hostName = String(item.hostName || '').toLowerCase()
    const user = String(item.username || '').toLowerCase()
    const group = String(item.groupName || '').toLowerCase()
    const accRemark = String(item.accountRemark || '').toLowerCase()
    const hostRemark = String(item.hostRemark || '').toLowerCase()
    const endpoint = `${hostAddr}:${item.hostPort || 22}`
    const userAtHost = user ? `${user}@${hostAddr}` : ''
    const userAtName = user ? `${user}@${hostName}` : ''

    const isMatch = terms.length === 0 || terms.every(term =>
      hostAddr.includes(term) ||
      endpoint.includes(term) ||
      hostName.includes(term) ||
      user.includes(term) ||
      group.includes(term) ||
      accRemark.includes(term) ||
      hostRemark.includes(term) ||
      userAtHost.includes(term) ||
      userAtName.includes(term)
    )

    if (isMatch) {
      const isIpMatch = terms.some(t => hostAddr.includes(t) || endpoint.includes(t))
      const hasAccount = Boolean(item.accountId && item.username)
      const label = hasAccount
        ? `${item.username}@${item.hostName || item.hostAddress}`
        : (item.hostName || item.hostAddress)

      const remarks = [item.hostRemark, item.accountRemark].filter(Boolean).join(' · ')
      const sub = remarks || (hasAccount ? `端口: ${item.hostPort || 22}` : '未配置登录账户')

      matchedTargets.push({
        id: hasAccount ? `acc-${item.accountId}` : `host-${item.hostId}`,
        label,
        sub,
        ip: item.hostAddress || '',
        port: item.hostPort || 22,
        groupName: item.groupName || '',
        ipMatched: isIpMatch,
        icon: hasAccount ? User : Monitor,
        type: hasAccount ? 'account' : 'host',
        target: item
      })
    }
  }

  if (matchedTargets.length > 0) {
    results.push({ isHeader: true, label: `服务器与账户 (${matchedTargets.length})` })
    results.push(...matchedTargets.slice(0, 15))
  }

  // 3. Filter actions & commands
  const searchTarget = q.startsWith('>') ? q.slice(1).trim() : q
  const matchedActions = defaultActions.filter(act => {
    if (!searchTarget) return true
    const text = `${act.label} ${act.sub} ${(act.keywords || []).join(' ')}`.toLowerCase()
    return text.includes(searchTarget)
  })

  if (matchedActions.length > 0) {
    results.push({ isHeader: true, label: '常用动作与页面跳转' })
    results.push(...matchedActions)
  }

  return results
})

watch(() => props.modelValue, (open) => {
  if (open) {
    activeIndex.value = 0
    void loadTargets()
    nextTick(() => {
      inputRef.value?.focus()
      inputRef.value?.select()
    })
  }
})

watch(filteredItems, () => {
  // Reset active index if out of bounds or on header
  if (activeIndex.value >= filteredItems.value.length) {
    activeIndex.value = 0
  }
  const current = filteredItems.value[activeIndex.value]
  if (current?.isHeader) {
    navigate(1)
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
  if (item.type === 'file') {
    if (item.fileContext === props.fileContext && props.fileContext?.openFile(item.file)) close()
    return
  }
  close()
  if (item.target) {
    emit('select-target', item.target)
  } else if (item.action === 'ask-ai') {
    emit('ask-ai', item.payload)
  } else if (item.action) {
    emit('action', item.action)
  }
}

onMounted(() => {
  if (props.modelValue) {
    void loadTargets()
  }
})
</script>

<style scoped>
.palette-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.48);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 12vh;
}
.palette-modal {
  width: 100%;
  max-width: 660px;
  background: var(--el-bg-color-overlay, #ffffff);
  border: 1px solid var(--el-border-color, #e2e8f0);
  border-radius: 12px;
  box-shadow: 0 20px 45px rgba(0, 0, 0, 0.3);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  animation: palette-slide-in 0.16s ease-out;
}
@keyframes palette-slide-in {
  from {
    opacity: 0;
    transform: translateY(-8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
html.dark .palette-modal {
  background: #0f1523;
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.75);
}
.palette-input-wrap {
  display: flex;
  align-items: center;
  padding: 13px 18px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f1f5f9);
  gap: 12px;
}
html.dark .palette-input-wrap {
  border-bottom-color: rgba(255, 255, 255, 0.08);
}
.palette-search-ico {
  font-size: 20px;
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
.palette-input::placeholder {
  color: var(--el-text-color-placeholder);
  font-size: 13.5px;
}
.palette-esc-badge {
  padding: 2px 7px;
  font-size: 11px;
  font-family: inherit;
  font-weight: 600;
  border-radius: 5px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  border: 1px solid var(--el-border-color);
  cursor: pointer;
  transition: all 0.15s;
}
.palette-esc-badge:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}
.palette-list {
  max-height: 380px;
  overflow-y: auto;
  padding: 8px;
}
.palette-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
  user-select: none;
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
  background: var(--el-fill-color-light, #f1f5f9);
}
html.dark .palette-item:not(.is-header):hover,
html.dark .palette-item.is-active {
  background: rgba(99, 102, 241, 0.14);
}
.palette-item-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}
.palette-item-ico {
  font-size: 18px;
  color: var(--el-color-primary, #6366f1);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
html.dark .palette-item-ico {
  background: rgba(255, 255, 255, 0.05);
}
.palette-item-ico.account {
  color: #10b981;
}
.palette-item-ico.host {
  color: #3b82f6;
}
.palette-item-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.palette-item-row-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
}
.palette-item-label {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.palette-ip-pill {
  font-family: monospace, ui-monospace;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-darker, rgba(0, 0, 0, 0.06));
  color: var(--el-text-color-regular);
  flex-shrink: 0;
}
.palette-ip-pill.is-match {
  background: rgba(99, 102, 241, 0.15);
  color: var(--el-color-primary, #6366f1);
  font-weight: 600;
}
.palette-group-pill {
  font-size: 10.5px;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
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
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  border: 1px solid var(--el-border-color);
}
.palette-empty {
  padding: 36px 16px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-placeholder);
}
.palette-empty-ico {
  font-size: 32px;
  opacity: 0.4;
}
.palette-empty p {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-regular);
}
.palette-empty-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  max-width: 420px;
}
.palette-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 16px;
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
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--el-fill-color);
  border: 1px solid var(--el-border-color);
  margin-right: 3px;
}
.palette-footer-brand {
  font-weight: 600;
  color: var(--el-color-primary, #6366f1);
}
</style>
