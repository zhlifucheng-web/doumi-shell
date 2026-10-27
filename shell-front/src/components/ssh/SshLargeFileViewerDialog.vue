<template>
  <el-dialog v-model="visible" title="文件查看" width="min(1400px, 96vw)" top="3vh" append-to-body destroy-on-close :close-on-click-modal="false" class="ssh-large-file-dialog">
    <div class="dialog-viewer">
      <SshFileViewer v-if="visible && file" :key="fullPath" :session-token="sessionToken" :path="fullPath" :name="file.name" :size="file.size" @close="visible = false" @standalone="openStandalone" @edit="switchToEditor" />
    </div>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import SshFileViewer from './SshFileViewer.vue'
const props = defineProps({ modelValue: Boolean, file: Object, dir: { type: String, default: '/' }, sessionToken: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue', 'switch-to-editor'])
const router = useRouter()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const fullPath = computed(() => props.file?.path || `${props.dir.replace(/\/$/, '')}/${props.file?.name || ''}`)
function openStandalone(encoding) {
  const route = router.resolve({ path: '/file-viewer', query: { token: props.sessionToken, path: fullPath.value, name: props.file.name, size: props.file.size, enc: encoding } })
  window.open(route.href, '_blank', 'width=1280,height=860,resizable=yes,scrollbars=yes')
}
function switchToEditor() { visible.value = false; emit('switch-to-editor', props.file) }
</script>

<style scoped>
.dialog-viewer { height:80dvh; min-height:300px; }
</style>
<style>
.ssh-large-file-dialog .el-dialog__body { padding:0; }
</style>
