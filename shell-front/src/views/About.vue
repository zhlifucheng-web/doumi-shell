<template>
  <el-scrollbar class="page-scroll"><main class="about-page">
    <section class="about-card">
      <div class="about-heading">
        <div>
          <p class="eyebrow">Doumi Shell</p>
          <h2>关于</h2>
        </div>
        <el-tag type="info">v{{ appVersion }}</el-tag>
      </div>
      <p class="description">跨平台 SSH 服务器管理工具，支持终端、文件、监控、脚本、进程和存储管理。</p>
      <el-alert v-if="latest?.newer" type="success" :closable="false" show-icon>
        <template #title>发现新版本 v{{ latest.version }}</template>
        <template #default><el-link :href="latest.url" target="_blank" type="success">前往 Gitee 下载更新</el-link></template>
      </el-alert>
      <el-alert v-else-if="checked" type="info" :closable="false" show-icon title="当前已是最新版本" />
      <div class="about-actions">
        <el-button :loading="checking" @click="check">检查更新</el-button>
        <el-button type="primary" tag="a" :href="repositoryUrl" target="_blank">
          <el-icon><Star /></el-icon> 去 Gitee 点个 Star
        </el-button>
      </div>
      <el-descriptions :column="1" border size="small" class="about-meta">
        <el-descriptions-item label="版本">{{ appVersion }}</el-descriptions-item>
        <el-descriptions-item label="代码仓库"><el-link :href="repositoryUrl" target="_blank">{{ repositoryUrl }}</el-link></el-descriptions-item>
        <el-descriptions-item label="开源协议"><el-link href="/LICENSE" target="_blank">非盈利开源许可</el-link></el-descriptions-item>
      </el-descriptions>
      <p class="star-hint">项目对个人和非盈利组织开放修改与使用。欢迎给仓库点 Star，帮助项目被更多人发现。</p>
    </section>
  </main></el-scrollbar>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Star } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { appVersion, checkLatestRelease, getLatestRelease, onLatestReleaseChange, repositoryUrl } from '@/utils/versionRefresh'

const latest = ref(getLatestRelease())
const checking = ref(false)
const checked = ref(Boolean(latest.value))
let stopListening

async function check(showMessage = true) {
  checking.value = true
  const result = await checkLatestRelease()
  latest.value = result || getLatestRelease()
  checked.value = true
  checking.value = false
  if (showMessage) ElMessage.success(latest.value?.newer ? `发现新版本 v${latest.value.version}` : '当前已是最新版本')
}

onMounted(() => {
  stopListening = onLatestReleaseChange(() => { latest.value = getLatestRelease(); checked.value = true })
  check(false)
})
onBeforeUnmount(() => stopListening?.())
</script>

<style scoped>
.about-page{max-width:760px;margin:0 auto;padding:32px 20px;box-sizing:border-box}.about-card{background:var(--doumi-surface);border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:28px}.about-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.eyebrow{margin:0 0 6px;color:var(--el-color-primary);font-size:12px;letter-spacing:1px;text-transform:uppercase}.about-heading h2{margin:0;font-size:24px}.description,.star-hint{color:var(--el-text-color-secondary);line-height:1.8}.about-actions{display:flex;gap:10px;flex-wrap:wrap;margin:20px 0}.about-actions a{text-decoration:none}.about-meta{margin-top:18px}.star-hint{font-size:12px;margin:18px 0 0}
</style>
