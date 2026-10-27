<template>
  <div class="standalone-viewer">
    <SshFileViewer v-if="token && path" :key="`${token}:${path}`" :session-token="token" :account-id="accountId" :path="path" :name="name" :size="size" :encoding="encoding" standalone @close="closeWindow" />
    <el-empty v-else description="未指定有效的文件路径或会话，请从文件列表重新打开" />
  </div>
</template>

<script setup>
import { computed, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import SshFileViewer from '@/components/ssh/SshFileViewer.vue'
const route = useRoute()
const token = computed(() => String(route.query.token || route.query.sessionToken || ''))
const accountId = computed(() => Number(route.query.accountId) || undefined)
const path = computed(() => String(route.query.path || ''))
const name = computed(() => String(route.query.name || path.value.split('/').pop() || '文件'))
const size = computed(() => Number(route.query.size) || 0)
const encoding = computed(() => String(route.query.enc || ''))
watchEffect(() => { document.title = `${name.value} - 文件查看 - Doumi Shell` })
function closeWindow() { window.close() }
</script>

<style scoped>
.standalone-viewer { width:100vw; height:100dvh; overflow:hidden; }
</style>
