<template>
  <el-scrollbar class="page-scroll"><div class="cleanup"><h2>清除数据</h2>
    <p>配置按创建时间筛选，执行历史按开始时间筛选。正在执行的数据及其配置、仍被保留数据引用的记录会受到保护。</p>
    <el-alert title="清除后无法恢复，建议先到“配置导入导出”备份配置。" type="warning" :closable="false" />
    <el-form label-position="top" class="form">
      <el-form-item label="选择数据表"><el-checkbox-group v-model="selected"><el-checkbox v-for="table in tables" :key="table.name" :label="table.name">{{ table.label }}（{{ table.timeColumn }}）</el-checkbox></el-checkbox-group></el-form-item>
      <el-form-item label="清除时间范围"><el-switch v-model="allTime" active-text="全部时间" /><el-date-picker v-if="!allTime" v-model="range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" range-separator="至" style="margin-top:12px;width:100%" /></el-form-item>
      <el-button type="primary" :loading="busy" :disabled="!selected.length || (!allTime && !range?.length)" @click="preview">预览清除范围</el-button>
    </el-form>
    <template v-if="plan">
      <h3>清除预览</h3>
      <el-table :data="plan.rows"><el-table-column prop="label" label="表" /><el-table-column prop="matched" label="匹配条数" /><el-table-column prop="protected" label="受保护条数" /><el-table-column prop="deletable" label="将清除条数" /></el-table>
      <p>未选择的表及时间范围外的数据不会被级联清除。预览有效期为 5 分钟，数据变化后需重新预览。</p>
      <el-button type="danger" :loading="busy" :disabled="!deleteCount" @click="clear">清除 {{ deleteCount }} 条数据</el-button>
    </template>
  </div></el-scrollbar>
</template>
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cleanupTables, previewCleanup, clearData } from '@/api/sshConsole'
const tables=ref([]),selected=ref(['ssh_script_execution']),allTime=ref(false),range=ref(null),plan=ref(null),busy=ref(false)
const deleteCount=computed(()=>plan.value?.rows.reduce((sum,row)=>sum+row.deletable,0)||0)
watch([selected,allTime,range],()=>{plan.value=null},{deep:true})
onMounted(async()=>{tables.value=await cleanupTables()})
async function preview(){busy.value=true;try{plan.value=await previewCleanup({tables:selected.value,allTime:allTime.value,from:range.value?.[0]?.toISOString(),to:range.value?.[1]?.toISOString()})}finally{busy.value=false}}
async function clear(){
  try{await ElMessageBox.confirm(`将永久清除 ${deleteCount.value} 条数据，是否继续？`,'确认清除',{type:'warning',confirmButtonText:'永久清除',cancelButtonText:'取消'})}catch{return}
  busy.value=true
  try{await clearData(plan.value.token);plan.value=null;ElMessage.success('数据已清除');window.dispatchEvent(new Event('doumi-configuration-imported'))}
  catch{plan.value=null}finally{busy.value=false}
}
</script>
<style scoped>.cleanup{min-height:100%;box-sizing:border-box;padding:24px;max-width:1000px;margin:auto}p{color:var(--el-text-color-secondary);line-height:1.7}.form{margin:24px 0}.el-checkbox-group{display:flex;flex-direction:column}</style>
