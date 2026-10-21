<template>
  <el-scrollbar class="page-scroll"><div class="configuration">
    <h2>配置导入导出</h2>
    <p>迁移分组、主机、账户凭据、脚本、定时任务和账户级监控配置。可选择加密或明文备份，导入时追加到现有配置。</p>
    <el-alert title="导入的定时任务默认暂停，请检查执行账户和 cron 后再启用。" type="info" :closable="false" />
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="导出方式">
        <el-switch v-model="encrypted" active-text="加密备份" inactive-text="明文备份" />
      </el-form-item>
      <el-alert v-if="!encrypted" title="明文备份包含 SSH 密码、私钥等账户凭据，请妥善保管。" type="warning" :closable="false" />
      <el-form-item v-if="encrypted" label="备份密码（至少 8 个字符，请妥善保存）">
        <el-input v-model="password" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <el-button type="primary" :loading="busy === 'export'" :disabled="!!busy" @click="download">{{ encrypted ? '导出加密备份' : '导出明文备份' }}</el-button>
      <el-divider />
      <el-form-item label="选择备份文件">
        <input ref="fileInput" type="file" accept=".json,application/json" :disabled="!!busy" @change="selectFile" />
      </el-form-item>
      <p v-if="filename">{{ filename }}</p>
      <el-alert v-if="archive && archive.algorithm === 'NONE'" title="已识别为明文备份，导入无需密码。" type="info" :closable="false" />
      <el-form-item v-if="archive && archive.algorithm !== 'NONE'" label="导入密码">
        <el-input v-model="importPassword" type="password" show-password autocomplete="off" />
      </el-form-item>
      <el-button type="primary" :loading="busy === 'import'" :disabled="!archive || !!busy" @click="restore">导入配置</el-button>
    </el-form>
    <el-alert v-if="result" :title="result" type="success" :closable="false" />
    <el-divider />
    <h3>退出与卸载 Doumi Shell</h3>
    <p>停止本机后台程序：右键系统托盘图标，选择 Exit Doumi Shell。关闭浏览器只关闭页面，后台脚本与计划任务仍继续运行。</p>
    <p>卸载前先退出程序，然后在 Windows“设置 → 应用 → 已安装的应用”中卸载 Doumi Shell，或使用开始菜单的 Uninstall Doumi Shell。卸载保留用户配置和密钥。</p>
  </div></el-scrollbar>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportConfiguration, importConfiguration } from '@/api/sshConsole'
const password = ref('')
const importPassword = ref('')
const encrypted = ref(true)
const busy = ref('')
const archive = ref(null)
const filename = ref('')
const result = ref('')
const fileInput = ref(null)
function validPassword(value) {
  if (value.length >= 8 && value.length <= 1024) return true
  ElMessage.warning('请输入至少 8 个字符的备份密码')
  return false
}
async function selectFile(event) {
  archive.value = null
  importPassword.value = ''
  filename.value = ''
  const file = event.target.files?.[0]
  if (!file) return
  if (file.size > 20 * 1024 * 1024) return ElMessage.error('备份文件不能超过 20 MB')
  try {
    const data = JSON.parse(await file.text())
    if (data.format !== 'doumi-backup' || data.version !== 1) throw new Error('备份文件格式或版本不支持')
    archive.value = data
    filename.value = file.name
  } catch (error) { ElMessage.error(error.message || '无法读取备份文件') }
}
async function download() {
  if (encrypted.value && !validPassword(password.value)) return
  busy.value = 'export'
  try {
    const data = await exportConfiguration(encrypted.value ? password.value : undefined, encrypted.value)
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `doumi-shell-backup-${new Date().toISOString().replace(/[:.]/g, '-')}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    password.value = ''
  } finally { busy.value = '' }
}
async function restore() {
  if (!archive.value || (archive.value.algorithm !== 'NONE' && !validPassword(importPassword.value))) return
  busy.value = 'import'
  try {
    const counts = await importConfiguration(archive.value.algorithm === 'NONE' ? undefined : importPassword.value, archive.value)
    result.value = `已导入 ${counts.groups} 个分组、${counts.hosts} 台主机、${counts.accounts} 个账户、${counts.scripts} 个脚本。`
    if (counts.monitorConfigs) result.value += ` 已恢复 ${counts.monitorConfigs} 个账户的监控配置。`
    importPassword.value = ''
    archive.value = null
    filename.value = ''
    fileInput.value.value = ''
    window.dispatchEvent(new Event('doumi-configuration-imported'))
  } finally { busy.value = '' }
}
</script>

<style scoped>
.configuration { max-width: 760px; min-height: 100%; box-sizing: border-box;  padding: 24px 32px; margin: auto; }
p { color: var(--el-text-color-secondary); line-height: 1.7; }
.el-form { margin: 24px 0; }
</style>
