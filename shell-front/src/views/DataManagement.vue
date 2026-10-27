<template>
  <section class="data-management">
    <div class="management-heading">
      <h2>数据管理</h2>
      <el-tabs v-model="view">
        <el-tab-pane label="配置导入导出" name="backup" />
        <el-tab-pane label="定时远程备份" name="remote-backup" />
        <el-tab-pane label="清除数据" name="cleanup" />
      </el-tabs>
    </div>
    <div class="management-content">
      <Configuration v-show="view==='backup'" />
      <RemoteBackup v-if="remoteBackupVisited" v-show="view==='remote-backup'" />
      <DataCleanup v-if="cleanupVisited" v-show="view==='cleanup'" />
    </div>
  </section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Configuration from './Configuration.vue'
import RemoteBackup from './RemoteBackup.vue'
import DataCleanup from './DataCleanup.vue'

const route = useRoute(), router = useRouter()
const cleanupVisited = ref(false)
const remoteBackupVisited = ref(false)

const view = computed({
  get: () => {
    const v = route.query.view
    if (v === 'cleanup' || v === 'remote-backup') return v
    return 'backup'
  },
  set: value => {
    router.replace({
      path: '/data-management',
      query: value === 'backup' ? {} : { view: value }
    })
  }
})

watch(view, value => {
  if (value === 'cleanup') cleanupVisited.value = true
  if (value === 'remote-backup') remoteBackupVisited.value = true
}, { immediate: true })
</script>
<style scoped>
.data-management{display:flex;flex-direction:column;height:100%;min-height:0}.management-heading{padding:16px 24px 0}.management-heading h2{margin:0 0 8px;font-size:20px}.management-content{flex:1;min-height:0}
</style>
