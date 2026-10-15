<template>
  <div class="cron-editor">
    <div class="cron-input"><el-input :model-value="modelValue" aria-label="Cron 表达式" placeholder="如 0 0 9 * * *（每天 09:00）" @update:model-value="$emit('update:modelValue', $event)" /><el-button @click="open">交互设置</el-button></div>
    <div class="hint">可手动填写 5 位或 6 位表达式，也可通过交互设置生成。执行时间使用下方时区。</div>
    <el-dialog v-model="visible" title="交互设置定时规则" width="min(620px, 95vw)" append-to-body>
      <el-scrollbar max-height="65vh">
        <el-alert v-if="unrecognized" title="当前表达式使用了自定义规则。可以设置新规则后应用，取消则保留原表达式。" type="info" :closable="false" />
        <el-form label-position="top" class="rule-form">
          <el-form-item label="执行频率"><el-select v-model="rule.frequency" aria-label="执行频率"><el-option v-for="item in frequencies" :key="item.value" :value="item.value" :label="item.label" /></el-select></el-form-item>
          <template v-if="rule.frequency==='minutes' || rule.frequency==='hours'">
            <el-form-item :label="rule.frequency==='minutes' ? '每隔多少分钟' : '每隔多少小时'"><el-input-number v-model="rule.interval" :min="1" :max="rule.frequency==='minutes' ? 59 : 23" aria-label="执行间隔" /></el-form-item>
            <el-form-item v-if="rule.frequency==='hours'" label="在第几分钟执行"><el-input-number v-model="rule.minute" :min="0" :max="59" aria-label="执行分钟" /></el-form-item>
            <el-form-item label="在第几秒执行"><el-input-number v-model="rule.second" :min="0" :max="59" aria-label="执行秒数" /></el-form-item>
            <p class="hint">按 Cron 字段循环：分钟间隔在每小时重新计数，小时间隔在每天重新计数。下方预览可确认实际执行时间。</p>
          </template>
          <el-form-item v-else label="执行时间"><el-time-picker v-model="rule.time" value-format="HH:mm:ss" format="HH:mm:ss" :clearable="false" aria-label="执行时间" /></el-form-item>
          <el-form-item v-if="rule.frequency==='weekly'" label="每周执行日"><el-checkbox-group v-model="rule.weekdays"><el-checkbox v-for="day in weekdays" :key="day.value" :label="day.value">{{ day.label }}</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item v-if="rule.frequency==='monthly'" label="每月执行日期"><el-select v-model="rule.monthDays" multiple placeholder="请选择日期" aria-label="每月执行日期"><el-option v-for="day in 31" :key="day" :value="day" :label="`${day} 日`" /></el-select><div class="hint">当月不存在所选日期时跳过，例如 31 日不会在四月执行。</div></el-form-item>
        </el-form>
        <p>生成表达式：<code>{{ expression || '请选择完整的执行规则' }}</code></p>
        <p class="hint">时区：{{ zone }} · Doumi Shell 运行期间自动执行，关闭浏览器不影响任务。</p>
        <el-alert v-if="previewError" :title="previewError" type="error" :closable="false" />
        <div v-loading="previewing" class="next-runs"><strong>未来 5 次执行时间</strong><ol v-if="dates.length"><li v-for="date in dates" :key="date">{{ date }}</li></ol><p v-else class="hint">{{ expression ? '正在获取预览…' : '至少选择一个有效日期，并填写完整时间。' }}</p></div>
      </el-scrollbar>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :disabled="!expression || previewing || !!previewError || !dates.length" @click="apply">应用表达式</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { previewCron } from '@/api/sshConsole'
import { buildCronRule, defaultCronRule, parseCronRule } from '@/utils/cronRule'
const props=defineProps({modelValue:{type:String,default:''},zone:{type:String,default:'Asia/Shanghai'}})
const emit=defineEmits(['update:modelValue'])
const visible=ref(false),unrecognized=ref(false),rule=ref(defaultCronRule()),dates=ref([]),previewing=ref(false),previewError=ref('')
const frequencies=[{value:'minutes',label:'每隔若干分钟'},{value:'hours',label:'每隔若干小时'},{value:'daily',label:'每天'},{value:'weekly',label:'每周'},{value:'monthly',label:'每月'}]
const weekdays=[{value:1,label:'周一'},{value:2,label:'周二'},{value:3,label:'周三'},{value:4,label:'周四'},{value:5,label:'周五'},{value:6,label:'周六'},{value:0,label:'周日'}]
const expression=computed(()=>buildCronRule(rule.value))
let timer,version=0
function invalidate(){clearTimeout(timer);version++;previewing.value=false}
function open(){const parsed=parseCronRule(props.modelValue);rule.value=parsed||defaultCronRule();unrecognized.value=!!props.modelValue?.trim()&&!parsed;visible.value=true}
watch([visible,expression,()=>props.zone],()=>{
  invalidate();dates.value=[];previewError.value=''
  if(!visible.value || !expression.value)return
  const current=version,cron=expression.value,zone=props.zone;previewing.value=true
  timer=setTimeout(async()=>{
    try{const data=await previewCron(cron,zone);if(current===version)dates.value=data}
    catch(error){if(current===version)previewError.value=error?.message||'无法获取执行时间，请检查规则和时区'}
    finally{if(current===version)previewing.value=false}
  },300)
})
function apply(){if(!expression.value || previewing.value || previewError.value || !dates.value.length)return;emit('update:modelValue',expression.value);visible.value=false}
onBeforeUnmount(invalidate)
</script>
<style scoped>
.cron-editor{width:100%}.cron-input{display:flex;gap:10px}.cron-input>.el-input{flex:1;min-width:0}.hint{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7}.rule-form{margin-top:16px}.rule-form .el-select{width:100%}.rule-form .el-checkbox-group{display:flex;flex-wrap:wrap;gap:0 12px}.next-runs{min-height:90px;margin-top:18px}.next-runs li{line-height:1.8}code{overflow-wrap:anywhere}
</style>
