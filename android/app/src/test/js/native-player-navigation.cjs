'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict'),path=require('path');
const source=fs.readFileSync(path.join(__dirname,'../../main/assets/music/app.js'),'utf8');
const part=(from,to)=>{const a=source.indexOf(from),b=source.indexOf(to,a);assert(a>=0&&b>a);return source.slice(a,b);};
const nodes=new Map(),$=id=>{if(!nodes.has(id))nodes.set(id,{hidden:true,open:false});return nodes.get(id);};
let full=false;
const ctx={$,current:{id:'song'},view:'home',selected:null,artistContext:null,activePlaylist:null,navigationDepth:0,fullNavigationClosing:false,
 nativeCatalogReady:true,nativePendingCommands:[],isFullOpen:()=>full,closeTrackMenu(){ $('track-more-menu').hidden=true; },closeFullMore(){ $('full-more-menu').hidden=true; },
 toggleQueue:open=>{$('queue-panel').hidden=!open;$('queue-backdrop').hidden=!open;},toggleFullQueue:open=>{$('full-queue').hidden=!open;$('full-queue-backdrop').hidden=!open;},notifyNativePlayback(){},notifyNativeNavigation(){}};
vm.createContext(ctx);vm.runInContext(part('  function nativeOverlayOpen()','  function nativeSettingsState()')+part('  function nativeNavigationState()','  function notifyNativeNavigation()')+part('  function nativeCommand(','  function nativePulse()'),ctx);
assert.equal(ctx.nativeNavigationState().miniPlayerVisible,true);
assert.equal(ctx.nativeCommand('openQueue'),true);assert.equal($('queue-panel').hidden,false);assert.equal(ctx.nativeNavigationState().miniPlayerVisible,false);assert.equal(ctx.nativeNavigationState().overlayOpen,true);
ctx.toggleQueue(false);$('track-more-menu').hidden=false;assert.equal(ctx.nativeNavigationState().miniPlayerVisible,false);$('track-more-menu').hidden=true;
ctx.view='settings';assert.equal(ctx.nativeNavigationState().miniPlayerVisible,true);assert.equal(ctx.nativeNavigationState().pageKey,'settings');
full=true;assert.equal(ctx.nativeNavigationState().fullPlayerOpen,true);assert.equal(ctx.nativeNavigationState().miniPlayerVisible,false);ctx.nativeCommand('openQueue');assert.equal($('full-queue').hidden,false);
full=false;ctx.toggleFullQueue(false);ctx.fullNavigationClosing=true;assert.equal(ctx.nativeNavigationState().miniPlayerVisible,false);ctx.fullNavigationClosing=false;
ctx.current=null;assert.equal(ctx.nativeNavigationState().miniPlayerVisible,false);ctx.current={id:'restored'};assert.equal(ctx.nativeNavigationState().miniPlayerVisible,true);
console.log('PASS: native mini visibility tracks restored songs, settings, menus, queues/fullscreen and close transitions; queue command routes correctly');
