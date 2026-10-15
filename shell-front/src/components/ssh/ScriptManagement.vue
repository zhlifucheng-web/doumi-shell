<template>
  <section class="script-management">
    <el-tabs v-model="view" class="management-tabs">
      <el-tab-pane label="脚本与定时任务" name="tasks" />
      <el-tab-pane label="执行历史" name="history" />
    </el-tabs>
    <div class="management-content">
      <SshScriptPanel v-show="view==='tasks'" :host-id="hostId" :account-id="accountId" :session-token="sessionToken" :opening-session="openingSession" @request-session="$emit('request-session')" />
      <ScriptExecutionHistory v-if="historyVisited" v-show="view==='history'" :active="active && activated && view==='history'" />
    </div>
  </section>
</template>
<script setup>
import { computed, onActivated, onDeactivated, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SshScriptPanel from './SshScriptPanel.vue'
import ScriptExecutionHistory from './ScriptExecutionHistory.vue'
defineProps({hostId:{type:Number,default:null},accountId:{type:Number,default:null},sessionToken:{type:String,default:''},openingSession:Boolean,active:Boolean})
defineEmits(['request-session'])
const route=useRoute(),router=useRouter(),activated=ref(true),historyVisited=ref(false)
const view=computed({get:()=>route.query.view==='history' ? 'history' : 'tasks',set:value=>router.replace({path:'/scripts',query:value==='history' ? {view:'history'} : {}})})
watch(view,value=>{if(value==='history')historyVisited.value=true},{immediate:true})
onActivated(()=>{activated.value=true})
onDeactivated(()=>{activated.value=false})
</script>
<style scoped>
.script-management{display:flex;flex-direction:column;height:100%;min-height:0}.management-tabs{padding:0 16px;flex-shrink:0}.management-content{flex:1;min-height:0}
</style>
