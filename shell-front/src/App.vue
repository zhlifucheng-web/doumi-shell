<template>
  <div id="app" class="app-root" :style="backgroundStyle">
    <header class="app-header">
      <div class="app-header__brand">
        <h1>Doumi Shell</h1>
      </div>
      <div v-if="managedServer" class="managed-server" :title="serverTitle" aria-label="当前管理服务器"><el-icon><Monitor /></el-icon><div><strong>{{ managedServer.username ? `${managedServer.username}@` : '' }}{{ managedServer.name }}</strong><small>{{ managedServer.address }}{{ managedServer.address ? `:${managedServer.port}` : '' }}</small></div></div>
      <el-scrollbar class="nav-scroll"><nav class="top-nav" aria-label="主导航">
        <router-link v-for="item in navigation" :key="item.path" :to="item.path">{{ item.label }}</router-link>
      </nav></el-scrollbar>
      <el-tooltip content="打开 AI 助手 · Ctrl + Shift + K" placement="bottom">
        <button class="ai-entry" type="button" aria-label="打开 AI 助手" @click="aiDialog = true">
          <el-icon class="ai-entry__icon"><MagicStick /></el-icon>
          <span>AI 助手</span>
        </button>
      </el-tooltip>
      <router-link to="/about" class="about-link" title="关于 Doumi Shell">
        <el-badge is-dot :hidden="!hasUpdate"><el-icon><InfoFilled /></el-icon><span>关于</span></el-badge>
      </router-link>
      <el-dropdown trigger="click" popper-class="app-theme-dropdown" @command="onThemeCommand">
        <span class="theme-trigger" role="button" tabindex="0">
          <el-icon class="theme-trigger__icon"><component :is="themeTriggerIcon" /></el-icon>
          <span class="theme-trigger__label">{{ themeLabel }}</span>
          <el-icon class="theme-trigger__caret"><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="light" :class="{ 'is-active-theme': preference === 'light' }">
              <el-icon><Sunny /></el-icon>
              浅色
            </el-dropdown-item>
            <el-dropdown-item command="dark" :class="{ 'is-active-theme': preference === 'dark' }">
              <el-icon><Moon /></el-icon>
              深色
            </el-dropdown-item>
            <el-dropdown-item command="system" :class="{ 'is-active-theme': preference === 'system' }">
              <el-icon><Monitor /></el-icon>
              跟随系统
            </el-dropdown-item>
            <el-dropdown-item command="background" divided>自定义主题背景</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>
    <main class="app-main">
      <router-view v-slot="{ Component }">
        <keep-alive><component :is="Component" /></keep-alive>
      </router-view>
    </main>
    <ThemeBackground v-model="backgroundDialog" />
    <AiAssistant v-model="aiDialog" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watchEffect } from 'vue'
import { ArrowDown, InfoFilled, MagicStick, Monitor, Moon, Sunny } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import { managedServer } from '@/composables/useWorkspaceContext'
import { useBackground } from '@/composables/useBackground'
import ThemeBackground from '@/components/ThemeBackground.vue'
import AiAssistant from '@/components/AiAssistant.vue'
import { checkLatestRelease, getLatestRelease, onLatestReleaseChange } from '@/utils/versionRefresh'

const { background, style: backgroundStyle } = useBackground()
// Teleported dialogs and menus share the same background settings as the workspace.
watchEffect(() => {
  const root=document.documentElement
  root.classList.toggle('doumi-custom-background',background.value.enabled)
  for(const [name,value] of Object.entries(backgroundStyle.value))root.style.setProperty(name,value)
})
const backgroundDialog = ref(false)
const aiDialog = ref(false)
const AI_AUTO_OPEN_KEY = 'doumi-ai-auto-open'
const hasUpdate = ref(Boolean(getLatestRelease()?.newer))
onLatestReleaseChange(() => { hasUpdate.value = Boolean(getLatestRelease()?.newer) })
checkLatestRelease().catch(() => { /* The About page reports failures and always offers manual downloads. */ })
function onGlobalKeydown(event) {
  if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'k' && !event.altKey && !event.metaKey) {
    event.preventDefault()
    aiDialog.value = !aiDialog.value
  }
}
onMounted(() => {
  try { aiDialog.value = localStorage.getItem(AI_AUTO_OPEN_KEY) === 'true' } catch { /* 默认不自动打开 */ }
  window.addEventListener('keydown', onGlobalKeydown)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKeydown))
const serverTitle = computed(()=>managedServer.value ? `${managedServer.value.username ? managedServer.value.username+'@' : ''}${managedServer.value.name} · ${managedServer.value.address}:${managedServer.value.port}` : '')

const { preference, themeLabel, setPreference } = useTheme()
const navigation = [
  { path: '/terminal', label: '终端与监控' },
  { path: '/scripts', label: '脚本与定时任务' }, { path: '/processes', label: '进程' }, { path: '/disk-usage', label: '存储管理' },
  { path: '/data-management', label: '数据管理' }
]

const themeTriggerIcon = computed(() => {
  if (preference.value === 'light') return Sunny
  if (preference.value === 'dark') return Moon
  return Monitor
})

function onThemeCommand(cmd) {
  if(cmd==='background'){backgroundDialog.value=true;return}
  setPreference(cmd)
}
</script>

<style scoped>
.app-root {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--doumi-background-image) center / cover no-repeat var(--doumi-background-color);
  --doumi-surface: color-mix(in srgb, var(--el-bg-color) var(--doumi-surface-opacity), transparent);
  font-family: system-ui, -apple-system, 'Microsoft YaHei', sans-serif;
}
.app-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--doumi-surface);
}
.app-header__brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}
.app-header h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.nav-scroll {flex:1;min-width:0}
.top-nav { display: flex; width:max-content; gap: 4px; padding-bottom:4px }
.managed-server {display:flex;align-items:center;gap:8px;max-width:260px;min-width:130px;color:var(--el-color-primary)}
.managed-server>div {display:flex;flex-direction:column;min-width:0;gap:3px}.managed-server strong,.managed-server small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.managed-server strong{font-size:13px}.managed-server small{font-size:11px;color:var(--el-text-color-secondary)}
.top-nav a { white-space: nowrap; text-decoration: none; color: var(--el-text-color-regular); padding: 9px 12px; border-radius: 6px; font-size: 14px; }
.top-nav a:hover { background: var(--el-fill-color-light); }
.top-nav a.router-link-active { background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; }
.about-link { flex-shrink:0; color:var(--el-text-color-regular); text-decoration:none; padding:6px 8px; border-radius:6px; font-size:13px }
.about-link:hover,.about-link.router-link-active { background:var(--el-fill-color-light); color:var(--el-color-primary) }
.about-link .el-badge { display:inline-flex; align-items:center; gap:4px }
.ai-entry { flex-shrink:0; display:inline-flex; align-items:center; gap:7px; padding:6px 4px; border:0; color:var(--el-text-color-primary); background:transparent; font:inherit; font-size:14px; font-weight:600; cursor:pointer; }
.ai-entry:hover,.ai-entry:focus-visible { color:var(--el-color-primary); outline:none; }
.ai-entry__icon { color:var(--el-color-primary); font-size:18px; }
@media (max-width: 1200px) { .app-header { flex-wrap: wrap; } .nav-scroll { order: 3; flex-basis: 100%; } .managed-server{margin-right:auto} }
.tagline {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.app-main {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.theme-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-text-color-regular);
  user-select: none;
}
.theme-trigger:hover,
.theme-trigger:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}
.theme-trigger__icon {
  font-size: 16px;
}
.theme-trigger__caret {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>

<style>
html,
body,
#app {
  margin: 0;
  height: 100%;
}
.app-theme-dropdown .el-dropdown-menu__item.is-active-theme {
  color: var(--el-color-primary);
  font-weight: 600;
}
.page-scroll{height:100%;min-height:0;width:100%}
.el-drawer__body{overflow:hidden}
.app-root .aside,.app-root .ssh-monitor,.app-root .block{background:var(--doumi-surface)}
.app-root .el-tree{background:transparent}
.app-root .el-scrollbar__bar {z-index:10}
/* Editors and terminal retain native scrolling for selection and keyboard support. */
* {scrollbar-width:thin;scrollbar-color:var(--el-text-color-placeholder) transparent}
*::-webkit-scrollbar{width:6px;height:6px}
*::-webkit-scrollbar-thumb{background:var(--el-text-color-placeholder);border-radius:6px}
*::-webkit-scrollbar-track{background:transparent}
</style>
<style src="./theme-background.css"></style>
