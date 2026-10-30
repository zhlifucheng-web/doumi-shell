<template>
  <el-scrollbar class="page-scroll">
    <div class="remote-backup">
      <p>自动将服务器连接信息（分组、主机、账户凭据）、运维脚本与监控配置定时备份并上传到指定的 MinIO 或 SFTP 服务器上。</p>

      <el-card v-if="loaded" class="status-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>备份运行状态</span>
            <el-tag :type="statusTagType" size="small">{{ statusText }}</el-tag>
          </div>
        </template>
        <div class="status-grid">
          <div class="status-item">
            <span class="label">定时调度：</span>
            <span class="value">{{ form.enabled ? '已启用 (' + form.cron + ')' : '未启用' }}</span>
          </div>
          <div class="status-item">
            <span class="label">下次执行：</span>
            <span class="value">{{ form.nextRunAt || '暂无计划' }}</span>
          </div>
          <div class="status-item">
            <span class="label">上次备份时间：</span>
            <span class="value">{{ form.lastBackupAt || '暂无备份记录' }}</span>
          </div>
          <div class="status-item">
            <span class="label">目标类型：</span>
            <span class="value">{{ form.targetType === 'SFTP' ? 'SFTP 服务器' : 'MinIO 对象存储' }}</span>
          </div>
        </div>
        <div v-if="form.lastMessage" class="status-message" :class="{ error: form.lastStatus === 'FAILED' }">
          <span class="label">最近执行结果：</span>
          <span class="msg-text">{{ form.lastMessage }}</span>
        </div>
      </el-card>

      <el-form v-if="loaded" ref="formRef" :model="form" label-position="top" class="backup-form">
        <h3 class="section-title">定时与加密设置</h3>

        <el-form-item label="启用定时备份">
          <el-switch v-model="form.enabled" active-text="开启自动备份" inactive-text="暂停自动备份" />
        </el-form-item>

        <el-row :gutter="20">
          <el-col :span="16">
            <el-form-item label="Cron 表达式（5 位或 6 位）">
              <el-input v-model="form.cron" placeholder="例如：0 0 2 * * * （每天凌晨 2 点）" @change="onCronChange" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="常用周期快捷选择">
              <el-select v-model="quickCron" placeholder="选择预设" @change="applyQuickCron">
                <el-option label="每天凌晨 02:00" value="0 0 2 * * *" />
                <el-option label="每天凌晨 03:00" value="0 0 3 * * *" />
                <el-option label="每 12 小时一次" value="0 0 */12 * * *" />
                <el-option label="每周一凌晨 02:00" value="0 0 2 * * 1" />
                <el-option label="每小时整点" value="0 0 * * * *" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="备份加密方式">
          <el-switch v-model="form.encrypted" active-text="加密备份（推荐）" inactive-text="明文备份" />
        </el-form-item>
        <el-alert
          v-if="!form.encrypted"
          title="明文备份包含 SSH 账户密码、私钥等凭据，请确保目标 MinIO / SFTP 服务器受安全保护。"
          type="warning"
          :closable="false"
          style="margin-bottom: 16px;"
        />

        <el-form-item v-if="form.encrypted" label="备份密码（至少 8 个字符，恢复/导入备份时需提供此密码）">
          <el-input
            v-model="form.backupPassword"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="form.hasBackupPassword ? '已设置加密密码，留空表示沿用已保存密码' : '请输入至少 8 个字符的备份加密密码'"
          />
        </el-form-item>

        <el-divider />

        <h3 class="section-title">备份目标服务器</h3>
        <el-form-item label="选择备份目标">
          <el-radio-group v-model="form.targetType">
            <el-radio-button label="MINIO">MinIO (S3 兼容对象存储)</el-radio-button>
            <el-radio-button label="SFTP">SFTP 服务器</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- MinIO Form -->
        <template v-if="form.targetType === 'MINIO'">
          <el-form-item label="MinIO 服务地址 (Endpoint)" required>
            <el-input v-model="form.minioEndpoint" placeholder="例如：http://192.168.1.100:9000 或 https://s3.example.com" />
          </el-form-item>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="存储桶名称 (Bucket)" required>
                <el-input v-model="form.minioBucket" placeholder="例如：doumi-backup" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="存储路径前缀（可选）">
                <el-input v-model="form.minioPath" placeholder="例如：web-shell/backups/（留空保存在根目录）" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="Access Key" required>
                <el-input v-model="form.minioAccessKey" placeholder="请输入 MinIO Access Key" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="Secret Key" required>
                <el-input
                  v-model="form.minioSecretKey"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  :placeholder="form.hasMinioSecretKey ? '已保存，留空表示沿用已保存密钥' : '请输入 MinIO Secret Key'"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="Region 区域（可选）">
            <el-input v-model="form.minioRegion" placeholder="例如：us-east-1（普通 MinIO 部署通常留空即可）" />
          </el-form-item>
        </template>

        <!-- SFTP Form -->
        <template v-if="form.targetType === 'SFTP'">
          <el-row :gutter="20">
            <el-col :span="18">
              <el-form-item label="SFTP 主机地址" required>
                <el-input v-model="form.sftpHost" placeholder="例如：192.168.1.50 或 sftp.example.com" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="端口" required>
                <el-input-number v-model="form.sftpPort" :min="1" :max="65535" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="登录用户名" required>
                <el-input v-model="form.sftpUsername" placeholder="请输入 SFTP 登录用户名" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="认证方式" required>
                <el-select v-model="form.sftpAuthType" style="width: 100%">
                  <el-option label="密码认证" value="PASSWORD" />
                  <el-option label="私钥认证" value="PRIVATE_KEY" />
                  <el-option label="私钥 + 密码短语" value="PRIVATE_KEY_WITH_PASSPHRASE" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item v-if="form.sftpAuthType === 'PASSWORD'" label="SFTP 密码" required>
            <el-input
              v-model="form.sftpPassword"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="form.hasSftpPassword ? '已保存，留空表示沿用已保存密码' : '请输入 SFTP 密码'"
            />
          </el-form-item>

          <template v-if="form.sftpAuthType === 'PRIVATE_KEY' || form.sftpAuthType === 'PRIVATE_KEY_WITH_PASSPHRASE'">
            <el-form-item label="私钥内容 (PEM 格式)" required>
              <el-input
                v-model="form.sftpPrivateKeyPem"
                type="textarea"
                :rows="4"
                :placeholder="form.hasSftpPrivateKey ? '已保存私钥，留空表示沿用已保存私钥' : '-----BEGIN RSA/OPENSSH PRIVATE KEY-----...'"
              />
            </el-form-item>
            <el-form-item v-if="form.sftpAuthType === 'PRIVATE_KEY_WITH_PASSPHRASE'" label="私钥密码短语 (Passphrase)">
              <el-input
                v-model="form.sftpPassphrase"
                type="password"
                show-password
                :placeholder="form.hasSftpPassphrase ? '已保存口令，留空不修改' : '若私钥无密码短语可留空'"
              />
            </el-form-item>
          </template>

          <el-form-item label="远程存储目录">
            <el-input v-model="form.sftpRemoteDir" placeholder="例如：/data/backups/web-shell/（不存在时会自动创建）" />
          </el-form-item>
        </template>

        <div class="action-buttons">
          <el-button type="primary" :loading="saving" @click="handleSave">保存配置</el-button>
          <el-button :loading="testing" @click="handleTest">测试连接</el-button>
          <el-button type="warning" :loading="executing" @click="handleExecuteNow">立即备份</el-button>
        </div>
      </el-form>
    </div>
  </el-scrollbar>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getRemoteBackupConfig,
  saveRemoteBackupConfig,
  testRemoteBackup,
  executeRemoteBackup
} from '@/api/sshConsole'

const loaded = ref(false)
const saving = ref(false)
const testing = ref(false)
const executing = ref(false)
const quickCron = ref('')

const form = ref({
  enabled: false,
  targetType: 'MINIO',
  cron: '0 0 2 * * *',
  zone: 'Asia/Shanghai',
  encrypted: true,
  backupPassword: '',
  hasBackupPassword: false,
  minioEndpoint: '',
  minioBucket: '',
  minioAccessKey: '',
  minioSecretKey: '',
  hasMinioSecretKey: false,
  minioRegion: '',
  minioPath: '',
  sftpHost: '',
  sftpPort: 22,
  sftpUsername: '',
  sftpAuthType: 'PASSWORD',
  sftpPassword: '',
  hasSftpPassword: false,
  sftpPrivateKeyPem: '',
  hasSftpPrivateKey: false,
  sftpPassphrase: '',
  hasSftpPassphrase: false,
  sftpRemoteDir: '',
  lastBackupAt: '',
  lastStatus: '',
  lastMessage: '',
  nextRunAt: ''
})

const statusTagType = computed(() => {
  if (form.value.lastStatus === 'SUCCESS') return 'success'
  if (form.value.lastStatus === 'FAILED') return 'danger'
  return form.value.enabled ? 'primary' : 'info'
})

const statusText = computed(() => {
  if (form.value.lastStatus === 'SUCCESS') return '上次备份成功'
  if (form.value.lastStatus === 'FAILED') return '上次备份失败'
  return form.value.enabled ? '定时计划运行中' : '未启用'
})

function applyQuickCron(val) {
  if (val) {
    form.value.cron = val
  }
}

function onCronChange() {
  quickCron.value = ''
}

async function loadConfig() {
  try {
    const data = await getRemoteBackupConfig()
    if (data) {
      form.value = {
        ...form.value,
        ...data,
        backupPassword: '',
        minioSecretKey: '',
        sftpPassword: '',
        sftpPrivateKeyPem: '',
        sftpPassphrase: ''
      }
    }
  } catch (err) {
    ElMessage.error(err.message || '加载远程备份配置失败')
  } finally {
    loaded.value = true
  }
}

async function handleSave() {
  if (form.value.enabled) {
    if (form.value.encrypted && !form.value.hasBackupPassword && !form.value.backupPassword) {
      return ElMessage.warning('启用加密备份时，请设置至少 8 个字符的备份密码')
    }
    if (form.value.encrypted && form.value.backupPassword && form.value.backupPassword.length < 8) {
      return ElMessage.warning('备份密码长度须为 8 至 1024 个字符')
    }
  }

  saving.value = true
  try {
    const res = await saveRemoteBackupConfig(form.value)
    if (res) {
      form.value = {
        ...form.value,
        ...res,
        backupPassword: '',
        minioSecretKey: '',
        sftpPassword: '',
        sftpPrivateKeyPem: '',
        sftpPassphrase: ''
      }
    }
    ElMessage.success('远程备份配置保存成功')
  } catch (err) {
    ElMessage.error(err.message || '保存配置失败')
  } finally {
    saving.value = false
  }
}

async function handleTest() {
  testing.value = true
  try {
    const res = await testRemoteBackup(form.value)
    ElMessage.success(res?.message || '目标服务器连接测试成功！')
  } catch (err) {
    ElMessage.error(err.message || '连接测试失败')
  } finally {
    testing.value = false
  }
}

async function handleExecuteNow() {
  try {
    await ElMessageBox.confirm('确定立即导出链接信息并上传至目标备份服务器吗？', '执行远程备份', {
      confirmButtonText: '立即备份',
      cancelButtonText: '取消',
      type: 'info'
    })
  } catch {
    return
  }

  executing.value = true
  try {
    const res = await executeRemoteBackup()
    ElMessage.success(res?.message || '远程备份执行成功！')
    await loadConfig()
  } catch (err) {
    ElMessage.error(err.message || '备份执行失败')
    await loadConfig()
  } finally {
    executing.value = false
  }
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.remote-backup {
  max-width: 820px;
  min-height: 100%;
  box-sizing: border-box;
  padding: 24px 32px;
  margin: auto;
}

h2 {
  margin-top: 0;
  margin-bottom: 8px;
  font-size: 20px;
}

p {
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin-top: 0;
  margin-bottom: 20px;
}

.status-card {
  margin-bottom: 24px;
  background-color: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px 24px;
}

.status-item {
  font-size: 13px;
  display: flex;
  align-items: center;
}

.status-item .label {
  color: var(--el-text-color-secondary);
  width: 100px;
  flex-shrink: 0;
}

.status-item .value {
  color: var(--el-text-color-primary);
  font-weight: 500;
  word-break: break-all;
}

.status-message {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
  display: flex;
}

.status-message.error .msg-text {
  color: var(--el-color-danger);
}

.status-message .label {
  color: var(--el-text-color-secondary);
  width: 100px;
  flex-shrink: 0;
}

.status-message .msg-text {
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.section-title {
  font-size: 16px;
  margin: 16px 0 16px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.backup-form {
  margin: 20px 0;
}

.action-buttons {
  margin-top: 32px;
  display: flex;
  gap: 16px;
}
</style>
