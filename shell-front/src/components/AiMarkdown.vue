<template>
  <div class="ai-markdown" @click="copyCode" v-html="html" />
  <span v-if="copyError" class="md-copy-error" role="status">{{ copyError }}</span>
</template>
<script setup>
import { computed, ref } from 'vue'
import { renderAiMarkdown } from '@/utils/aiMarkdown'
const props = defineProps({ text: { type: String, default: '' } })
const html = computed(() => renderAiMarkdown(props.text))
const copyError = ref('')
async function copyCode(event) {
  const button = event.target.closest?.('.md-copy-code')
  if (!button || !event.currentTarget.contains(button)) return
  const code = button.parentElement.querySelector('pre code')?.textContent || ''
  copyError.value = ''
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(code)
    else {
      const field = document.createElement('textarea')
      field.value = code; field.style.position = 'fixed'; field.style.opacity = '0'
      document.body.appendChild(field)
      try { field.select(); if (!document.execCommand('copy')) throw new Error('copy failed') } finally { field.remove() }
    }
    button.textContent = '已复制'
  } catch { copyError.value = '复制失败，请选择代码后手动复制' }
}
</script>
<style scoped>
.ai-markdown{line-height:1.7;overflow-wrap:anywhere}.ai-markdown :deep(> :first-child){margin-top:0}.ai-markdown :deep(> :last-child){margin-bottom:0}.ai-markdown :deep(p){margin:8px 0}.ai-markdown :deep(h1),.ai-markdown :deep(h2),.ai-markdown :deep(h3),.ai-markdown :deep(h4){line-height:1.4;margin:16px 0 8px}.ai-markdown :deep(h1){font-size:1.5em}.ai-markdown :deep(h2){font-size:1.3em}.ai-markdown :deep(h3){font-size:1.15em}.ai-markdown :deep(ul),.ai-markdown :deep(ol){padding-left:24px}.ai-markdown :deep(li+li){margin-top:4px}.ai-markdown :deep(blockquote){margin:12px 0;padding:4px 12px;border-left:3px solid var(--el-color-primary);background:var(--el-fill-color);color:var(--el-text-color-regular)}.ai-markdown :deep(code){font-family:Consolas,'Courier New',monospace;font-size:.92em;background:var(--el-fill-color-dark);border-radius:4px;padding:2px 5px}.ai-markdown :deep(pre){overflow:auto;max-height:360px;padding:34px 14px 14px;background:var(--el-bg-color);border:1px solid var(--el-border-color);border-radius:6px;white-space:pre;word-break:normal;font-family:Consolas,'Courier New',monospace}.ai-markdown :deep(pre code){padding:0;background:none;border-radius:0;white-space:pre}.ai-markdown :deep(.md-code-block){position:relative}.ai-markdown :deep(.md-copy-code){position:absolute;right:8px;top:7px;border:1px solid var(--el-border-color);border-radius:4px;background:var(--el-bg-color);color:var(--el-text-color-regular);font-size:11px;padding:3px 8px;cursor:pointer}.ai-markdown :deep(table){display:block;max-width:100%;overflow:auto;border-collapse:collapse;margin:12px 0}.ai-markdown :deep(th),.ai-markdown :deep(td){border:1px solid var(--el-border-color);padding:6px 12px;min-width:60px}.ai-markdown :deep(th){background:var(--el-fill-color);font-weight:600}.ai-markdown :deep(a){color:var(--el-color-primary);text-decoration:underline}.ai-markdown :deep(hr){border:0;border-top:1px solid var(--el-border-color);margin:16px 0}.ai-markdown :deep(img){max-width:100%;height:auto}.md-copy-error{color:var(--el-color-danger);font-size:12px}
</style>
