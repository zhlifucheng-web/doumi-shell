<template>
  <el-scrollbar class="page-scroll"><section class="disk-usage">
    <div class="scan-controls"><el-input v-model="path" placeholder="默认扫描当前 SSH 用户主目录，也可选择目录" aria-label="扫描目录" clearable @keyup.enter="scan()" /><el-button :disabled="loading" @click="openFolders">选择目录</el-button><el-input-number v-model="minimumMb" :min="1" :max="1048576" aria-label="大文件下限 MiB" /><span>MiB 以上</span><el-button type="primary" :loading="loading" @click="scan()">扫描空间</el-button></div>
    <p>目录和大文件统一展示，单击目录所在行即可进入扫描。目录按实际磁盘占用、大文件按逻辑大小排序，各显示前 200 项。扫描不跨文件系统、不跟随子目录符号链接，最长 85 秒。</p>
    <el-alert v-if="loading" title="正在扫描目录，终端和监控可继续使用。目录越大耗时越长。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="result">
      <div class="summary"><el-button type="primary" size="large" @click="scan(parent(result.path))" :disabled="loading || result.path==='/'"><el-icon><Back /></el-icon><span>上级目录</span></el-button><strong>{{ result.path }}</strong><el-tag>占用 {{ size(result.totalBytes) }}</el-tag><el-button @click="openFiles(result.path)">文件管理</el-button><span>{{ new Date(result.timestamp).toLocaleString() }}</span></div>
      <el-alert v-if="result.partial" :title="result.warning || '部分目录无权限或在扫描期间发生变化，结果可能不完整'" type="warning" :closable="false" />
      <el-alert v-if="result.limited" title="已达到显示上限，可进入子目录进一步扫描。" type="info" :closable="false" />
      <div class="entry-counts"><el-tag>目录 {{ result.directories.length }}</el-tag><el-tag type="info">大文件 {{ result.files.length }}</el-tag><span>目录在前，仅显示当前目录的直接子目录和大文件。</span></div>
      <el-table :data="entries" stripe empty-text="没有子目录或符合阈值的大文件" :row-class-name="({row})=>row.directory && !loading ? 'directory-row' : ''" @row-click="scanDirectory">
        <el-table-column label="类型" width="85"><template #default="{row}"><el-tag :type="row.directory ? 'primary' : 'info'">{{ row.directory ? '目录' : '文件' }}</el-tag></template></el-table-column>
        <el-table-column prop="path" label="名称" min-width="320" show-overflow-tooltip><template #default="{row}"><el-button v-if="row.directory" class="directory-link" link type="primary" :disabled="loading" @click.stop="scan(row.path)"><el-icon><Folder /></el-icon><span>{{ row.path.split('/').pop() }}</span></el-button><span v-else>{{ row.path.split('/').pop() }}</span></template></el-table-column>
        <el-table-column prop="bytes" label="大小" width="130" sortable><template #default="{row}">{{ size(row.bytes) }}</template></el-table-column>
        <el-table-column label="占目录空间" width="160"><template #default="{row}"><el-progress :percentage="Math.min(100,result.totalBytes ? Math.round(row.bytes/result.totalBytes*100) : 0)" /></template></el-table-column>
        <el-table-column label="管理" width="180" fixed="right"><template #default="{row}"><div @click.stop><el-button link :disabled="loading" @click="openFiles(row.directory ? row.path : parent(row.path))">定位</el-button><el-button link :disabled="loading" @click="rename(row)">重命名</el-button><el-button link type="danger" :disabled="loading" @click="remove(row)">删除</el-button></div></template></el-table-column>
      </el-table>
      <p>删除目录时须为空；非空目录可进入后管理。文件删除无法恢复。逻辑文件大小可能受稀疏文件影响，超过目录实际占用。</p>
    </template>
    <el-empty v-else-if="!loading" description="指定目录后点击扫描；留空使用当前用户主目录" />
    <el-dialog v-model="folderDialog" title="选择扫描目录" width="min(680px, 94vw)" append-to-body @closed="folderGeneration++">
      <div class="folder-toolbar"><el-button type="primary" :disabled="folderLoading || !folderPath || folderPath==='/'" @click="browseFolders(parent(folderPath))"><el-icon><Back /></el-icon><span>上级目录</span></el-button><el-button :disabled="folderLoading" @click="browseFolders('')">用户主目录</el-button><el-button :disabled="folderLoading" @click="browseFolders('/')">根目录</el-button></div>
      <p class="folder-path">{{ folderPath || '正在读取目录…' }}</p>
      <el-alert v-if="folderError" :title="folderError" type="error" :closable="false" />
      <el-scrollbar height="320px" v-loading="folderLoading">
        <el-table :data="folders" empty-text="此目录没有子目录，可直接选择当前目录" row-class-name="directory-row" @row-click="enterFolder">
          <el-table-column prop="name" label="文件夹" min-width="240" show-overflow-tooltip><template #default="{ row }"><el-icon><Folder /></el-icon> {{ row.name }}</template></el-table-column>
          <el-table-column width="85"><template #default="{ row }"><el-button link type="primary" :disabled="folderLoading" @click.stop="enterFolder(row)">进入</el-button></template></el-table-column>
        </el-table>
      </el-scrollbar>
      <template #footer><el-button @click="folderDialog=false">取消</el-button><el-button type="primary" :disabled="folderLoading || !!folderError || !folderPath" @click="chooseFolder">选择当前目录</el-button></template>
    </el-dialog>
  </section></el-scrollbar>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { scanDiskUsage, sftpDelete, sftpRename, listRemoteDirectories } from '@/api/sshConsole'
import { fileLocation } from '@/composables/useWorkspaceContext'
const props=defineProps({sessionToken:{type:String,default:''},accountId:{type:Number,default:null}})
const emit=defineEmits(['request-session'])
const router=useRouter(),path=ref(''),minimumMb=ref(100),loading=ref(false),result=ref(null),error=ref('')
const entries=computed(()=>result.value ? [...result.value.directories,...result.value.files] : [])
function scanDirectory(row){if(row.directory && !loading.value)scan(row.path)}
let generation=0
const folderDialog=ref(false),folderLoading=ref(false),folderPath=ref(''),folderError=ref(''),folders=ref([])
let folderGeneration=0
const parent=p=>p.slice(0,p.lastIndexOf('/'))||'/'
const size=n=>{if(n==null)return '—';const units=['B','KiB','MiB','GiB','TiB'];let index=0;while(n>=1024&&index<4){n/=1024;index++}return `${n.toFixed(index?1:0)} ${units[index]}`}
function openFolders(){
  if(!props.sessionToken){emit('request-session');ElMessage.info('正在连接，请就绪后选择目录');return}
  folderDialog.value=true
  browseFolders(path.value.startsWith('/') ? path.value : '')
}
async function browseFolders(target){
  const token=props.sessionToken,version=++folderGeneration
  folderLoading.value=true;folderError.value=''
  try {
    const data=await listRemoteDirectories(token,target)
    if(version!==folderGeneration || token!==props.sessionToken)return
    folderPath.value=data.path;folders.value=data.items
  }catch(e){if(version===folderGeneration){folderError.value=e?.message||'无法读取目录';folders.value=[]}}
  finally{if(version===folderGeneration)folderLoading.value=false}
}
function enterFolder(row){if(!folderLoading.value)browseFolders(`${folderPath.value.replace(/\/$/,'')}/${row.name}`)}
function chooseFolder(){path.value=folderPath.value;folderDialog.value=false}
async function scan(target){
  if(loading.value)return
  if(!props.sessionToken){emit('request-session');ElMessage.info('正在连接，请就绪后点击扫描');return}
  const token=props.sessionToken,version=++generation
  if(typeof target==='string')path.value=target
  const requested=path.value;loading.value=true;error.value=''
  try{const data=await scanDiskUsage(token,requested,minimumMb.value);if(version===generation&&token===props.sessionToken){result.value=data;path.value=data.path}}
  catch(e){if(version===generation)error.value=e?.message||'扫描失败'}finally{if(version===generation)loading.value=false}
}
function openFiles(target){fileLocation.value={accountId:props.accountId,path:target};router.push('/files')}
async function rename(row){
  const token=props.sessionToken,account=props.accountId
  try{const {value}=await ElMessageBox.prompt(`路径：${row.path}`,'重命名',{confirmButtonText:'保存',cancelButtonText:'取消',inputValue:row.path.split('/').pop(),inputValidator:v=>!!v&&v!=='.'&&v!=='..'&&!v.includes('/')||'请输入单个文件名'});if(token!==props.sessionToken||account!==props.accountId)return;await sftpRename(token,row.path,value);ElMessage.success('已重命名');await scan(result.value.path)}catch{/* canceled or API message */}
}
async function remove(row){
  const token=props.sessionToken,account=props.accountId
  try{await ElMessageBox.confirm(`将永久删除 ${row.path}${row.directory ? '（仅支持空目录）' : ''}。是否继续？`,'确认删除',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'});if(token!==props.sessionToken||account!==props.accountId)return;await sftpDelete(token,row.path);ElMessage.success('已删除');await scan(result.value.path)}catch{/* canceled or API message */}
}
watch(()=>[props.accountId,props.sessionToken],()=>{generation++;result.value=null;error.value='';loading.value=false;path.value='';folderGeneration++;folderDialog.value=false;folderLoading.value=false;folderPath.value='';folders.value=[]})
</script>
<style scoped>
.disk-usage{padding:12px;min-height:100%;box-sizing:border-box}.scan-controls{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.scan-controls>.el-input{flex:1;min-width:250px}.scan-controls>span,p{color:var(--el-text-color-secondary);font-size:12px;line-height:1.7}.summary{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin:20px 0}.summary strong{overflow-wrap:anywhere}.summary>span{font-size:12px;color:var(--el-text-color-secondary)}
.folder-toolbar{display:flex;gap:8px}.folder-path{overflow-wrap:anywhere}
.entry-counts{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin-bottom:12px}.entry-counts>span:last-child{font-size:12px;color:var(--el-text-color-secondary)}.directory-link{max-width:100%;justify-content:flex-start}.directory-link>span{overflow:hidden;text-overflow:ellipsis}.directory-link .el-icon,.summary .el-icon,.folder-toolbar .el-icon{margin-right:6px}:deep(.directory-row){cursor:pointer}
</style>
