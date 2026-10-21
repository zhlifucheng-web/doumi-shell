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
          :draggable="!filterText"
          :allow-drop="allowTreeDrop"
          @node-click="onNodeClick"
          @node-drag-start="onNodeDragStart"
          @node-drop="onNodeDrop"
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
        <div class="aside-hint">拖拽分组、主机或账户可排序和移动；双击分组/主机编辑，双击账户打开终端</div>
        </div>
      </el-aside>

      <el-main class="main">
        <ScriptManagement v-if="scriptsVisited" v-show="accountTab==='scripts'" :active="accountTab==='scripts'" :host-id="detail.type==='ACCOUNT' ? detail.raw?.hostId : detail.type==='HOST' ? detail.raw?.id : null" :account-id="detail.type==='ACCOUNT' ? detail.raw?.id : null" :session-token="sessionToken" :opening-session="openingFileSession" @request-session="establishFileSession" />
        <el-empty v-if="!detail.type && accountTab!=='scripts'" description="在左侧选择分组、主机或账户" />
        <div v-else-if="detail.type === 'ACCOUNT'" v-show="accountTab!=='scripts'" class="account-workspace">
          <div class="account-tabs route-panels">
            <div v-show="accountTab === 'terminal'" class="account-tab-pane">
              <TerminalFileLayout>
                <template #monitor>
                  <SshMonitorPanel v-if="activeTerminalTab && terminalVisited"
                    :account-id="activeTerminalTab.accountId" :session-token="sessionToken" :active="accountTab === 'terminal'" />
                </template>
                <template #terminal>
                  <div v-if="terminalTabs.length" class="terminal-tabs">
                    <div class="terminal-tab-bar" role="tablist" aria-label="已打开的终端">
                      <button v-for="tab in terminalTabs" :key="tab.tabId" type="button" role="tab"
                        class="terminal-tab" :class="{ 'is-active': activeTerminalId === tab.tabId }"
                        :aria-selected="activeTerminalId === tab.tabId" @click="activateTerminalTab(tab.tabId)">
                        <span>{{ tab.label }}</span>
                        <el-icon class="terminal-tab-close" title="关闭终端" @click.stop="closeTerminalTab(tab.tabId)"><Close /></el-icon>
                      </button>
                      <button class="terminal-tab-add" type="button" title="打开同一账户的新终端" @click="openAdditionalTerminalTab">+</button>
                    </div>
                    <div v-for="tab in terminalTabs" :key="`terminal-${tab.tabId}`" v-show="activeTerminalId === tab.tabId" class="terminal-tab-content">
                      <SshTerminal :account-id="tab.accountId" :terminal-id="tab.tabId"
                        @ready="payload => onTerminalReady(tab.tabId, payload)"
                        @disconnected="() => onTerminalDisconnected(tab.tabId)" />
                    </div>
                  </div>
                  <el-empty v-else description="从左侧选择账户以打开终端" />
                </template>
                <template #files>
                  <SshFilePanel v-if="activeTerminalTab" :account-id="activeTerminalTab.accountId" :host-id="activeTerminalTab.hostId"
                    :active="accountTab === 'terminal'" embedded
                    :session-token="sessionToken" :default-cwd="activeTerminalTab.defaultCwd || ''"
                    :opening-session="openingFileSession" @request-session="establishFileSession"
                    @session-invalid="onFileSessionInvalid" />
                </template>
              </TerminalFileLayout>
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
          <el-select v-model="groupForm.parentId" clearable filterable placeholder="不选则为根分组" style="width: 100%">
            <el-option v-for="option in groupParentOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
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
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Expand, Fold, Folder, Monitor, MoreFilled, Plus, Refresh, Search, User } from '@element-plus/icons-vue'
import { useWorkspaceStore } from '@/stores/workspace'
import SshTerminal from '@/components/ssh/SshTerminal.vue'
import TerminalFileLayout from '@/components/ssh/TerminalFileLayout.vue'
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
  moveTreeNode,
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
const { managedServer } = storeToRefs(useWorkspaceStore())
const sidebarCollapsed = ref(false)
try { sidebarCollapsed.value=localStorage.getItem('doumi-sidebar-collapsed')==='true' } catch { /* Default expanded. */ }
watch(sidebarCollapsed, value=>{try{localStorage.setItem('doumi-sidebar-collapsed',String(value))}catch{/* Preference only. */}})

const treeRef = ref(null)
const filterText = ref('')
const treeVersion = ref(0)
const treeLoading = ref(false)
let dragOrigin = null
const selectedNode = ref(null)
const defaultExpandedKeys = ref([])
const treeCurrentNodeKey = ref(savedAccount?.key ?? null)
const saving = ref(false)

const treeProps = { label: 'label', isLeaf: 'leaf' }

const groupDlg = ref(false)
const groupForm = ref({})
const groupFormRef = ref(null)
const groupParentOptions = ref([])
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
const terminalTabs = ref([])
const activeTerminalId = ref(null)
const activeTerminalTab = computed(() => terminalTabs.value.find(item => item.tabId === activeTerminalId.value) || null)
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

function onNodeDragStart(node) {
  const type = node.data.nodeType
  dragOrigin = {
    type,
    parentId: parentIdForNode(type, node.parent),
    siblingIds: (node.parent?.childNodes || [])
      .filter((sibling) => sibling.data.nodeType === type)
      .map((sibling) => sibling.data.id)
  }
}

function allowTreeDrop(draggingNode, dropNode, dropType) {
  const sourceType = draggingNode.data.nodeType
  const targetType = dropNode.data.nodeType
  if (dropType === 'inner') {
    if (sourceType === 'GROUP' && targetType === 'GROUP') {
      for (let node = dropNode; node && node.level > 0; node = node.parent) {
        if (node.data.id === draggingNode.data.id) return false
      }
      return true
    }
    return (sourceType === 'HOST' && targetType === 'GROUP') ||
      (sourceType === 'ACCOUNT' && targetType === 'HOST')
  }
  return (dropType === 'before' || dropType === 'after') && sourceType === targetType
}

function parentIdForNode(type, parentNode) {
  if (type === 'GROUP' && parentNode?.level === 0) return null
  return parentNode?.data?.id ?? null
}

async function onNodeDrop(draggingNode, dropNode, dropType) {
  const origin = dragOrigin
  dragOrigin = null
  if (!origin) return refreshTree()
  const type = draggingNode.data.nodeType
  const destinationParent = dropType === 'inner' ? dropNode : dropNode.parent
  const parentId = parentIdForNode(type, destinationParent)
  let orderedIds = (destinationParent?.childNodes || [])
    .filter((node) => node.data.nodeType === type)
    .map((node) => node.data.id)
  if (dropType === 'inner' && !destinationParent.loaded) {
    try {
      const parentType = destinationParent.data.nodeType === 'HOST' ? 'HOST' : 'GROUP'
      const children = await fetchTreeChildren(parentType, parentId)
      orderedIds = (children || [])
        .filter((node) => node.nodeType === type)
        .map((node) => node.id)
      if (!orderedIds.includes(draggingNode.data.id)) orderedIds.push(draggingNode.data.id)
    } catch (error) {
      ElMessage.error(error?.message || '读取目标节点内容失败，移动未保存')
      refreshTree()
      return
    }
  }
  const crossParent = origin.parentId !== parentId
  try {
    await moveTreeNode({
      nodeType: type,
      id: draggingNode.data.id,
      parentId,
      orderedIds,
      sourceOrderedIds: crossParent ? origin.siblingIds.filter((id) => id !== draggingNode.data.id) : null
    })
    if (selectedNode.value?.nodeType === type && selectedNode.value.id === draggingNode.data.id) {
      selectedNode.value = draggingNode.data
      await loadDetail(draggingNode.data)
    }
  } catch (error) {
    ElMessage.error(error?.message || '移动或排序失败，请刷新后重试')
    refreshTree()
  }
}

function refreshTree() {
  const current = selectedNode.value
  treeVersion.value += 1
  detail.value = { type: null, raw: null }
  nextTick(async () => {
    if (current?.nodeType && current.id != null) {
      await loadDetail(current)
      treeCurrentNodeKey.value = current.key
      treeRef.value?.setCurrentKey?.(current.key)
    } else {
      await restoreSavedAccount()
    }
  })
}

function onNodeClick(data) {
  selectedNode.value = data
  treeCurrentNodeKey.value = data.key
  loadDetail(data)
}

async function loadDetail(data) {
  try {
    if (data.nodeType === 'GROUP') {
      sessionToken.value = ''
      detail.value = { type: 'GROUP', raw: await getGroup(data.id) }
    } else if (data.nodeType === 'HOST') {
      sessionToken.value = ''
      detail.value = { type: 'HOST', raw: await getHost(data.id) }
    } else {
      const raw = await getAccount(data.id)
      detail.value = { type: 'ACCOUNT', raw }
      terminalVisited.value = accountTab.value === 'terminal'
      activateTerminalForAccount(raw)
      const hostMeta = await loadAccountHostMeta(raw.hostId)
      const openedTab = terminalTabs.value.find(item => item.accountId === raw.id)
      if (openedTab) {
        openedTab.hostName = hostMeta.name
        openedTab.hostAddress = hostMeta.host
        openedTab.label = terminalTabLabel(raw, hostMeta)
      }
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
    return accountHostMeta.value
  }
  try {
    const h = await getHost(hostId)
    accountHostMeta.value = { name: h.name || '', remark: h.remark || '', host: h.host || '', port: h.port || 22 }
    return accountHostMeta.value
  } catch {
    accountHostMeta.value = { name: '', remark: '' }
    return accountHostMeta.value
  }
}

watch([detail,accountHostMeta],()=>{
  const raw=detail.value.raw
  if(detail.value.type==='ACCOUNT'&&raw) {
    const host=accountHostMeta.value
    managedServer.value={hostId:raw.hostId,accountId:raw.id,name:host.name||`主机#${raw.hostId}`,address:host.host||'',port:host.port||22,username:raw.username||''}
  } else if(detail.value.type==='HOST'&&raw) {
    managedServer.value={hostId:raw.id,name:raw.name||raw.host,address:raw.host||'',port:raw.port||22,username:''}
  } else managedServer.value=null
},{deep:true,immediate:true})

function terminalTabLabel(account, host = accountHostMeta.value) {
  const hostLabel = `${host.name || account.host || `主机#${account.hostId}`} (${host.host || account.host || ''})`
  return `${hostLabel} · ${account.username || '账户'}`
}

function activateTerminalForAccount(account) {
  if (!account?.id) return
  let tab = terminalTabs.value.find(item => item.accountId === account.id)
  if (!tab) {
    tab = { tabId: newTerminalId(), accountId: account.id, account, hostId: account.hostId, defaultCwd: account.defaultCwd || '', label: terminalTabLabel(account), token: '' }
    terminalTabs.value.push(tab)
  } else {
    tab.hostId = account.hostId
    tab.account = account
    tab.defaultCwd = account.defaultCwd || ''
    tab.label = terminalTabLabel(account)
  }
  activeTerminalId.value = tab.tabId
  sessionToken.value = tab.token
}

function newTerminalId() {
  return `terminal-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function openAdditionalTerminalTab() {
  const current = activeTerminalTab.value
  if (!current) return
  const tab = { ...current, tabId: newTerminalId(), token: '' }
  terminalTabs.value.push(tab)
  activeTerminalId.value = tab.tabId
  sessionToken.value = ''
}

async function activateTerminalTab(tabId) {
  activeTerminalId.value = tabId
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  sessionToken.value = tab?.token || ''
  if (tab?.account) {
    detail.value = { type: 'ACCOUNT', raw: tab.account }
    await loadAccountHostMeta(tab.hostId)
    const node = { nodeType: 'ACCOUNT', id: tab.accountId, key: `a:${tab.accountId}`, label: tab.account.username, leaf: true, extra: { hostId: tab.hostId } }
    selectedNode.value = node
    treeCurrentNodeKey.value = node.key
  }
}

function closeTerminalTab(tabId) {
  const index = terminalTabs.value.findIndex(item => item.tabId === tabId)
  if (index < 0) return
  const wasActive = activeTerminalId.value === tabId
  terminalTabs.value.splice(index, 1)
  if (wasActive) {
    const next = terminalTabs.value[Math.min(index, terminalTabs.value.length - 1)]
    activeTerminalId.value = next?.tabId ?? null
    sessionToken.value = next?.token || ''
    if (next) {
      detail.value = { type: 'ACCOUNT', raw: next.account }
      void loadAccountHostMeta(next.hostId)
    }
  }
}

function onTerminalReady(tabId, payload) {
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  if (!tab) return
  tab.token = payload?.sessionToken || ''
  if (activeTerminalId.value === tabId) sessionToken.value = tab.token
}

function onTerminalDisconnected(tabId) {
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  if (tab) tab.token = ''
  if (activeTerminalId.value === tabId) sessionToken.value = ''
}

function onFileSessionInvalid() {
  sessionToken.value = ''
  const tab = terminalTabs.value.find(item => item.tabId === activeTerminalId.value)
  if (tab) tab.token = ''
}

watch([accountTab,()=>detail.value.raw?.id], ([tab]) => {
  const needsSession = ['scripts','processes','disk-usage'].includes(tab)
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

function buildGroupParentOptions(flat, excludeIds) {
  const byId = new Map(flat.map((group) => [group.id, group]))
  return flat.filter((group) => !excludeIds.has(group.id)).map((group) => {
    const path = [group.name]
    const seen = new Set([group.id])
    let parentId = group.parentId
    while (parentId != null && byId.has(parentId) && !seen.has(parentId)) {
      seen.add(parentId)
      const parent = byId.get(parentId)
      path.push(parent.name)
      parentId = parent.parentId
    }
    return { value: group.id, label: path.reverse().join(' / ') }
  })
}

function applyGroupFlat(flat, editingGroupId) {
  const nameMap = {}
  for (const g of flat) {
    nameMap[g.id] = g.name
  }
  groupNameById.value = nameMap
  hostGroupTree.value = buildGroupParentTree(flat, new Set())
  const exclude = editingGroupId != null ? collectDescendantIds(flat, editingGroupId) : new Set()
  groupParentOptions.value = buildGroupParentOptions(flat, exclude)
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
    groupParentOptions.value = []
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

async function confirmDeleteDetail() {
  const n = selectedNode.value
  if (!n) return
  const message = n.nodeType === 'HOST'
    ? `删除主机「${n.label}」会同时删除其全部账户、主机脚本及定时配置，并关闭这些账户的在线连接。历史执行记录会保留；有脚本运行时不能删除。`
    : n.nodeType === 'GROUP'
      ? `删除分组「${n.label}」会级联删除其全部子分组、主机、账户、主机脚本和定时配置，并关闭相关在线连接。历史执行记录会保留；分组树中有脚本运行时不能删除。`
      : `确定删除「${n.label}」？`
  const title = n.nodeType === 'GROUP' ? '级联删除分组' : n.nodeType === 'HOST' ? '删除主机及关联配置' : '确认'
  await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '删除' })
  if (n.nodeType === 'GROUP') {
    const saved = readSavedSshAccount()
    if (saved && await isHostInsideGroup(saved.hostId, n.id)) clearSavedSshAccount()
    await deleteGroup(n.id)
  }
  else if (n.nodeType === 'HOST') {
    const saved = readSavedSshAccount()
    if (saved?.hostId === n.id) clearSavedSshAccount()
    await deleteHost(n.id)
  }
  else {
    const saved = readSavedSshAccount()
    if (saved?.id === n.id) clearSavedSshAccount()
    await deleteAccount(n.id)
  }
  detail.value = { type: null, raw: null }
  treeCurrentNodeKey.value = null
  refreshTree()
}

async function isHostInsideGroup(hostId, groupId) {
  if (hostId == null || groupId == null) return false
  const host = await getHost(hostId).catch(() => null)
  if (!host) return false
  const pending = [groupId]
  const visited = new Set()
  while (pending.length) {
    const current = pending.pop()
    if (visited.has(current)) continue
    visited.add(current)
    const nodes = await fetchTreeChildren('GROUP', current).catch(() => [])
    for (const node of nodes || []) {
      if (node.nodeType === 'HOST' && node.id === hostId) return true
      if (node.nodeType === 'GROUP') pending.push(node.id)
    }
    if (host.groupId === current) return true
  }
  return false
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
.terminal-tabs { display:flex; flex-direction:column; flex:1; min-width:0; min-height:0; overflow:hidden; }
.terminal-tab-bar { display:flex; flex-shrink:0; gap:2px; min-width:0; height:34px; overflow-x:auto; overflow-y:hidden; border-bottom:1px solid var(--el-border-color); background:var(--el-fill-color-light); }
.terminal-tab { display:inline-flex; align-items:center; gap:10px; max-width:260px; min-width:120px; padding:0 10px; border:0; border-bottom:2px solid transparent; color:var(--el-text-color-regular); background:transparent; font:inherit; font-size:12px; white-space:nowrap; cursor:pointer; }
.terminal-tab span { overflow:hidden; text-overflow:ellipsis; }
.terminal-tab:hover { background:var(--el-fill-color); }
.terminal-tab.is-active { border-bottom-color:var(--el-color-primary); color:var(--el-color-primary); background:var(--el-bg-color); }
.terminal-tab-close { flex-shrink:0; color:var(--el-text-color-secondary); }
.terminal-tab-close:hover { color:var(--el-color-danger); }
.terminal-tab-add { flex:0 0 30px; border:0; background:transparent; color:var(--el-color-primary); font-size:18px; cursor:pointer; }
.terminal-tab-content { display:flex; flex:1; min-width:0; min-height:0; overflow:hidden; }
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
