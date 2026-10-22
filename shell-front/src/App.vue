<template>
  <div id="app" class="app-root" :style="backgroundStyle">
    <!-- ================= 顶部全局导航栏 (TOP BAR 48px) ================= -->
    <header class="app-header">
      <!-- 左侧：品牌 Logo 与当前连接上下文 -->
      <div class="header-left">
        <div class="brand-group">
          <div class="brand-icon">
            <svg class="brand-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <polyline points="4 17 10 11 4 5"></polyline>
              <line x1="12" y1="19" x2="20" y2="19"></line>
            </svg>
          </div>
          <span class="brand-title">Doumi Shell</span>
        </div>

        <div class="header-divider"></div>

        <!-- 当前主机上下文徽章 (面包屑式) -->
        <div v-if="managedServer" class="server-badge" :title="serverTitle">
          <span class="online-pulse-dot"></span>
          <span class="server-cluster">{{ managedServer.group || '默认分组' }} /</span>
          <span class="server-name">
            <el-icon class="server-icon"><Monitor /></el-icon>
            {{ managedServer.name }}
          </span>
          <span class="server-ip font-mono-code">{{ managedServer.address }}{{ managedServer.port ? `:${managedServer.port}` : '' }}</span>
        </div>
        <div v-else class="server-badge server-badge--empty">
          <span class="online-pulse-dot"></span>
          <span class="server-name">Doumi Shell 就绪 · 请在左侧连接主机</span>
        </div>
      </div>

      <!-- 右侧：功能路由导航与主题切换 -->
      <div class="header-right">
        <!-- 路由切换胶囊组 (Segmented Control) -->
        <nav class="nav-segmented" aria-label="主导航">
          <router-link
            v-for="item in navigation"
            :key="item.path"
            :to="item.path"
            class="nav-segment-item"
          >
            {{ item.label }}
          </router-link>
        </nav>

        <div class="header-divider"></div>

        <!-- AI 助手入口 -->
        <button
          type="button"
          class="ai-copilot-btn"
          title="打开 AI Copilot · Ctrl + Shift + K"
          @click="aiDialog = true"
        >
          <span class="ai-sparkle">✨</span>
          <span>AI Copilot</span>
          <span class="ai-status-dot"></span>
        </button>

        <!-- 极速主题切换 (深色 / 浅色 1-Click 切换) -->
        <el-dropdown trigger="contextmenu" @command="onThemeCommand">
          <button
            type="button"
            class="theme-toggle-btn"
            :title="`当前: ${effectiveDark ? '深色' : '浅色'} (点击直接切换，右键设置高级)`"
            @click="toggleThemeDirect"
          >
            <span class="theme-icon">{{ effectiveDark ? '🌙' : '☀️' }}</span>
            <span class="theme-text">{{ effectiveDark ? '深色' : '浅色' }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="light" :class="{ 'is-active-theme': preference === 'light' }">
                <el-icon><Sunny /></el-icon>
                日间浅色 (Paper Light)
              </el-dropdown-item>
              <el-dropdown-item command="dark" :class="{ 'is-active-theme': preference === 'dark' }">
                <el-icon><Moon /></el-icon>
                极客深色 (Obsidian Dark)
              </el-dropdown-item>
              <el-dropdown-item command="system" :class="{ 'is-active-theme': preference === 'system' }">
                <el-icon><Monitor /></el-icon>
                跟随系统
              </el-dropdown-item>
              <el-dropdown-item command="terminalTheme" divided>
                <el-icon><Brush /></el-icon>
                终端与 Zsh 配色方案
              </el-dropdown-item>
              <el-dropdown-item command="background">
                自定义背景壁纸
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <!-- 帮助文档 -->
        <router-link to="/help" class="about-btn" title="帮助与使用教程">
          <el-icon><QuestionFilled /></el-icon>
        </router-link>

        <!-- 关于 -->
        <router-link to="/about" class="about-btn" title="关于 Doumi Shell">
          <el-badge is-dot :hidden="!hasUpdate">
            <el-icon><InfoFilled /></el-icon>
          </el-badge>
        </router-link>
      </div>
    </header>

    <!-- ================= 主体工作区 ================= -->
    <main class="app-main">
      <router-view v-slot="{ Component }">
        <keep-alive><component :is="Component" /></keep-alive>
      </router-view>
    </main>

    <!-- ================= 现代 WebShell 底部专业状态条 ================= -->
    <AppStatusBar
      :cols="workspaceStore.terminalCols"
      :rows="workspaceStore.terminalRows"
      :session-active="workspaceStore.terminalConnected"
      @open-transfers="onOpenTransfers"
      @open-ai="aiDialog = true"
    />

    <ThemeBackground v-model="backgroundDialog" />
    <TerminalThemeDialog v-model="workspaceStore.terminalThemeOpen" />
    <AiAssistant v-model="aiDialog" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch, watchEffect } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { ArrowDown, Brush, InfoFilled, MagicStick, Monitor, Moon, QuestionFilled, Sunny } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import { useWorkspaceStore } from '@/stores/workspace'
import { useBackground } from '@/composables/useBackground'
import ThemeBackground from '@/components/ThemeBackground.vue'
import TerminalThemeDialog from '@/components/ssh/TerminalThemeDialog.vue'
import AiAssistant from '@/components/AiAssistant.vue'
import AppStatusBar from '@/components/AppStatusBar.vue'
import { useReleaseStore } from '@/stores/release'

const router = useRouter()
const { background, style: backgroundStyle } = useBackground()
const workspaceStore = useWorkspaceStore()
const { managedServer } = storeToRefs(workspaceStore)
const releaseStore = useReleaseStore()
const { latest: latestRelease } = storeToRefs(releaseStore)
const { preference, effectiveDark, themeLabel, setPreference } = useTheme()

// Teleported dialogs and menus share the same background settings as the workspace.
watchEffect(() => {
  const root = document.documentElement
  root.classList.toggle('doumi-custom-background', background.value.enabled)
  for (const [name, value] of Object.entries(backgroundStyle.value)) {
    root.style.setProperty(name, value)
  }
})

const backgroundDialog = ref(false)
const aiDialog = ref(false)
const AI_AUTO_OPEN_KEY = 'doumi-ai-auto-open'
const hasUpdate = computed(() => Boolean(latestRelease.value?.newer))
releaseStore.check().catch(() => { /* The About page reports failures and always offers manual downloads. */ })

function toggleThemeDirect() {
  setPreference(effectiveDark.value ? 'light' : 'dark')
}

function onGlobalKeydown(event) {
  if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'k' && !event.altKey && !event.metaKey) {
    event.preventDefault()
    aiDialog.value = !aiDialog.value
  }
}
watch(() => workspaceStore.aiOpen, (open) => {
  aiDialog.value = open
})
watch(aiDialog, (val) => {
  workspaceStore.aiOpen = val
})

function onContextMenu(event) {
  if (!(event.target instanceof Element) || !event.target.closest('.ssh-terminal-wrap')) {
    event.preventDefault()
  }
}

function onOpenTransfers() {
  void router.push('/terminal')
}

onMounted(() => {
  try {
    aiDialog.value = localStorage.getItem(AI_AUTO_OPEN_KEY) === 'true'
  } catch {
    /* 默认不自动打开 */
  }
  window.addEventListener('keydown', onGlobalKeydown)
  document.addEventListener('contextmenu', onContextMenu)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  document.removeEventListener('contextmenu', onContextMenu)
})

const serverTitle = computed(() =>
  managedServer.value
    ? `${managedServer.value.username ? managedServer.value.username + '@' : ''}${managedServer.value.name} · ${managedServer.value.address}:${managedServer.value.port}`
    : ''
)

const navigation = [
  { path: '/terminal', label: '终端与监控' },
  { path: '/scripts', label: '脚本任务' },
  { path: '/processes', label: '进程管理' },
  { path: '/disk-usage', label: '存储管理' },
  { path: '/data-management', label: '数据管理' }
]

function onThemeCommand(cmd) {
  if (cmd === 'background') {
    backgroundDialog.value = true
    return
  }
  if (cmd === 'terminalTheme') {
    workspaceStore.terminalThemeOpen = true
    return
  }
  setPreference(cmd)
}
</script>

<style scoped>
.app-root {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: var(--ws-bg-root);
  background-image: var(--doumi-background-image, none);
  background-position: center;
  background-size: cover;
  background-repeat: no-repeat;
  background-attachment: fixed;
  color: var(--ws-text-primary);
  font-family: var(--font-sans);
  overflow: hidden;
  transition: background-color var(--ws-transition-normal), color var(--ws-transition-normal);
}

/* ================= 1. 顶部全局导航栏 (48px) ================= */
.app-header {
  height: 48px;
  min-height: 48px;
  background-color: var(--ws-bg-header);
  border-bottom: 1px solid var(--ws-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  gap: 16px;
  z-index: 30;
  flex-shrink: 0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: background-color var(--ws-transition-normal), border-color var(--ws-transition-normal);
}

/* 1.1 左侧品牌与主机面包屑 */
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex-shrink: 0;
}
.brand-group {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 14px;
  letter-spacing: -0.2px;
  color: var(--ws-text-primary);
}
.brand-icon {
  width: 26px;
  height: 26px;
  border-radius: var(--ws-radius-md);
  background: var(--ws-accent-gradient);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  box-shadow: var(--ws-accent-shadow);
}
.brand-svg {
  width: 15px;
  height: 15px;
}
.brand-title {
  white-space: nowrap;
}
.brand-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: var(--ws-radius-xs);
  background: var(--ws-accent-bg);
  color: var(--ws-accent);
  border: 1px solid var(--ws-accent-border);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.header-divider {
  width: 1px;
  height: 16px;
  background-color: var(--ws-border);
}

.server-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--ws-radius-sm);
  background: var(--ws-bg-hover);
  border: 1px solid var(--ws-border);
  font-size: 12px;
  color: var(--ws-text-regular);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.server-cluster {
  color: var(--ws-text-muted);
}
.server-name {
  font-weight: 600;
  color: var(--ws-text-primary);
  display: flex;
  align-items: center;
  gap: 4px;
}
.server-icon {
  font-size: 13px;
  color: var(--ws-accent);
}
.server-ip {
  font-size: 11px;
  color: var(--ws-text-subtle);
}

/* 1.3 右侧导航与功能操作 */
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.nav-segmented {
  display: flex;
  align-items: center;
  gap: 2px;
  background: var(--ws-bg-hover);
  padding: 2px;
  border-radius: var(--ws-radius-md);
  border: 1px solid var(--ws-border);
}
.nav-segment-item {
  text-decoration: none;
  font-size: 12px;
  font-weight: 500;
  color: var(--ws-text-muted);
  padding: 4px 10px;
  border-radius: var(--ws-radius-sm);
  transition: all var(--ws-transition-fast);
  white-space: nowrap;
}
.nav-segment-item:hover {
  color: var(--ws-text-primary);
}
.nav-segment-item.router-link-active {
  background: var(--ws-bg-card);
  color: var(--ws-accent);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

/* AI Copilot 按钮 */
.ai-copilot-btn {
  height: 30px;
  padding: 0 10px;
  border-radius: var(--ws-radius-sm);
  background: var(--ws-accent-bg);
  border: 1px solid var(--ws-accent-border);
  color: var(--ws-accent);
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
}
.ai-copilot-btn:hover {
  background: var(--ws-accent);
  color: #ffffff;
  box-shadow: var(--ws-accent-shadow);
}
.ai-sparkle {
  font-size: 13px;
}
.ai-status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ws-color-online);
}

/* 主题直切按钮 (1-Click) */
.theme-toggle-btn {
  height: 30px;
  padding: 0 10px;
  border-radius: var(--ws-radius-sm);
  background: var(--ws-bg-hover);
  border: 1px solid var(--ws-border);
  color: var(--ws-text-regular);
  font-size: 12px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
}
.theme-toggle-btn:hover {
  background: var(--ws-bg-card);
  border-color: var(--ws-border-hover);
  color: var(--ws-text-primary);
}
.theme-icon {
  font-size: 13px;
}

.about-btn {
  height: 30px;
  width: 30px;
  border-radius: var(--ws-radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ws-text-muted);
  text-decoration: none;
  transition: all var(--ws-transition-fast);
}
.about-btn:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
}

/* ================= 2. 主体工作区 ================= */
.app-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: var(--ws-bg-root);
}
</style>
