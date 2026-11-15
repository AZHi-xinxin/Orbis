// User-provided full-length tracks, bundled locally in the same order on every client.
export const werewolfTracks = Object.freeze([
 '/assets/audio/werewolf-night.mp3',
 '/assets/audio/midnight-round-table-1.mp3',
 '/assets/audio/midnight-round-table-2.mp3',
]);
const preferenceKey='orbis-werewolf-bgm-enabled';
const speaker='<path d="M11 5 6 9H3v6h3l5 4Z"/>';
const waves='<path d="M15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/>';
const muted='<path d="m15 9 6 6m0-6-6 6"/>';

/** One player for this page; no game state, roles, network service or native action. */
export class WerewolfMusic {
 constructor({src=werewolfTracks,notify=()=>{},storageKey=preferenceKey}={}){
  this.tracks=[...new Set((Array.isArray(src)?src:[src]).filter(s=>typeof s==='string'&&/^\/assets\/audio\/[a-z0-9._-]+\.(mp3|ogg|wav)$/.test(s)))];
  this.index=0;this.src=this.tracks[0]||null;
  this.notify=notify;this.active=false;this.epoch=0;this.needsTap=false;
  this.preferenceKey=storageKey;this.enabled=false;try{this.enabled=localStorage.getItem(this.preferenceKey)==='true';}catch{}
  this.audio=new Audio();this.audio.loop=this.tracks.length===1;this.audio.volume=.22;this.audio.preload='none';
  this.ended=()=>{
   if(this.tracks.length<2)return;
   this.index=(this.index+1)%this.tracks.length;this.src=this.tracks[this.index];
   this.audio.src=this.src;this.sync();
  };
  this.visibility=()=>this.sync();
  this.focus=()=>queueMicrotask(()=>this.sync());
  this.pagehide=()=>{this.active=false;this.sync();};
  this.failed=()=>{this.enabled=false;this.needsTap=false;this.save();this.sync();this.notify('背景音乐暂时无法播放，可以稍后重试。');};
  document.addEventListener('visibilitychange',this.visibility);
  window.addEventListener('tabletop-audio-visibility',this.visibility);
  document.addEventListener('focusin',this.focus);document.addEventListener('focusout',this.focus);
  window.addEventListener('pagehide',this.pagehide);this.audio.addEventListener('error',this.failed);this.audio.addEventListener('ended',this.ended);
 }
 save(){try{localStorage.setItem(this.preferenceKey,String(this.enabled));}catch{}}
 shouldPlay(){return !!this.src&&this.enabled&&!this.needsTap&&this.active&&!document.hidden&&window.tabletopAudioActive!==false&&!document.activeElement?.matches('textarea,input,[contenteditable="true"]');}
 setActive(active){this.active=active;this.sync();}
 button(){
  const b=document.createElement('button');b.type='button';b.className='ww-music';
  b.onclick=()=>{if(!this.src){this.notify('背景音乐还未加入。');return;}this.enabled=this.needsTap||!this.enabled;this.needsTap=false;this.save();this.sync();};
  this.control=b;this.render();return b;
 }
 render(){
  const b=this.control;if(!b)return;const on=this.enabled&&!this.needsTap&&!!this.src;
  const icon=on?waves:muted;
  // Focus changes can call sync between pointerdown and pointerup. Keep the
  // existing SVG target attached so a tap on the icon still produces a click.
  if(this.renderedIcon!==icon||!b.firstChild){b.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">'+speaker+icon+'</svg>';this.renderedIcon=icon;}
  const label=this.needsTap?'点击播放背景音乐':on?'关闭背景音乐':'开启背景音乐';
  b.setAttribute('aria-label',label);b.setAttribute('aria-pressed',String(on));b.title=label;
 }
 async sync(){
  const token=++this.epoch;
  if(!this.shouldPlay()){this.audio.pause();this.render();return;}
  if(!this.audio.getAttribute('src'))this.audio.src=this.src;
  try{await this.audio.play();if(token===this.epoch)this.needsTap=false;}
  catch(e){if(token!==this.epoch)return;
   if(e.name==='NotAllowedError'){this.needsTap=true;}
   else if(e.name!=='AbortError'){this.failed();return;}
  }
  if(!this.shouldPlay())this.audio.pause();this.render();
 }
 destroy(){this.active=false;this.sync();document.removeEventListener('visibilitychange',this.visibility);window.removeEventListener('tabletop-audio-visibility',this.visibility);document.removeEventListener('focusin',this.focus);document.removeEventListener('focusout',this.focus);window.removeEventListener('pagehide',this.pagehide);this.audio.removeEventListener('error',this.failed);this.audio.removeEventListener('ended',this.ended);this.audio.removeAttribute('src');this.audio.load();this.control=null;}
}
