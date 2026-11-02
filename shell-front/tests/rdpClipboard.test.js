import test from 'node:test'
import assert from 'node:assert/strict'
import Guacamole from 'guacamole-common-js'
import { bindRdpClipboard } from '../src/utils/rdpClipboard.js'

const originalWindow=globalThis.window
test.beforeEach(()=>{globalThis.window={btoa:globalThis.btoa}})
test.afterEach(()=>{globalThis.window=originalWindow})
const tick=()=>new Promise(resolve=>setImmediate(resolve))
function fixture(options={}) {
  const surface=new EventTarget(),streams=[],sent=[],texts=[],notices=[],timers=new Map();let timer=0,enabled=true,focused=true
  const client={createClipboardStream(type){const stream={type,sendBlob:value=>sent.push(['blob',value]),sendEnd:()=>sent.push(['end'])};streams.push(stream);return stream}}
  const copied=[]
  const binding=bindRdpClipboard({Guacamole,client,surface,isEnabled:()=>enabled,hasFocus:()=>focused,sendShortcut:key=>sent.push(['key',key]),onText:text=>texts.push(text),onNotice:text=>notices.push(text),clipboard:{writeText:async text=>copied.push(text)},setTimer:fn=>{timers.set(++timer,fn);return timer},clearTimer:id=>timers.delete(id),...options})
  function event(type,fields={}){const event=new Event(type,{cancelable:true});Object.assign(event,fields);surface.dispatchEvent(event);return event}
  return {binding,client,streams,sent,texts,notices,copied,event,timers,setEnabled:value=>{enabled=value},setFocused:value=>{focused=value}}
}
test('paste waits for native text and sends UTF-8 chunks under acknowledgements before remote Ctrl+V',async()=>{
  const f=fixture();const key=f.event('keydown',{key:'v',ctrlKey:true});assert.equal(key.defaultPrevented,false)
  const event=f.event('paste',{clipboardData:{types:['text/plain'],getData:()=> '中文😀'}});assert.equal(event.defaultPrevented,true)
  assert.equal(f.streams.length,1);assert.equal(f.streams[0].type,'text/plain');assert.equal(f.sent.length,0)
  f.streams[0].onack(new Guacamole.Status(0,'ok'))
  assert.deepEqual(f.sent,[['blob','5Lit5paH8J+YgA==']])
  f.streams[0].onack(new Guacamole.Status(0,'ok'));await tick()
  assert.deepEqual(f.sent.slice(-2),[['end'],['key',118]]);f.binding.dispose()
})
test('long paste is paced and focus loss stops the delayed remote paste chord',async()=>{
  const f=fixture();const pending=f.binding.pasteText('a'.repeat(20000))
  assert.equal(f.sent.length,0)
  for(let i=0;i<4;i++){f.streams[0].onack(new Guacamole.Status(0,'ok'));assert.equal(f.sent.length,i+1)}
  f.setFocused(false);f.streams[0].onack(new Guacamole.Status(0,'ok'));await pending
  assert.equal(f.sent.filter(event=>event[0]==='key').length,0);f.binding.dispose()
})
test('remote clipboard UTF-8 survives split emoji and unrelated formats never reach local clipboard',async()=>{
  const f=fixture(),acks=[]
  const stream={sendAck:(...args)=>acks.push(args)}
  f.client.onclipboard(stream,'text/plain');stream.onblob('5Lit5paH8J8=');stream.onblob('mIA=');stream.onend();await tick()
  assert.deepEqual(f.texts,['中文😀']);assert.deepEqual(f.copied,['中文😀'])
  f.client.onclipboard({sendAck:(...args)=>acks.push(args)},'image/png');assert.equal(acks.at(-1)[1],256)
  f.setEnabled(false);const hidden={sendAck(){}};f.client.onclipboard(hidden,'text/plain');hidden.onblob('c2VjcmV0');hidden.onend();await tick()
  assert.deepEqual(f.copied,['中文😀']);f.binding.dispose()
})
test('copy starts a promise-backed clipboard write in the shortcut gesture and resolves with new remote text',async()=>{
  let item,writeCalled=false
  class Item {constructor(data){this.data=data;item=this}}
  const f=fixture({ClipboardItem:Item,clipboard:{write:async values=>{writeCalled=true;assert.equal(values[0],item);await item.data['text/plain']}}})
  const key=f.event('keydown',{key:'c',ctrlKey:true});assert.equal(key.defaultPrevented,true);assert.equal(writeCalled,true)
  assert.deepEqual(f.sent,[['key',99]])
  const stream={sendAck(){}};f.client.onclipboard(stream,'text/plain');stream.onblob('bmV3IHRleHQ=');stream.onend();await tick()
  assert.equal(await (await item.data['text/plain']).text(),'new text');f.binding.dispose()
})
test('permission denial is actionable and oversized text or files never trigger a stale remote paste',async()=>{
  const f=fixture({clipboard:{writeText:async()=>{throw new Error('denied secret')}}})
  await f.binding.copyText('hello');assert.match(f.notices.at(-1),/剪贴板/);assert.doesNotMatch(f.notices.join(' '),/secret/)
  await f.binding.pasteText('a'.repeat(262144));assert.equal(f.streams.length,0)
  f.event('paste',{clipboardData:{types:['Files'],files:[{}],getData:()=>''}});await tick();assert.equal(f.streams.length,0);assert.equal(f.sent.length,0)
  f.binding.dispose()
})
test('disposal aborts pending transfers and detaches shortcut listeners from stale desktops',async()=>{
  const f=fixture();const pending=f.binding.pasteText('hello');const stream=f.streams[0]
  f.binding.dispose();await pending
  stream.onack?.(new Guacamole.Status(0,'ok'));f.event('keydown',{key:'v',ctrlKey:true})
  assert.equal(f.sent.filter(event=>event[0]==='key'||event[0]==='blob').length,0)
  assert.equal(f.client.onclipboard,null)
})
test('failed promise-backed copy keeps the permission notice when the remote text arrives',async()=>{
  class Item {constructor(data){this.data=data}}
  for(const synchronous of [false,true]) {
    const f=fixture({ClipboardItem:Item,clipboard:{write(){if(synchronous)throw new Error('denied');return Promise.reject(new Error('denied'))}}})
    f.event('keydown',{key:'c',ctrlKey:true});await tick()
    const stream={sendAck(){}};f.client.onclipboard(stream,'text/plain');stream.onblob('dGV4dA==');stream.onend();await tick()
    assert.deepEqual(f.texts,['text']);assert.match(f.notices.at(-1),/浏览器未允许/);f.binding.dispose()
  }
})
test('Command modifier is reserved for local clipboard shortcuts instead of remote Windows Start',()=>{
  // Register after the capture listener, as Guacamole.Keyboard is registered.
  const surfaceEvents=[]
  const surface=new EventTarget()
  const binding=bindRdpClipboard({Guacamole,client:{},surface,isEnabled:()=>true,hasFocus:()=>true,sendShortcut(){}})
  surface.addEventListener('keydown',event=>surfaceEvents.push(event.key))
  surface.addEventListener('keyup',event=>surfaceEvents.push(event.key))
  surface.dispatchEvent(Object.assign(new Event('keydown'),{key:'Meta',metaKey:true}))
  surface.dispatchEvent(Object.assign(new Event('keyup'),{key:'Meta',metaKey:false}))
  assert.deepEqual(surfaceEvents,[]);binding.dispose()
})
test('native browser copy action requests remote text when the desktop has focus',()=>{
  const f=fixture()
  assert.equal(f.event('copy').defaultPrevented,true)
  assert.deepEqual(f.sent,[['key',99]])
  f.setFocused(false);assert.equal(f.event('copy').defaultPrevented,false)
  assert.equal(f.sent.length,1);f.binding.dispose()
})
