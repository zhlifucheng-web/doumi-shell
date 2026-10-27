<template>
  <el-button size="small" type="success" :disabled="!sessionToken || busy" @click="showUpload=true">上传文件夹</el-button>
  <input ref="folderInput" hidden type="file" webkitdirectory multiple @change="fallbackUpload" />
  <el-dialog v-model="showUpload" title="上传文件夹" width="520px" append-to-body :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
    <el-radio-group v-model="compressed"><el-radio :label="false">逐文件上传</el-radio><el-radio :label="true">压缩上传</el-radio></el-radio-group>
    <p class="hint">保留文件夹名称和层级。压缩上传会先在本机生成 ZIP，传到 Doumi Shell 后通过 SFTP 还原，无需服务器安装解压工具。</p>
    <p class="hint">同名文件会覆盖；中断后已传输的文件保留。单次最多 10000 项、64 层，总大小受服务端传输上限限制（默认 512 MB）。</p>
    <p class="hint">刷新或关闭页面后无法恢复文件夹传输或进度，请检查已完成的文件后重新选择文件夹上传。</p>
    <p v-if="!canPick" class="hint">当前浏览器使用文件夹文件选择器，无法读取空目录；若需保留空目录，请用 Chrome / Edge 从 localhost 打开。</p>
    <template #footer><el-button :disabled="busy" @click="showUpload=false">取消</el-button><el-button type="primary" :loading="busy" @click="chooseUpload">选择文件夹并上传</el-button></template>
  </el-dialog>
  <el-dialog v-model="showDownload" :title="`下载文件夹：${downloadTarget?.name || ''}`" width="520px" append-to-body :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
    <el-radio-group v-model="compressed"><el-radio :label="true">压缩下载（ZIP）</el-radio><el-radio :label="false">{{ canPick ? '直接保存文件夹' : '无压缩 ZIP' }}</el-radio></el-radio-group>
    <p class="hint">{{ canPick ? '直接保存会选择本机目录并保留文件夹层级，已有同名文件会覆盖。' : '此浏览器不支持直接写入本机目录，无压缩方式使用 ZIP 容器保留文件夹层级，解压后即可使用。' }}</p>
    <p class="hint">支持空目录和中文名称，不跟随符号链接。传输中断后，已保存的文件保留。</p>
    <p class="hint">刷新或关闭页面后无法恢复文件夹传输或进度，请检查本机已保存的文件后重新下载。</p>
    <template #footer><el-button :disabled="busy" @click="showDownload=false">取消</el-button><el-button type="primary" :loading="busy" @click="download">选择并下载</el-button></template>
  </el-dialog>
  <el-dialog :model-value="busy" title="文件夹传输" width="480px" append-to-body :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
    <el-progress :percentage="percent" :indeterminate="percent===0" /><p class="hint">{{ status }}</p>
  </el-dialog>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { sftpDownloadLarge, sftpDownload, sftpFolderTree, sftpFolderUpload, sftpFolderLimit, sftpFolderMkdir } from '@/api/sshConsole'
const props=defineProps({sessionToken:String,path:String})
const emit=defineEmits(['uploaded'])
const canPick=!!window.showDirectoryPicker
const folderInput=ref(),showUpload=ref(false),showDownload=ref(false),compressed=ref(true),downloadTarget=ref(null),busy=ref(false),percent=ref(0),status=ref('')
function warnBeforeLeave(event){event.preventDefault();event.returnValue=''}
watch(busy, value => {
  if(value)window.addEventListener('beforeunload',warnBeforeLeave)
  else window.removeEventListener('beforeunload',warnBeforeLeave)
},{flush:'sync'})
onBeforeUnmount(()=>window.removeEventListener('beforeunload',warnBeforeLeave))
const join=(base,name)=>`${base.replace(/\/$/,'')}/${name}`
function openDownload(row){if(busy.value)return;downloadTarget.value={name:row.name,path:join(props.path,row.name)};showDownload.value=true}
async function uploadFolderEntries(entries) {
  if (!entries || !entries.length) return
  try {
    await upload(entries, props.sessionToken, props.path, compressed.value)
  } catch (e) {
    ElMessage.error(e.message || '文件夹上传失败')
  } finally {
    busy.value = false
  }
}
defineExpose({openDownload, uploadFolderEntries})
async function chooseUpload(){
  if(!canPick){folderInput.value.value='';folderInput.value.click();return}
  const token=props.sessionToken,path=props.path,zip=compressed.value
  try {
    const root=await window.showDirectoryPicker({mode:'read'}),entries=[]
    busy.value=true;percent.value=0;status.value='正在读取文件夹…'
    async function walk(dir,prefix,depth=0){
      if(depth>64)throw new Error('文件夹层级超过 64')
      entries.push({name:prefix,dir:true})
      if(entries.length>10000)throw new Error('文件夹超过 10000 项')
      for await(const handle of dir.values()){
        if(handle.kind==='directory')await walk(handle,`${prefix}/${handle.name}`,depth+1)
        else {entries.push({name:`${prefix}/${handle.name}`,file:await handle.getFile()});if(entries.length>10000)throw new Error('文件夹超过 10000 项')}
      }
    }
    await walk(root,root.name);await upload(entries,token,path,zip)
  }catch(e){failure(e)}finally{busy.value=false}
}
async function fallbackUpload(event){
  const entries=Array.from(event.target.files||[]).map(file=>({name:file.webkitRelativePath,file}))
  if(!entries.length)return
  try{await upload(entries,props.sessionToken,props.path,compressed.value)}catch(e){failure(e)}finally{busy.value=false}
}
async function upload(entries,token,path,compress){
  if(entries.length>10000)throw new Error('文件夹超过 10000 项')
  busy.value=true;percent.value=0;status.value='准备上传…'
  const max=await sftpFolderLimit(),total=entries.reduce((sum,e)=>sum+(e.file?.size||0),0)
  if(total>max)throw new Error(`文件夹超过传输上限 ${Math.round(max/1024/1024)} MB`)
  if(compress){
    const {default:JSZip}=await import('jszip'),zip=new JSZip()
    for(const entry of entries){if(entry.dir)zip.folder(entry.name);else zip.file(entry.name,entry.file)}
    status.value='正在本机压缩文件夹…'
    const blob=await zip.generateAsync({type:'blob',compression:'DEFLATE',compressionOptions:{level:6}},p=>percent.value=Math.round(p.percent/2))
    status.value='上传并还原文件夹…';await sftpFolderUpload(token,path,new File([blob],'folder.zip'),{archive:true})
  }else{
    for(let i=0;i<entries.length;i++){
      const entry=entries[i];status.value=`正在上传 ${entry.name}`
      if(entry.dir)await sftpFolderMkdir(token,path,entry.name)
      else await sftpFolderUpload(token,path,entry.file,{relativePath:entry.name})
      percent.value=Math.round((i+1)/entries.length*100)
    }
  }
  percent.value=100;showUpload.value=false;emit('uploaded');ElMessage.success('文件夹上传完成')
}
async function download(){
  const token=props.sessionToken,target=downloadTarget.value,compress=compressed.value
  try {
    // Directory permission must be requested in the original user gesture.
    const dest=!compress&&canPick?await window.showDirectoryPicker({mode:'readwrite'}):null
    busy.value=true;percent.value=0;status.value='正在读取并传输文件夹…'
    if(dest){
      const tree=await sftpFolderTree(token,target.path),root=await dest.getDirectoryHandle(tree.name,{create:true})
      for(let i=0;i<tree.items.length;i++){
        const entry=tree.items[i],parts=entry.path.split('/');let dir=root
        for(const part of parts.slice(0,-1))dir=await dir.getDirectoryHandle(part,{create:true})
        status.value=`正在保存 ${entry.path}`
        if(entry.directory)await dir.getDirectoryHandle(parts.at(-1),{create:true})
        else {
          const handle=await dir.getFileHandle(parts.at(-1),{create:true})
          await sftpDownloadLarge(token,join(tree.path,entry.path),{handle})
        }
        percent.value=Math.round((i+1)/Math.max(1,tree.items.length)*100)
      }
    }else await sftpDownload(token,target.path,`${target.name}.zip`,{folder:true,compress})
    percent.value=100;showDownload.value=false;ElMessage.success(dest?'文件夹已保存':'文件夹下载已开始')
  }catch(e){failure(e)}finally{busy.value=false}
}
function failure(error){if(error?.name!=='AbortError')ElMessage.error(error?.message||'传输失败；已传输的文件保留，请检查后重试')}
</script>
<style scoped>.hint{font-size:12px;line-height:1.8;color:var(--el-text-color-secondary);overflow-wrap:anywhere}</style>
