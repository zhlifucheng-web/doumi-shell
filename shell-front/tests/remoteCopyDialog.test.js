import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick } from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
const filename=new URL('../src/components/ssh/RemoteCopyDialog.vue',import.meta.url)
const flush=async()=>{await new Promise(setImmediate);await nextTick()}
function mount(options={}) {
  const queued=[],calls=[],errors=[],events=[]
  const panel=mountSfcSetup(filename,{props:{source:{sessionToken:'old',sourcePath:'/src/folder',name:'folder',accountId:1,hostId:4},onQueued:()=>events.push('queued')},
    modules:{'element-plus':{ElMessage:{error:message=>errors.push(message),success:()=>{},warning:()=>{}}},
      '@/composables/useTransfers':{useTransfers:()=>({enqueueTransfer:task=>queued.push(task)})},
      '@/api/sshConsole':{listScheduleAccounts:options.list || (async()=>[{id:2,hostId:5,hostName:'target',username:'root'}]),
        sftpCopyTargetUsers:options.users || (async()=>({users:['root','alice']})),
        sftpCopyTargetDirectories:options.directories || (async()=>({path:'/srv',items:[{name:'data',dir:true}]})),
        sftpCopy:async(...args)=>{calls.push(args);return {completed:true}}}}})
  return {...panel,queued,calls,errors,events}
}
test('queued copy pins both endpoints, mode and owner while the dialog or source changes',async()=>{
  const panel=mount()
  try {
    await flush();Object.assign(panel.state.form,{targetAccountId:2,targetPath:'/srv/data',owner:'alice',mode:'DIRECT',overwrite:true})
    await panel.state.submit()
    panel.props.source={sessionToken:'new',sourcePath:'/different',name:'other',accountId:3,hostId:6};await flush()
    assert.equal(panel.queued.length,1);assert.equal(panel.queued[0].direction,'复制');assert.equal(panel.queued[0].hostId,4)
    await panel.queued[0].run({})
    assert.deepEqual(panel.calls[0][0],{sessionToken:'old',sourcePath:'/src/folder',targetAccountId:2,targetPath:'/srv/data',owner:'alice',mode:'DIRECT',overwrite:true})
    assert.deepEqual(panel.events,['queued'])
  } finally {panel.unmount()}
})
test('destination defaults to the source current directory, including root',async()=>{
  const panel=mount()
  try {
    await flush();assert.equal(panel.state.form.targetPath,'/src')
    panel.props.source={...panel.props.source,sourcePath:'/file.txt'};await flush()
    assert.equal(panel.state.form.targetPath,'/')
  }finally{panel.unmount()}
})
test('receiver owners can be selected or entered without being limited to suggestions',async()=>{
  const panel=mount()
  try {
    await flush();panel.state.form.targetAccountId=2;await flush()
    assert.deepEqual(panel.state.owners.value,['root','alice'])
    panel.state.form.owner='alice';panel.state.submit()
    panel.state.form.owner='custom-user';panel.state.submit()
    await panel.queued[0].run({});await panel.queued[1].run({})
    assert.equal(panel.calls[0][0].owner,'alice');assert.equal(panel.calls[1][0].owner,'custom-user')
  }finally{panel.unmount()}
})
test('directory picker reads receiver directories and selection stays editable',async()=>{
  const requests=[]
  const panel=mount({directories:async(...args)=>{requests.push(args);return {path:args[2],items:[{name:'data',dir:true}]}}})
  try {
    await flush();panel.state.form.targetAccountId=2;await flush()
    await panel.state.openFolders()
    assert.deepEqual(requests[0],['old',2,'/src'])
    await panel.state.enterFolder({name:'data'})
    panel.state.chooseFolder();assert.equal(panel.state.form.targetPath,'/src/data')
    panel.state.form.targetPath='/new/manual';panel.state.submit();await panel.queued[0].run({})
    assert.equal(panel.calls[0][0].targetPath,'/new/manual')
  }finally{panel.unmount()}
})
test('switching receiver clears selected owner and ignores previous receiver responses',async()=>{
  let resolveUsers,resolveFolders
  const panel=mount({users:(_token,id)=>id===2?new Promise(resolve=>{resolveUsers=resolve}):Promise.resolve({users:['bob']}),
    directories:()=>new Promise(resolve=>{resolveFolders=resolve})})
  try {
    await flush();panel.state.form.targetAccountId=2;await flush()
    const browsing=panel.state.openFolders();await flush()
    panel.state.form.owner='alice';panel.state.form.targetAccountId=3;await flush()
    resolveUsers({users:['alice']});resolveFolders({path:'/old',items:[{name:'old'}]});await browsing;await flush()
    assert.deepEqual(panel.state.owners.value,['bob']);assert.equal(panel.state.form.owner,'')
    assert.equal(panel.state.folderDialog.value,false);assert.deepEqual(panel.state.folders.value,[])
  }finally{panel.unmount()}
})
test('changing the source resets receiver selection and its pending suggestions',async()=>{
  let resolveUsers
  const panel=mount({users:()=>new Promise(resolve=>{resolveUsers=resolve})})
  try {
    await flush();panel.state.form.targetAccountId=2;await flush();panel.state.form.owner='alice'
    panel.props.source={...panel.props.source,sessionToken:'new',sourcePath:'/other/file'};await flush()
    resolveUsers({users:['old-user']});await flush()
    assert.equal(panel.state.form.targetAccountId,null);assert.equal(panel.state.form.owner,'')
    assert.equal(panel.state.form.targetPath,'/other');assert.deepEqual(panel.state.owners.value,[])
  }finally{panel.unmount()}
})
test('failed receiver browsing and user discovery leave manual inputs available',async()=>{
  const panel=mount({users:async()=>{throw new Error('users denied')},directories:async()=>{throw new Error('directory denied')}})
  try {
    await flush();panel.state.form.targetAccountId=2;await flush();await panel.state.openFolders()
    assert.match(panel.state.ownerError.value,/users denied/);assert.match(panel.state.folderError.value,/directory denied/)
    panel.state.form.targetPath='/new';panel.state.form.owner='alice';panel.state.submit()
    assert.equal(panel.queued.length,1)
  }finally{panel.unmount()}
})
test('invalid destination stays in the form and does not queue a request',async()=>{
  const panel=mount()
  try {
    await flush();Object.assign(panel.state.form,{targetAccountId:2,targetPath:'relative'})
    await panel.state.submit();assert.equal(panel.queued.length,0);assert.ok(panel.state.formError.value)
  }finally{panel.unmount()}
})
test('account loading failure is visible and can be retried',async()=>{
  let fail=true
  const panel=mount({list:async()=>{if(fail)throw new Error('network lost');return [{id:2,hostId:5,hostName:'target',username:'root'}]}})
  try {
    await flush();assert.match(panel.state.accountsError.value,/network lost/)
    fail=false;await panel.state.loadAccounts();assert.equal(panel.state.accountsError.value,'');assert.equal(panel.state.accounts.value.length,1)
  }finally{panel.unmount()}
})
