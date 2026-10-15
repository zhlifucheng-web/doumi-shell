<template>
  <Teleport v-if="target" :to="target.parentElement">
    <el-scrollbar ref="scrollbar" class="editor-scrollbar" always :style="geometry" @scroll="scroll">
      <div :style="{width:`${size.width}px`,height:`${size.height}px`}" />
    </el-scrollbar>
  </Teleport>
</template>
<script setup>
// Keep the editor's scrolling model, selection and keyboard behavior, and use
// Element Plus thumbs as a synchronized overlay for both scroll directions.
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
const props=defineProps({target:{type:Object,default:null}})
const scrollbar=ref(null),size=ref({width:0,height:0}),geometry=ref({})
let resizeObserver,mutationObserver,frame,previous
function sync(){
  cancelAnimationFrame(frame)
  frame=requestAnimationFrame(async()=>{
    const el=props.target;if(!el)return
    geometry.value={left:`${el.offsetLeft}px`,top:`${el.offsetTop}px`,width:`${el.clientWidth}px`,height:`${el.clientHeight}px`}
    size.value={width:el.scrollWidth,height:el.scrollHeight}
    await nextTick();scrollbar.value?.update();scrollbar.value?.setScrollTop(el.scrollTop);scrollbar.value?.setScrollLeft(el.scrollLeft)
  })
}
function scroll({scrollTop,scrollLeft}){if(props.target){props.target.scrollTop=scrollTop;props.target.scrollLeft=scrollLeft}}
function cleanup(){previous?.classList.remove('doumi-editor-scroll');previous?.removeEventListener('scroll',sync);resizeObserver?.disconnect();mutationObserver?.disconnect();cancelAnimationFrame(frame)}
watch(()=>props.target,el=>{
  cleanup();previous=el;if(!el)return
  el.classList.add('doumi-editor-scroll');el.addEventListener('scroll',sync,{passive:true})
  resizeObserver=new ResizeObserver(sync);resizeObserver.observe(el)
  if(el.firstElementChild)resizeObserver.observe(el.firstElementChild)
  mutationObserver=new MutationObserver(sync);mutationObserver.observe(el,{subtree:true,childList:true,attributes:true,characterData:true})
  nextTick(sync)
},{flush:'post',immediate:true})
onBeforeUnmount(cleanup)
</script>
<style>
.doumi-editor-scroll{scrollbar-width:none!important}.doumi-editor-scroll::-webkit-scrollbar{display:none}
.editor-scrollbar{position:absolute!important;pointer-events:none;z-index:8}
.editor-scrollbar .el-scrollbar__bar{pointer-events:auto}.editor-scrollbar .el-scrollbar__wrap{pointer-events:none}
</style>
