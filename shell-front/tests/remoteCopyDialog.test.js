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
