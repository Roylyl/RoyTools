'use strict';
(() => {
  const ROOT='https://raw.githubusercontent.com/Roylyl/Music/main/';
  const DB='roylyl-music-catalog-v1',STORE='catalog',KEY='current';
  const semver=value=>typeof value==='string'&&/^\d{1,6}\.\d{1,6}\.\d{1,6}$/.test(value);
  const record=value=>!!value&&typeof value==='object'&&!Array.isArray(value);
  const text=value=>typeof value==='string'&&value.length>0&&value.length<=2048;
  function safePath(value) {
    if(!text(value)||value.startsWith('/')||value.includes('\\')||/[\u0000-\u001f?#]/.test(value)||/^[a-z][a-z\d+.-]*:/i.test(value))return false;
    return value.split('/').every(part=>part&&part!=='.'&&part!=='..'&&!/%(?:2f|5c|2e|00)/i.test(part));
  }
  function validate(catalog,{remote=false}={}) {
    if(!record(catalog)||!text(catalog.version)||remote&&!semver(catalog.version)||catalog.schemaVersion!==undefined&&catalog.schemaVersion!==1||
       !Array.isArray(catalog.tracks)||!catalog.tracks.length||catalog.tracks.length>100000||!record(catalog.audioProperties)||!record(catalog.appleMusic))throw Error('曲库目录格式不正确');
    const ids=new Set();
    for(const track of catalog.tracks) {
      if(!record(track)||!text(track.id)||ids.has(track.id)||!text(track.title)||!text(track.artist)||!text(track.album)||!safePath(track.src)||!/\.mp3$/i.test(track.src)||!safePath(track.cover))throw Error('曲库曲目信息不完整');
      ids.add(track.id);
    }
    const apple=catalog.appleMusic;
    if(!record(apple.entries)||!Array.isArray(apple.favorites)||!Array.isArray(apple.playlists)||apple.playlists.length>10000)throw Error('歌单目录格式不正确');
    for(const entry of Object.values(apple.entries)) {
      if(!record(entry)||!text(entry.title)||!text(entry.artist)||entry.trackId&&!ids.has(entry.trackId)||entry.sourceUrl&&(!text(entry.sourceUrl)||!/^https:\/\//i.test(entry.sourceUrl)))throw Error('歌单曲目信息不正确');
    }
    const references=list=>Array.isArray(list)&&list.length<=100000&&list.every(id=>text(id)&&Object.hasOwn(apple.entries,id));
    if(!references(apple.favorites))throw Error('收藏曲目引用不存在');
    const playlistIds=new Set();
    for(const playlist of apple.playlists) {
      if(!record(playlist)||!text(playlist.id)||playlistIds.has(playlist.id)||!text(playlist.name)||!references(playlist.entries))throw Error('歌单信息或曲目引用不正确');
      playlistIds.add(playlist.id);
    }
    return catalog;
  }
  function create({fallbackUrl='./data/catalog.json',onState=()=>{}}={}) {
    let current=null,loading=null,checking=null,dbPromise=null;
    let state={checking:false,status:'idle',version:'',message:'尚未检查曲库更新',persisted:false};
    function update(next) {state={...state,...next,version:current?.version||''};try{onState({...state});}catch{}return {...state};}
    function database() {
      if(dbPromise)return dbPromise;
      dbPromise=new Promise((resolve,reject)=>{
        if(!globalThis.indexedDB){reject(Error('本机无法保存曲库目录'));return;}
        const request=indexedDB.open(DB,1);let settled=false;
        const timer=setTimeout(()=>{settled=true;reject(Error('本地曲库读取超时'));},3000);
        const fail=()=>{if(settled)return;settled=true;clearTimeout(timer);reject(request.error||Error('本地曲库暂不可用'));};
        request.onupgradeneeded=()=>{if(!request.result.objectStoreNames.contains(STORE))request.result.createObjectStore(STORE);};
        request.onerror=fail;request.onblocked=fail;
        request.onsuccess=()=>{if(settled){request.result.close();return;}settled=true;clearTimeout(timer);resolve(request.result);};
      });
      return dbPromise;
    }
    async function stored(write) {
      const db=await database();
      return new Promise((resolve,reject)=>{
        const transaction=db.transaction(STORE,write?'readwrite':'readonly'),store=transaction.objectStore(STORE);
        const request=write?store.put(write,KEY):store.get(KEY);
        const timer=setTimeout(()=>{try{transaction.abort();}catch{}reject(Error('本地曲库操作超时'));},3000);
        transaction.oncomplete=()=>{clearTimeout(timer);resolve(write||request.result||null);};
        transaction.onabort=transaction.onerror=()=>{clearTimeout(timer);reject(transaction.error||Error('无法保存本地曲库'));};
      });
    }
    async function json(address) {
      const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
      try {
        const response=await fetch(address,{cache:'no-cache',credentials:'omit',signal:controller.signal});
        if(!response.ok)throw Error('曲库服务器返回'+response.status);
        if(Number(response.headers.get('Content-Length'))>24000000)throw Error('曲库目录过大');
        const body=await response.text();if(body.length>24000000)throw Error('曲库目录过大');
        return JSON.parse(body);
      } finally {clearTimeout(timer);}
    }
    async function load() {
      if(current)return current;
      if(loading)return loading;
      loading=(async()=>{
        try {const cached=await stored();if(cached){current=validate(cached);update({status:'cached',persisted:true,message:'正在使用本地曲库'});return current;}}catch{}
        current=validate(await json(fallbackUrl));
        let persisted=false;try{await stored(current);persisted=true;}catch{}
        update({status:'cached',persisted,message:'正在使用内置曲库'});return current;
      })();
      try{return await loading;}finally{loading=null;}
    }
    async function check() {
      if(checking)return checking;
      checking=(async()=>{
        update({checking:true,status:'checking',message:'正在检查曲库更新…'});
        try {
          try{await load();}catch{}
          const manifest=await json(ROOT+'catalog-version.json');
          if(!record(manifest)||manifest.schemaVersion!==1||!semver(manifest.version)||manifest.catalog!=='catalog.json')throw Error('曲库版本信息不正确');
          if(current?.version===manifest.version) {
            update({status:'current',message:'已是最新曲库'});
          } else {
            const catalog=validate(await json(ROOT+'catalog.json?v='+encodeURIComponent(manifest.version)),{remote:true});
            if(catalog.version!==manifest.version)throw Error('曲库正在发布，请稍后重试');
            let persisted=false;try{await stored(catalog);persisted=true;}catch{}
            current=catalog;update({status:'updated',persisted,message:persisted?'曲库已更新':'曲库已更新，本机存储暂不可用'});
          }
        } catch(error) {
          const offline=globalThis.navigator?.onLine===false||error?.name==='TypeError'||error?.name==='AbortError';
          update({status:offline?'offline':'error',message:offline?'暂时无法连接，继续使用本地曲库':String(error?.message||'曲库更新失败，继续使用本地曲库')});
        } finally {update({checking:false});}
        return {...state,catalog:current};
      })();
      try{return await checking;}finally{checking=null;}
    }
    return {load,check,get current(){return current;},get state(){return {...state};}};
  }
  globalThis.RoylylCatalogSync={create,validate,safePath};
})();
