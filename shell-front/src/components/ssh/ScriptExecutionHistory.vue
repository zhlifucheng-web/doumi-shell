<template>
  <el-scrollbar class="page-scroll"><section class="history">
    <div class="filters">
      <el-select v-model="status" clearable placeholder="全部状态" @change="reset" style="width:160px">
        <el-option v-for="(label,value) in statuses" :key="value" :value="value" :label="label" />
      </el-select>
      <el-select v-model="source" clearable placeholder="全部来源" @change="reset" style="width:140px">
        <el-option value="MANUAL" label="手动执行" /><el-option value="CRON" label="定时执行" />
      </el-select>
      <el-button @click="load" :loading="loading">刷新</el-button>
      <span>关闭页面后任务继续执行，可在此重新查看和结束。</span>
    </div>
    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="script_name" label="脚本" min-width="130" />
      <el-table-column label="执行账户" min-width="160"><template #default="{row}">{{ row.username }}@{{ row.host_name }}</template></el-table-column>
      <el-table-column label="来源" width="85"><template #default="{row}">{{ row.source === 'CRON' ? '定时' : '手动' }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="active(row) ? 'warning' : row.status==='SUCCESS' ? 'success' : 'info'">{{ statuses[row.status] || row.status }}</el-tag></template></el-table-column>
      <el-table-column label="开始时间" min-width="175"><template #default="{row}">{{ date(row.started_at) }}</template></el-table-column>
      <el-table-column label="耗时" width="100"><template #default="{row}">{{ row.duration_ms == null ? '—' : `${(row.duration_ms / 1000).toFixed(1)} 秒` }}</template></el-table-column>
      <el-table-column label="操作" width="160" fixed="right"><template #default="{row}">
        <el-button link type="primary" @click="open(row.id)">查看输出</el-button>
        <el-button v-if="active(row)" link type="danger" :disabled="row.status==='CANCELLING'" @click="stop(row)">结束</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <el-drawer v-model="drawer" title="脚本执行详情" size="min(900px, 90vw)"><el-scrollbar height="100%">
      <template v-if="detail">
        <div class="detail-meta"><strong>{{ detail.script_name }}</strong><el-tag>{{ statuses[detail.status] || detail.status }}</el-tag>
          <el-button v-if="active(detail)" type="danger" :disabled="detail.status==='CANCELLING'" @click="stop(detail)">结束执行</el-button>
        </div>
        <p>{{ detail.username }}@{{ detail.host_name }} · {{ date(detail.started_at) }} · 退出码 {{ detail.exit_status ?? '—' }}</p>
        <div v-if="detail.error" class="copyable-error"><el-alert :title="detail.error" type="error" :closable="false" /><el-button size="small" @click="copyText(detail.error)">复制错误</el-button></div>
        <el-alert v-if="detail.truncated" title="输出已达到上限，每个输出通道最多保留 512 KiB。" type="warning" :closable="false" />
        <div class="output-heading"><h4>标准输出</h4><el-button size="small" :disabled="!detail.stdout" @click="copyText(detail.stdout)">复制</el-button></div><el-scrollbar max-height="45vh"><pre>{{ detail.stdout || '（暂无输出）' }}</pre></el-scrollbar>
        <div class="output-heading"><h4>错误输出</h4><el-button size="small" :disabled="!detail.stderr" @click="copyText(detail.stderr)">复制</el-button></div><el-scrollbar max-height="45vh"><pre>{{ detail.stderr || '（暂无输出）' }}</pre></el-scrollbar>
      </template>
    </el-scrollbar></el-drawer>
  </section></el-scrollbar>
</template>
<script setup>
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listExecutions, getExecution, stopExecution } from '@/api/sshConsole'
const props=defineProps({scriptId:{type:Number,default:null},active:{type:Boolean,default:true}})
const rows=ref([]),total=ref(0),page=ref(1),status=ref(''),source=ref(''),loading=ref(false),drawer=ref(false),detail=ref(null)
const statuses={QUEUED:'排队中',RUNNING:'执行中',CANCELLING:'正在结束',SUCCESS:'成功',FAILED:'执行失败',ERROR:'连接或执行错误',CANCELLED:'已结束',CANCEL_FAILED:'终止失败',TIMED_OUT:'超时',INTERRUPTED:'服务中断'}
const active=row=>['QUEUED','RUNNING','CANCELLING'].includes(row.status)
const date=value=>value ? new Date(value).toLocaleString() : '—'
let requestVersion=0
async function load(){
  const version=++requestVersion;loading.value=true
  try {const data=await listExecutions({scriptId:props.scriptId,status:status.value,source:source.value,page:page.value});if(version===requestVersion){rows.value=data.rows;total.value=data.total}}
  finally {if(version===requestVersion)loading.value=false}
}
function reset(){page.value=1;load()}
async function open(id){detail.value=await getExecution(id);drawer.value=true}
async function copyText(value){
  try {
    if(navigator.clipboard?.writeText) await navigator.clipboard.writeText(value)
    else {const field=document.createElement('textarea');field.value=value;field.style.position='fixed';field.style.opacity='0';document.body.appendChild(field);try{field.select();if(!document.execCommand('copy'))throw new Error('copy failed')}finally{field.remove()}}
    ElMessage.success('已复制到剪贴板')
  } catch { ElMessage.error('复制失败，请手动选择并复制') }
}
async function stop(row){
  try {await ElMessageBox.confirm(`结束脚本「${row.script_name}」及其进程组？`,'结束执行',{type:'warning',confirmButtonText:'结束执行'})}catch{return}
  await stopExecution(row.id);await load();if(detail.value?.id===row.id)detail.value=await getExecution(row.id)
}
watch(()=>props.active,enabled=>{if(enabled)load();else{requestVersion++;loading.value=false}},{immediate:true})
watch(()=>props.scriptId,()=>{if(props.active)reset()})
defineExpose({open,load})
</script>
<style scoped>
.history{padding:16px;box-sizing:border-box;height:100%}.filters,.detail-meta{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:16px}.filters span{font-size:12px;color:var(--el-text-color-secondary)}.el-pagination{margin-top:16px}pre{background:#181c24;color:#d4d4d4;border-radius:6px;padding:16px;white-space:pre-wrap;overflow-wrap:anywhere;font-family:Consolas,monospace}h4{margin-bottom:8px}
.output-heading{display:flex;align-items:center;justify-content:space-between;margin-top:12px}.output-heading h4{margin:0}.copyable-error{display:flex;align-items:center;gap:8px}.copyable-error .el-alert{flex:1;min-width:0}
</style>
