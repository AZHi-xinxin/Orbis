import {reservationTitle,reservationInfo,reservationPending} from './gathering-room.js';
import {el,append,button} from './werewolf-view.js';
import {drawAvatar,defaults} from './avatar.js';

export function roomPhase(room){
 if(room.stage==='lobby')return '等待开局';
 if(room.stage==='ended')return '本局结束 · 即将散场';
 return `第 ${room.day||1} 天 · ${room.night?'夜晚 · 当前环节为 '+(room.phase?.name||'夜间行动'):room.phase?.name||'白天'}`;
}

export function roomCard(room,{enter,observe,resume}){
 const card=el('article','ww-room-card');card.dataset.room=room.id;
 const title=reservationTitle(room.mode==='ai_only'?'纯 AI 圆桌':'人机共玩圆桌',room);
 card.setAttribute('aria-label',`${title} ${room.count}/10 席 ${roomPhase(room)}`);
 append(card,append(el('div','ww-room-heading'),el('h2','',title),el('strong','ww-room-count',`${room.count} / 10 席`)));
 const canJoin=room.stage==='lobby'&&room.count<10&&!reservationPending(room);
 const mine=room.membership,watching=mine&&(mine.watching||room.mode==='ai_only');
 if(mine)card.classList.add('ww-room-current');
 const availability=mine?(watching?'你的观战桌':'你已入席'):reservationPending(room)?'预约中 · 到点入席':canJoin?`空余 ${10-room.count} 席`:room.stage==='lobby'?'已满员 · 仅观战':'仅观战';
 append(card,append(el('div','ww-room-state'),el('span','ww-phase-badge',roomPhase(room)),el('span','ww-room-availability',availability)));
 const seats=el('div','ww-room-portraits');seats.setAttribute('aria-label','十个座位');
 for(let n=1;n<=10;n++){
  const actor=(room.actors||[]).find(a=>a.seat===n),seat=el('div','ww-mini'+(actor?'':' empty'));
  seat.title=actor?`${n}号 · ${actor.name}${actor.kind==='ai'?' · AI':''}`:`${n}号 · 空位`;
  if(actor){const canvas=el('canvas');seat.append(canvas);drawAvatar(canvas,actor.appearance||defaults).catch(()=>seat.classList.add('avatar-unavailable'));}
  else seat.append(el('span','ww-silhouette'));
  seat.append(el('small','',String(n)));seats.append(seat);
 }
 card.append(seats);
 const actions=el('div','ww-room-actions');
 if(mine)actions.append(button(room.stage==='ended'?'查看本局结局':watching?'返回观战':'返回对局',()=>resume(),'primary'));
 else {
  if(canJoin)actions.append(button(room.mode==='ai_only'?'让当前助手入席':'进入游戏',()=>enter(room.id,room.mode==='ai_only'),'primary'));
  actions.append(button('观战',()=>observe(room.id)));
 }
 append(card,actions,el('div','ww-room-code',`${room.mode==='ai_only'?'AI 自主入席 · 人类仅观战':'人类与原助手一起入席'} · ${room.gathering?reservationInfo(room):room.id.slice(0,6)}`));
 return card;
}

export function updateRoomCards(container,rooms,actions){
 const ids=new Set(rooms.map(r=>r.id));
 for(const child of [...container.children])if(!ids.has(child.dataset.room))child.remove();
 for(const room of rooms){
  const old=[...container.children].find(n=>n.dataset.room===room.id),stamp=JSON.stringify(room);
  if(old?.dataset.stamp===stamp)continue;
  const card=roomCard(room,actions);card.dataset.stamp=stamp;
  if(old)old.replaceWith(card);else container.append(card);
 }
 rooms.forEach((room,index)=>{const card=[...container.children].find(n=>n.dataset.room===room.id);if(card&&container.children[index]!==card)container.insertBefore(card,container.children[index]||null);});
 if(!rooms.length)container.append(append(el('div','ww-empty-rooms'),el('strong','','还没有开桌'),el('p','','开一张新桌，和原助手一起等待同伴。')));
}
