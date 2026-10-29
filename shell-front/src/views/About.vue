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
      <el-alert v-if="checkError" type="warning" :closable="false" show-icon title="检查更新失败，无法确认是否为最新版本">
        <template #default>{{ checkError }}</template>
      </el-alert>
      <el-alert v-if="latest?.newer" type="success" :closable="false" show-icon>
        <template #title>{{ checkError ? '上次检测到' : '发现' }}新版本 v{{ latest.version }}</template>
        <template #default>
          <el-link :href="latest.url" target="_blank" rel="noopener noreferrer" type="success">前往 GitHub 查看版本说明</el-link>
          <div class="platform-downloads">
            <el-link :href="latest.windowsUrl" target="_blank" rel="noopener noreferrer" type="primary">下载 Windows MSI</el-link>
            <el-link :href="latest.linuxUrl" target="_blank" rel="noopener noreferrer" type="primary">下载 Linux x64 包</el-link>
          </div>
        </template>
      </el-alert>
      <el-alert v-else-if="checked && !checkError && latest" type="info" :closable="false" show-icon :title="`当前 v${appVersion}，未发现更新版本（最新发布 v${latest.version}）`" />
      <div class="about-actions">
        <el-button :loading="checking" @click="check()">检查更新</el-button>
        <el-button tag="a" :href="releaseUrl" target="_blank" rel="noopener noreferrer">打开 GitHub 下载页面</el-button>
        <el-button type="primary" tag="a" :href="repositoryUrl" target="_blank">
          <el-icon><Star /></el-icon> 去 GitHub 点个 Star
        </el-button>
      </div>
      <div class="upgrade-instructions">
        <strong>如何升级</strong>
        <p>支持的正式安装包可使用下方“一键更新”。首次安装带更新器的版本、容器或托管服务，请在部署机器上按对应方式升级。通过局域网访问时，仅刷新浏览器无法升级服务器程序。</p>
        <p>Windows：在系统托盘退出旧版本，下载并运行新 MSI 安装包，然后重新启动。</p>
        <p>Linux：停止旧进程，用新包替换程序文件，保留原数据目录及配置，使用 Java 17 重新启动。</p>
        <p>升级前请等待文件传输和脚本任务结束。程序升级后刷新页面；页面自动刷新仅用于加载已安装的新程序资源。</p>
      </div>
      <UpdatePanel :latest="latest" @settings-saved="check(false)" />
      <el-descriptions :column="1" border size="small" class="about-meta">
        <el-descriptions-item label="版本">{{ appVersion }}</el-descriptions-item>
        <el-descriptions-item label="代码仓库"><el-link :href="repositoryUrl" target="_blank">{{ repositoryUrl }}</el-link></el-descriptions-item>
        <el-descriptions-item label="使用许可"><el-link href="https://polyformproject.org/licenses/noncommercial/1.0.0/" target="_blank" rel="noopener noreferrer">PolyForm Noncommercial 1.0.0</el-link></el-descriptions-item>
      </el-descriptions>
      <p class="star-hint">允许非商业用途的使用、修改与分发，商业用途不在许可授权范围内；这是非商业源码许可，不属于 OSI 定义的开源许可。欢迎给仓库点 Star，帮助项目被更多人发现。</p>
    </section>
  </main></el-scrollbar>
</template>

<script setup>
import { onActivated, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { Star } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { appVersion, repositoryUrl, releaseUrl } from '@/utils/versionRefresh'
import UpdatePanel from '@/components/update/UpdatePanel.vue'
import { useReleaseStore } from '@/stores/release'

const releaseStore = useReleaseStore()
const { latest, checking, error: checkError } = storeToRefs(releaseStore)
const checked = ref(Boolean(latest.value))

async function check(showMessage = true) {
  checked.value = false
  try {
    await releaseStore.check({ refresh: true })
    checked.value = true
    if (showMessage) ElMessage.success(latest.value.newer ? `发现新版本 v${latest.value.version}，请下载安装` : '未发现更新版本')
  } catch (error) {
    checkError.value = error.message || '无法完成版本检查，请打开 GitHub 下载页面查看'
    if (showMessage) ElMessage.warning('检查更新失败，请查看页面提示或直接打开 GitHub 下载页面')
  }
}

onMounted(() => { checked.value = Boolean(latest.value) })
onActivated(() => { void releaseStore.check().then(() => { checked.value = true }).catch(() => {}) })
</script>

<style scoped>
.about-page{max-width:760px;margin:0 auto;padding:32px 20px;box-sizing:border-box}.about-card{background:var(--doumi-surface);border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:28px}.about-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.eyebrow{margin:0 0 6px;color:var(--el-color-primary);font-size:12px;letter-spacing:1px;text-transform:uppercase}.about-heading h2{margin:0;font-size:24px}.description,.star-hint{color:var(--el-text-color-secondary);line-height:1.8}.about-actions{display:flex;gap:10px;flex-wrap:wrap;margin:20px 0}.about-actions a{text-decoration:none}.about-meta{margin-top:18px}.star-hint{font-size:12px;margin:18px 0 0}
</style>
<style scoped>
.platform-downloads{display:flex;gap:18px;flex-wrap:wrap;margin-top:10px}.upgrade-instructions{margin:18px 0;padding:14px;background:var(--el-fill-color-light);border-radius:6px;color:var(--el-text-color-regular);font-size:13px;line-height:1.8}.upgrade-instructions p{margin:6px 0}
</style>
