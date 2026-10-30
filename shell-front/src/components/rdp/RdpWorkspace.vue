<template>
  <el-dialog :model-value="modelValue" title="远程桌面" width="96%" top="3vh" class="rdp-workspace-dialog" append-to-body :close-on-click-modal="false" :close-on-press-escape="false" @update:model-value="closeWorkspace">
    <div class="rdp-workspace">
      <aside class="rdp-profiles" v-loading="loading">
        <div class="rdp-profile-header"><strong>{{ host?.name || host?.host }}</strong><el-button :icon="Plus" circle aria-label="新建远程桌面连接" @click="editProfile()" /></div>
        <div class="rdp-host-address">{{ host?.host }}</div>
        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
        <div class="rdp-profile-list">
          <div v-for="profile in profiles" :key="profile.id" class="rdp-profile">
            <button type="button" class="rdp-profile-connect" :disabled="!profile.enabled || !gateway.available" @click="openDesktop(profile)">{{ profile.name }}<span>{{ profile.username }} · {{ profile.port }}</span></button>
            <el-dropdown trigger="click" @command="command => profileAction(command, profile)"><el-button text :icon="MoreFilled" :aria-label="`${profile.name} 操作`" /><template #dropdown><el-dropdown-menu><el-dropdown-item command="edit">编辑</el-dropdown-item><el-dropdown-item command="delete">删除</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
          </div>
          <el-empty v-if="!loading && !profiles.length && !loadError" description="暂无连接" :image-size="56"><el-button type="primary" @click="editProfile()">新建连接</el-button></el-empty>
        </div>
        <el-button :icon="Refresh" @click="loadProfiles">刷新</el-button>
      </aside>
      <main class="rdp-main">
        <div v-if="!gateway.available" class="rdp-gateway-error" role="alert"><el-alert :title="gateway.message || '远程桌面网关不可用，请启动 guacd 并检查网关地址和端口'" type="warning" :closable="false" show-icon /><el-button @click="checkGateway">重试网关</el-button></div>
        <div v-if="tabs.length" class="rdp-tab-bar" role="tablist" aria-label="远程桌面会话">
          <div v-for="tab in tabs" :key="tab.id" class="rdp-tab" :class="{ active: activeId === tab.id }">
            <button type="button" role="tab" :aria-selected="activeId === tab.id" @click="activeId = tab.id">{{ tab.profile.name }}</button>
            <button type="button" :aria-label="`关闭 ${tab.profile.name}`" @click="closeTab(tab.id)"><el-icon><Close /></el-icon></button>
          </div>
        </div>
        <div class="rdp-desktops">
          <RdpDesktop v-for="tab in tabs" v-show="activeId === tab.id" :key="tab.id" :profile="tab.profile" :active="modelValue && activeId === tab.id" />
          <el-empty v-if="!tabs.length" description="选择连接以打开远程桌面" />
        </div>
      </main>
    </div>
    <el-dialog v-model="editorOpen" :title="editingId ? '编辑连接' : '新建连接'" width="min(480px, 94vw)" append-to-body :close-on-click-modal="false" @closed="form.password = ''">
      <el-form label-position="top" @submit.prevent="saveProfile">
        <el-form-item label="名称" required><el-input v-model="form.name" maxlength="100" autofocus /></el-form-item>
        <div class="rdp-form-row"><el-form-item label="用户名" required><el-input v-model="form.username" maxlength="255" autocomplete="off" /></el-form-item><el-form-item label="域"><el-input v-model="form.domain" maxlength="255" autocomplete="off" /></el-form-item></div>
        <el-form-item :label="editingId ? '密码（留空保留）' : '密码'" :required="!editingId"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="RDP 端口" required><el-input-number v-model="form.port" :min="1" :max="65535" /></el-form-item>
        <div class="rdp-form-row"><el-checkbox v-model="form.ignoreCertificate">忽略服务器证书校验</el-checkbox><el-checkbox v-model="form.enabled" :true-value="1" :false-value="0">启用</el-checkbox></div>
        <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon />
      </el-form>
      <template #footer><el-button @click="editorOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveProfile">保存</el-button></template>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { onBeforeUnmount, onDeactivated, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, MoreFilled, Plus, Refresh } from '@element-plus/icons-vue'
import RdpDesktop from './RdpDesktop.vue'
import { createRdpConnection, deleteRdpConnection, getRdpGateway, listRdpConnections, updateRdpConnection } from '@/api/rdp'
import { rdpProfileForm, rdpProfilePayload } from '@/utils/rdpProfile'
const props = defineProps({ modelValue: Boolean, host: Object })
const emit = defineEmits(['update:modelValue'])
const profiles = ref([]), tabs = ref([]), activeId = ref(''), loading = ref(false), loadError = ref('')
const gateway = reactive({ enabled: false, available: false, message: '正在检查网关…' })
const editorOpen = ref(false), editingId = ref(null), form = reactive(rdpProfileForm()), saving = ref(false), saveError = ref('')
let requestId = 0, nextTabId = 0, gatewayRequest = 0
async function checkGateway() {
  const current = ++gatewayRequest
  try { const result = await getRdpGateway(); if (current === gatewayRequest) Object.assign(gateway, result) }
  catch (error) { if (current === gatewayRequest) Object.assign(gateway, { available: false, message: error.message || '无法连接远程桌面网关' }) }
}
async function loadProfiles() {
  const hostId = props.host?.id, current = ++requestId
  if (!hostId || !props.modelValue) return
  loading.value = true; loadError.value = ''
  try { const result = await listRdpConnections(hostId); if (current === requestId) profiles.value = result || [] }
  catch (error) { if (current === requestId) loadError.value = error.message || '连接列表加载失败' }
  finally { if (current === requestId) loading.value = false }
}
function openDesktop(profile) { const id = `rdp-${++nextTabId}`; tabs.value.push({ id, profile: { ...profile } }); activeId.value = id }
function closeTab(id) { const index = tabs.value.findIndex(tab => tab.id === id); tabs.value = tabs.value.filter(tab => tab.id !== id); if (activeId.value === id) activeId.value = tabs.value[Math.min(index, tabs.value.length - 1)]?.id || '' }
function editProfile(profile) { editingId.value = profile?.id || null; Object.assign(form, rdpProfileForm(profile)); saveError.value = ''; editorOpen.value = true }
async function saveProfile() {
  if (saving.value) return
  saveError.value = ''
  try {
    const payload = rdpProfilePayload(form, props.host.id, editingId.value)
    saving.value = true
    if (editingId.value) await updateRdpConnection(editingId.value, payload); else await createRdpConnection(payload)
    form.password = ''; editorOpen.value = false; await loadProfiles()
  } catch (error) { saveError.value = error.message || '保存失败' }
  finally { saving.value = false }
}
async function profileAction(command, profile) {
  if (command === 'edit') return editProfile(profile)
  try {
    await ElMessageBox.confirm(`删除连接“${profile.name}”？`, '删除连接', { type: 'warning' })
    await deleteRdpConnection(profile.id)
    tabs.value.filter(tab => tab.profile.id === profile.id).forEach(tab => closeTab(tab.id))
    await loadProfiles()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.message || '删除失败') }
}
function clearWorkspace() { requestId++; gatewayRequest++; tabs.value = []; activeId.value = ''; editorOpen.value = false; form.password = '' }
function closeWorkspace() { clearWorkspace(); emit('update:modelValue', false) }
watch(() => [props.modelValue, props.host?.id], ([open]) => { if (open) { profiles.value = []; editorOpen.value = false; loadProfiles(); checkGateway() } else clearWorkspace() }, { immediate: true })
onDeactivated(closeWorkspace)
onBeforeUnmount(clearWorkspace)
</script>

<style scoped>
.rdp-workspace { height: 82vh; display: flex; min-height: 320px; border: 1px solid var(--el-border-color); border-radius: 6px; overflow: hidden; }
.rdp-profiles { width: 232px; flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; padding: 12px; border-right: 1px solid var(--el-border-color); background: var(--el-fill-color-extra-light); }
.rdp-profile-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; overflow-wrap: anywhere; }
.rdp-host-address { color: var(--el-text-color-regular); font-family: monospace; overflow-wrap: anywhere; }
.rdp-profile-list { flex: 1; overflow: auto; }
.rdp-profile { display: flex; border-bottom: 1px solid var(--el-border-color-lighter); padding: 4px 0; align-items: center; }
.rdp-profile-connect { min-width: 0; flex: 1; padding: 10px 4px; text-align: left; border: 0; background: transparent; color: var(--el-text-color-primary); cursor: pointer; overflow-wrap: anywhere; }
.rdp-profile-connect span { display: block; color: var(--el-text-color-regular); font-size: 12px; margin-top: 4px; }
.rdp-profile-connect:hover { color: var(--el-color-primary); }
.rdp-profile-connect:disabled { opacity: .55; cursor: not-allowed; }
.rdp-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.rdp-gateway-error { display: flex; gap: 8px; padding: 8px; align-items: center; }
.rdp-tab-bar { display: flex; flex-shrink: 0; overflow-x: auto; background: var(--el-fill-color-light); border-bottom: 1px solid var(--el-border-color); }
.rdp-tab { display: flex; align-items: center; border-right: 1px solid var(--el-border-color); white-space: nowrap; }
.rdp-tab.active { background: var(--el-bg-color); box-shadow: inset 0 2px var(--el-color-primary); }
.rdp-tab button { border: 0; background: transparent; color: var(--el-text-color-primary); padding: 12px; cursor: pointer; }
.rdp-tab button:focus-visible, .rdp-profile-connect:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.rdp-desktops { flex: 1; min-height: 0; }
.rdp-desktops > .el-empty { height: 100%; box-sizing: border-box; }
.rdp-form-row { display: flex; gap: 12px; flex-wrap: wrap; }
.rdp-form-row > .el-form-item { flex: 1; min-width: 150px; }
@media (max-width: 700px) { .rdp-workspace { flex-direction: column; } .rdp-profiles { width: auto; max-height: 190px; border-right: 0; border-bottom: 1px solid var(--el-border-color); padding: 8px; gap: 4px; } .rdp-profile-list { display: flex; gap: 12px; } .rdp-profile { min-width: 180px; } .rdp-main { min-height: 250px; } }
</style>
<style>
.rdp-workspace-dialog .el-dialog__body { padding: 8px 16px 16px; }
</style>
