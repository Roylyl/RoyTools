'use strict';
importScripts('./lyric-parser.js');
const AUDIO='roylyl-music-audio-v2',META='roylyl-music-audio-meta-v1',SHELL='roylyl-music-shell-20261111-35';
const FILES=['/music/','/music/index.html','/music/music.css?v=20261111-android-35','/music/roytools.css?v=20261111-android-35','/music/app.js?v=20261111-android-35','/music/playback-feedback.js?v=20261111-android-35','/music/lyric-parser.js?v=20261111-android-35','/music/image-loading.css?v=20260927-1','/music/image-loading.js?v=20261111-3','/music/catalog-sync.js?v=20261111-android-35','/music/data/catalog.json?v=20261111-android-35','/music/favicon.svg','/music/placeholder.svg'];
const metaKey='/music/__audio_index__',configKey='/music/__audio_config__',freshKey='/music/__audio_recovery__';
let indexNeedsRepair=false;
let generation=0,budget=-1,preloadEnabled=true,writeQueue=Promise.resolve(),configRevision=0,configRead,freshRead,optionalEpoch=0,lastKeptSource='';
const inflight=new Map(),playingRequests=new Map(),protectedSources=new Map();
const sourceEpoch=new Map(),recoveryRequests=new Map(),freshSources=new Set(),shellClients=new Map();
let manualBatch=null;
const assetGenerations={lyrics:0,covers:0},assetCaches={lyrics:'roylyl-music-lyrics-v1',covers:'roylyl-music-covers-v1'};
const shellPattern=/^roylyl-music-shell-(\d{8})-(\d+)$/;
const serial=task=>{const p=writeQueue.then(task,task);writeQueue=p.catch(()=>{});return p;};
const protectedSource=source=>[...protectedSources.values()].includes(source)||!!(manualBatch?.kind==='audio'&&!manualBatch.stopped&&manualBatch.sources.has(source));
const validBudget=value=>Number.isSafeInteger(value)&&value>=-1;
const exceedsBudget=bytes=>budget!==-1&&bytes>budget;
const validSize=response=>{const value=response?.headers.get('Content-Length');return /^\d+$/.test(value||'')&&Number.isSafeInteger(Number(value))?Number(value):0;};
const fullResponse=response=>response?.status===200&&!response.headers.has('Content-Range')&&validSize(response)>0;
const audioSource=source=>{try{const u=new URL(source);return u.origin==='https://raw.githubusercontent.com'&&u.pathname.startsWith('/Roylyl/Music/main/')&&u.pathname.endsWith('.mp3');}catch{return false;}};
const assetSource=(kind,source)=>{try{
  const u=new URL(source);if(u.pathname.split('/').some(part=>{try{const decoded=decodeURIComponent(part);return ['.','..'].includes(decoded)||decoded.includes('/')||decoded.includes('\\')||decoded.includes('\u0000');}catch{return true;}}))return false;
  return kind==='lyrics'?u.origin==='https://raw.githubusercontent.com'&&u.pathname.startsWith('/Roylyl/Music/main/')&&u.pathname.endsWith('.lrc'):
    kind==='covers'&&u.origin==='https://raw.githubusercontent.com'&&u.pathname.startsWith('/Roylyl/Music/main/')&&/\.(?:jpe?g|png|webp)$/i.test(u.pathname);
}catch{return false;}};
const musicClient=client=>{try{return new URL(client.url).pathname.startsWith('/music/');}catch{return false;}};
async function cleanShells(){
  await serial(async()=>{
    const clients=(await self.clients.matchAll({type:'window',includeUncontrolled:true})).filter(musicClient),live=new Set(clients.map(client=>client.id));
    for(const id of shellClients.keys())if(!live.has(id))shellClients.delete(id);
    const unknown=clients.filter(client=>!shellClients.has(client.id));
    if(unknown.length){
      // Replies arrive as separate message events. Do not wait for them here:
      // legacy pages may never reply and must keep their possible assets.
      for(const client of unknown)try{client.postMessage({type:'REPORT_SHELL'});}catch{}
      return;
    }
    const keep=new Set([SHELL,...shellClients.values()]),current=shellPattern.exec(SHELL);
    for(const name of await caches.keys()){
      const version=shellPattern.exec(name);
      // A newer installed shell may belong to the waiting worker.
      if(version&&!keep.has(name)&&(version[1]<current[1]||(version[1]===current[1]&&Number(version[2])<Number(current[2]))))await caches.delete(name);
    }
  });
}
async function ensureFreshSources(){
  if(!freshRead)freshRead=(async()=>{
    try{
      const saved=await (await caches.open(META)).match(freshKey),sources=await saved?.json();
      if(Array.isArray(sources))for(const source of sources)if(typeof source==='string'&&audioSource(source))freshSources.add(source);
    }catch{/* Storage failures must not prevent network playback. */}
  })();
  await freshRead;
}
async function saveFreshSources(){await (await caches.open(META)).put(freshKey,new Response(JSON.stringify([...freshSources]),{headers:{'Content-Type':'application/json'}}));}
async function ensureConfig(){
  await ensureFreshSources();
  if(configRevision>0)return;
  if(!configRead){const revision=configRevision;configRead=(async()=>{
    try{const saved=await (await caches.open(META)).match(configKey),config=await saved?.json();
      if(config&&revision===configRevision){if(validBudget(config.budget))budget=config.budget;preloadEnabled=config.preload!==false;}
    }catch{/* Cache availability must not determine whether audio plays. */}
  })();}await configRead;
}
async function saveConfig(){await (await caches.open(META)).put(configKey,new Response(JSON.stringify({budget,preload:preloadEnabled}),{headers:{'Content-Type':'application/json'}}));}
async function readIndex(){
  try{
    const saved=await (await caches.open(META)).match(metaKey);
    if(saved){const index=await saved.json();if(index&&typeof index==='object'&&!Array.isArray(index)&&!indexNeedsRepair)return index;}
    // Legacy Cache.put entries were atomic complete responses. Migrate their
    // exposed sizes once; settings never reads every audio body to total usage.
    const index={},cache=await caches.open(AUDIO);
    for(const key of await cache.keys()){
      const hit=await cache.match(key),size=validSize(hit);
      if(fullResponse(hit))index[key.url]={bytes:size,used:0,version:new URL(key.url).searchParams.get('v')||'',complete:true};
      else await cache.delete(key);
    }
    try{await saveIndex(index);indexNeedsRepair=false;}catch{indexNeedsRepair=true;}return index;
  }catch{return {};}
}
async function saveIndex(index){await (await caches.open(META)).put(metaKey,new Response(JSON.stringify(index),{headers:{'Content-Type':'application/json'}}));}
const indexBytes=index=>Object.values(index).reduce((n,x)=>n+(Number.isSafeInteger(x.bytes)&&x.bytes>0?x.bytes:0),0);
async function stats(port){await serial(async()=>{
  const index=await readIndex(),keys=new Set((await (await caches.open(AUDIO)).keys()).map(key=>key.url));let changed=false;
  for(const source of Object.keys(index))if(!keys.has(source)){delete index[source];changed=true;}
  if(changed)await saveIndex(index);
  port?.postMessage({type:'AUDIO_STATS',count:Object.keys(index).length,bytes:indexBytes(index)});
});}
self.addEventListener('install',event=>event.waitUntil((async()=>{
  const cache=await caches.open(SHELL);
  try{
    await cache.addAll(FILES);
    const catalog=await (await cache.match(FILES.find(file=>file.startsWith('/music/data/catalog.json')))).json();
    if(!Array.isArray(catalog?.tracks)||!catalog.tracks.length||catalog.tracks.some(track=>!track.id))throw Error('Invalid offline catalog');
  }catch(error){await caches.delete(SHELL);throw error;}
  // The APK ships the shell atomically. Audio/cache state survives activation.
  await self.skipWaiting();
})()));
self.addEventListener('activate',event=>event.waitUntil((async()=>{
  await cleanShells();
  await self.clients.claim();
})()));
self.addEventListener('message',event=>{
  const data=event.data||{},port=event.ports?.[0];
  const run=task=>event.waitUntil(task.catch(()=>port?.postMessage({ok:false})));
  if(data.type==='ACTIVATE_UPDATE')event.waitUntil(self.skipWaiting());
  if(data.type==='SHELL_CLIENT'&&event.source?.id&&typeof data.shell==='string'&&shellPattern.test(data.shell)){
    shellClients.set(event.source.id,data.shell);
    run(cleanShells().then(()=>port?.postMessage({ok:true})));
  }
  if(data.type==='AUDIO_CONFIG'){
    const previousBudget=budget;configRevision++;budget=validBudget(data.budget)?data.budget:-1;
    if(manualBatch?.kind==='audio'&&budget!==-1&&(previousBudget===-1||budget<previousBudget))stopManualBatch(manualBatch,'budget','缓存上限已调整，下载已停止','budget-exceeded');
    preloadEnabled=data.preload!==false;if(!budget||!preloadEnabled){optionalEpoch++;lastKeptSource='';}protectedSources.set(event.source?.id||'current',data.current||'');
    for(const [key,job] of inflight)if(job.optional&&(!budget||(!preloadEnabled&&job.preload))||job.playback&&!protectedSource(key))cancelJob(key,job);
    run((async()=>{await serial(saveConfig);port?.postMessage({ok:true});})());
  }
  if(data.type==='AUDIO_STATS')run(stats(port));
  if(data.type==='AUDIO_DOWNLOADS')run(audioDownloads(port));
  if(data.type==='DELETE_AUDIO')run(deleteAudio(data.source,port));
  if(data.type==='DOWNLOAD_AUDIO_BATCH'||data.type==='DOWNLOAD_ASSET_BATCH')run(downloadAudioBatch(data,port,event.source?.id||''));
  if(data.type==='CLEAR_ASSET_CACHE')run(clearAssetCache(data.kind,port));
  if(data.type==='CANCEL_AUDIO_BATCH'){
    const batch=manualBatch;
    if(batch&&batch.id===data.id&&batch.owner===(event.source?.id||'')){
      stopManualBatch(batch,'cancelled','已取消下载','cancelled');
      if(port)run(batch.finished.then(()=>port.postMessage({ok:true})));
    }else if(batch&&batch.id===data.id)port?.postMessage({ok:false,code:'forbidden',reason:'无法取消其他窗口的下载任务'});
    else port?.postMessage({ok:true}); // Already finished/cancelled is an idempotent success.
  }
  if(data.type==='CACHE_AUDIO')run((async()=>{
    await ensureConfig();const version=generation,epoch=optionalEpoch;
    if(!audioSource(data.source)||!budget||(data.preload&&!preloadEnabled)){port?.postMessage({ok:false});return;}
    if(data.preload){
      const owner=protectedSources.get(event.source?.id||'current');
      if(owner&&owner!==data.source&&!await cachedFull(owner)){
        const currentJob=inflight.get(owner);
        if(!currentJob||!await currentJob.stored){port?.postMessage({ok:false});return;}
      }
      if(owner&&owner!==protectedSources.get(event.source?.id||'current')){port?.postMessage({ok:false});return;}
    }
    const hit=await cachedFull(data.source);
    if(hit){port?.postMessage({ok:true});return;}
    // An in-progress playback Range may already be returning a full 200.
    // Wait for its headers before starting a second full download.
    const playing=playingRequests.get(data.source);if(playing)try{await playing;}catch{}
    if(version!==generation||(epoch!==optionalEpoch&&data.source!==lastKeptSource)||!budget||(data.preload&&!preloadEnabled)){port?.postMessage({ok:false});return;}
    const job=startFull(data.source,new Request(data.source),true,!!data.preload);
    port?.postMessage({ok:await job.stored});
  })());
  if(data.type==='CLEAR_AUDIO'){
    if(manualBatch?.kind==='audio')stopManualBatch(manualBatch,'cancelled','音乐缓存已清理，下载已停止','cleared');
    generation++;optionalEpoch++;lastKeptSource='';for(const [key,job] of inflight)if(job.optional)cancelJob(key,job);
    run((async()=>{
      // Clearing can be the first event after the Worker wakes. Restore saved
      // preferences, and preserve config/recovery records while removing audio.
      await ensureConfig();
      await serial(async()=>{await caches.delete(AUDIO);await (await caches.open(META)).delete(metaKey);await saveConfig();port?.postMessage({ok:true});});
    })());
  }
  if(data.type==='CANCEL_PRELOAD'){optionalEpoch++;lastKeptSource=data.keep||'';for(const [key,job] of inflight)if(job.optional&&key!==data.keep)cancelJob(key,job);}
});
function cancelJob(source,job){job.controller.abort();if(inflight.get(source)===job)inflight.delete(source);}
function stopManualBatch(batch,status,reason,code){
  if(batch.stopped)return;
  batch.stopped={status,reason,code};batch.controller?.abort();
  for(const controller of batch.controllers||[])controller.abort();
  batch.cancel();
}
function batchProgress(batch,status='running',reason='',code=''){
  if(batch.activeSources){
    const active=batch.activeSources.entries().next().value;
    batch.current=active?.[0]||'';batch.currentBytes=active?.[1].bytes||0;batch.currentTotalBytes=active?.[1].total||0;
  }
  const totalBytes=[...batch.sizes.values()].reduce((sum,bytes)=>sum+bytes,0);
  try{batch.port?.postMessage({type:'AUDIO_BATCH_PROGRESS',id:batch.id,kind:batch.kind,total:batch.sources.size,
    processed:batch.processed,downloaded:batch.downloaded,cached:batch.cached,failed:batch.failed,missing:batch.missing,
    status,reason,code,current:batch.current,downloadedBytes:batch.downloadedBytes,totalBytes,
    totalBytesKnown:batch.sizes.size===batch.sources.size,currentBytes:batch.currentBytes,
    currentTotalBytes:batch.currentTotalBytes,currentTotalBytesKnown:batch.currentTotalBytes>0,
    lastFailure:batch.lastFailure||null});}catch{}
}
function failedBatchSource(batch,code,reason,source=batch.current){batch.failed++;batch.lastFailure={source,code,reason};}
function finishBatchSource(batch){batch.processed++;batch.current='';batch.currentBytes=0;batch.currentTotalBytes=0;}
async function verifiedCachedAudio(source){
  const cache=await caches.open(AUDIO),hit=await cache.match(source);
  if(!fullResponse(hit))return false;
  const size=validSize(hit);
  // New entries are marked only after checking the entire body. Older entries
  // need that check once before an explicit download may claim them complete.
  let legacyBody=null;
  if(hit.headers.get('X-Roylyl-Audio-Complete')!=='1'){
    legacyBody=await hit.clone().blob();if(legacyBody.size!==size)return false;
  }
  await serial(async()=>{
    if(legacyBody){
      const headers=new Headers(hit.headers);headers.set('X-Roylyl-Audio-Complete','1');
      await cache.put(source,new Response(legacyBody,{status:200,headers}));
    }
    const index=await readIndex();
    if(index[source]?.bytes!==size||index[source]?.complete!==true){
      index[source]={bytes:size,used:Date.now(),version:new URL(source).searchParams.get('v')||'',complete:true};
      await saveIndex(index);
    }
  });
  return true;
}
function countBatchResponse(response,batch,source=batch.current){
  // Fetch exposes decoded text, while Content-Length can describe compressed wire bytes.
  const size=batch.kind==='lyrics'?0:validSize(response),active=batch.activeSources?.get(source);
  if(active)active.total=size;else batch.currentTotalBytes=size;
  if(size)batch.sizes.set(source,size);
  batchProgress(batch);
  if(!response.body)return response;
  const reader=response.body.getReader();let last=0;
  const stream=new ReadableStream({
    async pull(controller){
      try{
        const part=await reader.read();
        if(part.done){controller.close();return;}
        if(active)active.bytes+=part.value.byteLength;else batch.currentBytes+=part.value.byteLength;
        batch.downloadedBytes+=part.value.byteLength;
        const now=Date.now();if(now-last>=500){last=now;batchProgress(batch);}
        controller.enqueue(part.value);
      }catch(error){controller.error(error);}
    },
    cancel(reason){return reader.cancel(reason);}
  },{highWaterMark:0});
  return new Response(stream,{status:response.status,statusText:response.statusText,headers:response.headers});
}
async function audioDownloads(port){
  try{await serial(async()=>{
    const index=await readIndex(),cache=await caches.open(AUDIO),sources=[];
    const keys=new Set((await cache.keys()).map(request=>request.url));let changed=false;
    for(const source of Object.keys(index)){
      if(!keys.has(source)){delete index[source];changed=true;continue;}
      const item=index[source];if(!audioSource(source)||item?.complete!==true)continue;
      const hit=await cache.match(source);
      if(fullResponse(hit)&&hit.headers.get('X-Roylyl-Audio-Complete')==='1'&&item.bytes===validSize(hit))sources.push(source);
    }
    if(changed)await saveIndex(index);
    port?.postMessage({ok:true,sources});
  });}catch{port?.postMessage({ok:false,sources:[],reason:'无法读取已下载歌曲，请检查存储空间',code:'storage'});}
}
async function deleteAudio(source,port){
  if(!audioSource(source)){port?.postMessage({ok:false,reason:'歌曲地址无效',code:'invalid'});return;}
  try{await serial(async()=>{
    if(manualBatch&&!manualBatch.stopped&&manualBatch.sources.has(source)){
      port?.postMessage({ok:false,reason:'该歌曲正在下载，请先取消下载任务',code:'busy'});return;
    }
    sourceEpoch.set(source,(sourceEpoch.get(source)||0)+1);
    const job=inflight.get(source);if(job?.optional)cancelJob(source,job);
    else if(job)inflight.delete(source); // An audio element may still be consuming this stream.
    const cache=await caches.open(AUDIO),index=await readIndex();
    await cache.delete(source);delete index[source];await saveIndex(index);
    port?.postMessage({ok:true});
  });}catch{port?.postMessage({ok:false,reason:'删除失败，请检查存储空间后重试',code:'storage'});}
}
async function clearAssetCache(kind,port){
  if(!assetCaches[kind]){port?.postMessage({ok:false,reason:'缓存类型无效',code:'invalid'});return;}
  assetGenerations[kind]++;
  const batch=manualBatch;
  if(batch?.kind===kind){stopManualBatch(batch,'cancelled','缓存已清理，下载已停止','cleared');await batch.finished;}
  try{await serial(()=>caches.delete(assetCaches[kind]));port?.postMessage({ok:true});}
  catch{port?.postMessage({ok:false,reason:'缓存清理失败，请重试',code:'storage'});}
}
async function cachedAsset(kind,source){
  const hit=await (await caches.open(assetCaches[kind])).match(source);
  if(!hit)return false;
  if(kind==='covers')return hit.status===200;
  const saved=Number(hit.headers.get('X-Roylyl-Cached-At')||0),age=saved>0?Math.max(0,Date.now()-saved):Infinity;
  if((hit.status===404||hit.status===410)&&age<86400000)return 'missing';
  return hit.status===200;
}
async function storeAsset(kind,source,response,version,signal,batch,outcome){
  const fail=(code,reason)=>{Object.assign(outcome,{code,reason});return false;};
  const changed=()=>signal.aborted||version!==assetGenerations[kind];
  if(changed())return fail('cancelled','下载已取消');
  if(!response.ok&&!(kind==='lyrics'&&(response.status===404||response.status===410)))return fail('http',`资源服务器返回${response.status}，无法下载`);
  let body,status=200,headers,phase='network';
  try{
    if(kind==='lyrics'&&(response.status===404||response.status===410)){
      body='';status=404;outcome.missing=true;batch.sizes.set(source,0);
      const active=batch.activeSources?.get(source);if(active)active.total=0;else batch.currentTotalBytes=0;
      // Missing-file headers are conclusive; do not wait for an error body.
      response.body?.cancel().catch(()=>{});
    }else{
      const blob=await response.blob(),size=validSize(response);
      if(kind!=='lyrics'&&size&&blob.size!==size)return fail('incomplete','资源未完整下载，请重试');
      if(kind==='lyrics'){
        const text=await blob.text();
        if(/^\s*(?:<!doctype\s+html|<html[\s>])/i.test(text))return fail('invalid-content','歌词服务器返回了网页，请稍后重试');
        const missing=self.RoylylLyrics.parseLrc(text).diagnostic==='empty';
        body=missing?'':text;status=missing?404:200;outcome.missing=missing;
        batch.sizes.set(source,blob.size);
      }else{
        const type=response.headers.get('Content-Type')||'';
        if(!blob.size||(type&&!/^image\//i.test(type)))return fail('invalid-content','专辑封面资源无效');
        body=blob;
        if(!batch.sizes.has(source))batch.sizes.set(source,blob.size);
      }
    }
    if(changed())return fail('cancelled','下载已取消');
    headers=kind==='lyrics'?new Headers({'Content-Type':'text/plain; charset=utf-8','X-Roylyl-Cached-At':String(Date.now())}):new Headers(response.headers);
    phase='storage';
    return await serial(async()=>{
      if(changed())return fail('cancelled','下载已取消');
      await (await caches.open(assetCaches[kind])).put(source,new Response(body,{status,headers}));
      return true;
    });
  }catch(error){
    return fail(signal.aborted?'cancelled':error?.name==='QuotaExceededError'?'quota':phase,
      signal.aborted?'下载已取消':error?.name==='QuotaExceededError'?'设备存储空间不足，下载已停止':phase==='storage'?'无法保存资源，请检查存储空间':'资源下载中断，请检查网络');
  }
}
async function downloadLyricBatch(batch,sources,version){
  batch.controllers=new Set();batch.activeSources=new Map();
  let next=0;
  const stopped=()=>batch.stopped||version!==assetGenerations.lyrics;
  async function consume(){
    while(!stopped()&&next<sources.length){
      const source=sources[next++];let controller,timer,timedOut=false;
      batch.activeSources.set(source,{bytes:0,total:0});batchProgress(batch);
      try{
        const cached=await cachedAsset('lyrics',source);
        if(stopped())break;
        if(cached){
          if(cached==='missing')batch.missing++;else batch.cached++;
          batch.processed++;batch.sizes.set(source,0);continue;
        }
        controller=new AbortController();batch.controllers.add(controller);
        timer=setTimeout(()=>{timedOut=true;controller.abort();},12000);
        const response=await fetch(new Request(source),{signal:controller.signal,cache:'no-cache'}),outcome={};
        const missing=response.status===404||response.status===410;
        const stored=await storeAsset('lyrics',source,missing?response:countBatchResponse(response,batch,source),
          version,controller.signal,batch,outcome);
        if(stopped())break;
        if(stored){if(outcome.missing)batch.missing++;else batch.downloaded++;}
        else{
          if(timedOut){outcome.code='timeout';outcome.reason='歌词下载超时，已跳过，可稍后重试';}
          failedBatchSource(batch,outcome.code,outcome.reason,source);
          if(outcome.code==='quota'||outcome.code==='storage')stopManualBatch(batch,'error',outcome.reason,outcome.code);
        }
        batch.processed++;
      }catch(error){
        if(stopped())break;
        const code=!controller?'storage':timedOut?'timeout':'network';
        const reason=code==='storage'?'无法访问歌词缓存，请检查存储空间':timedOut?'歌词下载超时，已跳过，可稍后重试':'歌词下载失败，已跳过，可稍后重试';
        failedBatchSource(batch,code,reason,source);batch.processed++;
        if(code==='storage')stopManualBatch(batch,'error',reason,code);
      }finally{
        clearTimeout(timer);controller?.abort();batch.controllers.delete(controller);batch.activeSources.delete(source);
        if(!batch.stopped)batchProgress(batch);
      }
    }
  }
  // Small text files can run concurrently without letting one slow 404 stall the queue.
  await Promise.all(Array.from({length:Math.min(4,sources.length)},consume));
}
async function downloadAudioBatch(data,port,owner){
  const kind=data.type==='DOWNLOAD_AUDIO_BATCH'?'audio':data.kind;
  const label=kind==='audio'?'歌曲':kind==='lyrics'?'歌词':'专辑封面',unit=kind==='audio'?'首':kind==='lyrics'?'份':'张';
  const sources=Array.isArray(data.sources)?[...new Set(data.sources)]:[];
  const fail=(reason,code)=>{try{port?.postMessage({type:'AUDIO_BATCH_PROGRESS',id:data.id,total:sources.length,
    processed:0,downloaded:0,cached:0,failed:0,status:'error',reason,code,downloadedBytes:0,totalBytes:0,totalBytesKnown:false});}catch{}};
  if(!['audio','lyrics','covers'].includes(kind)||typeof data.id!=='string'||!data.id||data.id.length>128||!owner||!sources.length||sources.length>10000||sources.some(source=>kind==='audio'?!audioSource(source):!assetSource(kind,source))){fail('没有可下载的有效资源','invalid');return;}
  await ensureConfig();
  if(manualBatch){fail('已有下载任务正在进行','busy');return;}
  let cancel,finish;const cancelled=new Promise(resolve=>{cancel=resolve;}),finished=new Promise(resolve=>{finish=resolve;});
  const batch={id:data.id,kind,owner,port,sources:new Set(sources),processed:0,downloaded:0,cached:0,failed:0,missing:0,
    downloadedBytes:0,currentBytes:0,currentTotalBytes:0,current:'',sizes:new Map(),stopped:null,controller:null,cancel,cancelled,finished,finish};
  manualBatch=batch;
  const version=kind==='audio'?generation:assetGenerations[kind],heartbeat=setInterval(()=>{if(!batch.stopped)batchProgress(batch);},1000);
  try{
    batchProgress(batch);
    if(kind==='audio'&&!budget)stopManualBatch(batch,'budget','音乐缓存已关闭，请先设置缓存上限','budget-disabled');
    if(kind==='lyrics')await downloadLyricBatch(batch,sources,version);
    else for(const source of sources){
      if(batch.stopped)break;
      batch.current=source;batch.currentBytes=0;batch.currentTotalBytes=0;batchProgress(batch);
      try{
        const cached=kind==='audio'?await verifiedCachedAudio(source):await cachedAsset(kind,source);
        if(cached){
          if(batch.stopped)break;
          if(cached==='missing')batch.missing++;else batch.cached++;
          finishBatchSource(batch);batch.sizes.set(source,0);batchProgress(batch);continue;
        }
        // Joining an existing optional/playback copy must not transfer ownership:
        // cancelling this batch can never abort a playing or preloading request.
        const shared=kind==='audio'?inflight.get(source):null;
        if(shared){
          let waitTimer;const timeout=new Promise(resolve=>{waitTimer=setTimeout(()=>resolve(false),120000);});
          let ready;
          try{ready=await Promise.race([shared.stored,batch.cancelled,timeout]);}finally{clearTimeout(waitTimer);}
          if(batch.stopped)break;
          if(ready&&await verifiedCachedAudio(source)){
            batch.cached++;finishBatchSource(batch);batch.sizes.set(source,0);batchProgress(batch);continue;
          }
        }
        if(batch.stopped||version!==(kind==='audio'?generation:assetGenerations[kind]))break;
        const controller=new AbortController(),epoch=sourceEpoch.get(source)||0;batch.controller=controller;let timedOut=false;
        const timer=setTimeout(()=>{timedOut=true;controller.abort();},120000);
        const outcome={};
        try{
          const response=await fetch(new Request(source),{signal:controller.signal,
            ...(kind==='lyrics'?{cache:'no-cache'}:freshSources.has(source)?{cache:'no-store'}:{})});
          const counted=countBatchResponse(response,batch);
          const stored=kind==='audio'?await storeFull(source,counted,version,controller.signal,epoch,outcome):
            await storeAsset(kind,source,counted,version,controller.signal,batch,outcome);
          if(timedOut){outcome.code='timeout';outcome.reason=`${label}下载超时`;}
          if(batch.stopped)break;
          if(stored){if(outcome.missing)batch.missing++;else batch.downloaded++;}
          else{
            failedBatchSource(batch,outcome.code,outcome.reason);
            if(outcome.code==='budget-disabled'||outcome.code==='budget-exceeded')stopManualBatch(batch,'budget',outcome.reason,outcome.code);
            else if(outcome.code==='quota'||outcome.code==='storage')stopManualBatch(batch,'error',outcome.reason,outcome.code);
          }
          finishBatchSource(batch);
          if(!stored&&!batch.stopped)batchProgress(batch,'running',outcome.reason,outcome.code);
        }catch(error){
          if(batch.stopped)break;
          const reason=timedOut?`${label}下载超时`:`${label}下载失败，请检查网络`;
          failedBatchSource(batch,timedOut?'timeout':'network',reason);finishBatchSource(batch);
          batchProgress(batch,'running',reason,timedOut?'timeout':'network');
        }finally{clearTimeout(timer);controller.abort();batch.controller=null;}
      }catch(error){
        if(batch.stopped)break;
        failedBatchSource(batch,'storage',`无法访问${label}缓存，请检查存储空间`);finishBatchSource(batch);stopManualBatch(batch,'error',`无法访问${label}缓存，请检查存储空间`,'storage');
      }
      if(!batch.stopped)batchProgress(batch);
    }
    const stopped=batch.stopped;
    batchProgress(batch,stopped?.status||'completed',stopped?.reason||
      (batch.failed?`${batch.failed}${unit}${label}下载失败：${batch.lastFailure?.reason||'请重试'}`:''),stopped?.code||(batch.failed?'partial':''));
  }catch(error){batchProgress(batch,'error','下载任务中断，请重试','storage');}
  finally{clearInterval(heartbeat);if(manualBatch===batch)manualBatch=null;batch.finish();}
}
async function cachedFull(source){
  try{
    const response=await (await caches.open(AUDIO)).match(source);if(!fullResponse(response))return null;
    serial(async()=>{const index=await readIndex();if(index[source]){index[source].used=Date.now();await saveIndex(index);}}).catch(()=>{});
    return response;
  }catch{return null;}
}
async function cachedRange(response,range,method='GET'){
  const size=validSize(response),headers=new Headers(response.headers);headers.set('Accept-Ranges','bytes');
  const complete=()=>method==='HEAD'?new Response(null,{status:200,headers}):response;
  if(!range||!size)return complete();
  const match=/^bytes=(\d*)-(\d*)$/.exec(range);
  if(!match||(!match[1]&&!match[2]))return complete();
  let start,end;
  if(!match[1]){start=Math.max(0,size-Number(match[2]));end=size-1;}
  else{start=Number(match[1]);end=match[2]?Math.min(Number(match[2]),size-1):size-1;}
  if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start>=size||end<start){headers.set('Content-Range',`bytes */${size}`);headers.set('Content-Length','0');return new Response(null,{status:416,headers});}
  headers.set('Content-Range',`bytes ${start}-${end}/${size}`);headers.set('Content-Length',String(end-start+1));
  return new Response(method==='HEAD'?null:(await response.blob()).slice(start,end+1),{status:206,headers});
}
async function storeFull(source,response,version,signal,epoch=sourceEpoch.get(source)||0,outcome){
  const fail=(code,reason)=>{if(outcome)Object.assign(outcome,{code,reason});return false;};
  const size=validSize(response);
  if(version!==generation||signal.aborted)return fail('cancelled','下载已取消');
  if(!fullResponse(response))return fail(response?.status!==200?'http':'incomplete',
    response?.status!==200?`歌曲服务器返回${response?.status||'异常'}，无法下载`:'服务器未提供完整歌曲文件');
  if(!budget||exceedsBudget(size))return fail(!budget?'budget-disabled':'budget-exceeded',
    !budget?'音乐缓存已关闭，请先设置缓存上限':'缓存空间不足，请提高缓存上限后重试');
  let phase='network';
  try{
    if(outcome&&budget!==-1){
      const protectedBytes=await serial(async()=>Object.entries(await readIndex()).reduce((sum,[key,item])=>
        sum+(key!==source&&protectedSource(key)&&Number.isSafeInteger(item.bytes)&&item.bytes>0?item.bytes:0),0));
      if(exceedsBudget(protectedBytes+size))return fail('budget-exceeded','缓存空间不足，请提高缓存上限后重试');
    }
    // Only the optional cache copy waits for completion. The audio element gets
    // its streaming response immediately. Count actual bytes before publishing.
    const reader=response.body.getReader(),chunks=[];
    let received=0,lastReport=0;
    try {
      while(true){
        if(signal.aborted||version!==generation||epoch!==(sourceEpoch.get(source)||0)){
          await reader.cancel();return fail('cancelled','下载已取消');
        }
        const part=await reader.read();
        if(part.done)break;
        chunks.push(part.value);received+=part.value.byteLength;
        if(Date.now()-lastReport>=250||received===size){
          lastReport=Date.now();
          const progress={type:'AUDIO_DOWNLOAD_PROGRESS',source,bytes:received,total:size};
          self.clients.matchAll().then(clients=>{for(const client of clients)client.postMessage(progress);}).catch(()=>{});
        }
      }
    } finally {reader.releaseLock();}
    const body=new Blob(chunks,{type:response.headers.get('Content-Type')||'audio/mpeg'});
    if(body.size!==size)return fail('incomplete','歌曲文件未完整下载，请重试');
    if(version!==generation||signal.aborted)return fail('cancelled','下载已取消');
    phase='storage';
    return await serial(async()=>{
      if(version!==generation||signal.aborted||epoch!==(sourceEpoch.get(source)||0))return fail('cancelled','下载已取消');
      if(exceedsBudget(size))return fail('budget-exceeded','缓存空间不足，请提高缓存上限后重试');
      const cache=await caches.open(AUDIO),index=await readIndex();
      const existing=await cache.match(source);
      if(!freshSources.has(source)&&index[source]&&fullResponse(existing)&&existing.headers.get('X-Roylyl-Audio-Complete')==='1')return true;
      // Capacity limits stop new writes. User-owned saved songs are never evicted.
      const used=indexBytes(index)-(index[source]?.bytes||0);
      if(exceedsBudget(used+size))return fail('budget-exceeded','缓存已达到上限，已有歌曲会保留；请提高上限或手动清理');
      const headers=new Headers(response.headers);headers.set('Content-Length',String(body.size));headers.set('X-Roylyl-Audio-Complete','1');
      try{await cache.put(source,new Response(body,{status:200,headers}));}
      catch(error){return fail(error?.name==='QuotaExceededError'?'quota':'storage',
        error?.name==='QuotaExceededError'?'设备存储空间不足，下载已停止':'无法保存歌曲，请检查存储空间');}
      index[source]={bytes:body.size,used:Date.now(),version:new URL(source).searchParams.get('v')||'',complete:true};
      try{await saveIndex(index);}catch{indexNeedsRepair=true;return fail('storage','歌曲已保存，但缓存记录写入失败，请检查存储空间');}
      try{for(const c of await self.clients.matchAll())try{c.postMessage({type:'AUDIO_CACHED',source});}catch{}}catch{}
      return true;
    });
  }catch(error){return fail(signal.aborted?'cancelled':error?.name==='QuotaExceededError'?'quota':phase,
    signal.aborted?'下载已取消':error?.name==='QuotaExceededError'?'设备存储空间不足，下载已停止':phase==='storage'?'无法保存歌曲，请检查存储空间':'歌曲下载中断，请检查网络');}
}
function startFull(source,request,optional,preload=false,providedResponse,requestGeneration=generation,requestEpoch=sourceEpoch.get(source)||0){
  let job=inflight.get(source);
  if(job){if(!optional){job.optional=false;job.playback=true;}if(!preload)job.preload=false;return job;}
  const controller=new AbortController(),version=requestGeneration,epoch=requestEpoch;
  const response=providedResponse?Promise.resolve(providedResponse):fetch(request,{signal:controller.signal,...(freshSources.has(source)?{cache:'no-store'}:{})});
  job={controller,optional,preload,playback:!optional,response,stored:null};
  job.stored=response.then(value=>storeFull(source,value.clone(),version,controller.signal,epoch)).catch(()=>false).then(ok=>{if(!ok&&job.optional)controller.abort();return ok;}).finally(()=>{if(inflight.get(source)===job)inflight.delete(source);});
  inflight.set(source,job);return job;
}
async function audioFetch(event){
  await ensureConfig();
  const source=event.request.url,range=event.request.headers.get('Range'),method=event.request.method;
  const recovery=new URL(source),attempt=recovery.searchParams.get('recovery');
  if(attempt){
    recovery.searchParams.delete('recovery');const original=recovery.href;
    let repair=recoveryRequests.get(original);
    if(repair?.attempt!==attempt){
      // no-store does not replace the browser's old HTTP entry. Keep bypassing
      // it even after a fresh application cache entry is stored and evicted.
      freshSources.add(original);
      sourceEpoch.set(original,(sourceEpoch.get(original)||0)+1);
      const job=inflight.get(original);if(job?.optional)cancelJob(original,job);
      else inflight.delete(original); // Do not interrupt another tab's playback.
      playingRequests.delete(original);
      repair={attempt,done:serial(async()=>{
        // Keep the saved recording until a complete replacement is committed.
        try{await saveFreshSources();}catch{/* The in-memory bypass still applies. */}
      }).catch(()=>{})};recoveryRequests.set(original,repair);
    }
    await repair.done;
    // Keep the unique URL and Range header; bypass both application and HTTP cache.
    return fetch(event.request,{cache:'no-store'});
  }
  const hit=await cachedFull(source);if(hit)return cachedRange(hit,range,method);
  if(method==='HEAD')return fetch(event.request,freshSources.has(source)?{cache:'no-store'}:{});
  const playback=event.request.destination==='audio';
  if(playback)protectedSources.set(event.clientId||'current',source);
  if(range){
    if(playback&&budget){
      // A full 200 response is valid when a server ignores Range. Share one
      // stream with playback while storeFull drains its independent clone.
      const headers=new Headers(event.request.headers);headers.delete('Range');
      const job=startFull(source,new Request(event.request,{headers}),false,false);
      event.waitUntil(job.stored);
      const response=await job.response;
      if(fullResponse(response)){
        if(/^bytes=0-\d*$/i.test(range))return response.clone();
        // Seeking waits for this same full transfer instead of opening a second
        // network range. A failed storage write may still serve the shared body.
        const stored=await job.stored,complete=stored&&await cachedFull(source);
        return cachedRange(complete||response.clone(),range,method);
      }
    }
    // Network ranges stay intact. An ignored Range (200) can feed the optional
    // full cache, but a genuine 206 can never enter that cache.
    const version=generation,epoch=sourceEpoch.get(source)||0,promise=fetch(event.request,freshSources.has(source)?{cache:'no-store'}:{});playingRequests.set(source,promise);
    try{
      const response=await promise;
      if(fullResponse(response)&&budget&&epoch===(sourceEpoch.get(source)||0))event.waitUntil(startFull(source,event.request,!playback,false,response.clone(),version,epoch).stored);
      return response;
    }finally{if(playingRequests.get(source)===promise)playingRequests.delete(source);}
  }
  const preload=!playback&&!protectedSource(source);
  const playing=playingRequests.get(source);if(playing)try{await playing;}catch{}
  const job=startFull(source,event.request,!playback,preload);event.waitUntil(job.stored);
  return (await job.response).clone();
}
async function shellResponse(request){
  try{
    const cache=await caches.open(SHELL),hit=await cache.match(request);if(hit)return hit;
    // An already-open tab can still request a prior release's versioned script.
    for(const name of (await caches.keys()).filter(name=>name.startsWith('roylyl-music-shell-')&&name!==SHELL)){
      const previous=await (await caches.open(name)).match(request);if(previous)return previous;
    }
  }catch{}
  return fetch(request);
}
self.addEventListener('fetch',event=>{
  const u=new URL(event.request.url);
  if(audioSource(u.href)&&['GET','HEAD'].includes(event.request.method)){event.respondWith(audioFetch(event));return;}
  if(u.origin!==self.location.origin||!u.pathname.startsWith('/music/')||event.request.method!=='GET')return;
  if(event.request.mode==='navigate'&&(u.pathname==='/music/'||u.pathname==='/music/index.html')){
    // AssetLoader is available offline too; prefer the current APK's HTML.
    event.respondWith((async()=>{try{const response=await fetch(event.request);if(response.ok)return response;}catch{}const hit=await (await caches.open(SHELL)).match('/music/');return hit||Response.error();})());return;
  }
  if(FILES.some(file=>file.split('?')[0]===u.pathname))event.respondWith(shellResponse(event.request));
});
