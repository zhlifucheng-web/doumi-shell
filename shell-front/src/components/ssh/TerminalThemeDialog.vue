<template>
  <el-dialog
    v-model="visible"
    title="🎨 终端与 Zsh 配色管理"
    width="940px"
    class="terminal-theme-dialog"
    destroy-on-close
    append-to-body
  >
    <div class="theme-dialog-layout">
      <!-- 左侧：预设选择与导入 -->
      <div class="theme-controls-pane">
        <!-- 深色/浅色配色方案独立绑定切换条 -->
        <div class="mode-binding-banner">
          <div class="mode-binding-header">
            <span class="mode-binding-title">配置绑定目标：</span>
            <el-radio-group v-model="configuringMode" size="small" class="mode-radios">
              <el-radio-button label="dark">
                🌙 深色模式配色 ({{ darkTheme.name }})
              </el-radio-button>
              <el-radio-button label="light">
                ☀️ 浅色模式配色 ({{ lightTheme.name }})
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="mode-binding-desc">
            <span>当前系统：<strong>{{ effectiveDark ? '深色模式' : '浅色模式' }}</strong>。切换网页深/浅色时，终端将自动切换至对应设置过的独立配色方案。</span>
          </div>
        </div>

        <el-tabs v-model="activeTab" class="theme-tabs">
          <!-- Tab 1: 内置主流经典配色 -->
          <el-tab-pane label="经典预设" name="presets">
            <!-- 快捷分类筛选 -->
            <div class="filter-bar">
              <span class="filter-label">筛选:</span>
              <button
                type="button"
                class="filter-pill"
                :class="{ 'is-active': categoryFilter === 'all' }"
                @click="categoryFilter = 'all'"
              >
                全部 ({{ allThemes.length }})
              </button>
              <button
                type="button"
                class="filter-pill"
                :class="{ 'is-active': categoryFilter === 'dark' }"
                @click="categoryFilter = 'dark'"
              >
                深色系 (🌙)
              </button>
              <button
                type="button"
                class="filter-pill"
                :class="{ 'is-active': categoryFilter === 'light' }"
                @click="categoryFilter = 'light'"
              >
                浅色系 (☀️)
              </button>
              <button
                v-if="customThemes.length"
                type="button"
                class="filter-pill"
                :class="{ 'is-active': categoryFilter === 'custom' }"
                @click="categoryFilter = 'custom'"
              >
                我的导入 ({{ customThemes.length }})
              </button>
            </div>

            <div class="presets-scroll-grid">
              <div
                v-for="item in filteredThemes"
                :key="item.id"
                class="preset-card"
                :class="{
                  'is-selected': item.id === previewTheme.id,
                  'is-dark-target': configuringMode === 'dark' && item.id === darkThemeId,
                  'is-light-target': configuringMode === 'light' && item.id === lightThemeId
                }"
                @click="onSelectTheme(item)"
              >
                <div class="preset-card-header">
                  <div class="preset-title-wrap">
                    <span class="preset-name">{{ item.name }}</span>
                    <span class="preset-tag" :class="item.category">{{ item.tag || '主题' }}</span>
                  </div>
                  <div class="preset-status-badges">
                    <span v-if="item.id === darkThemeId" class="binding-badge badge-dark" title="已设为深色模式下的配色">🌙 深色绑定</span>
                    <span v-if="item.id === lightThemeId" class="binding-badge badge-light" title="已设为浅色模式下的配色">☀️ 浅色绑定</span>
                    <span v-if="item.id === activeThemeId" class="active-badge" title="当前终端正在生效">✓ 生效中</span>
                  </div>
                  <button
                    v-if="item.id.startsWith('imported-')"
                    type="button"
                    class="del-btn"
                    title="删除此自定义主题"
                    @click.stop="onDeleteTheme(item.id)"
                  >
                    ✕
                  </button>
                </div>

                <!-- 调色板色卡条 -->
                <div class="color-palette-strip" :style="{ backgroundColor: item.background }">
                  <span class="swatch" :style="{ backgroundColor: item.red }" title="Red" />
                  <span class="swatch" :style="{ backgroundColor: item.green }" title="Green" />
                  <span class="swatch" :style="{ backgroundColor: item.yellow }" title="Yellow" />
                  <span class="swatch" :style="{ backgroundColor: item.blue }" title="Blue" />
                  <span class="swatch" :style="{ backgroundColor: item.magenta }" title="Magenta" />
                  <span class="swatch" :style="{ backgroundColor: item.cyan }" title="Cyan" />
                  <span class="swatch" :style="{ backgroundColor: item.foreground }" title="Foreground" />
                </div>
              </div>
            </div>
          </el-tab-pane>

          <!-- Tab 2: 导入 Zsh / 终端配色 -->
          <el-tab-pane label="导入配色 (iTerm2/Gogh/JSON)" name="import">
            <div class="import-pane-content">
              <div
                class="drop-zone"
                :class="{ 'is-dragover': isDragOver }"
                @dragover.prevent="isDragOver = true"
                @dragleave.prevent="isDragOver = false"
                @drop.prevent="onDropFile"
                @click="triggerFileInput"
              >
                <input
                  ref="fileInputRef"
                  type="file"
                  accept=".itermcolors,.sh,.zsh,.json,.txt"
                  class="hidden-file-input"
                  @change="onFileSelected"
                />
                <div class="drop-zone-icon">📁</div>
                <div class="drop-zone-text">点击或将配色文件拖拽至此处</div>
                <div class="drop-zone-hint">支持 <strong>.itermcolors</strong> (iTerm2)、<strong>.sh</strong> (Gogh 脚本)、<strong>.json</strong> (VS Code / Windows Terminal)</div>
              </div>

              <div class="paste-divider">
                <span>或直接粘贴配色文本代码</span>
              </div>

              <div class="paste-zone">
                <el-input
                  v-model="pasteContent"
                  type="textarea"
                  :rows="6"
                  placeholder="可直接粘贴 iTerm2 plist XML、Gogh export COLOR_01=...、JSON 或 Xresources 配色文本…"
                  @input="onPasteInput"
                />
                <div v-if="detectedFormat" class="detect-badge">
                  <span class="detect-icon">✓</span>
                  <span>已识别格式: <strong>{{ detectedFormat }}</strong></span>
                </div>
              </div>

              <div class="import-actions">
                <el-button
                  type="primary"
                  :disabled="!canImport"
                  @click="onConfirmImport"
                >
                  导入并应用到终端
                </el-button>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- 右侧：实时 Zsh 终端效果预览窗口 -->
      <div class="theme-preview-pane">
        <div class="preview-header-bar">
          <span class="preview-title">实时终端视觉预览</span>
          <span class="preview-theme-pill">{{ previewTheme.name }}</span>
        </div>

        <div
          class="mock-terminal-window"
          :style="{
            backgroundColor: previewTheme.background,
            color: previewTheme.foreground
          }"
        >
          <!-- 拟真终端顶部控制红绿灯 -->
          <div class="mock-titlebar">
            <div class="window-dots">
              <span class="dot close" />
              <span class="dot min" />
              <span class="dot max" />
            </div>
            <span class="window-title">zsh — pino@doumi-server: ~/workspace</span>
          </div>

          <!-- 模拟 Zsh / Oh-My-Zsh 交互输出 -->
          <div class="mock-body font-mono-code">
            <div class="prompt-line">
              <span class="p-arrow" :style="{ color: previewTheme.green }">➜</span>
              <span class="p-dir" :style="{ color: previewTheme.cyan }">~/workspace/web-shell</span>
              <span class="p-git" :style="{ color: previewTheme.blue }">git:(<span :style="{ color: previewTheme.magenta }">main</span>)</span>
              <span class="p-dirty" :style="{ color: previewTheme.yellow }">✗</span>
              <span class="p-cmd">ls -la</span>
            </div>

            <div class="ls-output">
              <div class="ls-row">
                <span class="f-perm">drwxr-xr-x</span>
                <span class="f-owner">pino</span>
                <span class="f-name" :style="{ color: previewTheme.blue, fontWeight: 600 }">shell-front/</span>
              </div>
              <div class="ls-row">
                <span class="f-perm">drwxr-xr-x</span>
                <span class="f-owner">pino</span>
                <span class="f-name" :style="{ color: previewTheme.blue, fontWeight: 600 }">node_modules/</span>
              </div>
              <div class="ls-row">
                <span class="f-perm">-rw-r--r--</span>
                <span class="f-owner">pino</span>
                <span class="f-name" :style="{ color: previewTheme.green }">package.json</span>
              </div>
              <div class="ls-row">
                <span class="f-perm">-rw-r--r--</span>
                <span class="f-owner">pino</span>
                <span class="f-name">vite.config.js</span>
              </div>
            </div>

            <div class="prompt-line prompt-line-2">
              <span class="p-arrow" :style="{ color: previewTheme.green }">➜</span>
              <span class="p-dir" :style="{ color: previewTheme.cyan }">web-shell</span>
              <span class="p-cmd">pnpm build && ./status.sh</span>
            </div>

            <div class="status-output">
              <div>
                <span :style="{ color: previewTheme.brightGreen }">✓ built in 6.96s</span>
                <span :style="{ color: previewTheme.brightBlack }"> (dist/ ready)</span>
              </div>
              <div class="color-palette-demo">
                <span :style="{ color: previewTheme.red }">● ERR</span>
                <span :style="{ color: previewTheme.yellow }">● WARN</span>
                <span :style="{ color: previewTheme.green }">● OK</span>
                <span :style="{ color: previewTheme.cyan }">● INFO</span>
                <span :style="{ color: previewTheme.magenta }">● SYS</span>
                <span :style="{ color: previewTheme.blue }">● NET</span>
              </div>
            </div>

            <!-- 光标效果 -->
            <div class="prompt-line prompt-line-cursor">
              <span class="p-arrow" :style="{ color: previewTheme.green }">➜</span>
              <span class="p-dir" :style="{ color: previewTheme.cyan }">web-shell</span>
              <span class="mock-cursor" :style="{ backgroundColor: previewTheme.cursor }" />
            </div>
          </div>
        </div>

        <!-- 调色板 HEX 检查清单 -->
        <div class="theme-hex-breakdown">
          <div class="hex-item">
            <span class="hex-label">背景 (BG)</span>
            <span class="hex-val font-mono-code">{{ previewTheme.background }}</span>
          </div>
          <div class="hex-item">
            <span class="hex-label">前景 (FG)</span>
            <span class="hex-val font-mono-code">{{ previewTheme.foreground }}</span>
          </div>
          <div class="hex-item">
            <span class="hex-label">光标 (Cursor)</span>
            <span class="hex-val font-mono-code">{{ previewTheme.cursor }}</span>
          </div>
          <div class="hex-item">
            <span class="hex-label">强调蓝 (Blue)</span>
            <span class="hex-val font-mono-code">{{ previewTheme.blue }}</span>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="onApplyCurrent">
          绑定为【{{ configuringMode === 'dark' ? '深色' : '浅色' }}模式】配色
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useTerminalTheme } from '@/composables/useTerminalTheme'
import { useTheme } from '@/composables/useTheme'
import { parseTerminalColorScheme } from '@/utils/terminalThemeParser'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'applied'])

const visible = computed({
  get: () => props.modelValue,
  set: val => emit('update:modelValue', val)
})

const { effectiveDark } = useTheme()
const {
  activeThemeId,
  activeTheme,
  darkThemeId,
  lightThemeId,
  darkTheme,
  lightTheme,
  allThemes,
  customThemes,
  setTheme,
  setDarkTheme,
  setLightTheme,
  importTheme,
  removeCustomTheme
} = useTerminalTheme()

const configuringMode = ref(effectiveDark.value ? 'dark' : 'light')
const categoryFilter = ref('all')
const activeTab = ref(typeof window !== 'undefined' ? (new URLSearchParams(window.location.search).get('themeTab') || 'presets') : 'presets')
const previewTheme = ref({ ...(effectiveDark.value ? darkTheme.value : lightTheme.value) })
const isDragOver = ref(false)
const fileInputRef = ref(null)
const pasteContent = ref('')
const detectedFormat = ref('')
const parsedDraft = ref(null)

const filteredThemes = computed(() => {
  if (categoryFilter.value === 'dark') {
    return allThemes.value.filter(t => t.category !== 'light')
  }
  if (categoryFilter.value === 'light') {
    return allThemes.value.filter(t => t.category === 'light')
  }
  if (categoryFilter.value === 'custom') {
    return customThemes.value
  }
  return allThemes.value
})

watch(visible, val => {
  if (val) {
    configuringMode.value = effectiveDark.value ? 'dark' : 'light'
    previewTheme.value = { ...(effectiveDark.value ? darkTheme.value : lightTheme.value) }
    pasteContent.value = ''
    detectedFormat.value = ''
    parsedDraft.value = null
  }
})

watch(configuringMode, mode => {
  const target = mode === 'dark' ? darkTheme.value : lightTheme.value
  if (target) {
    previewTheme.value = { ...target }
  }
})

function onSelectTheme(theme) {
  previewTheme.value = { ...theme }
}

function onDeleteTheme(id) {
  removeCustomTheme(id)
  previewTheme.value = { ...(configuringMode.value === 'dark' ? darkTheme.value : lightTheme.value) }
  ElMessage.success('已删除自定义配色')
}

function onApplyCurrent() {
  const selectedTheme = previewTheme.value
  if (configuringMode.value === 'dark') {
    setDarkTheme(selectedTheme.id)
    ElMessage.success(`已绑定为【深色模式】终端配色: ${selectedTheme.name}`)
  } else {
    setLightTheme(selectedTheme.id)
    ElMessage.success(`已绑定为【浅色模式】终端配色: ${selectedTheme.name}`)
  }
  emit('applied', selectedTheme)
  visible.value = false
}

// 导入逻辑
function triggerFileInput() {
  fileInputRef.value?.click()
}

function onFileSelected(event) {
  const file = event.target.files?.[0]
  if (!file) return
  readFileContent(file)
}

function onDropFile(event) {
  isDragOver.value = false
  const file = event.dataTransfer?.files?.[0]
  if (!file) return
  readFileContent(file)
}

function readFileContent(file) {
  const reader = new FileReader()
  reader.onload = e => {
    const text = e.target?.result
    if (typeof text === 'string') {
      pasteContent.value = text
      inspectContent(text, file.name)
    }
  }
  reader.readAsText(file)
}

function onPasteInput() {
  inspectContent(pasteContent.value, '')
}

function inspectContent(content, filename) {
  if (!content.trim()) {
    detectedFormat.value = ''
    parsedDraft.value = null
    return
  }

  const parsed = parseTerminalColorScheme(content, filename)
  if (parsed) {
    parsedDraft.value = parsed
    previewTheme.value = { ...parsed }
    if (content.includes('plist')) detectedFormat.value = 'iTerm2 Color Scheme (.itermcolors)'
    else if (content.startsWith('{')) detectedFormat.value = 'VS Code / Windows Terminal JSON'
    else if (content.includes('COLOR_') || content.includes('printf')) detectedFormat.value = 'Gogh / Zsh Shell 脚本'
    else detectedFormat.value = 'Xresources / Terminal 配置文件'
  } else {
    detectedFormat.value = ''
    parsedDraft.value = null
  }
}

const canImport = computed(() => Boolean(parsedDraft.value))

function onConfirmImport() {
  if (!parsedDraft.value) return
  try {
    const saved = importTheme(pasteContent.value, parsedDraft.value.name, configuringMode.value)
    ElMessage.success(`成功导入并绑定为【${configuringMode.value === 'dark' ? '深色' : '浅色'}】配色: ${saved.name}`)
    visible.value = false
  } catch (err) {
    ElMessage.error(err.message || '导入失败')
  }
}
</script>

<style scoped>
.theme-dialog-layout {
  display: flex;
  gap: 20px;
  min-height: 480px;
}

/* 左侧控制区 */
.theme-controls-pane {
  flex: 1.1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.mode-binding-banner {
  margin-bottom: 10px;
  padding: 8px 12px;
  background: var(--ws-bg-card, rgba(255, 255, 255, 0.04));
  border: 1px solid var(--ws-border);
  border-radius: var(--ws-radius-sm);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mode-binding-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.mode-binding-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ws-text-primary);
}
.mode-binding-desc {
  font-size: 11px;
  color: var(--ws-text-muted);
  line-height: 1.4;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 2px 0;
  flex-wrap: wrap;
}
.filter-label {
  font-size: 11px;
  color: var(--ws-text-muted);
}
.filter-pill {
  padding: 2px 8px;
  font-size: 11px;
  border-radius: var(--ws-radius-xs);
  border: 1px solid var(--ws-border);
  background: var(--ws-bg-card);
  color: var(--ws-text-secondary);
  cursor: pointer;
  transition: all var(--ws-transition-fast);
}
.filter-pill:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
}
.filter-pill.is-active {
  background: var(--ws-accent-bg);
  border-color: var(--ws-accent);
  color: var(--ws-accent);
  font-weight: 600;
}

.presets-scroll-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 10px;
  max-height: 430px;
  overflow-y: auto;
  padding: 4px 6px 10px 2px;
}

.preset-card {
  border-radius: var(--ws-radius-sm);
  background: var(--ws-bg-card);
  border: 1px solid var(--ws-border);
  padding: 10px 12px;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
  display: flex;
  flex-direction: column;
  gap: 8px;
  position: relative;
}
.preset-card:hover {
  border-color: var(--ws-accent);
  background: var(--ws-bg-hover);
  transform: translateY(-1px);
}
.preset-card.is-selected {
  border-color: var(--ws-accent);
  box-shadow: 0 0 0 1px var(--ws-accent);
  background: var(--ws-accent-bg);
}

.preset-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.preset-title-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.preset-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ws-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.preset-tag {
  font-size: 10px;
  padding: 1px 4px;
  border-radius: var(--ws-radius-xs);
  background: var(--ws-bg-hover);
  color: var(--ws-text-muted);
}
.preset-tag.dark {
  background: rgba(99, 102, 241, 0.15);
  color: #818cf8;
}
.preset-tag.light {
  background: rgba(245, 158, 11, 0.15);
  color: #d97706;
}

.preset-status-badges {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.binding-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: var(--ws-radius-xs);
}
.binding-badge.badge-dark {
  background: rgba(99, 102, 241, 0.15);
  color: #818cf8;
}
.binding-badge.badge-light {
  background: rgba(245, 158, 11, 0.15);
  color: #d97706;
}

.active-badge {
  font-size: 10px;
  font-weight: 600;
  color: var(--ws-color-online);
  background: rgba(16, 185, 129, 0.15);
  padding: 1px 5px;
  border-radius: var(--ws-radius-xs);
}
.del-btn {
  background: transparent;
  border: none;
  color: var(--ws-text-muted);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
}
.del-btn:hover {
  color: var(--ws-color-danger);
}

.color-palette-strip {
  height: 22px;
  border-radius: var(--ws-radius-xs);
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: 0 6px;
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.swatch {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

/* 导入面板 */
.import-pane-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px;
}
.drop-zone {
  border: 2px dashed var(--ws-border);
  border-radius: var(--ws-radius-md);
  padding: 24px 16px;
  text-align: center;
  cursor: pointer;
  background: var(--ws-bg-input);
  transition: all var(--ws-transition-fast);
}
.drop-zone:hover, .drop-zone.is-dragover {
  border-color: var(--ws-accent);
  background: var(--ws-accent-bg);
}
.hidden-file-input {
  display: none;
}
.drop-zone-icon {
  font-size: 28px;
  margin-bottom: 6px;
}
.drop-zone-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--ws-text-primary);
  margin-bottom: 4px;
}
.drop-zone-hint {
  font-size: 11px;
  color: var(--ws-text-muted);
}

.paste-divider {
  display: flex;
  align-items: center;
  text-align: center;
  font-size: 11px;
  color: var(--ws-text-muted);
}
.paste-divider::before, .paste-divider::after {
  content: '';
  flex: 1;
  border-bottom: 1px solid var(--ws-border);
}
.paste-divider span {
  padding: 0 10px;
}

.paste-zone {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.detect-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ws-color-online);
  padding: 4px 8px;
  border-radius: var(--ws-radius-xs);
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.2);
}
.import-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}

/* 右侧预览区 */
.theme-preview-pane {
  flex: 1.1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--ws-bg-input);
  border-radius: var(--ws-radius-md);
  padding: 12px;
  border: 1px solid var(--ws-border);
}
.preview-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.preview-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ws-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.preview-theme-pill {
  font-size: 12px;
  font-weight: 600;
  color: var(--ws-accent);
  background: var(--ws-accent-bg);
  padding: 2px 8px;
  border-radius: var(--ws-radius-xs);
}

.mock-terminal-window {
  border-radius: var(--ws-radius-md);
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  height: 310px;
}

.mock-titlebar {
  height: 28px;
  background: rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: center;
  padding: 0 10px;
  position: relative;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}
.window-dots {
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}
.dot.close { background: #ff5f56; }
.dot.min { background: #ffbd2e; }
.dot.max { background: #27c93f; }

.window-title {
  position: absolute;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.6);
  pointer-events: none;
}

.mock-body {
  flex: 1;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
}

.prompt-line {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.p-arrow { font-weight: 700; }
.p-dir { font-weight: 600; }
.p-git { font-size: 11px; }
.p-dirty { font-weight: 700; }
.p-cmd { font-weight: 500; margin-left: 2px; }

.ls-output {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: 12px;
  opacity: 0.9;
  font-size: 11px;
}
.ls-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.f-perm { opacity: 0.6; }
.f-owner { opacity: 0.8; }

.status-output {
  padding-left: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.color-palette-demo {
  display: flex;
  gap: 8px;
  font-size: 11px;
  font-weight: 600;
  margin-top: 4px;
}

.mock-cursor {
  width: 7px;
  height: 14px;
  display: inline-block;
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.theme-hex-breakdown {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}
.hex-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px;
  border-radius: var(--ws-radius-xs);
  background: var(--ws-bg-card);
  border: 1px solid var(--ws-border);
  font-size: 11px;
}
.hex-label {
  color: var(--ws-text-muted);
}
.hex-val {
  font-weight: 600;
  color: var(--ws-text-primary);
}
</style>
