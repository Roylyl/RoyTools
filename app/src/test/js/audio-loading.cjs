'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const workerPath=process.argv[2]||path.join(__dirname,'../../main/assets/music/audio-worker.js');
const worker=fs.readFileSync(workerPath,'utf8'),ORIGIN='https://roylyl.github.io',AUDIO='roylyl-music-audio-v2';
const source=name=>`https://raw.githubusercontent.com/Roylyl/Music/main/${name}.mp3?v=1`;
const bytes='012345678901234567890123456789';
const full=body=>new Response(body??bytes,{headers:{'Content-Length':'30','Content-Type':'audio/mpeg'}});
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function until(check){for(let n=0;n<150;n++){if(check())return;await tick();}throw Error('Work did not advance');}
function harness(network){
  const stores=new Map(),handlers={},calls=[],messages=[];
  const key=request=>new URL(typeof request==='string'?request:request.url,ORIGIN).href;
  const cache=name=>{if(!stores.has(name))stores.set(name,new Map());const entries=stores.get(name);return {
    async match(request){return entries.get(key(request))?.clone();},
    async put(request,response){entries.set(key(request),new Response(await response.arrayBuffer(),{status:response.status,headers:response.headers}));},
    async delete(request){return entries.delete(key(request));},async keys(){return [...entries.keys()].map(value=>new Request(value));}
  };};
  const sandbox={URL,Request,Response,Headers,Blob,AbortController,ReadableStream,setTimeout,clearTimeout,setInterval,clearInterval,console,importScripts(){},
    self:{location:{origin:ORIGIN},addEventListener:(name,handler)=>handlers[name]=handler,clients:{async matchAll(){return [{postMessage(value){messages.push(value);}}];}}},
    caches:{async open(name){return cache(name);},async keys(){return [...stores.keys()];},async delete(name){return stores.delete(name);}},
    fetch(request,options={}){calls.push({request,options});return network(request,options);}};
  vm.createContext(sandbox);vm.runInContext(worker,sandbox);
  function message(data){let answer;const waits=[];handlers.message({data,source:{id:'audio-test'},ports:[{postMessage:value=>answer=value}],waitUntil:promise=>waits.push(promise)});return {done:Promise.all(waits),get answer(){return answer;}};}
  function request(name,range='bytes=0-'){
    const request=new Request(source(name),{headers:{Range:range}});Object.defineProperty(request,'destination',{value:'audio'});
    const waits=[];let response;handlers.fetch({request,clientId:'audio-test',waitUntil:promise=>waits.push(promise),respondWith:value=>response=value});
    return {response,async done(){await response;await Promise.all(waits);}};
  }
  return {calls,messages,cache,message,request,async config(current,budget=-1,preload=true){await message({type:'AUDIO_CONFIG',budget,current:current?source(current):'',preload}).done;}};
}
(async()=>{
  {
    let release;
    const h=harness(async(request,options)=>request.url===source('current')?full(new ReadableStream({start(controller){
      controller.enqueue(new TextEncoder().encode(bytes.slice(0,10)));
      release=()=>{controller.enqueue(new TextEncoder().encode(bytes.slice(10)));controller.close();};
      options.signal.addEventListener('abort',()=>controller.error(new DOMException('Cancelled','AbortError')),{once:true});
    }})):full());
    await h.config('current');const playing=h.request('current'),response=await playing.response;
    assert.equal(response.status,200);assert.equal(h.calls[0].request.headers.get('Range'),null,'initial playback must request the whole song');
    const current=h.message({type:'CACHE_AUDIO',source:source('current'),preload:false});
    const next=h.message({type:'CACHE_AUDIO',source:source('next'),preload:true});await tick();await tick();
    assert.equal(h.calls.length,1,'cache/playback share one full request and next waits for storage');
    const earlySeek=h.request('current','bytes=10-19');let seekReady=false;
    earlySeek.response.then(()=>{seekReady=true;});await tick();await tick();
    assert.equal(seekReady,false,'an uncached middle seek waits for the shared full transfer');
    assert.equal(h.calls.length,1,'seeking a still-loading song must not open another network range');
    release();await playing.done();await current.done;await next.done;
    const earlySegment=await earlySeek.response;assert.equal(earlySegment.status,206);assert.equal(await earlySegment.text(),bytes.slice(10,20));await earlySeek.done();
    assert.equal(current.answer.ok,true);assert.equal(next.answer.ok,true);
    assert.ok(h.messages.some(x=>x.type==='AUDIO_DOWNLOAD_PROGRESS'&&x.source===source('current')&&x.bytes===30),'whole-file byte progress reaches completion independently of playback');
    assert.equal(h.calls.length,2);assert.equal(h.calls[1].request.url,source('next'));
    assert.equal(await (await h.cache(AUDIO).match(source('current'))).text(),bytes,'whole song is saved even when the audio response is never consumed');
    const seek=h.request('current','bytes=10-19'),segment=await seek.response;
    assert.equal(segment.status,206);assert.equal(await segment.text(),bytes.slice(10,20));await seek.done();assert.equal(h.calls.length,2,'completed cache supports seeking offline');
  }
  {
    let signal;
    const h=harness(async(request,options)=>request.url===source('old')?full(new ReadableStream({start(controller){
      signal=options.signal;controller.enqueue(new TextEncoder().encode(bytes.slice(0,10)));
      signal.addEventListener('abort',()=>controller.error(new DOMException('Cancelled','AbortError')),{once:true});
    }})):full());
    await h.config('old');const old=h.request('old');await old.response;
    await h.config('new');await old.done();assert.equal(signal.aborted,true,'a superseded playback-only download releases its network request');
    const current=h.message({type:'CACHE_AUDIO',source:source('new'),preload:false});await current.done;
    assert.equal(await h.cache(AUDIO).match(source('old')),undefined);assert.equal(current.answer.ok,true);
  }
  {
    let release,signal;
    const h=harness(async(request,options)=>request.url===source('next')?full(new ReadableStream({start(controller){
      signal=options.signal;controller.enqueue(new TextEncoder().encode(bytes.slice(0,10)));
      release=()=>{controller.enqueue(new TextEncoder().encode(bytes.slice(10)));controller.close();};
      signal.addEventListener('abort',()=>controller.error(new DOMException('Cancelled','AbortError')),{once:true});
    }})):full());
    await h.config('current');await h.message({type:'CACHE_AUDIO',source:source('current'),preload:false}).done;
    const next=h.message({type:'CACHE_AUDIO',source:source('next'),preload:true});await until(()=>!!release);
    await h.config('next');const playing=h.request('next');await playing.response;
    await h.config('next',-1,false);assert.equal(signal.aborted,false,'turning preloading off must preserve a prefetch adopted by current playback');
    release();await next.done;await playing.done();assert.equal(h.calls.length,2,'promotion shares the original next-song request');
  }
  {
    const h=harness(async request=>new Response(bytes.slice(0,10),{status:206,headers:{'Content-Length':'10','Content-Range':'bytes 0-9/30'}}));
    await h.config('current',0,false);const playing=h.request('current','bytes=0-9');assert.equal((await playing.response).status,206);await playing.done();
    assert.equal(h.calls[0].request.headers.get('Range'),'bytes=0-9');assert.equal(await h.cache(AUDIO).match(source('current')),undefined,'disabled caching retains ordinary streaming without persistent writes');
  }
  console.log('PASS: '+path.basename(path.dirname(workerPath))+' audio full-load sharing, continuous save without playback reads, next-song barrier, stale cancellation, adopted-preload protection, offline seeking and disabled-cache behavior.');
})().catch(error=>{console.error(error);process.exitCode=1;});
