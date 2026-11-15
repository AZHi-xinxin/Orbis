/* All authored HTML runs in an opaque frame. Only two explicit messages cross it. */
export async function interactionPane(ctx,pane) {
 const {api,act,el,append,btn,notice,assetView,state,guard}=ctx;
 const kind=state.status.phase.kind,version=state.status.phase_version,self=state.status.self;
 const draft=(area,id)=>{const key=`orbis-task-draft:${state.room}:${self}:${version}:${id}`;try{area.value=localStorage.getItem(key)||'';}catch{}area.oninput=()=>{try{localStorage.setItem(key,area.value);}catch{}};return key;};
 const submit=async(action,data,key)=>{if(state.status.phase_version!==version||state.status.self!==self)throw Error('幕次已变化，旧草稿已保留；请返回当前任务核对。');const result=await act(action,data);if(result.correct!==false&&result.response_source!=='unrecognized'&&key)localStorage.removeItem(key);if(result.message)ctx.toast?.(result.message);return result;};
 const tasks=await api('script_read',{query:{room:state.room,kind:'tasks'}});
 if(!pane.isConnected||state.status.phase_version!==version)return;
 append(pane,notice(tasks.instructions),el('small','',`搭档共有资源 ${tasks.team_resources}`));
 if(tasks.memory_recall_available){const recall=el('details'),word=el('input');word.maxLength=200;word.placeholder='刚刚想起的名字、物品或关键词';append(recall,el('summary','','尝试唤起自己的记忆'),word,btn('回想',()=>submit('recall',{text:word.value})));pane.append(recall);}
 if(kind==='questions'){
  const q=el('textarea');q.placeholder='向主持提问';q.maxLength=1000;
  const key=draft(q,'question');
  append(pane,q,btn('提交问题',()=>submit('question',{text:q.value},key)),btn('本轮提问结束',()=>submit('questions_done')));
  if(tasks.question_mode==='npc_performance')pane.append(notice('这里记录的是角色反应，不等于主持核实的事实。'));
  append(pane,el('h3','','本幕已公开问答'));
  for(const x of tasks.question_history||[]){const row=el('section','reader-card');append(row,el('p','reader','问：'+x.text),el('p','reader','答：'+x.answer));pane.append(row);}
  if(tasks.deadline)append(pane,notice('本幕截止：'+new Date(tasks.deadline*1000).toLocaleTimeString()+'（按服务器时钟判定）'));
  for(const text of tasks.available_questions||[]){const asked=(tasks.question_history||[]).some(x=>x.text===text);append(pane,btn((asked?'已问 · ':'')+text,()=>submit('question',{text})));}
 }
 if(kind==='search')append(pane,btn('本轮取证结束',()=>act('search_done')));
 if(kind==='answers')for(const question of tasks.questions||[]){
  const card=el('section','reader-card');append(card,el('h3','',question.prompt));
  if(question.solved){card.append(notice('这一题已共同解开，无需重复提交。'));if(question.solution?.answer)card.append(el('p','reader',question.solution.answer));}
  else if(question.options?.length){append(card,notice(question.input_help||'从地点列表选择'));const select=el('select');for(const name of question.options){const option=el('option','',name);option.value=name;select.append(option);}const empty=el('option','','请选择地点');empty.value='';empty.selected=true;empty.disabled=true;select.prepend(empty);const send=btn('核对所选地点',()=>submit('answer',{question:question.id,text:select.value}));send.disabled=true;select.onchange=()=>{send.disabled=!select.value;};append(card,select,send);}
  else {const input=el('textarea');input.placeholder='输入简明答案';input.maxLength=1000;const key=draft(input,'answer:'+question.id);append(card,input,btn('核对答案',()=>submit('answer',{question:question.id,text:input.value},key)));}
  pane.append(card);
 }
 if(kind==='answers'&&!(tasks.questions||[]).length){const answer=el('textarea');answer.maxLength=4000;answer.placeholder='按本幕任务写下完整解答，也可交给AI提交';const key=draft(answer,'free-answer');if(tasks.own_answer)append(pane,notice('你已提交本幕解答，可回看或修改。'),el('p','reader',tasks.own_answer));append(pane,notice(tasks.answer_scope==='table'?'任意一席提交后向全桌公布，其他人只需阅读；自由解答不自动判定真伪。':'本幕为自由解答，无需填写题号。'),answer,btn(tasks.answer_scope==='table'?'提交共同还原':'提交本人的解答',()=>submit('answer',{text:answer.value},key)));}
 if(kind==='debrief')append(pane,btn('复盘结束',()=>act('finish')));
 if(!['performance','game','answers'].includes(kind))return;
 const result=await api('script_read',{query:{room:state.room,kind:kind==='game'?'games':'performances'}});
 if(!pane.isConnected||state.status.phase_version!==version)return;
 for(const item of result.items){
  const card=el('section','reader-card');append(card,el('h3','',item.title),el('p','reader',item.transcript));pane.append(card);
  if(kind==='game'){
   if(item.scope==='table')card.append(notice('可交给AI解题，任意一席完成后全桌共享结果。'));
   append(card,notice(item.text||''));
   for(const choice of item.choices||[])append(card,btn(choice.label,()=>act('game_choice',{game:item.id,choice:choice.id})));
   if(item.input){const answer=el('input');answer.maxLength=200;answer.placeholder='与搭档商量后输入答案';append(card,answer,btn('提交答案',()=>act('game_answer',{game:item.id,text:answer.value})));}
   if(item.finished)append(card,notice('这一项已完成，奖励由服务器记账。'));
  }
  append(card,btn('打开互动场景',()=>assetView(item)));
 }
}

export async function submissionPage(ctx){
 const {api,el,append,btn,app,notice,modal,upload,retryUpload,template}=ctx;
 append(app,el('div','eyebrow','PUBLIC STORY LIBRARY'),el('h1','','把一个故事，留给所有人。'));
 const info=await api('submissions/guide');
 const box=el('section','panel stack');append(box,el('h2','','投稿前请读'),el('p','',info.rights),notice('至少 6 个角色，按人和 AI 成对设置偶数席位。正文、配图、音频、HTML 和游戏资源均需自带；这里不保存私人本。'),el('p','','普通剧情、ABO 设定、正常棋牌及角色吸烟饮酒，不会因这些词被拒稿。内容审核针对色情内容、现实金钱赌博和违法毒品推广。'));
 const detailed=el('details');append(detailed,el('summary','','展开完整格式和制作说明'),el('pre','reader',info.guide));box.append(detailed);
 const check=el('input');check.type='checkbox';const label=el('label','row');append(label,check,el('span','','我已阅读公开、留存、随机本及权利声明；文件内也须逐项确认。'));
 append(box,btn('下载可编辑投稿模板',template),label,btn('选择并上传 ZIP',()=>{if(!check.checked)throw Error('请先阅读并确认投稿声明');return upload();},'primary'),btn('核对上一次投稿回执',retryUpload));app.append(box);
 try{const list=await api('submissions');const rows=el('section','stack');append(rows,el('h2','','我的投稿'));for(const x of list.items)append(rows,append(el('article','panel'),el('h3','',x.title),el('p','',({pending:'待审核',approved:'已进入公共库',rejected:'未通过'}[x.status]||x.status)),el('small','',x.reason||'每次上传保留独立版本，更新不会改变已开局的剧本。')));app.append(rows);}catch(e){app.append(notice(e.message));}
}
