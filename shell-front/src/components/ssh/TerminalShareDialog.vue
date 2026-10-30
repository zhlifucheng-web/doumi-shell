<template>
  <el-dialog
    :model-value="modelValue"
    title="共享终端"
    width="580px"
    destroy-on-close
    append-to-body
    class="terminal-share-dialog"
    @update:model-value="val => $emit('update:modelValue', val)"
    @open="onDialogOpen"
  >
    <div class="share-dialog-content">
      <!-- 会话目标提示 -->
      <div class="target-session-banner">
        <span class="target-label">当前终端：</span>
        <span class="target-val">{{ terminalTab?.label || '默认主终端' }}</span>
        <span class="target-encoding font-mono-code">编码: {{ currentEncoding }}</span>
      </div>

      <!-- 创建分享设置 -->
      <div class="share-form-section">
        <div class="form-row">
          <label class="form-label">协同权限：</label>
          <el-radio-group v-model="form.permission" size="default">
            <el-radio-button label="READ_WRITE">读写协同</el-radio-button>
            <el-radio-button label="READ_ONLY">只读观看</el-radio-button>
          </el-radio-group>
        </div>

        <div class="form-row">
          <label class="form-label">有效期限：</label>
          <el-select v-model="form.expireMinutes" style="width: 220px">
            <el-option :value="30" label="30 分钟" />
            <el-option :value="60" label="1 小时" />
            <el-option :value="120" label="2 小时" />
            <el-option :value="1440" label="24 小时" />
            <el-option :value="0" label="永久有效 (直到手动关闭)" />
          </el-select>
        </div>

        <div class="form-action-row">
          <el-button type="primary" :loading="creating" @click="handleCreateShare">
            生成分享链接
          </el-button>
        </div>
      </div>

      <!-- 新生成的链接展示 -->
      <div v-if="newShare" class="new-share-result">
        <div class="result-header">
          <span class="result-title">✅ 分享链接已生成</span>
          <span class="result-tag" :class="newShare.permission === 'READ_ONLY' ? 'is-ro' : 'is-rw'">
            {{ newShare.permission === 'READ_ONLY' ? '只读观看' : '读写协同' }}
          </span>
          <span v-if="newShare.encoding" class="result-encoding font-mono-code">
            {{ newShare.encoding }}
          </span>
        </div>

        <div class="result-url-row">
          <el-input :model-value="getShareFullUrl(newShare.token, newShare.encoding)" readonly>
            <template #append>
              <el-button type="primary" @click="copyLink(getShareFullUrl(newShare.token, newShare.encoding))">
                复制链接
              </el-button>
            </template>
          </el-input>
        </div>

        <div class="result-meta-row">
          <span v-if="newShare.expireAt">到期时间: {{ formatTime(newShare.expireAt) }}</span>
          <span v-else>有效期: 永久有效（可随时在下方作废）</span>
          <el-link type="primary" :href="getShareFullUrl(newShare.token, newShare.encoding)" target="_blank">
            在独立窗口中打开预览 ↗
          </el-link>
        </div>
      </div>

      <!-- 已生效的分享链接列表 -->
      <div v-if="activeShares.length" class="active-shares-section">
        <div class="section-title">
          <span>当前会话的有效分享链接 ({{ activeShares.length }})</span>
          <el-button link size="small" @click="loadActiveShares">刷新列表 ↻</el-button>
        </div>

        <div class="shares-list">
          <div v-for="item in activeShares" :key="item.token" class="share-item">
            <div class="share-item-left">
              <span class="item-badge" :class="item.readOnly ? 'is-ro' : 'is-rw'">
                {{ item.readOnly ? '只读' : '协同' }}
              </span>
              <span v-if="item.encoding" class="item-encoding font-mono-code">
                {{ item.encoding }}
              </span>
              <span class="item-time">
                {{ item.expireAt ? `到期: ${formatTime(item.expireAt)}` : '永久有效' }}
              </span>
              <span v-if="item.activeGuests > 0" class="item-guests">
                👥 {{ item.activeGuests }} 人在线
              </span>
            </div>

            <div class="share-item-right">
              <el-button size="small" link type="primary" @click="copyLink(getShareFullUrl(item.token, item.encoding))">
                复制
              </el-button>
              <el-button size="small" link type="danger" @click="handleRevoke(item.token)">
                作废
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="$emit('update:modelValue', false)">完成</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { createShareSession, listShareSessions, revokeShareSession } from '@/api/sshConsole'
import { buildShareUrl } from '@/utils/terminalShareHelper'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  account: { type: Object, default: null },
  terminalTab: { type: Object, default: null }
})

const emit = defineEmits(['update:modelValue'])

const creating = ref(false)
const newShare = ref(null)
const activeShares = ref([])

const currentEncoding = computed(() => props.terminalTab?.encoding || 'UTF-8')

const form = reactive({
  permission: 'READ_WRITE',
  expireMinutes: 120
})

function getShareFullUrl(token, enc) {
  const finalEnc = enc || newShare.value?.encoding || currentEncoding.value
  return buildShareUrl(window.location.origin, window.location.pathname, token, finalEnc)
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function copyLink(url) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(url)
    } else {
      const input = document.createElement('textarea')
      input.value = url
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    ElMessage.success('共享会话链接已成功复制到剪贴板！')
  } catch {
    ElMessage.error('复制失败，请手动选中文本复制')
  }
}

async function handleCreateShare() {
  const accountId = props.terminalTab?.accountId || props.account?.id
  if (!accountId) {
    ElMessage.warning('未能识别当前终端所属账户')
    return
  }

  creating.value = true
  try {
    const res = await createShareSession({
      accountId,
      terminalId: props.terminalTab?.terminalId || 'default',
      permission: form.permission,
      expireMinutes: form.expireMinutes,
      encoding: currentEncoding.value
    })
    newShare.value = res
    ElMessage.success('共享链接已生成！')
    await loadActiveShares()
  } catch (err) {
    ElMessage.error(err?.message || '生成分享链接失败')
  } finally {
    creating.value = false
  }
}

async function loadActiveShares() {
  const accountId = props.terminalTab?.accountId || props.account?.id
  if (!accountId) return
  try {
    const res = await listShareSessions(accountId, props.terminalTab?.terminalId || 'default')
    activeShares.value = Array.isArray(res) ? res : []
  } catch {
    activeShares.value = []
  }
}

async function handleRevoke(token) {
  try {
    await revokeShareSession(token)
    ElMessage.success('该共享链接已作废，协作者连接已即时断开')
    if (newShare.value?.token === token) {
      newShare.value = null
    }
    await loadActiveShares()
  } catch (err) {
    ElMessage.error(err?.message || '作废失败')
  }
}

function onDialogOpen() {
  newShare.value = null
  loadActiveShares()
}
</script>

<style scoped>
.share-dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.target-session-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 8px 12px;
  background: var(--el-fill-color, rgba(255, 255, 255, 0.04));
  border-radius: 6px;
}

.target-label {
  color: var(--el-text-color-secondary, #8b949e);
}

.target-val {
  font-weight: 500;
  color: var(--el-text-color-primary, #c9d1d9);
}

.target-encoding {
  margin-left: auto;
  font-size: 11px;
  color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary-light-9, rgba(64, 158, 255, 0.1));
  padding: 2px 6px;
  border-radius: 4px;
}

.share-form-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  background: var(--el-fill-color-blank, rgba(255, 255, 255, 0.02));
  border: 1px solid var(--el-border-color-lighter, rgba(255, 255, 255, 0.08));
  border-radius: 8px;
}

.form-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.form-label {
  width: 75px;
  font-size: 13px;
  color: var(--el-text-color-regular, #8b949e);
  flex-shrink: 0;
}

.form-action-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}

.new-share-result {
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: rgba(46, 160, 67, 0.08);
  border: 1px solid rgba(46, 160, 67, 0.25);
  border-radius: 8px;
  padding: 14px;
}

.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.result-title {
  font-size: 14px;
  font-weight: 600;
  color: #3fb950;
}

.result-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}

.result-tag.is-rw {
  background: rgba(46, 160, 67, 0.2);
  color: #3fb950;
}

.result-tag.is-ro {
  background: rgba(210, 153, 34, 0.2);
  color: #d29922;
}

.result-encoding {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9, rgba(64, 158, 255, 0.15));
  color: var(--el-color-primary, #409eff);
  margin-left: 6px;
}

.result-url-row {
  margin: 4px 0;
}

.result-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--el-text-color-secondary, #8b949e);
}

.active-shares-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 4px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-secondary, #8b949e);
}

.shares-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 180px;
  overflow-y: auto;
}

.share-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--el-fill-color-dark, rgba(255, 255, 255, 0.04));
  border-radius: 6px;
  font-size: 12px;
}

.share-item-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.item-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}

.item-badge.is-rw {
  background: rgba(46, 160, 67, 0.15);
  color: #3fb950;
}

.item-badge.is-ro {
  background: rgba(210, 153, 34, 0.15);
  color: #d29922;
}

.item-encoding {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color, rgba(255, 255, 255, 0.08));
  color: var(--el-text-color-primary, #c9d1d9);
}

.item-time {
  color: var(--el-text-color-secondary, #8b949e);
}

.item-guests {
  color: var(--el-color-primary, #409eff);
  font-size: 11px;
}

.share-item-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
