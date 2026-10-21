<template>
  <Teleport :to="fullscreenHost || 'body'" :disabled="!fullscreenVisible || !fullscreenHost">
  <aside ref="panel" class="ssh-monitor" :class="{'ssh-monitor--fullscreen':fullscreenVisible}" :style="[backgroundStyle,{width: `${width}px`}]" aria-label="主机监控">
    <div class="toolbar"><strong>监控</strong><el-select :model-value="activeDashboardId" aria-label="切换监控面板" size="small" class="dashboard-select" @change="switchDashboard"><el-option v-for="dashboard in dashboards" :key="dashboard.id" :label="dashboard.name" :value="dashboard.id" /></el-select><div class="toolbar-actions"><el-button text size="small" title="新建监控面板" aria-label="新建监控面板" :disabled="dashboards.length>=12" @click="createDashboard"><el-icon><Plus /></el-icon></el-button><el-button text size="small" title="重命名监控面板" aria-label="重命名监控面板" @click="renameDashboard"><el-icon><EditPen /></el-icon></el-button><el-button text size="small" title="删除监控面板" aria-label="删除监控面板" :disabled="dashboards.length<=1" @click="deleteDashboard"><el-icon><Delete /></el-icon></el-button><el-button text size="small" title="全屏查看监控面板" aria-label="全屏查看监控面板" @click="openFullscreen"><el-icon><FullScreen /></el-icon></el-button><el-button text size="small" title="监控设置" aria-label="监控设置" @click="settingsVisible=true"><el-icon><Setting /></el-icon></el-button><el-button text size="small" :title="expanded ? '收缩监控' : '展开监控'" :aria-label="expanded ? '收缩监控' : '展开监控'" @click="toggleExpanded"><el-icon><component :is="expanded ? Fold : Expand" /></el-icon></el-button></div></div>
    <el-scrollbar class="monitor-scroll"><div class="body">
      <div class="status-row"><el-tag :type="status.type" size="small" role="status">{{ status.label }}</el-tag><span>{{ latest?.timestamp ? `更新于 ${time(latest.timestamp)}` : '等待首次采样' }}</span></div>
      <div v-if="!sessionToken || latest?.status==='DISABLED' || error || (!error && warnings)" class="notice-slot full-row"><p v-if="!sessionToken" class="hint">连接终端后自动采样。</p><p v-if="latest?.status==='DISABLED'" class="hint">监控已在服务端关闭。</p><p v-if="error" class="warning">{{ error }}</p><p v-if="!error && warnings" class="warning">{{ warnings }}</p></div>
      <p class="hint full-row">按 {{ Math.round(effectiveInterval/1000) }} 秒间隔刷新 · 暂停或离开页面期间不补采</p>

      <section v-show="visibleCards.cpu" class="block" :class="{'is-dragging': draggedCard==='cpu'}" :style="cardStyle('cpu')" draggable="true" @dragstart="dragCard('cpu',$event)" @dragend="clearDraggedCard" @dragover.prevent @drop="dropCard('cpu',$event)">
        <div class="block-head"><strong>CPU</strong><span class="value">{{ pct(latest?.cpu?.usagePercent) }}</span><el-button text size="small" title="放大 CPU 趋势" aria-label="放大 CPU 趋势" @click="openDetail('cpu')"><el-icon><FullScreen /></el-icon></el-button></div>
        <p class="hint">{{ latest?.cpu?.cores ?? '—' }} 核 · I/O 等待 {{ pct(latest?.cpu?.iowaitPercent) }}</p>
        <div ref="cpuEl" class="chart" aria-label="CPU 使用率趋势" />
        <div class="loads"><div v-for="(key,index) in ['load1','load5','load15']" :key="key"><b>{{ decimal(latest?.cpu?.[key]) }}</b><span>{{ [1,5,15][index] }} 分钟负载</span></div></div>
        <p v-if="latest?.cpu?.cores" class="hint">负载持续高于 {{ latest.cpu.cores }} 时，检查 CPU 或 I/O 压力。</p>
      </section>

      <section v-show="visibleCards.memory" class="block" :class="{'is-dragging': draggedCard==='memory'}" :style="cardStyle('memory')" draggable="true" @dragstart="dragCard('memory',$event)" @dragend="clearDraggedCard" @dragover.prevent @drop="dropCard('memory',$event)">
        <div class="block-head"><strong>内存</strong><span>{{ pct(latest?.memory?.usagePercent) }}</span></div>
        <el-progress :percentage="latest?.memory?.usagePercent ?? 0" :show-text="false" :color="level(latest?.memory?.usagePercent)" />
        <p class="hint">已用 {{ memory(latest?.memory?.usedMb) }} / {{ memory(latest?.memory?.totalMb) }} · 可用 {{ memory(latest?.memory?.availableMb) }}</p>
        <div class="block-head"><strong>Swap</strong><span>{{ latest?.swap ? latest.swap.enabled ? pct(latest.swap.usagePercent) : '未启用' : '—' }}</span></div>
        <el-progress v-if="latest?.swap?.enabled" aria-label="交换内存使用率" :percentage="latest.swap.usagePercent ?? 0" :show-text="false" :stroke-width="8" :color="latest.swap.usagePercent >= 80 ? level(latest.swap.usagePercent) : '#a16ae8'" />
        <p v-if="latest?.swap?.enabled" class="hint">已用 {{ memory(latest.swap.usedMb) }} / {{ memory(latest.swap.totalMb) }}</p>
      </section>

      <section v-show="visibleCards.network" class="block" :class="{'is-dragging': draggedCard==='network'}" :style="cardStyle('network')" draggable="true" @dragstart="dragCard('network',$event)" @dragend="clearDraggedCard" @dragover.prevent @drop="dropCard('network',$event)">
        <div class="block-head"><strong>网络吞吐</strong><div class="chart-actions"><el-select v-model="iface" aria-label="监控网卡" placeholder="默认网卡汇总" size="small" style="width:145px"><el-option label="默认网卡汇总" value="" /><el-option v-for="i in latest?.network?.interfaces || []" :key="i.name" :value="i.name" :label="i.name" /></el-select><el-button text size="small" title="放大网络趋势" aria-label="放大网络趋势" @click="openDetail('network')"><el-icon><FullScreen /></el-icon></el-button></div></div>
        <div class="net-values"><span>↓ {{ rate(network?.rxBytesPerSec) }}</span><span>↑ {{ rate(network?.txBytesPerSec) }}</span></div>
        <p class="hint">{{ iface ? `网卡 ${iface}` : `汇总：${defaultInterfaces || '无默认网卡，请手动选择'}` }}</p>
        <div ref="netEl" class="chart" aria-label="网络吞吐趋势" />
        <p v-if="iface && network" class="hint">累计错误包 收/发 {{ network.rxErrors ?? '—' }}/{{ network.txErrors ?? '—' }} · 丢包 {{ network.rxDropped ?? '—' }}/{{ network.txDropped ?? '—' }}</p>
      </section>

      <section v-show="visibleCards.gpu" class="block gpu-block" :class="{'is-dragging': draggedCard==='gpu'}" :style="cardStyle('gpu')" draggable="true" @dragstart="dragCard('gpu',$event)" @dragend="clearDraggedCard" @dragover.prevent @drop="dropCard('gpu',$event)">
        <div class="block-head"><strong>GPU</strong><el-button text size="small" :disabled="!latest?.gpus?.length" title="查看 GPU 详情" aria-label="查看 GPU 详情" @click="openDetail('gpu')"><el-icon><FullScreen /></el-icon></el-button></div>
        <p v-if="!latest?.gpus?.length" class="hint gpu-empty">未检测到 NVIDIA GPU / nvidia-smi</p>
        <div ref="gpuEl" class="chart gpu-chart" aria-label="GPU 使用率趋势" />
        <div v-for="gpu in latest?.gpus || []" :key="gpu.index" class="gpu-row" @scroll="showScrollFeedback"><div class="block-head"><span class="gpu-name" :title="gpu.name">GPU {{ gpu.index }} · {{ gpu.name }}</span><b>{{ pct(gpu.utilizationPercent) }}</b></div><el-progress :percentage="gpu.utilizationPercent ?? 0" :show-text="false" :color="level(gpu.utilizationPercent)" /><p class="hint">显存 {{ memory(gpu.memoryUsedMb) }} / {{ memory(gpu.memoryTotalMb) }} · {{ gpu.temperatureC ?? '—' }}°C</p></div>
      </section>

      <section v-show="visibleCards.disk" class="block disk-block" :class="{'is-dragging': draggedCard==='disk'}" :style="cardStyle('disk')" draggable="true" @dragstart="dragCard('disk',$event)" @dragend="clearDraggedCard" @dragover.prevent @drop="dropCard('disk',$event)">
        <div class="block-head"><strong>磁盘与 inode</strong><span class="hint">{{ latest?.diskTimestamp ? time(latest.diskTimestamp) : '尚未采样' }}</span></div>
        <div class="disk-list" @scroll="showScrollFeedback"><p v-if="!latest?.disks?.length" class="hint disk-empty">暂无可用磁盘数据</p>
          <div v-for="disk in latest?.disks || []" :key="`${disk.filesystem}:${disk.mount}`" class="disk">
            <div class="block-head"><span class="mount" :title="`${disk.mount} · ${disk.filesystem}`">{{ disk.mount }}</span><b>{{ pct(disk.usagePercent) }}</b></div>
            <el-progress :percentage="disk.usagePercent ?? 0" :show-text="false" :color="level(disk.usagePercent)" />
            <p class="hint">可用 {{ capacity(disk.availableGb) }} / {{ capacity(disk.totalGb) }} · inode {{ pct(disk.inodeUsagePercent) }}<span v-if="disk.inodesAvailable!=null">（剩余 {{ disk.inodesAvailable.toLocaleString() }}）</span></p>
          </div>
        </div>
      </section>
      <section v-for="item in customScripts.filter(script => script.enabled && visibleCustomCardIds.includes(script.id))" :key="item.id" class="block custom-script-block" :class="{'is-dragging': draggedCard===`custom:${item.id}`}" :style="cardStyle(`custom:${item.id}`)" draggable="true" @dragstart="dragCard(`custom:${item.id}`,$event)" @dragend="clearDraggedCard" @dragover.prevent @drop="dropCard(`custom:${item.id}`,$event)">
        <div class="block-head"><strong>{{ item.name }}</strong><span class="hint">{{ item.timestamp ? time(item.timestamp) : '等待采样' }}</span></div>
        <template v-if="item.error && !['line','bar','pie'].includes(item.type)"><pre class="custom-script-output custom-script-error">{{ item.error }}</pre></template>
        <template v-else-if="item.type === 'value'"><div class="custom-script-value">{{ scriptValue(item).value }}<small>{{ scriptValue(item).unit }}</small></div></template>
        <template v-else-if="item.type === 'progress'"><el-progress :percentage="scriptValue(item).value" :color="level(scriptValue(item).value)" /><p class="hint">{{ scriptValue(item).label }}</p></template>
        <template v-else-if="item.type === 'json'"><table class="custom-script-table"><tbody><tr v-for="row in scriptValue(item).rows" :key="row.key"><th>{{ row.key }}</th><td>{{ row.value }}</td></tr></tbody></table></template>
        <div v-else-if="['line','bar','pie'].includes(item.type)" :ref="el => setCustomChartRef(item.id, el)" class="custom-script-chart" :aria-label="`${item.name}图表`" />
        <div v-else-if="item.type === 'text'" class="custom-script-text">{{ scriptValue(item).text }}</div>
        <pre v-else class="custom-script-output">{{ item.output || '暂无输出' }}</pre>
        <p v-if="item.exitStatus != null" class="hint">退出码 {{ item.exitStatus }}</p>
      </section>
    </div></el-scrollbar>
    <div class="resize-handle" role="separator" aria-label="调整监控面板宽度" aria-orientation="vertical" :aria-valuenow="width" :aria-valuemin="260" :aria-valuemax="maxWidth" tabindex="0" @pointerdown="dragStart" @pointermove="dragMove" @pointerup="dragEnd" @pointercancel="dragEnd" @keydown.left.prevent="setWidth(width-20)" @keydown.right.prevent="setWidth(width+20)" />
  </aside>
  </Teleport>
  <el-dialog v-model="fullscreenVisible" fullscreen append-to-body class="monitor-fullscreen-dialog" :show-close="false" @opened="onFullscreenOpened">
    <template #header><div class="fullscreen-dialog-header"><strong>{{ activeDashboard?.name || '监控面板' }}</strong><el-button text title="退出全屏" aria-label="退出全屏" @click="fullscreenVisible=false"><el-icon><Close /></el-icon></el-button></div></template>
    <div ref="fullscreenHost" class="monitor-fullscreen-host" />
  </el-dialog>
  <el-dialog v-model="settingsVisible" :title="`监控设置 · ${activeDashboard?.name || '当前面板'}`" width="min(1120px, calc(100vw - 32px))" append-to-body class="monitor-settings-dialog">
    <div class="monitor-settings-columns">
      <section class="settings-column monitor-options-column">
        <h3>监控设置</h3>
        <el-form label-position="top" class="monitor-options-form">
          <el-form-item label="刷新频率"><el-select v-model="interval" aria-label="监控刷新间隔"><el-option v-for="s in [5,10,30]" :key="s" :label="`${s} 秒`" :value="s*1000" /></el-select></el-form-item>
          <el-form-item label="运行控制"><div class="settings-controls"><el-button size="small" :disabled="!sessionToken" @click="paused=!paused">{{ paused ? '继续采样' : '暂停采样' }}</el-button><el-button size="small" :loading="loading" :disabled="!sessionToken || !active" @click="poll(true)">立即刷新</el-button></div></el-form-item>
          <el-form-item label="趋势范围"><el-select v-model="minutes" aria-label="监控趋势范围"><el-option v-for="m in [5,15,30]" :key="m" :label="`最近 ${m} 分钟`" :value="m" /></el-select></el-form-item>
          <el-form-item label="系统指标"><el-checkbox-group v-model="selectedCards" class="card-options"><el-checkbox v-for="item in cardOptions" :key="item.key" :label="item.key">{{ item.label }}</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item v-if="customScripts.length" label="当前面板中的自定义卡片"><el-checkbox-group v-model="visibleCustomCardIds" class="card-options"><el-checkbox v-for="item in customScripts" :key="`visible-${item.id}`" :label="item.id" :disabled="!item.enabled">{{ item.name }}</el-checkbox></el-checkbox-group></el-form-item>
        </el-form>
      </section>
      <section class="settings-column scripts-column">
        <h3>自定义脚本 <small>{{ customScripts.length }}/20 · 启用状态按账户生效</small></h3>
        <el-form label-position="top" class="script-editor-form">
          <div class="script-editor-fields"><el-form-item label="名称"><el-input v-model="scriptDraft.name" maxlength="50" placeholder="例如：队列积压" /></el-form-item><el-form-item label="卡片类型"><el-select v-model="scriptDraft.type"><el-option label="状态文本" value="text" /><el-option label="数值" value="value" /><el-option label="进度百分比" value="progress" /><el-option label="JSON 详情" value="json" /><el-option label="折线图" value="line" /><el-option label="柱状图" value="bar" /><el-option label="饼图" value="pie" /></el-select></el-form-item></div>
          <el-form-item label="无数据时显示"><el-input v-model="scriptDraft.emptyText" maxlength="100" placeholder="等待有效数据" /></el-form-item>
          <el-form-item label="采集脚本"><el-input v-model="scriptDraft.script" type="textarea" :rows="4" maxlength="12000" placeholder="脚本需输出约定 JSON；可使用下方框架" /><p class="script-framework">{{ scriptFramework }}</p></el-form-item>
          <div class="script-editor-actions"><el-button type="primary" :disabled="!scriptDraft.name.trim() || !scriptDraft.script.trim() || (!editingScriptId && customScripts.length >= 20)" @click="saveCustomScript">{{ editingScriptId ? '保存脚本' : '添加脚本卡片' }}</el-button><span class="hint">超时不超过采样间隔的 60%。</span></div>
        </el-form>
        <div class="script-card-list" @scroll="showScrollFeedback"><div v-for="item in customScripts" :key="`setting-${item.id}`" class="script-setting-row"><el-switch v-model="item.enabled" :aria-label="`启用${item.name}`" /><span class="script-setting-name" :title="item.name">{{ item.name }}</span><el-button text size="small" title="编辑脚本" aria-label="编辑脚本" @click="editCustomScript(item)"><el-icon><Edit /></el-icon></el-button><el-button text size="small" type="danger" title="删除脚本" aria-label="删除脚本" @click="removeCustomScript(item.id)"><el-icon><Delete /></el-icon></el-button></div><p v-if="!customScripts.length" class="hint script-list-empty">尚未添加自定义脚本</p></div>
        <p v-if="customScripts.length" class="hint scripts-permission-note">脚本以当前 SSH 账户权限在远端执行，请只使用你信任的只读命令。</p>
      </section>
    </div>
    <template #footer><el-button type="primary" @click="settingsVisible=false">完成</el-button></template>
  </el-dialog>
  <el-dialog v-model="detailVisible" :title="detailTitle" width="min(1000px, calc(100vw - 32px))" append-to-body @opened="resizeDetail">
    <div class="detail-summary" v-if="detailMetric === 'cpu'">CPU {{ pct(latest?.cpu?.usagePercent) }} · {{ latest?.cpu?.cores ?? '—' }} 核 · I/O 等待 {{ pct(latest?.cpu?.iowaitPercent) }} · 负载 {{ decimal(latest?.cpu?.load1) }} / {{ decimal(latest?.cpu?.load5) }} / {{ decimal(latest?.cpu?.load15) }}</div>
    <div class="detail-summary" v-else-if="detailMetric === 'network'">↓ {{ rate(network?.rxBytesPerSec) }} · ↑ {{ rate(network?.txBytesPerSec) }} · {{ iface || '默认网卡汇总' }}</div>
    <div class="detail-summary" v-else>GPU 总数 {{ latest?.gpus?.length || 0 }} · 平均使用率 {{ pct(gpuAverage) }}</div>
    <div ref="detailChartEl" class="detail-chart" />
    <div v-if="detailMetric === 'gpu'" class="detail-gpus"><div v-for="gpu in latest?.gpus || []" :key="gpu.index"><strong>GPU {{ gpu.index }} · {{ gpu.name }}</strong><span>使用率 {{ pct(gpu.utilizationPercent) }}</span><span>显存 {{ memory(gpu.memoryUsedMb) }} / {{ memory(gpu.memoryTotalMb) }}</span><span>温度 {{ gpu.temperatureC ?? '—' }}°C</span></div></div>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onActivated, onDeactivated, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessageBox } from 'element-plus'
import { Close, Delete, Edit, EditPen, Expand, Fold, FullScreen, Plus, Setting } from '@element-plus/icons-vue'
import { fetchHostMetrics, runMonitorScript } from '@/api/sshConsole'
import { MONITOR_CONFIG_CHANGED } from '@/utils/monitorConfigStore'
import { useMonitorConfigStore } from '@/stores/monitorConfig'
import { useTheme } from '@/composables/useTheme'
import { useBackground } from '@/composables/useBackground'
import { readMonitorHistory, saveMonitorHistory } from '@/utils/monitorHistory'
import { monitorSeries } from '@/utils/monitorSeries'

const props=defineProps({sessionToken:{type:String,default:''},accountId:{type:Number,default:null},active:{type:Boolean,default:true}})
const monitorConfigStore=useMonitorConfigStore()
const {effectiveDark}=useTheme()
const {style:backgroundStyle}=useBackground()
const panel=ref(null),fullscreenHost=ref(null),cpuEl=ref(null),netEl=ref(null),gpuEl=ref(null),expanded=ref(false),paused=ref(false),fullscreenVisible=ref(false)
const cardOptions=[{key:'cpu',label:'CPU'},{key:'memory',label:'内存与 Swap'},{key:'network',label:'网络'},{key:'gpu',label:'GPU'},{key:'disk',label:'磁盘与 inode'}]
const editorState=computed(()=>monitorConfigStore.editor(props.accountId))
const configState=computed(()=>monitorConfigStore.ensure(props.accountId))
const editorField=name=>computed({get:()=>editorState.value[name],set:value=>{editorState.value[name]=value}})
const selectedCards=editorField('selectedCards'),interval=editorField('interval'),minutes=editorField('minutes'),iface=editorField('iface'),activeDashboardId=editorField('activeDashboardId'),visibleCustomCardIds=editorField('visibleCustomCardIds'),cardOrder=editorField('cardOrder'),configRevision=editorField('revision')
const visibleCards=computed(()=>Object.fromEntries(cardOptions.map(item=>[item.key,selectedCards.value.includes(item.key)])))
const customScripts=computed({get:()=>configState.value.customScripts,set:value=>{configState.value.customScripts=value}}),dashboards=computed({get:()=>configState.value.dashboards,set:value=>{configState.value.dashboards=value}})
const scriptDraft=ref({name:'',script:'',type:'text',emptyText:'等待有效数据'}),editingScriptId=ref('')
const activeDashboard=computed(()=>dashboards.value.find(item=>item.id===activeDashboardId.value)||dashboards.value[0])
let loadedDashboardId='default'
const configSourceId=`monitor-panel-${Date.now()}-${Math.random().toString(36).slice(2)}`
const scriptFramework='数值/进度：{"value":42,"unit":"%","label":"当前值"}；时间折线（每次采样一个点）：{"value":42} 或 {"series":[{"name":"值","value":42}]}；静态折线/柱状：{"labels":["一","二","三"],"series":[{"name":"值","data":[12,18,15]}],"xAxisName":"项目","yAxisName":"数量"}；饼图：{"data":[{"name":"正常","value":80},{"name":"异常","value":20}]}；状态文本：{"text":"正常"}；JSON：输出任意 JSON。'
const draggedCard=ref('')
let configReady=false,configSaveTimer
const latest=shallowRef(null),points=shallowRef([]),error=ref(''),loading=ref(false),serverInterval=ref(5000),now=ref(Date.now()),visible=ref(!document.hidden),activated=ref(true)
const customChartEls=new Map(),customCharts=new Map()
const customHistory=new Map()
const detailVisible=ref(false),settingsVisible=ref(false),detailMetric=ref('cpu'),detailChartEl=ref(null)
const defaultWidth=380,width=ref(defaultWidth),maxWidth=ref(760)
try{const stored=Number(localStorage.getItem('doumi-monitor-width-v2'));if(stored>=260&&stored<=760)width.value=stored}catch{/* 默认宽度 */}
const effectiveInterval=computed(()=>Math.max(interval.value,serverInterval.value))
const enabled=computed(()=>!!props.sessionToken&&props.active&&activated.value&&visible.value&&!paused.value&&latest.value?.status!=='DISABLED')
const historyKey=()=>props.accountId ? `account:${props.accountId}` : `session:${props.sessionToken}`
const network=computed(()=>iface.value?latest.value?.network?.interfaces?.find(i=>i.name===iface.value):latest.value?.network)
const defaultInterfaces=computed(()=>latest.value?.network?.interfaces?.filter(i=>i.includedByDefault).map(i=>i.name).join(', '))
const warnings=computed(()=>Object.values(latest.value?.errors||{}).join('；'))
const gpuAverage=computed(()=>{const list=latest.value?.gpus||[];return list.length?list.reduce((sum,g)=>sum+(g.utilizationPercent||0),0)/list.length:null})
const detailTitle=computed(()=>({cpu:'CPU 详细趋势',network:'网络详细趋势',gpu:'GPU 详情'})[detailMetric.value])
function accountSettingsKey(prefix){return `${prefix}:${props.accountId||'default'}`}
function dashboardSnapshot(){return {id:activeDashboardId.value,name:activeDashboard.value?.name||'监控面板',selectedCards:[...selectedCards.value],customCardIds:[...visibleCustomCardIds.value],cardOrder:[...cardOrder.value],minutes:minutes.value,iface:iface.value}}
function defaultDashboard(){const cards=cardOptions.map(item=>item.key);return {id:'default',name:'默认面板',selectedCards:cards,customCardIds:[],cardOrder:cards,minutes:5,iface:''}}
function ensureDashboard(){
  if(!dashboards.value.length)dashboards.value=[defaultDashboard()]
  if(!dashboards.value.some(item=>item.id===activeDashboardId.value)){
    const next=dashboards.value[0];activeDashboardId.value=next.id;loadedDashboardId=next.id
    selectedCards.value=[...(next.selectedCards||[])];visibleCustomCardIds.value=[...(next.customCardIds||[])];cardOrder.value=[...(next.cardOrder||cardOptions.map(item=>item.key))]
    minutes.value=Number(next.minutes)||5;iface.value=next.iface||''
  }
}
function syncActiveDashboard(){const index=dashboards.value.findIndex(item=>item.id===activeDashboardId.value);if(index>=0)dashboards.value[index]=dashboardSnapshot()}
function switchDashboard(id){
  const previous=dashboards.value.find(item=>item.id===loadedDashboardId)
  if(previous){const index=dashboards.value.indexOf(previous);dashboards.value[index]=dashboardSnapshotFor(previous.id,previous.name)}
  const next=dashboards.value.find(item=>item.id===id);if(!next)return
  loadedDashboardId=id;activeDashboardId.value=id
  selectedCards.value=Array.isArray(next.selectedCards)?[...next.selectedCards]:[]
  visibleCustomCardIds.value=Array.isArray(next.customCardIds)?[...next.customCardIds]:[]
  cardOrder.value=Array.isArray(next.cardOrder)?[...next.cardOrder]:[...cardOptions.map(item=>item.key),...visibleCustomCardIds.value.map(value=>`custom:${value}`)]
  minutes.value=Number(next.minutes)||5
  iface.value=next.iface||''
  queueConfigSave()
  nextTick(()=>{charts.forEach(chart=>chart.resize());customCharts.forEach(chart=>chart.resize())})
}
function onDashboardShortcut(event){
  const isSpace=event.code==='Space'||event.key===' '
  if(!event.defaultPrevented&&event.ctrlKey&&isSpace&&!event.altKey&&!event.shiftKey&&!event.metaKey&&!event.repeat&&props.active&&activated.value&&dashboards.value.length>1){
    const target=event.target
    if(target instanceof HTMLElement&&(target.isContentEditable||target.closest('input,textarea,select,[contenteditable="true"],[role="textbox"]')))return
    event.preventDefault()
    const currentIndex=dashboards.value.findIndex(item=>item.id===activeDashboardId.value)
    switchDashboard(dashboards.value[(currentIndex+1+dashboards.value.length)%dashboards.value.length].id)
  }
}
function dashboardSnapshotFor(id,name){return {id,name,selectedCards:[...selectedCards.value],customCardIds:[...visibleCustomCardIds.value],cardOrder:[...cardOrder.value],minutes:minutes.value,iface:iface.value}}
async function createDashboard(){
  ensureDashboard()
  if(dashboards.value.length>=12)return
  syncActiveDashboard()
  const id=`panel-${Date.now()}-${Math.random().toString(36).slice(2,7)}`
  const current=dashboardSnapshot()
  dashboards.value.push({...current,id,name:`监控面板 ${dashboards.value.length+1}`,customCardIds:[],cardOrder:current.cardOrder.filter(key=>!key.startsWith('custom:'))})
  switchDashboard(id)
  await flushConfigSave()
}
async function renameDashboard(){
  const dashboard=activeDashboard.value;if(!dashboard)return
  try{const result=await ElMessageBox.prompt('输入监控面板名称','重命名监控面板',{inputValue:dashboard.name,inputPattern:/^.{1,40}$/,inputErrorMessage:'名称须为 1 至 40 个字符',confirmButtonText:'保存',cancelButtonText:'取消'});dashboard.name=result.value.trim();syncActiveDashboard();await flushConfigSave()}
  catch{/* 用户取消 */}
}
async function deleteDashboard(){
  if(dashboards.value.length<=1)return
  const current=activeDashboard.value
  try{await ElMessageBox.confirm(`删除面板“${current.name}”？其中的布局会被移除，自定义卡片不会删除。`,'删除监控面板',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'})}
  catch{return}
  dashboards.value=dashboards.value.filter(item=>item.id!==current.id)
  const next=dashboards.value[0];loadedDashboardId=current.id;switchDashboard(next.id)
  await flushConfigSave()
}
function openFullscreen(){fullscreenVisible.value=true}
function onFullscreenOpened(){nextTick(()=>{resize();charts.forEach(chart=>chart.resize());customCharts.forEach(chart=>chart.resize())})}
async function saveCustomScript(){
  const name=scriptDraft.value.name.trim(),script=scriptDraft.value.script.trim()
  if(!name||!script)return
  const emptyText=scriptDraft.value.emptyText.trim()||'等待有效数据'
  if(editingScriptId.value){const item=customScripts.value.find(row=>row.id===editingScriptId.value);if(item)Object.assign(item,{name,script,type:scriptDraft.value.type||'text',emptyText})}
  else {const id=`${Date.now()}-${Math.random().toString(36).slice(2,8)}`;customScripts.value.push({id,name,script,type:scriptDraft.value.type||'text',emptyText,enabled:true,output:'',error:'',exitStatus:null,timestamp:null});visibleCustomCardIds.value.push(id);cardOrder.value.push(`custom:${id}`)}
  scriptDraft.value={name:'',script:'',type:'text',emptyText:'等待有效数据'};editingScriptId.value=''
  await flushConfigSave()
}
function editCustomScript(item){editingScriptId.value=item.id;scriptDraft.value={name:item.name,script:item.script,type:item.type||'text',emptyText:item.emptyText||'等待有效数据'}}
async function removeCustomScript(id){
  const key=String(id),item=customScripts.value.find(row=>String(row.id)===key)
  customScripts.value=customScripts.value.filter(row=>String(row.id)!==key)
  visibleCustomCardIds.value=visibleCustomCardIds.value.filter(value=>String(value)!==key)
  dashboards.value=dashboards.value.map(dashboard=>({...dashboard,customCardIds:dashboard.customCardIds.filter(value=>String(value)!==key),cardOrder:dashboard.cardOrder.filter(value=>value!==`custom:${key}`)}))
  cardOrder.value=cardOrder.value.filter(value=>value!==`custom:${key}`)
  customHistory.delete(key)
  const chart=customCharts.get(key);chart?.dispose();customCharts.delete(key);customChartEls.delete(key)
  try{localStorage.removeItem(customHistoryKey(key))}catch{/* Runtime history is optional. */}
  if(editingScriptId.value===key){editingScriptId.value='';scriptDraft.value={name:'',script:'',type:'text',emptyText:'等待有效数据'}}
  if(item)nextTick(renderCustomCharts)
  await flushConfigSave()
}
function scriptValue(item){
  try { const value=JSON.parse(item.output||'{}');if(item.type==='text')return {text:value.text ?? value.value ?? item.output ?? '暂无输出'};if(item.type==='value')return {value:value.value ?? value.text ?? '—',unit:value.unit||''};if(item.type==='progress')return {value:Math.max(0,Math.min(100,Number(value.value)||0)),label:value.label||value.text||''};return {rows:Object.entries(value||{}).map(([key,current])=>({key,value:typeof current==='object'?JSON.stringify(current):String(current)}))} }
  catch { return item.type==='json'?{rows:[{key:'输出',value:item.output||'暂无输出'}]}:item.type==='text'?{text:item.output||'暂无输出'}:{value:'—',unit:'',label:'输出不是有效 JSON'} }
}
function setCustomChartRef(id,el){if(el)customChartEls.set(id,el);else customChartEls.delete(id)}
function chartData(item){try{return JSON.parse(item.output||'{}')}catch{return null}}
function customHistoryKey(id){return `doumi-monitor-script-history-v1:${props.accountId||'default'}:${id}`}
function readCustomHistory(item){
  if(customHistory.has(item.id))return customHistory.get(item.id)
  try { const cutoff=Date.now()-30*60*1000;const rows=JSON.parse(localStorage.getItem(customHistoryKey(item.id))||'[]');const valid=Array.isArray(rows)?rows.filter(row=>row&&Number(row.timestamp)>=cutoff):[];customHistory.set(item.id,valid);return valid } catch { const empty=[];customHistory.set(item.id,empty);return empty }
}
function recordCustomHistory(item){
  if(item.type!=='line')return
  const value=chartData(item);if(!value)return
  const values=[]
  if(value.value!=null&&value.value!==''&&Number.isFinite(Number(value.value)))values.push({name:item.name,value:Number(value.value)})
  for(const row of Array.isArray(value.series)?value.series:[]){
    const current=Number(row.value ?? (typeof row.data==='number'?row.data:NaN))
    if(row.value!=null&&row.value!==''&&Number.isFinite(current))values.push({name:row.name||item.name,value:current})
  }
  if(!values.length)return
  const rows=readCustomHistory(item);rows.push({timestamp:item.timestamp||Date.now(),values});const cutoff=Date.now()-30*60*1000;const next=rows.filter(row=>row.timestamp>=cutoff).slice(-900);customHistory.set(item.id,next)
  try{localStorage.setItem(customHistoryKey(item.id),JSON.stringify(next))}catch{/* History is optional. */}
}
function customChartEmptyText(item,value,hasData){
  if(item.error)return item.error
  if(hasData)return ''
  if(!item.timestamp)return item.emptyText||'等待有效数据'
  if(!value)return item.emptyText||'脚本未返回有效 JSON 数据'
  if(!hasData)return item.emptyText||'暂无有效数据'
  return ''
}
function renderCustomCharts(){
  customScripts.value.filter(item=>item.enabled&&['line','bar','pie'].includes(item.type)).forEach(item=>{
    const el=customChartEls.get(item.id);if(!el)return
    let chart=customCharts.get(item.id);if(!chart){chart=echarts.init(el);customCharts.set(item.id,chart)}
    const value=chartData(item),palette=['#409eff','#67c23a','#e6a23c','#f56c6c','#9b59b6','#16a085']
    const cutoff=Date.now()-minutes.value*60*1000
    const history=readCustomHistory(item).filter(row=>Number(row.timestamp)>=cutoff)
    const timeSeries=item.type==='line'&&history.length>0
    let sourceSeries=timeSeries?Array.from(new Set(history.flatMap(row=>row.values.map(valueRow=>valueRow.name)))).map(name=>({name,data:history.map(row=>{const point=row.values.find(valueRow=>valueRow.name===name);return [row.timestamp,point?.value??null]})})):Array.isArray(value?.series)?value.series:[{name:item.name,data:value?.data||value?.values||[]}]
    if(item.type==='line'&&!timeSeries&&value&&value.value!=null&&value.value!==''&&Number.isFinite(Number(value.value))){sourceSeries=[{name:item.name,data:[[item.timestamp||Date.now(),Number(value.value)]]}]}
    if(item.type==='line'&&!timeSeries&&value&&Array.isArray(value.series))sourceSeries=value.series.map(row=>row.value!=null&&row.value!==''&&Number.isFinite(Number(row.value))?{...row,data:[[item.timestamp||Date.now(),Number(row.value)]]}:row)
    const labels=Array.isArray(value?.labels)?value.labels:Array.isArray(value?.categories)?value.categories:[]
    const normalized=sourceSeries.map((row,index)=>{
      const data=[]
      ;(Array.isArray(row.data)?row.data:[]).forEach((point,pointIndex)=>{
        if(Array.isArray(point)){data.push(point);return}
        if(point&&typeof point==='object'){
          const label=point.name ?? point.x ?? point.label
          if(item.type!=='pie'&&label!=null&&!labels[pointIndex])labels[pointIndex]=String(label)
          data.push({value:point.value ?? point.y ?? point.v ?? 0,name:label})
        } else { if(item.type!=='pie'&&!labels[pointIndex])labels[pointIndex]=String(pointIndex+1); data.push({value:Number(point)||0}) }
      })
      return {name:row.name||item.name,type:item.type==='pie'?'pie':item.type==='bar'?'bar':'line',smooth:item.type==='line',showSymbol:item.type==='line',data,itemStyle:{color:palette[index%palette.length]}}
    })
    const hasData=normalized.some(row=>row.data.some(point=>Array.isArray(point)?point.length>1&&point[1]!=null:Number.isFinite(Number(point?.value??point))))
    const axis=item.type==='pie'?{}:timeSeries?{type:'time',name:value?.xAxisName||'时间',nameLocation:'middle',nameGap:28,axisLabel:{color:effectiveDark.value?'#c7d0df':'#687488',fontSize:10,hideOverlap:true},axisLine:{show:true,lineStyle:{color:effectiveDark.value?'#687488':'#aeb7c5'}},splitLine:{show:true,lineStyle:{color:effectiveDark.value?'#394456':'#e9edf3'}}}:{type:'category',data:labels.length?labels:normalized[0]?.data.map((_,index)=>String(index+1))||[],name:value?.xAxisName||'项目',nameLocation:'middle',nameGap:28,axisLabel:{color:effectiveDark.value?'#c7d0df':'#687488',fontSize:10},axisLine:{show:true,lineStyle:{color:effectiveDark.value?'#687488':'#aeb7c5'}},splitLine:{show:true,lineStyle:{color:effectiveDark.value?'#394456':'#e9edf3'}}}
    const valueAxis=item.type==='pie'?{}:{type:'value',name:value?.yAxisName||'数值',nameLocation:'middle',nameGap:38,axisLine:{show:true,lineStyle:{color:effectiveDark.value?'#687488':'#aeb7c5'}},axisLabel:{color:effectiveDark.value?'#c7d0df':'#687488',fontSize:10},splitLine:{show:true,lineStyle:{color:effectiveDark.value?'#394456':'#e9edf3'}}}
    const emptyText=customChartEmptyText(item,value,hasData)
    chart.setOption({animation:false,tooltip:{trigger:item.type==='pie'?'item':'axis'},legend:{show:normalized.length>1||item.type==='pie',textStyle:{color:effectiveDark.value?'#c7d0df':'#687488',fontSize:10}},grid:{left:56,right:14,top:30,bottom:48},xAxis:item.type==='pie'?undefined:axis,yAxis:item.type==='pie'?undefined:valueAxis,graphic:[{id:'custom-chart-empty-state',type:'text',left:'center',top:'middle',silent:true,invisible:!emptyText,style:{text:emptyText,fill:effectiveDark.value?'#aeb9cb':'#687488',fontSize:12,textAlign:'center'}}],series:normalized})
    chart.resize()
  })
}
function toggleExpanded(){expanded.value=!expanded.value;setWidth(expanded.value?maxWidth.value:defaultWidth)}
async function sampleCustomScripts(token,version){
  for(const item of customScripts.value){
    if(!item.enabled)continue
    try{const result=await runMonitorScript(token,item.script,effectiveInterval.value);if(disposed||version!==generation||token!==props.sessionToken)return;item.output=result.output||'';item.error=result.error||'';item.exitStatus=result.exitStatus;item.timestamp=Date.now();recordCustomHistory(item)}
    catch(e){if(disposed||version!==generation||token!==props.sessionToken)return;item.error=e?.message||'脚本采集失败';item.timestamp=Date.now()}
  }
}
function configPayload(){
  ensureDashboard()
  const current=dashboardSnapshot()
  const savedDashboards=dashboards.value.map(item=>item.id===activeDashboardId.value?current:{...item,selectedCards:[...item.selectedCards],customCardIds:[...item.customCardIds],cardOrder:[...item.cardOrder],minutes:Number(item.minutes)||5,iface:item.iface||''})
  return {revision:configRevision.value,activeDashboardId:activeDashboardId.value,customScripts:customScripts.value.map(({id,name,script,type,enabled,emptyText})=>({id,name,script,type,enabled,emptyText})),dashboards:savedDashboards,interval:interval.value}
}
let configSavePromise=null,configSaveQueued=false
async function persistConfig(){
  if(!configReady||!props.accountId)return
  if(configSavePromise){configSaveQueued=true;return configSavePromise}
  const accountId=props.accountId
  const payload=configPayload()
  configSavePromise=(async()=>{
    try{
      const saved=await monitorConfigStore.save(accountId,payload,configSourceId)
      if(String(accountId)===String(props.accountId))configRevision.value=Number(saved?.revision)||configRevision.value
    }catch(e){
      if(/版本冲突|提交版本/.test(e?.message||'')){error.value='监控配置已被其他操作更新，已重新加载最新配置';await reloadAccountSettings()}
      else error.value=`监控配置保存失败：${e?.message||'请求失败'}`
    }finally{
      configSavePromise=null
      if(configSaveQueued){configSaveQueued=false;queueConfigSave()}
    }
  })()
  return configSavePromise
}
function syncCurrentDashboardLayout(){
  if(!configReady)return
  const index=dashboards.value.findIndex(item=>item.id===activeDashboardId.value)
  if(index>=0)dashboards.value[index]=dashboardSnapshotFor(activeDashboardId.value,dashboards.value[index].name)
}
function queueConfigSave(){if(!configReady)return;syncCurrentDashboardLayout();clearTimeout(configSaveTimer);configSaveTimer=setTimeout(()=>{configSaveTimer=null;void persistConfig()},250)}
async function flushConfigSave(){
  if(!configReady||!props.accountId)return
  while(true){
    if(configSaveTimer){clearTimeout(configSaveTimer);configSaveTimer=null}
    if(configSavePromise){configSaveQueued=true;await configSavePromise;continue}
    configSaveQueued=false
    await persistConfig()
    if(!configSaveQueued&&!configSaveTimer)break
  }
}
watch([selectedCards,visibleCustomCardIds,interval,minutes,iface,cardOrder],queueConfigSave,{deep:true,flush:'sync'})
watch(()=>JSON.stringify(customScripts.value.map(({id,name,script,type,enabled,emptyText})=>({id,name,script,type,enabled,emptyText}))),queueConfigSave,{flush:'sync'})
function cardStyle(key){return {order:cardOrder.value.indexOf(key)>=0?cardOrder.value.indexOf(key):cardOrder.value.length+1}}
function dragCard(key,event){
  draggedCard.value=key
  if(event?.dataTransfer){event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',key)}
}
function clearDraggedCard(){draggedCard.value=''}
function dropCard(key,event){
  event?.preventDefault()
  const from=cardOrder.value.indexOf(draggedCard.value),to=cardOrder.value.indexOf(key)
  if(from<0||to<0||from===to){clearDraggedCard();return}
  const next=[...cardOrder.value],item=next.splice(from,1)[0];next.splice(to,0,item);cardOrder.value=next
  queueConfigSave()
  clearDraggedCard()
}
const status=computed(()=>{
  if(!props.sessionToken)return {label:'未连接',type:'info'}
  if(latest.value?.status==='DISABLED')return {label:'已禁用',type:'info'}
  if(paused.value||!props.active||!activated.value||!visible.value)return {label:'已暂停',type:'info'}
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
let timer,clock,observer,charts=[],detailChart=null,generation=0,inFlight=false,disposed=false,controller,drag=null,mounted=false,breakBefore=true

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
      const cutoff=Date.now()-minutes.value*60000
      points.value=[...points.value.filter(p=>p.timestamp>=cutoff&&p.timestamp!==data.timestamp),data].slice(-900)
      saveMonitorHistory(key,points.value)
    }
    if(iface.value&&!data.network?.interfaces?.some(i=>i.name===iface.value))iface.value=''
    render()
    await sampleCustomScripts(token,version)
    nextTick(renderCustomCharts)
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
  points.value=trimHistory(readMonitorHistory(historyKey()))
  saveMonitorHistory(historyKey(),points.value)
  latest.value=points.value.at(-1)||null;error.value='';iface.value='';serverInterval.value=5000
}
function trimHistory(history){
  const cutoff=Date.now()-minutes.value*60000
  return history.filter(point=>point.timestamp>=cutoff)
}
function pruneHistory(){
  points.value=trimHistory(points.value)
  saveMonitorHistory(historyKey(),points.value)
  latest.value=points.value.at(-1)||null
  render()
}
function ensureCharts(){
  const chartHost=cpuEl.value||netEl.value||gpuEl.value
  if(!mounted||!props.active||!chartHost?.clientWidth)return
  if(!charts.length){
    const muted=effectiveDark.value?'#aeb9cb':'#687488',line=effectiveDark.value?'#333e4f':'#e9edf3'
    const base={animation:false,backgroundColor:'transparent',tooltip:{trigger:'axis',confine:true},grid:{left:48,right:12,top:24,bottom:28},
      xAxis:{type:'time',axisLabel:{color:muted,fontSize:10,hideOverlap:true,formatter:value=>time(value)},axisLine:{lineStyle:{color:line}},splitNumber:3},
      yAxis:{type:'value',axisLabel:{color:muted,fontSize:10},splitLine:{lineStyle:{color:line}}},
      legend:{top:0,textStyle:{color:muted,fontSize:10}}}
    const cpu=echarts.init(cpuEl.value),net=echarts.init(netEl.value),gpu=echarts.init(gpuEl.value)
    cpu.setOption({...base,yAxis:{...base.yAxis,min:0,max:100},tooltip:{...base.tooltip,valueFormatter:value=>pct(value)},series:[
      {name:'使用率',type:'line',smooth:.35,showSymbol:false,connectNulls:false,color:'#409eff',areaStyle:{opacity:.08},data:[]},
      {name:'I/O 等待',type:'line',smooth:.35,showSymbol:false,connectNulls:false,color:'#e6a23c',data:[]}]})
    net.setOption({...base,yAxis:{...base.yAxis,axisLabel:{...base.yAxis.axisLabel,formatter:value=>value>=1048576?`${(value/1048576).toFixed(0)}M`:value>=1024?`${(value/1024).toFixed(0)}K`:`${value}`}},tooltip:{...base.tooltip,valueFormatter:value=>rate(value)},series:[
      {name:'下行',type:'line',smooth:.35,showSymbol:false,connectNulls:false,color:'#67c23a',data:[]},
      {name:'上行',type:'line',smooth:.35,showSymbol:false,connectNulls:false,color:'#409eff',data:[]}]})
    gpu.setOption({...base,yAxis:{...base.yAxis,min:0,max:100},tooltip:{...base.tooltip,valueFormatter:value=>pct(value)},series:[]})
    charts=[cpu,net,gpu]
  }
  charts.forEach(chart=>chart.resize())
}
const series=monitorSeries
function render(){
  renderDetail()
  if(!charts.length||!props.active)return
  const end=now.value,start=end-minutes.value*60000,rows=points.value.filter(p=>p.timestamp>=start)
  const n=row=>iface.value?row.network?.interfaces?.find(i=>i.name===iface.value):row.network
  charts[0].setOption({xAxis:{min:start,max:end},series:[{data:series(rows,p=>p.cpu?.usagePercent)},{data:series(rows,p=>p.cpu?.iowaitPercent)}]})
  charts[1].setOption({xAxis:{min:start,max:end},series:[{data:series(rows,p=>n(p)?.rxBytesPerSec)},{data:series(rows,p=>n(p)?.txBytesPerSec)}]})
  const gpus=latest.value?.gpus||[]
  charts[2].setOption({xAxis:{min:start,max:end},series:gpus.map((gpu,index)=>({name:`GPU ${gpu.index}`,type:'line',smooth:.35,showSymbol:false,connectNulls:false,color:['#9b59b6','#e67e22','#16a085','#e74c3c'][index%4],data:series(rows,p=>p.gpus?.find(item=>item.index===gpu.index)?.utilizationPercent)}))})
}
function detailSeriesData(){
  const rows=points.value, n=row=>iface.value?row.network?.interfaces?.find(i=>i.name===iface.value):row.network
  const gpuList=latest.value?.gpus||[]
  return detailMetric.value==='cpu'?[{name:'使用率',type:'line',smooth:.35,showSymbol:false,color:'#409eff',data:series(rows,p=>p.cpu?.usagePercent)},{name:'I/O 等待',type:'line',smooth:.35,showSymbol:false,color:'#e6a23c',data:series(rows,p=>p.cpu?.iowaitPercent)}]:detailMetric.value==='network'?[{name:'下行',type:'line',smooth:.35,showSymbol:false,color:'#67c23a',data:series(rows,p=>n(p)?.rxBytesPerSec)},{name:'上行',type:'line',smooth:.35,showSymbol:false,color:'#409eff',data:series(rows,p=>n(p)?.txBytesPerSec)}]:gpuList.map((gpu,index)=>({name:`GPU ${gpu.index}`,type:'line',smooth:.35,showSymbol:false,color:['#9b59b6','#e67e22','#16a085','#e74c3c'][index%4],data:series(rows,p=>p.gpus?.find(item=>item.index===gpu.index)?.utilizationPercent)}))
}
function renderDetail(){
  if(!detailChart||!detailVisible.value)return
  const end=now.value,start=end-minutes.value*60000
  detailChart.setOption({xAxis:{min:start,max:end},series:detailSeriesData()})
}
function openDetail(metric){
  detailMetric.value=metric;detailVisible.value=true
  nextTick(()=>{
    detailChart?.dispose()
    if(!detailChartEl.value)return
    detailChart=echarts.init(detailChartEl.value)
    detailChart.setOption({animation:false,tooltip:{trigger:'axis'},grid:{left:52,right:20,top:28,bottom:34},legend:{top:0},xAxis:{type:'time'},yAxis:{type:'value'},series:detailSeriesData()});detailChart.resize()
  })
}
function resizeDetail(){detailChart?.resize()}
function setWidth(value){width.value=Math.round(Math.min(maxWidth.value,Math.max(260,value)));try{localStorage.setItem('doumi-monitor-width-v2',String(width.value))}catch{/* 仅保存偏好 */}}
function showScrollFeedback(event){
  const element=event.currentTarget
  element.classList.add('is-scrolling')
  clearTimeout(element._scrollFeedbackTimer)
  element._scrollFeedbackTimer=setTimeout(()=>element.classList.remove('is-scrolling'),900)
}
function dragStart(event){if(event.button!==0)return;drag={x:event.clientX,width:width.value};event.currentTarget.setPointerCapture(event.pointerId);event.preventDefault()}
function dragMove(event){if(drag)setWidth(drag.width+event.clientX-drag.x)}
function dragEnd(event){drag=null;if(event.currentTarget.hasPointerCapture(event.pointerId))event.currentTarget.releasePointerCapture(event.pointerId)}
function resize(){
  maxWidth.value=Math.max(260,Math.min(760,(panel.value?.parentElement?.clientWidth||900)-260))
  if(width.value>maxWidth.value)width.value=maxWidth.value
  ensureCharts();render()
}
function visibility(){visible.value=!document.hidden;sync()}
async function reloadAccountSettings(){
  const accountId=props.accountId
  clearTimeout(configSaveTimer)
  configReady=false
  customHistory.clear()
  if(!props.accountId){customScripts.value=[];configReady=true;return}
  try{
    const saved=await monitorConfigStore.load(accountId)
    if(String(accountId)!==String(props.accountId))return
    configRevision.value=Number(saved?.revision)||0
    let config=saved
    if(!Array.isArray(config?.dashboards)||!Array.isArray(config?.customScripts)){
      if(configRevision.value>0)throw new Error('监控配置格式无效，请重新导入新格式的配置')
      config={revision:configRevision.value,activeDashboardId:'default',customScripts:[],dashboards:[{id:'default',name:'默认面板',selectedCards:[...cardOptions.map(item=>item.key)],customCardIds:[],cardOrder:[...cardOptions.map(item=>item.key)],minutes:5,iface:''}],interval:5000}
      const created=await monitorConfigStore.save(accountId,config,configSourceId);configRevision.value=Number(created?.revision)||configRevision.value
    }
    customScripts.value=config.customScripts.slice(0,20).map(item=>({...item,type:item.type,emptyText:item.emptyText||'等待有效数据',enabled:item.enabled!==false,output:'',error:'',exitStatus:null,timestamp:null}))
    dashboards.value=config.dashboards
    const preferredDashboard=saved.activeDashboardId||activeDashboardId.value
    activeDashboardId.value=dashboards.value.some(item=>item.id===preferredDashboard)?preferredDashboard:dashboards.value[0].id
    loadedDashboardId=activeDashboardId.value
    const activeLayout=dashboards.value.find(item=>item.id===activeDashboardId.value)
    selectedCards.value=Array.isArray(activeLayout?.selectedCards)?activeLayout.selectedCards:['cpu','memory','network','gpu','disk']
    visibleCustomCardIds.value=Array.isArray(activeLayout?.customCardIds)?activeLayout.customCardIds:customScripts.value.map(item=>item.id)
    cardOrder.value=Array.isArray(activeLayout?.cardOrder)?activeLayout.cardOrder:[...cardOptions.map(item=>item.key),...visibleCustomCardIds.value.map(id=>`custom:${id}`)]
    interval.value=Number(config.interval)||5000;minutes.value=Number(activeLayout?.minutes)||Number(config.minutes)||5;iface.value=activeLayout?.iface||config.iface||''
  }catch(e){error.value=`监控配置读取失败：${e?.message||'请求失败'}`}
  configReady=true
  nextTick(renderCustomCharts)
}
function onMonitorConfigChanged(event){
  if(event?.detail?.sourceId===configSourceId)return
  const changedAccountId=event?.detail?.accountId
  if(changedAccountId!=null&&String(changedAccountId)!==String(props.accountId))return
  clearTimeout(configSaveTimer)
  void reloadAccountSettings()
}
watch(()=>props.accountId,(value,oldValue)=>{if(value!==oldValue)reloadAccountSettings()},{flush:'post'})
watch(()=>props.sessionToken,()=>{restore();sync()},{flush:'post'})
watch([()=>props.active,paused,interval],sync,{flush:'post'})
watch(minutes,pruneHistory)
watch(minutes,()=>nextTick(renderCustomCharts))
watch(iface,render)
watch(customScripts,()=>nextTick(renderCustomCharts),{deep:true})
watch(effectiveDark,()=>{charts.forEach(c=>c.dispose());charts=[];nextTick(()=>{ensureCharts();render()})})
onMounted(async()=>{
  restore();await nextTick();mounted=true
  observer=new ResizeObserver(resize);observer.observe(panel.value);if(panel.value?.parentElement)observer.observe(panel.value.parentElement)
  document.addEventListener('visibilitychange',visibility)
  window.addEventListener(MONITOR_CONFIG_CHANGED,onMonitorConfigChanged)
  window.addEventListener('keydown',onDashboardShortcut)
  await reloadAccountSettings()
  clock=setInterval(()=>{now.value=Date.now();if(enabled.value)render()},1000)
  resize();sync();nextTick(renderCustomCharts)
})
onActivated(()=>{activated.value=true;sync()})
onDeactivated(()=>{activated.value=false;sync()})
onBeforeUnmount(()=>{disposed=true;generation++;clearTimeout(timer);clearTimeout(configSaveTimer);clearInterval(clock);controller?.abort();observer?.disconnect();document.removeEventListener('visibilitychange',visibility);window.removeEventListener(MONITOR_CONFIG_CHANGED,onMonitorConfigChanged);window.removeEventListener('keydown',onDashboardShortcut);charts.forEach(c=>c.dispose());charts=[];customCharts.forEach(c=>c.dispose());customCharts.clear();detailChart?.dispose()})
</script>

<style scoped>
.ssh-monitor{position:relative;flex-shrink:0;display:flex;flex-direction:column;min-height:0;border-right:1px solid var(--el-border-color-light);background:var(--doumi-surface,var(--el-bg-color-page));font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;font-size:12px}.ssh-monitor--fullscreen{width:100%!important;height:100%;min-width:0;border:0}
.monitor-scroll{flex:1;min-height:0}.toolbar{display:flex;justify-content:flex-start;align-items:center;flex-wrap:nowrap;gap:5px;padding:8px 6px;border-bottom:1px solid var(--el-border-color-light);flex-shrink:0}.toolbar strong{font-size:13px;white-space:nowrap}.dashboard-select{width:112px;flex:0 1 112px}.toolbar-actions{display:flex;align-items:center;gap:0;flex-shrink:0}.toolbar-actions .el-button{padding:5px}.card-options{display:grid;grid-template-columns:1fr 1fr;gap:4px 10px}.settings-controls{display:flex;gap:8px}.collapsed .toolbar{flex-direction:column;padding:8px 0;gap:6px}.collapsed .toolbar-actions{flex-direction:column}
.body{padding:12px;min-height:0;display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,280px),1fr));grid-auto-rows:min-content;align-content:start;gap:10px}.full-row{grid-column:1/-1}.controls,.status-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-height:32px}.status-row>span:last-child{font-size:11px;color:var(--el-text-color-secondary)}.notice-slot{min-height:18px}.notice-slot p{min-height:18px}.block{box-sizing:border-box;min-width:0;min-height:230px;background:var(--doumi-surface,var(--el-bg-color));border:1px solid var(--el-border-color);border-radius:8px;padding:12px;align-self:start;overflow:hidden;box-shadow:0 1px 3px #00000012}.block:hover{border-color:var(--el-color-primary-light-5);box-shadow:0 2px 8px #0000001c}.block-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:8px;padding-bottom:7px;border-bottom:1px solid var(--el-border-color-lighter)}.value{font-size:22px;font-weight:600}.hint{font-size:11px;color:var(--el-text-color-secondary);line-height:1.7;margin:0}.block .hint{margin-top:7px}.warning{font-size:11px;line-height:1.6;margin:0;color:var(--el-color-warning)}.chart{height:150px;width:100%;margin:8px 0}.loads{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.loads div{display:flex;flex-direction:column;gap:5px}.loads b{font-size:17px}.loads span{font-size:10px;color:var(--el-text-color-secondary)}.net-values{display:flex;gap:16px;font-size:15px;font-weight:600;margin-top:12px}.chart-actions{display:flex;align-items:center;gap:2px}.gpu-row+.gpu-row{margin-top:12px;padding-top:10px;border-top:1px solid var(--el-border-color-lighter)}.gpu-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.gpu-empty,.disk-empty{min-height:35px}.disk{min-width:0}.disk+.disk{margin-top:14px;padding-top:12px;border-top:1px solid var(--el-border-color-lighter)}.mount{min-width:0;overflow-wrap:anywhere;font-family:Consolas,monospace}.resize-handle{position:absolute;right:-4px;top:0;bottom:0;width:8px;cursor:col-resize;touch-action:none;z-index:5}.resize-handle:hover,.resize-handle:focus-visible{background:var(--el-color-primary-light-5);outline:none}
.detail-summary{margin-bottom:10px;color:var(--el-text-color-secondary)}.detail-chart{height:min(55vh,480px);min-height:280px;width:100%}.detail-gpus{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px;margin-top:12px}.detail-gpus>div{display:flex;flex-direction:column;gap:6px;padding:10px;border:1px solid var(--el-border-color-lighter);border-radius:6px}.disk-block{height:280px}.disk-list{height:220px;overflow:auto}.gpu-block{min-height:250px}.gpu-row{max-height:150px;overflow:auto}
.custom-script-output{margin:0;min-height:55px;max-height:150px;overflow:auto;white-space:pre-wrap;word-break:break-word;font:12px/1.5 Consolas,monospace;color:var(--el-text-color-primary);background:var(--el-fill-color-light);padding:8px;border-radius:4px}.script-editor-actions{display:flex;align-items:center;gap:8px;margin-bottom:10px}.script-setting-row{display:flex;align-items:center;gap:8px;padding:6px 0;border-top:1px solid var(--el-border-color-lighter)}.script-setting-name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.custom-script-chart{height:180px;width:100%;min-width:0}
.custom-script-text{min-height:55px;padding:12px 0;font-size:18px;line-height:1.5;word-break:break-word}
.custom-script-table{width:100%;border-collapse:collapse;font-size:12px}.custom-script-table th,.custom-script-table td{border-bottom:1px solid var(--el-border-color-lighter);padding:6px 8px;text-align:left;vertical-align:top;word-break:break-word}.custom-script-table th{width:34%;color:var(--el-text-color-secondary);font-weight:500;background:var(--el-fill-color-light)}
.custom-script-value{font-size:30px;font-weight:600;line-height:1.2;padding:12px 0}.custom-script-value small{font-size:13px;font-weight:400;margin-left:6px;color:var(--el-text-color-secondary)}.custom-script-error{color:var(--el-color-danger)}.script-framework{margin:6px 0 0;color:var(--el-text-color-secondary);font:11px/1.5 Consolas,monospace;white-space:pre-wrap;background:var(--el-fill-color-light);padding:7px;border-radius:4px}
.block{cursor:grab}.block:active{cursor:grabbing}.block.is-dragging{opacity:.45}
.monitor-fullscreen-host{height:calc(100vh - 82px);min-height:0;overflow:hidden}.monitor-fullscreen-dialog :deep(.el-dialog__body){height:calc(100vh - 70px);padding:0;overflow:hidden}.fullscreen-dialog-header{display:flex;align-items:center;justify-content:space-between;min-height:32px}.ssh-monitor--fullscreen .toolbar{padding-left:12px;padding-right:12px}
.monitor-settings-dialog :deep(.el-dialog){max-width:calc(100vw - 32px)}.monitor-settings-dialog :deep(.el-dialog__body){padding:16px 22px}.monitor-settings-columns{display:grid;grid-template-columns:minmax(260px,.9fr) minmax(0,1.4fr);gap:24px;height:min(70vh,660px);min-height:440px}.settings-column{box-sizing:border-box;min-width:0;min-height:0;padding:0 2px}.settings-column h3{display:flex;align-items:center;gap:8px;margin:0 0 12px;font-size:14px}.settings-column h3 small{font-size:12px;color:var(--el-text-color-secondary);font-weight:400}.monitor-options-column{overflow-y:auto;border-right:1px solid var(--el-border-color-lighter);padding-right:18px}.monitor-options-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:16px}.monitor-options-form :deep(.el-form-item){display:block;min-width:0;margin-bottom:12px}.monitor-options-form :deep(.el-form-item__label){display:block;line-height:20px;padding:0 0 5px}.monitor-options-form :deep(.el-form-item__content){min-width:0;margin-left:0!important}.monitor-options-form :deep(.el-form-item:nth-child(n+4)){grid-column:1/-1}.monitor-options-form :deep(.el-select){width:100%}.scripts-column{display:flex;flex-direction:column}.script-editor-form{flex:none}.script-editor-fields{display:grid;grid-template-columns:minmax(0,1fr) 160px;gap:14px}.script-editor-form :deep(.el-form-item){margin-bottom:8px}.script-editor-form :deep(.el-form-item__label){line-height:20px;padding-bottom:4px}.script-editor-form :deep(.el-form-item__content){margin-left:0!important}.script-editor-form :deep(textarea){resize:vertical}.script-editor-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:2px 0 10px}.script-card-list{flex:1;min-height:0;overflow-y:auto;border-top:1px solid var(--el-border-color-lighter);border-bottom:1px solid var(--el-border-color-lighter);padding:4px 8px 4px 0}.script-setting-row{display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--el-border-color-lighter)}.script-setting-name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.script-list-empty{padding:14px 0}.scripts-permission-note{flex:none;padding-top:8px}.monitor-settings-dialog :deep(.el-dialog__footer){padding-top:4px}
.disk-list,.gpu-row,.script-card-list,.monitor-options-column{scrollbar-width:thin;scrollbar-color:transparent transparent}.disk-list.is-scrolling,.gpu-row.is-scrolling,.script-card-list.is-scrolling,.monitor-options-column.is-scrolling{scrollbar-color:var(--el-text-color-secondary) transparent}.disk-list::-webkit-scrollbar,.gpu-row::-webkit-scrollbar,.script-card-list::-webkit-scrollbar,.monitor-options-column::-webkit-scrollbar{width:7px}.disk-list::-webkit-scrollbar-thumb,.gpu-row::-webkit-scrollbar-thumb,.script-card-list::-webkit-scrollbar-thumb,.monitor-options-column::-webkit-scrollbar-thumb{background:transparent;border-radius:4px}.disk-list.is-scrolling::-webkit-scrollbar-thumb,.gpu-row.is-scrolling::-webkit-scrollbar-thumb,.script-card-list.is-scrolling::-webkit-scrollbar-thumb,.monitor-options-column.is-scrolling::-webkit-scrollbar-thumb{background:var(--el-text-color-secondary)}
@media(max-width:560px){.body{grid-template-columns:minmax(0,1fr)}.full-row{grid-column:1}.block{min-height:190px}}
@media(max-width:760px){.monitor-settings-dialog :deep(.el-dialog__body){max-height:calc(100vh - 150px);overflow:auto;padding:14px}.monitor-settings-columns{grid-template-columns:minmax(0,1fr);height:auto;min-height:0}.monitor-options-column{border-right:0;border-bottom:1px solid var(--el-border-color-lighter);padding:0 0 12px}.scripts-column{min-height:480px}.script-card-list{max-height:220px;min-height:100px;flex:none}}
@media(max-width:480px){.monitor-options-form{grid-template-columns:minmax(0,1fr)}.monitor-options-form :deep(.el-form-item:nth-child(n)){grid-column:1}.script-editor-fields{grid-template-columns:minmax(0,1fr)}}
</style>
