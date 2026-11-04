'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const worker=fs.readFileSync(path.join(__dirname,'../../main/assets/music/audio-worker.js'),'utf8');
const parser=require('../../main/assets/music/lyric-parser.js');
const ORIGIN='https://roylyl.github.io',CACHE='roylyl-music-lyrics-v1';
const source=name=>`https://raw.githubusercontent.com/Roylyl/Music/main/${name}.lrc?lyricRevision=1`;
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function until(predicate){for(let n=0;n<200;n++){if(predicate())return;await tick();}throw Error('Task did not advance');}
function harness(network){
  const stores=new Map(),handlers={},calls=[],progress=[];let now=1_000_000;
  const key=value=>new URL(typeof value==='string'?value:value.url,ORIGIN).href;
  const cache=name=>{
    if(!stores.has(name))stores.set(name,new Map());const entries=stores.get(name);
    return {
      async match(request){return entries.get(key(request))?.clone();},
      async put(request,response){const body=await response.arrayBuffer();entries.set(key(request),new Response(body,{status:response.status,headers:response.headers}));},
      async delete(request){return entries.delete(key(request));},
      async keys(){return [...entries.keys()].map(url=>new Request(url));}
    };
  };
  const sandbox={URL,Request,Response,Headers,AbortController,ReadableStream,console,setTimeout,clearTimeout,setInterval,clearInterval,
    Date:{now:()=>now},importScripts(){},
    self:{RoylylLyrics:parser,location:{origin:ORIGIN},addEventListener:(name,fn)=>handlers[name]=fn,clients:{async matchAll(){return [];}}},
    caches:{async open(name){return cache(name);},async keys(){return [...stores.keys()];},async delete(name){return stores.delete(name);}},
    fetch(request,options){calls.push(request.url);return network(request,options);}
  };
  vm.createContext(sandbox);vm.runInContext(worker,sandbox);
  function message(data){
    const waits=[];handlers.message({data,source:{id:'test-window'},ports:[{postMessage:value=>progress.push(structuredClone(value))}],waitUntil:promise=>waits.push(promise)});
    return Promise.all(waits);
  }
  const batch=(sources,id='lyrics-test')=>message({type:'DOWNLOAD_ASSET_BATCH',kind:'lyrics',id,sources});
  return {batch,message,progress,calls,cache,stores,advance:ms=>now+=ms};
}
(async()=>{
  {
    let release,cancelledBodies=0,active=0,maxActive=0;
    const h=harness(async request=>{
      active++;maxActive=Math.max(maxActive,active);
      try{
        if(request.url.includes('/slow.lrc'))return await new Promise(resolve=>{release=resolve;});
        if(request.url.includes('/gone'))return new Response(new ReadableStream({cancel(){cancelledBodies++;}}),{status:request.url.includes('gone410')?410:404});
        if(request.url.includes('/network'))throw new TypeError('Offline');
        if(request.url.includes('/server'))return new Response('Retry later',{status:503});
        // Cross-origin Fetch may hide Content-Encoding and expose the compressed
        // Content-Length alongside an already decoded lyric body.
        if(request.url.includes('/good1'))return new Response('[00:01.00]压缩传输后的完整歌词',{headers:{'Content-Length':'1'}});
        return new Response('[00:01.00]歌词');
      }finally{active--;}
    });
    const sources=['slow','gone404','gone410','good1','network','server','good2'].map(source),done=h.batch(sources);
    await until(()=>h.progress.some(value=>value.processed===sources.length-1));
    assert.ok(h.calls.length===sources.length,'A slow first request must not block later lyrics');
    assert.ok(maxActive>1&&maxActive<=4,'Network concurrency must be bounded');
    assert.equal(cancelledBodies,2,'Missing files must cancel the error body immediately');
    release(new Response('[00:01.00]迟到的歌词'));await done;
    const result=h.progress.at(-1);
    assert.deepEqual([result.status,result.processed,result.downloaded,result.missing,result.failed],['completed',7,3,2,2]);
    assert.equal(await h.cache(CACHE).match(source('network')),undefined,'Network failures must not become missing lyrics');
    assert.equal(await h.cache(CACHE).match(source('server')),undefined,'Server failures must remain retryable');
    const before=h.calls.length;h.advance(61_000);
    await h.batch([source('gone404'),source('gone410')],'cached-missing');
    assert.equal(h.calls.length,before,'Known missing files must survive the former one-minute expiry');
    assert.equal(h.progress.at(-1).missing,2);
    await h.batch([source('gone404').replace('=1','=2')],'new-revision');
    assert.equal(h.calls.length,before+1,'A new lyric revision must recheck a previous missing file');
    console.log('PASS: bounded concurrency, immediate404/410skip, retryable failures and revision-aware missing cache');
  }
  {
    let aborted=0;
    const h=harness(async(_,options)=>new Response(new ReadableStream({start(controller){
      options.signal.addEventListener('abort',()=>{aborted++;controller.error(new DOMException('Cancelled','AbortError'));},{once:true});
    }})));
    const done=h.batch(Array.from({length:8},(_,n)=>source('cancel'+n)),'cancel-batch');
    await until(()=>h.calls.length===4);
    await h.message({type:'CANCEL_AUDIO_BATCH',id:'cancel-batch'});await done;
    assert.equal(aborted,4,'Cancelling must abort every active lyric request');
    assert.equal(h.calls.length,4,'Cancelling must not schedule remaining files');
    assert.equal((await h.cache(CACHE).keys()).length,0,'Cancelled responses must not be written');
    assert.equal(h.progress.findLast(value=>value.type==='AUDIO_BATCH_PROGRESS').status,'cancelled');
    console.log('PASS: concurrent cancellation leaves no late cache writes');
  }
  {
    const app=fs.readFileSync(path.join(__dirname,'../../main/assets/music/app.js'),'utf8');
    const start=app.indexOf('  async function cachedLyricCount()'),end=app.indexOf('  async function updateCacheStatus()',start);
    assert.ok(start>=0&&end>start);
    let active=0,max=0;
    const keys=Array.from({length:100},(_,index)=>index),cache={async keys(){return keys;},async match(key){
      active++;max=Math.max(max,active);await tick();active--;return {status:key<69?200:404};
    }};
    const context={LYRIC_CACHE:'lyrics',caches:{async open(){return cache;}}};vm.createContext(context);
    vm.runInContext(app.slice(start,end)+'\nthis.count=cachedLyricCount;',context);
    assert.equal(await context.count(),69,'Missing-file markers must not count as saved lyrics');
    assert.ok(max<=32,'Cache statistics must bound concurrent reads');
    console.log('PASS: saved-lyric statistics exclude missing records');
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
