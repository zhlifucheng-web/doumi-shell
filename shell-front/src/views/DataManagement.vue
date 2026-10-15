<template>
  <section class="data-management">
    <div class="management-heading"><h2>数据管理</h2><el-tabs v-model="view"><el-tab-pane label="配置导入导出" name="backup" /><el-tab-pane label="清除数据" name="cleanup" /></el-tabs></div>
    <div class="management-content"><Configuration v-show="view==='backup'" /><DataCleanup v-if="cleanupVisited" v-show="view==='cleanup'" /></div>
  </section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Configuration from './Configuration.vue'
import DataCleanup from './DataCleanup.vue'
const route=useRoute(),router=useRouter(),cleanupVisited=ref(false)
const view=computed({get:()=>route.query.view==='cleanup' ? 'cleanup' : 'backup',set:value=>router.replace({path:'/data-management',query:value==='cleanup' ? {view:'cleanup'} : {}})})
watch(view,value=>{if(value==='cleanup')cleanupVisited.value=true},{immediate:true})
</script>
<style scoped>
.data-management{display:flex;flex-direction:column;height:100%;min-height:0}.management-heading{padding:16px 24px 0}.management-heading h2{margin:0 0 8px;font-size:20px}.management-content{flex:1;min-height:0}
</style>
