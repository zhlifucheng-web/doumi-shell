<template>
  <Teleport v-if="target" :to="target.parentElement">
    <el-scrollbar ref="scrollbar" class="editor-scrollbar" always :style="geometry" @scroll="scroll" @pointerdown.capture="beginDrag">
      <div :style="{width:`${size.width}px`,height:`${size.height}px`}" />
    </el-scrollbar>
  </Teleport>
</template>
<script setup>
// Keep the editor's scrolling model, selection and keyboard behavior, and use
// Element Plus thumbs as a synchronized overlay for both scroll directions.
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
const props=defineProps({target:{type:Object,default:null},active:{type:Boolean,default:true}})
const scrollbar=ref(null),size=ref({width:0,height:0}),geometry=ref({})
let resizeObserver,mutationObserver,frame,releaseFrame,dragEndFrame,previous
let syncing=false,dragging=false,generation=0
function visible(el){return props.active&&el&&el.clientWidth>0&&el.clientHeight>0&&el.getClientRects().length>0}
function sync(){
  cancelAnimationFrame(frame)
  cancelAnimationFrame(releaseFrame)
  syncing=true
  const current=++generation
  frame=requestAnimationFrame(async()=>{
    const el=props.target;if(!visible(el))return
    geometry.value={left:`${el.offsetLeft}px`,top:`${el.offsetTop}px`,width:`${el.clientWidth}px`,height:`${el.clientHeight}px`}
    size.value={width:el.scrollWidth,height:el.scrollHeight}
    await nextTick()
    if(current!==generation||el!==props.target||!visible(el))return
    scrollbar.value?.update()
    if(!dragging){scrollbar.value?.setScrollTop(el.scrollTop);scrollbar.value?.setScrollLeft(el.scrollLeft)}
    // Native scroll events from programmatic updates arrive asynchronously.
    releaseFrame=requestAnimationFrame(()=>{if(current===generation)syncing=false})
  })
}
function scroll({scrollTop,scrollLeft}){
  const el=props.target;if(!visible(el))return
  if(syncing&&!dragging){
    if(scrollTop!==el.scrollTop||scrollLeft!==el.scrollLeft)sync()
    return
  }
  el.scrollTop=scrollTop;el.scrollLeft=scrollLeft
}
function beginDrag(event){
  if(event.button!==0)return
  cancelAnimationFrame(dragEndFrame);dragging=true
  document.addEventListener('pointerup',endDrag);document.addEventListener('pointercancel',endDrag)
}
function endDrag(){
  document.removeEventListener('pointerup',endDrag);document.removeEventListener('pointercancel',endDrag)
  // Let the final native scroll event from the drag arrive before resyncing.
  dragEndFrame=requestAnimationFrame(()=>{dragging=false;sync()})
}
function cleanup(){++generation;previous?.classList.remove('doumi-editor-scroll');previous?.removeEventListener('scroll',sync);resizeObserver?.disconnect();mutationObserver?.disconnect();cancelAnimationFrame(frame);cancelAnimationFrame(releaseFrame);cancelAnimationFrame(dragEndFrame);dragging=false;document.removeEventListener('pointerup',endDrag);document.removeEventListener('pointercancel',endDrag)}
watch(()=>props.target,el=>{
  cleanup();previous=el;if(!el)return
  el.classList.add('doumi-editor-scroll');el.addEventListener('scroll',sync,{passive:true})
  resizeObserver=new ResizeObserver(sync);resizeObserver.observe(el)
  if(el.firstElementChild)resizeObserver.observe(el.firstElementChild)
  mutationObserver=new MutationObserver(sync);mutationObserver.observe(el,{subtree:true,childList:true,attributes:true,characterData:true})
  nextTick(sync)
},{flush:'post',immediate:true})
watch(()=>props.active,sync,{flush:'post'})
onBeforeUnmount(cleanup)
</script>
<style>
.doumi-editor-scroll{scrollbar-width:none!important}.doumi-editor-scroll::-webkit-scrollbar{display:none}
.editor-scrollbar{position:absolute!important;pointer-events:none;z-index:8}
.editor-scrollbar .el-scrollbar__bar{pointer-events:auto}.editor-scrollbar .el-scrollbar__wrap{pointer-events:none}
</style>
