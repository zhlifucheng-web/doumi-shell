<template>
  <div class="share-terminal-page" :class="{ 'is-dark': effectiveDark }">
    <!-- 顶部状态导航条 -->
    <header class="share-header">
      <div class="header-left">
        <div class="brand-tag">
          <span class="brand-dot"></span>
          <span class="brand-title">Doumi Shell</span>
          <span class="brand-sub">会话共享</span>
        </div>

        <div v-if="shareInfo && !loading && !error" class="session-info">
          <span class="server-title" :title="shareInfo.label">{{ shareInfo.label }}</span>
          <span
            class="perm-badge"
            :class="shareInfo.readOnly ? 'is-readonly' : 'is-collaborative'"
          >
            {{ shareInfo.readOnly ? '👁️ 只读观看' : '✍️ 实时协同' }}
          </span>
          <span class="mode-badge">tmux</span>
          <span v-if="timeRemainingText" class="expire-badge" :title="`到期时间: ${formatTime(shareInfo.expireAt)}`">
            ⏱️ {{ timeRemainingText }}
          </span>
        </div>
      </div>

      <div class="header-right">
        <!-- 编码选择 -->
        <el-select
          v-model="terminalEncoding"
          size="small"
          class="encoding-select"
          title="选择终端编码"
        >
          <el-option label="UTF-8" value="UTF-8" />
          <el-option label="GBK" value="GBK" />
          <el-option label="GB2312" value="GB2312" />
          <el-option label="ISO-8859-1" value="ISO-8859-1" />
        </el-select>

        <!-- 字体放大/缩小 -->
        <div class="font-controls">
          <button type="button" class="tool-btn icon-btn" title="缩小字体" @click="fontSize = Math.max(12, fontSize - 1)">A-</button>
          <span class="font-indicator">{{ fontSize }}px</span>
          <button type="button" class="tool-btn icon-btn" title="放大字体" @click="fontSize = Math.min(24, fontSize + 1)">A+</button>
        </div>

        <!-- 配色主题切换 -->
        <button
          type="button"
          class="tool-btn"
          :title="`切换配色方案 (${activeTheme.name})`"
          @click="themeDialogVisible = true"
        >
          <span>🎨 {{ activeTheme.name }}</span>
        </button>

        <!-- 页面深浅色模式切换 -->
        <button
          type="button"
          class="tool-btn icon-btn"
          :title="isDark ? '切换浅色模式' : '切换深色模式'"
          @click="toggleTheme"
        >
          <span>{{ isDark ? '🌙' : '☀️' }}</span>
        </button>

        <!-- 全屏切换 -->
        <button
          type="button"
          class="tool-btn icon-btn"
          :title="isFullscreen ? '退出全屏' : '全屏展示'"
          @click="toggleFullscreen"
        >
          <span>{{ isFullscreen ? '⤢' : '⤡' }}</span>
        </button>
      </div>
    </header>

    <!-- 主展示区 -->
    <main class="share-main">
      <!-- 加载中 -->
      <div v-if="loading" class="state-container">
        <el-icon class="is-loading state-icon"><Loading /></el-icon>
        <p class="state-text">正在验证并接入共享终端会话…</p>
      </div>

      <!-- 失效 / 过期 / 错误 -->
      <div v-else-if="error || !shareInfo || shareInfo.expired" class="state-container is-error">
        <div class="error-card">
          <div class="error-icon">⚠️</div>
          <h2 class="error-title">共享会话链接已失效</h2>
          <p class="error-desc">
            {{ error || '该终端共享链接已过期，或已被发起者主动关闭作废。请联系会话发起者重新生成共享链接。' }}
          </p>
          <div class="error-actions">
            <el-button type="primary" @click="router.push('/terminal')">返回主控制台</el-button>
            <el-button @click="loadShare">重新尝试连接</el-button>
          </div>
        </div>
      </div>

      <!-- 终端就绪 -->
      <div v-else class="terminal-wrapper">
        <div v-if="shareInfo.readOnly" class="readonly-banner">
          <span class="banner-icon">ℹ️</span>
          <span>当前会话为<strong>只读观看模式</strong>：实时同步远端屏幕操作与输出，协作者不可输入命令。</span>
        </div>

        <div class="terminal-body">
          <SshTerminal
            ref="terminalComponent"
            :account-id="shareInfo.accountId"
            :terminal-id="shareInfo.terminalId"
            :share-token="shareToken"
            :read-only="shareInfo.readOnly"
            :font-size="fontSize"
            :encoding="terminalEncoding"
          />
        </div>
      </div>
    </main>

    <!-- 配色弹窗 -->
    <TerminalThemeDialog v-model="themeDialogVisible" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import SshTerminal from '@/components/ssh/SshTerminal.vue'
import TerminalThemeDialog from '@/components/ssh/TerminalThemeDialog.vue'
import { getShareSessionInfo } from '@/api/sshConsole'
import { formatExpireDuration } from '@/utils/terminalShareHelper'
import { useTerminalTheme } from '@/composables/useTerminalTheme'
import { useWorkspaceStore } from '@/stores/workspace'
import { storeToRefs } from 'pinia'

const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const { isDark, effectiveDark } = storeToRefs(workspaceStore)
const { activeTheme } = useTerminalTheme()

const shareToken = computed(() => String(route.query.token || '').trim())
const queryEncoding = computed(() => String(route.query.encoding || '').trim())
const loading = ref(true)
const error = ref('')
const shareInfo = ref(null)

const fontSize = ref(14)
const terminalEncoding = ref(String(route.query.encoding || '').trim() || 'UTF-8')
const themeDialogVisible = ref(false)
const isFullscreen = ref(false)
const terminalComponent = ref(null)

let countdownTimer = null
const nowTimestamp = ref(Date.now())

const timeRemainingText = computed(() => {
  if (!shareInfo.value?.expireAt) return ''
  return formatExpireDuration(shareInfo.value.expireAt, nowTimestamp.value)
})

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function toggleTheme() {
  workspaceStore.toggleTheme()
}

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen().then(() => {
      isFullscreen.value = true
    }).catch(() => {})
  } else {
    document.exitFullscreen().then(() => {
      isFullscreen.value = false
    }).catch(() => {})
  }
}

async function loadShare() {
  if (!shareToken.value) {
    loading.value = false
    error.value = '缺少共享会话 Token 参数，请检查打开的链接是否完整。'
    return
  }

  loading.value = true
  error.value = ''
  try {
    const res = await getShareSessionInfo(shareToken.value)
    if (!res || res.expired) {
      error.value = '共享会话链接已失效或已过期。'
      shareInfo.value = null
    } else {
      shareInfo.value = res
      // 优先采用 URL 中的编码参数，若未指定则采用创建分享时保存的编码
      const sharedEnc = queryEncoding.value || res.encoding
      if (sharedEnc) {
        terminalEncoding.value = sharedEnc
      }
      if (res.expired) {
        error.value = '共享会话链接已过期。'
      }
    }
  } catch (err) {
    error.value = err?.message || '获取共享会话信息失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadShare()
  countdownTimer = setInterval(() => {
    nowTimestamp.value = Date.now()
    if (shareInfo.value?.expireAt && shareInfo.value.expireAt <= nowTimestamp.value) {
      shareInfo.value.expired = true
    }
  }, 1000)
})

onBeforeUnmount(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})
</script>

<style scoped>
.share-terminal-page {
  display: flex;
  flex-direction: column;
  width: 100vw;
  height: 100vh;
  background-color: var(--el-bg-color-page, #0d1117);
  color: var(--el-text-color-primary, #e6edf3);
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}

.share-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 16px;
  background: var(--el-bg-color, #161b22);
  border-bottom: 1px solid var(--el-border-color-darker, rgba(255, 255, 255, 0.08));
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.brand-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
}

.brand-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2ea043;
  box-shadow: 0 0 6px rgba(46, 160, 67, 0.6);
}

.brand-title {
  color: var(--el-text-color-primary, #f0f6fc);
}

.brand-sub {
  font-size: 11px;
  color: var(--el-text-color-secondary, #8b949e);
  background: var(--el-fill-color, rgba(255, 255, 255, 0.06));
  padding: 1px 6px;
  border-radius: 10px;
}

.session-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.server-title {
  font-size: 13px;
  font-weight: 500;
  max-width: 320px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--el-text-color-primary, #c9d1d9);
}

.perm-badge {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 12px;
  font-weight: 500;
}

.perm-badge.is-collaborative {
  background: rgba(46, 160, 67, 0.15);
  color: #3fb950;
  border: 1px solid rgba(46, 160, 67, 0.3);
}

.perm-badge.is-readonly {
  background: rgba(210, 153, 34, 0.15);
  color: #d29922;
  border: 1px solid rgba(210, 153, 34, 0.3);
}

.mode-badge {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-darker, rgba(255, 255, 255, 0.08));
  color: var(--el-text-color-secondary, #8b949e);
  font-family: monospace;
}

.expire-badge {
  font-size: 11px;
  color: var(--el-color-info, #8b949e);
  background: var(--el-fill-color-lighter, rgba(255, 255, 255, 0.04));
  padding: 2px 6px;
  border-radius: 4px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.encoding-select {
  width: 105px;
}

.font-controls {
  display: inline-flex;
  align-items: center;
  background: var(--el-fill-color-dark, rgba(255, 255, 255, 0.04));
  border-radius: 4px;
  padding: 2px 4px;
}

.font-indicator {
  font-size: 11px;
  color: var(--el-text-color-secondary, #8b949e);
  padding: 0 4px;
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border-radius: 4px;
  border: 1px solid var(--el-border-color-lighter, rgba(255, 255, 255, 0.1));
  background: var(--el-fill-color-blank, rgba(255, 255, 255, 0.05));
  color: var(--el-text-color-primary, #c9d1d9);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.tool-btn:hover {
  background: var(--el-fill-color, rgba(255, 255, 255, 0.1));
  border-color: var(--el-border-color, rgba(255, 255, 255, 0.2));
}

.tool-btn.icon-btn {
  padding: 0 7px;
  min-width: 28px;
}

.share-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  position: relative;
}

.state-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 16px;
}

.state-icon {
  font-size: 32px;
  color: var(--el-color-primary, #409eff);
}

.state-text {
  font-size: 14px;
  color: var(--el-text-color-secondary, #8b949e);
}

.error-card {
  max-width: 460px;
  padding: 32px;
  background: var(--el-bg-color, #161b22);
  border: 1px solid var(--el-border-color-darker, rgba(255, 255, 255, 0.1));
  border-radius: 10px;
  text-align: center;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.error-icon {
  font-size: 42px;
  margin-bottom: 16px;
}

.error-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 10px;
  color: var(--el-text-color-primary, #f0f6fc);
}

.error-desc {
  font-size: 13px;
  color: var(--el-text-color-secondary, #8b949e);
  line-height: 1.6;
  margin-bottom: 24px;
}

.error-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.terminal-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

.readonly-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(210, 153, 34, 0.12);
  border-bottom: 1px solid rgba(210, 153, 34, 0.25);
  color: #e3b341;
  font-size: 12px;
  padding: 6px 16px;
}

.banner-icon {
  font-size: 14px;
}

.terminal-body {
  flex: 1;
  min-height: 0;
  height: 100%;
  position: relative;
}
</style>
