import {WerewolfView,el,append,button} from './werewolf-view.js';
import {configure} from './avatar.js';
import {native,callNative} from './native.js';
import {updateRoomCards} from './werewolf-lobby.js';
import {orderLobbyRooms} from './lobby-order.js';
import {WerewolfMusic} from './werewolf-music.js';
const music=new WerewolfMusic({notify:message=>error({message})});
const root=document.querySelector('#app');let view,status,room='',observing=false,watchingWithAi=false,polling=false,entering=false,navigation=0,busy=false,events=[],rules='',drafts={},pending=null,lobbyCards=null,lobbyPolling=false;
const id=()=>crypto.randomUUID();
async function api(path,body,query={}){return callNative('api',{path,body,query});}
function modal(title,...children){const d=el('dialog','ww-modal');append(d,button('关闭',()=>d.close(),'close'),el('h2','',title),...children);document.body.append(d);d.addEventListener('close',()=>d.remove());d.showModal();return d;}
function error(e){const old=root.querySelector('.ww-error');if(old)old.remove();const box=el('div','ww-error',e.message||String(e));root.append(box);setTimeout(()=>box.remove(),6000);}
async function guard(fn){try{return await fn();}catch(e){error(e);}}
function draftKey(channel){return `werewolf-draft:${room}:${status?.self||'observer'}:${channel}`;}
function getDraft(channel){return drafts[channel]??sessionStorage.getItem(draftKey(channel))??'';}
function saveDraft(channel,value){drafts[channel]=value;sessionStorage.setItem(draftKey(channel),value);}
function clearDrafts(){for(let i=sessionStorage.length-1;i>=0;i--){const k=sessionStorage.key(i);if(k?.startsWith('werewolf-draft:'+room+':'))sessionStorage.removeItem(k);}drafts={};}
function history(channel='public'){const log=el('div');for(const e of events.filter(e=>channel==='wolves'?e.channel==='wolves':e.public===true&&e.channel==='public'&&e.kind!=='wolf_message')){const a=status.actors.find(a=>a.id===e.actor);log.append(append(el('article','ww-message'),el('b','',a?`${a.seat}号 · ${a.name}${a.kind==='ai'?' · AI':''}`:'系统主持'),el('p','',e.text)));}const d=modal(channel==='wolves'?'狼队记录':'白天公屏',log);requestAnimationFrame(()=>d.scrollTop=d.scrollHeight);}
function dressing(){const d=modal('狼人杀装扮');for(const [person,label] of [['human','我的装扮'],['ai','AI 的装扮']])d.append(button(label,()=>guard(async()=>{await callNative('wardrobe_editor',{person});d.close();})));}
function rulesModal(){modal('狼人杀规则',el('p','',rules||'正在读取规则…')).querySelector('p').style.whiteSpace='pre-wrap';}
function role(){modal(status.observer?'观战说明':'我的身份',el('p','',status.observer?'观战席可以阅读白天公屏，夜间行动不公开。':status.my_role_name||'开局后系统随机发牌。'))}
function results(){const box=el('div');box.append(el('p','',status.winner==='wolves'?'狼人阵营获胜。':'好人阵营获胜。'));for(const a of status.roles||[])box.append(el('p','',`${a.seat}号 · ${a.name}：${a.role}`));box.append(el('p','','游戏内无复盘，有复盘需求请自行移至 Orbis 朋友圈／群聊社区内自行发言。'));modal('本局结果',box);}
async function write(action,extra={}){
 if(busy||observing)return;busy=true;
 try{
  // Re-use an uncertain request until its receipt is known; never manufacture a second action.
  if(pending){const old=pending;await api(old.path,old.body);pending=null;if(old.body.action==='leave'){await lobby();return;}if(old.body.action==='say'){saveDraft(old.body.channel||'public','');view.key='';}await refresh();return;}
  const body={action,...extra,phase_version:status.phase_version,request_id:id()};pending={path:action==='say'?'script_say':'script_pass',body};
  const result=await api(pending.path,body);pending=null;
  if(action==='leave'){await lobby();return;}
  if(action==='say'){saveDraft(status.night?'wolves':'public','');view.key='';}
  await refresh();
  if(result.result)modal('查验结果',el('div','ww-result',result.result));
 }catch(e){if(e.status>=400&&e.status<500&&![401,403,408].includes(e.status))pending=null;throw e;}
 finally{busy=false;}
}
async function menu(){
 const d=modal('圆桌菜单');
 d.append(button('和原助手聊聊',()=>guard(()=>callNative('quick_chat'))),button('本局 AI 自动提醒',()=>guard(async()=>{const x=await callNative('toggle_ai');d.append(el('p','',x.running?'已开启，轮到它时自动提醒。':'已暂停，可再次开启。'));})),button('刷新连接',()=>guard(async()=>{if(!observing)await callNative(watchingWithAi?'watch':'connect',{room});await refresh();d.close();})));
 if(status.stage==='ended'||(status.stage==='lobby'&&!status.observer))d.append(button(status.mode==='mixed'?'我和助手一起离席':'离席',()=>guard(async()=>{await write('leave');d.close();})));
 if(observing)d.append(button('和当前助手观战',()=>guard(async()=>{await enter(room,false,true);d.close();})));
 d.append(button('返回大厅',()=>{d.close();guard(lobby);}));
}
async function enter(rid,observe=false,watch=false,aiOnly=false){if(entering)return;entering=true;const visit=++navigation;
 try{if(!observe)await callNative(watch?'watch':'connect',{room:rid,ai_only:aiOnly});
 if(visit!==navigation)return;
 room=rid;status=null;observing=observe;watchingWithAi=watch;events=[];drafts={};pending=null;view?.destroy();
 view=new WerewolfView(root,{draft:getDraft,draftChanged:saveDraft,music:()=>music.button(),rules:rulesModal,connected:()=>!observing,reconnect:()=>guard(restoreRoomConnection),role,results,history,menu:()=>guard(menu),back:()=>guard(lobby),seat:n=>{const a=status.actors.find(a=>a.seat===n);if(a)modal(`${n}号 · ${a.name}`,el('p','',a.kind==='ai'?'AI 玩家':'人类玩家'),el('p','',a.alive?(a.afk?'已挂机':'在席'):'观战中'));},act:(a,b)=>guard(()=>write(a,b)),say:(text,channel)=>guard(()=>write('say',{text,channel}))});
 }finally{entering=false;}
 await refresh();}
async function refresh(){if(!room||polling||entering||(status?.stage==='ended'&&status.id===room))return;polling=true;const visit=navigation;try{
 if(pending&&!busy){const old=pending;try{await api(old.path,old.body);pending=null;if(old.body.action==='leave'){await lobby();return;}if(old.body.action==='say'){saveDraft(old.body.channel||'public','');view.key='';}}catch(e){if(e.status>=400&&e.status<500&&![401,403,408].includes(e.status))pending=null;throw e;}}
 const s=observing?await api('observe',null,{room,kind:'status'}):await api('script_status');
 const data=observing?await api('observe',null,{room,kind:'events',cursor:'0'}):await api('script_read',null,{kind:'events',cursor:'0'});
 if(visit!==navigation)return;
 status=s;events=data.items||[];view.update(status,events);music.setActive(true);
 if(s.stage==='ended')clearDrafts();
 }catch(e){if(visit!==navigation)return;if([404,410].includes(e.status)){clearDrafts();room='';view?.destroy();await lobby();modal('本局已散场',el('p','','本桌临时记录已清空，原聊天保留。'));}else if([401,403].includes(e.status)&&!observing){await callNative(watchingWithAi?'watch':'connect',{room});}else throw e;}finally{polling=false;}}
async function refreshLobby(){
 if(!native||room||lobbyPolling||!lobbyCards?.isConnected)return;
 const visit=navigation;const cards=lobbyCards;lobbyPolling=true;
 try{const [data,mine]=await Promise.all([api('catalog'),api('my_room').catch(()=>null)]);if(visit!==navigation)return;rules=data.rules;
  const rooms=orderLobbyRooms((data.rooms||[]).map(r=>({...r,seats:10,membership:mine?.room===r.id?mine:null})));
  updateRoomCards(cards,rooms,{enter:(rid,aiOnly)=>guard(()=>enter(rid,false,false,aiOnly)),observe:rid=>guard(()=>enter(rid,true)),resume:()=>guard(resumeRoom)});
  const returnButton=root.querySelector('.ww-return-current');
  returnButton.title=mine?.watching?'返回原观战桌并恢复助手连接':'返回原对局并恢复助手连接';
  root.querySelector('.ww-lobby-sync').textContent='人数与阶段自动更新 · 观战可随时进出';
 }catch(e){if(visit===navigation){root.querySelector('.ww-lobby-sync').textContent='暂未刷新，请点击“刷新大厅”重试';throw e;}}
 finally{lobbyPolling=false;}
}
let restoringConnection=false;
async function restoreRoomConnection(){
 if(restoringConnection)return;restoringConnection=true;const visit=navigation;
 for(const b of root.querySelectorAll('.ww-reconnect')){b.disabled=true;b.textContent='重联中';}
 try{
 const r=await callNative('restore_connection');
 if(visit!==navigation)return;
 if(!r.room){modal('还没有入席',el('p','','原席位未找到，请在大厅确认当前对局。'));return;}
 if(r.assistant_verified!==true)throw new Error('助手连接尚未确认恢复，请重试。');
 await enter(r.room,false,r.watching===true);
 if(!status||status.id!==r.room){modal('尚未确认恢复',el('p','','未读到当前对局状态，请检查网络后重试。'));return;}
 const notice=el('div','ww-notice','连接已恢复');notice.setAttribute('role','status');root.append(notice);setTimeout(()=>notice.remove(),2500);
 }finally{restoringConnection=false;for(const b of root.querySelectorAll('.ww-reconnect')){b.disabled=false;b.textContent=b.classList.contains('ww-return-current')?'返回对局':'重联';}}
}
async function resumeRoom(){const r=await callNative('resume');if(r.room)await enter(r.room,false,r.watching===true);else modal('还没有入席',el('p','','请选择一张圆桌，或开一张新桌。'));}
async function lobby(){music.setActive(false);++navigation;view?.destroy();room='';view=null;document.body.classList.remove('ww-dark');root.className='ww-root ww-lobby-root';root.replaceChildren();const header=el('header','ww-header');
 const returnButton=button('返回对局',()=>guard(restoreRoomConnection),'ww-return-current ww-reconnect');returnButton.title='返回原对局并恢复助手连接';
 append(header,button('‹',()=>guard(()=>callNative('close')),'ww-back'),el('h1','ww-header-title','星夜狼人杀'),returnButton,button('装扮',dressing,'ww-rules'),button('游戏规则',rulesModal,'ww-rules'));
 const list=el('section','ww-lobby');append(list,append(el('div','ww-lobby-welcome'),el('span','ww-lobby-eyebrow','今晚 · 系统主持'),el('h2','','今夜，一起入席'),el('p','','十人圆桌，和熟悉的助手一起。'),el('small','','入席即准备 · 满员自动开局')));
 lobbyCards=el('div','ww-room-list');lobbyCards.append(el('div','ww-empty-rooms','正在寻找圆桌…'));list.append(lobbyCards);
 const create=el('section','ww-create-room');append(create,el('h3','','开一张新桌'),append(el('div','ww-room-actions'),button('人机共玩',()=>guard(()=>createRoom('mixed')),'primary'),button('纯 AI 局',()=>guard(()=>createRoom('ai_only')))));
 append(list,create,append(el('div','ww-lobby-links'),button('刷新大厅',()=>guard(lobby))),el('p','ww-lobby-sync','正在读取人数与阶段…'));
 root.append(header,list);if(!native){lobbyCards.textContent='请在 Orbis 的「工具与娱乐 → 狼人杀」打开。';return;}
 await refreshLobby();
}
async function createRoom(mode){const result=await api('room',{mode,request_id:id()});await enter(result.room,mode==='ai_only');}
window.tabletopNavigateBack=()=>{const d=document.querySelector('dialog[open]');if(d)d.close();else if(room)guard(lobby);else guard(()=>callNative('close'));};
await guard(async()=>{configure(await(await fetch('/assets/manifest.json')).json(),await(await fetch('/assets/wardrobe.json')).json());await lobby();});
setInterval(()=>{if(room&&!document.hidden)guard(refresh);},2000);
setInterval(()=>{if(!room&&!document.hidden)guard(refreshLobby);},5000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)guard(room?refresh:refreshLobby);});
