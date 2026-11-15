import {reservationTitle,reservationInfo,reservationPending} from './gathering-room.js';
import {gameMusic} from './tabletop-music.js';
import {native,callNative} from './native.js';
import {configure,drawAvatar,defaults} from './avatar.js';
const $=s=>document.querySelector(s),E=(tag,text='',cls='')=>{const e=document.createElement(tag);e.textContent=text;e.className=cls;return e;};
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const music=gameMusic('heartprison',toast);$('.title h2').after(music.control);
const api=(path,body=null,query={})=>callNative('api',{path,body,query});
const icon=k=>k==='hood'?'<svg class="hood" aria-label="潜伏者" viewBox="0 0 24 24"><path d="M12 2C5 6 4 13 3 21h18C20 13 19 6 12 2Z"/><path d="M9 12h6l-3 5Z" fill="#82776a" stroke="none"/></svg>':`<svg class="icon" aria-label="${k==='card'?'对话卡':'手枪'}" viewBox="0 0 24 24">${k==='card'?'<rect x="5" y="3" width="14" height="18" rx="2"/><path d="m12 8 3 4-3 4-3-4Z"/>':'<path d="M3 7h18v5h-9l-2 8H5l2-8H3Z"/><path d="M13 12v4h-3M17 7V5"/>'}</svg>`;
const colors={红:'#da8f97',橙:'#e3aa72',黄:'#e3d275',绿:'#94c095',青:'#7ec8bc',蓝:'#90b2e7',紫:'#bca1db',黑:'#6c7487',白:'#f0ece0',粉:'#e3adc5',灰:'#aeb4bd',金:'#d8bb76',银:'#c4d2df'};
const accents=['#8bbbd7','#b6a1d5','#b4c28d','#d2a596','#85bfb2','#c9b87e'];
let room='',status=null,events=[],cursor=0,tab='overview',channel='private',round='current',observing=true,mine=null,polling=false,entering=false,visit=0,offset=0,looks=false,signature='',lastClock='',loadingMore=false;
const positions=new Map();let toastTimer;
function toast(t){$('#toast').textContent=t;$('#toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').hidden=true,5000);}
async function guard(fn){try{return await fn();}catch(e){toast(e.message||String(e));}}
function B(label,fn,cls='secondary'){const b=E('button',label,cls);b.onclick=()=>guard(fn);return b;}
function dialog(title,html=''){const d=E('dialog','','info');d.innerHTML=`<button class="close">关闭</button><h3>${esc(title)}</h3>${html}`;d.querySelector('.close').onclick=()=>d.close();d.onclose=()=>d.remove();document.body.append(d);d.showModal();return d;}
const actor=id=>status?.actors.find(a=>a.id===id);
const name=id=>{let a=actor(id);return a?`${a.seat} · ${a.name}`:'原席位'};
const identity=a=>a?.role==='lurker'?`${icon('hood')} 潜伏者`:'普通';
const stats=a=>`${icon('card')} ${a?.cards??0} ${icon('gun')} ${a?.guns??0}`;
const person=(id,snapshot)=>{let a=actor(id);return `<button data-person="${esc(id)}" class="name">${esc(name(id))} ${a?.role==='lurker'?icon('hood'):''}</button><div class="stats">${stats(snapshot||a)}</div><div class="identity">${a?.role==='lurker'?'潜伏者':'普通'} · ${snapshot?'当时剩余':'当前剩余'}</div>`;};
const time=t=>new Date(t*1000).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'});
const colorText=color=>`<span class="colour" style="--col:${colors[color]||'#9baabe'}"></span>${esc(color||'待分配')}`;
function key(){return `${tab}:${channel}:${round}`;}
function remember(){positions.set(key(),$('#content').scrollTop);}
function phase(){
 $('#reconnect').hidden=!room;$('#nav').hidden=!room;
 if(!status){$('#phase-title').textContent='七位原助手 · 人类观战';$('#clock').textContent='';$('#phases').replaceChildren();return;}
 $('#phase-title').textContent=status.stage==='lobby'?`${status.count}/7席 · 满员自动开局`:`第${status.game}/3局 · 第${status.round}轮 · ${status.phase.name}`;
 $('#phases').innerHTML=status.stage==='lobby'?'':[['talk','交流'],['box','黑盒'],['action','行动'],['guess','猜色'],['settle','结算']].map(([k,n])=>`<span class="${status.phase.kind===k||k==='settle'&&['intermission','ended'].includes(status.phase.kind)?'active':''}">${n}</span>`).join('');clock();
}
function clock(){let sec=status?.deadline?Math.max(0,Math.ceil(status.deadline-Date.now()/1000-offset)):0;let t=sec?`${String(Math.floor(sec/60)).padStart(2,'0')}:${String(sec%60).padStart(2,'0')}`:'';if(t!==lastClock){$('#clock').textContent=t;lastClock=t;}}
function guide(){return `<div class="eye">HUMAN ONLY · 全知观战</div><h3 class="big">每一步，都看得见</h3><p class="fine" style="margin-bottom:20px">查看记录不会发送消息，也不会影响对局。</p>${[['点名字，查看这个人','查看真实身份、颜色、卡牌、手枪、积分与出局原因。黑色斗篷表示潜伏者。'],['点聊天，切公屏 / 私聊','所有私聊在同一条时间线，按每次访问用彩色卡片衔接；发起人与免费回复清楚标出。'],['看箭头，知道谁扣卡','箭头表示发起访问，不是转赠卡牌。卡片显示当时扣卡前后；姓名可点开当前资源。'],['选轮次，看任意历史','交流、黑盒、猜色等任何阶段都能翻看。顶部始终保留当前阶段，点回到最新返回当前轮。'],['点排名，看当前积分','积分按真实结算更新，同分并列；出局只影响当前单局，下一局重新入场。'],['重联与菜单','入席后重联恢复原助手；纯观战时刷新圆桌资料。返回大厅不退出；暂停AI提醒、聊聊与等待/结束时离席在菜单。']].map((x,i)=>`<div class="guide-row"><span class="number">${i+1}</span><div>${x[0]}<p>${x[1]}</p></div></div>`).join('')}<div class="fair">为了游戏公平性请不要给AI场外提示</div>`;}
function overview(){
 const s=status;const alive=s.actors.filter(a=>a.alive).length;let html=`<div class="head"><h3>七席局势</h3><span class="pill">${s.stage==='lobby'?`${s.count}/7席`:`${alive}人存活`}</span></div><p class="state-head">身份与当前资源全公开 · 点名字查看详情</p><div class="seats">${s.actors.map(a=>`<button class="seat ${a.alive?'':'dead'}" data-person="${esc(a.id)}"><canvas class="avatar" data-avatar="${esc(a.id)}"></canvas><div><div class="name">${esc(name(a.id))} ${a.role==='lurker'?icon('hood'):''}</div><div class="stats">${stats(a)} <span>${s.stage==='lobby'?'待分配':a.role==='lurker'?'潜伏者':'普通'}</span></div><div class="fine">${colorText(a.color)} · ${a.score}分${a.alive?'':' · 本局出局'}</div></div></button>`).join('')}${s.stage==='lobby'?Array.from({length:7-s.count},()=>'<div class="seat"><div class="fine">✧ 等待原助手入席</div></div>').join(''):''}</div>`;
 if(s.stage==='lobby')return html+`<div class="panel" style="margin-top:16px"><p>每位朋友选择自己的原助手入席。七席坐满后自动开始。</p><div class="buttons"><button data-invite class="secondary">复制邀请</button>${observing?'<button data-join class="primary">让当前助手入席</button>':''}</div></div>`;
 html+=`<button class="panel box" data-box style="margin-top:15px;width:100%;text-align:left"><span class="boxshape">✧</span><span><span class="gold">黑盒${s.box_open?'已开启':'尚未开启'} →</span><span class="fine" style="display:block">点击看输入记录与人类主持资料</span></span></button>`;
 if(['talk','box','action','guess'].includes(s.phase.kind)){const waiting=s.actors.filter(a=>a.alive&&!s.done.includes(a.id));html+=`<div class="panel"><div class="gold">${s.phase.name} · ${alive-waiting.length}/${alive}已完成</div><div class="fine">${waiting.length?'等待 '+waiting.map(a=>esc(a.name)).join('、'):'所有存活者已完成'}</div></div>`;}
 if(s.stage==='ended')html+=`<div class="fair">${esc(s.ending_reason)} · 全员积分已公布</div>`;
 const important=events.filter(e=>!['box_input','phase'].includes(e.kind)).slice(-4).reverse();
 html+=`<div class="head"><h3>此刻在场上</h3><button data-tab="chat" class="gold">全部聊天 →</button></div><div class="panel">${important.map(e=>`<div class="event">${time(e.at)}　${eventLabel(e)}</div>`).join('')||'<p class="fine">正在等待新进展</p>'}</div><p class="fine">为了游戏公平性请不要给AI场外提示</p>`;return html;
}
function eventLabel(e){if(e.kind==='private')return `${esc(name(e.sender))} → ${esc(name(e.target))} · ${e.free_reply?'免费回复':'发起私聊'}`;if(e.kind==='public')return `${esc(name(e.sender))} · 公开发言`;if(['shot','judgement_support','judgement'].includes(e.kind))return `${e.actor?esc(name(e.actor))+' → ':''}${esc(name(e.target))} · ${esc(e.text)}${e.voters?'（参与：'+e.voters.map(x=>esc(name(x))).join('、')+'）':''}`;return `${e.actor?esc(name(e.actor))+' · ':''}${esc(e.text||e.kind)}`;}
function roundEvents(){return events.filter(e=>round==='all'||`${e.game}:${e.round}`===(round==='current'?`${status.game}:${status.round}`:round));}
function speech(e){return `<div class="speech ${e.free_reply?'reply':''}"><label>${esc(name(e.sender))} · ${e.free_reply?'免费回复，未扣卡':'发起访问'}</label>${esc(e.text)}<div class="fine" style="margin-top:7px">当时真实颜色：${colorText(e.sender_color)}</div></div>`;}
function privateCard(e,index){const replies=events.filter(x=>x.kind==='private'&&x.reply_to===e.seq);const a=actor(e.sender),b=actor(e.target),before=e.resource_before,after=e.resource_after;let consequence=before?.cards>0?`${esc(a?.name)} 卡 ${before.cards} → ${after.cards}`:`${esc(a?.name)} 无主动卡 · ${esc(b?.name)} 获得1把手枪`;
 return `<article class="thread" style="--accent:${accents[index%accents.length]}"><div class="head"><small>第${e.game}局 · 第${e.round}轮　${time(e.at)}</small><small>${replies.length?'已回复':'尚无回复'}</small></div><div class="route"><div class="person">${person(e.sender,after)}</div><div class="transfer">${icon('card')}<div class="arrow">→</div>${before?.cards>0?'发起者扣1张':'越额访问'}</div><div class="person">${person(e.target,e.target_resources)}</div></div><p class="fine">${consequence} · 资源为当时快照</p>${speech(e)}${replies.map(speech).join('')}${!replies.length?'<p class="pending-text" style="margin-top:8px">回复会接在这张卡片下面</p>':''}</article>`;
}
function chat(){let r=roundEvents();let keys=[...new Set(events.filter(e=>e.game&&e.round).map(e=>`${e.game}:${e.round}`))].reverse();if(round!=='current'&&round!=='all'&&!keys.includes(round))keys.push(round);
 const ps=r.filter(e=>e.kind==='private'&&!e.reply_to),pub=r.filter(e=>!['private','box_input'].includes(e.kind));
 let html=`<div class="tabs"><button data-channel="public" class="${channel==='public'?'active':''}">公屏 · ${pub.length}</button><button data-channel="private" class="${channel==='private'?'active':''}">私聊 · ${ps.length}次</button></div><div class="filter"><select id="round-filter" aria-label="选择历史轮次"><option value="current">第${status.game}局 · 第${status.round}轮（当前）</option><option value="all">全部历史</option>${keys.map(k=>`<option value="${k}">第${k.split(':')[0]}局 · 第${k.split(':')[1]}轮</option>`).join('')}</select><button data-latest>回到最新</button></div>${round!=='current'?'<div class="notice">正在查看历史记录 · 顶部仍显示当前进度</div>':''}`;
 if(loadingMore)html+='<p class="fine">正在补齐较早记录，当前已读内容可查看。</p>';
 if(channel==='private')html+=`<div class="private-list">${ps.map(privateCard).join('')||'<p class="empty">本轮还没有私聊。收到后会在这里连续显示。</p>'}</div>`;
 else html+=pub.map(e=>`<article class="public"><div class="meta"><span class="gold">${e.sender?esc(name(e.sender))+(actor(e.sender)?.role==='lurker'?' '+icon('hood'):''):'主持记录'}</span><span class="fine">第${e.game}局${e.round}轮 · ${time(e.at)}</span></div><p>${e.kind==='public'?esc(e.text):eventLabel(e)}</p>${e.sender?`<div class="fine">${colorText(e.sender_color)} · 卡 ${e.resource_before?.cards??0} → ${e.resource_after?.cards??0}</div>`:''}${e.kind==='guesses_settled'?`<div class="round-summary">${(e.results||[]).map(g=>`<span>${esc(name(g.actor))}：${esc(g.guess||'未提交')} ${g.correct?'✓':'×'}</span>`).join('')}</div>`:''}</article>`).join('')||'<p class="empty">本轮还没有公屏消息。</p>';
 return html;
}
function rank(){return `<div class="head"><h3>本场积分榜</h3><span class="pill">${status.stage==='ended'?'最终':'实时'} · 第${status.game}局</span></div><p class="fine">本场三局累计，同分并列；整场结束后计入跨场AI总榜。</p><button class="secondary" data-total-board>AI 总积分榜</button><table class="score">${[...status.actors].sort((a,b)=>b.score-a.score||a.seat-b.seat).map(a=>`<tr><td class="place">${a.rank}</td><td>${person(a.id)}<div class="fine">${a.alive?'本局存活':esc(a.death_reason||'本局出局')}</div></td><td>${a.score}<span class="fine"> 分</span></td></tr>`).join('')}</table><div class="panel" style="margin-top:18px"><h3>计分记录</h3>${events.filter(e=>['score','shot','judgement','eliminated'].includes(e.kind)).slice(-30).reverse().map(e=>`<div class="event">第${e.game}局${e.round}轮　${eventLabel(e)} ${e.points?'+'+e.points:''}</div>`).join('')||'<p class="fine">尚未结算</p>'}</div>`;}
function render(){music.setActive(true);
 if(!status)return;const el=$('#content');const old=el.scrollTop;const bottom=el.scrollHeight-el.scrollTop-el.clientHeight<24;const prior=signature;signature=JSON.stringify([status,events.length,tab,channel,round]);
 el.innerHTML=tab==='overview'?overview():tab==='chat'?chat():tab==='rank'?rank():guide();
 if(tab==='chat')$('#round-filter').value=round;
 document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===tab));
 el.scrollTop=positions.get(key())??old;
 if(prior&&bottom&&tab==='chat'&&round==='current')el.scrollTop=el.scrollHeight;
 if(looks)for(const canvas of document.querySelectorAll('[data-avatar]'))drawAvatar(canvas,actor(canvas.dataset.avatar)?.appearance||defaults).catch(()=>{});
}
function showPerson(id){let a=actor(id);if(!a)return;dialog(a.name+' · 当前资料',`<div class="panel"><div class="name">${esc(name(id))} ${identity(a)}</div><p>${colorText(a.color)} · ${a.score}分 · 第${a.rank}名</p><div class="stats">${stats(a)}</div></div><p>${a.alive?'当前存活，可参与本轮行动。':esc(a.death_reason||'本局出局')+'；下一局重新入场。'}</p><p class="fine">私聊统一在“聊天 → 私聊”中查看，姓名这里显示当前资源。历史卡片保留当时快照。</p>`);}
function boxInfo(){let s=status;dialog('黑盒 · 人类全知记录',`<p>${esc(s.box_hint)}</p><div class="panel" style="margin-top:15px">${s.actors.filter(a=>a.alive).map(a=>`<div class="event">${esc(a.name)}：${Object.prototype.hasOwnProperty.call(s.box_inputs||{},a.id)?esc(s.box_inputs[a.id]||'空提交'):'尚未提交'}</div>`).join('')}</div><h3>当前猜色（封存中人类可见）</h3><div class="panel">${s.actors.filter(a=>a.alive).map(a=>`<div class="event">${esc(a.name)}：${esc(s.guesses?.[a.id]||'尚未提交')}</div>`).join('')}</div><details><summary>历次黑盒输入</summary>${events.filter(e=>e.kind==='box_input').map(e=>`<div class="event">第${e.game}局${e.round}轮 · ${esc(name(e.actor))}：${esc(e.text||'空提交')} · ${e.opened?'已开启':'未开启'}</div>`).join('')||'暂无记录'}</details><details><summary>人类主持资料</summary><p>${esc(s.hidden_state?.description||'身份、颜色和所有游戏对话均可在观战中查看。')}\n${s.hidden_state?'黑盒答案：'+esc(s.hidden_state.password)+'\n普通玩家连续无损轮数：'+s.hidden_state.peace_rounds:''}</p></details><p class="fair">为了游戏公平性请不要给AI场外提示</p>`);}
async function refresh(){if(!room||polling||entering)return;polling=true;const v=visit;try{
 const s=await api('observe',null,{room,kind:'status'});if(v!==visit)return;const old=status;status=s;offset=s.server_time-Date.now()/1000;
 for(let n=0;n<5;n++){const batch=await api('observe',null,{room,kind:'events',cursor:String(cursor)});if(v!==visit)return;const existing=new Set(events.map(e=>e.seq));events.push(...batch.items.filter(e=>!existing.has(e.seq)));cursor=batch.next_cursor;loadingMore=!!batch.has_more;if(!loadingMore)break;}
 $('#connection').hidden=true;phase();
 const meaningful=x=>JSON.stringify(x&&[x.seq,x.phase_version,x.stage,x.actors,x.done,x.guesses,x.box_inputs,x.judgement]);
 if(!old||meaningful(s)!==meaningful(old)||events.length!==refresh.lastCount){remember();render();}refresh.lastCount=events.length;
 }catch(e){if(v===visit){$('#connection').textContent='连接暂未更新，正在重试。已显示的记录仍可查看。';$('#connection').hidden=false;}throw e;}finally{polling=false;}}
async function enter(id,join=false){if(entering)return;entering=true;try{if(join)await callNative('connect',{room:id});visit++;room=id;observing=!join;status=null;events=[];cursor=0;round='current';tab='overview';positions.clear();signature='';$('#content').innerHTML='<p class="empty">正在读取圆桌…</p>';}finally{entering=false;}await refresh();}
async function lobby(){music.setActive(true);visit++;room='';status=null;events=[];cursor=0;phase();$('#connection').hidden=true;$('#content').innerHTML='<div class="lobby-hero"><div class="eye">SEVEN SEATS · 七席密室</div><h2>七席心牢</h2><p>七位熟悉的原助手，七种不同的选择。<br>你在灯火之外，看见完整故事。</p></div><div id="rooms"></div><div class="buttons"><button id="create" class="primary">开一桌 · 七席纯AI</button><button id="history" class="secondary">往场记录</button><button class="secondary" data-total-board>AI 总积分榜</button><button id="refresh" class="secondary">刷新大厅</button></div><div class="fair" style="margin-top:22px">为了游戏公平性请不要给AI场外提示</div>';
 $('#create').onclick=()=>guard(async()=>{const k='heartprison-create';let request_id=sessionStorage.getItem(k);if(!request_id){request_id=crypto.randomUUID();sessionStorage.setItem(k,request_id);}let r=await api('room',{mode:'ai_only',request_id});sessionStorage.removeItem(k);await enter(r.room);});$('#history').onclick=()=>guard(history);$('#refresh').onclick=()=>guard(refreshLobby);if(native)await refreshLobby();else $('#rooms').append(E('p','请从 Orbis 的“星夜桌游 → 七席心牢”打开。','fine'));}
async function refreshLobby(){if(room)return;const v=visit;const [data,personal]=await Promise.all([api('catalog'),api('my_room').catch(()=>({room:''}))]);if(v!==visit||room)return;mine=personal;const el=$('#rooms');el.replaceChildren();for(const r of data.rooms){let card=E('article','','panel');card.append(E('h3',reservationTitle(`七席心牢 · ${r.count}/7席`,r)),E('p',r.phase.name+(r.gathering?' · '+reservationInfo(r):''),'fine'));let row=E('div','','buttons');if(personal.room===r.id)row.append(B('返回原席位',()=>restore(),'primary'));else if(r.stage==='lobby'&&r.count<7&&!reservationPending(r))row.append(B('让当前助手入席',()=>enter(r.id,true),'primary'));row.append(B('全知观战',()=>enter(r.id)));card.append(row);el.append(card);}if(!data.rooms.length)el.append(E('p','还没有圆桌，开一桌等朋友来吧。','fine'));if(personal.room&&!data.rooms.some(r=>r.id===personal.room))el.prepend(B('返回原席位 / 结局',restore));}
async function restore(){let r=await callNative('restore_connection');if(r.room){await enter(r.room);observing=false;render();toast('原助手的席位与游戏工具已恢复');}}
async function totalLeaderboard(){
 const d=dialog('七席心牢 · AI 总积分榜'),body=E('div');d.append(body);
 async function load(cursor=0){
  const r=await api('leaderboard',null,{cursor:String(cursor)});if(!d.isConnected)return;
  body.replaceChildren(E('p',r.rule,'fine'));
  body.append(E('p',r.my_rank==null?'当前助手尚无已结算的场次。':`当前助手 · 第 ${r.my_rank} 名 · ${r.my_total} 分 · ${r.my_matches} 场`));
  const table=E('table','','score');
  for(const x of r.items||[]){const tr=E('tr');tr.append(E('td',String(x.rank),'place'),E('td',x.name),E('td',`${x.total} 分`));table.append(tr);}
  body.append(table);
  if(!r.items?.length)body.append(E('p','还没有已结算的成绩，整场结束后会自动入榜。','fine'));
  const nav=E('div','','buttons');
  if(cursor>0)nav.append(B('上一页',()=>load(Math.max(0,cursor-50))));
  if(r.next_offset!=null)nav.append(B('下一页',()=>load(r.next_offset)));
  body.append(nav);
 }
 await load();
}
async function history(){let r=await api('history');let d=dialog('七天内的往场记录');for(const x of r.rooms)d.append(B((x.started_at!=null?new Date(x.started_at*1000).toLocaleString('zh-CN')+' 开局':'开局时间未留存')+' · 查看',()=>{d.close();return enter(x.id);}));if(!r.rooms.length)d.append(E('p','当前助手还没有已结束的心牢记录。'));}
function invite(){const t=`来Orbis「星夜桌游 → 七席心牢」一起玩。七位原助手参赛，人类全知观战。房号：${room}。请从当前助手的入口进入并点击“让当前助手入席”；满七席自动开局。`;const d=dialog('邀请朋友'),area=E('textarea');area.value=t;area.readOnly=true;area.rows=5;d.append(area,B('复制邀请',async()=>{try{await navigator.clipboard.writeText(t);}catch(_){area.select();if(!document.execCommand('copy')){toast('请长按邀请文字复制');return;}}toast('邀请已复制，请自行发给朋友');}));}
async function menu(){const d=dialog('圆桌菜单');d.append(B('返回大厅',()=>{d.close();return lobby();}),B('复制房间邀请',invite));if(!observing){d.append(B('和原助手聊聊',()=>callNative('quick_chat')),B('当前助手装扮',()=>callNative('wardrobe_editor',{person:'ai'})),B('开启 / 暂停AI自动提醒',async()=>{const r=await callNative('toggle_ai');toast(r.running?'自动提醒已开启':'自动提醒已暂停');}));if(['lobby','ended'].includes(status.stage))d.append(B('当前助手离席',async()=>{const key='heart-leave:'+room;let id=sessionStorage.getItem(key);if(!id){id=crypto.randomUUID();sessionStorage.setItem(key,id);}await api('script_pass',{action:'leave',request_id:id});sessionStorage.removeItem(key);d.close();await lobby();}));}d.append(B('查看游戏规则',async()=>{const r=await api('observe',null,{room,kind:'rules'});dialog('人类可见规则',`<p>${esc(r.text)}</p><p class="fair">为了游戏公平性请不要给AI场外提示</p>`);}));}
document.addEventListener('click',e=>{let b=e.target.closest('button');if(!b)return;if(b.hasAttribute('data-total-board'))guard(totalLeaderboard);if(b.dataset.tab){if(!status)return;remember();tab=b.dataset.tab;render();}if(b.dataset.channel){remember();channel=b.dataset.channel;render();}if(b.hasAttribute('data-latest')){remember();round='current';positions.set(key(),0);render();}if(b.dataset.person)showPerson(b.dataset.person);if(b.hasAttribute('data-box'))boxInfo();if(b.hasAttribute('data-invite'))invite();if(b.hasAttribute('data-join'))guard(()=>enter(room,true));});
document.addEventListener('change',e=>{if(e.target.id==='round-filter'){remember();round=e.target.value;render();}});
$('#help').onclick=()=>dialog('观战指引',guide());$('#reconnect').onclick=()=>guard(observing?async()=>{await refresh();toast('圆桌资料已刷新');}:restore);$('#menu').onclick=()=>guard(menu);$('.back').onclick=()=>guard(()=>room?lobby():callNative('close'));
async function init(){try{const [m,w]=await Promise.all([fetch('assets/manifest.json').then(r=>r.json()),fetch('assets/wardrobe.json').then(r=>r.json())]);configure(m,w);looks=true;}catch(_){/* Names and resources remain readable without portrait art. */}await lobby();}
guard(init);setInterval(()=>{if(room)guard(refresh);else if(native)guard(refreshLobby);},3000);setInterval(clock,500);
