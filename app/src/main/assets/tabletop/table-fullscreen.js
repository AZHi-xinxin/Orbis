import {native,callNative} from './native.js';
import {showPhaseGuide} from './phase-guide.js';

// A presentation of the real, permission-filtered game state. No fixture roles or story text.
export function createFullTable(c){
 const {state,app,api,act,pull,el,append,btn,notice,toast,modal,guard,roomMeta,drawAvatar,defaults,rid}=c;
 let view='table',lastPhase='',lastStage='',renderId=0,pageIndex=0,cluePublic=false,personal=false,history=false;
 const scrolls=new Map(),follow=new Map(),seenSeq=new Map();let mount=app,renderedView='',renderedPhase='';
 const icons={clues:'▤',reading:'♧',tasks:'☑',performances:'▷'};
 const labels={table:'圆桌',waiting:'等候入席与准备',claim:'领取角色',dm:'DM 的提醒',log:'全桌发言',compose:'写给圆桌的话',reading:'我的剧本',clues:'线索袋',tasks:'当前任务',search:'搜证与行动点',performances:'查看演绎',private:'局内角色私聊',notes:'自己的笔记',vote:'本幕投票',recap:'结局与复盘',ended:'这一桌，暂时散场',controls:'圆桌控制',reconcile:'核对这次操作'};
 const current=()=>state.status;
 const sk=()=>`${state.room}:${state.player.id}:${current().phase_version}`;
 const key=(kind,k=sk())=>'orbis-tabletop-'+kind+':'+k;
 const read=(k,fallback)=>{try{return JSON.parse(localStorage.getItem(k))??fallback;}catch{return fallback;}};
 const store=(k,v)=>{try{localStorage.setItem(k,JSON.stringify(v));}catch{toast('本机草稿未能保存，请先复制保留内容');}};
 function navigate(next){const p=app.querySelector('.fs-body');if(p)scrolls.set(view,p.scrollTop);const focus=document.activeElement;if(app.contains(focus)&&['TEXTAREA','INPUT','SELECT'].includes(focus.tagName))focus.blur();view=next;c.setPage('table');render();}
 function confirm(title,message,run){const box=el('div','stack');append(box,el('h2','',title),el('p','',message),append(el('div','row'),btn('再想一下',()=>document.querySelector('#dialog').close()),btn('确认',async()=>{await run();document.querySelector('#dialog').close();},'primary')));modal(box);}
 function quickChat(){if(native)return callNative('quick_chat');toast('在 Orbis 中可从右侧“聊聊”打开所属 AI 的原私人窗口。');}
 const quick=()=>btn('聊聊',quickChat,'fs-quick');
 function top(title,subtitle){return append(el('header','fs-top'),btn('‹',()=>view==='table'?c.setPage('home'):navigate('table'),'fs-round'),append(el('div','fs-title'),el('h1','',title),el('small','',subtitle)),el('span','fs-music-slot'),btn('•••',()=>navigate('controls'),'fs-round'));}
 function pane(title,subtitle){const s=current(),root=el('section','fs-page');append(root,top(title,subtitle||s.phase.name));const body=el('div','fs-body');root.append(body);mount.append(root);return body;}
 function card(title,text){return append(el('article','fs-card'),el('h2','',title),text?el('p','reader',text):null);}
 function pack(...items){return append(el('div','fs-actions'),...items);}
 function same(version,who){return current()?.phase_version===version&&current()?.self===who;}
 function activePhaseAction(action,extra={}){const version=current().phase_version,self=current().self;return async()=>{await pull();if(!same(version,self))throw Error('幕次或角色已变化，请重新核对当前资料');await act(action,extra);};}
 function render(refresh=false){
  const s=current();if(!s)return;if(lastStage!==s.stage){if(s.stage==='ended'){view='ended';document.querySelector('#dialog').close();}else if(view==='waiting'&&s.stage==='claim')view='claim';else if(['waiting','claim'].includes(view)&&s.stage==='playing')view='table';lastStage=s.stage;}const phase=sk();if(lastPhase&&phase!==lastPhase){for(const k of [...scrolls.keys()])if(k!=='reading')scrolls.delete(k);if(document.querySelector('#dialog.cinema-dialog[open]'))document.querySelector('#dialog').close();if(view==='compose')toast('幕次已变化，旧草稿单独保留，请核对本幕再发言。');}lastPhase=phase;
  if(refresh&&renderedView===view&&renderedPhase===phase&&app.firstChild&&['reading','notes','compose','performances'].includes(view))return;
  const focus=document.activeElement;
  // Polling must not move a person away from the text they are editing or watching.
  if(app.contains(focus)&&['TEXTAREA','INPUT','SELECT'].includes(focus.tagName))return;
  const previous=app.querySelector('.fs-body');if(previous){scrolls.set(view,previous.scrollTop);follow.set(view,previous.scrollHeight-previous.clientHeight-previous.scrollTop<90);}
  const feed=app.querySelector('.fs-live-feed');if(feed){scrolls.set('live',feed.scrollTop);follow.set('live',feed.scrollHeight-feed.clientHeight-feed.scrollTop<80);}const rev=++renderId;document.body.classList.add('tabletop-fullscreen');
  // Keep the displayed page intact while the next read is in flight.
  const staged=el('div');staged.hidden=true;app.append(staged);mount=staged;const nextView=view;
  const commit=()=>{if(rev!==renderId){staged.remove();return false;}const focused=document.activeElement;if(app.contains(focused)&&!staged.contains(focused)&&['TEXTAREA','INPUT','SELECT'].includes(focused.tagName)){staged.remove();return false;}const nodes=[...staged.childNodes];app.replaceChildren(...nodes);const music=c.music?.();if(music)app.querySelector('.fs-music-slot')?.append(music);renderedView=nextView;renderedPhase=phase;mount=app;stageNotice();return true;};
  if(view==='table'){renderTable();commit();return;}
  const body=pane(labels[view]||'当前资料',s.stage==='ended'?'本局已结束 · 可回看资料':s.phase.name);
  const valid=()=>rev===renderId&&body.isConnected&&same(s.phase_version,s.self);
  guard(async()=>{
   try {
   if(view==='waiting')c.renderWaiting(body,s);
   else if(view==='claim')c.renderClaim(body,s);
   else if(view==='compose')await compose(body,valid);
   else if(view==='reading')await reading(body,valid);
   else if(view==='clues')await clues(body,valid);
   else if(view==='search')await search(body,valid);
   else if(view==='tasks')await tasks(body,valid);
   else if(view==='performances')await performances(body,valid);
   else if(view==='dm'||view==='log')await log(body,view==='dm',valid);
   else if(view==='private'){c.renderPrivate(body);body.append(quick());}
   else if(view==='notes')await c.renderNotes(body);
   else if(view==='vote')vote(body);
   else if(view==='recap')await recap(body,valid);
   else if(view==='ended')ended(body);
   else if(view==='controls'){if(native)window.tabletopHostStatus=await callNative('host_status');if(valid())controls(body);}
   else if(view==='reconcile')await reconcile(body,valid);
   if(valid()&&commit()){if(['dm','log','private'].includes(view)&&follow.get(view)!==false)body.scrollTop=body.scrollHeight;else body.scrollTop=scrolls.get(view)||0;if(['dm','log','private'].includes(view))followMessages(body,view);}else staged.remove();
   } finally {staged.remove();if(mount===staged)mount=app;}
  });
 }
 function finishButtons(){const s=current(),bar=el('div','fs-finish-actions');if(s.stage!=='playing')return bar;if(s.phase.kind==='vote'&&s.unanimous){bar.append(btn('查看投票 / 修改自己的票',()=>navigate('vote'),'primary'));return bar;}const done=target=>s.progress?.some(x=>x.actor===target&&x.done);const own=btn(done(s.self)?'✓ 本轮已结束':'结束本轮并准备',()=>confirm('结束本轮并准备？',s.guide?.completion||'确认结束本人本轮行动。',activePhaseAction('end_round')),'primary');own.disabled=done(s.self);bar.append(own);if(state.player.kind==='human'&&s.pair?.ai){const ai=btn(done(s.pair.ai)?'✓ AI已结束本轮':'代替AI结束本轮',()=>confirm('代替AI结束本轮？',s.guide?.completion||'确认代替搭档结束本轮。',activePhaseAction('end_round',{target:s.pair.ai})));ai.disabled=done(s.pair.ai);bar.append(ai);}return bar;}
 function stageNotice(force=false){showPhaseGuide({state,dialog:document.querySelector('#dialog'),el,append,btn,modal,navigate,labels,read,store},force);}
 function messageBox(x){if(x.kind==='public_message')return c.publicMessage(x);const text=x.text||(x.kind==='phase_open'?'进入：'+x.name:x.kind==='vote_result'?'本轮票数：'+Object.entries(x.counts||{}).map(([k,v])=>k+' '+v+' 票').join(' · '):'');if(!text)return null;const box=el('article','fs-message'+(x.actor===current().self?' mine':''));append(box,el('small','',x.kind==='question'?((current().actors.find(a=>a.id===x.actor)?.name||'同桌')+' · 提问'):x.source==='npc_performance'?'剧中人物的反应':'DM'),el('p','reader',text));if(x.entry)box.append(btn('前往'+(labels[x.entry]||'当前任务'),()=>navigate(x.entry)));return box;}
 function liveFeed(root){const log=el('div','fs-live-feed');log.setAttribute('aria-label','圆桌公屏');log.setAttribute('role','log');const position=scrolls.get('live')||0,stick=follow.get('live')!==false;for(const x of state.events.filter(x=>['public_message','question','question_answer','help_requested','phase_completion','dm_announcement','hint','ending','game_end'].includes(x.kind)).slice(-80)){const m=messageBox(x);if(m)log.append(m);}if(!log.childNodes.length)log.append(notice(current().guide?.text||'等待同桌入席'));root.append(log);requestAnimationFrame(()=>{log.scrollTop=stick?log.scrollHeight:position;followMessages(log,'live');});}
 function followMessages(container,stream){
  const kinds=stream==='private'?['private_message']:stream==='dm'?['dm_announcement','phase_open','hint','ending','game_end']:['public_message','question','question_answer','help_requested','phase_completion','dm_announcement','hint','ending','game_end'];
  const rows=state.events.filter(x=>kinds.includes(x.kind));const latest=rows.slice(-1)[0]?.seq||0;
  const mark=()=>seenSeq.set(stream,latest);
  if(follow.get(stream)!==false)mark();
  const unread=rows.filter(x=>x.seq>(seenSeq.get(stream)||0)).length;
  let jump;
  if(unread&&follow.get(stream)===false){jump=btn('有 '+unread+' 条新消息 ↓',()=>{container.scrollTop=container.scrollHeight;follow.set(stream,true);mark();jump.remove();},'fs-new-messages');container.parentElement.append(jump);}
  container.addEventListener('scroll',()=>{const bottom=container.scrollHeight-container.clientHeight-container.scrollTop<80;follow.set(stream,bottom);if(bottom){mark();jump?.remove();}});
 }
 function renderTable(){
  const s=current(),root=el('section','fs-table');root.style.backgroundImage=`url('${roomMeta(s.theme).path}')`;mount.append(root);
  append(root,top(s.script.title,roomMeta(s.theme).name+' · '+s.phase.name+(s.phase_number?' · '+s.phase_number+'/'+s.phase_count:'')),append(el('div','fs-table-tools'),btn('本幕怎么玩',()=>stageNotice(true)),btn('✦ DM 提示',()=>navigate('dm')),native?null:quick()));
  const ring=el('div','fs-ring'),count=s.script.seats,coords=count===6?[[20,14],[80,14],[87,49],[80,84],[20,84],[13,49]]:Array.from({length:count},(_,i)=>[50+35*Math.sin((i/count)*Math.PI*2),50-33*Math.cos((i/count)*Math.PI*2)]);
  if(count>8)ring.classList.add('many-seats');
  coords.forEach(([x,y],i)=>{const a=s.actors[i],actor=el('div','fs-actor'+(y>60?' front':'')+(a?.id===s.self?' self':'')+(a?'':' vacant'));actor.style.left=x+'%';actor.style.top=y+'%';const chair=el('img','fs-chair');chair.src='/assets/armchair.png';chair.alt='';actor.append(chair);if(a){const avatar=el('canvas','fs-avatar');actor.append(avatar);guard(()=>drawAvatar(avatar,Object.keys(a.appearance||{}).length?a.appearance:defaults));}const name=el('button','fs-name',a?`${i+1} ${a.name}${a.kind==='ai'?' · AI':''}`:'等待入席');name.type='button';name.onclick=()=>{if(a)modal(card(a.name,`${a.kind==='ai'?'AI':'人类'} · ${a.role_name||'角色待领取'}\n${s.progress?.find(p=>p.actor===a.id)?.label||'等待本幕行动'}`));};actor.append(name);const progress=s.progress?.find(p=>p.actor===a?.id);if(progress?.done)actor.append(el('span','fs-done','✓'));ring.append(actor);});
  const table=el('img','fs-round-table');table.src='/assets/round-table.png';table.alt='圆桌';ring.append(table);const voice=btn(s.stage==='lobby'?'等候入席':s.stage==='claim'?'领取角色':s.stage==='ended'?'回看复盘':'点此发言',()=>navigate(s.stage==='lobby'?'waiting':s.stage==='claim'?'claim':s.stage==='ended'?'recap':'compose'),'fs-voice');append(voice,el('small','',s.stage==='playing'?'向全桌发言':'本局当前安排'));ring.append(voice);root.append(ring);if(s.stage==='playing'){root.classList.add('has-live-feed');liveFeed(root);root.append(finishButtons());}
  const done=s.progress?.find(x=>x.actor===s.self)?.done;
  let hint=s.stage==='lobby'?'确认本对准备':s.stage==='claim'?'领取你们的角色':s.stage==='ended'?'本局已结束':s.paused?'本局暂时暂停':s.waiting_for_agreement?'票已收齐，意见未统一':done?'本幕已完成，等待同桌':s.phase.kind==='reading'?'先阅读自己的剧本':s.phase.kind==='performance'?'查看本幕演绎':s.phase.kind==='discussion'&&s.can_speak?'现在可以发言':'查看本幕任务';
  const action=s.stage==='lobby'?'waiting':s.stage==='claim'?'claim':s.stage==='ended'?'ended':s.phase.kind==='reading'?'reading':s.phase.kind==='performance'?'performances':s.phase.kind==='vote'?'vote':s.phase.kind==='discussion'&&s.can_speak?'compose':'tasks';
  const turn=btn(hint,()=>navigate(action),'fs-turn');append(turn,el('small','',s.stage==='playing'?`${s.phase.name} · 还可公开发言 ${s.remaining_speech} 次`:`${s.actors.filter(a=>!a.left).length} / ${count} 入席`));root.append(turn);if(s.stage==='ended')root.append(countdown());
  const pockets=el('nav','fs-pockets');for(const [id,title] of [['clues','线索袋'],['reading','我的剧本'],['tasks','当前任务'],['performances','查看演绎']]){const b=btn('',()=>navigate(id));b.setAttribute('aria-label',title);append(b,el('span','fs-icon',icons[id]),el('span','',title));pockets.append(b);}root.append(pockets);
  append(root,append(el('div','fs-bottom'),btn('全桌发言',()=>navigate('log')),btn('局内私聊',()=>navigate('private')),btn('笔记',()=>navigate('notes'))));
  if(state.players.length>1){const select=el('select','fs-debug');select.setAttribute('aria-label','合成测试席位');for(const x of state.players){const op=el('option','',x.name);op.value=x.id;select.append(op);}select.value=state.player.id;select.onchange=()=>guard(async()=>{state.player=state.players.find(x=>x.id===select.value);state.events=[];state.cursor=0;await pull();render();});root.append(select);}
 }
 async function compose(body,valid){
  const s=current(),phase=s.phase_version,k=key('draft'),pendingKey=key('pending',`${state.room}:${s.self}`),saved=read(k,{text:''});
  append(body,notice(s.can_speak?'写清楚想公开的内容，填入默认文案后仍可修改，不会自动发送。':s.phase.kind==='reading'?'阅读中只保存草稿，不能向全桌发送。':'本幕当前不可发言，可先保存草稿。'),pack(el('span','muted','当前角色 · '+s.phase.name),quick()));
  const area=el('textarea','fs-speech');area.id='public-composer';area.maxLength=4000;area.value=saved.text;area.placeholder='把想说的话写在这里…';area.setAttribute('aria-label','发言草稿');const count=el('small','fs-count',`${area.value.length} / 4000`);area.oninput=()=>{store(k,{text:area.value,phase,name:s.phase.name,at:Date.now()});count.textContent=`${area.value.length} / 4000`;};append(body,area,count);
  const templates=await api('script_read',{query:{kind:'reply_templates',room:state.room}});if(!valid())return;
  const template=templates.items?.[0],fill=btn('使用默认文案',()=>{const run=()=>{area.value=template.text;area.oninput();toast('已填入，请按自己的想法修改');};if(area.value.trim())confirm('替换当前草稿？','填入不会发送；你可以继续修改。',run);else run();});fill.disabled=!template;
  const send=btn(s.can_speak?'发送':s.phase.kind==='reading'?'阅读中，不可发送':'本幕不可发送',()=>{
   if(!area.value.trim())throw Error('请先写下想说的话');const text=area.value;
   confirm('向全桌发送？','全桌将看到这段发言，请确认公开范围。',async()=>{
    await pull();if(!same(phase,s.self))throw Error('幕次已变化，原草稿保留；请重新核对');if(!current().can_speak)throw Error('当前已不能发言，草稿保留');
    if(read(pendingKey,null))throw Error('正在自动确认上一条发言的结果，草稿已保留');
    const payload={room:state.room,request_id:rid(),phase_version:phase,channel:'public',text};store(pendingKey,{payload,draftKey:k});
    try{await api('script_say',{body:payload});localStorage.removeItem(pendingKey);if(read(k,{}).text===text)localStorage.removeItem(k);await pull();navigate('log');toast('发言已送达圆桌');}
    catch(e){if(e.status>=400&&e.status<500&&![401,408].includes(e.status))localStorage.removeItem(pendingKey);else {retryAt=0;void recoverPendingSpeech();toast('连接暂未确认，正在自动重联核对，草稿已保留');}throw e;}
   });
  },'primary');send.disabled=!s.can_speak||!!read(pendingKey,null);append(body,pack(fill,btn('保存草稿',()=>{area.oninput();toast('草稿已保存，没有发送');}),send));
  append(body,notice(template?.source==='script'?'本幕原稿提供的回复模板。':template?'本幕没有原作模板，可使用通用整理模板；空格由你填写，不预填剧情事实。':'本幕暂无默认文案，可以自己填写。'));
  if(template){const d=el('details');append(d,el('summary','','查看本幕模板'),el('pre','reader',template.text),btn('复制模板',async()=>{await navigator.clipboard.writeText(template.text);toast('已复制模板');}));body.append(d);}
  const prefix=key('draft',`${state.room}:${s.self}:`),older=Object.keys(localStorage).filter(x=>x.startsWith(prefix)&&x!==k).map(x=>read(x,{})).filter(x=>x.text).sort((a,b)=>b.at-a.at)[0];
  if(older)body.append(btn('查看上幕保留的草稿',()=>{const box=card(older.name||'此前草稿',older.text);append(box,btn('复制到当前草稿',()=>confirm('复制到当前幕？','请重新核对这段话是否适合当前幕，复制不会发送。',()=>{area.value=older.text;area.oninput();})));modal(box);}));
  body.append(finishButtons());body.append(btn('回到圆桌',()=>navigate('table')));
 }
 function pageControls(body,total,run){const bar=pack(btn('◁ 上一张',()=>{if(pageIndex>0){pageIndex--;run();}}),el('span','',`${total? pageIndex+1:0} / ${total}`),btn('下一张 ▷',()=>{if(pageIndex+1<total){pageIndex++;run();}}));bar.firstChild.disabled=pageIndex===0;bar.lastChild.disabled=pageIndex+1>=total;body.append(bar);}
 async function reading(body,valid){
  body.classList.add('fs-ebook');const fontKey='orbis-tabletop-reader-size';let size=Math.max(16,Math.min(30,Number(read(fontKey,19))||19));body.style.setProperty('--reader-size',size+'px');
  const sizeLabel=el('span','',size+' px');const resize=delta=>{size=Math.max(16,Math.min(30,size+delta));store(fontKey,size);body.style.setProperty('--reader-size',size+'px');sizeLabel.textContent=size+' px';};
  body.append(pack(btn('A−',()=>resize(-1)),sizeLabel,btn('A＋',()=>resize(1))));
  append(body,pack(btn(history?'查看本幕':'查看已读章节',()=>{history=!history;scrolls.set('reading',0);render();}),quick()));
  let r=await api('script_read',{query:{room:state.room,kind:history?'history_chapters':'chapter'}});if(!valid())return;
  if(!history&&!r.items?.length){r=await api('script_read',{query:{room:state.room,kind:'history_chapters'}});if(!valid())return;body.append(notice('本幕没有新章节，以下可回看本人已开放的资料。'));}
  if(r.role_name)body.append(notice('当前角色：'+r.role_name+' · '+(r.role_gender||'未注明')));
  const items=r.items||[];pageIndex=Math.min(pageIndex,Math.max(items.length-1,0));if(!items.length)body.append(notice('当前没有向你开放的资料。'));
  else{body.append(notice((history?'已开放资料':'本幕资料')+' · 共 '+items.length+' 节，上下滑动连续阅读。'));for(const item of items){const sheet=card(item.title);c.appendStoryContent(sheet,item);if(!item.text&&!(item.assets||[]).length)sheet.append(btn('打开原稿页',()=>c.showSource(item)));body.append(sheet);}}
  if(current().phase.kind==='reading'&&current().stage==='playing')append(body,pack(btn('先存发言草稿',()=>navigate('compose')),btn(current().read_done?'已读完':'我已读完当前资料',()=>confirm('确认已经读完？','只确认本人当前开放的资料；回桌等同桌读完。',activePhaseAction('read_done')),'primary')));
 }
 async function clues(body,valid){
  append(body,pack(btn('私人线索',()=>{cluePublic=false;pageIndex=0;render();},cluePublic?'':'selected'),btn('公开线索',()=>{cluePublic=true;pageIndex=0;render();},cluePublic?'selected':'')));
  const r=await api('script_read',{query:{room:state.room,kind:'pocket'}});if(!valid())return;
  const rows=(r.items||[]).filter(x=>cluePublic?x.public:x.owned&&!x.public);pageIndex=Math.min(pageIndex,Math.max(rows.length-1,0));
  if(rows.length){const index=el('select');index.setAttribute('aria-label','线索目录');rows.forEach((x,i)=>{const op=el('option','',`${i+1}. ${x.title}`);op.value=String(i);index.append(op);});index.value=String(pageIndex);index.onchange=()=>{pageIndex=Number(index.value);render();};append(body,pack(index,btn('最新一张',()=>{pageIndex=rows.length-1;render();})));const x=rows[pageIndex],box=card(x.title);c.appendStoryContent(box,x);if(x.owned&&!x.public)box.append(btn('分享给全桌',()=>confirm('公开这张线索？','全桌将能阅读，公开后不能改回私人。',activePhaseAction('share_clue',{target:x.id}))));if(x.can_hide)box.append(btn(x.hidden?'这张已保留':'本轮保留这一张',activePhaseAction('hide_clue',{target:x.id})));body.append(box);pageControls(body,rows.length,render);}else body.append(notice(cluePublic?'还没有已公开的线索。':'当前没有未公开的私人线索。'));
  for(const x of r.sources||[])body.append(btn('打开已领取原页 · '+x.title,()=>c.showSource(x)));
  if(current().phase.kind==='search')body.append(btn('前往本幕搜证',()=>navigate('search'),'primary'));body.append(quick());
 }
 async function tasks(body,valid){
  body.append(btn('本幕怎么玩',()=>stageNotice(true)));
  append(body,pack(btn('集体任务',()=>{personal=false;render();},personal?'':'selected'),btn('个人任务',()=>{personal=true;render();},personal?'selected':'')));
  const r=await api('script_read',{query:{room:state.room,kind:'tasks'}});if(!valid())return;
  if(personal){for(const text of r.role_tasks||[])body.append(card('当前角色任务',text));if(!r.role_tasks?.length)body.append(notice('本幕没有单独列出的个人任务，请按已开放的剧本行动。'));}
  else append(body,card(current().phase.name,r.instructions),notice('只显示当前进度，不展示未开放的目标。'));
  if(current().stage==='ended'){body.append(btn('回看结局与复盘',()=>navigate('recap'),'primary'));return;}
  const k=current().phase.kind;
  if(['search','questions','answers'].includes(k)){const help=btn(r.help_state==='delivered'?'提示已发布':`申请提示 ${current().help_votes}/${current().help_threshold}`,activePhaseAction('help'));help.disabled=!!r.help_requested;body.append(help);if(r.help_state==='pending')body.append(notice('已记录申请，等更多同桌同意后发布提示。'));}
  if(r.completion)body.append(notice('本幕完成条件：'+r.completion));
  if(k==='reading')body.append(btn('阅读当前剧本',()=>navigate('reading'),'primary'));
  if(k==='discussion'){body.append(btn(current().passed?'这轮已说完':'这轮说完了',()=>confirm('结束本轮公开发言？','确认后本轮不能继续公开发言。',activePhaseAction('pass')),'primary'));body.append(btn(`申请提示 ${current().help_votes}/${current().help_threshold}`,activePhaseAction('help')));}
  if(k==='search')body.append(btn('搜证与行动点',()=>navigate('search'),'primary'));
  if(k==='vote')body.append(btn('打开本幕投票',()=>navigate('vote'),'primary'));
  if(k==='performance')body.append(btn('全屏查看演绎',()=>navigate('performances'),'primary'));
  if(['game','questions','answers','debrief'].includes(k)){const box=el('div','stack');body.append(box);await c.interactionPane(c.runtimeContext(),box);}
  body.append(quick());
 }
 async function search(body,valid){
  const s=current();append(body,notice(s.phase.kind==='search'?`${s.ap_scope==='pair'?'本对共用':s.ap_scope==='actor'?'你个人':'当前'}可用行动点 ${s.ap}；领取成功后才更新。`:'当前不是搜证环节。'));
  for(const x of s.locations||[])body.append(btn(`${x.title} · ${x.cost} 点`,()=>confirm('领取这条线索？',`将消耗 ${x.cost} 点，当前权限和前置条件由服务器核对。`,activePhaseAction('search',{target:x.id}))));
  if(s.phase.kind==='search')body.append(btn('本轮取证结束',()=>confirm('结束本轮取证？','若本幕要求保留一张线索，请先在线索袋核对选择。',activePhaseAction('search_done')),'primary'));
  body.append(btn('打开线索袋',()=>navigate('clues')));const ctx=c.runtimeContext(),box=el('div','stack');body.append(box);const tasks=await api('script_read',{query:{kind:'tasks',room:state.room}});if(valid())body.append(card('本幕规则',tasks.instructions));
 }
 async function performances(body,valid){
  const r=await api('script_read',{query:{room:state.room,kind:'performances'}});if(!valid())return;
  if(!r.items.length)body.append(notice('本幕暂无向你开放的演绎，请按当前任务继续。'));
  for(const item of r.items){const box=card(item.title);append(box,notice('进入独立全屏演绎。退出画面不会自动确认完成。'),btn('全屏查看演绎',()=>c.showInteraction(item),'primary'));body.append(box);}
  if(current().stage==='playing'&&current().own_phase_done)body.append(notice('你已确认本幕完成，等待同桌。'));
 }
 async function log(body,dm,valid){
  let items=state.events;if(dm){items=[];let cursor=0;for(;;){const r=await api('script_read',{query:{room:state.room,kind:'dm',cursor}});if(!valid())return;items.push(...r.items);if(!r.has_more)break;cursor=r.next_cursor;}}
  const rows=items.filter(x=>dm||['public_message','question','question_answer','help_requested','phase_completion','phase_open','dm_announcement','hint','ending','vote_result','game_end'].includes(x.kind));
  if(!rows.length)body.append(notice('这里还没有已公开的记录。'));
  for(const x of rows){const box=messageBox(x);if(box)body.append(box);}
  if(!dm)body.append(btn('打开大输入框',()=>navigate('compose'),'primary'));else body.append(notice('主持手册、未来剧情和其他角色的秘密不在这里。'));
 }
 function vote(body){const s=current();if(s.phase.kind!=='vote'){body.append(notice('当前不是投票环节。'));return;}append(body,notice(`${s.anonymous?'匿名投票':'本幕投票'} · 已收到 ${s.votes_received} 票。${s.change_vote?'本幕允许修改本人的投票。':'本幕不允许改票。'}`));
  if(s.waiting_for_agreement)append(body,notice('票已收齐，但意见尚未统一。请继续讨论，可修改自己的票；本幕不会按多数票推进。'),notice(Object.entries(s.vote_counts||{}).map(([k,v])=>k+'：'+v+'票').join(' · ')));
  for(const choice of s.vote_options){const b=btn(choice+(s.my_vote===choice?' ✓':''),()=>confirm('确认这一票？',choice,activePhaseAction('vote',{choice})),s.my_vote===choice?'selected':'');b.disabled=!!s.my_vote&&!s.change_vote;body.append(b);}
 }
 async function recap(body,valid){
  for(const x of state.events.filter(x=>['ending','game_end'].includes(x.kind)))body.append(card('本局实际结局',x.text||'本局已结束'));
  if(current().stage==='ended'){
   const r=await api('script_read',{query:{room:state.room,kind:'recap'}});if(!valid())return;
   if(r.ending)body.append(card('完整复盘',r.ending));
   body.append(notice('本局已结束，可查看全角色资料、演绎和分支复盘。其他玩家的私人聊天和笔记不会公开。'));
   for(const role of r.roles||[]){const d=el('section');d.append(el('h2','',role.name+' · 完整角色资料'));for(const ch of role.chapters){const b=card(ch.title);c.appendStoryContent(b,ch);d.append(b);}body.append(d);}
   const performances=el('section');performances.append(el('h2','','全本演绎与分支'));for(const item of r.performances||[])performances.append(btn(item.title,()=>c.showInteraction(item)));body.append(performances);for(const clue of r.clues||[]){const b=card(clue.title);c.appendStoryContent(b,clue);body.append(b);}
   body.append(btn('回到散场倒计时',()=>navigate('ended')));
  }else if(current().phase.kind==='debrief'){
   const r=await api('script_read',{query:{room:state.room,kind:'tasks'}});if(!valid())return;body.append(card('本幕复盘',r.instructions));body.append(btn('我已完成复盘',activePhaseAction('finish'),'primary'));
  }
  body.append(btn('回看本人的已读资料',()=>{history=true;navigate('reading');}));
 }
 function secondsLeft(){const s=current();if(!s?.expires_at)return 0;const now=Number.isFinite(s.server_time)?s.server_time+(performance.now()-(s.received_at??performance.now()))/1000:Date.now()/1000;return Math.max(0,Math.ceil(s.expires_at-now));}
 function countdown(){const n=el('div','notice room-countdown');n.setAttribute('role','timer');updateCountdown(n);return n;}
 function updateCountdown(n){const secs=secondsLeft();n.textContent='距离房间关闭还剩 '+String(Math.floor(secs/60)).padStart(2,'0')+':'+String(secs%60).padStart(2,'0');}
 setInterval(()=>{if(current()?.stage!=='ended')return;for(const n of app.querySelectorAll('.room-countdown'))updateCountdown(n);if(secondsLeft()===0)guard(c.closeExpiredRoom);},1000);
 function ended(body){append(body,card('谢谢今晚的相聚','不需要再次确认完成。人类和AI可以自行离席，五分钟后房间自动清空。'),countdown(),btn('回看复盘',()=>navigate('recap')),btn('打开自己的笔记',()=>navigate('notes')),btn('回原窗口',quickChat),btn('离开这张圆桌',c.confirmLeave,'primary'));}
 function controls(body){append(body,notice(window.tabletopHostStatus?.state||'当前圆桌状态以最近一次成功回执为准。'),btn('回到圆桌',()=>navigate('table'),'primary'),btn('准备／恢复',()=>native?callNative('prepare'):quickChat()),btn(window.tabletopHostStatus?.running?'暂停本局 AI':'开启本局 AI',async()=>{if(native){await callNative('toggle_ai');render();}else toast('浏览器试演不连接真实模型');}),btn('核对操作回执',()=>navigate('reconcile')),btn('调整装扮',()=>c.setPage('wardrobe')),btn('返回大厅（保留席位）',()=>c.setPage('home')),btn('离开这张圆桌',c.confirmLeave),native?btn('关闭圆桌页面（保留席位）',()=>callNative('close')):null,quick());}
 let recoveryBusy=false,retryAt=0,recoveryAttempts=0;
 async function recoverPendingSpeech(){
  if(!current()||recoveryBusy||Date.now()<retryAt)return;
  const ownerKey=key('pending',`${state.room}:${current().self}`),pending=read(ownerKey,null);if(!pending)return;
  recoveryBusy=true;
  try{
   // Always replay the exact saved request. The server returns its original
   // receipt even when the phase has advanced; no new public message is created.
   await api('script_say',{body:pending.payload});
   if(read(ownerKey,null)?.payload?.request_id===pending.payload.request_id)localStorage.removeItem(ownerKey);
   if(read(pending.draftKey,{}).text===pending.payload.text)localStorage.removeItem(pending.draftKey);
   const area=app.querySelector('#public-composer');if(area?.value===pending.payload.text){area.value='';area.oninput?.();}const dialog=document.querySelector('#dialog');if(dialog.open&&dialog.querySelector('h2')?.textContent==='向全桌发送？')dialog.close();retryAt=0;recoveryAttempts=0;toast('上一条发言已确认送达');
  }catch(e){
   if(e.status>=400&&e.status<500&&![401,403,408].includes(e.status)){
    if(read(ownerKey,null)?.payload?.request_id===pending.payload.request_id)localStorage.removeItem(ownerKey);
    toast('原操作未完成，草稿已保留，请按当前阶段继续。');recoveryAttempts=0;
   }else retryAt=Date.now()+Math.min(30000,3000*2**Math.min(recoveryAttempts++,4));
  }finally{recoveryBusy=false;}
 }
 async function reconcile(body){const pendingKey=key('pending',`${state.room}:${current().self}`),pending=read(pendingKey,null);append(body,notice('草稿保留。结果不明时核对同一笔请求，不另发一条新消息。'),btn('核对原操作回执',async()=>{
   let matched=false;
   if(native){const result=await callNative('reconcile');matched=!!pending&&result.human?._reconciled_request_id===pending.payload.request_id;}
   // The host can have received success while the WebView lost its callback. In that
   // case resend this exact saved request, never a new ID: the server receipt wins.
   if(pending&&!matched){try{await api('script_say',{body:pending.payload});}catch(e){if(e.status>=400&&e.status<500&&![401,408].includes(e.status)){localStorage.removeItem(pendingKey);toast('原操作明确未完成，草稿保留；请核对当前阶段再编辑。');navigate('compose');}throw e;}}
   if(pending){const draft=read(pending.draftKey,{});if(draft.text===pending.payload.text)localStorage.removeItem(pending.draftKey);localStorage.removeItem(pendingKey);}await pull();toast('原操作已核对');navigate('log');
  },'primary'),btn('查看全桌记录',()=>navigate('log')),btn('返回当前草稿',()=>navigate('compose')));}
 async function confirmPerformance(version){if(current().phase_version!==version)throw Error('幕次已变化，请返回当前任务核对');confirm('确认本段演绎完成？','只标记本人，其他同桌仍需各自确认。',activePhaseAction('performance_done'));}
 // Only the trusted app host calls this. Back closes overlays first; it never vacates a seat.
 window.tabletopNavigateBack=()=>{if(c.roomChat.close())return;if(state.page==='observe'){c.setPage('home');return;}const d=document.querySelector('#dialog');if(d.open){d.close();return;}if(view!=='table'){navigate('table');return;}if(state.page==='table'){c.setPage('home');return;}if(native)guard(()=>callNative('close'));};
 return {render:()=>render(true),navigate,confirmPerformance,recoverPendingSpeech};
}
