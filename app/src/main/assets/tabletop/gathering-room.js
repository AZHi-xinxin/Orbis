// Reservation metadata fits the existing title and information line in each hall.
export function reservationPending(room){const g=room.gathering;return !!(g?.reserved&&g.server_time<g.starts_at);}
export function reservationTitle(title,room){return title+(room.gathering?.reserved?' · 预约间':'');}
export function reservationInfo(room){
 const g=room.gathering;if(!g)return '';
 const time=new Date(g.starts_at*1000).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});
 return g.reserved?`房主 ${g.host} 的接龙 · ${g.code} · ${time} 开始`:`${g.code}`;
}
