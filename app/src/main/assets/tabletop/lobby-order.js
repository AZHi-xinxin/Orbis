// Public lobby metadata only; stable sorting preserves order within each group.
export function orderLobbyRooms(rooms){
 const rank=room=>{
  if(room.count===0)return 2;
  if(room.stage==='lobby'&&room.count>0&&room.count<room.seats)return 0;
  return 1;
 };
 return [...rooms].sort((a,b)=>rank(a)-rank(b));
}
