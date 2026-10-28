import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick, ref, createRenderer, h, KeepAlive } from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
import { useTerminalFileContext } from '../src/composables/useTerminalFileContext.js'

const filename = new URL('./helpers/TerminalFileContextHarness.vue', import.meta.url)
// The harness mounts lifecycle hooks exactly as the consuming panel does.
function mountContext(request, props = {}) {
  const timers = new Map(), calls = [], storage = new Map()
  let id = 0

  const mounted = mountSfcSetup(filename, { modules: { context: { useTerminalFileContext } }, props: { token:'a',tab:'one',active:true,blocked:false,...props }, globals: {
    request, calls, schedule: (fn, delay) => { const key=++id;timers.set(key,{fn,delay});return key }, unschedule: key=>timers.delete(key),
    storage: {getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value)}
  } })
  return {...mounted,timers,calls,storage, async tick(){ const timer=timers.values().next().value;assert.ok(timer);timers.clear();await timer.fn();await flush() }}
}
const flush=async()=>{await Promise.resolve();await Promise.resolve();await nextTick()}
const ready=(cwd='/srv/a',extra={})=>({available:true,status:'available',cwd,terminalUser:'alice',terminalUid:1000,terminalGid:1000,fileUser:'root',fileUid:0,fileGid:0,contextId:'x',...extra})

test('follows changed absolute cwd only, retaining both identities and one completion-based timer',async()=>{
  let resolve,requests=0
  const panel=mountContext(()=>{requests++;return new Promise(r=>resolve=r)})
  try{
    assert.equal(requests,1);assert.equal(panel.timers.size,0)
    resolve(ready());await flush();assert.deepEqual(panel.calls,['/srv/a']);assert.equal(panel.timers.size,1);assert.equal([...panel.timers.values()][0].delay,1000)
    const pending=panel.tick();assert.equal(requests,2);assert.equal(panel.timers.size,0);resolve(ready());await pending
    assert.deepEqual(panel.calls,['/srv/a']);assert.equal(panel.state.context.context.value.fileUser,'root')
  }finally{panel.unmount()}
})

test('follow off persists only a boolean, probes identity but defers jumps over open viewers',async()=>{
  let value=ready('/srv/b')
  const panel=mountContext(async()=>value,{blocked:true})
  try{
    await flush();assert.deepEqual(panel.calls,[])
    value=ready('/srv/latest');await panel.tick();assert.deepEqual(panel.calls,[])
    panel.props.blocked=false;await flush();assert.deepEqual(panel.calls,['/srv/latest'])
    panel.state.context.follow.value=false;await flush();value=ready('/srv/c',{terminalUser:'bob',terminalUid:2000});await panel.tick()
    assert.deepEqual(panel.calls,['/srv/latest']);assert.equal(panel.state.context.context.value.terminalUser,'bob')
    assert.deepEqual([...panel.storage.values()],['false'])
    panel.state.context.follow.value=true;await flush();assert.deepEqual(panel.calls,['/srv/latest','/srv/c'])
  }finally{panel.unmount()}
})

test('old token/tab probes never publish or overlap and inactive/unmounted views stop timers',async()=>{
  const pending=[]
  const panel=mountContext(token=>new Promise(resolve=>pending.push({token,resolve})))
  try{
    panel.props.token='b';panel.props.tab='two';await flush();assert.equal(pending.length,1)
    pending[0].resolve(ready('/old'));await flush();assert.deepEqual(panel.calls,[])
    const second=panel.tick();assert.equal(pending.length,2);assert.equal(pending[1].token,'b')
    panel.props.active=false;await flush();pending[1].resolve(ready('/new'));await second;await flush();assert.equal(panel.timers.size,0);assert.deepEqual(panel.calls,[])
    panel.props.active=true;await flush();assert.equal(pending.length,3)
    pending[2].resolve(ready('/new'));await flush();assert.deepEqual(panel.calls,['/new']);panel.unmount();assert.equal(panel.timers.size,0)
  }finally{panel.unmount()}
})

test('unavailable, failed or relative probes keep directory and clear usable ownership',async()=>{
  let response=ready(),failure=false
  const panel=mountContext(async()=>{if(failure)throw new Error('probe failed');return response})
  try{
    await flush();assert.equal(panel.state.context.canAssignOwner.value,true)
    response=ready('relative');await panel.tick();assert.equal(panel.state.context.canAssignOwner.value,false);assert.deepEqual(panel.calls,['/srv/a'])
    failure=true;await panel.tick();assert.match(panel.state.context.message.value,/probe failed/);assert.equal(panel.state.context.context.value,null)
    failure=false;response={available:false,status:'nested_ssh',message:'Nested SSH'};await panel.tick();assert.equal(panel.state.context.message.value,'Nested SSH');assert.deepEqual(panel.calls,['/srv/a'])
  }finally{panel.unmount()}
})

test('switching tabs with the same token discards a late context and clears ownership immediately',async()=>{
 const pending=[]
 const panel=mountContext(()=>new Promise(resolve=>pending.push(resolve)))
 try{
  panel.props.tab='second';await flush();assert.equal(panel.state.context.canAssignOwner.value,false)
  pending[0](ready('/first'));await flush();assert.deepEqual(panel.calls,[])
  const second=panel.tick();pending[1](ready('/second'));await second
  assert.deepEqual(panel.calls,['/second']);assert.equal(panel.state.context.canAssignOwner.value,true)
  panel.props.token='next';await flush();assert.equal(panel.state.context.canAssignOwner.value,false)
 }finally{panel.unmount()}
})

test('KeepAlive deactivation stops probes and reactivation starts with a fresh context',async()=>{
 const visible=ref(true),timers=new Map();let calls=0,context
 const renderer=createRenderer({createElement:()=>({}),createComment:()=>({}),insert(){},remove(){},parentNode:()=>null,nextSibling:()=>null})
 const Child={setup(){context=useTerminalFileContext({sessionToken:()=> 's',tabId:()=> 'one',active:()=>true,blocked:()=>false,currentPath:()=>'/a',request:async()=>{calls++;return ready('/a')},schedule:fn=>{timers.set(1,fn);return 1},unschedule:key=>timers.delete(key)});return ()=>null}}
 const app=renderer.createApp({setup:()=>()=>h(KeepAlive,null,{default:()=>visible.value?h(Child):null})})
 app.mount({})
 try{
  await flush();assert.equal(calls,1);assert.equal(timers.size,1)
  visible.value=false;await flush();assert.equal(timers.size,0);assert.equal(context.canAssignOwner.value,false)
  visible.value=true;await flush();assert.equal(calls,2);assert.equal(timers.size,1)
 }finally{app.unmount()}
 assert.equal(timers.size,0)
})
