import {drawAvatar,defaults} from './avatar.js';
export const el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
export const append=(n,...children)=>{for(const x of children.flat())if(x)n.append(x);return n;};
export const button=(text,click,cls='')=>{const b=el('button',cls,text);b.type='button';b.onclick=click;return b;};
const positions=[[9,70],[9,50],[9,30],[26,9],[50,9],[74,9],[91,30],[91,50],[91,70],[50,91]];
export class WerewolfView{
 constructor(root,actions={}){this.root=root;this.actions=actions;this.key='';this.lastSeq=-1;this.timer=setInterval(()=>this.clock(),500);}
 destroy(){clearInterval(this.timer);}
 invoke(name,...args){return this.actions[name]?.(...args);}
 update(s,events=[]){this.s=s;this.events=events;this.baseTime=Date.now();this.serverTime=s.server_time||Date.now()/1000;
  // A teammate taking a turn must not detach the focused editor (and close Android's IME).
  const wolfChat=s.night&&s.wolf_view&&s.turn?.kind==='wolves_chat';
  const key=[s.id,s.stage,s.night,wolfChat?s.day:s.phase_version,s.self,s.turn?.kind,wolfChat?'':(s.allowed_actions||[]).join(','),s.observer,s.wolf_view,s.stage==='lobby'?s.actors.map(a=>a.id+':'+a.ready).join(','):''].join(':');
  if(key!==this.key){this.key=key;this.build();}
  if(this.wolfChatControls){const actions=s.allowed_actions||[];this.wolfChatControls[0].disabled=!actions.includes('say');this.wolfChatControls[1].disabled=!actions.includes('pass');this.wolfChatRound.textContent=`仅同阵营可见 · 第 ${s.wolf_round||1} 轮发言`;this.wolfChatBadge.textContent=actions.length?'轮到你了':'等待同伴行动';this.footer.querySelector('.ww-turn strong').textContent=this.wolfChatBadge.textContent;}
  this.renderActors();this.renderMessages();this.clock();
 }
 clock(){if(!this.s)return;const s=this.s,remain=Math.max(0,Math.ceil((s.expires_at||s.deadline||0)-this.serverTime-(Date.now()-this.baseTime)/1000));
  if(this.nightWait)this.nightWait.textContent=Date.now()-this.baseTime>12000?'进度暂未更新，请检查连接':remain?'本次行动最多等待 3 分钟，超时自动跳过。':'本次等待已到时，正在核对最新进度…';
  if(this.time)this.time.textContent=s.stage==='ended'?(remain?`散场 ${Math.floor(remain/60)}:${String(remain%60).padStart(2,'0')}`:'结局已保存'):s.deadline?`本次行动剩余 ${Math.floor(remain/60)}:${String(remain%60).padStart(2,'0')}`:'';
 }
 build(){if(this.nightPublicLog)this.nightPublicPosition={top:this.nightPublicLog.scrollTop,follow:this.nightPublicLog.scrollHeight-this.nightPublicLog.clientHeight-this.nightPublicLog.scrollTop<60};this.nightPublicLog=null;this.wolfChatControls=null;const s=this.s;document.body.classList.toggle('ww-dark',Boolean(s.night));this.root.className='ww-root'+(s.night?' ww-night-root':'')+(s.stage==='lobby'?' ww-waiting-root':'');this.root.replaceChildren();this.seats=[];this.lastSeq=-1;this.nightWait=null;
  const header=el('header','ww-header'),title=el('div','ww-header-title');this.time=el('span','ww-timer');
  append(title,append(el('div','ww-title-line'),el('h1','',s.stage==='ended'?'本局结束':s.observer?'观战中':s.stage==='lobby'?(s.mode==='ai_only'?'纯 AI 圆桌':'人机共玩圆桌'):'星夜狼人杀'),this.invoke('music')),append(el('p'),el('span','',s.stage==='lobby'?`${s.actors.length} / 10 席 · 等待开局`:`第 ${s.day||1} 天 · ${s.night?'夜晚 · 当前环节为 '+(s.phase?.name||'夜间结算'):s.phase?.name||'白天'} · ${s.actors.length}/10 席 · `),this.time));
  append(header,button('‹',()=>this.invoke('back'),'ww-back'),title);
  if(!s.observer||this.invoke('connected')){const reconnect=button('重联',()=>this.invoke('reconnect'),'ww-reconnect');reconnect.setAttribute('aria-label','恢复房间连接');reconnect.title='恢复房间连接';header.append(reconnect);}
  header.append(button('游戏规则',()=>this.invoke('rules'),'ww-rules'));this.root.append(header);
  this.board=el('section','ww-board'+(s.night?' ww-night':''));this.root.append(this.board);
  if(s.night)this.night();else this.day();
  this.footer=el('footer','ww-footer');this.root.append(this.footer);this.foot();
 }
 day(){const s=this.s,table=el('img','ww-table-art');table.src='/assets/round-table.png';table.alt='';this.board.append(table);
  for(let i=0;i<10;i++){const seat=button('',()=>this.invoke('seat',i+1),'ww-seat'),canvas=el('canvas');seat.style.left=positions[i][0]+'%';seat.style.top=positions[i][1]+'%';append(seat,canvas,el('span','ww-number',i+1),el('span','ww-name'),el('small'));this.seats.push(seat);this.board.append(seat);}
  if(s.stage==='lobby'){
   this.log=null;this.wolfLog=null;
   this.board.append(append(el('section','ww-waiting-panel'),el('span','ww-waiting-mark','✦'),el('h2','','正在等待开局'),el('p','',s.observer?'你正在观战 · 未占用席位':'已自动安排空位'),el('strong','',`${s.actors.length} / 10 席`),el('small','','满员后系统自动发牌')));return;
  }
  const feed=el('section','ww-feed');this.log=el('div','ww-log');this.log.setAttribute('role','log');this.log.setAttribute('aria-label','全桌公屏');this.newMessages=button('↓ 新发言',()=>{this.log.scrollTop=this.log.scrollHeight;this.newMessages.classList.add('ww-hidden');},'ww-new ww-hidden');
  append(feed,append(el('div','ww-feed-title'),el('strong','','全桌公屏'),button('记录',()=>this.invoke('history'))),this.log,this.newMessages);this.board.append(feed);
 }
 renderActors(){const s=this.s;this.seats.forEach((seat,i)=>{const a=s.actors.find(x=>x.seat===i+1);seat.className='ww-seat'+(!a?' empty':'')+(a?.id===s.turn?.actor?' active':'')+(a?.id===s.self?' self':'')+(a&&a.alive===false?' out':'');seat.querySelector('.ww-name').textContent=a?`${a.seat} ${a.name}${a.kind==='ai'?' · AI':''}`:'等待入席';seat.querySelector('small').textContent=!a?'空位':s.stage==='ended'?'本局结束':a.alive===false?'观战中':a.afk?'已挂机':a.id===s.turn?.actor?'发言中':s.stage==='lobby'?'已入席':'等待中';const canvas=seat.querySelector('canvas');canvas.style.visibility=a?'visible':'hidden';const look=JSON.stringify(a?.appearance||defaults);if(a&&canvas.dataset.look!==look){canvas.dataset.look=look;drawAvatar(canvas,a.appearance||defaults).catch(()=>{});}});}
 renderMessages(){
  if(this.nightPublicLog)this.renderLog(this.nightPublicLog,this.events.filter(x=>x.public===true&&x.channel==='public'&&x.kind!=='wolf_message'),'还没有白天的公开发言，天亮后会保留在这里。');
  const log=this.s.night?this.wolfLog:this.log;if(log)this.renderLog(log,this.events.filter(x=>this.s.night?x.channel==='wolves'||x.kind==='wolf_message':x.public!==false),'本轮发言会显示在这里。');
 }
 renderLog(log,messages,emptyText){const stamp=messages.map(x=>x.seq).join(',');if(log.dataset.stamp===stamp)return;const follow=!log.dataset.stamp||log.scrollHeight-log.clientHeight-log.scrollTop<60,pos=log.scrollTop;log.dataset.stamp=stamp;log.replaceChildren();
  for(const e of messages){const card=el('article','ww-message'+(e.actor?'':' system'));const a=this.s.actors.find(x=>x.id===e.actor);append(card,el('b','',a?`${a.seat}号 · ${a.name}${a.kind==='ai'?' · AI':''}`:'系统主持'),el('p','',e.text||''));log.append(card);}
  if(!messages.length)log.append(el('p','ww-badge',emptyText));
  const restored=log===this.nightPublicLog?this.nightPublicPosition:null;if(restored)this.nightPublicPosition=null;
  requestAnimationFrame(()=>{if(restored?restored.follow:follow)log.scrollTop=log.scrollHeight;else{log.scrollTop=restored?.top??pos;if(log===this.log)this.newMessages?.classList.remove('ww-hidden');}});
 }
 textarea(channel='public'){const input=el('textarea','ww-compose');input.placeholder=channel==='wolves'?'只给狼队的话…':'先写下想说的话，轮到你再发送…';input.maxLength=4000;input.setAttribute('aria-label',channel==='wolves'?'狼队发言草稿':'白天发言草稿');input.value=this.invoke('draft',channel)||'';input.oninput=()=>this.invoke('draftChanged',channel,input.value);return input;}
 targets(parent,{dead=false}={}){const row=el('div','ww-targets');this.target='';const input=el('input');input.placeholder='输入座位号、名字或 ID';input.setAttribute('aria-label','行动目标');input.oninput=()=>{this.target=input.value;for(const b of row.children)b.classList.remove('selected');};parent.append(input,row);
  for(const a of this.s.actors.filter(a=>(dead||a.alive!==false)&&(!this.s.candidates||this.s.candidates.includes(a.id)))){const b=button(String(a.seat),()=>{this.target=String(a.seat);input.value=String(a.seat);for(const child of row.children)child.classList.toggle('selected',child===b);});b.append(el('small','',a.name));row.append(b);}return ()=>this.target;}
 night(){const s=this.s;this.wolfLog=null;this.log=null;const kind=s.turn?.kind,active=s.allowed_actions?.length>0;
  if(s.wolf_view&&kind==='wolves_chat'){this.wolfChat();return;}
  if(!active&&!s.wolf_view){this.board.classList.add('ww-night-reading');this.board.append(append(el('div','ww-night-idle ww-night-idle-compact'),el('h2','','☾ '+(s.phase?.name||'夜间行动')),el('p','',s.observer?'夜间行动保密，等待时可以回看白天发言。':'轮到你时会自动出现操作，等待时可以回看白天发言。'),this.nightWait=el('p','ww-night-wait')));this.publicNightHistory();return;}
  const card=el('div','ww-night-card');this.board.append(card);card.append(el('p','ww-current-phase','当前环节为 '+(s.phase?.name||'夜间行动')),this.nightWait=el('p','ww-night-wait'));card.append(el('span','ww-badge',active?'轮到你的回合了':'等待狼队同伴行动'));
  if(!s.wolf_view)card.append(button('白天公屏记录',()=>this.invoke('history','public')));
  if(s.wolf_view)card.append(append(el('div','ww-actions'),button('狼队记录',()=>this.invoke('history','wolves')),button('白天发言',()=>this.invoke('history','public'))));
  if(kind==='seer'){append(card,el('h2','','选择今夜查验的人'),el('p','','查验结果仅你可见。👍 好人　👎 狼人'));const target=this.targets(card);append(card,append(el('div','ww-actions'),button('确认查验',()=>this.invoke('act','inspect',{target:target()}),'primary'),button('跳过',()=>this.invoke('act','pass'))));}
  else if(kind==='wolves_vote'){append(card,el('h2','','选择今晚的目标'),el('p','',s.vote_round===2?'第二次投票，不再交流；仍不一致时从实际票中随机选择。':'狼队共同选择一位目标。'));const target=this.targets(card);append(card,append(el('div','ww-actions'),button('确认目标',()=>this.invoke('act','wolf_vote',{target:target()}),'primary'),button('弃权',()=>this.invoke('act','wolf_vote',{target:''}))));}
  else if(kind==='witch'){append(card,el('h2','','今夜的消息'),el('p','',s.night_info?.victim?`今晚被袭击的是 ${s.night_info.victim.seat}号 · ${s.night_info.victim.name}。`:'今晚没有人被狼人袭击。'));const haveDrugs=(s.medicines?.antidote||0)+(s.medicines?.poison||0)>0;const target=haveDrugs?this.targets(card):()=>'';append(card,el('p','',`解药 ${s.medicines?.antidote||0} 瓶 · 毒药 ${s.medicines?.poison||0} 瓶；每晚只能用一瓶。`));const controls=el('div','ww-actions');const save=button('用解药',()=>this.invoke('act','medicine',{choice:'antidote'}),'primary'),poison=button('用毒药',()=>this.invoke('act','medicine',{choice:'poison',target:target()}));save.disabled=!s.allowed_actions.includes('antidote');poison.disabled=!s.allowed_actions.includes('poison');append(controls,haveDrugs?save:null,haveDrugs?poison:null,button('不用',()=>this.invoke('act',haveDrugs?'medicine':'pass',haveDrugs?{choice:'none'}:{})));card.append(controls);}
  else if(kind==='hunter'){append(card,el('h2','',s.can_shoot?'是否开枪？':'今夜无需开枪'),el('p','',s.can_shoot?'你可以指定座位，也可以放弃。允许选择已出局的座位。':'本轮没有可执行的开枪动作，确认后回到夜晚。'));const target=this.targets(card,{dead:true});const shoot=button('开枪',()=>this.invoke('act','shoot',{target:target()}),'primary');shoot.disabled=!s.can_shoot;append(card,append(el('div','ww-actions'),shoot,button('放弃 / 确认',()=>this.invoke('act','pass'))));}
  if(!active){this.board.classList.add('ww-night-reading');this.publicNightHistory();}
  if(!active)for(const b of card.querySelectorAll('button'))if(!['狼队记录','白天发言'].includes(b.textContent))b.disabled=true;
 }
 wolfChat(){this.root.classList.add('ww-wolf-chat-root');this.board.classList.add('ww-night-reading','ww-night-wolves-chat');
  const card=el('div','ww-night-card');this.board.append(card);
  this.wolfChatBadge=el('span','ww-badge');this.wolfChatRound=el('small','ww-wolf-round');
  append(card,append(el('div','ww-wolf-heading'),el('h2','','狼队夜谈'),this.wolfChatBadge),this.wolfChatRound,
   append(el('div','ww-actions ww-wolf-history'),button('狼队记录',()=>this.invoke('history','wolves')),button('白天发言',()=>this.invoke('history','public'))));
  this.wolfLog=el('div','ww-wolf-log');this.wolfLog.setAttribute('role','log');this.wolfLog.setAttribute('aria-label','狼队夜谈记录');
  const input=this.textarea('wolves');this.wolfChatControls=[button('发送给狼队',()=>this.invoke('say',input.value,'wolves'),'primary'),button('跳过发言',()=>this.invoke('act','pass'))];
  append(card,this.wolfLog,input,append(el('div','ww-actions'),...this.wolfChatControls));this.publicNightHistory();
 }
 publicNightHistory(){const pane=el('section','ww-night-public');this.nightPublicLog=el('div','ww-night-public-log');this.nightPublicLog.setAttribute('role','log');this.nightPublicLog.setAttribute('aria-label','白天公屏记录');append(pane,append(el('div','ww-feed-title'),el('strong','','白天公屏'),button('展开记录',()=>this.invoke('history','public'))),this.nightPublicLog);this.board.append(pane);}
 foot(){const s=this.s;
  if(s.stage==='lobby'){
   this.footer.append(el('div','ww-waiting-caption',s.observer?'观战可随时进出':'入席即准备 · 等待其他同伴入席'));
   const controls=append(el('div','ww-actions'),button('返回大厅',()=>this.invoke('back'),'primary'));
   if(!s.observer)controls.append(button(s.mode==='mixed'?'我和助手一起离席':'离席',()=>this.invoke('act','leave')));
   this.footer.append(controls,append(el('div','ww-support'),el('span','','观战全程开放'),button('圆桌菜单',()=>this.invoke('menu'))));return;
  }
  const turn=el('div','ww-turn');append(turn,el('strong','',s.stage==='ended'?'本局已结束':s.observer?'你正在观战':s.allowed_actions?.length?'轮到你了':s.night?(s.wolf_view?'等待同伴行动':'等待天亮…'):'可提前准备发言'),button(s.observer?'观战说明':'查看我的身份',()=>this.invoke('role'),'ww-private-toggle'));this.footer.append(turn);
  if(s.stage==='ended')append(this.footer,el('div','ww-observer-note',(s.winner==='wolves'?'狼人阵营获胜。':'好人阵营获胜。')+' 可查看本局身份与公开记录；三分钟后清空临时记录，当前结局仍保留在此页。游戏内无复盘，有复盘需求请自行移至 Orbis 朋友圈／群聊社区内自行发言。'),button('查看本局结果',()=>this.invoke('results')),s.mode==='mixed'&&s.actors.some(a=>a.id===s.self&&a.kind==='human')?button('我和助手一起离席',()=>this.invoke('act','leave'),'primary ww-observer-back'):null);
  else if(s.observer)this.footer.append(el('div','ww-observer-note',s.night?'夜间身份行动不向观战席公开。':'可查看白天公屏。观战席不参与发言和投票。'),button('返回大厅',()=>this.invoke('back'),'ww-observer-back'));
  else if(!s.night&&s.turn?.kind==='hunter'&&s.allowed_actions.length){const box=el('div','ww-vote');box.append(el('span','','你可以开枪，也可以选择放弃。'));const target=this.targets(box,{dead:true});append(box,append(el('div','ww-actions'),button('开枪',()=>this.invoke('act','shoot',{target:target()}),'primary'),button('放弃',()=>this.invoke('act','pass'))));this.footer.append(box);}
  else if(!s.night&&['vote','pk_vote'].includes(s.phase?.kind)){const box=el('div','ww-vote');box.append(el('span','',s.phase.kind==='pk_vote'?'PK 投票 · 再次平票则本日无人出局':'放逐投票 · 每位存活玩家一票'));const target=this.targets(box);append(box,append(el('div','ww-actions'),button('确认投票',()=>this.invoke('act','vote',{target:target()}),'primary'),button('弃权',()=>this.invoke('act','vote',{target:''}))));for(const b of box.querySelectorAll('button'))if(!s.allowed_actions.includes('vote'))b.disabled=true;this.footer.append(box);}
  else if(!s.night){const input=this.textarea();this.footer.append(input);const send=button(s.can_speak?'发送并结束发言':'等待我的回合',()=>this.invoke('say',input.value,'public'),'primary');send.disabled=!s.can_speak;append(this.footer,append(el('div','ww-actions'),send));}
  const support=el('div','ww-support');append(support,el('span','',s.stage==='ended'?'本局已结束 · 可退出连接':s.night?'每人最多等待 3 分钟':'发言一次即顺位继续 · 超时自动跳过'),button('圆桌菜单',()=>this.invoke('menu')));if(!s.night&&s.allowed_actions?.includes('pass')&&s.turn?.kind!=='hunter')support.prepend(button('跳过本次',()=>this.invoke('act','pass')));this.footer.append(support);
 }
}
