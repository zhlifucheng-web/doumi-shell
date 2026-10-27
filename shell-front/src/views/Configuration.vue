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
    <el-alert v-if="result" :title="result" type="success" :closable="false" style="margin-bottom: 24px;" />

    <el-divider />

    <!-- FinalShell Import Section -->
    <div class="finalshell-section">
      <h3>从 FinalShell 导入</h3>
      <p>支持导入 FinalShell 导出的连接配置（包含 <code>.zip</code> 压缩包或单个 <code>.json</code> 文件），或者直接指定本机/服务器上的配置目录。系统将自动解析分组层级并解密密码凭据。</p>
      
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="导入到指定分组">
          <el-tree-select
            v-model="finalShellTargetGroup"
            :data="groupTree"
            check-strictly
            placeholder="请选择目标分组（默认导入到根分组）"
            style="width: 100%;"
            clearable
          />
        </el-form-item>

        <el-form-item label="导入来源">
          <el-radio-group v-model="finalShellSourceType">
            <el-radio label="file">上传文件 (.zip / .json)</el-radio>
            <el-radio label="localPath">本机 / 服务器目录或文件路径</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="finalShellSourceType === 'file'" label="选择 FinalShell 备份文件">
          <input
            ref="finalShellFileInput"
            type="file"
            accept=".zip,.json,application/zip,application/json"
            :disabled="!!busy"
            @change="selectFinalShellFile"
          />
          <p v-if="finalShellFileName" class="file-hint">{{ finalShellFileName }}</p>
        </el-form-item>

        <el-form-item v-if="finalShellSourceType === 'localPath'" label="输入目录或文件绝对路径">
          <el-input
            v-model="finalShellLocalPath"
            placeholder="例如：/Users/pino/Downloads/finalshell"
            clearable
          />
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="finalShellOverwrite">若同名主机/账户已存在则覆盖更新</el-checkbox>
        </el-form-item>

        <el-button
          type="primary"
          :loading="busy === 'finalshell'"
          :disabled="!canImportFinalShell || !!busy"
          @click="doFinalShellImport"
        >
          开始从 FinalShell 导入
        </el-button>
      </el-form>

      <el-alert
        v-if="finalShellResult"
        :title="finalShellResult"
        type="success"
        :closable="false"
        style="margin-top: 16px;"
      />
      <div v-if="finalShellWarnings.length > 0" class="warnings-box">
        <el-alert
          v-for="(w, idx) in finalShellWarnings"
          :key="idx"
          :title="w"
          type="warning"
          :closable="false"
          style="margin-top: 8px;"
        />
      </div>
    </div>

    <el-divider />
    <h3>退出与卸载 Doumi Shell</h3>
    <p>停止本机后台程序：右键系统托盘图标，选择 Exit Doumi Shell。关闭浏览器只关闭页面，后台脚本与计划任务仍继续运行。</p>
    <p>卸载前先退出程序，然后在 Windows“设置 → 应用 → 已安装的应用”中卸载 Doumi Shell，或使用开始菜单的 Uninstall Doumi Shell。卸载保留用户配置和密钥。</p>
  </div></el-scrollbar>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportConfiguration, importConfiguration, listAllGroups, importFinalShell } from '@/api/sshConsole'

const password = ref('')
const importPassword = ref('')
const encrypted = ref(true)
const busy = ref('')
const archive = ref(null)
const filename = ref('')
const result = ref('')
const fileInput = ref(null)

// FinalShell state
const finalShellTargetGroup = ref('')
const finalShellSourceType = ref('file')
const finalShellSelectedFile = ref(null)
const finalShellFileName = ref('')
const finalShellLocalPath = ref('')
const finalShellOverwrite = ref(false)
const finalShellResult = ref('')
const finalShellWarnings = ref([])
const finalShellFileInput = ref(null)
const allGroups = ref([])

const groupTree = computed(() => {
  const rootNode = { value: '', label: '(根目录 / 顶层分组)', children: [] }
  const map = new Map()
  allGroups.value.forEach(g => {
    map.set(g.id, { value: g.id, label: g.name, children: [] })
  })
  allGroups.value.forEach(g => {
    const node = map.get(g.id)
    if (g.parentId && map.has(g.parentId)) {
      map.get(g.parentId).children.push(node)
    } else {
      rootNode.children.push(node)
    }
  })
  return [rootNode]
})

const canImportFinalShell = computed(() => {
  if (finalShellSourceType.value === 'file') {
    return !!finalShellSelectedFile.value
  }
  return !!finalShellLocalPath.value.trim()
})

async function fetchGroups() {
  try {
    const list = await listAllGroups()
    allGroups.value = Array.isArray(list) ? list : []
  } catch (e) {
    console.error('加载分组列表失败', e)
  }
}

onMounted(() => {
  fetchGroups()
})

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
    await fetchGroups()
  } finally { busy.value = '' }
}

function selectFinalShellFile(event) {
  finalShellSelectedFile.value = null
  finalShellFileName.value = ''
  const file = event.target.files?.[0]
  if (!file) return
  if (file.size > 100 * 1024 * 1024) return ElMessage.error('文件大小不能超过 100 MB')
  finalShellSelectedFile.value = file
  finalShellFileName.value = `${file.name} (${(file.size / 1024).toFixed(1)} KB)`
}

async function doFinalShellImport() {
  busy.value = 'finalshell'
  finalShellResult.value = ''
  finalShellWarnings.value = []
  try {
    const res = await importFinalShell({
      file: finalShellSourceType.value === 'file' ? finalShellSelectedFile.value : undefined,
      localPath: finalShellSourceType.value === 'localPath' ? finalShellLocalPath.value.trim() : undefined,
      targetGroupId: finalShellTargetGroup.value || undefined,
      overwrite: finalShellOverwrite.value
    })

    let msg = `FinalShell 导入完成：成功导入 ${res.createdGroups} 个新分组、${res.importedHosts} 台主机、${res.importedAccounts} 个账户（已自动解密密码）。`
    if (res.updatedHosts > 0 || res.updatedAccounts > 0) {
      msg += ` 更新 ${res.updatedHosts} 台主机、${res.updatedAccounts} 个账户。`
    }
    if (res.failedCount > 0) {
      msg += ` 失败 ${res.failedCount} 个。`
    }
    finalShellResult.value = msg
    finalShellWarnings.value = res.warnings || []

    if (finalShellFileInput.value) {
      finalShellFileInput.value.value = ''
    }
    finalShellSelectedFile.value = null
    finalShellFileName.value = ''

    window.dispatchEvent(new Event('doumi-configuration-imported'))
    await fetchGroups()
    ElMessage.success('FinalShell 导入成功')
  } catch (error) {
    ElMessage.error(error.message || 'FinalShell 导入失败')
  } finally {
    busy.value = ''
  }
}
</script>

<style scoped>
.configuration { max-width: 760px; min-height: 100%; box-sizing: border-box; padding: 24px 32px; margin: auto; }
p { color: var(--el-text-color-secondary); line-height: 1.7; }
.el-form { margin: 24px 0; }
.finalshell-section h3 { margin-bottom: 8px; }
.file-hint { font-size: 13px; color: var(--el-color-primary); margin-top: 4px; }
.warnings-box { margin-top: 12px; }
</style>
