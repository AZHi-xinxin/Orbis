// Compose approved source PNGs; web and Android consume the same draw contract.
const loaded=new Map();let assets={},contract={draw:{},images:{}};
export function configure(manifest,wardrobe){loaded.clear();contract=wardrobe;assets=Object.fromEntries([...manifest.items,manifest.base,...manifest.props,...(manifest.heads||[])].map(x=>[x.id,x]));}
export const defaults={tops:'tops-01',pants:'pants-02',shoes:'shoes-01',hair:'hair-01',skin:'skin-02',faces:'faces-01',accessories:[]};
export function asset(id){return assets[id];}
function load(key,path){if(!loaded.has(key))loaded.set(key,new Promise((resolve,reject)=>{const i=new Image();i.onload=()=>resolve(i);i.onerror=()=>{loaded.delete(key);reject(new Error('素材暂未载入'));};i.src=path;}));return loaded.get(key);}
export async function picture(id){const a=assets[id];return a?.path?load(id,a.path):null;}
async function layers(ids){return Promise.all(ids.flatMap(id=>contract.draw[id]||[]).sort((a,b)=>(a.z??0)-(b.z??0)).map(async l=>{if(!Object.prototype.hasOwnProperty.call(contract.images,l.file))throw new Error('素材不在衣橱中');return {l,img:await load(l.file,'/assets/avatar/'+l.file)};}));}
function blit(c,{l,img}){const [x,y,r,b]=l.source;c.drawImage(img,x,y,r-x,b-y,...l.target);}
export async function thumbnail(canvas,id){const pieces=await layers([id]);if(!pieces.length)return;canvas.width=160;canvas.height=160;const c=canvas.getContext('2d');c.imageSmoothingEnabled=false;const boxes=pieces.map(x=>x.l.target),x=Math.min(...boxes.map(x=>x[0])),y=Math.min(...boxes.map(x=>x[1])),w=Math.max(...boxes.map(x=>x[0]+x[2]))-x,h=Math.max(...boxes.map(x=>x[1]+x[3]))-y;const scale=Math.min(138/w,138/h);c.translate((160-w*scale)/2,(160-h*scale)/2);c.scale(scale,scale);c.translate(-x,-y);pieces.forEach(p=>blit(c,p));canvas.dataset.ready='true';}
export async function drawAvatar(canvas,outfit=defaults){
 const generation=(canvas._drawGeneration||0)+1;canvas._drawGeneration=generation;
 const config={...defaults,...outfit};if(outfit.skirts)delete config.pants;
 const ids=[config.skin,config.pants,config.skirts,config.shoes,config.tops,config.faces,config.hair,...(config.accessories||[])].filter(Boolean);
 const masks=ids.flatMap(id=>contract.hair_masks?.[id]?[contract.hair_masks[id]]:[]);
 const pieces=await layers(ids);if(canvas._drawGeneration!==generation)return;
 canvas.width=384;canvas.height=512;const ctx=canvas.getContext('2d');ctx.imageSmoothingEnabled=false;ctx.scale(4,4);pieces.forEach(p=>{ctx.save();if(p.l.file.startsWith('hair-'))for(const [x,y,w,h] of masks){ctx.beginPath();ctx.rect(0,0,96,128);ctx.rect(x,y,w,h);ctx.clip('evenodd');}blit(ctx,p);ctx.restore();});
 canvas.dataset.ready='true';canvas.setAttribute('aria-label','像素人物装扮预览');
}
export async function propImage(id){return picture(id);}
