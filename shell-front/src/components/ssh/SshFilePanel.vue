<template>
  <div
    class="ssh-file-panel"
    :class="{ embedded, 'is-dragover': isDragOver }"
    @dragenter="handleDragEnter"
    @dragover.prevent="handleDragOver"
    @dragleave="handleDragLeave"
    @drop.prevent="handleDrop"
  >
    <!-- 拖拽上传覆盖层 -->
    <div v-if="isDragOver" class="drop-upload-overlay">
      <el-icon class="drop-upload-ico"><Upload /></el-icon>
      <div class="drop-upload-text">松开鼠标立即上传到当前目录</div>
      <div class="drop-upload-path font-mono-code">{{ currentPath || pathInput || '当前目录' }}</div>
    </div>

    <div v-if="!sessionToken" class="no-session">
      <el-empty description="尚未建立 SSH 会话">
        <el-button type="primary" :loading="openingSession" @click="$emit('request-session')">
          建立文件会话
        </el-button>
      </el-empty>
      <p class="hint">也可先在「终端」Tab 连接，连接成功后会自动复用 sessionToken。</p>
      <el-popover
        trigger="hover"
        :width="350"
        placement="bottom-end"
        :disabled="activeTransferCount === 0"
        popper-class="transfer-hover-popper"
        :show-after="200"
        :hide-after="150"
      >
        <template #reference>
          <el-button size="small" :type="activeTransferCount ? 'primary' : 'default'" plain :title="activeTransferCount ? '' : transferButtonTitle" @click="showTransfers = true">{{ transferButtonLabel }}</el-button>
        </template>
        <TransferHoverCard
          :transfers="activeTransfers"
          :overall-progress="activeOverallProgress"
          @open-list="showTransfers = true"
        />
      </el-popover>
    </div>
    <template v-else>
      <!-- 终端路径协同提示条 (在有提示信息或两端路径不一致时提示) -->
      <div v-if="syncNotice || contextMessage || autoNavigationError" class="terminal-sync-notice" role="status">
        <div class="notice-content">
          <el-icon class="notice-icon"><Connection /></el-icon>
          <span class="notice-text">{{ syncNotice || contextMessage || autoNavigationError }}</span>
        </div>
        <button v-if="canJumpTerminalCwd" type="button" class="notice-jump-btn" @click="jumpToTerminalCwd">
          立即对齐终端目录 →
        </button>
      </div>

      <div class="sftp-toolbar">
        <!-- 左侧：导航按钮组 + 一体化智能地址栏 -->
        <div class="toolbar-nav-group">
          <!-- 导航与终端联动按钮簇 -->
          <div class="nav-btn-cluster">
            <button
              type="button"
              class="nav-icon-btn"
              :disabled="!canGoParent"
              title="返回上一级 (Alt + ↑)"
              @click="goParent"
            >
              <el-icon><ArrowLeft /></el-icon>
            </button>
            <button
              type="button"
              class="nav-icon-btn"
              title="前往主目录 (~)"
              @click="navigateToQuickPath('~')"
            >
              <el-icon><House /></el-icon>
            </button>
            <button
              type="button"
              class="nav-icon-btn"
              :class="{ 'is-loading': loading }"
              title="刷新目录 (F5)"
              @click="refresh"
            >
              <el-icon><Refresh /></el-icon>
            </button>
            <span class="nav-cluster-divider" />
            <!-- 终端联动快捷控制组 -->
            <button
              type="button"
              class="nav-icon-btn sync-jump-btn"
              :disabled="!terminalContext?.cwd"
              :title="terminalContext?.cwd ? `对齐终端：跳转至 ${terminalContext.cwd}` : '暂无终端目录信息'"
              @click="jumpToTerminalCwd"
            >
              <el-icon><Aim /></el-icon>
            </button>
            <button
              type="button"
              class="nav-icon-btn sync-toggle-btn"
              :class="{ 'is-active': followTerminal }"
              :title="followTerminal ? '终端联动：已开启（终端切换目录时自动跟随）' : '终端联动：已关闭（点击开启）'"
              @click="followTerminal = !followTerminal"
            >
              <el-icon><Link /></el-icon>
            </button>
          </div>

          <!-- 一体化智能地址栏 -->
          <div class="smart-address-bar" :class="{ 'is-editing': isEditingPath }">
            <!-- 浏览态：面包屑与快捷书签 -->
            <div v-if="!isEditingPath" class="address-breadcrumb-wrap" @click="enterPathEditMode">
              <span class="breadcrumb-root" title="前往根目录" @click.stop="goRoot">/</span>
              <template v-for="(seg, idx) in breadcrumbs" :key="idx">
                <span class="breadcrumb-separator">/</span>
                <span
                  class="breadcrumb-seg"
                  :class="{ 'is-last': idx === breadcrumbs.length - 1 }"
                  @click.stop="goToIndex(idx)"
                >
                  {{ seg }}
                </span>
              </template>
            </div>

            <!-- 编辑态：路径输入框 -->
            <div v-else class="address-input-wrap">
              <input
                ref="pathInputRef"
                v-model="pathInput"
                class="address-text-input font-mono-code"
                placeholder="输入绝对路径 (回车前往)..."
                @keyup.enter="handlePathInputEnter"
                @keyup.esc="exitPathEditMode"
              />
              <button type="button" class="address-action-btn confirm" @click="handlePathInputEnter">前往</button>
              <button type="button" class="address-action-btn cancel" @click="exitPathEditMode">取消</button>
            </div>

            <!-- 地址栏右侧操作：常用书签下拉 + 编辑切换图标 -->
            <div class="address-bar-actions">
              <el-dropdown trigger="click" @command="navigateToQuickPath">
                <button type="button" class="address-pill-btn" title="常用系统路径快捷跳转" @click.stop>
                  <el-icon><Star /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="~">~ 家目录</el-dropdown-item>
                    <el-dropdown-item command="/">/ 根目录</el-dropdown-item>
                    <el-dropdown-item divided command="/var/log">/var/log 日志目录</el-dropdown-item>
                    <el-dropdown-item command="/etc">/etc 配置文件</el-dropdown-item>
                    <el-dropdown-item command="/tmp">/tmp 临时缓存</el-dropdown-item>
                    <el-dropdown-item command="/opt">/opt 可选软件包</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>

              <button
                v-if="!isEditingPath"
                type="button"
                class="address-pill-btn edit-btn"
                title="手动输入路径 (Ctrl+L)"
                @click.stop="enterPathEditMode"
              >
                <el-icon><Edit /></el-icon>
              </button>
            </div>
          </div>
        </div>

        <!-- 右侧：身份与权限协同 + 过滤 + 排序 + 传输 + 新建 + 上传 -->
        <div class="toolbar-action-group">
          <!-- 用户身份与权限协同卡片 -->
          <el-popover trigger="click" :width="290" placement="bottom-end">
            <template #reference>
              <button
                type="button"
                class="user-context-pill"
                :title="`文件身份: ${terminalContext?.fileUser || 'root'} · 终端用户: ${terminalContext?.terminalUser || '未知'}`"
              >
                <el-icon class="user-pill-ico"><User /></el-icon>
                <span class="user-pill-name font-mono-code">{{ terminalContext?.fileUser || 'root' }}</span>
                <span class="user-pill-arrow">→ {{ terminalContext?.terminalUser || 'nodear' }}</span>
                <el-icon class="user-pill-down"><ArrowDown /></el-icon>
              </button>
            </template>
            <div class="user-popover-card">
              <div class="popover-title">权限协同与归属配置</div>
              <div class="popover-row">
                <span class="popover-k">SFTP 会话身份:</span>
                <span class="popover-v font-mono-code">{{ terminalContext?.fileUser || '待检测' }}</span>
              </div>
              <div class="popover-row">
                <span class="popover-k">终端登录用户:</span>
                <span class="popover-v font-mono-code">{{ terminalContext?.terminalUser || '未知' }}</span>
              </div>
              <div class="popover-divider" />
              <el-checkbox v-model="assignUploadOwner" :disabled="!canAssignOwner" class="upload-owner-checkbox">
                上传后归属终端用户
              </el-checkbox>
              <div class="popover-hint">
                上传文件完成后自动执行 chown 归属于终端用户，避免以 root 权限上传导致普通用户无法写入。
              </div>
            </div>
          </el-popover>

          <!-- 搜索过滤 -->
          <el-input
            v-model="filterKeyword"
            size="small"
            class="filter-input"
            placeholder="过滤文件..."
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>

          <!-- 排序下拉 -->
          <el-dropdown trigger="click" @command="handleSortCommand">
            <el-button size="small" class="sort-btn" :title="`当前排序: ${currentSortLabel}`">
              <el-icon class="el-icon--left"><Sort /></el-icon>
              <span>{{ currentSortLabel }}</span>
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="name:ascending" :class="{ 'is-active': sortProp === 'name' && sortOrder === 'ascending' }">文件名（A → Z 顺序）</el-dropdown-item>
                <el-dropdown-item command="name:descending" :class="{ 'is-active': sortProp === 'name' && sortOrder === 'descending' }">文件名（Z → A 倒序）</el-dropdown-item>
                <el-dropdown-item divided command="mtime:descending" :class="{ 'is-active': sortProp === 'mtime' && sortOrder === 'descending' }">修改时间（最新在前 倒序）</el-dropdown-item>
                <el-dropdown-item command="mtime:ascending" :class="{ 'is-active': sortProp === 'mtime' && sortOrder === 'ascending' }">修改时间（最早在前 顺序）</el-dropdown-item>
                <el-dropdown-item divided command="createTime:descending" :class="{ 'is-active': sortProp === 'createTime' && sortOrder === 'descending' }">新增时间（最新在前 倒序）</el-dropdown-item>
                <el-dropdown-item command="createTime:ascending" :class="{ 'is-active': sortProp === 'createTime' && sortOrder === 'ascending' }">新增时间（最早在前 顺序）</el-dropdown-item>
                <el-dropdown-item divided command="size:descending" :class="{ 'is-active': sortProp === 'size' && sortOrder === 'descending' }">大小（由大到小 倒序）</el-dropdown-item>
                <el-dropdown-item command="size:ascending" :class="{ 'is-active': sortProp === 'size' && sortOrder === 'ascending' }">大小（由小到大 顺序）</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <!-- 传输队列胶囊 -->
          <el-popover
            trigger="hover"
            :width="350"
            placement="bottom-end"
            :disabled="activeTransferCount === 0"
            popper-class="transfer-hover-popper"
            :show-after="200"
            :hide-after="150"
          >
            <template #reference>
              <el-button
                size="small"
                :type="activeTransferCount ? 'warning' : 'default'"
                :plain="!activeTransferCount"
                class="transfer-badge-btn"
                :title="activeTransferCount ? '' : transferButtonTitle"
                @click="showTransfers = true"
              >
                <el-icon v-if="activeTransferCount" class="is-loading"><Refresh /></el-icon>
                <span>{{ transferButtonLabel }}</span>
              </el-button>
            </template>
            <TransferHoverCard
              :transfers="activeTransfers"
              :overall-progress="activeOverallProgress"
              @open-list="showTransfers = true"
            />
          </el-popover>

          <!-- 详细信息切换 -->
          <el-button v-if="embedded" size="small" :aria-pressed="showDetails" @click="showDetails = !showDetails">
            {{ showDetails ? '精简' : '详细' }}
          </el-button>

          <!-- 新建目录 -->
          <el-button size="small" type="primary" plain @click="showMkdir = true">
            <el-icon class="el-icon--left"><FolderAdd /></el-icon>
            <span>新建目录</span>
          </el-button>

          <!-- 上传文件 & 文件夹 -->
          <input ref="uploadInput" type="file" hidden multiple @change="uploadFiles" />
          <el-button size="small" type="primary" @click="uploadInput.click()">
            <el-icon class="el-icon--left"><Upload /></el-icon>
            <span>上传文件</span>
          </el-button>
          <FolderTransfer
            ref="folderTransfer"
            :session-token="sessionToken"
            :path="loadedPath || currentPath"
            :owner-uid="uploadOwnership?.ownerUid"
            :owner-gid="uploadOwnership?.ownerGid"
            :ownership-requested="assignUploadOwner"
            :ownership-available="canAssignOwner"
            @uploaded="onFolderUploaded"
          />
        </div>
      </div>

      <div class="file-table-container">

        <SshVirtualTable
          :items="sortedItems"
          :loading="loading"
          :embedded="embedded"
          :show-details="showDetails"
          :sort-prop="sortProp"
          :sort-order="sortOrder"
          :current-path="loadedPath || currentPath"
          :empty-description="filterKeyword ? '未匹配到相关文件' : '当前目录为空'"
          :can-chown="canChown"
          @sort-change="onSortChange"
          @row-dblclick="onRowDblClick"
          @edit-file="openFileEditor"
          @force-edit="openFullEditor"
          @view-standalone="openStandaloneViewer"
          @download="downloadRow"
          @open="enterDir"
          @folder-download="folderTransfer?.openDownload($event)"
          @rename="openRename"
          @chown="openChown"
          @delete="confirmDelete"
        />
      </div>
    </template>

    <el-dialog v-model="showTransfers" title="文件传输 · 当前服务器" width="min(1080px, 94vw)" top="8vh" append-to-body destroy-on-close>
      <p v-if="transferHistoryWarning" role="alert" class="transfer-error">{{ transferHistoryWarning }}</p>
      <div class="transfer-heading">
        <strong>共 {{ visibleTransfers.length }} 项<span v-if="activeTransferCount"> · {{ activeTransferCount }} 项进行中 ({{ activeTransferPercent }}%)</span></strong>
        <el-button text size="small" @click="clearVisibleTransfers">清除已结束</el-button>
      </div>
      <el-progress
        v-if="activeTransferCount"
        :percentage="activeTransferPercent"
        :stroke-width="5"
        style="margin: 4px 0 10px;"
      />
      <p class="transfer-hint">关闭弹窗不影响传输。{{ transferNotice }}</p>
      <el-table :data="visibleTransfers" size="small" max-height="55vh" empty-text="当前服务器暂无传输记录">
        <el-table-column prop="direction" label="方向" width="65" />
        <el-table-column label="账户" width="80"><template #default="{row}">#{{ row.accountId }}</template></el-table-column>
        <el-table-column prop="name" label="文件" min-width="150" show-overflow-tooltip />
        <el-table-column prop="path" label="远程目录" min-width="140" show-overflow-tooltip />
        <el-table-column label="进度" min-width="180"><template #default="{row}">
          <span v-if="row.restored" class="transfer-hint">历史记录 · 最后记录进度</span>
          <template v-if="!['已交给浏览器','交给浏览器中'].includes(row.status)">
            <el-progress :percentage="row.status === '已完成' ? 100 : Math.min(99, Math.floor(row.loaded / Math.max(row.total,1) * 100))" :status="['失败','无法恢复'].includes(row.status) ? 'exception' : row.status === '已完成' ? 'success' : undefined" />
            <span>{{ formatSize(row.loaded) }} / {{ formatSize(row.total) }}<template v-if="row.status === '传输中'"> · {{ formatSize(row.speed) }}/s</template></span>
          </template><span v-else>请在浏览器下载列表查看</span>
        </template></el-table-column>
        <el-table-column label="状态" min-width="130"><template #default="{row}">{{ row.status }}<span v-if="row.error" class="transfer-error">{{ row.error }}</span></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{row}"><el-button v-if="['等待中','传输中'].includes(row.status)" link type="danger" @click="row.cancel()">取消</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="showTransfers = false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="showMkdir" title="新建目录" width="400px" destroy-on-close @closed="mkdirName = ''"><el-scrollbar max-height="65vh">
      <el-input v-model="mkdirName" placeholder="目录名" maxlength="255" @keyup.enter="doMkdir" />
      </el-scrollbar><template #footer>
        <el-button @click="showMkdir = false">取消</el-button>
        <el-button type="primary" :loading="mkdirLoading" @click="doMkdir">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showRename" title="重命名" width="400px" destroy-on-close @closed="resetRename"><el-scrollbar max-height="65vh">
      <el-input v-model="renameName" placeholder="新名称" maxlength="255" @keyup.enter="doRename" />
      </el-scrollbar><template #footer>
        <el-button @click="showRename = false">取消</el-button>
        <el-button type="primary" :loading="renameLoading" @click="doRename">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="showChown"
      title="修改归属用户"
      width="440px"
      destroy-on-close
      @closed="resetChown"
    >
      <el-form label-position="top" size="default">
        <el-form-item label="目标对象">
          <div class="chown-target-preview font-mono-code">
            <el-icon class="mr-1"><component :is="chownTarget?.dir ? Folder : Document" /></el-icon>
            <span class="chown-target-name" :title="chownTarget?.name">{{ chownTarget?.name }}</span>
          </div>
        </el-form-item>

        <el-form-item label="归属用户 (所有者)">
          <el-select
            v-model="chownOwner"
            filterable
            allow-create
            default-first-option
            placeholder="请选择或输入用户名 (如 root, www-data)"
            style="width: 100%"
          >
            <el-option
              v-for="u in systemUsers"
              :key="u"
              :label="u"
              :value="u"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="归属用户组 (可选)">
          <el-select
            v-model="chownGroup"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="请选择或输入用户组名 (如 root, www-data)"
            style="width: 100%"
          >
            <el-option
              v-for="g in systemGroups"
              :key="g"
              :label="g"
              :value="g"
            />
          </el-select>
        </el-form-item>

        <el-form-item v-if="chownTarget?.dir">
          <el-checkbox v-model="chownRecursive">
            同时递归应用到所有子文件和子目录 (-R)
          </el-checkbox>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="showChown = false">取消</el-button>
        <el-button type="primary" :loading="chownLoading" @click="doChown">确定</el-button>
      </template>
    </el-dialog>

    <SshFileEditorDialog
      v-model="showEditor"
      :file="editorFile"
      :dir="loadedPath || currentPath || defaultCwd || '/'"
      :session-token="sessionToken"
      :account-id="accountId"
      @saved="refresh"
      @switch-to-stream="openLargeFileViewer"
    />

    <SshLargeFileViewerDialog
      v-model="showLargeViewer"
      :file="largeViewerFile"
      :dir="loadedPath || currentPath || defaultCwd || '/'"
      :session-token="sessionToken"
      :account-id="accountId"
      @switch-to-editor="openFullEditor"
    />
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onScopeDispose, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Aim,
  ArrowDown,
  ArrowLeft,
  Connection,
  Document,
  Edit,
  Folder,
  FolderAdd,
  House,
  Link,
  Refresh,
  Search,
  Sort,
  Star,
  Upload,
  User
} from '@element-plus/icons-vue'
import {
  getTerminalContext,
  sftpChown,
  sftpDelete,
  sftpDownloadLarge,
  sftpGetUsersAndGroups,
  sftpList,
  sftpMkdir,
  sftpRename,
  sftpUploadLarge
} from '@/api/sshConsole'
import { useTerminalFileContext } from '@/composables/useTerminalFileContext'
import { useTransfers } from '@/composables/useTransfers'
import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'
import { isTransferActive, transferErrorMessage, transfersForServer } from '@/composables/transferHistory'
import FolderTransfer from './FolderTransfer.vue'
import SshVirtualTable from './SshVirtualTable.vue'
import SshFileEditorDialog from './SshFileEditorDialog.vue'
import SshLargeFileViewerDialog from './SshLargeFileViewerDialog.vue'
import { isBinaryFile } from '@/utils/fileTypeHelper'
import { sortFileList } from '@/utils/fileSorter'
import { formatSize } from '@/utils/transferFormat'
import TransferHoverCard from './TransferHoverCard.vue'
import { useAiChanges } from '@/composables/useAiChanges'
useAiChanges(/^(file_|ssh_command)/, () => { if (props.sessionToken) return refresh() })

const props = defineProps({
  accountId: { type: Number, default: null },
  tabId: { type: String, default: '' },
  savedPath: { type: String, default: '' },
  hostId: { type: Number, default: null },
  active: { type: Boolean, default: true },
  embedded: { type: Boolean, default: false },
  sessionToken: { type: String, default: '' },
  defaultCwd: { type: String, default: '' },
  openingSession: { type: Boolean, default: false }
})
const { transfers, transferHistoryWarning, enqueueTransfer, delegateBrowserDownload, clearFinishedTransfers, assignTransferHost } = useTransfers()
const { fileLocation, fileSearchContext } = storeToRefs(useWorkspaceStore())
const transferNotice = '切换页面可继续传输。刷新或关闭页面会中断页面内传输，重开后仅保留最近 200 条历史记录和最后进度，无法自动续传。SSH 断开后需重新发起。浏览器接管的下载请在浏览器下载列表查看。'

const emit = defineEmits(['request-session', 'session-invalid', 'path-change'])

watch(() => [props.accountId, props.hostId], ([accountId, hostId]) => {
  // Old records predate host metadata; assign only when the account's actual server is known.
  assignTransferHost(accountId, hostId)
}, { immediate: true })
const visibleTransfers = computed(() => transfersForServer(transfers?.value || transfers, props.hostId))
const showTransfers = ref(false)
const activeTransfers = computed(() => visibleTransfers.value.filter(isTransferActive))
const activeTransferCount = computed(() => activeTransfers.value.length)

let activeBatchStart = null
watch(activeTransferCount, (count) => {
  if (count > 0) {
    if (activeBatchStart === null) {
      const earliest = Math.min(...activeTransfers.value.map(t => t.createdAt || Date.now()))
      activeBatchStart = Number.isFinite(earliest) ? earliest : Date.now()
    }
  } else {
    activeBatchStart = null
  }
}, { immediate: true })

const activeOverallProgress = computed(() => {
  if (activeTransferCount.value <= 0) {
    return { loaded: 0, total: 0, percent: 0 }
  }

  const batchTasks = visibleTransfers.value.filter(task => {
    if (isTransferActive(task)) return true
    if (activeBatchStart !== null && task.status === '已完成') {
      const time = task.updatedAt || task.createdAt || 0
      return time >= activeBatchStart
    }
    return false
  })

  let totalLoaded = 0
  let totalSize = 0
  for (const t of batchTasks) {
    const tTotal = Math.max(0, Number(t.total) || 0)
    const tLoaded = t.status === '已完成'
      ? tTotal
      : Math.min(tTotal > 0 ? tTotal : Infinity, Math.max(0, Number(t.loaded) || 0))
    totalLoaded += tLoaded
    totalSize += tTotal
  }

  const percent = totalSize > 0
    ? Math.min(99, Math.floor((totalLoaded / totalSize) * 100))
    : 0

  return {
    loaded: totalLoaded,
    total: totalSize,
    percent
  }
})

const activeTransferPercent = computed(() => activeOverallProgress.value.percent)

const transferButtonLabel = computed(() => {
  if (activeTransferCount.value > 0) {
    const pct = activeOverallProgress.value.total > 0 ? ` ${activeTransferPercent.value}%` : ''
    return `传输 (${activeTransferCount.value})${pct}`
  }
  return `传输 (${visibleTransfers.value.length})`
})

const transferButtonTitle = computed(() => {
  if (activeTransferCount.value > 0) {
    const { loaded, total, percent } = activeOverallProgress.value
    if (total > 0) {
      return `文件传输：${activeTransferCount.value} 项进行中 · 综合进度 ${percent}% (${formatSize(loaded)} / ${formatSize(total)})`
    }
    return `文件传输：${activeTransferCount.value} 项进行中`
  }
  return `文件传输列表（共 ${visibleTransfers.value.length} 项记录）`
})
watch(() => props.active, active => { if (!active) showTransfers.value = false })
onDeactivated(() => { showTransfers.value = false })
function clearVisibleTransfers() {
  const visible = new Set(visibleTransfers.value)
  clearFinishedTransfers(task => visible.has(task))
}

const currentPath = ref('')
const loadedPath = ref('')
const pathInput = ref('')
const items = ref([])
const loading = ref(false)
const uploadInput = ref()
const folderTransfer = ref()
const showMkdir = ref(false)
const showDetails = ref(false)
const mkdirName = ref('')
const mkdirLoading = ref(false)
const showRename = ref(false)
const renameName = ref('')
const renameTarget = ref(null)
const renameLoading = ref(false)
const canChown = ref(false)
const showChown = ref(false)
const chownTarget = ref(null)
const chownOwner = ref('')
const chownGroup = ref('')
const chownRecursive = ref(false)
const chownLoading = ref(false)
const systemUsers = ref([])
const systemGroups = ref([])
const isDragOver = ref(false)
const showEditor = ref(false)
const editorFile = ref(null)
const showLargeViewer = ref(false)
const largeViewerFile = ref(null)
const sortProp = ref(localStorage.getItem('ssh_file_sort_prop') || 'name')
const sortOrder = ref(localStorage.getItem('ssh_file_sort_order') || 'ascending')
const filterKeyword = ref('')

const currentSortLabel = computed(() => {
  const isAsc = sortOrder.value === 'ascending'
  switch (sortProp.value) {
    case 'name': return isAsc ? '文件名 (A-Z)' : '文件名 (Z-A)'
    case 'mtime': return isAsc ? '修改时间 (旧→新)' : '修改时间 (新→旧)'
    case 'createTime': return isAsc ? '新增时间 (旧→新)' : '新增时间 (新→旧)'
    case 'size': return isAsc ? '大小 (小→大)' : '大小 (大→小)'
    default: return '排序'
  }
})

function handleSortCommand(command) {
  if (!command) return
  const [prop, order] = command.split(':')
  onSortChange({ prop, order })
}

function onSortChange({ prop, order }) {
  sortProp.value = prop
  sortOrder.value = order
  try {
    localStorage.setItem('ssh_file_sort_prop', prop)
    localStorage.setItem('ssh_file_sort_order', order)
  } catch {
    /* ignore storage quota / sandbox */
  }
}

const sortedItems = computed(() => {
  let list = items.value || []
  const kw = filterKeyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(item => String(item.name || '').toLowerCase().includes(kw))
  }
  return sortFileList(list, { prop: sortProp.value, order: sortOrder.value })
})

const breadcrumbs = computed(() => {
  const p = currentPath.value
  if (!p || p === '/') return []
  return p.split('/').filter(Boolean)
})

let handledLocation=null,lastAccount,lastTab,listRequest=0
let restoringPath = !!props.savedPath
let disposed = false
onScopeDispose(() => { disposed = true; ++listRequest })
const autoNavigationError = ref('')
const isEditingPath = ref(false)
const pathInputRef = ref(null)

function enterPathEditMode() {
  isEditingPath.value = true
  pathInput.value = loadedPath.value || currentPath.value || ''
  setTimeout(() => {
    pathInputRef.value?.focus?.()
    pathInputRef.value?.select?.()
  }, 40)
}

function exitPathEditMode() {
  isEditingPath.value = false
  pathInput.value = loadedPath.value || currentPath.value || ''
}

function handlePathInputEnter() {
  isEditingPath.value = false
  navigateToPath()
}

const canJumpTerminalCwd = computed(() => {
  const cwd = terminalContext.value?.cwd
  const cur = (loadedPath.value || currentPath.value || '').replace(/\/+$/, '')
  return !!(cwd && cwd.startsWith('/') && cwd.replace(/\/+$/, '') !== cur)
})

function jumpToTerminalCwd() {
  const cwd = terminalContext.value?.cwd
  if (cwd && cwd.startsWith('/')) {
    navigateToQuickPath(cwd)
  } else {
    ElMessage.info('暂未检测到终端工作目录')
  }
}

const syncNotice = computed(() => {
  if (!followTerminal.value && canJumpTerminalCwd.value) {
    return `终端当前位于: ${terminalContext.value?.cwd}`
  }
  return ''
})

const { follow: followTerminal, assignUploadOwner, context: terminalContext, message: contextMessage, canAssignOwner } = useTerminalFileContext({
  sessionToken: () => props.sessionToken, tabId: () => props.tabId, active: () => props.active,
  blocked: () => showEditor.value || showLargeViewer.value,
  currentPath: () => loadedPath.value,
  request: getTerminalContext,
  onNavigate(path) {
    autoNavigationError.value = ''
    currentPath.value = path; pathInput.value = path
    void refresh({ automatic: true })
  }
})
const uploadOwnership = computed(() => assignUploadOwner.value && canAssignOwner.value
  ? { ownerUid: terminalContext.value.terminalUid, ownerGid: terminalContext.value.terminalGid } : null)
function captureUpload(targetDir) {
  const dir = targetDir || loadedPath.value
  if (!props.sessionToken || !dir?.startsWith('/') || dir.includes('\0')) {
    ElMessage.warning('请等待文件会话和绝对目录加载完成后上传')
    return null
  }
  if (assignUploadOwner.value && !canAssignOwner.value) {
    ElMessage.warning('无法确认终端用户 UID/GID，请等待检测成功或取消上传归属选项')
    return null
  }
  return { token: props.sessionToken, dir, ownership: { ...uploadOwnership.value }, accountId: props.accountId, hostId: props.hostId }
}
function onFolderUploaded(snapshot) {
  if (snapshot?.token === props.sessionToken && snapshot?.path === loadedPath.value) void refresh()
}
function queueUpload(file, snapshot) {
  const { token, dir, ownership, accountId, hostId } = snapshot
  enqueueTransfer({ name: file.name, direction: '上传', path: dir, total: file.size, accountId, hostId,
    run: async transferOptions => {
      const result = await sftpUploadLarge(token, dir, file, { ...transferOptions, ...ownership })
      if (result?.warning) {
        ElMessage.success(`已上传 ${file.name}`)
        ElMessage.warning(`文件已上传，归属设置未完成：${result.warning}`)
      }
      if (props.sessionToken === token && loadedPath.value === dir) void refresh()
      return result
    }
  })
}

watch(
  () => [props.sessionToken, props.active, fileLocation.value, props.accountId, props.tabId],
  ([tok, active, location, accountId, tabId], previous = []) => {
    // Consuming a one-shot navigation request must not start a duplicate listing.
    if (!location && previous[2] && tok === previous[0] && active === previous[1] && accountId === previous[3] && tabId === previous[4]) return
    ++listRequest // Invalidate a response even when the next view has no session.
    loading.value = false
    if (accountId !== lastAccount || tabId !== lastTab) {
      currentPath.value = props.savedPath || ''
      pathInput.value = props.savedPath || props.defaultCwd || ''
      restoringPath = !!props.savedPath
      loadedPath.value = ''
      lastAccount = accountId
      lastTab = tabId
      handledLocation = null
      items.value = []
    }
    if (!active) return
    if (tok) {
      if (location && location !== handledLocation && location.accountId === props.accountId) {
        followTerminal.value = false
        currentPath.value = location.path
        pathInput.value = location.path
        handledLocation = location
        fileLocation.value = null
      }
      refresh()
    } else {
      items.value = []
    }
  },
  { immediate: true }
)

// The palette reuses the loaded listing; typing never starts another SFTP request.
let publishedFileContext = null
const fileSearchActive = ref(true)
function clearFileSearchContext() {
  if (publishedFileContext && fileSearchContext.value === publishedFileContext) fileSearchContext.value = null
  publishedFileContext = null
}
onDeactivated(() => { fileSearchActive.value = false; clearFileSearchContext() })
onActivated(() => { fileSearchActive.value = true })
onScopeDispose(clearFileSearchContext)
watch(
  () => [props.sessionToken, props.active, props.accountId, props.tabId, loadedPath.value, currentPath.value, loading.value, items.value, fileSearchActive.value],
  () => {
    clearFileSearchContext()
    if (disposed || !fileSearchActive.value || !props.active || !props.sessionToken || loading.value || !loadedPath.value || currentPath.value !== loadedPath.value) return
    const token = props.sessionToken, accountId = props.accountId, tabId = props.tabId, path = loadedPath.value
    const files = items.value.filter(row => !row.dir && !isBinaryFile(row.name))
    let context
    fileSearchContext.value = {
      accountId, tabId, path, files,
      openFile(row) {
        if (disposed || !fileSearchActive.value || !props.active || loading.value ||
            token !== props.sessionToken || accountId !== props.accountId || tabId !== props.tabId ||
            path !== loadedPath.value || path !== currentPath.value || fileSearchContext.value !== context) return false
        const file = files.find(file => file.name === row?.name)
        if (!file) return false
        openLargeFileViewer({ ...file, path: `${path.replace(/\/+$/, '')}/${file.name}` })
        return true
      }
    }
    context = fileSearchContext.value
    publishedFileContext = context
  },
  { immediate: true, flush: 'post' }
)

const canGoParent = computed(() => {
  const p = (loadedPath.value || currentPath.value || '').trim()
  if (!p || p === '/') return false
  return true
})

function goParent() {
  followTerminal.value = false
  if (!canGoParent.value) return
  const p = (loadedPath.value || currentPath.value || '/').replace(/\/+$/, '')
  const lastSlash = p.lastIndexOf('/')
  const parent = lastSlash <= 0 ? '/' : p.substring(0, lastSlash)
  currentPath.value = parent
  pathInput.value = parent
  refresh()
}

function goRoot() {
  followTerminal.value = false
  currentPath.value = props.defaultCwd || '/'
  pathInput.value = currentPath.value
  refresh()
}

function goToIndex(idx) {
  followTerminal.value = false
  const segs = breadcrumbs.value.slice(0, idx + 1)
  currentPath.value = '/' + segs.join('/')
  pathInput.value = currentPath.value
  refresh()
}

function enterDir(target) {
  followTerminal.value = false
  const name = (typeof target === 'object' && target !== null ? target.name : target)?.trim()
  if (!name) return

  // Base path is the directory currently loaded and displayed in the table
  const base = loadedPath.value || currentPath.value || props.defaultCwd || '/'
  const next = name.startsWith('/')
    ? name
    : base.endsWith('/') ? `${base}${name}` : `${base}/${name}`

  // Guard against redundant duplicate navigation while a request is already loading
  if (loading.value && currentPath.value === next) {
    return
  }

  currentPath.value = next
  pathInput.value = next
  refresh()
}

function navigateToPath() {
  followTerminal.value = false
  currentPath.value = pathInput.value.trim()
  refresh()
}

function isSessionExpiredError(err) {
  const d = err?.response?.data
  const msg = [d?.externMessage, d?.message, err?.message].filter(Boolean).join(' ')
  return /会话不存在|已过期/.test(msg)
}

function isUnavailableDirectoryError(err) {
  const data = err?.response?.data
  const message = [data?.externMessage, data?.message, err?.message].filter(Boolean).join(' ')
  return /no[ _]such[ _](file|path)|not[ _]a[ _]directory|permission[ _]denied|不存在|无权|权限/i.test(message)
}

async function refresh({ automatic = false } = {}) {
  if (!props.sessionToken || disposed) return
  const request = ++listRequest, token = props.sessionToken, tabId = props.tabId
  const pathArg = currentPath.value || pathInput.value || ''
  const restoring = restoringPath && pathArg === props.savedPath
  restoringPath = false
  loading.value = true
  try {
    const data = await sftpList(token, pathArg)
    if (request !== listRequest || token !== props.sessionToken || tabId !== props.tabId) return
    loadedPath.value = data.path || pathArg
    currentPath.value = loadedPath.value
    pathInput.value = loadedPath.value
    items.value = data.items || []
    if (loadedPath.value) emit('path-change', loadedPath.value, tabId)
  } catch (err) {
    if (request !== listRequest || token !== props.sessionToken || tabId !== props.tabId) return
    if (isSessionExpiredError(err)) {
      // 页面刷新/终端重连时旧 token 会被服务端主动清理；终端 ready
      // 后会自动提供新 token，不在这个过渡窗口弹出全局告警。
      restoringPath = restoring
      emit('session-invalid')
    } else if (restoring && isUnavailableDirectoryError(err)) {
      ElMessage.warning('上次的 SFTP 目录不存在或无权访问，已尝试返回默认目录')
      currentPath.value = props.defaultCwd || ''
      pathInput.value = currentPath.value
      await refresh()
    } else {
      restoringPath = restoring
      currentPath.value = loadedPath.value || currentPath.value
      pathInput.value = currentPath.value
      const errMsg = err?.response?.data?.externMessage || err?.response?.data?.message || err?.message || '读取远程目录失败'
      if (automatic) autoNavigationError.value = `终端目录无法访问：${errMsg}`
      else ElMessage.error(errMsg)
    }
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function onRowDblClick(row) {
  if (row.dir) {
    enterDir(row.name)
  } else if (isBinaryFile(row.name)) {
    ElMessage.warning('该文件不支持文本预览，请使用下载操作')
  } else {
    openLargeFileViewer(row)
  }
}

function openLargeFileViewer(row) {
  if (!row || row.dir) return
  largeViewerFile.value = row
  showLargeViewer.value = true
}

const router = useRouter()

function openStandaloneViewer(row) {
  if (!row || row.dir || !props.sessionToken) return
  const base = loadedPath.value || currentPath.value || props.defaultCwd || '/'
  const filePath = row.path || (base.endsWith('/') ? `${base}${row.name}` : `${base}/${row.name}`)

  const routeData = router.resolve({
    path: '/file-viewer',
    query: {
      token: props.sessionToken,
      accountId: props.accountId,
      path: filePath,
      name: row.name,
      size: row.size
    }
  })

  const width = Math.min(1280, Math.floor(window.screen.availWidth * 0.88))
  const height = Math.min(860, Math.floor(window.screen.availHeight * 0.88))
  const left = Math.max(0, Math.floor((window.screen.availWidth - width) / 2))
  const top = Math.max(0, Math.floor((window.screen.availHeight - height) / 2))

  window.open(
    routeData.href,
    `ssh_viewer_${Date.now()}`,
    `width=${width},height=${height},left=${left},top=${top},menubar=no,toolbar=no,location=no,status=no,resizable=yes,scrollbars=yes`
  )
}

async function openFullEditor(row) {
  if (!row || row.dir) return
  if (row.size > 50 * 1024 * 1024) {
    ElMessage.warning(`文件大小 (${formatSize(row.size)}) 超过全量在线编辑上限 (50 MB)，请使用查看功能或下载后编辑`)
    return
  }

  if (isBinaryFile(row.name)) {
    try {
      await ElMessageBox.confirm(
        `文件「${row.name}」疑似二进制文件，在线编辑可能出现乱码或损坏。是否仍要强制打开？`,
        '文件格式提示',
        {
          confirmButtonText: '强制打开',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  } else if (row.size > 5 * 1024 * 1024) {
    try {
      await ElMessageBox.confirm(
        `文件「${row.name}」大小约为 ${formatSize(row.size)}，全量加载将下载完整文件至浏览器排版，建议使用直接查看。是否仍要全量打开？`,
        '全量加载提示',
        {
          confirmButtonText: '全量打开',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  }

  editorFile.value = row
  showEditor.value = true
}

async function openFileEditor(row) {
  if (!row || row.dir) return
  // If large text file (> 5MB), route to large file dynamic streaming viewer directly
  if (row.size > 5 * 1024 * 1024 && !isBinaryFile(row.name)) {
    openLargeFileViewer(row)
    return
  }
  await openFullEditor(row)
}

async function downloadRow(row) {
  const base = currentPath.value || '/'
  const remote = base.endsWith('/') ? base + row.name : `${base}/${row.name}`
  const token = props.sessionToken
  const accountId = props.accountId
  const hostId = props.hostId
  try {
    // Ask for permission in the original click event, before queueing any work.
    const handle = window.showSaveFilePicker ? await window.showSaveFilePicker({ suggestedName: row.name }) : null
    if (!handle) {
      delegateBrowserDownload({ name: row.name, path: base, total: row.size, accountId, hostId,
        run: () => sftpDownloadLarge(token, remote) })
      return
    }
    enqueueTransfer({ name: row.name, direction: '下载', path: base, total: row.size, accountId, hostId,
      run: options => sftpDownloadLarge(token, remote, { ...options, handle }) })
  } catch (error) {
    if (error.name !== 'AbortError') ElMessage.error(transferErrorMessage(error, '下载'))
  }
}

function navigateToQuickPath(target) {
  pathInput.value = target
  void navigateToPath()
}

let dragDepth = 0

function handleDragEnter(event) {
  if (!event.dataTransfer?.types?.includes('Files')) return
  dragDepth++
  isDragOver.value = true
}

function handleDragOver(event) {
  if (!event.dataTransfer?.types?.includes('Files')) return
  event.preventDefault()
  event.dataTransfer.dropEffect = 'copy'
  isDragOver.value = true
}

function handleDragLeave(event) {
  dragDepth--
  if (dragDepth <= 0) {
    dragDepth = 0
    isDragOver.value = false
  }
}

async function handleDrop(event) {
  dragDepth = 0
  isDragOver.value = false
  event.preventDefault()
  if (!event.dataTransfer) return
  await processDroppedDataTransfer(event.dataTransfer)
}

async function readDirectoryEntryRecursively(entry, basePath = '') {
  const relPath = basePath ? `${basePath}/${entry.name}` : entry.name
  if (entry.isFile) {
    const file = await new Promise((resolve, reject) => entry.file(resolve, reject))
    return [{ name: relPath, file, dir: false }]
  } else if (entry.isDirectory) {
    const dirReader = entry.createReader()
    const readBatch = () => new Promise((resolve, reject) => dirReader.readEntries(resolve, reject))
    let entries = []
    let batch
    do {
      batch = await readBatch()
      if (batch && batch.length) {
        entries = entries.concat(batch)
      }
    } while (batch && batch.length > 0)

    const list = [{ name: relPath, dir: true }]
    for (const child of entries) {
      const children = await readDirectoryEntryRecursively(child, relPath)
      list.push(...children)
    }
    return list
  }
  return []
}

async function processDroppedDataTransfer(dataTransfer, targetDir = null) {
  if (!props.sessionToken) {
    ElMessage.warning('SFTP 会话尚未建立，请先建立连接')
    emit('request-session')
    return
  }
  const snapshot = captureUpload(targetDir)
  if (!snapshot) return
  const { dir } = snapshot

  const items = dataTransfer?.items
  let hasDirectory = false
  const folderEntries = []
  const regularFiles = []

  if (items && items.length && items[0].webkitGetAsEntry) {
    for (let i = 0; i < items.length; i++) {
      const entry = items[i].webkitGetAsEntry?.()
      if (entry) {
        if (entry.isDirectory) {
          hasDirectory = true
          const sub = await readDirectoryEntryRecursively(entry)
          folderEntries.push(...sub)
        } else if (entry.isFile) {
          const file = items[i].getAsFile()
          if (file) regularFiles.push(file)
        }
      } else {
        const file = items[i].getAsFile?.()
        if (file) regularFiles.push(file)
      }
    }
  } else {
    regularFiles.push(...Array.from(dataTransfer?.files || []))
  }

  // Upload folder entries if any
  if (hasDirectory && folderEntries.length > 0) {
    if (folderTransfer.value?.uploadFolderEntries) {
      await folderTransfer.value.uploadFolderEntries(folderEntries, { token: snapshot.token, path: snapshot.dir, ownership: snapshot.ownership })
    }
  }

  // Upload regular files if any
  if (regularFiles.length > 0) {
    for (const file of regularFiles) queueUpload(file, snapshot)
    ElMessage.success(`已开始上传 ${regularFiles.length} 个文件至 ${dir || '当前目录'}`)
  }
}

defineExpose({
  currentPath,
  refresh,
  processDroppedDataTransfer
})

function uploadFiles(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = '' // File references belong to the queue and are released when each task ends.
  const snapshot = captureUpload()
  if (!snapshot) return
  for (const file of files) queueUpload(file, snapshot)
}

function remotePathForRow(row) {
  const base = currentPath.value || '/'
  return base.endsWith('/') ? base + row.name : `${base}/${row.name}`
}

function openRename(row) {
  renameTarget.value = row
  renameName.value = row.name
  showRename.value = true
}

function resetRename() {
  renameTarget.value = null
  renameName.value = ''
}

async function doRename() {
  const row = renameTarget.value
  const name = renameName.value.trim()
  if (!row || !name || name === row.name) {
    showRename.value = false
    return
  }
  renameLoading.value = true
  try {
    await sftpRename(props.sessionToken, remotePathForRow(row), name)
    ElMessage.success('已重命名')
    showRename.value = false
    await refresh()
  } finally {
    renameLoading.value = false
  }
}

async function loadSystemUsersAndGroups() {
  const token = props.sessionToken
  if (!token) return
  try {
    const res = await sftpGetUsersAndGroups(token)
    if (disposed || token !== props.sessionToken) return
    // 如果当前用户无法查看到其他用户或无权限，关闭此选项
    canChown.value = !!(res?.canViewOtherUsers && res?.canChown)
    if (res?.users) systemUsers.value = res.users
    if (res?.groups) systemGroups.value = res.groups
  } catch (err) {
    if (disposed || token !== props.sessionToken) return
    canChown.value = false
    if (isSessionExpiredError(err)) emit('session-invalid')
  }
}

watch(() => props.sessionToken, token => {
  canChown.value = false
  systemUsers.value = []
  systemGroups.value = []
  if (token) {
    void loadSystemUsersAndGroups()
  }
}, { immediate: true })

function openChown(row) {
  chownTarget.value = row
  chownOwner.value = row.owner || ''
  chownGroup.value = row.group || ''
  chownRecursive.value = false
  showChown.value = true
}

function resetChown() {
  chownTarget.value = null
  chownOwner.value = ''
  chownGroup.value = ''
  chownRecursive.value = false
}

async function doChown() {
  const row = chownTarget.value
  if (!row) return
  const owner = chownOwner.value.trim()
  const group = chownGroup.value.trim()
  if (!owner && !group) {
    ElMessage.warning('请至少指定归属用户或用户组')
    return
  }
  chownLoading.value = true
  try {
    await sftpChown(
      props.sessionToken,
      remotePathForRow(row),
      owner,
      group,
      row.dir ? chownRecursive.value : false
    )
    ElMessage.success('已修改归属用户')
    showChown.value = false
    await refresh()
  } finally {
    chownLoading.value = false
  }
}

async function confirmDelete(row) {
  const tip = row.dir
    ? `删除空目录「${row.name}」？非空目录请先清空。`
    : `删除文件「${row.name}」？`
  try {
    await ElMessageBox.confirm(tip, '确认删除', { type: 'warning' })
    await sftpDelete(props.sessionToken, remotePathForRow(row))
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* cancel or error */
  }
}

async function doMkdir() {
  const name = mkdirName.value.trim()
  if (!name) {
    ElMessage.warning('请输入目录名')
    return
  }
  const base = currentPath.value || props.defaultCwd || '/'
  const remote = base.endsWith('/') ? base + name : `${base}/${name}`
  mkdirLoading.value = true
  try {
    await sftpMkdir(props.sessionToken, remote)
    ElMessage.success('目录已创建')
    showMkdir.value = false
    await refresh()
  } finally {
    mkdirLoading.value = false
  }
}


function formatMtime(sec) {
  const s = Number(sec) || 0
  if (s <= 0) return '—'
  return new Date(s * 1000).toLocaleString()
}
</script>

<style scoped>
.ssh-file-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  gap: 8px;
  overflow: hidden;
  position: relative;
}
.no-session {
  padding: 24px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
  margin-top: 8px;
}
.terminal-sync-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 10px;
  background: var(--ws-accent-bg, rgba(99, 102, 241, 0.08));
  border: 1px solid rgba(99, 102, 241, 0.25);
  border-radius: var(--ws-radius-sm, 6px);
  font-size: 12px;
  color: var(--el-color-primary, #6366f1);
}
.notice-content {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notice-icon {
  font-size: 14px;
}
.notice-text {
  font-size: 12px;
}
.notice-jump-btn {
  background: none;
  border: none;
  color: var(--el-color-primary, #6366f1);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  padding: 1px 4px;
  white-space: nowrap;
  transition: opacity 0.15s ease;
}
.notice-jump-btn:hover {
  text-decoration: underline;
  opacity: 0.85;
}

.sftp-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.toolbar-nav-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 300px;
}
.nav-btn-cluster {
  display: inline-flex;
  align-items: center;
  background: var(--el-fill-color-light, rgba(0, 0, 0, 0.04));
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--ws-radius-sm, 6px);
  padding: 2px;
  gap: 1px;
  flex-shrink: 0;
}
.nav-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  border-radius: 4px;
  color: var(--ws-text-muted, #64748b);
  cursor: pointer;
  transition: all 0.15s ease;
}
.nav-icon-btn:hover:not(:disabled) {
  background: var(--el-bg-color, #ffffff);
  color: var(--ws-text-primary, #0f172a);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}
.nav-icon-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.nav-icon-btn.is-loading .el-icon {
  animation: rotating 1.2s linear infinite;
}
.nav-cluster-divider {
  display: inline-block;
  width: 1px;
  height: 12px;
  background: var(--el-border-color-lighter);
  margin: 0 2px;
}
.nav-icon-btn.sync-jump-btn:hover:not(:disabled) {
  color: var(--el-color-success, #10b981);
}
.nav-icon-btn.sync-toggle-btn.is-active {
  color: var(--el-color-primary, #6366f1);
  background: var(--ws-accent-bg, rgba(99, 102, 241, 0.12));
}

.smart-address-bar {
  flex: 1;
  display: flex;
  align-items: center;
  min-height: 28px;
  background: var(--el-fill-color-light, rgba(0, 0, 0, 0.03));
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--ws-radius-sm, 6px);
  padding: 0 6px 0 8px;
  cursor: text;
  transition: all 0.15s ease;
  position: relative;
  overflow: hidden;
}
.smart-address-bar:hover {
  border-color: var(--el-border-color);
  background: var(--el-fill-color, rgba(0, 0, 0, 0.05));
}
.smart-address-bar.is-editing,
.smart-address-bar:focus-within {
  border-color: var(--el-color-primary, #6366f1);
  box-shadow: 0 0 0 1px rgba(99, 102, 241, 0.2);
  background: var(--el-bg-color, #ffffff);
}
.address-breadcrumb-wrap {
  display: flex;
  align-items: center;
  gap: 3px;
  flex: 1;
  overflow-x: auto;
  white-space: nowrap;
  scrollbar-width: none;
  font-size: 12px;
}
.address-breadcrumb-wrap::-webkit-scrollbar {
  display: none;
}
.breadcrumb-root {
  font-weight: 700;
  color: var(--el-color-primary, #6366f1);
  cursor: pointer;
  padding: 1px 4px;
  border-radius: 3px;
  transition: background 0.12s ease;
}
.breadcrumb-root:hover {
  background: var(--ws-accent-bg, rgba(99, 102, 241, 0.1));
}
.breadcrumb-separator {
  color: var(--ws-text-muted, #94a3b8);
  font-size: 11px;
}
.breadcrumb-seg {
  color: var(--ws-text-muted, #64748b);
  cursor: pointer;
  padding: 1px 4px;
  border-radius: 3px;
  font-family: var(--font-mono, monospace);
  transition: all 0.12s ease;
}
.breadcrumb-seg:hover {
  color: var(--el-color-primary, #6366f1);
  background: var(--ws-accent-bg, rgba(99, 102, 241, 0.08));
}
.breadcrumb-seg.is-last {
  font-weight: 600;
  color: var(--ws-text-primary, #0f172a);
  background: var(--el-bg-color, rgba(255, 255, 255, 0.8));
  border: 1px solid var(--el-border-color-lighter);
}
.address-input-wrap {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 4px;
}
.address-text-input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 12px;
  color: var(--el-text-color-primary);
  font-family: var(--font-mono, monospace);
}
.address-action-btn {
  border: none;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.address-action-btn.confirm {
  background: var(--el-color-primary, #6366f1);
  color: #ffffff;
}
.address-action-btn.confirm:hover {
  background: var(--el-color-primary-light-3);
}
.address-action-btn.cancel {
  background: transparent;
  color: var(--ws-text-muted, #64748b);
}
.address-action-btn.cancel:hover {
  color: var(--ws-text-primary);
}
.address-bar-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: 4px;
  flex-shrink: 0;
}
.address-pill-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  border-radius: 3px;
  color: var(--ws-text-muted, #94a3b8);
  cursor: pointer;
  transition: all 0.15s ease;
}
.address-pill-btn:hover {
  color: var(--el-color-primary, #6366f1);
  background: var(--ws-accent-bg, rgba(99, 102, 241, 0.08));
}

.toolbar-action-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.user-context-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  height: 26px;
  border-radius: var(--ws-radius-sm, 6px);
  background: var(--el-fill-color-light, rgba(0, 0, 0, 0.03));
  border: 1px solid var(--el-border-color-lighter);
  color: var(--ws-text-muted, #64748b);
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.user-context-pill:hover {
  border-color: var(--el-border-color);
  background: var(--el-fill-color);
  color: var(--ws-text-primary);
}
.user-pill-ico {
  color: #eab308;
  font-size: 12px;
}
.user-pill-name {
  font-weight: 600;
  color: var(--ws-text-primary);
}
.user-pill-arrow {
  color: var(--ws-text-muted);
  font-size: 10px;
}
.user-pill-down {
  font-size: 9px;
  margin-left: 1px;
}

.user-popover-card {
  padding: 4px 0;
  font-size: 12px;
}
.popover-title {
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--ws-text-primary);
  font-size: 13px;
}
.popover-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.popover-k {
  color: var(--ws-text-muted);
}
.popover-v {
  font-weight: 600;
  color: var(--ws-text-primary);
  background: var(--el-fill-color-light);
  padding: 1px 6px;
  border-radius: 4px;
}
.popover-divider {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 8px 0;
}
.upload-owner-checkbox {
  margin: 4px 0;
}
.popover-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}

.filter-input {
  width: 130px;
}
.filter-input :deep(.el-input__wrapper) {
  padding-left: 8px;
}
.sort-btn {
  padding: 5px 8px;
}
.transfer-badge-btn {
  padding: 5px 8px;
}

html.dark .nav-icon-btn:hover:not(:disabled),
:root[data-theme='dark'] .nav-icon-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.08);
  color: #ffffff;
}
html.dark .smart-address-bar.is-editing,
:root[data-theme='dark'] .smart-address-bar.is-editing {
  background: #0f1523;
}
html.dark .breadcrumb-seg.is-last,
:root[data-theme='dark'] .breadcrumb-seg.is-last {
  background: rgba(255, 255, 255, 0.08);
  color: #e2e8f0;
}
.file-table-container {
  flex: 1;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
}
.drop-upload-overlay {
  position: absolute;
  inset: 0;
  z-index: 50;
  background: rgba(99, 102, 241, 0.15);
  backdrop-filter: blur(4px);
  border: 2px dashed var(--el-color-primary, #6366f1);
  border-radius: var(--ws-radius-md, 8px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--el-color-primary, #6366f1);
  font-weight: 600;
  font-size: 13px;
  pointer-events: none;
}
.drop-upload-ico {
  font-size: 32px;
}
.drop-upload-text {
  font-size: 14px;
  font-weight: 600;
}
.drop-upload-path {
  font-size: 12px;
  opacity: 0.9;
  background: rgba(0, 0, 0, 0.08);
  padding: 2px 10px;
  border-radius: 4px;
}
html.dark .drop-upload-path,
:root[data-theme='dark'] .drop-upload-path {
  background: rgba(255, 255, 255, 0.1);
}
.file-name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.file-type-badge {
  font-size: 9px;
  font-weight: 700;
  padding: 1px 4px;
  border-radius: 3px;
  letter-spacing: 0.5px;
  font-family: var(--font-mono, monospace);
}
.badge-dir {
  background: rgba(99, 102, 241, 0.15);
  color: #6366f1;
}
.badge-log {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
}
.badge-sh {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
}
.badge-cfg {
  background: rgba(6, 182, 212, 0.15);
  color: #06b6d4;
}
.badge-zip {
  background: rgba(168, 85, 247, 0.15);
  color: #a855f7;
}
.row-ico.is-dir {
  color: var(--el-color-primary, #6366f1);
}
.file-name-text {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
.file-name-text.is-dir {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.chown-target-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--el-fill-color-light, #f5f7fa);
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  width: 100%;
}
.chown-target-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
html.dark .chown-target-preview,
:root[data-theme='dark'] .chown-target-preview {
  background: rgba(255, 255, 255, 0.05);
}
</style>
