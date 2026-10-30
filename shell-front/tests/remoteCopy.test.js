import test from 'node:test'
import assert from 'node:assert/strict'
import { runRemoteCopy } from '../src/composables/remoteCopy.js'

test('reports server progress and preserves ownership warning at completion', async () => {
  const states=[{status:'RUNNING',loaded:2,total:5},{status:'COMPLETED',loaded:5,total:5,warning:'属主设置失败'}]
  const progress=[],calls=[]
  const request=async(action,body)=>{calls.push([action,body]);return action==='start'?{id:'job'}:states.shift()}
  const result=await runRemoteCopy({sessionToken:'old',sourcePath:'/src',targetAccountId:2,targetPath:'/dest',mode:'RELAY'},
    {onProgress:(...args)=>progress.push(args)},request,async()=>{})
  assert.equal(result.warning,'属主设置失败');assert.deepEqual(progress,[[2,5],[5,5]])
  assert.deepEqual(calls[1],['status',{sessionToken:'old',id:'job'}])
})
test('cancellation during start still cancels the returned server job and waits for cleanup',async()=>{
  const controller=new AbortController(),calls=[]
  const request=async(action,body)=>{calls.push(action);if(action==='start'){controller.abort();return {id:'job'}};if(action==='status')return {status:'CANCELLED',loaded:0,total:0};return {}}
  await assert.rejects(runRemoteCopy({sessionToken:'old'},{signal:controller.signal},request,async()=>{}),{name:'AbortError'})
  assert.deepEqual(calls,['start','cancel','status'])
})
test('server failure keeps its original explanation and never reports completion',async()=>{
  const request=async action=>action==='start'?{id:'job'}:{status:'FAILED',error:'目标文件已存在',loaded:0,total:5}
  await assert.rejects(runRemoteCopy({sessionToken:'old'},{},request,async()=>{}),/目标文件已存在/)
})
test('a failed status request requests cancellation instead of leaving an unseen job running',async()=>{
  const calls=[]
  const request=async action=>{calls.push(action);if(action==='start')return {id:'job'};if(action==='status')throw new Error('network lost');return {}}
  await assert.rejects(runRemoteCopy({sessionToken:'old'},{},request,async()=>{}),/network lost/)
  assert.deepEqual(calls,['start','status','cancel'])
})
test('cleanup failure after cancellation remains visible in the shared transfer list',async()=>{
  const controller=new AbortController()
  const request=async action=>{
    if(action==='start'){controller.abort();return {id:'job'}}
    if(action==='status')return {status:'FAILED',error:'临时文件清理失败：/dest/.doumi-copy-test.part',loaded:1,total:5}
    return {}
  }
  await assert.rejects(runRemoteCopy({sessionToken:'old'},{signal:controller.signal},request,async()=>{}),
    error=>error.cleanupWarning?.includes('/dest/.doumi-copy-test.part'))
})
