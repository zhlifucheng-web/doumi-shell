<template>
  <div class="ssh-process-panel">
    <div class="toolbar">
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      <el-select v-model="sortKey" size="small" style="width: 140px">
        <el-option label="按 CPU 降序" value="cpu" />
        <el-option label="按内存降序" value="mem" />
        <el-option label="按名称升序" value="name" />
        <el-option label="按 PID 升序" value="pid" />
      </el-select>
      <el-input v-model="filterText" size="small" clearable placeholder="筛选 PID / 命令 / 用户" style="width: 200px" />
    </div>
    <div v-loading="loading" class="process-body">
      <el-scrollbar class="process-scroll">
      <el-empty v-if="!sessionToken && !loading" description="需要 SSH 会话" />
      <el-collapse v-else v-model="expandedUsers">
        <el-collapse-item v-for="g in grouped" :key="g.user" :name="g.user">
          <template #title>
            <span class="user-title">{{ g.user }}</span>
            <span class="user-count">{{ g.processes.length }} 个进程</span>
          </template>
          <el-table :data="g.processes" size="small" stripe max-height="320">
            <el-table-column prop="pid" label="PID" width="80" />
            <el-table-column prop="cpuPercent" label="CPU%" width="72" sortable />
            <el-table-column prop="memPercent" label="内存%" width="72" sortable />
            <el-table-column prop="rssKb" label="RSS(KB)" width="88" />
            <el-table-column prop="command" label="命令" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="warning" size="small" @click="kill(row, 'TERM')">停止</el-button>
                <el-button link type="danger" size="small" @click="kill(row, 'KILL')">强制停止</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useAiChanges } from '@/composables/useAiChanges'
useAiChanges(/^(process_kill|ssh_command|script_run)/, () => { if (props.active && props.sessionToken) return load() })
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchHostProcesses, killHostProcess } from '@/api/sshConsole'

const props = defineProps({
  sessionToken: { type: String, default: '' },
  active: { type: Boolean, default: false }
})

const emit = defineEmits(['request-session'])

const loading = ref(false)
const rows = ref([])
const sortKey = ref('cpu')
const filterText = ref('')
const expandedUsers = ref([])
let requestVersion = 0

function compareRows(a, b) {
  if (sortKey.value === 'cpu') return b.cpuPercent - a.cpuPercent
  if (sortKey.value === 'mem') return b.memPercent - a.memPercent
  if (sortKey.value === 'name') return (a.command || '').localeCompare(b.command || '')
  return a.pid - b.pid
}

const filtered = computed(() => {
  const q = filterText.value.trim().toLowerCase()
  let list = rows.value
  if (q) {
    list = list.filter(
      (r) =>
        String(r.pid).includes(q) || (r.user && r.user.toLowerCase().includes(q)) ||
        (r.command && r.command.toLowerCase().includes(q))
    )
  }
  return [...list].sort(compareRows)
})

const grouped = computed(() => {
  const map = new Map()
  for (const r of filtered.value) {
    const u = r.user || '(unknown)'
    if (!map.has(u)) map.set(u, [])
    map.get(u).push(r)
  }
  const groups = []
  for (const [user, processes] of map.entries()) {
    groups.push({ user, processes: [...processes].sort(compareRows) })
  }
  groups.sort((a, b) => {
    const sumCpu = (list) => list.reduce((s, p) => s + (p.cpuPercent || 0), 0)
    if (sortKey.value === 'cpu') return sumCpu(b.processes) - sumCpu(a.processes)
    if (sortKey.value === 'mem') {
      const sumMem = (list) => list.reduce((s, p) => s + (p.memPercent || 0), 0)
      return sumMem(b.processes) - sumMem(a.processes)
    }
    return a.user.localeCompare(b.user)
  })
  return groups
})

async function kill(row, signal) {
  if (!props.sessionToken) {
    emit('request-session')
    return
  }
  const isKill = signal === 'KILL'
  const action = isKill ? '强制杀停（SIGKILL）' : '结束（SIGTERM）'
  const title = isKill ? '确认强制停止进程' : '确认停止进程'
  try {
    await ElMessageBox.confirm(
      `将对远程主机发送 ${action}。\n\nPID：${row.pid}\n用户：${row.user || '-'}\nCPU：${row.cpuPercent ?? '-'}%  内存：${row.memPercent ?? '-'}%\n命令：${row.command || '-'}`,
      title,
      {
        type: 'warning',
        confirmButtonText: isKill ? '强制停止' : '停止',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true
      }
    )
    const r = await killHostProcess(props.sessionToken, row.pid, signal)
    if (r.success) {
      ElMessage.success('已发送信号')
      await load()
    } else {
      ElMessage.error(r.message || `失败 (exit ${r.exitStatus})`)
    }
  } catch {
    /* cancel */
  }
}

async function load() {
  if (!props.sessionToken) {
    emit('request-session')
    return
  }
  const version = ++requestVersion
  const token = props.sessionToken
  loading.value = true
  try {
    const result = await fetchHostProcesses(token)
    if (version !== requestVersion || token !== props.sessionToken) return
    rows.value = result || []
    expandedUsers.value = grouped.value.slice(0, 5).map((g) => g.user)
  } catch {
    if (version === requestVersion && token === props.sessionToken) rows.value = []
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

onMounted(() => {
  if (props.active) load()
})

watch(
  () => [props.sessionToken, props.active],
  () => {
    requestVersion++
    if (props.active && props.sessionToken) load()
  }
)
</script>

<style scoped>
.ssh-process-panel {
  box-sizing: border-box;
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 8px;
  overflow: hidden;
}
.toolbar {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
  margin-bottom: 8px;
}
.process-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.process-scroll { height: 100%; }
.user-title {
  font-weight: 600;
  margin-right: 8px;
}
.user-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
