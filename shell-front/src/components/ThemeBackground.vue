<template>
  <el-dialog v-model="visible" title="自定义主题背景" width="min(560px, 92vw)" @open="open"><el-scrollbar max-height="65vh">
    <el-form label-position="top">
      <el-form-item label="背景颜色"><el-color-picker v-model="draft.color" :predefine="['#eaf0f8','#dcece5','#f6e8df','#202b40','#30283f']" /></el-form-item>
      <el-form-item label="背景图片（保存在当前浏览器）">
        <input ref="fileInput" type="file" accept="image/png,image/jpeg,image/webp" aria-label="选择主题背景图片" @change="selectImage" />
        <el-button v-if="draft.image" text @click="draft.image=''">移除图片</el-button>
      </el-form-item>
      <el-form-item label="界面不透明度"><el-slider v-model="draft.opacity" :min="60" :max="100" show-input /></el-form-item>
    </el-form>
    <div class="preview" :style="{backgroundColor:draft.color,backgroundImage:draft.image ? `url(${draft.image})` : 'none'}">
      <div :style="{backgroundColor:`color-mix(in srgb, var(--el-bg-color) ${draft.opacity}%, transparent)`}">Doumi Shell · 背景预览</div>
    </div>
    <p>图片会压缩后保存；浅色、深色主题均可使用。不透明度统一应用于整个界面、终端和弹窗，文字与图标保持清晰。</p>
    </el-scrollbar><template #footer><el-button @click="reset">恢复默认</el-button><el-button @click="visible=false">取消</el-button><el-button type="primary" :disabled="reading" @click="apply">保存背景</el-button></template>
  </el-dialog>
</template>
<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useBackground } from '@/composables/useBackground'
const visible=defineModel({type:Boolean,default:false})
const {background,save,reset:resetBackground}=useBackground()
const draft=ref({...background.value}),reading=ref(false),fileInput=ref(null)
let version=0
function open(){version++;draft.value={...background.value};reading.value=false;if(fileInput.value)fileInput.value.value=''}
async function selectImage(event){
  const file=event.target.files?.[0],current=++version
  if(!file)return
  if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>20*1024*1024){ElMessage.error('请选择 20 MiB 以内的 PNG、JPEG 或 WebP 图片');event.target.value='';return}
  reading.value=true
  let url
  try{
    url=URL.createObjectURL(file)
    const image=new Image();image.src=url;await image.decode()
    const scale=Math.min(1,1920/image.width,1080/image.height)
    const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(image.width*scale));canvas.height=Math.max(1,Math.round(image.height*scale))
    canvas.getContext('2d').drawImage(image,0,0,canvas.width,canvas.height)
    if(current===version)draft.value.image=canvas.toDataURL('image/webp',.8)
  }catch{ElMessage.error('图片无法读取，请更换图片')}
  finally{if(url)URL.revokeObjectURL(url);if(current===version)reading.value=false}
}
function apply(){try{save({...draft.value,color:draft.value.color||'#eaf0f8'});visible.value=false}catch{ElMessage.error('浏览器存储空间不足，请缩小图片后重试')}}
function reset(){try{resetBackground();open()}catch{ElMessage.error('无法保存背景设置')}}
</script>
<style scoped>
.preview{height:150px;border-radius:10px;background-size:cover;background-position:center;display:grid;place-items:center;border:1px solid var(--el-border-color)}.preview>div{padding:20px;background:var(--el-bg-color);border-radius:8px;color:var(--el-text-color-primary)}p{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7}
</style>
