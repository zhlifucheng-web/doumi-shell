import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import { nextTick, ref } from 'vue'
import { rdpDimensions } from '../src/utils/rdpSession.js'
import * as resolutions from '../src/utils/rdpResolution.js'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
const { descriptor } = parse(readFileSync(new URL('../src/components/rdp/RdpDesktop.vue', import.meta.url),'utf8'))
const compiled=compileScript(descriptor,{id:'rdp-display'})
function production(name,deps) {
  const node=compiled.scriptSetupAst.find(n=>n.type==='FunctionDeclaration' && n.id.name===name)
  return new Function(...Object.keys(deps), `${descriptor.scriptSetup.content.slice(node.start,node.end)}; return ${name}`)(...Object.values(deps))
}
test('fit enlarges a small fixed desktop to the largest complete proportional image',()=>{
  const scales=[], display={getWidth:()=>1280,getHeight:()=>720,scale:value=>scales.push(value)}
  const scale=production('scaleDisplay',{display,viewport:ref({clientWidth:2560,clientHeight:1440}),fit:ref(true)})
  scale();assert.deepEqual(scales,[2])
})
test('fit shrinks without cropping and original size remains one-to-one',()=>{
  const scales=[], display={getWidth:()=>1920,getHeight:()=>1080,scale:value=>scales.push(value)}
  const fit=ref(true),scale=production('scaleDisplay',{display,viewport:ref({clientWidth:960,clientHeight:800}),fit})
  scale();fit.value=false;scale();assert.deepEqual(scales,[0.5,1])
})
test('hidden desktop does not apply zero scale or change its input coordinate mapping',()=>{
  const scales=[],display={getWidth:()=>1280,getHeight:()=>720,scale:value=>scales.push(value)}
  production('scaleDisplay',{display,viewport:ref({clientWidth:0,clientHeight:0}),fit:ref(true)})()
  assert.deepEqual(scales,[])
})
test('custom resolution connects with the entered dimensions instead of fallback size',async()=>{
  const requests=[]
  const connect=production('connect',{
    fullscreenError:ref(''),releaseInput(){},nextTick:async()=>{},resolution:ref('custom'),
    customWidth:ref(1600),customHeight:ref(1000),viewport:ref({clientWidth:900,clientHeight:600}),
    rdpDimensions,emit(){},props:{profile:{id:7},active:true},session:{connect:async value=>requests.push(value),setActive(){}}
  })
  await connect();assert.deepEqual(requests,[{connectionId:7,width:1600,height:1000,dpi:96}])
})
test('custom apply normalizes dimensions and retains the applied values in its inputs',async()=>{
  const requests=[],customWidth=ref(1601),customHeight=ref(2400)
  const connect=production('connect',{
    fullscreenError:ref(''),releaseInput(){},nextTick:async()=>{},resolution:ref('custom'),
    customWidth,customHeight,viewport:ref({clientWidth:900,clientHeight:600}),
    rdpDimensions,emit(){},props:{profile:{id:7},active:true},session:{connect:async value=>requests.push(value),setActive(){}}
  })
  await connect();assert.deepEqual(requests,[{connectionId:7,width:1600,height:2160,dpi:96}])
  assert.equal(customWidth.value,1600);assert.equal(customHeight.value,2160)
})

function desktop(profile, initialResolution) {
  const requests=[], remembered=[]
  const mounted=mountSfcSetup(new URL('../src/components/rdp/RdpDesktop.vue',import.meta.url),{
    props:{profile,initialResolution,active:true,onResolution:value=>remembered.push(value)},
    modules:{
      'guacamole-common-js':{default:{}}, '@/api/rdp':{}, '@/utils/rdpGuacamole':{}, '@/utils/rdpError':{}, '@/utils/rdpClipboard':{},
      '@/utils/rdpResolution':resolutions,
      '@/utils/rdpFocus':{bindRdpFocus:()=>()=>{}},
      '@/utils/rdpSession':{rdpDimensions,createRdpSession:()=>({connect:async value=>requests.push(value),disconnect(){},setActive(){},setFocused(){}})}
    },
    globals:{window:{},document:{},ResizeObserver:class{observe(){}disconnect(){}}}
  })
  return {...mounted,requests,remembered}
}

test('first connection uses the saved account resolution and existing tab override wins after account edits',async()=>{
  const fresh=desktop({id:7,resolution:'1920x1080'})
  const existing=desktop({id:7,resolution:'1920x1080'},'1600x1000')
  try {
    await nextTick();await Promise.resolve()
    assert.deepEqual(fresh.requests[0],{connectionId:7,width:1920,height:1080,dpi:96})
    assert.deepEqual(existing.requests[0],{connectionId:7,width:1600,height:1000,dpi:96})
    existing.props.profile={id:7,resolution:'2560x1440'}
    await nextTick();await existing.state.connect()
    assert.deepEqual(existing.requests.at(-1),{connectionId:7,width:1600,height:1000,dpi:96})
    assert.equal(existing.remembered.at(-1),'1600x1000')
    existing.state.resolution.value='1280x720'
    await nextTick();await existing.state.connect()
    assert.deepEqual(existing.requests.at(-1),{connectionId:7,width:1280,height:720,dpi:96})
    assert.equal(existing.remembered.at(-1),'1280x720')
  } finally {fresh.unmount();existing.unmount()}
})
