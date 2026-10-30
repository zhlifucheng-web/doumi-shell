<template>
  <section class="self-update" aria-labelledby="self-update-title">
    <div class="update-heading">
      <h3 id="self-update-title">一键更新</h3>
      <el-tag v-if="state">{{ state.platform }}</el-tag>
    </div>
    <p class="update-hint">由部署机器下载并安装 GitHub 正式版本。更新期间服务会重启，SSH 连接会断开，请先结束文件传输及脚本任务。</p>
    <el-alert v-if="state && !state.supported" :title="state.reason" type="info" :closable="false" show-icon />
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="更新管理密钥">
        <el-input v-model="token" type="password" show-password autocomplete="off" placeholder="输入部署机器的更新管理密钥" :disabled="busy" />
        <p class="field-help">从部署机器数据目录下的 updates/admin-token 文件读取，或使用部署时设置的 DOUMI_UPDATE_TOKEN。密钥仅用于本页操作，不会保存在浏览器中。</p>
      </el-form-item>
      <el-form-item label="GitHub 加速代理（可选）">
        <el-input v-model="proxy" placeholder="例如 https://gh-proxy.com/；留空时直连 GitHub" :disabled="busy || saving" autocomplete="off" />
        <p class="field-help">以“代理前缀 + 完整 GitHub 地址”下载。版本检测直连失败时也会尝试代理，需代理服务支持 GitHub API。</p>
      </el-form-item>
      <div class="update-buttons">
        <el-button :loading="saving" :disabled="busy || !token || !state" @click="save">保存代理设置</el-button>
        <el-button type="primary" :loading="starting" :disabled="busy || !state?.supported || !latest?.newer || !token || proxy !== state?.proxy" @click="install">
          {{ latest?.newer ? `更新至 v${latest.version}` : '暂无可安装更新' }}
        </el-button>
      </div>
    </el-form>
    <div v-if="state && state.phase !== 'IDLE'" class="update-progress" role="status" aria-live="polite">
      <el-alert :title="phase.label" :description="state.message" :type="phase.type" :closable="false" show-icon />
      <el-progress v-if="busy" :percentage="state.progress || 0" :stroke-width="8" />
      <p v-if="busy" class="field-help">服务重启时页面可能暂时失去连接，请保持页面打开。正在安装时请勿再次启动或手动替换程序。</p>
    </div>
    <el-alert v-if="error" class="update-error" :title="error" type="warning" :closable="false" show-icon />
    <el-button v-if="error && !busy" link type="primary" @click="poll">重新获取更新状态</el-button>
  </section>
</template>
<script setup>
import { computed, onMounted, onActivated, onDeactivated, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createUpdateClient, updatePhase } from '@/utils/selfUpdate'
import { appVersion } from '@/utils/versionRefresh'
const props = defineProps({ latest: { type: Object, default: null } })
const emit = defineEmits(['settings-saved'])
const request = createUpdateClient({ base: import.meta.env.VITE_API_BASE || '' })
const state = ref(null), token = ref(''), proxy = ref(''), error = ref('')
const starting = ref(false), saving = ref(false)
const busy = computed(() => starting.value || Boolean(state.value?.busy))
const phase = computed(() => updatePhase(state.value?.phase))
let timer, polling = false, disconnectedAt = 0
async function poll() {
  if (polling) return
  polling = true
  try {
    const next = await request('status')
    if (!state.value) proxy.value = next.proxy || ''
    state.value = next; error.value = ''; disconnectedAt = 0
    if (next.phase === 'SUCCEEDED' && next.currentVersion !== appVersion) {
      const url = new URL(window.location.href); url.searchParams.set('v', next.currentVersion)
      window.location.replace(url.href)
    }
  } catch (e) {
    if (state.value?.busy) {
      disconnectedAt ||= Date.now()
      error.value = Date.now() - disconnectedAt < 20 * 60 * 1000
        ? '服务正在安装或重启，页面会自动重试连接。'
        : '服务长时间未恢复，请到部署机器查看数据目录 updates 下的更新日志及备份。'
    } else error.value = e.message
  } finally { polling = false }
}
async function save() {
  saving.value = true; error.value = ''
  try {
    state.value = await request('settings', { method: 'PUT', token: token.value, body: { proxy: proxy.value } })
    proxy.value = state.value.proxy || ''; ElMessage.success('代理设置已保存'); emit('settings-saved')
  } catch (e) { error.value = e.message }
  finally { saving.value = false }
}
async function install() {
  try {
    await ElMessageBox.confirm(`更新到 v${props.latest.version} 将重启部署机器上的 Doumi Shell，并断开所有 SSH 连接。请确认文件传输和脚本任务已经结束。`, '安装更新', {
      confirmButtonText: '开始更新', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  starting.value = true; error.value = ''
  try { state.value = await request('install', { method: 'POST', token: token.value, body: { version: props.latest.version } }) }
  catch (e) { error.value = e.message; await poll() }
  finally { starting.value = false }
}
function startPolling() { if (!timer) { void poll(); timer = window.setInterval(poll, 2500) } }
function stopPolling() { window.clearInterval(timer); timer = undefined; token.value = '' }
onMounted(startPolling); onActivated(startPolling); onDeactivated(stopPolling); onBeforeUnmount(stopPolling)
</script>
<style scoped>
.self-update{margin-top:24px;padding-top:20px;border-top:1px solid var(--el-border-color)}
.update-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.update-heading h3{margin:0;font-size:17px}
.update-hint,.field-help{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7}.field-help{margin:6px 0 0}
.el-form{margin-top:16px}.el-form-item{margin-bottom:16px}.update-buttons{display:flex;flex-wrap:wrap;gap:10px}.update-buttons .el-button+.el-button{margin-left:0}
.update-progress,.update-error{margin-top:16px}.el-progress{margin-top:14px}
</style>
