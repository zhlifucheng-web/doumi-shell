<template>
  <section class="help-page">
    <header class="help-heading">
      <div><p>Doumi Shell · 帮助中心</p><h2>使用教程</h2><span>从第一个连接，到日常巡检与 AI 协作</span></div>
      <el-button @click="$router.push('/terminal')">返回终端</el-button>
    </header>
    <div class="help-layout">
      <nav class="help-directory" aria-label="教程目录">
        <strong>目录</strong>
        <button v-for="item in directory" :key="item.id" type="button" @click="goTo(item.id)">{{ item.title }}</button>
      </nav>
      <el-scrollbar class="help-scroll" ref="scrollbar">
        <article ref="article" class="help-article" v-html="html" />
      </el-scrollbar>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import guide from '../../../docs/user-guide.md?raw'

const article = ref(), scrollbar = ref()
const markdown = new MarkdownIt({ html: false, linkify: true })
const tokens = markdown.parse(guide, {})
const directory = []
for (let index = 0; index < tokens.length; index++) {
  if (tokens[index].type === 'heading_open' && tokens[index].tag === 'h2') {
    const id = `help-section-${directory.length}`
    tokens[index].attrSet('id', id)
    directory.push({ id, title: tokens[index + 1].content })
  }
  if (tokens[index].type === 'inline') {
    for (const child of tokens[index].children || []) {
      if (child.type === 'link_open' && /^https?:\/\//i.test(child.attrGet('href') || '')) {
        child.attrSet('target', '_blank')
        child.attrSet('rel', 'noopener noreferrer')
      }
    }
  }
}
const html = DOMPurify.sanitize(markdown.renderer.render(tokens, markdown.options, {}), { ADD_ATTR: ['target'] })
function goTo(id) {
  const target = article.value?.querySelector(`#${id}`)
  if (target) scrollbar.value?.setScrollTop(target.offsetTop - article.value.offsetTop)
}
</script>

<style scoped>
.help-page{height:100%;max-width:1280px;margin:0 auto;padding:24px;box-sizing:border-box;display:flex;flex-direction:column;gap:24px;color:var(--el-text-color-primary)}
.help-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-shrink:0}.help-heading p{margin:0 0 8px;color:var(--el-color-primary);font-size:12px}.help-heading h2{margin:0 0 8px;font-size:26px}.help-heading span{color:var(--el-text-color-secondary);font-size:14px}
.help-layout{display:flex;gap:24px;min-height:0;flex:1}.help-directory{width:200px;flex-shrink:0;overflow:auto;display:flex;flex-direction:column;align-self:flex-start;max-height:100%;box-sizing:border-box;padding:16px 12px;border:1px solid var(--el-border-color);border-radius:10px;background:var(--doumi-surface)}.help-directory strong{font-size:13px;padding:0 10px 10px}.help-directory button{text-align:left;border:0;border-radius:6px;background:transparent;color:var(--el-text-color-regular);padding:10px;font:inherit;font-size:13px;cursor:pointer}.help-directory button:hover,.help-directory button:focus-visible{background:var(--el-fill-color-light);color:var(--el-color-primary)}
.help-scroll{min-width:0;flex:1;border:1px solid var(--el-border-color);border-radius:10px;background:var(--doumi-surface)}.help-article{padding:24px 32px;line-height:1.85;overflow-wrap:anywhere;font-size:14px}.help-article :deep(h1){display:none}.help-article :deep(h2){margin:32px 0 12px;padding-bottom:10px;border-bottom:1px solid var(--el-border-color-light);font-size:20px}.help-article :deep(p){margin:10px 0}.help-article :deep(ul),.help-article :deep(ol){padding-left:24px}.help-article :deep(li+li){margin-top:6px}.help-article :deep(table){display:block;max-width:100%;overflow:auto;border-collapse:collapse;margin:16px 0}.help-article :deep(th),.help-article :deep(td){border:1px solid var(--el-border-color);padding:8px 12px;text-align:left}.help-article :deep(th){background:var(--el-fill-color-light)}.help-article :deep(code){font-family:Consolas,monospace;font-size:.93em;background:var(--el-fill-color);padding:2px 5px;border-radius:4px}.help-article :deep(a){color:var(--el-color-primary)}
@media(max-width:760px){.help-page{padding:16px;gap:16px}.help-layout{flex-direction:column;gap:12px}.help-directory{width:100%;max-height:150px;flex-direction:row;flex-wrap:wrap;align-self:stretch;flex-shrink:0;gap:4px}.help-directory strong{width:100%;padding-bottom:2px}.help-directory button{padding:6px}.help-article{padding:16px}.help-heading h2{font-size:22px}}
</style>
