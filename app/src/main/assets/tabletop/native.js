export const native=typeof window.OrbisTabletopBridge!=='undefined';
const requests=new Map();let sequence=0;
window.tabletopNativeResult=(id,result,error)=>{const r=requests.get(String(id));if(!r)return;requests.delete(String(id));clearTimeout(r.timer);if(error){const e=new Error(typeof error==='object'?error.message:error);if(typeof error==='object')Object.assign(e,error);r.reject(e);}else r.resolve(result);};
export function callNative(action,args={}){
 if(!native)return Promise.reject(Error('请在 Orbis 中打开这个入口'));
 if(requests.size>=8)return Promise.reject(Error('还有操作正在处理，请稍候'));
 return new Promise((resolve,reject)=>{const id=String(++sequence);const timer=setTimeout(()=>{requests.delete(id);reject(Error('暂未确认结果，请核对原回执；不要重复提交'));},125000);requests.set(id,{resolve,reject,timer});window.OrbisTabletopBridge.postMessage(JSON.stringify({id,action,args}));});
}
