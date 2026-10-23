<template>
  <button
    v-if="!state.installed"
    type="button"
    class="pwa-install-btn"
    :disabled="state.installing"
    :aria-busy="state.installing"
    :title="state.available ? '安装 Doumi Shell 应用' : '查看应用安装方法'"
    @click="install"
  >
    <el-icon><Download /></el-icon>
    <span>{{ state.installing ? '正在安装…' : '安装应用' }}</span>
  </button>
  <el-dialog v-model="helpVisible" title="安装 Doumi Shell" width="min(460px, 92vw)" append-to-body>
    <div class="pwa-install-help">
      <p v-if="!secureContext" role="status">当前地址不支持完整 PWA 安装。请通过受信任的 HTTPS 地址或本机 localhost 访问，再尝试安装。</p>
      <p v-if="isIos">在 Safari 中打开本页，点击“分享”，选择“添加到主屏幕”，再点击“添加”。</p>
      <p v-else>在 Chrome 或 Edge 中打开本页，点击地址栏的安装图标，或在浏览器菜单中选择“安装 Doumi Shell”／“将此页面安装为应用”。</p>
      <p v-if="secureContext && !isIos">如果暂时没有安装选项，请等待页面加载完成后重试，或确认浏览器是否已安装此应用。</p>
      <p class="pwa-install-note">安装后可从桌面或应用列表独立打开。终端、文件传输和 AI 功能仍需要连接 Doumi Shell 服务。</p>
    </div>
    <template #footer><el-button @click="helpVisible = false">知道了</el-button></template>
  </el-dialog>
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { Download } from '@element-plus/icons-vue'
import { createPwaInstaller } from '@/utils/pwa'

const state = ref({ installed: false, available: false, installing: false })
const helpVisible = ref(false)
const secureContext = window.isSecureContext
const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent)
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
const installer = createPwaInstaller(window, next => { state.value = next })
onBeforeUnmount(() => installer.dispose())

async function install() {
  if (state.value.installing) return
  if (!await installer.install()) helpVisible.value = true
}
</script>

<style scoped>
.pwa-install-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--ws-border);
  border-radius: 8px;
  color: var(--ws-text-primary);
  background: var(--ws-bg-hover);
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}
.pwa-install-btn:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.pwa-install-btn:disabled { opacity: 0.65; cursor: wait; }
.pwa-install-help { color: var(--ws-text-primary); line-height: 1.7; }
.pwa-install-help p { margin: 0 0 12px; }
.pwa-install-note { color: var(--ws-text-secondary); font-size: 13px; }
@media (pointer: coarse) { .pwa-install-btn { min-height: 44px; } }
</style>
