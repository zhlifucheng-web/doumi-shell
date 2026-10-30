<template>
  <el-dialog :model-value="modelValue" :title="form.id ? '编辑账户' : '新建账户'" width="min(560px, 94vw)" top="5vh" append-to-body destroy-on-close :close-on-click-modal="false" @update:model-value="closeEditor">
    <el-scrollbar max-height="calc(90vh - 220px)">
      <el-form :disabled="loading || saving" label-position="top" @submit.prevent="saveConnection">
        <div class="account-form-row">
          <el-form-item label="类型"><el-select v-model="form.protocol" :disabled="!!form.id"><el-option label="SSH" value="SSH" /><el-option label="RDP" value="RDP" /></el-select></el-form-item>
          <el-form-item label="所属主机" required><el-select v-model="form.hostId" filterable placeholder="请选择所属主机"><el-option v-for="host in hostOptions" :key="host.id" :value="host.id" :label="`${host.name} (${host.host})`"><span>{{ host.name }}</span><span class="host-address">{{ host.host }}</span></el-option></el-select></el-form-item>
        </div>
        <el-form-item v-if="form.protocol === 'RDP'" label="名称"><el-input v-model="form.name" maxlength="100" :placeholder="form.username" /></el-form-item>
        <div class="account-form-row">
          <el-form-item label="用户名" required><el-input v-model="form.username" :maxlength="form.protocol === 'RDP' ? 255 : 64" autocomplete="off" /></el-form-item>
          <el-form-item v-if="form.protocol === 'RDP'" label="域"><el-input v-model="form.domain" maxlength="255" autocomplete="off" /></el-form-item>
        </div>
        <el-form-item v-if="form.protocol === 'SSH'" label="认证方式"><el-select v-model="form.authType"><el-option label="密码" value="PASSWORD" /><el-option label="私钥" value="PRIVATE_KEY" /><el-option label="私钥+口令" value="PRIVATE_KEY_WITH_PASSPHRASE" /></el-select></el-form-item>
        <el-form-item v-if="form.protocol === 'RDP' || form.authType === 'PASSWORD'" :label="form.id ? '密码（留空保留）' : '密码'" :required="!form.id"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <template v-else>
          <el-form-item :label="form.id ? '私钥（留空保留）' : '私钥 PEM'" :required="!form.id"><el-input v-model="form.privateKeyPem" type="textarea" :rows="4" autocomplete="off" /></el-form-item>
          <el-form-item v-if="form.authType === 'PRIVATE_KEY_WITH_PASSPHRASE'" label="私钥口令"><el-input v-model="form.passphrase" type="password" show-password autocomplete="new-password" /></el-form-item>
        </template>
        <el-form-item v-if="form.protocol === 'SSH'" label="默认目录"><el-input v-model="form.defaultCwd" /></el-form-item>
        <el-form-item v-else label="RDP 端口" required><el-input-number v-model="form.port" :min="1" :max="65535" /></el-form-item>
        <div class="account-form-row account-form-options">
          <el-checkbox v-if="form.protocol === 'RDP'" v-model="form.ignoreCertificate">忽略服务器证书校验</el-checkbox>
          <el-checkbox v-model="form.enabled" :true-value="1" :false-value="0">启用</el-checkbox>
          <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" maxlength="512" /></el-form-item>
      </el-form>
    </el-scrollbar>
    <div class="account-feedback" aria-live="polite" aria-atomic="true">
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon />
      <el-alert v-else-if="testResult" :title="testResult.message" :type="testResult.success ? 'success' : 'error'" :closable="false" show-icon />
    </div>
    <template #footer><div class="account-footer"><el-button :loading="testing" :disabled="loading || saving" @click="testConnection">测试连接</el-button><div><el-button @click="closeEditor">取消</el-button><el-button type="primary" :loading="saving" :disabled="loading || testing" @click="saveConnection">保存</el-button></div></div></template>
  </el-dialog>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { getAccount, createAccount, updateAccount, testAccount } from '@/api/sshConsole'
import { getRdpConnection, createRdpConnection, updateRdpConnection, testRdpConnection } from '@/api/rdp'
import { accountConnectionForm, accountConnectionPayload, accountConnectionFeedback } from '@/utils/connectionAccount'

const props = defineProps({ modelValue: Boolean, initial: { type: Object, default: () => ({}) }, hostOptions: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'saved'])
const form = reactive(accountConnectionForm()), loading = ref(false), saving = ref(false), testing = ref(false)
const errorMessage = ref(''), testResult = ref(null)
let generation = 0, revision = 0, testRequest = 0, lockedProtocol = null

function clearSecrets() { form.password = ''; form.privateKeyPem = ''; form.passphrase = '' }
function invalidate() { revision++; testResult.value = null; errorMessage.value = '' }
watch(form, invalidate, { deep: true, flush: 'sync' })
watch(() => form.protocol, (protocol, previous) => {
  if (lockedProtocol && protocol !== lockedProtocol) { form.protocol = lockedProtocol; return }
  if (protocol !== previous) clearSecrets()
}, { flush: 'sync' })

async function resetEditor() {
  const current = ++generation
  testRequest++; testing.value = false; saving.value = false; loading.value = false
  lockedProtocol = props.initial?.id ? (props.initial.protocol === 'RDP' ? 'RDP' : 'SSH') : null
  Object.assign(form, accountConnectionForm(props.initial))
  invalidate()
  if (!form.id) return
  loading.value = true
  try {
    const account = await (form.protocol === 'RDP' ? getRdpConnection(form.id) : getAccount(form.id))
    if (current === generation && props.modelValue) Object.assign(form, accountConnectionForm({ ...account, protocol: lockedProtocol }))
  } catch (error) { if (current === generation) errorMessage.value = accountConnectionFeedback(error?.message, form, '账户加载失败，请关闭后重试') }
  finally { if (current === generation) loading.value = false }
}

function closeEditor() {
  generation++; testRequest++; clearSecrets(); invalidate()
  testing.value = false; saving.value = false; loading.value = false
  emit('update:modelValue', false)
}

async function testConnection() {
  if (!props.modelValue || loading.value || saving.value || testing.value) return
  errorMessage.value = ''; testResult.value = null
  let payload
  try { payload = accountConnectionPayload(form, { test: true }) }
  catch (error) { errorMessage.value = error.message; return }
  const current = ++testRequest, session = generation, draft = revision
  testing.value = true
  try {
    const result = await (form.protocol === 'RDP' ? testRdpConnection(payload) : testAccount(payload))
    if (current === testRequest && session === generation && draft === revision && props.modelValue) {
      const success = result?.success === true
      testResult.value = { success, message: accountConnectionFeedback(result?.message, form, success ? '连接成功' : '连接或认证失败，请检查主机、用户名和凭据', { success }), elapsedMs: Number.isFinite(result?.elapsedMs) && result.elapsedMs >= 0 ? result.elapsedMs : undefined }
    }
  } catch (error) {
    if (current === testRequest && session === generation && draft === revision && props.modelValue) testResult.value = { success: false, message: accountConnectionFeedback(error?.message, form, '测试失败，请检查网络或稍后重试') }
  } finally { if (current === testRequest && session === generation) testing.value = false }
}

async function saveConnection() {
  if (!props.modelValue || loading.value || saving.value || testing.value) return
  errorMessage.value = ''
  let payload
  try { payload = accountConnectionPayload(form) }
  catch (error) { errorMessage.value = error.message; return }
  const current = generation, protocol = form.protocol, id = form.id
  saving.value = true
  try {
    const account = protocol === 'RDP'
      ? await (id ? updateRdpConnection(id, payload) : createRdpConnection(payload))
      : await (id ? updateAccount(id, payload) : createAccount(payload))
    if (current !== generation || !props.modelValue) return
    clearSecrets()
    emit('saved', { protocol, account })
    closeEditor()
  } catch (error) { if (current === generation) errorMessage.value = accountConnectionFeedback(error?.message, form, '保存失败，请检查连接设置后重试') }
  finally { if (current === generation) saving.value = false }
}

watch(() => props.modelValue, open => {
  if (open) resetEditor()
  else { generation++; testRequest++; clearSecrets(); invalidate(); testing.value = false; saving.value = false; loading.value = false }
}, { immediate: true })
onBeforeUnmount(() => { generation++; testRequest++; clearSecrets() })
</script>

<style scoped>
.account-form-row { display: flex; flex-wrap: wrap; gap: 12px; }
.account-form-row > .el-form-item { flex: 1; min-width: 160px; }
.el-select { width: 100%; }
.account-form-options { align-items: center; }
.account-form-options > .el-form-item { margin-bottom: 0; }
.host-address { margin-left: 12px; color: var(--el-text-color-secondary); }
.account-feedback:has(> *) { margin-top: 12px; }
.account-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
</style>
