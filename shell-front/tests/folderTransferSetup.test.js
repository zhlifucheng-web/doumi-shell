import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick } from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
const filename=new URL('../src/components/ssh/FolderTransfer.vue',import.meta.url)
function mountFolder({picker,limit=async()=>10000,...props}={}){
 const calls=[],warnings=[],successes=[],errors=[],uploaded=[]
 const panel=mountSfcSetup(filename,{props:{sessionToken:'old',path:'/old',ownerUid:1000,ownerGid:1001,ownershipRequested:true,ownershipAvailable:true,onUploaded:s=>uploaded.push(s),...props},globals:{window:{showDirectoryPicker:picker,addEventListener(){},removeEventListener(){}}},modules:{'element-plus':{ElMessage:{warning:m=>warnings.push(m),success:m=>successes.push(m),error:m=>errors.push(m)}},'@/api/sshConsole':{
 sftpFolderLimit:limit,
 sftpFolderMkdir:async(...args)=>{calls.push(['mkdir',...args]);return {uploaded:true,ownershipApplied:false,warning:'mkdir denied'}},
 sftpFolderUpload:async(...args)=>{calls.push(['upload',...args]);return {uploaded:true,ownershipApplied:false,warning:'file denied'}}
 }}})
 panel.state.compressed.value=false
 return {...panel,calls,warnings,successes,errors,uploaded}
}
const entry={name:'folder/a.txt',file:new File(['a'],'a.txt')}
test('folder picker captures destination, token and ownership before asynchronous selection',async()=>{
 let resolve
 const panel=mountFolder({picker:()=>new Promise(r=>resolve=r)})
 try{
  const pending=panel.state.chooseUpload()
  Object.assign(panel.props,{sessionToken:'new',path:'/new',ownerUid:2000,ownerGid:2001});await nextTick()
  resolve({name:'folder',async *values(){yield {kind:'file',name:'a.txt',getFile:async()=>entry.file}}})
  await pending
  assert.deepEqual(panel.calls[0],['mkdir','old','/old','folder',{ownerUid:1000,ownerGid:1001}])
  assert.deepEqual(panel.calls[1].slice(0,3),['upload','old','/old'])
  assert.deepEqual(panel.calls[1][4],{relativePath:'folder/a.txt',ownerUid:1000,ownerGid:1001})
  assert.equal(panel.errors.length,0);assert.equal(panel.successes.length,1);assert.equal(panel.warnings.length,1)
  assert.deepEqual(panel.uploaded,[{token:'old',path:'/old'}])
 }finally{panel.unmount()}
})
test('fallback picker keeps the same snapshot until file selection completes',async()=>{
 const panel=mountFolder()
 try{
  panel.state.folderInput.value={value:'',click(){}}
  await panel.state.chooseUpload()
  Object.assign(panel.props,{sessionToken:'new',path:'/new',ownerUid:2000,ownerGid:2001});await nextTick()
  const file=entry.file;Object.defineProperty(file,'webkitRelativePath',{value:'folder/a.txt'})
  await panel.state.fallbackUpload({target:{files:[file]}})
  assert.deepEqual(panel.calls[0].slice(0,3),['upload','old','/old'])
  assert.deepEqual(panel.calls[0][4],{relativePath:'folder/a.txt',ownerUid:1000,ownerGid:1001})
 }finally{panel.unmount()}
})
test('dropped folder uses the supplied pre-traversal snapshot during ongoing session changes',async()=>{
 const panel=mountFolder({sessionToken:'new',path:'/new',ownerUid:2000,ownerGid:2001})
 try{
  await panel.state.uploadFolderEntries([entry],{token:'old',path:'/captured',ownership:{ownerUid:1000,ownerGid:1001}})
  assert.deepEqual(panel.calls[0].slice(0,3),['upload','old','/captured'])
  assert.deepEqual(panel.calls[0][4],{relativePath:'folder/a.txt',ownerUid:1000,ownerGid:1001})
 }finally{panel.unmount()}
})
test('unavailable checked ownership prevents the folder picker from enqueueing',async()=>{
 const panel=mountFolder({ownershipAvailable:false})
 try{await panel.state.uploadFolderEntries([entry]);assert.equal(panel.calls.length,0);assert.equal(panel.warnings.length,1)}finally{panel.unmount()}
})
