import {native,callNative} from './native.js';

export function publicMessage(c,x,{reply,assetLoader=c.assetBlob,status=c.state.status}={}){
 const {el,append,btn,guard}=c;
 const name=id=>{const a=status?.actors?.find(a=>a.id===id);return id==='dm'?'DM':a?a.name+(a.role_name?' · '+a.role_name:''):'同桌';};
 const box=el('article','fs-message'+(x.actor===status?.self?' mine':''));box.dataset.seq=x.seq;
 append(box,el('small','',name(x.actor||'dm')));
 if(x.quote)box.append(el('blockquote','chat-quote',name(x.quote.actor)+'：'+(x.quote.text||'图片')));
 if(x.text)box.append(el('p','reader',x.text));
 if(x.mentions?.length)box.append(el('small','chat-mentions',x.mentions.map(id=>'@'+name(id)).join(' ')));
 for(const image of x.images||[]){const pic=el('img','room-chat-image');pic.alt='同桌分享的图片';pic.loading='lazy';box.append(pic);guard(async()=>{const blob=await assetLoader(image);if(!pic.isConnected)return;const url=URL.createObjectURL(blob);pic.src=url;pic.onload=()=>{URL.revokeObjectURL(url);pic.dispatchEvent(new Event('roomimage',{bubbles:true}));};pic.onerror=()=>URL.revokeObjectURL(url);});}
 if(reply)box.append(btn('引用回复',()=>reply(x),'quiet'));
 return box;
}
export function createRoomChat(c){
 const {state,el,append,btn,api,rid,guard,toast}=c;
 const dialog=el('dialog','room-chat-dialog'),head=el('header','row spread'),title=el('strong','','本桌临时群聊'),hint=el('p','muted'),log=el('div','room-chat-log'),jump=btn('有新消息 ↓',()=>{follow=true;log.scrollTop=log.scrollHeight;jump.hidden=true;}),area=el('textarea'),quote=el('div','chat-quote'),attachments=el('div','row'),tools=el('div','row'),send=btn('发送到全桌',sendMessage,'primary');
 area.placeholder='写给同桌的话…';area.maxLength=4000;area.setAttribute('aria-label','群聊消息');log.setAttribute('role','log');log.setAttribute('aria-label','本桌临时群聊消息');jump.hidden=true;quote.hidden=true;
 append(head,title,btn('收起',()=>dialog.close()));append(dialog,head,hint,log,jump,quote,attachments,area,tools,send);document.body.append(dialog);
 let follow=true,last='',scope='',images=[],mentions=[],quoting=null,seen=0,loadedSeq=-1;
 let seenKey='';
 const markSeen=seq=>{seen=seq;try{localStorage.setItem(seenKey,String(seq));}catch{}};
 const storageKey=()=>`orbis-room-chat:${state.room}:${state.player?.id}:${state.status?.phase_version}`;
 const load=key=>{try{return JSON.parse(localStorage.getItem(key)||'{}');}catch{return {};}};
 const save=()=>localStorage.setItem(scope,JSON.stringify({text:area.value,images,mentions,quote:quoting?.seq}));
 const floating=btn('本桌群聊',open,'room-chat-launch');floating.hidden=true;document.body.append(floating);
 area.oninput=save;
 log.onscroll=()=>{follow=log.scrollHeight-log.clientHeight-log.scrollTop<80;if(follow){jump.hidden=true;markSeen(loadedSeq);}};
 log.addEventListener('roomimage',()=>{if(follow)log.scrollTop=log.scrollHeight;});
 function draft(){const next=storageKey();if(next===scope)return;scope=next;const d=load(scope);area.value=d.text||'';images=d.images||[];mentions=d.mentions||[];quoting=state.events.find(x=>x.seq===d.quote)||null;draftParts();}
 function draftParts(){quote.replaceChildren();quote.hidden=!quoting;if(quoting)append(quote,el('span','',quoting.text||'图片'),btn('取消引用',()=>{quoting=null;draftParts();save();}));attachments.replaceChildren();images.forEach((name,i)=>attachments.append(btn('图片 '+(i+1)+' ×',()=>{images=images.filter(x=>x!==name);draftParts();save();})))}
 function reply(x){quoting=x;draftParts();save();area.focus();}
 const mention=el('select');mention.setAttribute('aria-label','提及同桌');mention.onchange=()=>{const id=mention.value;if(!id)return;if(!mentions.includes(id))mentions.push(id);area.value+=' @'+mention.selectedOptions[0].textContent+' ';mention.value='';save();area.focus();};
 const emoji=el('select');emoji.setAttribute('aria-label','添加表情');for(const e of ['表情','🙂','😂','🤔','❤️','👍','👀','🎉']){const option=el('option','',e);option.value=e==='表情'?'':e;emoji.append(option);}emoji.onchange=()=>{area.value+=emoji.value;emoji.value='';save();};
 append(tools,mention,emoji,btn('添加图片',pickImage));
 async function pickImage(){if(images.length>=4)throw Error('一条消息最多4张图片');const k=scope,version=state.status.phase_version;let r;
  if(native)r=await callNative('pick_room_image',{phase_version:version});
  else{const input=el('input');input.type='file';input.accept='image/*';const file=await new Promise(resolve=>{input.onchange=()=>resolve(input.files[0]);input.oncancel=()=>resolve(null);input.click();});if(!file)return;const bitmap=await createImageBitmap(file),canvas=document.createElement('canvas'),scale=Math.min(1,1200/Math.max(bitmap.width,bitmap.height));canvas.width=Math.round(bitmap.width*scale);canvas.height=Math.round(bitmap.height*scale);canvas.getContext('2d').drawImage(bitmap,0,0,canvas.width,canvas.height);bitmap.close();let data=canvas.toDataURL('image/jpeg',.8);if(data.length>690000)data=canvas.toDataURL('image/jpeg',.5);if(data.length>690000)throw Error('图片过大，请选择较小图片');r=await api('chat_image',{body:{room:state.room,phase_version:version,request_id:rid(),data:data.split(',')[1]}});}
  if(k!==scope){toast('幕次已变化，请在当前幕重新选择图片');return;}images.push(r.name);draftParts();save();
 }
 async function sendMessage(){
  if(!state.status.can_speak)throw Error('当前阶段暂不可发言，可先保存草稿');
  const k=scope,pendingKey=k+':pending';let body=load(pendingKey);
  if(!body.request_id){body={room:state.room,request_id:rid(),phase_version:state.status.phase_version,channel:'public',text:area.value,images,mentions,...(quoting?{quote:quoting.seq}:{})};localStorage.setItem(pendingKey,JSON.stringify(body));}
  try{await api('script_say',{body});localStorage.removeItem(pendingKey);localStorage.removeItem(k);if(scope===k){area.value='';images=[];mentions=[];quoting=null;draftParts();}await c.pull();update();}
  catch(e){if(e.status>=400&&e.status<500&&![401,403,408].includes(e.status))localStorage.removeItem(pendingKey);throw e;}
 }
 function update(){floating.hidden=state.page!=='table'||!state.status||!['playing','ended'].includes(state.status.stage);if(!state.room){dialog.close();log.replaceChildren();last='';return;}
  const nextSeen=`orbis-room-chat-seen:${state.room}:${state.player?.id}`;if(nextSeen!==seenKey){seenKey=nextSeen;try{seen=Number(localStorage.getItem(seenKey))||0;}catch{seen=0;}last='';log.replaceChildren();}
  const rows=state.events.filter(x=>['public_message','dm_announcement','hint','ending','game_end'].includes(x.kind));const latest=rows.slice(-1)[0]?.seq||0;const unread=rows.filter(x=>x.seq>seen).length;floating.textContent='本桌群聊'+(unread?' · '+unread:'');if(!dialog.open)return;
  draft();hint.textContent=state.status.can_speak?'全桌可见 · 本轮还可发言 '+state.status.remaining_speech+' 次':'本阶段可查看消息、保存草稿，开放发言后再发送。';send.disabled=!state.status.can_speak;
  const names=state.status.actors.map(a=>a.id+':'+a.name).join('|');if(mention.dataset.names!==names){mention.dataset.names=names;mention.replaceChildren(el('option','','@ 同桌'));mention.firstChild.value='';for(const a of [...state.status.actors,{id:'dm',name:'DM'}]){const o=el('option','',a.name);o.value=a.id;mention.append(o);}}
  const key=state.room+':'+latest;if(key!==last){const pos=log.scrollTop;last=key;const existing=new Set([...log.children].map(x=>Number(x.dataset.seq)));for(const x of rows)if(!existing.has(x.seq))log.append(publicMessage(c,x,{reply}));loadedSeq=latest;requestAnimationFrame(()=>{log.scrollTop=follow?log.scrollHeight:pos;if(follow){markSeen(latest);jump.hidden=true;}else jump.hidden=latest<=seen;});}
 }
 function open(){dialog.showModal();update();}
 return {open,update,close:()=>{if(!dialog.open)return false;dialog.close();return true;}};
}
