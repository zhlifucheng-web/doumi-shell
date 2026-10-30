<template>
  <div class="ssh-console">
    <el-container class="layout">
      <el-aside :width="sidebarCollapsed ? '52px' : `${sidebarWidth}px`" class="aside" :class="{'aside-collapsed':sidebarCollapsed, 'is-resizing': isSidebarResizing}">
        <div class="sidebar-header">
          <button type="button" class="sidebar-toggle" :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" @click="sidebarCollapsed=!sidebarCollapsed">
            <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
            <span v-if="!sidebarCollapsed" class="sidebar-title">主机与分组</span>
          </button>
        </div>

        <!-- Mini-Dock 模式 (侧栏收起时展示，保持轻快切换会话) -->
        <div v-show="sidebarCollapsed" class="aside-mini-dock">
          <el-tooltip
            v-for="(tab, idx) in terminalTabs"
            :key="`mini-${tab.tabId}`"
            placement="right"
            :show-after="50"
            effect="dark"
            popper-class="terminal-tab-popper"
          >
            <template #content>
              <div class="tab-tooltip-card">
                <div class="tab-tooltip-title">
                  <span class="user-badge" :class="{ 'is-root': getTabUsername(tab) === 'root' }">{{ getTabUsername(tab) }}</span>
                  <span>@ {{ tab.customTitle || getTabDisplayLabel(tab) }}</span>
                </div>
                <div v-if="getTabHostAddress(tab)" class="tab-tooltip-row">
                  <span class="tab-tooltip-k">地址:</span>
                  <span class="tab-tooltip-v font-mono-code">{{ getTabHostAddress(tab) }}</span>
                </div>
                <div class="tab-tooltip-row">
                  <span class="tab-tooltip-k">用户:</span>
                  <span class="tab-tooltip-v font-mono-code" :class="{ 'text-danger': getTabUsername(tab) === 'root' }">
                    {{ getTabUsername(tab) }}
                  </span>
                </div>
                <div class="tab-tooltip-row">
                  <span class="tab-tooltip-k">状态:</span>
                  <span class="tab-tooltip-v" :class="isTabConnected(tab) ? 'is-online' : 'is-offline'">
                    {{ isTabConnected(tab) ? '● 已连接' : '○ 未连接' }}
                  </span>
                </div>
                <div v-if="idx < 9" class="tab-tooltip-row">
                  <span class="tab-tooltip-k">快捷键:</span>
                  <span class="tab-tooltip-v font-mono-code">{{ getTabShortcutLabel(idx + 1) }}</span>
                </div>
                <div class="tab-tooltip-hint">点击切换 · 右键更多操作</div>
              </div>
            </template>

            <button
              type="button"
              class="mini-dock-item"
              :class="{
                'is-active': activeTerminalId === tab.tabId,
                'is-root-user': getTabUsername(tab) === 'root'
              }"
              :style="getTabThemeStyle(tab)"
              @click="activateTerminalTab(tab.tabId)"
              @contextmenu.prevent="openTabContextMenu($event, tab)"
            >
              <span class="mini-host-label">{{ getTabMiniHost(tab, accountHostMeta) }}</span>
              <span class="mini-user-badge" :class="{ 'is-root': getTabUsername(tab) === 'root' }">
                {{ getTabUsername(tab) }}
              </span>
              <span v-if="tab.subIndex && tab.subIndex > 1" class="mini-sub-badge">#{{ tab.subIndex }}</span>
              <span class="mini-status-dot" :class="{ 'is-connected': isTabConnected(tab) }"></span>
            </button>
          </el-tooltip>
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
                  <el-dropdown-item command="host">新建主机</el-dropdown-item>
                  <el-dropdown-item command="account">新建账户</el-dropdown-item>
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
                :title="tab.customTitle || tab.label"
                @click="activateTerminalTab(tab.tabId)"
              >
                <div class="pin-left">
                  <span class="online-pulse-dot" :class="{ 'is-offline': !isTabConnected(tab) }"></span>
                  <span class="pin-name">{{ getTabDisplayLabel(tab) }}</span>
                </div>
                <div class="pin-right font-mono-code">

                  <span class="pin-badge">{{ isRdp(tab) ? 'RDP' : tmuxCompatibility(tab.connectionMode, tab.tmuxVersion, tab.tmuxVersionSource).label }}</span>
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
            :auto-expand-parent="false"
            :current-node-key="treeCurrentNodeKey"
            :filter-node-method="filterNode"
            :draggable="!filterText"
            :allow-drop="allowTreeDrop"
            @node-click="onNodeClick"
            @node-expand="onNodeExpand"
            @node-collapse="onNodeCollapse"
            @node-drag-start="onNodeDragStart"
            @node-drop="onNodeDrop"
          >
            <template #default="{ data }">
              <div
                class="tree-node"
                :class="{ 'is-account': isAccountNode(data), 'is-host': data.nodeType === 'HOST' }"
                @dblclick.stop="onNodeDblClick(data)"
              >
                <el-tooltip
                  effect="light"
                  :show-after="350"
                  placement="right"
                  :offset="12"
                  popper-class="tree-node-tooltip"
                  :enterable="true"
                >
                  <template #content>
                    <div class="tree-tip-card">
                      <div class="tree-tip-header">
                        <el-tag size="small" :type="nodeTagType(data)" effect="plain" class="tree-tip-tag">
                          {{ nodeTypeName(data) }}
                        </el-tag>
                        <span class="tree-tip-title">{{ getNodeMainTitle(data) }}</span>
                      </div>
                      <div v-if="data.nodeType === 'HOST'" class="tree-tip-body">
                        <div v-if="hasCustomHostName(data)" class="tree-tip-row">
                          <span class="tree-tip-label">主机名称:</span>
                          <span class="tree-tip-value">{{ data.label }}</span>
                        </div>
                        <div v-if="data.extra?.host" class="tree-tip-row">
                          <span class="tree-tip-label">IP 地址:</span>
                          <span class="tree-tip-value font-mono-code">{{ data.extra.host }}</span>
                          <el-button
                            link
                            type="primary"
                            size="small"
                            class="tree-tip-copy-btn"
                            title="复制 IP"
                            @click.stop="copyText(data.extra.host, '已复制 IP 地址')"
                          >
                            <el-icon><CopyDocument /></el-icon>
                          </el-button>
                        </div>
                        <div v-if="data.extra?.port" class="tree-tip-row">
                          <span class="tree-tip-label">SSH 端口:</span>
                          <span class="tree-tip-value font-mono-code">{{ data.extra.port }}</span>
                        </div>
                        <div v-if="data.remark" class="tree-tip-row">
                          <span class="tree-tip-label">备注说明:</span>
                          <span class="tree-tip-value">{{ data.remark }}</span>
                        </div>
                      </div>
                      <div v-else-if="isAccountNode(data)" class="tree-tip-body">
                        <div class="tree-tip-row">
                          <span class="tree-tip-label">账户名:</span>
                          <span class="tree-tip-value font-mono-code">{{ data.label }}</span>
                        </div>
                        <div v-if="data.extra?.authType" class="tree-tip-row">
                          <span class="tree-tip-label">认证方式:</span>
                          <span class="tree-tip-value">{{ data.extra.authType === 'KEY' ? '密钥认证' : '密码认证' }}</span>
                        </div>
                        <div v-if="data.remark" class="tree-tip-row">
                          <span class="tree-tip-label">备注:</span>
                          <span class="tree-tip-value">{{ data.remark }}</span>
                        </div>
                      </div>
                      <div v-else-if="data.nodeType === 'GROUP'" class="tree-tip-body">
                        <div v-if="data.remark" class="tree-tip-row">
                          <span class="tree-tip-label">分组备注:</span>
                          <span class="tree-tip-value">{{ data.remark }}</span>
                        </div>
                      </div>
                    </div>
                  </template>

                  <div class="node-main-area">
                    <el-icon class="node-ico" :class="{ 'ico-acc': isAccountNode(data), 'ico-host': data.nodeType === 'HOST', 'ico-group': data.nodeType === 'GROUP' }">
                      <Monitor v-if="isRdp(data)" /><User v-else-if="isAccountNode(data)" />
                      <Monitor v-else-if="data.nodeType === 'HOST'" />
                      <Folder v-else />
                    </el-icon>
                    <div class="node-text">
                      <div class="node-title-row">
                        <span class="node-label" :class="{ 'is-ip-font': data.nodeType === 'HOST' && !hasCustomHostName(data) }">
                          {{ getNodeMainTitle(data) }}
                        </span>
                        <span v-if="isAccountNode(data)" class="connection-protocol">{{ isRdp(data) ? 'RDP' : 'SSH' }}</span>
                        <span v-if="isAccountNode(data)" class="node-status-pill" :class="{ 'is-open': terminalTabs.some(t => tabMatchesNode(t, data)) }"></span>
                      </div>
                      <span
                        v-if="getNodeSubTitle(data)"
                        class="node-sub"
                        :class="{ 'is-ip-badge': data.nodeType === 'HOST' && hasCustomHostName(data) }"
                      >
                        {{ getNodeSubTitle(data) }}
                      </span>
                    </div>
                  </div>
                </el-tooltip>

                <el-dropdown class="node-actions" trigger="click" @command="(command) => onNodeAction(command, data)" @click.stop>
                  <el-button text circle size="small" title="更多操作" @click.stop><el-icon><MoreFilled /></el-icon></el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit">编辑</el-dropdown-item>
                      <el-dropdown-item v-if="isAccountNode(data)" command="terminal">新建连接</el-dropdown-item>
                      <el-dropdown-item v-if="isAccountNode(data)" command="test">测试连接</el-dropdown-item>
                      <el-dropdown-item v-if="data.nodeType === 'GROUP'" command="subgroup">新建子分组</el-dropdown-item>
                      <el-dropdown-item v-if="data.nodeType === 'GROUP' || data.nodeType === 'HOST' || isAccountNode(data)" command="host">新建主机</el-dropdown-item>
                      <el-dropdown-item v-if="data.nodeType === 'HOST' || isAccountNode(data)" command="account">新建账户</el-dropdown-item>
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

        <!-- 侧栏拖拽调整宽度的把手 -->
        <div
          v-show="!sidebarCollapsed"
          class="sidebar-resizer"
          role="separator"
          aria-label="调整主机树侧栏宽度"
          aria-orientation="vertical"
          :aria-valuenow="sidebarWidth"
          :aria-valuemin="MIN_SIDEBAR_WIDTH"
          :aria-valuemax="MAX_SIDEBAR_WIDTH"
          tabindex="0"
          title="拖动调整宽度，方向键微调，双击恢复默认"
          @pointerdown="onSidebarResizeStart"
          @pointermove="onSidebarResizeMove"
          @pointerup="onSidebarResizeEnd"
          @pointercancel="onSidebarResizeEnd"
          @keydown="resizeSidebarByKeyboard"
          @dblclick="resetSidebarWidth"
        >
          <div class="sidebar-resizer-handle"></div>
        </div>
      </el-aside>

      <el-main class="main">
        <el-empty v-if="detail.type === 'RDP_ACCOUNT' && accountTab !== 'terminal'" description="此连接为远程桌面"><el-button @click="router.push('/terminal')">打开桌面</el-button></el-empty>
        <ScriptManagement v-if="scriptsVisited && detail.type !== 'RDP_ACCOUNT'" v-show="accountTab==='scripts'" :active="accountTab==='scripts'" :host-id="detail.type==='ACCOUNT' ? detail.raw?.hostId : detail.type==='HOST' ? detail.raw?.id : null" :account-id="detail.type==='ACCOUNT' ? detail.raw?.id : null" :session-token="sessionToken" :opening-session="openingFileSession" @request-session="establishFileSession" />
        <el-empty v-if="!detail.type && accountTab!=='scripts'" description="在左侧选择分组、主机或账户" />
        <div v-else-if="['ACCOUNT', 'RDP_ACCOUNT'].includes(detail.type)" v-show="accountTab!=='scripts'" class="account-workspace">
          <div class="account-tabs route-panels">
            <div v-show="accountTab === 'terminal'" class="account-tab-pane">
              <TerminalFileLayout :hide-files="activeIsRdp">
                <template #monitor>
                  <SshMonitorPanel v-if="activeTerminalTab && terminalVisited && !activeIsRdp"
                    :account-id="activeTerminalTab.accountId" :session-token="sessionToken" :active="accountTab === 'terminal'" @session-invalid="onMonitorSessionInvalid" />
                </template>
                <template #terminal>
                  <div v-if="terminalTabs.length" class="terminal-tabs">
                    <div class="terminal-tab-bar" role="tablist" aria-label="已打开的连接">
                      <!-- 标签左滑动箭头 -->
                      <button
                        v-show="showLeftScroll"
                        type="button"
                        class="tab-scroll-arrow left"
                        title="向左滚动标签"
                        @click="scrollTabs('left')"
                      >
                        <el-icon><ArrowLeft /></el-icon>
                      </button>

                      <div
                        ref="tabScrollRef"
                        class="terminal-tab-scroll"
                        @wheel="onTabWheel"
                      >
                        <button
                          v-for="(tab, idx) in terminalTabs"
                          :key="tab.tabId"
                          type="button"
                          role="tab"
                          class="terminal-tab"
                          :class="{ 'is-active': activeTerminalId === tab.tabId }"
                          :aria-selected="activeTerminalId === tab.tabId"
                          @click="activateTerminalTab(tab.tabId)"
                          @contextmenu.prevent="openTabContextMenu($event, tab)"
                        >
                          <el-tooltip
                            placement="bottom"
                            :show-after="400"
                            effect="dark"
                            popper-class="terminal-tab-popper"
                          >
                            <template #content>
                              <div class="tab-tooltip-card">
                                <div class="tab-tooltip-title">{{ tab.customTitle || getTabDisplayLabel(tab) }}</div>
                                <div v-if="getTabHostAddress(tab)" class="tab-tooltip-row">
                                  <span class="tab-tooltip-k">地址:</span>
                                  <span class="tab-tooltip-v font-mono-code">{{ getTabHostAddress(tab) }}</span>
                                </div>
                                <div v-if="tab.account?.username" class="tab-tooltip-row">
                                  <span class="tab-tooltip-k">账户:</span>
                                  <span class="tab-tooltip-v">{{ tab.account.username }}</span>
                                </div>
                                <div class="tab-tooltip-row">
                                  <span class="tab-tooltip-k">状态:</span>
                                  <span class="tab-tooltip-v" :class="isTabConnected(tab) ? 'is-online' : 'is-offline'">
                                    {{ isTabConnected(tab) ? '● 已连接' : '○ 未连接' }}
                                  </span>
                                </div>
                                <div v-if="idx < 9" class="tab-tooltip-row">
                                  <span class="tab-tooltip-k">快捷键:</span>
                                  <span class="tab-tooltip-v font-mono-code">{{ getTabShortcutLabel(idx + 1) }}</span>
                                </div>
                                <div class="tab-tooltip-hint">滚轮滑动 · 右键更多操作</div>
                              </div>
                            </template>
                            <span class="tab-inner">
                              <span class="tab-status-dot" :class="{ 'is-connected': isTabConnected(tab) }"></span>
                              <span class="tab-label">{{ getTabDisplayLabel(tab) }}</span><span class="connection-protocol">{{ isRdp(tab) ? 'RDP' : 'SSH' }}</span>
                              <span v-if="tab.subIndex && tab.subIndex > 1" class="tab-index">#{{ tab.subIndex }}</span>
                            </span>
                          </el-tooltip>
                          <el-icon class="terminal-tab-close" title="关闭连接" @click.stop="closeTerminalTab(tab.tabId)"><Close /></el-icon>
                        </button>
                        <button class="terminal-tab-add" type="button" title="打开同一账户的新连接" @click="openAdditionalTerminalTab">+</button>
                        <TerminalConnectionStatus v-if="activeTerminalTab?.connectionMode" :connection-mode="activeTerminalTab.connectionMode" :tmux-version="activeTerminalTab.tmuxVersion" :tmux-version-source="activeTerminalTab.tmuxVersionSource" />
                      </div>

                      <!-- 标签右滑动箭头 -->
                      <button
                        v-show="showRightScroll"
                        type="button"
                        class="tab-scroll-arrow right"
                        title="向右滚动标签"
                        @click="scrollTabs('right')"
                      >
                        <el-icon><ArrowRight /></el-icon>
                      </button>

                      <!-- 全部终端下拉菜单 (More Tabs ∨) -->
                      <el-dropdown trigger="click" class="terminal-tab-dropdown" @command="handleTabDropdownCommand">
                        <button type="button" class="tab-dropdown-btn" :title="`全部连接清单 (${terminalTabs.length})`">
                          <el-icon><Grid /></el-icon>
                          <span class="tab-count-badge font-mono-code">{{ terminalTabs.length }}</span>
                        </button>
                        <template #dropdown>
                          <el-dropdown-menu class="terminal-tabs-dropdown-menu">
                            <div class="dropdown-header">
                              <span>已打开的连接 ({{ terminalTabs.length }})</span>
                            </div>
                            <div class="dropdown-tabs-list">
                              <el-dropdown-item
                                v-for="(tab, idx) in terminalTabs"
                                :key="`menu-${tab.tabId}`"
                                :command="{ action: 'activate', tabId: tab.tabId }"
                                :class="{ 'is-active': activeTerminalId === tab.tabId }"
                                class="dropdown-tab-item"
                              >
                                <div class="dropdown-item-left">
                                  <span class="dropdown-status-dot" :class="{ 'is-connected': isTabConnected(tab) }"></span>
                                  <span class="dropdown-tab-label">{{ getTabDisplayLabel(tab) }}</span>
                                  <span v-if="getTabHostAddress(tab)" class="dropdown-tab-ip font-mono-code">{{ getTabHostAddress(tab) }}</span>
                                </div>
                                <div class="dropdown-item-right">
                                  <span v-if="idx < 9" class="dropdown-shortcut-badge font-mono-code">Ctrl+{{ idx + 1 }}</span>
                                  <el-icon v-if="activeTerminalId === tab.tabId" class="active-check"><Check /></el-icon>
                                  <button
                                    type="button"
                                    class="dropdown-close-btn"
                                    title="关闭"
                                    @click.stop="closeTerminalTab(tab.tabId)"
                                  >
                                    <el-icon><Close /></el-icon>
                                  </button>
                                </div>
                              </el-dropdown-item>
                            </div>
                            <div class="dropdown-footer-actions">
                              <button type="button" class="footer-btn" :disabled="terminalTabs.length <= 1" @click.stop="closeOtherTerminalTabs(activeTerminalId)">
                                关闭其他
                              </button>
                              <button type="button" class="footer-btn danger" @click.stop="closeAllTerminalTabs">
                                全部关闭
                              </button>
                            </div>
                          </el-dropdown-menu>
                        </template>
                      </el-dropdown>
                      <TerminalToolbar v-if="!activeIsRdp"
                        :allow-disconnect="!!activeTerminalTab"
                        :connection-state="activeTerminalTab?.connectionState || (activeTerminalTab?.token ? 'connected' : 'connecting')"
                        :snippet-visible="snippetBarVisible"
                        :is-split="isSplit"
                        :allow-split="terminalTabs.length > 1"
                        :font-size="terminalFontSize"
                        :terminal-tone="terminalLightTone"
                        :encoding="activeTerminalTab?.encoding || terminalEncoding"
                        @clear="onClearActiveTerminal"
                        @disconnect="disconnectTerminalTab(activeTerminalId)"
                        @reconnect="reconnectTerminalTab(activeTerminalId)"
                        @reconnect-ssh="reconnectTerminalTab(activeTerminalId, true)"
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

                    <!-- 常用快捷命令架 (浮于终端上方，不挤压终端视口) -->
                    <transition name="snippet-slide">
                      <SnippetBar
                        v-if="snippetBarVisible && !activeIsRdp"
                        @run="onRunSnippet"
                        @close="snippetBarVisible = false"
                      />
                    </transition>

                    <!-- 终端显示区域 (支持左右分屏对比) -->
                    <div class="terminal-view-split" :class="{ 'is-split-active': isSplit && splitTerminalTab && !activeIsRdp }">
                      <div class="terminal-pane main-pane">
                        <div v-for="tab in terminalTabs" :key="`terminal-${tab.tabId}`" v-show="activeTerminalId === tab.tabId" class="terminal-tab-content">
                          <RdpDesktop v-if="tab.protocol === 'RDP'" :profile="tab.account"
                            :active="activeTerminalId === tab.tabId && accountTab === 'terminal' && !accountDlg"
                            :ref="el => setTerminalRef(tab.tabId, el)"
                            @state="state => onRdpState(tab.tabId, state)" @edit="editRdpTab(tab)" />
                          <SshTerminal v-else :account-id="tab.accountId"
                            connection-actions-in-toolbar
                            :active="activeTerminalId === tab.tabId"
                            :paused="!!tab.manuallyDisconnected"
                            :terminal-id="tab.terminalId || tab.tabId"
                            :ref="el => setTerminalRef(tab.tabId, el)"
                            :font-size="terminalFontSize"
                            :light-tone="terminalLightTone"
                            :encoding="tab.encoding || terminalEncoding"
                            :current-path="tab.sftpPath || tab.defaultCwd || ''"
                            @drop-files="onTerminalDropFiles"
                            @ready="payload => onTerminalReady(tab.tabId, payload)"
                            @connecting="onTerminalConnecting(tab.tabId)"
                            @disconnected="payload => onTerminalDisconnected(tab.tabId, payload)"
                            @size-change="onTerminalSizeChange"
                            @change-encoding="enc => onChangeEncoding(enc, tab.tabId)"
                            @ask-ai="handleTerminalAskAi" />
                        </div>
                      </div>

                      <div v-if="isSplit && splitTerminalTab && !activeIsRdp" class="terminal-pane split-pane">
                        <div class="split-pane-header">
                          <span class="split-badge">分屏会话</span>
                          <span class="split-label">{{ splitTerminalTab.label }}</span>
                          <TerminalConnectionActions
                            :state="splitTerminalTab.connectionState || (splitTerminalTab.token ? 'connected' : 'connecting')"
                            @disconnect="disconnectTerminalTab(splitTerminalTab.tabId)"
                            @reconnect="reconnectTerminalTab(splitTerminalTab.tabId)"
                            @reconnect-ssh="reconnectTerminalTab(splitTerminalTab.tabId, true)"
                          />
                          <button type="button" class="split-close-btn" title="关闭分屏" @click="isSplit = false">✕</button>
                        </div>
                        <div class="split-pane-content">
                          <SshTerminal :account-id="splitTerminalTab.accountId"
                            connection-actions-in-toolbar
                            :active="isSplit"
                            :paused="!!splitTerminalTab.manuallyDisconnected"
                            :terminal-id="splitTerminalTab.terminalId || splitTerminalTab.tabId"
                            :ref="el => setTerminalRef(`split-${splitTerminalTab.tabId}`, el)"
                            :font-size="terminalFontSize"
                            :light-tone="terminalLightTone"
                            :encoding="splitTerminalTab.encoding || terminalEncoding"
                            :current-path="splitTerminalTab.sftpPath || splitTerminalTab.defaultCwd || ''"
                            @drop-files="onTerminalDropFiles"
                            @ready="payload => onTerminalReady(splitTerminalTab.tabId, payload)"
                            @connecting="onTerminalConnecting(splitTerminalTab.tabId)"
                            @disconnected="payload => onTerminalDisconnected(splitTerminalTab.tabId, payload)"
                            @change-encoding="enc => onChangeEncoding(enc, splitTerminalTab.tabId)" />
                        </div>
                      </div>
                    </div>
                  </div>
                  <el-empty v-else description="从左侧选择账户以打开终端" />
                </template>
                <template #files>
                  <SshFilePanel v-if="activeTerminalTab && !activeIsRdp" ref="filePanelRef"
                    :tab-id="activeTerminalTab.tabId" :saved-path="activeTerminalTab.sftpPath || ''"
                    :account-id="activeTerminalTab.accountId" :host-id="activeTerminalTab.hostId"
                    :server-address="getTabHostAddress(activeTerminalTab)"
                    :active="accountTab === 'terminal'" embedded
                    :session-token="sessionToken" :default-cwd="activeTerminalTab.defaultCwd || ''"
                    :opening-session="openingFileSession" @request-session="establishFileSession"
                    @session-invalid="onFileSessionInvalid"
                    @path-change="onFilePanelPathChange" />
                </template>
              </TerminalFileLayout>
            </div>
            <div v-if="detail.type === 'ACCOUNT'" v-show="accountTab === 'processes'" class="account-tab-pane">
              <SshProcessPanel
                v-if="detail.raw?.id"
                v-show="accountTab === 'processes'"
                :session-token="sessionToken"
                :active="accountTab === 'processes'"
                @request-session="establishFileSession"
              />
            </div>
            <div v-if="detail.type === 'ACCOUNT'" v-show="accountTab === 'disk-usage'" class="account-tab-pane"><SshDiskPanel v-if="detail.raw?.id" :key="detail.raw.id" :account-id="detail.raw.id" :session-token="sessionToken" @request-session="establishFileSession" /></div>
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
              <el-button
                v-if="detail.type === 'GROUP'"
                type="primary"
                plain
                @click="openAddHost(detail.raw.id)"
              >
                + 新建主机
              </el-button>
              <el-button
                v-if="detail.type === 'HOST'"
                type="primary"
                plain
                @click="openAddHost(detail.raw.groupId)"
              >
                + 新建主机
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
              <span class="host-accounts-title">账户连接 ({{ hostAccounts.length }})</span>
              <el-button size="small" type="primary" plain @click="openAddAccount(detail.raw.id)">
                + 新建账户
              </el-button>
            </div>
            <div v-if="hostAccounts.length" class="host-accounts-list">
              <div v-for="acc in hostAccounts" :key="acc.key" class="host-account-item">
                <div class="acc-info">
                  <el-icon class="acc-icon"><User /></el-icon>
                  <span class="acc-name">{{ acc.label || acc.username }}</span>
                  <el-tag size="small" type="info">{{ isRdp(acc) ? 'RDP' : 'SSH' }}</el-tag>
                </div>
                <el-button size="small" type="primary" @click="connectHostAccount(acc)">
                  打开连接
                </el-button>
              </div>
            </div>
            <div v-else class="host-accounts-empty">
              <p>该主机暂未配置账户连接</p>
              <el-button type="primary" size="small" @click="openAddAccount(detail.raw.id)">
                新建账户
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
          <el-tree-select
            v-model="hostForm.groupId"
            :data="hostGroupTree"
            check-strictly
            filterable
            clearable
            placeholder="选择所属分组"
            style="width: 100%"
            :render-after-expand="false"
            popper-class="ssh-group-tree-popper"
          />
        </el-form-item>
        <el-form-item label="展示名" prop="name">
          <el-input v-model="hostForm.name" maxlength="128" placeholder="如：测试服、业务网关、DB主库" clearable />
        </el-form-item>
        <el-form-item label="地址" prop="host">
          <el-input v-model="hostForm.host" placeholder="IP（IPv4/IPv6）或域名" clearable />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="hostForm.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
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
    <AccountConnectionEditor v-model="accountDlg" :initial="accountForm" :host-options="hostOptions" @saved="onAccountSaved" />

    <!-- 共享终端会话弹窗 -->
    <TerminalShareDialog
      v-model="shareDialogVisible"
      :account="detail?.raw"
      :terminal-tab="activeTerminalTab"
    />

    <!-- 终端标签页右键菜单 -->
    <div
      v-if="contextMenu.visible"
      class="tab-context-menu"
      :style="{ top: `${contextMenu.y}px`, left: `${contextMenu.x}px` }"
      @click.stop
    >
      <div class="context-menu-item" @click="handleContextMenuRename">
        <el-icon><EditPen /></el-icon>
        <span>重命名标签</span>
      </div>
      <div class="context-menu-item" @click="handleContextMenuNewTab">
        <el-icon><Plus /></el-icon>
        <span>新建会话连接</span>
      </div>
      <div class="context-menu-divider"></div>
      <div class="context-menu-item" @click="handleContextMenuClose">
        <el-icon><Close /></el-icon>
        <span>关闭当前标签</span>
      </div>
      <div class="context-menu-item" :class="{ 'is-disabled': terminalTabs.length <= 1 }" @click="handleContextMenuCloseOthers">
        <el-icon><FolderRemove /></el-icon>
        <span>关闭其他标签</span>
      </div>
      <div class="context-menu-item" :class="{ 'is-disabled': isContextMenuLastTab }" @click="handleContextMenuCloseRight">
        <el-icon><Right /></el-icon>
        <span>关闭右侧标签</span>
      </div>
      <div class="context-menu-divider"></div>
      <div class="context-menu-item danger" @click="handleContextMenuCloseAll">
        <el-icon><Delete /></el-icon>
        <span>关闭全部连接</span>
      </div>
    </div>

  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { getRdpConnection, deleteRdpConnection, testRdpConnection } from '@/api/rdp'
import { createRdpTab, matchesConnection } from '@/utils/connectionTabs'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Close,
  CopyDocument,
  Delete,
  EditPen,
  Expand,
  Fold,
  Folder,
  FolderRemove,
  Grid,
  Monitor,
  MoreFilled,
  Plus,
  Refresh,
  Right,
  Search,
  User
} from '@element-plus/icons-vue'
import { useWorkspaceStore } from '@/stores/workspace'
import SshTerminal from '@/components/ssh/SshTerminal.vue'
import TerminalToolbar from '@/components/ssh/TerminalToolbar.vue'
import TerminalConnectionActions from '@/components/ssh/TerminalConnectionActions.vue'
import TerminalConnectionStatus from '@/components/ssh/TerminalConnectionStatus.vue'
import { tmuxCompatibility } from '@/utils/tmuxCompatibility'
import TerminalShareDialog from '@/components/ssh/TerminalShareDialog.vue'
import SnippetBar from '@/components/ssh/SnippetBar.vue'
import TerminalFileLayout from '@/components/ssh/TerminalFileLayout.vue'
import { useLayoutPreference } from '@/composables/useLayoutPreference'
import SshFilePanel from '@/components/ssh/SshFilePanel.vue'
import SshMonitorPanel from '@/components/ssh/SshMonitorPanel.vue'
import ScriptManagement from '@/components/ssh/ScriptManagement.vue'
import SshProcessPanel from '@/components/ssh/SshProcessPanel.vue'
import SshDiskPanel from '@/components/ssh/SshDiskPanel.vue'
import { getTabShortcutLabel, parseTabSwitchShortcut, resolveTargetTab } from '@/utils/terminalTabShortcut'
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
  fetchSearchTargets,
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
  readSavedTerminalTabs,
  formatTerminalTabDisplayLabel,
  getTabUsername,
  getTabMiniHost,
  getTabThemeStyle
} from '@/utils/sshConsoleStorage'
import {
  validateHostAddressRule,
  validateHostPortRule
} from '@/utils/hostValidator'

const RdpDesktop = defineAsyncComponent(() => import('@/components/rdp/RdpDesktop.vue'))
const AccountConnectionEditor = defineAsyncComponent(() => import('@/components/ssh/AccountConnectionEditor.vue'))
function isRdp(value) { return value?.protocol === 'RDP' || value?.nodeType === 'RDP_ACCOUNT' || value?.extra?.protocol === 'RDP' }
function isAccountNode(value) { return value?.nodeType === 'ACCOUNT' || value?.nodeType === 'RDP_ACCOUNT' }
function isTabConnected(tab) { return tab?.protocol === 'RDP' ? tab.connectionState === 'connected' : !!tab?.token }
function tabMatchesNode(tab, node) { return matchesConnection(tab, node.id, isRdp(node) ? 'RDP' : 'SSH') }


const savedAccount = readSavedSshAccount()
const { managedServer } = storeToRefs(useWorkspaceStore())
const sidebarCollapsed = useLayoutPreference('sidebarCollapsed')

const DEFAULT_SIDEBAR_WIDTH = 280
const MIN_SIDEBAR_WIDTH = 220
const MAX_SIDEBAR_WIDTH = 640
const sidebarWidth = useLayoutPreference('sidebarWidth')
const isSidebarResizing = ref(false)
let sidebarResizeOrigin = null

function onSidebarResizeStart(event) {
  if (event.button !== 0 || sidebarCollapsed.value) return
  sidebarResizeOrigin = {
    startX: event.clientX,
    startWidth: sidebarWidth.value
  }
  isSidebarResizing.value = true
  try {
    event.currentTarget.setPointerCapture(event.pointerId)
  } catch {}
  event.preventDefault()
}

function onSidebarResizeMove(event) {
  if (!sidebarResizeOrigin) return
  const deltaX = event.clientX - sidebarResizeOrigin.startX
  const newWidth = Math.min(MAX_SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH, sidebarResizeOrigin.startWidth + deltaX))
  sidebarWidth.value = Math.round(newWidth)
}

function onSidebarResizeEnd(event) {
  if (!sidebarResizeOrigin) return
  sidebarResizeOrigin = null
  isSidebarResizing.value = false
  try {
    event.currentTarget.releasePointerCapture(event.pointerId)
  } catch {}
}

function resizeSidebarByKeyboard(event) {
  if (sidebarCollapsed.value || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Home') sidebarWidth.value = MIN_SIDEBAR_WIDTH
  else if (event.key === 'End') sidebarWidth.value = MAX_SIDEBAR_WIDTH
  else sidebarWidth.value = Math.min(MAX_SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH,
    sidebarWidth.value + (event.key === 'ArrowRight' ? 10 : -10)))
}

function resetSidebarWidth() {
  if (sidebarCollapsed.value) return
  sidebarWidth.value = DEFAULT_SIDEBAR_WIDTH
}

async function copyText(text, successMsg = '已复制到剪贴板') {
  if (!text) return
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const input = document.createElement('input')
      input.value = text
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    ElMessage.success(successMsg)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
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

const accountDlg = ref(false)
const accountForm = ref({})
const hostOptions = ref([])

const detail = ref({ type: null, raw: null })
const hostAccounts = ref([])
const route = useRoute()
const router = useRouter()
const accountTab = computed(() => route.meta.panel || '')
const terminalVisited = ref(accountTab.value === 'terminal')
const scriptsVisited = ref(accountTab.value === 'scripts')
watch(accountTab, tab => { if (tab === 'scripts') scriptsVisited.value = true })
watch(accountTab, tab => { if (tab === 'terminal') terminalVisited.value = true })
const accountHostMeta = ref({ name: '', remark: '', host: '', port: 22, groupId: null })
const sessionToken = ref('')
const terminalTabs = ref([])
const activeTerminalId = ref(null)
const activeTerminalTab = computed(() => terminalTabs.value.find(item => item.tabId === activeTerminalId.value) || null)
const activeIsRdp = computed(() => activeTerminalTab.value?.protocol === 'RDP')
const tabScrollRef = ref(null)
const showLeftScroll = ref(false)
const showRightScroll = ref(false)
const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  tab: null
})
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
  return terminalTabs.value.find(tab => tab.tabId !== activeTerminalId.value && tab.protocol !== 'RDP') || null
})

const filePanelRef = ref(null)
const currentSftpPath = computed(() => activeTerminalTab.value?.sftpPath || '')

function onFilePanelPathChange(path, tabId) {
  const tab = terminalTabs.value.find(tab => tab.tabId === tabId)
  if (!tab || !path) return
  tab.sftpPath = path
  persistTerminalTabs()
}

async function onTerminalDropFiles(dataTransfer) {
  if (!activeTerminalTab.value) return
  if (!sessionToken.value) {
    ElMessage.info('正在建立 SFTP 会话以开始上传...')
    await establishFileSession()
  }
  await nextTick()
  if (filePanelRef.value?.processDroppedDataTransfer) {
    const targetDir = currentSftpPath.value || activeTerminalTab.value?.defaultCwd || ''
    await filePanelRef.value.processDroppedDataTransfer(dataTransfer, targetDir)
  }
}

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

function disconnectTerminalTab(tabId) {
  // A split tab also has a mounted primary view; detach both clients.
  terminalRefs.get(tabId)?.disconnect()
  terminalRefs.get(`split-${tabId}`)?.disconnect()
}

function reconnectTerminalTab(tabId, plainSsh = false) {
  const method = plainSsh ? 'reconnectPlainSsh' : 'reconnect'
  terminalRefs.get(tabId)?.[method]()
  terminalRefs.get(`split-${tabId}`)?.[method]()
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
  groupId: [{ required: true, message: '请选择所属分组', trigger: 'change' }],
  name: [{ required: true, message: '请输入展示名', trigger: 'blur' }],
  host: [
    { required: true, message: '请输入主机地址（IP 或域名）', trigger: 'blur' },
    { validator: validateHostAddressRule, trigger: ['blur', 'change'] }
  ],
  port: [
    { required: true, message: '请输入 SSH 端口号', trigger: 'blur' },
    { validator: validateHostPortRule, trigger: ['blur', 'change'] }
  ]
}

const selectedGroupId = computed(() => {
  const n = selectedNode.value
  if (!n) return null
  if (n.nodeType === 'GROUP') return n.id
  if (n.nodeType === 'HOST') return n.extra?.groupId ?? null
  if (isAccountNode(n)) {
    return n.extra?.groupId ?? accountHostMeta.value?.groupId ?? null
  }
  return null
})

const selectedHostId = computed(() => {
  const n = selectedNode.value
  if (!n) return null
  if (n.nodeType === 'HOST') return n.id
  if (isAccountNode(n)) {
    return n.extra?.hostId ?? detail.value.raw?.hostId ?? null
  }
  return null
})

if (savedAccount) {
  resolveSshExpandedKeys(savedAccount, { getGroup, getHost }).then((keys) => {
    defaultExpandedKeys.value = keys
  })
}

const searchTargetsCache = ref([])
async function loadSearchTargets() {
  try {
    const list = await fetchSearchTargets()
    if (Array.isArray(list)) {
      searchTargetsCache.value = list
    }
  } catch {
    /* fallback quietly */
  }
}

const matchingGroupIds = computed(() => {
  const q = filterText.value.trim().toLowerCase()
  if (!q) return new Set()
  const s = new Set()
  for (const item of searchTargetsCache.value) {
    const hostAddr = String(item.hostAddress || '').toLowerCase()
    const hostName = String(item.hostName || '').toLowerCase()
    const endpoint = `${hostAddr}:${item.hostPort || 22}`
    const user = String(item.username || '').toLowerCase()
    const accRemark = String(item.accountRemark || '').toLowerCase()
    const hostRemark = String(item.hostRemark || '').toLowerCase()
    if (
      hostAddr.includes(q) ||
      endpoint.includes(q) ||
      hostName.includes(q) ||
      user.includes(q) ||
      accRemark.includes(q) ||
      hostRemark.includes(q)
    ) {
      if (item.groupId != null) {
        s.add(item.groupId)
      }
    }
  }
  return s
})

watch(filterText, async (val) => {
  if (val && searchTargetsCache.value.length === 0) {
    await loadSearchTargets()
  }
  if (val) {
    for (const gid of matchingGroupIds.value) {
      const gKey = `g:${gid}`
      if (!defaultExpandedKeys.value.includes(gKey)) {
        defaultExpandedKeys.value.push(gKey)
      }
    }
  }
  treeRef.value?.filter(val)
})

function handleTabSwitchAction(action) {
  if (!action || !terminalTabs.value.length) return
  const target = resolveTargetTab(terminalTabs.value, activeTerminalId.value, action)
  if (!target) return
  if (target.tabId !== activeTerminalId.value) {
    activateTerminalTab(target.tabId, true)
  } else {
    focusConnectionTab(target.tabId)
  }
}

function onSwitchTerminalTabEvent(event) {
  if (event?.detail) {
    handleTabSwitchAction(event.detail)
  }
}

function onGlobalTabSwitchKeydown(event) {
  if (event.defaultPrevented) return
  const action = parseTabSwitchShortcut(event)
  if (action) {
    event.preventDefault()
    handleTabSwitchAction(action)
  }
}

onMounted(() => {
  restoreSavedAccount()
  void loadSearchTargets()
  window.addEventListener('doumi-configuration-imported', refreshTree)
  window.addEventListener('doumi-open-account', onGlobalOpenAccount)
  window.addEventListener('doumi-terminal-action', onGlobalTerminalAction)
  window.addEventListener('doumi-switch-terminal-tab', onSwitchTerminalTabEvent)
  window.addEventListener('keydown', onGlobalTabSwitchKeydown)
  window.addEventListener('click', closeContextMenu)
  window.addEventListener('blur', closeContextMenu)
  window.addEventListener('resize', checkScrollOverflow)
  nextTick(() => {
    checkScrollOverflow()
    scrollToActiveTab()
  })
})
onBeforeUnmount(() => {
  window.removeEventListener('doumi-configuration-imported', refreshTree)
  window.removeEventListener('doumi-open-account', onGlobalOpenAccount)
  window.removeEventListener('doumi-terminal-action', onGlobalTerminalAction)
  window.removeEventListener('doumi-switch-terminal-tab', onSwitchTerminalTabEvent)
  window.removeEventListener('keydown', onGlobalTabSwitchKeydown)
  window.removeEventListener('click', closeContextMenu)
  window.removeEventListener('blur', closeContextMenu)
  window.removeEventListener('resize', checkScrollOverflow)
  onSidebarResizeEnd()
})

async function persistAccountSelection(node, hostGroupId) {
  if (!isAccountNode(node)) return
  writeSavedSshAccount({
    nodeType: node.nodeType,
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
  const protocol = activeTabAccount?.protocol === 'RDP' || (!activeTabAccount && saved?.nodeType === 'RDP_ACCOUNT') ? 'RDP' : 'SSH'
  const targetAccountId = (protocol === 'RDP' ? activeTabAccount?.connectionId : activeTabAccount?.accountId) || saved?.id
  if (!targetAccountId) return
  let acc
  try {
    acc = protocol === 'RDP' ? { ...await getRdpConnection(targetAccountId), protocol: 'RDP' } : await getAccount(targetAccountId)
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
    nodeType: protocol === 'RDP' ? 'RDP_ACCOUNT' : 'ACCOUNT',
    id: acc.id,
    key: `${protocol === 'RDP' ? 'r' : 'a'}:${acc.id}`,
    label: acc.username,
    leaf: true,
    extra: {
      hostId: acc.hostId,
      groupId: groupId ?? null
    }
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
  treeRef.value?.setCurrentKey?.(node.key, false)
}

function hasCustomHostName(data) {
  if (data?.nodeType !== 'HOST') return false
  const label = (data.label || '').trim()
  const host = (data.extra?.host || '').trim()
  const endpoint = (data.extra?.endpoint || '').trim()
  if (!label) return false
  if (label === host || label === endpoint || label === `${host}:22`) return false
  return true
}

function getNodeMainTitle(data) {
  if (data.nodeType === 'HOST') {
    const host = (data.extra?.host || '').trim()
    const port = data.extra?.port
    const endpoint = data.extra?.endpoint || ''
    const label = (data.label || '').trim()

    // 若无自定义别名或别名就是 IP，直接显示完整 IP，独占可用宽度
    if (!hasCustomHostName(data)) {
      if (port && port !== 22) {
        return endpoint || `${host}:${port}` || label || '未命名主机'
      }
      return host || label || endpoint || '未命名主机'
    }
    // 有自定义别名场景：显示别名
    return label
  }
  return data.label || ''
}

function getNodeSubTitle(data) {
  if (data.nodeType === 'HOST') {
    const host = (data.extra?.host || '').trim()
    const port = data.extra?.port
    const endpoint = data.extra?.endpoint || ''

    // 若有自定义别名，副文本显示 IP（非 22 端口显示 endpoint）
    if (hasCustomHostName(data)) {
      if (port && port !== 22) {
        return endpoint || `${host}:${port}`
      }
      return host || endpoint
    }
    // 无自定义别名时，主标题已展示完整 IP，副文本只展示备注，避免重复 IP 占用空间导致截断
    return data.remark || ''
  }
  if (data.remark) return data.remark
  return ''
}

function nodeTagType(data) {
  if (isAccountNode(data)) return 'success'
  if (data.nodeType === 'HOST') return 'primary'
  return 'warning'
}

function nodeTypeName(data) {
  if (isAccountNode(data)) return '账户'
  if (data.nodeType === 'HOST') return '主机'
  return '分组'
}

function filterNode(value, data) {
  if (!value) return true
  const v = String(value).trim().toLocaleLowerCase()
  if (!v) return true
  if (data.nodeType === 'GROUP') {
    if (matchingGroupIds.value.has(data.id)) return true
    return [data.label, data.remark].some(field => String(field || '').toLocaleLowerCase().includes(v))
  }
  const host = data.extra?.host || ''
  const endpoint = data.extra?.endpoint || ''
  return [data.label, data.remark, host, endpoint]
    .some(field => String(field || '').toLocaleLowerCase().includes(v))
}

function checkHighlightCurrent(list) {
  if (treeCurrentNodeKey.value && (list || []).some((n) => n.key === treeCurrentNodeKey.value)) {
    nextTick(() => {
      treeRef.value?.setCurrentKey?.(treeCurrentNodeKey.value, false)
      const el = treeRef.value?.$el?.querySelector('.is-current')
      el?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
    })
  }
}

function onNodeExpand(data) {
  if (data?.key) {
    if (!defaultExpandedKeys.value.includes(data.key)) {
      defaultExpandedKeys.value.push(data.key)
    }
  }
}

function onNodeCollapse(data, node) {
  if (data?.key) {
    const toRemove = new Set([data.key])
    function collectChildKeys(n) {
      if (n && n.childNodes) {
        for (const child of n.childNodes) {
          if (child.data?.key) toRemove.add(child.data.key)
          collectChildKeys(child)
        }
      }
    }
    collectChildKeys(node)
    defaultExpandedKeys.value = defaultExpandedKeys.value.filter((k) => !toRemove.has(k))
  }
}

async function loadNode(node, resolve) {
  if (node.level === 0) {
    treeLoading.value = true
    try {
      const list = await fetchTreeChildren('ROOT')
      const mapped = mapNodes(list)
      resolve(mapped)
      checkHighlightCurrent(mapped)
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
      const list = await fetchTreeChildren('GROUP', data.id)
      const mapped = mapNodes(list)
      resolve(mapped)
      checkHighlightCurrent(mapped)
    } else if (data.nodeType === 'HOST') {
      const list = await fetchTreeChildren('HOST', data.id)
      const mapped = (list || []).map((child) => ({
        ...child,
        leaf: child.leaf === true,
        extra: {
          ...child.extra,
          hostId: child.extra?.hostId ?? data.id,
          groupId: child.extra?.groupId ?? data.extra?.groupId ?? null,
          host: data.extra?.host || '',
          endpoint: data.extra?.endpoint || ''
        }
      }))
      resolve(mapped)
      checkHighlightCurrent(mapped)
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
      (['ACCOUNT', 'RDP_ACCOUNT'].includes(sourceType) && targetType === 'HOST')
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
    if (type === 'RDP_ACCOUNT' && crossParent) await syncRdpTabs(await getRdpConnection(draggingNode.data.id))
    if (selectedNode.value?.nodeType === type && selectedNode.value.id === draggingNode.data.id) {
      selectedNode.value = draggingNode.data
      await loadDetail(draggingNode.data)
    }
  } catch (error) {
    ElMessage.error(error?.message || '移动或排序失败，请刷新后重试')
    refreshTree()
  }
}

async function refreshTree(targetNode = null) {
  const explicitTarget = targetNode && typeof targetNode === 'object' && targetNode.nodeType ? targetNode : null
  if (explicitTarget) {
    selectedNode.value = explicitTarget
    treeCurrentNodeKey.value = explicitTarget.key
  }
  const current = selectedNode.value

  const expandedSet = new Set(defaultExpandedKeys.value || [])
  if (current?.nodeType && current.id != null) {
    try {
      const neededKeys = await resolveSshExpandedKeys(current, { getGroup, getHost })
      for (const k of neededKeys) expandedSet.add(k)
    } catch { /* ignore */ }
  }
  defaultExpandedKeys.value = Array.from(expandedSet)
  treeVersion.value += 1
  // Keep the current account workspace mounted while its tree/detail data is
  // refreshed. Clearing detail here unmounts the terminal, which closes its
  // WebSocket and invalidates the session token; the next render could then
  // reuse that stale token and issue "session does not exist" requests.
  const sameDetail = current?.nodeType === detail.value.type
    && current?.id != null
    && Number(current.id) === Number(detail.value.raw?.id)
  if (!sameDetail) detail.value = { type: null, raw: null }
  void loadSearchTargets()
  nextTick(async () => {
    if (current?.nodeType && current.id != null) {
      await loadDetail(current)
      treeCurrentNodeKey.value = current.key
      treeRef.value?.setCurrentKey?.(current.key, false)
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
  const protocol = isRdp(acc) ? 'RDP' : 'SSH'
  const node = {
    nodeType: protocol === 'RDP' ? 'RDP_ACCOUNT' : 'ACCOUNT',
    id: acc.id,
    key: `${protocol === 'RDP' ? 'r' : 'a'}:${acc.id}`,
    label: acc.label || acc.username,
    leaf: true,
    extra: {
      ...(acc.extra || {}),
      hostId: acc.hostId ?? detail.value.raw?.id,
      groupId: acc.groupId ?? detail.value.raw?.groupId ?? null
    }
  }
  selectedNode.value = node
  treeCurrentNodeKey.value = node.key
  treeRef.value?.setCurrentKey?.(node.key, false)
  await loadDetail(node)
  if (route.meta.panel !== 'terminal') {
    router.push('/terminal')
  }
}

async function onGlobalOpenAccount(e) {
  const target = e.detail
  if (!target) return
  if (target.accountId) {
    const acc = {
      id: target.accountId,
      protocol: target.protocol || 'SSH',
      username: target.username,
      hostId: target.hostId,
      groupId: target.groupId,
      label: target.username ? `${target.username}@${target.hostName || target.hostAddress}` : (target.hostName || target.hostAddress)
    }
    await connectHostAccount(acc)
  } else if (target.hostId) {
    const node = {
      nodeType: 'HOST',
      id: target.hostId,
      key: `h:${target.hostId}`,
      label: target.hostName || target.hostAddress,
      extra: {
        host: target.hostAddress,
        port: target.hostPort || 22,
        groupId: target.groupId
      }
    }
    selectedNode.value = node
    treeCurrentNodeKey.value = node.key
    treeRef.value?.setCurrentKey?.(node.key, false)
    await loadDetail(node)
  }
}

function onGlobalTerminalAction(e) {
  if (activeIsRdp.value) return
  const act = e.detail
  if (act === 'clear') {
    onClearActiveTerminal()
  } else if (act === 'split') {
    isSplit.value = !isSplit.value
  } else if (act === 'snippets') {
    snippetBarVisible.value = !snippetBarVisible.value
  }
}

let detailRequest = 0
async function loadDetail(data) {
  const current = ++detailRequest
  try {
    if (data.nodeType === 'GROUP') {
      sessionToken.value = ''
      hostAccounts.value = []
      const raw = await getGroup(data.id)
      if (current !== detailRequest) return
      detail.value = { type: 'GROUP', raw }
    } else if (data.nodeType === 'HOST') {
      sessionToken.value = ''
      const raw = await getHost(data.id)
      if (current !== detailRequest) return
      detail.value = { type: 'HOST', raw }
      try {
        const accs = await fetchTreeChildren('HOST', data.id)
        if (current === detailRequest) hostAccounts.value = accs || []
      } catch {
        if (current === detailRequest) hostAccounts.value = []
      }
    } else if (isRdp(data)) {
      hostAccounts.value = []
      const raw = { ...await getRdpConnection(data.id), protocol: 'RDP' }
      if (current !== detailRequest) return
      const host = await loadAccountHostMeta(raw.hostId)
      if (current !== detailRequest) return
      activateRdpForAccount(raw, host)
      detail.value = { type: 'RDP_ACCOUNT', raw }
      await persistAccountSelection(data, host.groupId)
      if (accountTab.value !== 'terminal') await router.push('/terminal')
    } else {
      hostAccounts.value = []
      const raw = await getAccount(data.id)
      if (current !== detailRequest) return
      detail.value = { type: 'ACCOUNT', raw }
      terminalVisited.value = accountTab.value === 'terminal'
      activateTerminalForAccount(raw)
      const hostMeta = await loadAccountHostMeta(raw.hostId)
      if (current !== detailRequest) return
      const openedTab = terminalTabs.value.find(item => item.accountId === raw.id)
      if (openedTab) {
        openedTab.hostName = hostMeta.name
        openedTab.hostAddress = hostMeta.host
        openedTab.label = terminalTabLabel(raw, hostMeta)
      }
      const groupId = hostMeta?.groupId ?? null
      if (data.extra) {
        data.extra.groupId = groupId
      }
      await persistAccountSelection(data, groupId)
    }
  } catch {
    if (current !== detailRequest) return
    if (isRdp(data)) { ElMessage.error('远程桌面账户加载失败'); return }
    detail.value = { type: data.nodeType, raw: data }
    if (isAccountNode(data) && !isRdp(data)) {
      if (data.extra?.hostId) {
        await loadAccountHostMeta(data.extra.hostId)
      }
      activateTerminalForAccount(data)
    }
  }
}

let hostMetaRequest = 0
async function loadAccountHostMeta(hostId) {
  const current = ++hostMetaRequest
  let meta = { name: '', remark: '', host: '', port: 22, groupId: null }
  if (hostId) try {
    const host = await getHost(hostId)
    meta = { id: host.id, name: host.name || '', remark: host.remark || '', host: host.host || '', port: host.port || 22, groupId: host.groupId ?? null }
  } catch { /* Metadata failures do not change the selected protocol. */ }
  if (current === hostMetaRequest) accountHostMeta.value = meta
  return meta
}

// Keep the shared AI context tied to the terminal tab currently in the foreground.
// An account may have multiple tabs, so accountId alone is not enough to identify it.
watch([detail, accountHostMeta, activeTerminalTab], () => {
  const tab = activeTerminalTab.value
  workspaceStore.activeDesktop = tab?.protocol === 'RDP' ? {
    name: tab.hostName || tab.hostAddress,
    address: tab.hostAddress || '',
    port: tab.account?.port || 3389,
    username: tab.account?.username || '',
    connected: tab.connectionState === 'connected'
  } : null
  if (tab?.protocol === 'RDP' || detail.value.type === 'RDP_ACCOUNT') {
    workspaceStore.terminalConnected = false; managedServer.value = null; return
  }
  workspaceStore.terminalConnected = Boolean(tab?.token)
  const raw = tab?.account || detail.value.raw
  if (tab?.accountId && raw) {
    const host = accountHostMeta.value
    managedServer.value = {
      hostId: tab.hostId || raw.hostId,
      accountId: tab.accountId,
      name: host.name || `主机#${tab.hostId || raw.hostId}`,
      address: host.host || '',
      port: host.port || 22,
      username: raw.username || '',
      tabId: tab.tabId,
      terminalId: tab.terminalId || tab.tabId,
      terminalLabel: tab.label || '',
      connectionMode: tab.connectionMode || ''
    }
  } else if (detail.value.type === 'ACCOUNT' && raw) {
    const host = accountHostMeta.value
    managedServer.value = {
      hostId: raw.hostId, accountId: raw.id, name: host.name || `主机#${raw.hostId}`,
      address: host.host || '', port: host.port || 22, username: raw.username || ''
    }
  } else if (detail.value.type === 'HOST' && raw) {
    managedServer.value = { hostId: raw.id, name: raw.name || raw.host, address: raw.host || '', port: raw.port || 22, username: '' }
  } else managedServer.value = null
}, { deep: true, immediate: true })

function persistTerminalTabs() {
  saveTerminalTabs(terminalTabs.value, activeTerminalId.value)
}

function terminalTabLabel(account, host = accountHostMeta.value) {
  const hostName = (host?.name || account.host || `主机#${account.hostId}`).trim()
  const hostAddress = (host?.host || account.host || '').trim()

  let hostPart = hostName
  if (hostAddress && hostAddress !== hostName) {
    hostPart = `${hostName} (${hostAddress})`
  }

  return `${hostPart} · ${account.username || '账户'}`
}

function getTabDisplayLabel(tab) {
  if (tab.protocol === 'RDP') return tab.customTitle || tab.label
  return formatTerminalTabDisplayLabel(tab, accountHostMeta.value)
}

function getTabHostAddress(tab) {
  if (!tab) return ''
  const host = tab.hostAddress || tab.account?.host || (tab.hostId === accountHostMeta.value?.id ? accountHostMeta.value?.host : '')
  const port = tab.account?.port || (tab.hostId === accountHostMeta.value?.id ? accountHostMeta.value?.port : 22)
  if (!host) return ''
  return port && Number(port) !== 22 ? `${host}:${port}` : host
}

function checkScrollOverflow() {
  const el = tabScrollRef.value
  if (!el) {
    showLeftScroll.value = false
    showRightScroll.value = false
    return
  }
  showLeftScroll.value = el.scrollLeft > 4
  showRightScroll.value = el.scrollLeft + el.clientWidth < el.scrollWidth - 4
}

function onTabWheel(event) {
  const el = tabScrollRef.value
  if (!el) return
  if (event.deltaY !== 0 && event.deltaX === 0) {
    event.preventDefault()
    el.scrollLeft += event.deltaY
    checkScrollOverflow()
  }
}

function scrollTabs(direction) {
  const el = tabScrollRef.value
  if (!el) return
  const amount = 200
  el.scrollBy({ left: direction === 'left' ? -amount : amount, behavior: 'smooth' })
  setTimeout(checkScrollOverflow, 300)
}

function scrollToActiveTab() {
  const el = tabScrollRef.value?.querySelector('.terminal-tab.is-active')
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' })
  }
  checkScrollOverflow()
}

watch(activeTerminalId, () => {
  nextTick(scrollToActiveTab)
})

watch(() => terminalTabs.value.length, () => {
  nextTick(checkScrollOverflow)
})

const isContextMenuLastTab = computed(() => {
  if (!contextMenu.tab || !terminalTabs.value.length) return true
  const idx = terminalTabs.value.findIndex(t => t.tabId === contextMenu.tab.tabId)
  return idx === -1 || idx === terminalTabs.value.length - 1
})

function openTabContextMenu(event, tab) {
  const menuWidth = 160
  const menuHeight = 220
  const x = Math.min(event.clientX, window.innerWidth - menuWidth - 10)
  const y = Math.min(event.clientY, window.innerHeight - menuHeight - 10)
  contextMenu.visible = true
  contextMenu.x = x
  contextMenu.y = y
  contextMenu.tab = tab
}

function closeContextMenu() {
  if (contextMenu.visible) {
    contextMenu.visible = false
    contextMenu.tab = null
  }
}

async function handleContextMenuRename() {
  const tab = contextMenu.tab
  closeContextMenu()
  if (!tab) return
  try {
    const currentName = tab.customTitle || getTabDisplayLabel(tab)
    const { value } = await ElMessageBox.prompt('请输入新的终端标签名称（留空恢复默认）：', '重命名终端标签', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: currentName,
      inputPlaceholder: '例如：Nginx反代 / Redis排障'
    })
    tab.customTitle = (value || '').trim()
    persistTerminalTabs()
  } catch {
    /* user cancelled */
  }
}

function handleContextMenuNewTab() {
  const tab = contextMenu.tab
  closeContextMenu()
  if (!tab) return
  openTabForAccount(tab)
}

function handleContextMenuClose() {
  const tabId = contextMenu.tab?.tabId
  closeContextMenu()
  if (tabId) closeTerminalTab(tabId)
}

function handleContextMenuCloseOthers() {
  const tabId = contextMenu.tab?.tabId
  closeContextMenu()
  if (tabId) closeOtherTerminalTabs(tabId)
}

function handleContextMenuCloseRight() {
  const tabId = contextMenu.tab?.tabId
  closeContextMenu()
  if (tabId) closeRightTerminalTabs(tabId)
}

function handleContextMenuCloseAll() {
  closeContextMenu()
  closeAllTerminalTabs()
}

function openTabForAccount(tab) {
  if (!tab?.accountId) return
  if (isRdp(tab)) return activateRdpForAccount(tab.account, { name: tab.hostName, host: tab.hostAddress }, true)
  const { tabId, terminalId, subIndex } = allocateTerminalId(tab.accountId, terminalTabs.value, { forceNew: true })
  const baseLabel = tab.account ? terminalTabLabel(tab.account) : (tab.label || '终端')
  const newTab = {
    ...tab,
    tabId,
    terminalId,
    label: `${baseLabel} #${subIndex}`,
    customTitle: '',
    subIndex,
    token: '',
    encoding: tab.encoding || terminalEncoding.value
  }
  terminalTabs.value.push(newTab)
  activeTerminalId.value = newTab.tabId
  sessionToken.value = ''
  persistTerminalTabs()
  nextTick(scrollToActiveTab)
}

function closeOtherTerminalTabs(tabId) {
  const keep = terminalTabs.value.find(t => t.tabId === tabId)
  if (!keep) return
  terminalTabs.value = [keep]
  if (activeTerminalId.value !== keep.tabId) {
    activateTerminalTab(keep.tabId)
  } else {
    persistTerminalTabs()
  }
  nextTick(checkScrollOverflow)
}

function closeRightTerminalTabs(tabId) {
  const index = terminalTabs.value.findIndex(t => t.tabId === tabId)
  if (index < 0) return
  terminalTabs.value = terminalTabs.value.slice(0, index + 1)
  if (!terminalTabs.value.some(t => t.tabId === activeTerminalId.value)) {
    const target = terminalTabs.value[index]
    if (target) activateTerminalTab(target.tabId)
  } else {
    persistTerminalTabs()
  }
  nextTick(checkScrollOverflow)
}

function closeAllTerminalTabs() {
  detailRequest++
  terminalTabs.value = []
  activeTerminalId.value = null
  sessionToken.value = ''
  persistTerminalTabs()
  nextTick(checkScrollOverflow)
}

function handleTabDropdownCommand(cmd) {
  if (cmd?.action === 'activate' && cmd.tabId) {
    activateTerminalTab(cmd.tabId)
  }
}

function activateRdpForAccount(account, host, forceNew = false) {
  let tab = !forceNew && terminalTabs.value.find(tab => matchesConnection(tab, account.id, 'RDP'))
  if (!tab) { tab = createRdpTab(account, host, terminalTabs.value); terminalTabs.value.push(tab) }
  else { tab.account = { ...account, protocol: 'RDP' }; tab.hostName = host.name; tab.hostAddress = host.host; tab.hostId = account.hostId }
  activeTerminalId.value = tab.tabId; sessionToken.value = ''; terminalVisited.value = true
  detail.value = { type: 'RDP_ACCOUNT', raw: tab.account }
  persistTerminalTabs(); nextTick(scrollToActiveTab)
}

function focusConnectionTab(tabId) {
  const tab = terminalTabs.value.find(tab => tab.tabId === tabId)
  const method = isRdp(tab) ? 'focusDisplay' : 'focusTerminal'
  terminalRefs.get(tabId)?.[method]?.()
}

function onRdpState(tabId, state) {
  const tab = terminalTabs.value.find(tab => tab.tabId === tabId)
  if (tab) tab.connectionState = state.state
}

function activateTerminalForAccount(account) {
  if (!account?.id) return
  let tab = terminalTabs.value.find(item => item.accountId === account.id)
  if (!tab) {
    const { tabId, terminalId, subIndex } = allocateTerminalId(account.id, terminalTabs.value)
    tab = {
      tabId,
      terminalId,
      accountId: account.id,
      account,
      hostId: account.hostId,
      defaultCwd: account.defaultCwd || '',
      sftpPath: '',
      label: terminalTabLabel(account),
      customTitle: '',
      subIndex: subIndex || 1,
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
  nextTick(scrollToActiveTab)
}

function openAdditionalTerminalTab() {
  const current = activeTerminalTab.value
  if (!current) return
  if (isRdp(current)) return openTabForAccount(current)
  const { tabId, terminalId, subIndex } = allocateTerminalId(current.accountId, terminalTabs.value, { forceNew: true })
  const baseLabel = current.account ? terminalTabLabel(current.account) : (current.label || '终端')
  const label = `${baseLabel} #${subIndex}`
  const tab = {
    ...current,
    tabId,
    terminalId,
    label,
    subIndex,
    customTitle: '',
    token: '',
    encoding: current.encoding || terminalEncoding.value
  }
  terminalTabs.value.push(tab)
  activeTerminalId.value = tab.tabId
  sessionToken.value = ''
  persistTerminalTabs()
  nextTick(scrollToActiveTab)
}

async function activateTerminalTab(tabId, shouldFocus = false) {
  const current = ++detailRequest
  activeTerminalId.value = tabId
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  sessionToken.value = tab?.token || ''
  if (tab?.account) {
    const protocol = isRdp(tab) ? 'RDP' : 'SSH'
    detail.value = { type: protocol === 'RDP' ? 'RDP_ACCOUNT' : 'ACCOUNT', raw: tab.account }
    await loadAccountHostMeta(tab.hostId)
    if (current !== detailRequest) return
    const id = protocol === 'RDP' ? tab.connectionId : tab.accountId
    const node = { nodeType: protocol === 'RDP' ? 'RDP_ACCOUNT' : 'ACCOUNT', id, key: `${protocol === 'RDP' ? 'r' : 'a'}:${id}`, label: tab.account.name || tab.account.username, leaf: true, extra: { hostId: tab.hostId, protocol } }
    selectedNode.value = node
    treeCurrentNodeKey.value = node.key
    await persistAccountSelection(node)
  }
  persistTerminalTabs()
  nextTick(() => {
    scrollToActiveTab()
    if (shouldFocus) {
      focusConnectionTab(tabId)
    }
  })
}

function closeTerminalTab(tabId) {
  detailRequest++
  const index = terminalTabs.value.findIndex(item => item.tabId === tabId)
  if (index < 0) return
  const wasActive = activeTerminalId.value === tabId
  terminalTabs.value.splice(index, 1)
  if (wasActive) {
    const next = terminalTabs.value[Math.min(index, terminalTabs.value.length - 1)]
    activeTerminalId.value = next?.tabId ?? null
    sessionToken.value = next?.token || ''
    if (next) {
      detail.value = { type: isRdp(next) ? 'RDP_ACCOUNT' : 'ACCOUNT', raw: next.account }
      void loadAccountHostMeta(next.hostId)
    }
  }
  persistTerminalTabs()
}

function onTerminalConnecting(tabId) {
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  if (!tab) return
  tab.manuallyDisconnected = false
  tab.connectionState = 'connecting'
}

function onTerminalReady(tabId, payload) {
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  if (!tab) return
  tab.manuallyDisconnected = false
  tab.connectionState = 'connected'
  tab.token = payload?.sessionToken || ''
  tab.connectionMode = payload?.connectionMode || ''
  tab.tmuxVersion = payload?.tmuxVersion || ''
  tab.tmuxVersionSource = payload?.tmuxVersionSource || 'unknown'
  if (activeTerminalId.value === tabId) sessionToken.value = tab.token
}

function onTerminalDisconnected(tabId, payload) {
  const tab = terminalTabs.value.find(item => item.tabId === tabId)
  if (tab) tab.connectionState = 'disconnected'
  if (tab && payload?.manual) tab.manuallyDisconnected = true
  if (tab) { tab.token = ''; tab.connectionMode = ''; tab.tmuxVersion = ''; tab.tmuxVersionSource = 'unknown' }
  if (activeTerminalId.value === tabId) sessionToken.value = ''
}

function onMonitorSessionInvalid(token) {
  for (const tab of terminalTabs.value) {
    if (tab.token === token) { tab.token = ''; tab.connectionMode = ''; tab.tmuxVersion = ''; tab.tmuxVersionSource = 'unknown' }
  }
  if (sessionToken.value === token) sessionToken.value = ''
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
  if (detail.value.type !== 'ACCOUNT' || activeIsRdp.value) return
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
  if (isAccountNode(data)) {
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
  if (command === 'subgroup') return openAddGroup(data.id)
  if (command === 'host') {
    if (data.nodeType === 'GROUP') return openAddHost(data.id)
    if (data.nodeType === 'HOST') return openAddHost(data.extra?.groupId)
    if (isAccountNode(data)) {
      const gId = data.extra?.groupId || accountHostMeta.value?.groupId || null
      return openAddHost(gId)
    }
    return openAddHost(null)
  }
  if (command === 'account') {
    if (data.nodeType === 'HOST') return openAddAccount(data.id)
    if (isAccountNode(data)) return openAddAccount(data.extra?.hostId)
    return openAddAccount(null)
  }
  if (command === 'delete') {
    loadDetail(data)
    nextTick(() => confirmDeleteDetail())
  }
}

async function openNewAccountTerminal(data) {
  const current = ++detailRequest
  try {
    if (isRdp(data)) {
      const account = { ...await getRdpConnection(data.id), protocol: 'RDP' }
      if (current !== detailRequest) return
      const host = await loadAccountHostMeta(account.hostId)
      if (current !== detailRequest) return
      activateRdpForAccount(account, host, true)
      detail.value = { type: 'RDP_ACCOUNT', raw: account }
      await persistAccountSelection(data, host.groupId)
      if (current === detailRequest) await router.push('/terminal')
      return
    }
    const account = await getAccount(data.id)
    if (current !== detailRequest) return
    const host = await loadAccountHostMeta(account.hostId)
    if (current !== detailRequest) return
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
      sftpPath: '',
      label,
      customTitle: '',
      subIndex,
      token: '',
      encoding: terminalEncoding.value
    }
    terminalTabs.value.push(tab)
    detail.value = { type: 'ACCOUNT', raw: account }
    activeTerminalId.value = tab.tabId
    sessionToken.value = ''
    terminalVisited.value = true
    persistTerminalTabs()
    nextTick(scrollToActiveTab)
    await router.push('/terminal')
    await persistAccountSelection(data)
  } catch (error) {
    if (current === detailRequest) ElMessage.error(isRdp(data) ? '远程桌面账户加载失败' : error?.message || '新建终端失败')
  }
}

function onAddCommand(cmd) {
  if (cmd === 'group') openAddGroup(null)
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

async function refreshHostOptions() {
  try {
    const list = []
    async function walk(parentType, parentId, groupPath = '') {
      const children = await fetchTreeChildren(parentType, parentId)
      for (const n of children || []) {
        if (n.nodeType === 'GROUP') {
          const curPath = groupPath ? `${groupPath} / ${n.label}` : n.label
          await walk('GROUP', n.id, curPath)
        } else if (n.nodeType === 'HOST') {
          list.push({
            id: n.id,
            name: n.label,
            host: n.extra?.host || '',
            port: n.extra?.port || 22,
            groupId: parentId,
            groupPath: groupPath || '默认分组',
            label: `${n.label} (${n.extra?.host || ''}:${n.extra?.port || 22})`
          })
        }
      }
    }
    await walk('ROOT')
    hostOptions.value = list
    return list
  } catch {
    hostOptions.value = []
    return []
  }
}

async function openAddGroup(defaultParentId = null) {
  await refreshGroupParentOptions(null)
  groupForm.value = { parentId: defaultParentId, name: '', remark: '', sortOrder: 0 }
  groupDlg.value = true
}

async function editGroupNode(data) {
  const g = await getGroup(data.id)
  await refreshGroupParentOptions(g.id)
  groupForm.value = { ...g }
  groupDlg.value = true
}

async function openAddHost(groupId = null) {
  await refreshGroupParentOptions(null)
  let defaultGroupId =
    groupId != null
      ? groupId
      : (selectedGroupId.value || null)
  if (!defaultGroupId && isAccountNode(selectedNode.value)) {
    const hostId = selectedNode.value.extra?.hostId || detail.value.raw?.hostId
    if (hostId) {
      try {
        const h = await getHost(hostId)
        defaultGroupId = h?.groupId ?? null
      } catch {
        // ignore
      }
    }
  }
  hostForm.value = {
    groupId: defaultGroupId,
    name: '',
    host: '',
    port: 22,
    remark: '',
    sortOrder: 0,
    enabled: 1
  }
  hostDlg.value = true
}

async function editHostNode(data) {
  await refreshGroupParentOptions(null)
  const h = await getHost(data.id)
  hostForm.value = { ...h, port: h.port ?? 22 }
  hostDlg.value = true
}

async function openAddAccount(hostId = null) {
  await refreshHostOptions()
  accountForm.value = { protocol: 'SSH', hostId: hostId ?? selectedHostId.value ?? (isAccountNode(selectedNode.value) ? selectedNode.value.extra?.hostId : null) }
  accountDlg.value = true
}

async function editAccountNode(data) {
  await refreshHostOptions()
  accountForm.value = { id: data.id, protocol: isRdp(data) ? 'RDP' : 'SSH', hostId: data.extra?.hostId }
  accountDlg.value = true
}

async function editRdpTab(tab) {
  return editAccountNode({ nodeType: 'RDP_ACCOUNT', id: tab.connectionId, extra: { hostId: tab.hostId } })
}

async function syncRdpTabs(account) {
  const tabs = terminalTabs.value.filter(tab => matchesConnection(tab, account.id, 'RDP'))
  if (!tabs.length) return
  const host = await getHost(account.hostId)
  for (const tab of tabs) {
    terminalRefs.get(tab.tabId)?.disconnect?.()
    tab.account = { ...account, protocol: 'RDP' }; tab.hostId = account.hostId
    tab.hostName = host.name; tab.hostAddress = host.host
    tab.label = `${host.name || host.host} · ${account.name || account.username}`
  }
  await nextTick()
  if (account.enabled !== 0 && host.enabled !== 0) for (const tab of tabs) terminalRefs.get(tab.tabId)?.reconnect?.()
  persistTerminalTabs()
}

async function onAccountSaved({ protocol, account }) {
  if (protocol === 'RDP') await syncRdpTabs(account)
  const node = { nodeType: protocol === 'RDP' ? 'RDP_ACCOUNT' : 'ACCOUNT', id: account.id, key: `${protocol === 'RDP' ? 'r' : 'a'}:${account.id}`, label: account.name || account.username, leaf: true, extra: { hostId: account.hostId, protocol, enabled: account.enabled } }
  await refreshTree(node)
}

function openEditFromDetail() {
  const n = selectedNode.value
  if (!n) return
  if (n.nodeType === 'GROUP') editGroupNode(n)
  else if (n.nodeType === 'HOST') editHostNode(n)
  else if (isAccountNode(n)) editAccountNode(n)
}

async function saveGroup() {
  await groupFormRef.value?.validate()
  saving.value = true
  try {
    const body = { ...groupForm.value }
    let saved
    if (body.id) saved = await updateGroup(body.id, body)
    else saved = await createGroup(body)
    groupDlg.value = false
    const node = {
      nodeType: 'GROUP',
      id: saved.id,
      key: `g:${saved.id}`,
      label: saved.name,
      remark: saved.remark,
      parentId: saved.parentId ?? null,
      leaf: false,
      extra: {}
    }
    await refreshTree(node)
  } finally {
    saving.value = false
  }
}

async function saveHost() {
  await hostFormRef.value?.validate()
  saving.value = true
  try {
    const body = { ...hostForm.value }
    if (body.host) body.host = String(body.host).trim()
    if (body.port) body.port = Number(body.port)
    let saved
    if (body.id) saved = await updateHost(body.id, body)
    else saved = await createHost(body)
    hostDlg.value = false
    const port = saved.port != null ? saved.port : 22
    const node = {
      nodeType: 'HOST',
      id: saved.id,
      key: `h:${saved.id}`,
      label: saved.name,
      remark: saved.remark,
      groupId: saved.groupId,
      leaf: false,
      extra: {
        endpoint: `${saved.host}:${port}`,
        host: saved.host,
        port,
        groupId: saved.groupId,
        enabled: saved.enabled
      }
    }
    await refreshTree(node)
  } finally {
    saving.value = false
  }
}

async function handleTestExistingAccount(data) {
  try {
    const result = isRdp(data) ? await testRdpConnection({ id: data.id }) : await testAccount(data.id)
    if (result?.success) ElMessage.success(result.message || '连接测试成功')
    else ElMessage.error(result?.message || '连接测试失败')
  } catch (error) { ElMessage.error(error.message || '连接测试失败') }
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
  const keep = []
  for (const tab of terminalTabs.value) {
    const removed = isAccountNode(n) ? tabMatchesNode(tab, n) : n.nodeType === 'HOST' ? tab.hostId === n.id : await isHostInsideGroup(tab.hostId, n.id)
    if (!removed) keep.push(tab)
  }
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
    if (saved?.id === n.id && saved.nodeType === n.nodeType) clearSavedSshAccount()
    if (isRdp(n)) await deleteRdpConnection(n.id); else await deleteAccount(n.id)
  }
  terminalTabs.value = keep
  if (!keep.some(tab => tab.tabId === activeTerminalId.value)) activeTerminalId.value = keep[0]?.tabId ?? null
  persistTerminalTabs()
  if (n.key) {
    defaultExpandedKeys.value = defaultExpandedKeys.value.filter((k) => k !== n.key)
  }
  detail.value = { type: null, raw: null }
  treeCurrentNodeKey.value = null
  selectedNode.value = null
  await refreshTree()
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
.connection-protocol { flex-shrink: 0; font-size: 10px; font-weight: 600; color: var(--el-text-color-secondary); padding: 1px 4px; border: 1px solid var(--el-border-color-lighter); border-radius: 3px; }
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
.aside.is-resizing {
  transition: none !important;
  user-select: none;
}
.sidebar-resizer {
  position: absolute;
  top: 0;
  right: 0;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 20;
  touch-action: none;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color var(--ws-transition-fast);
}
.sidebar-resizer:hover,
.sidebar-resizer:active,
.sidebar-resizer:focus-visible {
  background-color: var(--el-color-primary-light-8);
}
.sidebar-resizer-handle {
  width: 2px;
  height: 24px;
  border-radius: 1px;
  background-color: var(--ws-border);
  opacity: 0;
  transition: opacity var(--ws-transition-fast), background-color var(--ws-transition-fast);
}
.sidebar-resizer:hover .sidebar-resizer-handle,
.sidebar-resizer:active .sidebar-resizer-handle,
.sidebar-resizer:focus-visible .sidebar-resizer-handle {
  opacity: 1;
  background-color: var(--ws-accent);
}
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
  gap: 10px;
  padding: 10px 0;
  overflow-y: auto;
  overflow-x: hidden;
  max-height: 100%;
}
.mini-dock-item {
  width: 42px;
  height: 46px;
  border-radius: var(--ws-radius-md);
  background: var(--node-theme-bg, var(--ws-bg-card));
  border: 1.5px solid var(--node-theme-border, var(--ws-border));
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  padding: 4px 2px;
  position: relative;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
  user-select: none;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  box-sizing: border-box;
}
.mini-dock-item:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.12);
  border-color: var(--node-theme-color, var(--ws-accent));
}
.mini-dock-item.is-active {
  border-color: var(--node-theme-color, var(--ws-accent));
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.25), 0 2px 8px rgba(0, 0, 0, 0.1);
  background: var(--ws-bg-active, rgba(99, 102, 241, 0.1));
}
.mini-host-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--node-theme-color, var(--ws-text-primary));
  line-height: 1.2;
  max-width: 38px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mini-user-badge {
  font-size: 9px;
  font-family: var(--font-mono, monospace);
  font-weight: 600;
  padding: 1px 3px;
  border-radius: 4px;
  background: var(--ws-bg-input, rgba(0, 0, 0, 0.06));
  color: var(--ws-text-regular);
  line-height: 1;
  max-width: 38px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--ws-border);
}
.mini-user-badge.is-root {
  background: rgba(239, 68, 68, 0.15);
  color: var(--ws-color-danger, #ef4444);
  border-color: rgba(239, 68, 68, 0.35);
  font-weight: 700;
}
.mini-sub-badge {
  position: absolute;
  bottom: -3px;
  right: -3px;
  font-size: 8px;
  font-family: var(--font-mono, monospace);
  font-weight: 700;
  color: #fff;
  background: var(--ws-accent, #6366f1);
  border-radius: 3px;
  padding: 0 2px;
  line-height: 1.2;
}
.mini-status-dot {
  position: absolute;
  top: 3px;
  right: 3px;
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
  flex: 1;
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
.node-main-area {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  cursor: pointer;
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
  gap: 6px;
}
.node-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1 1 auto;
}
.node-label {
  display: block;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ws-text-primary);
  line-height: 1.4;
}
.node-label.is-ip-font {
  font-family: var(--font-mono);
  letter-spacing: -0.2px;
}
.node-sub {
  font-size: 11px;
  color: var(--ws-text-subtle);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  flex-shrink: 0;
  max-width: 55%;
  line-height: 1.4;
}
.node-sub.is-ip-badge {
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--ws-text-muted);
  background: var(--ws-bg-card);
  border: 1px solid var(--ws-border);
  padding: 0 4px;
  border-radius: var(--ws-radius-xs);
  line-height: 16px;
  letter-spacing: -0.2px;
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
  position: relative;
}
.snippet-slide-enter-active,
.snippet-slide-leave-active {
  transition: opacity 0.18s cubic-bezier(0.16, 1, 0.3, 1), transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}
.snippet-slide-enter-from,
.snippet-slide-leave-to {
  opacity: 0;
  transform: translateY(-6px);
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
  gap: 6px;
  transition: background-color var(--ws-transition-normal), border-color var(--ws-transition-normal);
}
.tab-scroll-arrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--ws-border);
  background: var(--ws-bg-input);
  color: var(--ws-text-muted);
  border-radius: var(--ws-radius-xs);
  cursor: pointer;
  flex-shrink: 0;
  transition: all var(--ws-transition-fast);
}
.tab-scroll-arrow:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
  border-color: var(--ws-border-hover);
}
.terminal-tab-dropdown {
  flex-shrink: 0;
}
.tab-dropdown-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 6px;
  border-radius: var(--ws-radius-xs);
  background: var(--ws-bg-input);
  border: 1px solid var(--ws-border);
  color: var(--ws-text-regular);
  font-size: 11px;
  cursor: pointer;
  transition: all var(--ws-transition-fast);
}
.tab-dropdown-btn:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
  border-color: var(--ws-border-hover);
}
.tab-count-badge {
  font-size: 10px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--ws-bg-card);
  color: var(--ws-text-muted);
  border: 1px solid var(--ws-border);
  line-height: 1.2;
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
  max-width: 180px;
  min-width: 70px;
  flex-shrink: 1;
  height: 30px;
  padding: 0 8px;
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
  min-width: 100px;
  flex-shrink: 0;
  background: var(--ws-bg-active-tab);
  color: var(--ws-text-primary);
  border-bottom: 2px solid var(--ws-accent);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  font-weight: 600;
}
.tab-inner {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
  flex: 1;
  min-width: 0;
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

/* Tooltip 卡片 */
.tab-tooltip-card {
  font-size: 12px;
  line-height: 1.4;
  padding: 2px;
  min-width: 160px;
}
.tab-tooltip-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.15);
  padding-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.user-badge {
  font-size: 10px;
  font-family: var(--font-mono, monospace);
  font-weight: 700;
  padding: 1px 4px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
  line-height: 1.2;
  display: inline-block;
}
.user-badge.is-root {
  background: #ef4444;
  color: #fff;
}
.text-danger {
  color: #ef4444 !important;
}
.tab-tooltip-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  margin-bottom: 3px;
}
.tab-tooltip-k {
  color: rgba(255, 255, 255, 0.65);
}
.tab-tooltip-v {
  color: #fff;
}
.tab-tooltip-v.is-online {
  color: #10b981;
}
.tab-tooltip-v.is-offline {
  color: #94a3b8;
}
.tab-tooltip-hint {
  margin-top: 6px;
  font-size: 10px;
  color: rgba(255, 255, 255, 0.45);
  text-align: right;
  border-top: 1px dashed rgba(255, 255, 255, 0.1);
  padding-top: 3px;
}

:global(.terminal-tab-popper.el-popper) {
  background: var(--ws-bg-card, #0f1523) !important;
  color: var(--ws-text-primary, #f8fafc) !important;
  border: 1px solid rgba(255, 255, 255, 0.14) !important;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.7) !important;
  border-radius: 8px !important;
  padding: 8px 10px !important;
}

:global(.terminal-tab-popper.el-popper .el-popper__arrow::before) {
  background: var(--ws-bg-card, #0f1523) !important;
  border: 1px solid rgba(255, 255, 255, 0.14) !important;
}

/* 下拉终端列表 */
.terminal-tabs-dropdown-menu {
  min-width: 260px;
  max-width: 380px;
  padding: 4px 0;
}
.dropdown-header {
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ws-text-subtle);
  border-bottom: 1px solid var(--ws-border);
}
.dropdown-tabs-list {
  max-height: 280px;
  overflow-y: auto;
}
.dropdown-tab-item {
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  gap: 8px;
  padding: 6px 12px !important;
}
.dropdown-tab-item.is-active {
  background: var(--ws-accent-bg);
  color: var(--ws-accent);
}
.dropdown-item-left {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  flex: 1;
}
.dropdown-status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ws-text-muted);
  flex-shrink: 0;
}
.dropdown-status-dot.is-connected {
  background: var(--ws-color-online);
}
.dropdown-tab-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.dropdown-tab-ip {
  font-size: 10px;
  color: var(--ws-text-muted);
  margin-left: 4px;
}
.dropdown-item-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.dropdown-shortcut-badge {
  font-size: 10px;
  color: var(--ws-text-muted);
  background: var(--ws-bg-card);
  padding: 1px 5px;
  border-radius: 3px;
  border: 1px solid var(--ws-border);
  line-height: 1.2;
}
.active-check {
  color: var(--ws-accent);
  font-size: 12px;
}
.dropdown-close-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 2px;
  color: var(--ws-text-muted);
  display: inline-flex;
  align-items: center;
  border-radius: 3px;
}
.dropdown-close-btn:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-color-danger);
}
.dropdown-footer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  padding: 6px 12px;
  border-top: 1px solid var(--ws-border);
}
.footer-btn {
  background: transparent;
  border: 1px solid var(--ws-border);
  color: var(--ws-text-regular);
  font-size: 11px;
  padding: 2px 8px;
  border-radius: var(--ws-radius-xs);
  cursor: pointer;
}
.footer-btn:hover:not(:disabled) {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
}
.footer-btn.danger:hover {
  background: var(--ws-bg-hover);
  color: var(--ws-color-danger);
  border-color: var(--ws-color-danger);
}
.footer-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 右键上下文菜单 */
.tab-context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 150px;
  padding: 4px 0;
  background: var(--ws-bg-card);
  border: 1px solid var(--ws-border);
  border-radius: var(--ws-radius-sm);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(10px);
}
.context-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--ws-text-regular);
  cursor: pointer;
  transition: all var(--ws-transition-fast);
  user-select: none;
}
.context-menu-item:hover:not(.is-disabled) {
  background: var(--ws-bg-hover);
  color: var(--ws-text-primary);
}
.context-menu-item.danger:hover {
  color: var(--ws-color-danger);
}
.context-menu-item.is-disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.context-menu-divider {
  height: 1px;
  background: var(--ws-border);
  margin: 4px 0;
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
.split-close-btn:hover:not(:disabled) {
  color: var(--ws-color-danger);
}
.split-close-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
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

/* 主机下拉选择项 */
.ssh-host-select-popper .el-select-dropdown__item {
  height: auto !important;
  line-height: normal !important;
  padding: 8px 12px !important;
}
.host-select-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}
.host-select-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.host-select-name {
  font-weight: 500;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}
.host-select-addr {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.host-select-group {
  font-size: 11px;
  flex-shrink: 0;
  margin-left: auto;
}

/* 树节点富文本提示悬浮卡片 */
.tree-node-tooltip {
  max-width: 320px !important;
  padding: 8px 12px !important;
  border-radius: var(--ws-radius-md, 8px) !important;
  color: var(--el-text-color-primary);
  box-shadow: var(--el-box-shadow) !important;
}
.tree-tip-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
}
.tree-tip-header {
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding-bottom: 6px;
  margin-bottom: 2px;
}
.tree-tip-tag {
  flex-shrink: 0;
}
.tree-tip-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-tip-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.tree-tip-row {
  display: flex;
  align-items: center;
  gap: 6px;
  line-height: 20px;
}
.tree-tip-label {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  font-size: 11px;
}
.tree-tip-value {
  color: var(--el-text-color-primary);
  word-break: break-all;
  font-size: 12px;
}
.tree-tip-copy-btn {
  padding: 2px 4px !important;
  height: 20px !important;
  margin-left: 2px;
}
</style>
