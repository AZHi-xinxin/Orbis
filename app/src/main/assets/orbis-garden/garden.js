'use strict';
(() => {
  const main=document.getElementById('garden-main');
  const $=(tag,cls,text)=>{const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e;};
  const button=(text,fn,cls='local-button')=>{const b=$('button',cls,text);b.type='button';b.addEventListener('click',()=>Promise.resolve().then(fn).catch(error));return b;};
  let counter=0, pending=new Map(), config={}, month='', date='', currentKind='DIARY', currentOffset=0, routeGeneration=0;
  const labels={DIARY:'日记',LETTER:'信件',WISH:'心愿',ANCHOR:'锚点',SONG:'歌曲'};
  const iso=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
  function error(e){const n=document.getElementById('garden-notice');n.textContent=e?.message||'暂时无法读取，请重试；没有自动覆盖记录。';n.hidden=false;setTimeout(()=>n.hidden=true,7000);}
  window.gardenRequest=(action,payload={})=>new Promise((resolve,reject)=>{
    if(['chat','management','settings','soup'].includes(action)&&window.GardenLibrary?.requestLeave&&!window.GardenLibrary.requestLeave()){resolve({cancelled:true});return;}
    if(!window.OrbisGardenBridge){reject(new Error('本地宿主尚未连接。'));return;}
    if(pending.size>=8){reject(new Error('请等待当前操作完成。'));return;}
    const id=String(++counter);const timer=['edit','importBook','importLibrary','exportLibrary'].includes(action)?null:setTimeout(()=>{if(pending.delete(id))reject(new Error('操作结果尚未确认；请重新读取核对，不要重复提交写入。'));},60000);pending.set(id,{resolve,reject,timer});
    window.OrbisGardenBridge.postMessage(JSON.stringify({id,action,payload}));
  });
  window.gardenReceive=(reply)=>{const p=pending.get(reply.id);if(!p)return;pending.delete(reply.id);if(p.timer)clearTimeout(p.timer);reply.ok?p.resolve(reply.result):p.reject(new Error(reply.error||'操作未完成；没有自动重试。'));};
  if(window.OrbisGardenBridge)window.OrbisGardenBridge.onmessage=e=>{try{window.gardenReceive(JSON.parse(e.data));}catch(_){error(new Error('本地响应格式异常。'));}};
  window.gardenPage=(title,subtitle)=>{
    ++routeGeneration; document.body.classList.remove('at-home');main.replaceChildren();window.scrollTo(0,0);
    const wrap=$('section','local-shell'),header=$('header','reading-header');header.append(button('‹',()=>home(),'back-btn'));
    const words=$('div');words.append($('h1','',title));if(subtitle)words.append($('p','local-note',subtitle));header.append(words);wrap.append(header);main.append(wrap);return wrap;
  };
  window.gardenNavigateHome=()=>home();
  window.gardenError=error;
  window.gardenRefresh=()=>document.body.classList.contains('at-home')?home():date?day(date):null;
  window.gardenBack=()=>{if(!document.body.classList.contains('at-home'))home();else window.gardenRequest('chat').catch(error);};
  function navIcon(label,fn,type){const b=button('',fn,type);b.append($('span','name-tag',label));if(type==='reading-entry'){const icon=$('div','reading-entry__icon'),book=$('div','reading-entry__book');book.append($('span'),$('span'),$('span'));icon.append(book);b.append(icon);}else if(type==='arcade-entry'){const icon=$('div','arcade-entry__icon'),game=$('div','arcade-console');game.append($('i'),$('b'));icon.append(game);b.append(icon);}else b.append($('span',type==='right-group'?'local-flower':'star-toggle',type==='right-group'?'⚹':'✦'));return b;}
  async function home(){
    if(window.GardenLibrary?.requestLeave && !window.GardenLibrary.requestLeave())return;
    date=''; const generation=++routeGeneration;document.body.classList.add('at-home');main.replaceChildren();window.scrollTo(0,0);
    const wrap=$('section','local-home');const top=$('nav','top-section');top.setAttribute('aria-label','后花园导航');
    top.append(navIcon(config.companionName||'伙伴',()=>management(),'left-group'),navIcon('藏书阁',()=>window.GardenLibrary.open(),'reading-entry'),navIcon('管理',()=>management(),'left-group'),navIcon('游戏机',()=>soup(),'arcade-entry'),navIcon('返回聊天',()=>window.gardenRequest('chat'),'right-group'));wrap.append(top);
    const aura=$('div','calendar-aura'),glow=$('div','calendar-aura__glow');glow.append($('span','calendar-aura__star','✦'));aura.append(glow);
    const calendar=$('section','calendar'),header=$('header','calendar__header');const y=+month.slice(0,4),m=+month.slice(5),title=$('span','calendar__month');title.append($('b','year-number',String(y)),document.createTextNode(`年${m}月`));
    title.tabIndex=0;title.title='点击回到本月';title.onclick=()=>{month=iso(new Date()).slice(0,7);home();};title.onkeydown=e=>{if(e.key==='Enter')title.click();};
    const nav=$('div','calendar__nav');const previous=button('‹',()=>{month=iso(new Date(y,m-2,1)).slice(0,7);home();},'icon-btn');previous.setAttribute('aria-label','上个月');const next=button('›',()=>{month=iso(new Date(y,m,1)).slice(0,7);home();},'icon-btn');next.setAttribute('aria-label','下个月');nav.append(previous,next);header.append(title,nav);calendar.append(header);
    const weekdays=$('div','calendar__weekdays');'一二三四五六日'.split('').forEach(w=>weekdays.append($('span','',w)));calendar.append(weekdays);const grid=$('div','calendar__grid');calendar.append(grid);aura.append(calendar);wrap.append(aura);wrap.append($('footer','app-footer','Orbis'));main.append(wrap);
    const first=(new Date(y,m-1,1).getDay()+6)%7,days=new Date(y,m,0).getDate(),today=iso(new Date());const cells=new Map();
    for(let i=0;i<first;i++)grid.append($('span','calendar__day calendar__day--empty'));
    for(let n=1;n<=days;n++){const d=`${month}-${String(n).padStart(2,'0')}`,b=button(String(n),()=>day(d),'calendar__day'+(d===today?' calendar__day--today':''));b.setAttribute('aria-label',`${d}${d===today?' 今天':''}`);cells.set(d,b);grid.append(b);}
    const data=await window.gardenRequest('month',{month});if(generation!==routeGeneration)return;
    Object.entries(data.counts||{}).forEach(([d,n])=>{if(cells.has(d)&&n>0){cells.get(d).classList.add('calendar__day--has-entry');cells.get(d).setAttribute('aria-label',`${d}，${n}条本地记录`);}});
  }
  async function day(d){
    date=d;currentOffset=0;const wrap=window.gardenPage(`${+d.slice(5,7)}月${+d.slice(8)}日`,d.slice(0,4)+' · 这一天的后花园');
    wrap.classList.add('local-date-page');const quick=$('div','quick-row');
    [['LETTER','✉️'],['DIARY','📔'],['WISH','☘️']].forEach(([k,icon])=>{const b=button('',()=>entries(k,d),'quick-card');b.append($('div','quick-card__icon',icon),$('div','quick-card__label',labels[k]));quick.append(b);});wrap.append(quick);
    const mid=$('div','mid-section'),anchor=$('section','anchor-card'),head=$('div','anchor-card__head');head.append($('span','anchor-card__label','✦ 记忆锚点'),button('+',()=>edit(null,'ANCHOR',d),'anchor-add-btn'));anchor.append(head);const anchors=$('div','anchor-list');anchor.append(anchors,button('查看这一天的锚点',()=>entries('ANCHOR',d),'local-button'));
    const song=button('',()=>entries('SONG',d),'song-card');song.append($('div','song-card__icon','♪'),$('div','song-card__title','我们的歌'),$('div','song-card__sub','留给这一天的声音'));mid.append(anchor,song);wrap.append(mid);
    const token=routeGeneration,result=await window.gardenRequest('list',{date:d,kind:'ANCHOR',offset:0});if(token!==routeGeneration)return;
    if(!result.entries.length)anchors.append($('p','local-empty','还没有锚点'));result.entries.slice(0,3).forEach(e=>anchors.append(button(e.title,()=>edit(e,'ANCHOR',d),'local-entry')));
  }
  async function entries(kind,d,offset=0){
    date=d;currentKind=kind;currentOffset=offset;
    const wrap=window.gardenPage(`${+d.slice(5,7)}月${+d.slice(8)}日 · ${labels[kind]}`,`${d} · 只看这一天`),actions=$('div','local-actions');
    actions.append(button('‹ 返回这一天',()=>day(d)),button(`写${labels[kind]}`,()=>edit(null,kind,d)));wrap.append(actions);
    const section=$('section','local-section');wrap.append(section);const generation=routeGeneration;
    const result=await window.gardenRequest('list',{date:d,kind,offset});if(generation!==routeGeneration)return;
    if(!result.entries.length)section.append($('p','local-empty',`这一天还没有${labels[kind]}。`));
    result.entries.forEach(e=>{const b=button('',()=>edit(e,kind,d),'local-entry');b.append($('strong','',e.title),$('small','',e.author),$('span','excerpt',e.body));section.append(b);});
    const pager=$('div','local-actions');if(offset>0)pager.append(button('上一页',()=>entries(kind,d,Math.max(0,offset-30))));if(result.hasMore)pager.append(button('下一页',()=>entries(kind,d,offset+30)));wrap.append(pager);
  }
  async function edit(e,kind,d){await window.gardenRequest('edit',{id:e?.id||null,kind,date:d});if(date===d)await entries(kind,d,currentOffset);}
  async function management(){date='';const w=window.gardenPage('后花园管理','本地保存 · 不自动复制远端');const box=$('section','local-section');box.append($('p','local-note','日记、锚点、信件、心愿和歌曲都放在各自日期内。旧记录保留；新增记录可以补写到选中的日期。'));
    const actions=$('div','local-actions');actions.append(button('存储与称呼',()=>window.gardenRequest('settings')),button('AI 读写授权与记录备份',()=>window.gardenRequest('management')));box.append(actions);w.append(box);}
  function soup(){return window.gardenRequest('soup');}
  document.getElementById('garden-home-star').onclick=()=>window.gardenRequest('chat').catch(error);
  window.gardenBoot=async()=>{config=await window.gardenRequest('config');month=iso(new Date()).slice(0,7);await home();};
  window.addEventListener('DOMContentLoaded',()=>window.gardenBoot().catch(error));
})();
