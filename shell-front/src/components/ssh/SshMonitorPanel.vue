<template>
  <aside ref="panel" class="ssh-monitor" :class="{collapsed}" :style="{width: collapsed ? '64px' : `${width}px`}" aria-label="主机监控">
    <div class="toolbar"><strong>监控</strong><el-button size="small" text @click="collapsed=!collapsed">{{ collapsed ? '展开' : '收起' }}</el-button></div>
    <el-scrollbar v-show="!collapsed" class="monitor-scroll"><div class="body">
      <div class="status-row"><el-tag :type="status.type" size="small" role="status">{{ status.label }}</el-tag><span v-if="latest?.timestamp">更新于 {{ time(latest.timestamp) }}</span></div>
      <div class="controls">
        <el-button size="small" :disabled="!sessionToken" @click="paused=!paused">{{ paused ? '继续' : '暂停' }}</el-button>
        <el-button size="small" :loading="loading" :disabled="!sessionToken || !active" @click="poll(true)">刷新</el-button>
        <el-select v-model="interval" aria-label="监控刷新间隔" size="small" style="width:100px"><el-option v-for="s in [5,10,30]" :key="s" :label="`${s} 秒`" :value="s*1000" /></el-select>
        <el-select v-model="minutes" aria-label="监控趋势范围" size="small" style="width:112px"><el-option v-for="m in [5,15,30]" :key="m" :label="`最近 ${m} 分钟`" :value="m" /></el-select>
      </div>
      <p v-if="!sessionToken" class="hint">连接终端后自动采样。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="latest?.status==='DISABLED'" class="hint">监控已在服务端关闭。</p>
      <p v-if="warnings" class="warning">{{ warnings }}</p>
      <p class="hint">按 {{ Math.round(effectiveInterval/1000) }} 秒间隔刷新 · 暂停或离开页面期间不补采</p>

      <section class="block">
        <div class="block-head"><strong>CPU</strong><span class="value">{{ pct(latest?.cpu?.usagePercent) }}</span></div>
        <p class="hint">{{ latest?.cpu?.cores ?? '—' }} 核 · I/O 等待 {{ pct(latest?.cpu?.iowaitPercent) }}</p>
        <div ref="cpuEl" class="chart" aria-label="CPU 使用率趋势" />
        <div class="loads"><div v-for="(key,index) in ['load1','load5','load15']" :key="key"><b>{{ decimal(latest?.cpu?.[key]) }}</b><span>{{ [1,5,15][index] }} 分钟负载</span></div></div>
        <p v-if="latest?.cpu?.cores" class="hint">负载持续高于 {{ latest.cpu.cores }} 时，检查 CPU 或 I/O 压力。</p>
      </section>

      <section class="block">
        <div class="block-head"><strong>内存</strong><span>{{ pct(latest?.memory?.usagePercent) }}</span></div>
        <el-progress :percentage="latest?.memory?.usagePercent ?? 0" :show-text="false" :color="level(latest?.memory?.usagePercent)" />
        <p class="hint">已用 {{ memory(latest?.memory?.usedMb) }} / {{ memory(latest?.memory?.totalMb) }} · 可用 {{ memory(latest?.memory?.availableMb) }}</p>
        <div class="block-head"><strong>Swap</strong><span>{{ latest?.swap ? latest.swap.enabled ? pct(latest.swap.usagePercent) : '未启用' : '—' }}</span></div>
        <el-progress v-if="latest?.swap?.enabled" aria-label="交换内存使用率" :percentage="latest.swap.usagePercent ?? 0" :show-text="false" :stroke-width="8" :color="latest.swap.usagePercent >= 80 ? level(latest.swap.usagePercent) : '#a16ae8'" />
        <p v-if="latest?.swap?.enabled" class="hint">已用 {{ memory(latest.swap.usedMb) }} / {{ memory(latest.swap.totalMb) }}</p>
      </section>

      <section class="block">
        <div class="block-head"><strong>网络吞吐</strong><el-select v-model="iface" aria-label="监控网卡" placeholder="默认网卡汇总" size="small" style="width:145px"><el-option label="默认网卡汇总" value="" /><el-option v-for="i in latest?.network?.interfaces || []" :key="i.name" :value="i.name" :label="i.name" /></el-select></div>
        <div class="net-values"><span>↓ {{ rate(network?.rxBytesPerSec) }}</span><span>↑ {{ rate(network?.txBytesPerSec) }}</span></div>
        <p class="hint">{{ iface ? `网卡 ${iface}` : `汇总：${defaultInterfaces || '无默认网卡，请手动选择'}` }}</p>
        <div ref="netEl" class="chart" aria-label="网络吞吐趋势" />
        <p v-if="iface && network" class="hint">累计错误包 收/发 {{ network.rxErrors ?? '—' }}/{{ network.txErrors ?? '—' }} · 丢包 {{ network.rxDropped ?? '—' }}/{{ network.txDropped ?? '—' }}</p>
      </section>

      <section class="block">
        <div class="block-head"><strong>磁盘与 inode</strong><span class="hint">{{ latest?.diskTimestamp ? time(latest.diskTimestamp) : '尚未采样' }}</span></div>
        <p v-if="!latest?.disks?.length" class="hint">暂无可用磁盘数据</p>
        <div v-for="disk in latest?.disks || []" :key="`${disk.filesystem}:${disk.mount}`" class="disk">
          <div class="block-head"><span class="mount" :title="`${disk.mount} · ${disk.filesystem}`">{{ disk.mount }}</span><b>{{ pct(disk.usagePercent) }}</b></div>
          <el-progress :percentage="disk.usagePercent ?? 0" :show-text="false" :color="level(disk.usagePercent)" />
          <p class="hint">可用 {{ capacity(disk.availableGb) }} / {{ capacity(disk.totalGb) }} · inode {{ pct(disk.inodeUsagePercent) }}<span v-if="disk.inodesAvailable!=null">（剩余 {{ disk.inodesAvailable.toLocaleString() }}）</span></p>
        </div>
      </section>
      <p class="hint">保留最多 30 分钟趋势。暂停、断连或无有效采样时保留断点；短时采样延迟不拆线。</p>
    </div></el-scrollbar>
    <div v-if="!collapsed" class="resize-handle" role="separator" aria-label="调整监控面板宽度" aria-orientation="vertical" :aria-valuenow="width" :aria-valuemin="260" :aria-valuemax="maxWidth" tabindex="0" @pointerdown="dragStart" @pointermove="dragMove" @pointerup="dragEnd" @pointercancel="dragEnd" @keydown.left.prevent="setWidth(width-20)" @keydown.right.prevent="setWidth(width+20)" />
  </aside>
</template>

<script setup>
import { computed, nextTick, onActivated, onDeactivated, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { fetchHostMetrics } from '@/api/sshConsole'
import { useTheme } from '@/composables/useTheme'
import { readMonitorHistory, saveMonitorHistory } from '@/utils/monitorHistory'
import { monitorSeries } from '@/utils/monitorSeries'

const props=defineProps({sessionToken:{type:String,default:''},accountId:{type:Number,default:null},active:{type:Boolean,default:true}})
const {effectiveDark}=useTheme()
const panel=ref(null),cpuEl=ref(null),netEl=ref(null),collapsed=ref(false),paused=ref(false),interval=ref(5000),minutes=ref(5),iface=ref('')
const latest=shallowRef(null),points=shallowRef([]),error=ref(''),loading=ref(false),serverInterval=ref(5000),now=ref(Date.now()),visible=ref(!document.hidden),activated=ref(true)
const width=ref(460),maxWidth=ref(760)
try{const stored=Number(localStorage.getItem('doumi-monitor-width-v2'));if(stored>=260&&stored<=760)width.value=stored}catch{/* 默认宽度 */}
const effectiveInterval=computed(()=>Math.max(interval.value,serverInterval.value))
const enabled=computed(()=>!!props.sessionToken&&props.active&&activated.value&&visible.value&&!collapsed.value&&!paused.value&&latest.value?.status!=='DISABLED')
const historyKey=()=>props.accountId ? `account:${props.accountId}` : `session:${props.sessionToken}`
const network=computed(()=>iface.value?latest.value?.network?.interfaces?.find(i=>i.name===iface.value):latest.value?.network)
const defaultInterfaces=computed(()=>latest.value?.network?.interfaces?.filter(i=>i.includedByDefault).map(i=>i.name).join(', '))
const warnings=computed(()=>Object.values(latest.value?.errors||{}).join('；'))
const status=computed(()=>{
  if(!props.sessionToken)return {label:'未连接',type:'info'}
  if(latest.value?.status==='DISABLED')return {label:'已禁用',type:'info'}
  if(paused.value||!props.active||!activated.value||!visible.value||collapsed.value)return {label:'已暂停',type:'info'}
  if(error.value)return {label:'采集失败',type:'danger'}
  if(latest.value?.timestamp&&now.value-latest.value.timestamp>Math.max(15000,effectiveInterval.value*3))return {label:'数据已过期',type:'warning'}
  if(!latest.value?.timestamp||latest.value.status==='WARMING_UP')return {label:'首次采样中',type:'warning'}
  if(latest.value.status==='PARTIAL')return {label:'部分指标不可用',type:'warning'}
  return {label:'正常',type:'success'}
})
const pct=n=>n==null?'—':`${n.toFixed(1)}%`
const decimal=n=>n==null?'—':n.toFixed(2)
const memory=n=>n==null?'—':n>=1024?`${(n/1024).toFixed(1)} GiB`:`${n.toFixed(0)} MiB`
const capacity=n=>n==null?'—':`${n.toFixed(1)} GiB`
const rate=n=>n==null?'—':n>=1048576?`${(n/1048576).toFixed(1)} MiB/s`:n>=1024?`${(n/1024).toFixed(1)} KiB/s`:`${n} B/s`
const time=value=>new Date(value).toLocaleTimeString('zh-CN',{hour12:false})
const level=n=>n>=90?'#f56c6c':n>=80?'#e6a23c':'#409eff'
let timer,clock,observer,charts=[],generation=0,inFlight=false,disposed=false,controller,drag=null,mounted=false,breakBefore=true

async function poll(manual=false){
  if(disposed||inFlight||!props.sessionToken||!props.active||!activated.value||!visible.value||(!manual&&!enabled.value))return
  clearTimeout(timer)
  const version=generation,token=props.sessionToken,key=historyKey()
  inFlight=true;loading.value=true;controller=new AbortController()
  try{
    const data=await fetchHostMetrics(token,{signal:controller.signal})
    if(disposed||version!==generation||token!==props.sessionToken)return
    error.value='';latest.value=data;serverInterval.value=Math.max(3000,data.pollIntervalMs||5000)
    data.sampleIntervalMs=effectiveInterval.value
    if(data.timestamp){
      const existing=points.value.find(p=>p.timestamp===data.timestamp)
      data.breakBefore=existing?.breakBefore ?? breakBefore
      if(!existing)breakBefore=false
      const cutoff=Date.now()-30*60000
      points.value=[...points.value.filter(p=>p.timestamp>=cutoff&&p.timestamp!==data.timestamp),data].slice(-900)
      saveMonitorHistory(key,points.value)
    }
    if(iface.value&&!data.network?.interfaces?.some(i=>i.name===iface.value))iface.value=''
    render()
  }catch(e){if(!disposed&&version===generation&&e?.code!=='ERR_CANCELED'){error.value=e?.message||'采样失败';breakBefore=true}}
  finally{
    inFlight=false;loading.value=false;controller=null
    if(!disposed&&enabled.value)timer=setTimeout(()=>poll(),effectiveInterval.value)
  }
}
function sync(){
  if(!enabled.value)breakBefore=true
  generation++;clearTimeout(timer)
  if(mounted&&enabled.value&&!inFlight)poll()
  nextTick(()=>{ensureCharts();render()})
}
function restore(){
  breakBefore=true
  const cutoff=Date.now()-30*60000
  points.value=readMonitorHistory(historyKey()).filter(p=>p.timestamp>=cutoff)
  latest.value=points.value.at(-1)||null;error.value='';iface.value='';serverInterval.value=5000
}
function ensureCharts(){
  if(!mounted||collapsed.value||!props.active||!cpuEl.value?.clientWidth)return
  if(!charts.length){
    const muted=effectiveDark.value?'#aeb9cb':'#687488',line=effectiveDark.value?'#333e4f':'#e9edf3'
    const base={animation:false,backgroundColor:'transparent',tooltip:{trigger:'axis',confine:true},grid:{left:48,right:12,top:24,bottom:28},
      xAxis:{type:'time',axisLabel:{color:muted,fontSize:10,hideOverlap:true,formatter:value=>time(value)},axisLine:{lineStyle:{color:line}},splitNumber:3},
      yAxis:{type:'value',axisLabel:{color:muted,fontSize:10},splitLine:{lineStyle:{color:line}}},
      legend:{top:0,textStyle:{color:muted,fontSize:10}}}
    const cpu=echarts.init(cpuEl.value),net=echarts.init(netEl.value)
    cpu.setOption({...base,yAxis:{...base.yAxis,min:0,max:100},tooltip:{...base.tooltip,valueFormatter:value=>pct(value)},series:[
      {name:'使用率',type:'line',showSymbol:true,symbolSize:3,connectNulls:false,color:'#409eff',areaStyle:{opacity:.08},data:[]},
      {name:'I/O 等待',type:'line',showSymbol:true,symbolSize:3,connectNulls:false,color:'#e6a23c',data:[]}]})
    net.setOption({...base,yAxis:{...base.yAxis,axisLabel:{...base.yAxis.axisLabel,formatter:value=>value>=1048576?`${(value/1048576).toFixed(0)}M`:value>=1024?`${(value/1024).toFixed(0)}K`:`${value}`}},tooltip:{...base.tooltip,valueFormatter:value=>rate(value)},series:[
      {name:'下行',type:'line',showSymbol:true,symbolSize:3,connectNulls:false,color:'#67c23a',data:[]},
      {name:'上行',type:'line',showSymbol:true,symbolSize:3,connectNulls:false,color:'#409eff',data:[]}]})
    charts=[cpu,net]
  }
  charts.forEach(chart=>chart.resize())
}
const series=monitorSeries
function render(){
  if(!charts.length||collapsed.value||!props.active)return
  const end=now.value,start=end-minutes.value*60000,rows=points.value.filter(p=>p.timestamp>=start)
  const n=row=>iface.value?row.network?.interfaces?.find(i=>i.name===iface.value):row.network
  charts[0].setOption({xAxis:{min:start,max:end},series:[{data:series(rows,p=>p.cpu?.usagePercent)},{data:series(rows,p=>p.cpu?.iowaitPercent)}]})
  charts[1].setOption({xAxis:{min:start,max:end},series:[{data:series(rows,p=>n(p)?.rxBytesPerSec)},{data:series(rows,p=>n(p)?.txBytesPerSec)}]})
}
function setWidth(value){width.value=Math.round(Math.min(maxWidth.value,Math.max(260,value)));try{localStorage.setItem('doumi-monitor-width-v2',String(width.value))}catch{/* 仅保存偏好 */}}
function dragStart(event){if(event.button!==0)return;drag={x:event.clientX,width:width.value};event.currentTarget.setPointerCapture(event.pointerId);event.preventDefault()}
function dragMove(event){if(drag)setWidth(drag.width+event.clientX-drag.x)}
function dragEnd(event){drag=null;if(event.currentTarget.hasPointerCapture(event.pointerId))event.currentTarget.releasePointerCapture(event.pointerId)}
function resize(){
  maxWidth.value=Math.max(260,Math.min(760,(panel.value?.parentElement?.clientWidth||900)-260))
  if(width.value>maxWidth.value)width.value=maxWidth.value
  ensureCharts();render()
}
function visibility(){visible.value=!document.hidden;sync()}
watch(()=>[props.sessionToken,props.accountId],()=>{restore();sync()},{flush:'post'})
watch([()=>props.active,collapsed,paused,interval],sync,{flush:'post'})
watch([minutes,iface],render)
watch(effectiveDark,()=>{charts.forEach(c=>c.dispose());charts=[];nextTick(()=>{ensureCharts();render()})})
onMounted(async()=>{
  restore();await nextTick();mounted=true
  observer=new ResizeObserver(resize);observer.observe(panel.value);if(panel.value?.parentElement)observer.observe(panel.value.parentElement)
  document.addEventListener('visibilitychange',visibility)
  clock=setInterval(()=>{now.value=Date.now();if(enabled.value)render()},1000)
  resize();sync()
})
onActivated(()=>{activated.value=true;sync()})
onDeactivated(()=>{activated.value=false;sync()})
onBeforeUnmount(()=>{disposed=true;generation++;clearTimeout(timer);clearInterval(clock);controller?.abort();observer?.disconnect();document.removeEventListener('visibilitychange',visibility);charts.forEach(c=>c.dispose());charts=[]})
</script>

<style scoped>
.ssh-monitor{position:relative;flex-shrink:0;display:flex;flex-direction:column;min-height:0;border-right:1px solid var(--el-border-color-light);background:var(--el-bg-color-page);font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;font-size:12px}
.monitor-scroll{flex:1;min-height:0}.toolbar{display:flex;justify-content:space-between;align-items:center;padding:8px 10px;border-bottom:1px solid var(--el-border-color-light);flex-shrink:0}.toolbar strong{font-size:13px}.collapsed .toolbar{flex-direction:column;padding:8px 0;gap:6px}.body{padding:12px;min-height:0;display:flex;flex-direction:column;gap:12px}.controls,.status-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.status-row>span:last-child{font-size:11px;color:var(--el-text-color-secondary)}.block{background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:12px;flex-shrink:0}.block-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:8px}.value{font-size:22px;font-weight:600}.hint{font-size:11px;color:var(--el-text-color-secondary);line-height:1.7;margin:0}.block .hint{margin-top:7px}.warning{font-size:11px;line-height:1.6;margin:0;color:var(--el-color-warning)}.chart{height:150px;width:100%;margin:8px 0}.loads{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.loads div{display:flex;flex-direction:column;gap:5px}.loads b{font-size:17px}.loads span{font-size:10px;color:var(--el-text-color-secondary)}.net-values{display:flex;gap:16px;font-size:15px;font-weight:600;margin-top:12px}.disk+.disk{margin-top:14px;padding-top:12px;border-top:1px solid var(--el-border-color-lighter)}.mount{min-width:0;overflow-wrap:anywhere;font-family:Consolas,monospace}.resize-handle{position:absolute;right:-4px;top:0;bottom:0;width:8px;cursor:col-resize;touch-action:none;z-index:5}.resize-handle:hover,.resize-handle:focus-visible{background:var(--el-color-primary-light-5);outline:none}
</style>
