<template>
  <div class="ssh-console">
    <el-container class="layout">
      <el-aside :width="sidebarCollapsed ? '52px' : `${sidebarWidth}px`" class="aside" :class="{'aside-collapsed':sidebarCollapsed}">
        <div class="sidebar-header">
          <button type="button" class="sidebar-toggle" :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" @click="sidebarCollapsed=!sidebarCollapsed">
            <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
            <span v-if="!sidebarCollapsed" class="sidebar-title">主机与分组</span>
          </button>
        </div>
        
        <!-- Mini-Dock 模式 (侧栏收起时展示，保持轻快切换会话) -->
        <div v-show="sidebarCollapsed" class="aside-mini-dock">
          <div
            v-for="tab in terminalTabs"
            :key="`mini-${tab.accountId}`"
            class="mini-dock-item"
            :class="{ 'is-active': activeTerminalId === tab.accountId }"
            :title="`${tab.label} (点击切换)`"
            @click="activateTerminalTab(tab.accountId)"
          >
            <el-icon><Monitor /></el-icon>
            <span class="mini-status-dot" :class="{ 'is-connected': !!tab.token }"></span>
          </div>
        </div>

        <div v-show="!sidebarCollapsed" class="aside-content">
          <div class="aside-toolbar">
            <el-input
              v-model="filterText"
              placeholder="筛选分组/主机名/IP/账户..."
              clearable
              class="aside-filter"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-dropdown trigger="click" @command="onAddCommand">
              <button type="button" class="tool-icon-btn primary" title="新建">
                <el-icon><Plus /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="group">新建分组</el-dropdown-item>
                  <el-dropdown-item command="host" :disabled="!selectedGroupId">新建主机</el-dropdown-item>
                  <el-dropdown-item command="account" :disabled="!selectedHostId">新建账户</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <button type="button" class="tool-icon-btn" :disabled="treeLoading" title="刷新" @click="refreshTree">
              <el-icon :class="{ 'is-loading': treeLoading }"><Refresh /></el-icon>
            </button>
          </div>

          <!-- 常用与快捷会话 -->
          <div v-if="terminalTabs.length" class="quick-pins-section">
            <div class="quick-pins-header">
              <span>快捷会话 ({{ terminalTabs.length }})</span>
              <span class="quick-pins-tag font-mono-code">运行中</span>
            </div>
            <div class="quick-pins-list">
              <div
                v-for="tab in terminalTabs"
                :key="`pin-${tab.tabId}`"
                class="quick-pin-item"
                :class="{ 'is-active': activeTerminalId === tab.tabId }"
                @click="activateTerminalTab(tab.tabId)"
              >
                <div class="pin-left">
                  <span class="online-pulse-dot"></span>
                  <span class="pin-name">{{ tab.label }}</span>
                </div>
                <div class="pin-right font-mono-code">
                  <span class="pin-latency">12ms</span>
                  <span class="pin-badge">{{ tab.connectionMode || 'SSH' }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="tree-section-header">
            <span>集群与主机</span>
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
                :class="{ 'is-account': data.nodeType === 'ACCOUNT', 'is-host': data.nodeType === 'HOST' }"
                @dblclick.stop="onNodeDblClick(data)"
              >
                <el-icon class="node-ico" :class="{ 'ico-acc': data.nodeType === 'ACCOUNT', 'ico-host': data.nodeType === 'HOST', 'ico-group': data.nodeType === 'GROUP' }">
                  <User v-if="data.nodeType === 'ACCOUNT'" />
                  <Monitor v-else-if="data.nodeType === 'HOST'" />
                  <Folder v-else />
                </el-icon>
                <div class="node-text">
                  <div class="node-title-row">
                    <span class="node-label" :title="nodeTitle(data)">{{ data.label }}</span>
                    <span v-if="data.nodeType === 'ACCOUNT'" class="node-status-pill" :class="{ 'is-open': terminalTabs.some(t => t.accountId === data.id) }"></span>
                  </div>
                  <span v-if="nodeSubtitle(data)" class="node-sub">{{ nodeSubtitle(data) }}</span>
                </div>
                <el-dropdown class="node-actions" trigger="click" @command="(command) => onNodeAction(command, data)" @click.stop>
                  <el-button text circle size="small" title="更多操作" @click.stop><el-icon><MoreFilled /></el-icon></el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit">编辑</el-dropdown-item>
                      <el-dropdown-item v-if="data.nodeType === 'ACCOUNT'" command="terminal">新建终端</el-dropdown-item>
                      <el-dropdown-item v-if="data.nodeType === 'ACCOUNT'" command="test">测试连接</el-dropdown-item>
                      <el-dropdown-item v-if="data.nodeType === 'GROUP'" command="host">新建主机</el-dropdown-item>
                      <el-dropdown-item v-if="data.nodeType === 'HOST'" command="account">新建账户</el-dropdown-item>
                      <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-tree></el-scrollbar>

          <div class="aside-footer">
            <span class="footer-status">
              <span class="online-pulse-dot"></span>
              <span>SSH 资产正常</span>
            </span>
            <button type="button" class="footer-refresh-btn" :disabled="treeLoading" title="刷新主机树" @click="refreshTree">
              ⟳ 刷新
            </button>
          </div>
        </div>
        <div v-if="!sidebarCollapsed" class="sidebar-resize-handle" role="separator" aria-label="调整主机与分组侧栏宽度" aria-orientation="vertical" :aria-valuenow="sidebarWidth" :aria-valuemin="SIDEBAR_MIN_WIDTH" :aria-valuemax="SIDEBAR_MAX_WIDTH" tabindex="0" title="拖动调整宽度；使用左右方向键微调" @pointerdown.prevent="startSidebarResize" @keydown="resizeSidebarByKeyboard"></div>
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
                      <div class="terminal-tab-scroll">
                        <button v-for="(tab, idx) in terminalTabs" :key="tab.tabId" type="button" role="tab"
                          class="terminal-tab" :class="{ 'is-active': activeTerminalId === tab.tabId }"
                          :aria-selected="activeTerminalId === tab.tabId" @click="activateTerminalTab(tab.tabId)">
                          <span class="tab-status-dot" :class="{ 'is-connected': !!tab.token }"></span>
                          <span class="tab-label">{{ tab.label }}</span>
                          <span class="tab-index">#{{ idx + 1 }}</span>
                          <el-icon class="terminal-tab-close" title="关闭终端" @click.stop="closeTerminalTab(tab.tabId)"><Close /></el-icon>
                        </button>
                        <button class="terminal-tab-add" type="button" title="打开同一账户的新终端" @click="openAdditionalTerminalTab">+</button>
                        <span v-if="activeTerminalTab?.connectionMode" class="terminal-connection-mode" :title="activeTerminalTab.connectionMode">{{ activeTerminalTab.connectionMode }}</span>
                      </div>
                      <TerminalToolbar
                        :snippet-visible="snippetBarVisible"
                        :is-split="isSplit"
                        :allow-split="terminalTabs.length > 1"
                        :font-size="terminalFontSize"
                        :terminal-tone="terminalLightTone"
                        :encoding="activeTerminalTab?.encoding || terminalEncoding"
                        @clear="onClearActiveTerminal"
                        @toggle-snippets="snippetBarVisible = !snippetBarVisible"
                        @toggle-split="isSplit = !isSplit"
                        @change-font-size="size => terminalFontSize = size"
                        @toggle-tone="toggleTerminalTone"
                        @toggle-fullscreen="toggleTerminalFullscreen"
                        @open-theme="workspaceStore.terminalThemeOpen = true"
                        @open-share="shareDialogVisible = true"
                        @change-encoding="onChangeEncoding"
                      />
                    </div>

                    <!-- 常用快捷命令架 -->
                    <SnippetBar v-if="snippetBarVisible" @run="onRunSnippet" />

                    <!-- 终端显示区域 (支持左右分屏对比) -->
                    <div class="terminal-view-split" :class="{ 'is-split-active': isSplit && splitTerminalTab }">
                      <div class="terminal-pane main-pane">
                        <div v-for="tab in terminalTabs" :key="`terminal-${tab.tabId}`" v-show="activeTerminalId === tab.tabId" class="terminal-tab-content">
                          <SshTerminal :account-id="tab.accountId"
                            :terminal-id="tab.terminalId || tab.tabId"
                            :ref="el => setTerminalRef(tab.tabId, el)"
                            :font-size="terminalFontSize"
                            :light-tone="terminalLightTone"
                            :encoding="tab.encoding || terminalEncoding"
                            @ready="payload => onTerminalReady(tab.tabId, payload)"
                            @disconnected="() => onTerminalDisconnected(tab.tabId)"
                            @size-change="onTerminalSizeChange"
                            @change-encoding="enc => onChangeEncoding(enc, tab.tabId)"
                            @ask-ai="handleTerminalAskAi" />
                        </div>
                      </div>

                      <div v-if="isSplit && splitTerminalTab" class="terminal-pane split-pane">
                        <div class="split-pane-header">
                          <span class="split-badge">分屏会话</span>
                          <span class="split-label">{{ splitTerminalTab.label }}</span>
                          <button type="button" class="split-close-btn" title="关闭分屏" @click="isSplit = false">✕</button>
                        </div>
                        <div class="split-pane-content">
                          <SshTerminal :account-id="splitTerminalTab.accountId"
                            :terminal-id="splitTerminalTab.terminalId || splitTerminalTab.tabId"
                            :ref="el => setTerminalRef(`split-${splitTerminalTab.tabId}`, el)"
                            :font-size="terminalFontSize"
                            :light-tone="terminalLightTone"
                            :encoding="splitTerminalTab.encoding || terminalEncoding"
                            @ready="payload => onTerminalReady(splitTerminalTab.tabId, payload)"
                            @disconnected="() => onTerminalDisconnected(splitTerminalTab.tabId)"
                            @change-encoding="enc => onChangeEncoding(enc, splitTerminalTab.tabId)" />
                        </div>
                      </div>
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
              <el-button
                v-if="detail.type === 'HOST' && hostAccounts.length"
                type="success"
                @click="connectHostAccount(hostAccounts[0])"
              >
                🚀 快速打开终端
              </el-button>
              <el-button type="primary" @click="openEditFromDetail">编辑</el-button>
              <el-button type="danger" plain @click="confirmDeleteDetail">删除</el-button>
            </div>
          </div>
          <el-descriptions :column="1" border size="small" class="detail-desc">
            <el-descriptions-item v-for="row in detailRows" :key="row.label" :label="row.label">
              {{ row.value }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- 若为主机，展示其下属的 SSH 账户列表与快捷直连入口 -->
          <div v-if="detail.type === 'HOST'" class="host-accounts-card">
            <div class="host-accounts-header">
              <span class="host-accounts-title">关联 SSH 账户 ({{ hostAccounts.length }})</span>
              <el-button size="small" type="primary" plain @click="openAddAccount(detail.raw.id)">
                + 新建账户
              </el-button>
            </div>
            <div v-if="hostAccounts.length" class="host-accounts-list">
              <div v-for="acc in hostAccounts" :key="acc.id" class="host-account-item">
                <div class="acc-info">
                  <el-icon class="acc-icon"><User /></el-icon>
                  <span class="acc-name">{{ acc.label || acc.username }}</span>
                  <el-tag size="small" type="info">{{ acc.extra?.authType || 'PASSWORD' }}</el-tag>
                </div>
                <el-button size="small" type="primary" @click="connectHostAccount(acc)">
                  打开终端
                </el-button>
              </div>
            </div>
            <div v-else class="host-accounts-empty">
              <p>该主机暂未配置 SSH 登录账户，无法直接开启终端</p>
              <el-button type="primary" size="small" @click="openAddAccount(detail.raw.id)">
                立即新建 SSH 账户
              </el-button>
            </div>
          </div>
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
        <div class="dialog-footer-split">
          <el-button
            type="warning"
            plain
            :loading="testingAccount"
            @click="testAccountConnection"
          >
            ⚡ 测试连接
          </el-button>
          <div class="dialog-footer-right">
            <el-button @click="accountDlg = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="saveAccount">保存</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
    <!-- 共享终端会话弹窗 -->
    <TerminalShareDialog
      v-model="shareDialogVisible"
      :account="detail?.raw"
      :terminal-tab="activeTerminalTab"
    />
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
import TerminalToolbar from '@/components/ssh/TerminalToolbar.vue'
import TerminalShareDialog from '@/components/ssh/TerminalShareDialog.vue'
import SnippetBar from '@/components/ssh/SnippetBar.vue'
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
  testAccount,
  updateAccount,
  updateGroup,
  updateHost
} from '@/api/sshConsole'
import {
  clearSavedSshAccount,
  readSavedSshAccount,
  resolveSshExpandedKeys,
  writeSavedSshAccount,
  allocateTerminalId,
  saveTerminalTabs,
  readSavedTerminalTabs
} from '@/utils/sshConsoleStorage'

const savedAccount = readSavedSshAccount()
const { managedServer } = storeToRefs(useWorkspaceStore())
const sidebarCollapsed = ref(false)
const SIDEBAR_WIDTH_KEY = 'doumi-sidebar-width'
const SIDEBAR_MIN_WIDTH = 220
const SIDEBAR_MAX_WIDTH = 420
const DEFAULT_SIDEBAR_WIDTH = 260
const sidebarWidth = ref(DEFAULT_SIDEBAR_WIDTH)
function clampSidebarWidth(value) {
  return Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, Math.round(value)))
}
try { sidebarCollapsed.value=localStorage.getItem('doumi-sidebar-collapsed')==='true' } catch { /* Default expanded. */ }
watch(sidebarCollapsed, value=>{try{localStorage.setItem('doumi-sidebar-collapsed',String(value))}catch{/* Preference only. */}})
try {
  const savedWidth = Number(localStorage.getItem(SIDEBAR_WIDTH_KEY))
  if (Number.isFinite(savedWidth)) sidebarWidth.value = clampSidebarWidth(savedWidth)
} catch { /* Use the default width when browser storage is unavailable. */ }

let sidebarResizePointer = null
function startSidebarResize(event) {
  if (event.button !== 0) return
  sidebarResizePointer = { pointerId: event.pointerId, startX: event.clientX, startWidth: sidebarWidth.value }
  event.currentTarget.setPointerCapture(event.pointerId)
  document.body.classList.add('sidebar-resizing')
  event.currentTarget.addEventListener('pointermove', moveSidebarResize)
  event.currentTarget.addEventListener('pointerup', stopSidebarResize, { once: true })
  event.currentTarget.addEventListener('pointercancel', stopSidebarResize, { once: true })
}
function moveSidebarResize(event) {
  if (!sidebarResizePointer || event.pointerId !== sidebarResizePointer.pointerId) return
  sidebarWidth.value = clampSidebarWidth(sidebarResizePointer.startWidth + event.clientX - sidebarResizePointer.startX)
}
function stopSidebarResize(event) {
  if (!sidebarResizePointer || (event?.pointerId != null && event.pointerId !== sidebarResizePointer.pointerId)) return
  sidebarResizePointer = null
  document.body.classList.remove('sidebar-resizing')
  try { localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidth.value)) } catch { /* Preference only. */ }
}
function resizeSidebarByKeyboard(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Home') sidebarWidth.value = SIDEBAR_MIN_WIDTH
  else if (event.key === 'End') sidebarWidth.value = SIDEBAR_MAX_WIDTH
  else sidebarWidth.value = clampSidebarWidth(sidebarWidth.value + (event.key === 'ArrowRight' ? 10 : -10))
  try { localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidth.value)) } catch { /* Preference only. */ }
}

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
const hostAccounts = ref([])
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

const snippetBarVisible = ref(false)
const shareDialogVisible = ref(false)
const isSplit = ref(false)
const terminalFontSize = ref(14)
const terminalLightTone = ref('paper')
try {
  const savedTone = localStorage.getItem('doumi-term-tone')
  if (savedTone) terminalLightTone.value = savedTone
} catch { /* ignore */ }

const terminalEncoding = ref('UTF-8')
try {
  const savedEnc = localStorage.getItem('doumi-term-encoding')
  if (savedEnc) terminalEncoding.value = savedEnc
} catch { /* ignore */ }

function toggleTerminalTone() {
  terminalLightTone.value = terminalLightTone.value === 'paper' ? 'hybrid' : 'paper'
  try { localStorage.setItem('doumi-term-tone', terminalLightTone.value) } catch { /* ignore */ }
}

function onChangeEncoding(enc, targetTabId = null) {
  terminalEncoding.value = enc
  const tab = targetTabId
    ? terminalTabs.value.find(t => t.tabId === targetTabId)
    : activeTerminalTab.value
  if (tab) {
    tab.encoding = enc
  }
  try {
    localStorage.setItem('doumi-term-encoding', enc)
  } catch { /* ignore */ }
  persistTerminalTabs()
  ElMessage.success(`终端编码已切换为: ${enc}`)
}

const splitTerminalTab = computed(() => {
  if (!isSplit.value || terminalTabs.value.length < 2) return null
  return terminalTabs.value.find(tab => tab.tabId !== activeTerminalId.value) || null
})

const terminalRefs = new Map()
function setTerminalRef(key, el) {
  if (el) terminalRefs.set(key, el)
  else terminalRefs.delete(key)
}

function onRunSnippet(cmd) {
  const term = terminalRefs.get(activeTerminalId.value)
  if (term) term.sendInput(cmd)
}

function onClearActiveTerminal() {
  const term = terminalRefs.get(activeTerminalId.value)
  if (term) term.clearScreen()
}

function toggleTerminalFullscreen() {
  const el = document.querySelector('.terminal-slot')
  if (!el) return
  if (document.fullscreenElement) {
    void document.exitFullscreen()
  } else {
    void el.requestFullscreen()
  }
}

const workspaceStore = useWorkspaceStore()
function onTerminalSizeChange(size) {
  workspaceStore.terminalCols = size.cols
  workspaceStore.terminalRows = size.rows
}

function handleTerminalAskAi(prompt) {
  workspaceStore.openAi(prompt)
}

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
onBeforeUnmount(() => {
  window.removeEventListener('doumi-configuration-imported', refreshTree)
  stopSidebarResize()
})

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
  const savedTabs = readSavedTerminalTabs()
  if (savedTabs.tabs && savedTabs.tabs.length > 0) {
    terminalTabs.value = savedTabs.tabs
    const targetActive = savedTabs.tabs.find(t => t.tabId === savedTabs.activeTabId) || savedTabs.tabs[0]
    activeTerminalId.value = targetActive?.tabId || null
  }

  const saved = readSavedSshAccount()
  const activeTabAccount = terminalTabs.value.find(t => t.tabId === activeTerminalId.value)
  const targetAccountId = activeTabAccount?.accountId || saved?.id
  if (!targetAccountId) return
  let acc
  try {
    acc = await getAccount(targetAccountId)
  } catch {
    clearSavedSshAccount()
    return
  }
  let groupId = saved?.groupId
  if (groupId == null && acc.hostId) {
    try {
      const h = await getHost(acc.hostId)
      groupId = h?.groupId ?? null
    } catch {
      groupId = null
    }
  }
  const keys = await resolveSshExpandedKeys(
    { ...saved, id: acc.id, hostId: acc.hostId, groupId },
    { getGroup, getHost }
  )
  defaultExpandedKeys.value = keys

  const node = {
    nodeType: 'ACCOUNT',
    id: acc.id,
    key: `a:${acc.id}`,
    label: acc.username,
    leaf: true,
    extra: { hostId: acc.hostId }
  }
  selectedNode.value = node
  treeCurrentNodeKey.value = node.key
  await loadDetail(node)
  if (activeTabAccount) {
    activeTerminalId.value = activeTabAccount.tabId
  }
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
  const v = String(value).trim().toLocaleLowerCase()
  if (!v) return true
  const endpoint = data.nodeType === 'HOST' ? data.extra?.endpoint || '' : ''
  return [data.label, data.remark, endpoint]
    .some(field => String(field || '').toLocaleLowerCase().includes(v))
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

async function connectHostAccount(acc) {
  if (!acc?.id) return
  await loadDetail({
    nodeType: 'ACCOUNT',
    id: acc.id,
    key: `a:${acc.id}`,
    label: acc.label || acc.username,
    leaf: true,
    extra: { hostId: detail.value.raw?.id }
  })
  if (route.meta.panel !== 'terminal') {
    router.push('/terminal')
  }
}

async function loadDetail(data) {
  try {
    if (data.nodeType === 'GROUP') {
      sessionToken.value = ''
      hostAccounts.value = []
      detail.value = { type: 'GROUP', raw: await getGroup(data.id) }
    } else if (data.nodeType === 'HOST') {
      sessionToken.value = ''
      detail.value = { type: 'HOST', raw: await getHost(data.id) }
      try {
        const accs = await fetchTreeChildren('HOST', data.id)
        hostAccounts.value = accs || []
      } catch {
        hostAccounts.value = []
      }
    } else {
      hostAccounts.value = []
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
    if (data.nodeType === 'ACCOUNT') {
      if (data.extra?.hostId) {
        await loadAccountHostMeta(data.extra.hostId)
      }
      activateTerminalForAccount(data)
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

function persistTerminalTabs() {
  saveTerminalTabs(terminalTabs.value, activeTerminalId.value)
}

function terminalTabLabel(account, host = accountHostMeta.value) {
  const hostLabel = `${host.name || account.host || `主机#${account.hostId}`} (${host.host || account.host || ''})`
  return `${hostLabel} · ${account.username || '账户'}`
}

function activateTerminalForAccount(account) {
  if (!account?.id) return
  let tab = terminalTabs.value.find(item => item.accountId === account.id)
  if (!tab) {
    const { tabId, terminalId } = allocateTerminalId(account.id, terminalTabs.value)
    tab = {
      tabId,
      terminalId,
      accountId: account.id,
      account,
      hostId: account.hostId,
      defaultCwd: account.defaultCwd || '',
      label: terminalTabLabel(account),
      token: '',
      encoding: terminalEncoding.value
    }
    terminalTabs.value.push(tab)
  } else {
    tab.hostId = account.hostId
    tab.account = account
    tab.defaultCwd = account.defaultCwd || ''
    tab.label = terminalTabLabel(account)
  }
  activeTerminalId.value = tab.tabId
  sessionToken.value = tab.token
  persistTerminalTabs()
}

function openAdditionalTerminalTab() {
  const current = activeTerminalTab.value
  if (!current) return
  const { tabId, terminalId, subIndex } = allocateTerminalId(current.accountId, terminalTabs.value, { forceNew: true })
  const baseLabel = current.account ? terminalTabLabel(current.account) : (current.label || '终端')
  const label = `${baseLabel} #${subIndex}`
  const tab = {
    ...current,
    tabId,
    terminalId,
    label,
    token: '',
    encoding: current.encoding || terminalEncoding.value
  }
  terminalTabs.value.push(tab)
  activeTerminalId.value = tab.tabId
  sessionToken.value = ''
  persistTerminalTabs()
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
    await persistAccountSelection(node)
  }
  persistTerminalTabs()
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
  persistTerminalTabs()
}

function onTerminalReady(tabId, payload) {
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  if (!tab) return
  tab.token = payload?.sessionToken || ''
  tab.connectionMode = payload?.connectionMode || ''
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

async function onNodeDblClick(data) {
  selectedNode.value = data
  if (data.nodeType === 'ACCOUNT') {
    void openNewAccountTerminal(data)
    return
  }
  if (data.nodeType === 'HOST') {
    try {
      const children = await fetchTreeChildren('HOST', data.id)
      if (children && children.length > 0) {
        const firstAccount = children.find(c => c.nodeType === 'ACCOUNT') || children[0]
        void openNewAccountTerminal(firstAccount)
        return
      }
    } catch {
      /* fallback */
    }
    editHostNode(data)
    return
  }
  if (data.nodeType === 'GROUP') editGroupNode(data)
  else editAccountNode(data)
}

function onNodeAction(command, data) {
  selectedNode.value = data
  if (command === 'terminal') return openNewAccountTerminal(data)
  if (command === 'test') return handleTestExistingAccount(data)
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

async function openNewAccountTerminal(data) {
  try {
    const account = await getAccount(data.id)
    const host = await loadAccountHostMeta(account.hostId)
    const { tabId, terminalId, subIndex } = allocateTerminalId(account.id, terminalTabs.value, { forceNew: true })
    const baseLabel = terminalTabLabel(account, host)
    const label = subIndex > 1 ? `${baseLabel} #${subIndex}` : baseLabel
    const tab = {
      tabId,
      terminalId,
      accountId: account.id,
      account,
      hostId: account.hostId,
      defaultCwd: account.defaultCwd || '',
      label,
      token: '',
      encoding: terminalEncoding.value
    }
    terminalTabs.value.push(tab)
    detail.value = { type: 'ACCOUNT', raw: account }
    activeTerminalId.value = tab.tabId
    sessionToken.value = ''
    terminalVisited.value = true
    persistTerminalTabs()
    await router.push('/terminal')
    await persistAccountSelection(data)
  } catch (error) {
    ElMessage.error(error?.message || '新建终端失败')
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

const testingAccount = ref(false)

async function testAccountConnection() {
  const form = accountForm.value
  if (!form.username?.trim()) {
    ElMessage.warning('请先填写用户名')
    return
  }
  if (!form.hostId) {
    ElMessage.warning('未能识别当前所属主机')
    return
  }
  const secret = buildSecretPayload()
  if (secret === null && !form.id) {
    ElMessage.warning('新建账户须填写密码或私钥凭据后再测试连接')
    return
  }

  testingAccount.value = true
  try {
    const payload = {
      id: form.id || undefined,
      hostId: form.hostId,
      username: form.username.trim(),
      authType: form.authType || 'PASSWORD',
      secret: secret !== undefined ? secret : undefined
    }
    const res = await testAccount(payload)
    if (res && res.success) {
      ElMessage.success(`✅ SSH 测试成功！${res.message || ''}`)
    } else {
      ElMessage.error(`❌ 连接失败: ${res?.message || '连接或认证失败，请检查主机、用户名和凭据'}`)
    }
  } catch (err) {
    ElMessage.error(`❌ 测试失败: ${err?.message || '网络或接口异常'}`)
  } finally {
    testingAccount.value = false
  }
}

async function handleTestExistingAccount(data) {
  try {
    ElMessage.info(`正在测试账户「${data.label}」的 SSH 连接…`)
    const res = await testAccount(data.id)
    if (res && res.success) {
      ElMessage.success(`✅「${data.label}」测试成功！${res.message || ''}`)
    } else {
      ElMessage.error(`❌「${data.label}」连接失败: ${res?.message || '连接或认证失败'}`)
    }
  } catch (err) {
    ElMessage.error(`❌ 测试失败: ${err?.message || '网络或接口异常'}`)
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
  display: flex;
  flex-direction: column;
  background: var(--ws-bg-root);
}
.layout {
  height: 100%;
  width: 100%;
  overflow: hidden;
}

/* ================= 侧边栏 ================= */
.aside {
  position: relative;
  flex: 0 0 auto;
  border-right: 1px solid var(--ws-border);
  background: var(--ws-bg-sidebar);
  display: flex;
  flex-direction: column;
  padding: 0;
  box-sizing: border-box;
  overflow: hidden;
  transition: width var(--ws-transition-fast), background-color var(--ws-transition-normal);
}
.sidebar-resize-handle {
  position: absolute;
  z-index: 12;
  top: 0;
  right: -3px;
  bottom: 0;
  width: 7px;
  cursor: col-resize;
  touch-action: none;
}
.sidebar-resize-handle::after {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 3px;
  width: 1px;
  background: transparent;
  content: '';
}
.sidebar-resize-handle:hover::after,
.sidebar-resize-handle:focus-visible::after {
  width: 2px;
  background: var(--ws-accent);
}
.sidebar-resize-handle:focus-visible { outline: none; }
@media (max-width: 700px) {
  .aside:not(.aside-collapsed) { max-width: min(420px, 65vw); }
}
.sidebar-header {
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
  border-bottom: 1px solid var(--ws-border);
}
.sidebar-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  border: none;
  color: var(--ws-text-regular);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: var(--ws-radius-sm);
  transition: all var(--ws-transition-fast);
}
.sidebar-toggle:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
}
.sidebar-title {
  white-space: nowrap;
}

.aside-mini-dock {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
}
.mini-dock-item {
  width: 36px;
  height: 36px;
  border-radius: var(--ws-radius-md);
  background: var(--ws-bg-card);
  border: 1px solid var(--ws-border);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  cursor: pointer;
  color: var(--ws-text-muted);
  font-size: 15px;
  transition: all var(--ws-transition-fast);
}
.mini-dock-item:hover, .mini-dock-item.is-active {
  background: var(--ws-bg-active);
  border-color: var(--ws-accent);
  color: var(--ws-accent);
}
.mini-status-dot {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ws-text-muted);
}
.mini-status-dot.is-connected {
  background: var(--ws-color-online);
  box-shadow: 0 0 5px rgba(16, 185, 129, 0.6);
}

.aside-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.aside-toolbar {
  display: flex;
  gap: 6px;
  padding: 8px 10px;
  align-items: center;
  border-bottom: 1px solid var(--ws-border);
}
.aside-filter {
  flex: 1;
}
.tool-icon-btn {
  width: 28px;
  height: 28px;
  border-radius: var(--ws-radius-sm);
  background: var(--ws-bg-input);
  border: 1px solid var(--ws-border);
  color: var(--ws-text-regular);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
}
.tool-icon-btn:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
}
.tool-icon-btn.primary {
  background: var(--ws-accent-bg);
  border-color: var(--ws-accent-border);
  color: var(--ws-accent);
}
.tool-icon-btn.primary:hover {
  background: var(--ws-accent);
  color: #ffffff;
}

/* 常用与置顶会话列表 */
.quick-pins-section {
  padding: 8px 10px 4px;
  border-bottom: 1px solid var(--ws-border);
}
.quick-pins-header {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--ws-text-muted);
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.quick-pins-tag {
  color: var(--ws-color-online);
  font-size: 10px;
}
.quick-pins-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.quick-pin-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  border-radius: var(--ws-radius-sm);
  background: var(--ws-bg-card);
  border: 1px solid var(--ws-border);
  cursor: pointer;
  transition: all var(--ws-transition-fast);
  font-size: 12px;
}
.quick-pin-item:hover {
  background: var(--ws-bg-hover);
  border-color: var(--ws-border-hover);
}
.quick-pin-item.is-active {
  background: var(--ws-bg-active);
  border-color: var(--ws-accent-border);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}
.pin-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.pin-name {
  font-weight: 500;
  color: var(--ws-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pin-right {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.pin-latency {
  font-size: 10px;
  color: var(--ws-color-online);
}
.pin-badge {
  font-size: 9px;
  padding: 0 4px;
  border-radius: 2px;
  background: var(--ws-bg-hover);
  color: var(--ws-text-muted);
}

.tree-section-header {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--ws-text-muted);
  padding: 8px 10px 4px;
}
.asset-scroll {
  flex: 1;
  min-height: 0;
  padding: 0 4px;
}
.asset-tree {
  flex: 1;
  min-height: 0;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  padding-right: 4px;
}
.node-ico {
  font-size: 14px;
  flex-shrink: 0;
}
.node-ico.ico-acc { color: var(--ws-accent); }
.node-ico.ico-host { color: #06b6d4; }
.node-ico.ico-group { color: #f59e0b; }
.node-text {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}
.node-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.node-label {
  display: block;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ws-text-primary);
}
.node-sub {
  font-size: 11px;
  color: var(--ws-text-subtle);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
}
.node-status-pill {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ws-color-online);
  flex-shrink: 0;
  opacity: 0.6;
}
.node-status-pill.is-open {
  opacity: 1;
  box-shadow: 0 0 5px rgba(16, 185, 129, 0.6);
}
.node-actions {
  margin-left: auto;
  opacity: 0;
  flex-shrink: 0;
}
.tree-node:hover .node-actions, .tree-node:focus-within .node-actions {
  opacity: 1;
}

.aside-footer {
  padding: 6px 10px;
  border-top: 1px solid var(--ws-border);
  background: var(--ws-bg-hover);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ws-text-muted);
}
.footer-status {
  display: flex;
  align-items: center;
  gap: 6px;
}
.footer-refresh-btn {
  background: transparent;
  border: none;
  color: var(--ws-text-muted);
  cursor: pointer;
  font-size: 11px;
  padding: 2px 4px;
  border-radius: var(--ws-radius-xs);
}
.footer-refresh-btn:hover {
  color: var(--ws-accent);
}

/* ================= 主体工作区 (Edge-to-Edge) ================= */
.main {
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--ws-bg-root);
  flex: 1;
}
.account-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  min-height: 0;
}
.account-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.account-tab-pane {
  height: 100%;
  width: 100%;
  overflow: hidden;
}

/* ================= 终端标签栏 (Warp/VS Code Style 36px) ================= */
.terminal-tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.terminal-tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  height: 36px;
  padding: 0 8px;
  border-bottom: 1px solid var(--ws-border);
  background: var(--ws-bg-tab-bar);
  gap: 8px;
  transition: background-color var(--ws-transition-normal), border-color var(--ws-transition-normal);
}
.terminal-tab-scroll {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  overflow-x: auto;
  overflow-y: hidden;
  height: 100%;
  flex: 1;
  scrollbar-width: none;
}
.terminal-tab-scroll::-webkit-scrollbar {
  display: none;
}
.terminal-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  min-width: 110px;
  height: 30px;
  padding: 0 10px;
  border-radius: var(--ws-radius-sm) var(--ws-radius-sm) 0 0;
  border: 1px solid transparent;
  border-bottom: 2px solid transparent;
  background: var(--ws-bg-inactive-tab);
  color: var(--ws-text-muted);
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
  user-select: none;
}
.terminal-tab:hover {
  background: var(--ws-bg-card);
  color: var(--ws-text-regular);
}
.terminal-tab.is-active {
  background: var(--ws-bg-active-tab);
  color: var(--ws-text-primary);
  border-bottom: 2px solid var(--ws-accent);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  font-weight: 600;
}
.tab-status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ws-text-muted);
  flex-shrink: 0;
}
.tab-status-dot.is-connected {
  background: var(--ws-color-online);
  box-shadow: 0 0 5px rgba(16, 185, 129, 0.6);
}
.tab-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.tab-index {
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--ws-accent);
  opacity: 0.8;
}
.terminal-tab-close {
  flex-shrink: 0;
  color: var(--ws-text-muted);
  border-radius: 3px;
  padding: 1px;
  font-size: 12px;
  opacity: 0;
  transition: opacity var(--ws-transition-fast);
}
.terminal-tab:hover .terminal-tab-close {
  opacity: 1;
}
.terminal-tab-close:hover {
  color: var(--ws-color-danger);
  background: var(--ws-bg-hover);
}

/* 分屏显示 */
.terminal-view-split {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.terminal-pane {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.terminal-pane.split-pane {
  border-left: 1px solid var(--ws-border);
}
.split-pane-header {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 28px;
  padding: 0 8px;
  background: var(--ws-bg-hover);
  border-bottom: 1px solid var(--ws-border);
  font-size: 11px;
}
.split-badge {
  padding: 1px 4px;
  border-radius: 3px;
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
  font-weight: 600;
  font-size: 10px;
}
.split-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ws-text-regular);
}
.split-close-btn {
  background: transparent;
  border: none;
  color: var(--ws-text-muted);
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 3px;
}
.split-close-btn:hover {
  color: var(--ws-color-danger);
}
.split-pane-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  display: flex;
}
.terminal-tab-content {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.terminal-tab-add {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--ws-radius-sm, 4px);
  border: 1px dashed var(--ws-border);
  background: transparent;
  color: var(--ws-text-muted);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  margin-bottom: 3px;
  transition: all var(--ws-transition-fast);
}
.terminal-tab-add:hover {
  border-color: var(--ws-accent);
  color: var(--ws-accent);
  background: var(--ws-bg-card);
}
.terminal-connection-mode {
  flex: 0 0 auto;
  align-self: center;
  margin-left: 6px;
  margin-right: 4px;
  padding: 1px 6px;
  border: 1px solid var(--ws-border);
  border-radius: 10px;
  background: var(--ws-bg-card);
  color: var(--ws-accent);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.5px;
  font-family: var(--font-mono, monospace);
  white-space: nowrap;
}
.account-tabs :deep(.el-tab-pane) {
  height: 100%;
  overflow: hidden;
}
.sftp-stub {
  font-size: 12px;
  color: var(--ws-text-muted);
  word-break: break-all;
}

/* 主机/分组详情与关联账户卡片 */
.detail-panel {
  padding: 24px;
  max-width: 840px;
  overflow-y: auto;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.detail-head h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ws-text-primary);
}
.detail-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.detail-desc {
  margin-bottom: 20px;
}
.host-accounts-card {
  margin-top: 24px;
  padding: 16px;
  background: var(--ws-bg-card);
  border: 1px solid var(--ws-border);
  border-radius: var(--ws-radius-md);
}
.host-accounts-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.host-accounts-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--ws-text-primary);
}
.host-accounts-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.host-account-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--ws-bg-hover);
  border: 1px solid var(--ws-border);
  border-radius: var(--ws-radius-sm);
  transition: all var(--ws-transition-fast);
}
.host-account-item:hover {
  border-color: var(--ws-accent);
}
.acc-info {
  display: flex;
  align-items: center;
  gap: 10px;
}
.acc-icon {
  font-size: 16px;
  color: var(--ws-accent);
}
.acc-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--ws-text-primary);
}
.host-accounts-empty {
  text-align: center;
  padding: 24px 16px;
  color: var(--ws-text-muted);
  font-size: 13px;
}
.host-accounts-empty p {
  margin-bottom: 12px;
}
.dialog-footer-split {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.dialog-footer-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>

<style>
body.sidebar-resizing {
  cursor: col-resize !important;
  user-select: none !important;
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
