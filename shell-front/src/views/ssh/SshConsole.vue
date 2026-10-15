<template>
  <div class="ssh-console">
    <el-container class="layout">
      <el-aside :width="sidebarCollapsed ? '48px' : '280px'" class="aside" :class="{'aside-collapsed':sidebarCollapsed}">
        <el-button class="sidebar-toggle" text :aria-label="sidebarCollapsed ? '展开分组侧栏' : '收起分组侧栏'" :title="sidebarCollapsed ? '展开分组侧栏' : '收起分组侧栏'" @click="sidebarCollapsed=!sidebarCollapsed"><el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon><span v-if="!sidebarCollapsed">分组与服务器</span></el-button>
        <div v-show="!sidebarCollapsed" class="aside-content">
        <div class="aside-toolbar">
          <el-input
            v-model="filterText"
            placeholder="筛选分组/主机/账户"
            clearable
            class="aside-filter"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-dropdown trigger="click" @command="onAddCommand">
            <el-button type="primary" title="新建">
              <el-icon><Plus /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="group">新建分组</el-dropdown-item>
                <el-dropdown-item command="host" :disabled="!selectedGroupId">新建主机</el-dropdown-item>
                <el-dropdown-item command="account" :disabled="!selectedHostId">新建账户</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button :loading="treeLoading" title="刷新" @click="refreshTree">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>

        <el-scrollbar class="asset-scroll"><el-tree
          :key="treeVersion"
          ref="treeRef"
          class="asset-tree"
          node-key="key"
          highlight-current
          lazy
          :load="loadNode"
          :props="treeProps"
          :default-expanded-keys="defaultExpandedKeys"
          :current-node-key="treeCurrentNodeKey"
          :filter-node-method="filterNode"
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <div
              class="tree-node"
              :class="{ 'is-account': data.nodeType === 'ACCOUNT' }"
              @dblclick.stop="onNodeDblClick(data)"
            >
              <el-icon class="node-ico">
                <User v-if="data.nodeType === 'ACCOUNT'" />
                <Monitor v-else-if="data.nodeType === 'HOST'" />
                <Folder v-else />
              </el-icon>
              <div class="node-text">
                <span class="node-label" :title="nodeTitle(data)">{{ data.label }}</span>
                <span v-if="nodeSubtitle(data)" class="node-sub">{{ nodeSubtitle(data) }}</span>
              </div>
              <el-dropdown class="node-actions" trigger="click" @command="(command) => onNodeAction(command, data)" @click.stop>
                <el-button text circle size="small" title="更多操作" @click.stop><el-icon><MoreFilled /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑</el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'GROUP'" command="host">新建主机</el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'HOST'" command="account">新建账户</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-tree></el-scrollbar>
        <div class="aside-hint">双击分组/主机编辑；双击账户打开终端</div>
        </div>
      </el-aside>

      <el-main class="main">
        <ScriptManagement v-if="scriptsVisited" v-show="accountTab==='scripts'" :active="accountTab==='scripts'" :host-id="detail.type==='ACCOUNT' ? detail.raw?.hostId : detail.type==='HOST' ? detail.raw?.id : null" :account-id="detail.type==='ACCOUNT' ? detail.raw?.id : null" :session-token="sessionToken" :opening-session="openingFileSession" @request-session="establishFileSession" />
        <el-empty v-if="!detail.type && accountTab!=='scripts'" description="在左侧选择分组、主机或账户" />
        <div v-else-if="detail.type === 'ACCOUNT'" v-show="accountTab!=='scripts'" class="account-workspace">
          <div class="detail-head account-head">
            <h2>{{ accountTabTitle }}</h2>
            <div class="detail-actions">
              <el-button :loading="testing" @click="runTest">测试连接</el-button>
              <el-button type="primary" @click="openEditFromDetail">编辑</el-button>
              <el-button type="danger" plain @click="confirmDeleteDetail">删除</el-button>
            </div>
          </div>
          <div class="account-tabs route-panels">
            <div v-show="accountTab === 'terminal'" class="account-tab-pane">
              <div v-if="detail.raw?.id && terminalVisited" class="terminal-split">
                <SshMonitorPanel
                  :account-id="detail.raw.id"
                  :session-token="sessionToken"
                  :active="accountTab === 'terminal'"
                />
                <SshTerminal
                  :key="terminalMountKey"
                  class="terminal-main"
                  :account-id="detail.raw.id"
                  :host-remark="accountHostRemark"
                  :account-remark="detail.raw.remark || ''"
                  @ready="onTerminalReady"
                  @disconnected="onTerminalDisconnected"
                />
              </div>
            </div>
            <div v-show="accountTab === 'files'" class="account-tab-pane">
              <SshFilePanel
                :account-id="detail.raw.id"
                :active="accountTab === 'files'"
                v-if="detail.raw?.id"
                v-show="accountTab === 'files'"
                :session-token="sessionToken"
                :default-cwd="detail.raw.defaultCwd || ''"
                :opening-session="openingFileSession"
                @request-session="establishFileSession"
                @session-invalid="onFileSessionInvalid"
              />
            </div>
            <div v-show="accountTab === 'processes'" class="account-tab-pane">
              <SshProcessPanel
                v-if="detail.raw?.id"
                v-show="accountTab === 'processes'"
                :session-token="sessionToken"
                :active="accountTab === 'processes'"
                @request-session="establishFileSession"
              />
            </div>
            <div v-show="accountTab === 'disk-usage'" class="account-tab-pane"><SshDiskPanel v-if="detail.raw?.id" :key="detail.raw.id" :account-id="detail.raw.id" :session-token="sessionToken" @request-session="establishFileSession" /></div>
          </div>
        </div>
        <div v-else-if="detail.type && accountTab!=='scripts'" class="detail-panel">
          <div class="detail-head">
            <h2>{{ detailTitle }}</h2>
            <div class="detail-actions">
              <el-button type="primary" @click="openEditFromDetail">编辑</el-button>
              <el-button type="danger" plain @click="confirmDeleteDetail">删除</el-button>
            </div>
          </div>
          <el-descriptions :column="1" border size="small" class="detail-desc">
            <el-descriptions-item v-for="row in detailRows" :key="row.label" :label="row.label">
              {{ row.value }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-main>
    </el-container>

    <!-- 分组 -->
    <el-dialog v-model="groupDlg" :title="groupForm.id ? '编辑分组' : '新建分组'" width="480px" destroy-on-close><el-scrollbar max-height="65vh">
      <el-form ref="groupFormRef" :model="groupForm" :rules="groupRules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="groupForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="父分组">
          <el-tree-select
            v-model="groupForm.parentId"
            :data="groupParentTree"
            check-strictly
            clearable
            filterable
            placeholder="不选则为根分组"
            style="width: 100%"
            :render-after-expand="false"
            popper-class="ssh-group-tree-popper"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="groupForm.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="groupForm.remark" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="环境说明、项目归属等" />
        </el-form-item>
      </el-form>
      </el-scrollbar><template #footer>
        <el-button @click="groupDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 主机 -->
    <el-dialog v-model="hostDlg" :title="hostForm.id ? '编辑主机' : '新建主机'" width="520px" destroy-on-close><el-scrollbar max-height="65vh">
      <el-form ref="hostFormRef" :model="hostForm" :rules="hostRules" label-width="88px">
        <el-form-item label="分组" prop="groupId">
          <el-input v-if="hostGroupLocked" :model-value="hostGroupLabel" disabled />
          <el-tree-select
            v-else
            v-model="hostForm.groupId"
            :data="hostGroupTree"
            check-strictly
            filterable
            placeholder="选择所属分组"
            style="width: 100%"
            :render-after-expand="false"
            popper-class="ssh-group-tree-popper"
          />
        </el-form-item>
        <el-form-item label="展示名" prop="name">
          <el-input v-model="hostForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="地址" prop="host">
          <el-input v-model="hostForm.host" placeholder="IP 或域名" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="hostForm.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="hostForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="hostForm.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="hostForm.remark" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="机房、用途、维护人等" />
        </el-form-item>
      </el-form>
      </el-scrollbar><template #footer>
        <el-button @click="hostDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveHost">保存</el-button>
      </template>
    </el-dialog>

    <!-- 账户 -->
    <el-dialog v-model="accountDlg" :title="accountForm.id ? '编辑账户' : '新建账户'" width="560px" destroy-on-close><el-scrollbar max-height="65vh">
      <el-form ref="accountFormRef" :model="accountForm" :rules="accountRules" label-width="100px">
        <el-form-item label="主机" prop="hostId">
          <el-input v-if="accountHostLocked" :model-value="accountHostLabel" disabled />
          <el-input-number v-else v-model="accountForm.hostId" :min="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="accountForm.username" maxlength="64" />
        </el-form-item>
        <el-form-item label="认证方式" prop="authType">
          <el-select v-model="accountForm.authType" style="width: 100%;">
            <el-option label="密码" value="PASSWORD" />
            <el-option label="私钥" value="PRIVATE_KEY" />
            <el-option label="私钥+口令" value="PRIVATE_KEY_WITH_PASSPHRASE" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="accountForm.authType === 'PASSWORD'" :label="accountForm.id ? '新密码' : '密码'">
          <el-input v-model="secretPassword" type="password" show-password autocomplete="new-password" :placeholder="accountForm.id ? '留空表示不修改' : '必填'" />
        </el-form-item>
        <template v-else>
          <el-form-item :label="accountForm.id ? '新私钥' : '私钥 PEM'">
            <el-input v-model="secretPrivateKey" type="textarea" :rows="4" :placeholder="accountForm.id ? '留空表示不修改' : '必填'" />
          </el-form-item>
          <el-form-item v-if="accountForm.authType === 'PRIVATE_KEY_WITH_PASSPHRASE'" label="私钥口令">
            <el-input v-model="secretPassphrase" type="password" show-password autocomplete="new-password" />
          </el-form-item>
        </template>
        <el-form-item label="默认目录">
          <el-input v-model="accountForm.defaultCwd" placeholder="可选，终端/SFTP 默认 cwd" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="accountForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="accountForm.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="accountForm.remark" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="密钥存放、sudo 说明等" />
        </el-form-item>
      </el-form>
      </el-scrollbar><template #footer>
        <el-button @click="accountDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAccount">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Expand, Fold, Folder, Monitor, MoreFilled, Plus, Refresh, Search, User } from '@element-plus/icons-vue'
import { managedServer } from '@/composables/useWorkspaceContext'
import SshTerminal from '@/components/ssh/SshTerminal.vue'
import SshFilePanel from '@/components/ssh/SshFilePanel.vue'
import SshMonitorPanel from '@/components/ssh/SshMonitorPanel.vue'
import ScriptManagement from '@/components/ssh/ScriptManagement.vue'
import SshProcessPanel from '@/components/ssh/SshProcessPanel.vue'
import SshDiskPanel from '@/components/ssh/SshDiskPanel.vue'
import {
  createAccount,
  createGroup,
  createHost,
  deleteAccount,
  deleteGroup,
  deleteHost,
  getAccount,
  getGroup,
  getHost,
  fetchTreeChildren,
  testAccount,
  openAccountSession,
  updateAccount,
  updateGroup,
  updateHost
} from '@/api/sshConsole'
import {
  clearSavedSshAccount,
  readSavedSshAccount,
  resolveSshExpandedKeys,
  writeSavedSshAccount
} from '@/utils/sshConsoleStorage'

const savedAccount = readSavedSshAccount()
const sidebarCollapsed = ref(false)
try { sidebarCollapsed.value=localStorage.getItem('doumi-sidebar-collapsed')==='true' } catch { /* Default expanded. */ }
watch(sidebarCollapsed, value=>{try{localStorage.setItem('doumi-sidebar-collapsed',String(value))}catch{/* Preference only. */}})

const treeRef = ref(null)
const filterText = ref('')
const treeVersion = ref(0)
const treeLoading = ref(false)
const selectedNode = ref(null)
const defaultExpandedKeys = ref([])
const treeCurrentNodeKey = ref(savedAccount?.key ?? null)
const saving = ref(false)
const testing = ref(false)

const treeProps = { label: 'label', isLeaf: 'leaf' }

const groupDlg = ref(false)
const groupForm = ref({})
const groupFormRef = ref(null)
const groupParentTree = ref([])
const hostGroupTree = ref([])
const groupNameById = ref({})

const hostDlg = ref(false)
const hostForm = ref({})
const hostFormRef = ref(null)
const hostGroupLocked = ref(false)
const hostGroupLabel = ref('')

const accountDlg = ref(false)
const accountForm = ref({})
const accountFormRef = ref(null)
const accountHostLocked = ref(false)
const accountHostLabel = ref('')
const secretPassword = ref('')
const secretPrivateKey = ref('')
const secretPassphrase = ref('')

const detail = ref({ type: null, raw: null })
const route = useRoute()
const router = useRouter()
const accountTab = computed(() => route.meta.panel || '')
const terminalVisited = ref(accountTab.value === 'terminal')
const scriptsVisited = ref(accountTab.value === 'scripts')
watch(accountTab, tab => { if (tab === 'scripts') scriptsVisited.value = true })
watch(accountTab, tab => { if (tab === 'terminal') terminalVisited.value = true })
const accountHostMeta = ref({ name: '', remark: '' })
const sessionToken = ref('')
const terminalMountKey = ref(0)
const openingFileSession = ref(false)

const groupRules = { name: [{ required: true, message: '请输入名称', trigger: 'blur' }] }
const hostRules = {
  groupId: [{ required: true, message: '请选择分组', trigger: 'change' }],
  name: [{ required: true, message: '请输入展示名', trigger: 'blur' }],
  host: [{ required: true, message: '请输入地址', trigger: 'blur' }]
}
const accountRules = {
  hostId: [{ required: true, message: 'hostId 必填', trigger: 'change' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  authType: [{ required: true, message: '请选择认证方式', trigger: 'change' }]
}

const selectedGroupId = computed(() => {
  const n = selectedNode.value
  if (!n) return null
  if (n.nodeType === 'GROUP') return n.id
  if (n.nodeType === 'HOST') return n.extra?.groupId
  return null
})

const selectedHostId = computed(() => {
  const n = selectedNode.value
  if (!n) return null
  if (n.nodeType === 'HOST') return n.id
  if (n.nodeType === 'ACCOUNT') return n.extra?.hostId
  return null
})

if (savedAccount) {
  resolveSshExpandedKeys(savedAccount, { getGroup, getHost }).then((keys) => {
    defaultExpandedKeys.value = keys
  })
}

watch(filterText, (val) => {
  treeRef.value?.filter(val)
})

onMounted(() => {
  restoreSavedAccount()
  window.addEventListener('doumi-configuration-imported', refreshTree)
})
onBeforeUnmount(() => window.removeEventListener('doumi-configuration-imported', refreshTree))

async function persistAccountSelection(node, hostGroupId) {
  if (node?.nodeType !== 'ACCOUNT') return
  writeSavedSshAccount({
    id: node.id,
    key: node.key,
    hostId: node.extra?.hostId,
    groupId: hostGroupId ?? null,
    label: node.label
  })
}

async function restoreSavedAccount() {
  const saved = readSavedSshAccount()
  if (!saved) return
  let acc
  try {
    acc = await getAccount(saved.id)
  } catch {
    clearSavedSshAccount()
    return
  }
  let groupId = saved.groupId
  if (groupId == null && acc.hostId) {
    try {
      const h = await getHost(acc.hostId)
      groupId = h?.groupId ?? null
    } catch {
      groupId = null
    }
  }
  const keys = await resolveSshExpandedKeys(
    { ...saved, hostId: acc.hostId, groupId },
    { getGroup, getHost }
  )
  defaultExpandedKeys.value = keys

  const node = {
    nodeType: 'ACCOUNT',
    id: saved.id,
    key: saved.key || `a:${saved.id}`,
    label: saved.label || acc.username,
    leaf: true,
    extra: { hostId: acc.hostId }
  }
  selectedNode.value = node
  treeCurrentNodeKey.value = node.key
  await loadDetail(node)
  await nextTick()
  if (keys.length && treeRef.value?.setExpandedKeys) {
    treeRef.value.setExpandedKeys(keys)
  }
  treeRef.value?.setCurrentKey?.(node.key)
}

function nodeTitle(data) {
  if (data.remark) return `${data.label}\n${data.remark}`
  return data.label
}

function nodeSubtitle(data) {
  if (data.nodeType === 'HOST' && data.extra?.endpoint) return data.extra.endpoint
  if (data.remark) return data.remark
  return ''
}

function filterNode(value, data) {
  if (!value) return true
  const v = String(value)
  return (data.label && data.label.includes(v)) || (data.remark && data.remark.includes(v))
}

async function loadNode(node, resolve) {
  if (node.level === 0) {
    treeLoading.value = true
    try {
      const list = await fetchTreeChildren('ROOT')
      resolve(mapNodes(list))
    } catch {
      resolve([])
    } finally {
      treeLoading.value = false
    }
    return
  }
  const data = node.data
  try {
    if (data.nodeType === 'GROUP') {
      resolve(mapNodes(await fetchTreeChildren('GROUP', data.id)))
    } else if (data.nodeType === 'HOST') {
      resolve(mapNodes(await fetchTreeChildren('HOST', data.id)))
    } else {
      resolve([])
    }
  } catch {
    resolve([])
  }
}

function mapNodes(list) {
  return (list || []).map((n) => ({
    ...n,
    leaf: n.leaf === true
  }))
}

function refreshTree() {
  treeVersion.value += 1
  detail.value = { type: null, raw: null }
  nextTick(() => restoreSavedAccount())
}

function onNodeClick(data) {
  selectedNode.value = data
  treeCurrentNodeKey.value = data.key
  loadDetail(data)
}

async function loadDetail(data) {
  sessionToken.value = ''
  try {
    if (data.nodeType === 'GROUP') {
      detail.value = { type: 'GROUP', raw: await getGroup(data.id) }
    } else if (data.nodeType === 'HOST') {
      detail.value = { type: 'HOST', raw: await getHost(data.id) }
    } else {
      const raw = await getAccount(data.id)
      detail.value = { type: 'ACCOUNT', raw }
      terminalVisited.value = accountTab.value === 'terminal'
      terminalMountKey.value += 1
      await loadAccountHostMeta(raw.hostId)
      let groupId = null
      if (raw.hostId) {
        try {
          const h = await getHost(raw.hostId)
          groupId = h?.groupId ?? null
        } catch {
          groupId = null
        }
      }
      await persistAccountSelection(data, groupId)
    }
  } catch {
    detail.value = { type: data.nodeType, raw: data }
    if (data.nodeType === 'ACCOUNT' && data.extra?.hostId) {
      await loadAccountHostMeta(data.extra.hostId)
    }
  }
}

async function loadAccountHostMeta(hostId) {
  if (!hostId) {
    accountHostMeta.value = { name: '', remark: '' }
    return
  }
  try {
    const h = await getHost(hostId)
    accountHostMeta.value = { name: h.name || '', remark: h.remark || '', host: h.host || '', port: h.port || 22 }
  } catch {
    accountHostMeta.value = { name: '', remark: '' }
  }
}

watch([detail,accountHostMeta],()=>{
  const raw=detail.value.raw
  if(detail.value.type==='ACCOUNT'&&raw) {
    const host=accountHostMeta.value
    managedServer.value={name:host.name||`主机#${raw.hostId}`,address:host.host||'',port:host.port||22,username:raw.username||''}
  } else if(detail.value.type==='HOST'&&raw) {
    managedServer.value={name:raw.name||raw.host,address:raw.host||'',port:raw.port||22,username:''}
  } else managedServer.value=null
},{deep:true,immediate:true})

const accountHostRemark = computed(() => accountHostMeta.value.remark || '')

const accountTabTitle = computed(() => {
  const r = detail.value.raw
  if (!r) return ''
  const hostLabel = accountHostMeta.value.name || `主机#${r.hostId}`
  const remark = r.remark ? ` — ${r.remark}` : ''
  return `${r.username}@${hostLabel}${remark}`
})

function onTerminalReady(payload) {
  sessionToken.value = payload?.sessionToken || ''
}

function onTerminalDisconnected() {
  sessionToken.value = ''
}

function onFileSessionInvalid() {
  sessionToken.value = ''
}

watch([accountTab,()=>detail.value.raw?.id], ([tab]) => {
  const needsSession = ['files','scripts','processes','disk-usage'].includes(tab)
  if (needsSession && detail.value.type==='ACCOUNT' && detail.value.raw?.id && !sessionToken.value && !openingFileSession.value) {
    establishFileSession()
  }
})

async function establishFileSession() {
  const id = detail.value.raw?.id
  if (!id || sessionToken.value || openingFileSession.value) return
  openingFileSession.value = true
  try {
    const r = await openAccountSession(id)
    if(detail.value.raw?.id!==id || detail.value.type!=='ACCOUNT')return
    sessionToken.value = r?.sessionToken || ''
    if (!sessionToken.value) {
      ElMessage.error('未能获取 sessionToken')
    }
  } finally {
    openingFileSession.value = false
  }
}

const detailTitle = computed(() => {
  const t = detail.value.type
  const r = detail.value.raw
  if (!t || !r) return ''
  if (t === 'GROUP') return `分组：${r.name}`
  if (t === 'HOST') return `主机：${r.name}`
  return `账户：${r.username}`
})

const detailRows = computed(() => {
  const t = detail.value.type
  const r = detail.value.raw
  if (!r) return []
  if (t === 'GROUP') {
    return [
      { label: 'ID', value: r.id },
      { label: '父分组', value: r.parentId ?? '（根）' },
      { label: '名称', value: r.name },
      { label: '排序', value: r.sortOrder },
      { label: '备注', value: r.remark || '—' }
    ]
  }
  if (t === 'HOST') {
    return [
      { label: 'ID', value: r.id },
      { label: '分组 ID', value: r.groupId },
      { label: '展示名', value: r.name },
      { label: '地址', value: `${r.host}:${r.port ?? 22}` },
      { label: '启用', value: r.enabled === 1 ? '是' : '否' },
      { label: '排序', value: r.sortOrder },
      { label: '备注', value: r.remark || '—' }
    ]
  }
  return [
    { label: 'ID', value: r.id },
    { label: '主机 ID', value: r.hostId },
    { label: '用户名', value: r.username },
    { label: '认证', value: r.authType },
    { label: '凭据', value: r.hasSecret ? (r.secretHint || '已保存') : '未配置' },
    { label: '默认目录', value: r.defaultCwd || '—' },
    { label: '启用', value: r.enabled === 1 ? '是' : '否' },
    { label: '备注', value: r.remark || '—' }
  ]
})

function onNodeDblClick(data) {
  selectedNode.value = data
  if (data.nodeType === 'ACCOUNT') {
    loadDetail(data)
    router.push('/terminal')
    return
  }
  if (data.nodeType === 'GROUP') editGroupNode(data)
  else if (data.nodeType === 'HOST') editHostNode(data)
  else editAccountNode(data)
}

function onNodeAction(command, data) {
  selectedNode.value = data
  if (command === 'edit') {
    if (data.nodeType === 'GROUP') editGroupNode(data)
    else if (data.nodeType === 'HOST') editHostNode(data)
    else editAccountNode(data)
    return
  }
  if (command === 'host') return openAddHost(data.id)
  if (command === 'account') return openAddAccount(data.id)
  if (command === 'delete') {
    loadDetail(data)
    nextTick(() => confirmDeleteDetail())
  }
}

function onAddCommand(cmd) {
  if (cmd === 'group') openAddGroup()
  else if (cmd === 'host') openAddHost(selectedGroupId.value)
  else if (cmd === 'account') openAddAccount(selectedHostId.value)
}

async function loadAllGroupsFlat() {
  const out = []
  async function walk(parentType, parentId) {
    const list = await fetchTreeChildren(parentType, parentId)
    for (const n of list) {
      if (n.nodeType !== 'GROUP') continue
      out.push({
        id: n.id,
        parentId: parentType === 'ROOT' ? null : parentId,
        name: n.label
      })
      await walk('GROUP', n.id)
    }
  }
  await walk('ROOT')
  return out
}

function collectDescendantIds(flat, rootId) {
  const ids = new Set([rootId])
  let changed = true
  while (changed) {
    changed = false
    for (const g of flat) {
      if (g.parentId != null && ids.has(g.parentId) && !ids.has(g.id)) {
        ids.add(g.id)
        changed = true
      }
    }
  }
  return ids
}

function buildGroupParentTree(flat, excludeIds) {
  const items = flat.filter((g) => !excludeIds.has(g.id))
  const map = new Map()
  for (const g of items) {
    map.set(g.id, { value: g.id, label: g.name, children: [] })
  }
  const roots = []
  for (const g of items) {
    const node = map.get(g.id)
    if (g.parentId == null || !map.has(g.parentId)) {
      roots.push(node)
    } else {
      map.get(g.parentId).children.push(node)
    }
  }
  const prune = (nodes) => {
    for (const n of nodes) {
      if (n.children?.length) {
        prune(n.children)
      } else {
        delete n.children
      }
    }
  }
  prune(roots)
  return roots
}

function applyGroupFlat(flat, editingGroupId) {
  const nameMap = {}
  for (const g of flat) {
    nameMap[g.id] = g.name
  }
  groupNameById.value = nameMap
  hostGroupTree.value = buildGroupParentTree(flat, new Set())
  const exclude = editingGroupId != null ? collectDescendantIds(flat, editingGroupId) : new Set()
  groupParentTree.value = buildGroupParentTree(flat, exclude)
}

function groupDisplayName(groupId, fallbackLabel) {
  if (groupId == null) return ''
  return groupNameById.value[groupId] || fallbackLabel || `分组 #${groupId}`
}

async function refreshGroupParentOptions(editingGroupId) {
  try {
    const flat = await loadAllGroupsFlat()
    applyGroupFlat(flat, editingGroupId)
  } catch {
    groupParentTree.value = []
    hostGroupTree.value = []
    groupNameById.value = {}
  }
}

async function resolveHostLabel(hostId) {
  if (hostId == null) return ''
  const n = selectedNode.value
  if (n?.nodeType === 'HOST' && n.id === hostId) {
    const addr = n.extra?.host && n.extra?.port ? ` (${n.extra.host}:${n.extra.port})` : ''
    return `${n.label}${addr}`
  }
  try {
    const h = await getHost(hostId)
    return `${h.name} (${h.host}:${h.port ?? 22})`
  } catch {
    return `主机 #${hostId}`
  }
}

async function openAddGroup() {
  await refreshGroupParentOptions(null)
  const defaultParent =
    selectedNode.value?.nodeType === 'GROUP' ? selectedNode.value.id : null
  groupForm.value = { parentId: defaultParent, name: '', remark: '', sortOrder: 0 }
  groupDlg.value = true
}

async function editGroupNode(data) {
  const g = await getGroup(data.id)
  await refreshGroupParentOptions(g.id)
  groupForm.value = { ...g }
  groupDlg.value = true
}

async function openAddHost(groupId) {
  await refreshGroupParentOptions(null)
  const locked = groupId != null
  hostGroupLocked.value = locked
  const fallback =
    selectedNode.value?.nodeType === 'GROUP' && selectedNode.value.id === groupId
      ? selectedNode.value.label
      : ''
  hostGroupLabel.value = locked ? groupDisplayName(groupId, fallback) : ''
  hostForm.value = { groupId, name: '', host: '', port: 22, remark: '', sortOrder: 0, enabled: 1 }
  hostDlg.value = true
}

async function editHostNode(data) {
  await refreshGroupParentOptions(null)
  const h = await getHost(data.id)
  hostGroupLocked.value = false
  hostForm.value = { ...h, port: h.port ?? 22 }
  hostDlg.value = true
}

async function openAddAccount(hostId) {
  accountHostLocked.value = hostId != null
  accountHostLabel.value = hostId != null ? await resolveHostLabel(hostId) : ''
  secretPassword.value = ''
  secretPrivateKey.value = ''
  secretPassphrase.value = ''
  accountForm.value = {
    hostId,
    username: '',
    authType: 'PASSWORD',
    defaultCwd: '',
    remark: '',
    sortOrder: 0,
    enabled: 1
  }
  accountDlg.value = true
}

async function editAccountNode(data) {
  const a = await getAccount(data.id)
  accountHostLocked.value = true
  accountHostLabel.value = await resolveHostLabel(a.hostId)
  secretPassword.value = ''
  secretPrivateKey.value = ''
  secretPassphrase.value = ''
  accountForm.value = { ...a }
  accountDlg.value = true
}

function openEditFromDetail() {
  const n = selectedNode.value
  if (!n) return
  if (n.nodeType === 'GROUP') editGroupNode(n)
  else if (n.nodeType === 'HOST') editHostNode(n)
  else if (n.nodeType === 'ACCOUNT') editAccountNode(n)
}

async function saveGroup() {
  await groupFormRef.value?.validate()
  saving.value = true
  try {
    const body = { ...groupForm.value }
    if (body.id) await updateGroup(body.id, body)
    else await createGroup(body)
    groupDlg.value = false
    refreshTree()
  } finally {
    saving.value = false
  }
}

async function saveHost() {
  await hostFormRef.value?.validate()
  saving.value = true
  try {
    const body = { ...hostForm.value }
    if (body.id) await updateHost(body.id, body)
    else await createHost(body)
    hostDlg.value = false
    refreshTree()
  } finally {
    saving.value = false
  }
}

function buildSecretPayload() {
  if (accountForm.value.authType === 'PASSWORD') {
    if (!secretPassword.value && !accountForm.value.id) return null
    if (!secretPassword.value) return undefined
    return { password: secretPassword.value }
  }
  if (!secretPrivateKey.value && !accountForm.value.id) return null
  if (!secretPrivateKey.value) return undefined
  const s = { privateKeyPem: secretPrivateKey.value }
  if (accountForm.value.authType === 'PRIVATE_KEY_WITH_PASSPHRASE') {
    s.passphrase = secretPassphrase.value
  }
  return s
}

async function saveAccount() {
  await accountFormRef.value?.validate()
  const secret = buildSecretPayload()
  if (secret === null) {
    ElMessage.warning('请填写凭据')
    return
  }
  saving.value = true
  try {
    const body = { ...accountForm.value }
    if (secret !== undefined) body.secret = secret
    if (body.id) await updateAccount(body.id, body)
    else await createAccount(body)
    accountDlg.value = false
    refreshTree()
  } finally {
    saving.value = false
  }
}

async function runTest() {
  const id = detail.value.raw?.id
  if (!id) return
  testing.value = true
  try {
    const r = await testAccount(id)
    if (r?.success) ElMessage.success(`连接成功（${r.elapsedMs} ms）`)
    else ElMessage.error(r?.message || '连接失败')
  } finally {
    testing.value = false
  }
}

async function confirmDeleteDetail() {
  const n = selectedNode.value
  if (!n) return
  await ElMessageBox.confirm(`确定删除「${n.label}」？`, '确认', { type: 'warning' })
  if (n.nodeType === 'GROUP') await deleteGroup(n.id)
  else if (n.nodeType === 'HOST') await deleteHost(n.id)
  else {
    const saved = readSavedSshAccount()
    if (saved?.id === n.id) clearSavedSshAccount()
    await deleteAccount(n.id)
  }
  detail.value = { type: null, raw: null }
  treeCurrentNodeKey.value = null
  refreshTree()
}
</script>

<style scoped>
.ssh-console {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.layout {
  height: 100%;
}
.aside {
  border-right: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  padding: 8px;
  box-sizing: border-box;
}
.aside-content {display:flex;flex-direction:column;flex:1;min-height:0}
.sidebar-toggle {justify-content:flex-start;margin-bottom:8px;flex-shrink:0}
.aside-collapsed .sidebar-toggle {padding:8px;justify-content:center}
.asset-scroll {flex:1;min-height:0}
.aside-toolbar {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
  align-items: center;
}
.aside-filter {
  flex: 1;
}
.asset-tree {
  flex: 1;
  min-height: 0;
}
.asset-tree :deep(.el-tree-node__content) {
  height: auto;
  min-height: 40px;
  align-items: flex-start;
  padding-top: 6px;
  padding-bottom: 6px;
  border-radius: 6px;
}
.asset-tree :deep(.el-tree-node__expand-icon) {
  margin-top: 2px;
  font-size: 14px;
}
.tree-node {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex: 1;
  min-width: 0;
  padding-right: 4px;
  line-height: 1.35;
}
.node-ico {
  margin-top: 2px;
  font-size: 16px;
  flex-shrink: 0;
}
.node-text {
  flex: 1;
  min-width: 0;
}
.node-actions { margin-left:auto; opacity:0; flex-shrink:0 }
.tree-node:hover .node-actions, .tree-node:focus-within .node-actions { opacity:1 }
.node-label {
  display: block;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-sub {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.35;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  word-break: break-all;
}
.aside-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
}
.main {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.detail-head h2 {
  margin: 0;
  font-size: 18px;
}
.detail-actions {
  display: flex;
  gap: 8px;
}
.detail-desc {
  max-width: 640px;
}
.account-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.account-head {
  flex-shrink: 0;
}
.account-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.account-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
.account-tabs :deep(.el-tabs__header) {
  flex-shrink: 0;
  margin-bottom: 8px;
  position: relative;
  z-index: 2;
}
.account-tabs :deep(.el-tabs__nav-wrap) {
  overflow: visible;
}
.account-tabs :deep(.el-tabs__item) {
  min-height: 40px;
  padding: 0 22px;
  font-size: 14px;
  line-height: 40px;
}
.account-tabs :deep(.el-tabs__content) {
  position: relative;
  z-index: 1;
}
.account-tab-pane {
  height: 100%;
  overflow: hidden;
}
.terminal-split {
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.terminal-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.account-tabs :deep(.el-tab-pane) {
  height: 100%;
  overflow: hidden;
}
.sftp-stub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
</style>

<style>
.ssh-group-tree-popper .el-select-dropdown__wrap {
  max-height: min(60vh, 420px);
}
.ssh-group-tree-popper .el-tree {
  min-height: 120px;
  padding: 6px 0;
}
.ssh-group-tree-popper .el-tree-node__content {
  min-height: 34px;
  height: auto;
  padding-top: 4px;
  padding-bottom: 4px;
}
</style>
