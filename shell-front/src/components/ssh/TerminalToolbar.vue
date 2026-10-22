<template>
  <div class="terminal-toolbar" role="toolbar" aria-label="终端工具栏">
    <!-- 日间模式下的终端纸白/双拼底色切换 -->
    <button
      v-if="!isDark"
      type="button"
      class="tool-btn"
      :title="`当前底色: ${terminalTone === 'paper' ? '纸白高对比' : '双拼暗黑'} (点击切换)`"
      @click="$emit('toggle-tone')"
    >
      <span>{{ terminalTone === 'paper' ? '📄 纸白' : '⬛ 双拼' }}</span>
    </button>

    <!-- 清屏 -->
    <button
      type="button"
      class="tool-btn"
      title="清空当前屏幕输出 · Ctrl + L"
      @click="$emit('clear')"
    >
      <el-icon><Delete /></el-icon>
      <span class="tool-btn-text">清屏</span>
    </button>

    <!-- 常用命令架开关 -->
    <button
      type="button"
      class="tool-btn"
      :class="{ 'is-active': snippetVisible }"
      title="切换常用快捷命令架"
      @click="$emit('toggle-snippets')"
    >
      <el-icon><Document /></el-icon>
      <span class="tool-btn-text">快捷命令</span>
    </button>

    <!-- 分屏对比 -->
    <button
      v-if="allowSplit"
      type="button"
      class="tool-btn"
      :class="{ 'is-active': isSplit }"
      title="切换终端左右分屏对比"
      @click="$emit('toggle-split')"
    >
      <el-icon><Rank /></el-icon>
      <span class="tool-btn-text">{{ isSplit ? '取消分屏' : '分屏' }}</span>
    </button>

    <!-- 字号缩放 -->
    <div class="tool-group">
      <button
        type="button"
        class="tool-btn tool-btn-compact"
        title="减小字号"
        :disabled="fontSize <= 12"
        @click="$emit('change-font-size', fontSize - 1)"
      >
        A-
      </button>
      <span class="font-size-label font-mono-code">{{ fontSize }}px</span>
      <button
        type="button"
        class="tool-btn tool-btn-compact"
        title="增大字号"
        :disabled="fontSize >= 20"
        @click="$emit('change-font-size', fontSize + 1)"
      >
        A+
      </button>
    </div>

    <!-- 全屏 -->
    <button
      type="button"
      class="tool-btn"
      title="全屏终端"
      @click="$emit('toggle-fullscreen')"
    >
      <el-icon><FullScreen /></el-icon>
    </button>

    <!-- 终端与 Zsh 配色方案 -->
    <button
      type="button"
      class="tool-btn"
      title="终端配色与导入 Zsh 主题 (Catppuccin / Tokyo Night / iTerm2)"
      @click="$emit('open-theme')"
    >
      <span>🎨</span>
      <span class="tool-btn-text">配色</span>
    </button>

    <!-- 字符编码切换 -->
    <el-dropdown trigger="click" @command="enc => $emit('change-encoding', enc)">
      <button
        type="button"
        class="tool-btn"
        :title="`当前字符编码: ${encoding || 'UTF-8'} (点击切换)`"
      >
        <span class="tool-btn-text font-mono-code">{{ encoding || 'UTF-8' }}</span>
        <el-icon class="tool-btn-caret"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="UTF-8" :class="{ 'is-active-enc': (encoding || 'UTF-8') === 'UTF-8' }">
            UTF-8 (国际通用)
          </el-dropdown-item>
          <el-dropdown-item command="GBK" :class="{ 'is-active-enc': encoding === 'GBK' }">
            GBK / GB2312 (简体中文)
          </el-dropdown-item>
          <el-dropdown-item command="GB18030" :class="{ 'is-active-enc': encoding === 'GB18030' }">
            GB18030 (国家大字库)
          </el-dropdown-item>
          <el-dropdown-item command="Big5" :class="{ 'is-active-enc': encoding === 'Big5' }">
            Big5 (繁体中文)
          </el-dropdown-item>
          <el-dropdown-item command="ISO-8859-1" :class="{ 'is-active-enc': encoding === 'ISO-8859-1' }">
            ISO-8859-1 (西欧 Latin-1)
          </el-dropdown-item>
          <el-dropdown-item command="Windows-1252" :class="{ 'is-active-enc': encoding === 'Windows-1252' }">
            Windows-1252 (ANSI)
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowDown, Delete, Document, FullScreen, Rank } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'

const { effectiveDark: isDark } = useTheme()

defineProps({
  snippetVisible: { type: Boolean, default: false },
  isSplit: { type: Boolean, default: false },
  allowSplit: { type: Boolean, default: true },
  fontSize: { type: Number, default: 14 },
  terminalTone: { type: String, default: 'paper' }, // 'paper' | 'hybrid'
  encoding: { type: String, default: 'UTF-8' }
})

defineEmits([
  'clear',
  'toggle-snippets',
  'toggle-split',
  'change-font-size',
  'toggle-fullscreen',
  'toggle-tone',
  'open-theme',
  'change-encoding'
])
</script>

<style scoped>
.terminal-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 8px;
  border-radius: var(--ws-radius-xs);
  background: var(--ws-bg-input);
  border: 1px solid var(--ws-border);
  color: var(--ws-text-regular);
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
  user-select: none;
  line-height: 1.4;
}
.tool-btn:hover {
  background: var(--ws-bg-hover);
  border-color: var(--ws-border-hover);
  color: var(--ws-text-primary);
}
.tool-btn.is-active {
  background: var(--ws-accent-bg);
  border-color: var(--ws-accent);
  color: var(--ws-accent);
  font-weight: 600;
}
.tool-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.tool-btn-caret {
  font-size: 10px;
  margin-left: 1px;
}
:deep(.is-active-enc) {
  color: var(--el-color-primary) !important;
  font-weight: 600;
}
.tool-group {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--ws-border);
  border-radius: var(--ws-radius-xs);
  padding: 1px;
  background: var(--ws-bg-input);
}
.tool-btn-compact {
  padding: 1px 4px;
  border: none;
  background: transparent;
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--ws-text-muted);
}
.tool-btn-compact:hover {
  color: var(--ws-text-primary);
  background: var(--ws-bg-hover);
}
.font-size-label {
  font-size: 10px;
  color: var(--ws-text-subtle);
  padding: 0 4px;
}
@media (max-width: 600px) {
  .tool-btn-text {
    display: none;
  }
}
</style>
