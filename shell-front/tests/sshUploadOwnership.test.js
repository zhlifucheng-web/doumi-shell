import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
function apiHarness() {
  const calls=[]
  const source=readFileSync(new URL('../src/api/sshConsole.js',import.meta.url),'utf8').replace(/^import .* from .*$/gm,'').replace(/export /g,'')
  const client={get:async(url,config)=>{calls.push({url,...config});return {available:false}},post:async(url,body,config)=>{calls.push({url,body,...config});return url.endsWith('/start')?'upload-1':{uploaded:true,ownershipApplied:false,warning:'ownership denied'} }}
  const api=new Function('apiClient','handleApiResponse','get',`${source};return {sftpUploadLarge,sftpFolderUpload,sftpFolderMkdir,getTerminalContext:typeof getTerminalContext==='function'?getTerminalContext:undefined}`)(client,(x,options)=>{calls.at(-1).options=options;return x},(url,params,options)=>{calls.push({url,params,options});return {available:false}})
  return {...api,calls}
}
test('chunked upload pins numeric ownership at start and returns successful commit warnings',async()=>{
  const api=apiHarness(),file=new File(['abc'],'notes.txt')
  const result=await api.sftpUploadLarge('old-token','/captured',file,{ownerUid:0,ownerGid:1000})
  assert.deepEqual(api.calls[0].params,{sessionToken:'old-token',path:'/captured',name:'notes.txt',total:3,ownerUid:0,ownerGid:1000})
  assert.deepEqual(result,{uploaded:true,ownershipApplied:false,warning:'ownership denied'})
  assert.equal(api.calls.length,3)
})
test('folder file and directory requests forward an ownership snapshot',async()=>{
  const api=apiHarness()
  await api.sftpFolderUpload('s','/captured',new File(['a'],'a'),{relativePath:'folder/a',ownerUid:1001,ownerGid:1002})
  await api.sftpFolderMkdir('s','/captured','folder',{ownerUid:1001,ownerGid:1002})
  assert.deepEqual(api.calls[0].params,{sessionToken:'s',path:'/captured',archive:false,relativePath:'folder/a',ownerUid:1001,ownerGid:1002})
  assert.deepEqual(api.calls[1].params,{sessionToken:'s',path:'/captured',relativePath:'folder',ownerUid:1001,ownerGid:1002})
})
test('terminal context probe suppresses global failure and success popups',async()=>{
 const api=apiHarness();await api.getTerminalContext('s')
 assert.deepEqual(api.calls[0],{url:'/api/ssh-console/terminal-context',params:{sessionToken:'s'},signal:undefined,timeout:15000,options:{showSuccess:false,showFailure:false}})
})
