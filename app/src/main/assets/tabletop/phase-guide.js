// A real modal with a per-seat, per-phase acknowledgement. Never replace a
// story viewer or interrupt a draft being edited during background polling.
export function showPhaseGuide(c,force=false){
 const {state,dialog,el,append,btn,modal,navigate,labels,read,store}=c,s=state.status;
 if(!s?.guide||!['playing','claim'].includes(s.stage))return;
 const key=`orbis-phase-guide-v2:${state.room}:${s.self}:${s.phase_version}`;
 if(dialog.dataset.phaseGuideKey&&dialog.dataset.phaseGuideKey!==key){
  dialog.removeEventListener('close',dialog.phaseGuideClose);
  delete dialog.dataset.phaseGuideKey;dialog.close();
  requestAnimationFrame(()=>showPhaseGuide(c,force));return;
 }
 if(dialog.open||(!force&&read(key,false)))return;
 const focused=document.activeElement;
 if(!force&&['TEXTAREA','INPUT','SELECT'].includes(focused?.tagName))return;
 const box=el('section','stack fs-phase-guide');
 append(box,el('h2','','现在进入：'+s.guide.title),el('p','reader',s.guide.text));
 if(s.guide.instructions)append(box,el('h3','','现在怎么做'),el('p','reader',s.guide.instructions));
 if(s.guide.completion)append(box,el('small','','完成后：'+s.guide.completion));
 const finish=()=>{store(key,true);dialog.close();};
 append(box,btn('打开'+(labels[s.guide.entry]||'当前任务'),()=>{finish();navigate(s.guide.entry||'tasks');},'primary'),btn('我知道了，留在当前页面',finish));
 dialog.dataset.phaseGuideKey=key;
 dialog.phaseGuideClose=()=>{store(key,true);delete dialog.dataset.phaseGuideKey;};
 dialog.addEventListener('close',dialog.phaseGuideClose,{once:true});
 modal(box);
}
