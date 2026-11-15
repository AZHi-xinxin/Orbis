#!/usr/bin/env python3
"""Reproduce optional official packs. PNG sources stay outside Android assets."""
from pathlib import Path
import json, hashlib, re, io, zipfile
from PIL import Image
R=Path(__file__).resolve().parents[1]
S=R/'optional-assets/orbis-pets'; O=R/'optional-assets/dist';O.mkdir(exist_ok=True)
C=R/'app/src/main/assets/orbis-pet-art.json'
ids=re.findall(r'PetAppearance\("([^"]+)", "([^"]+)"', (R/'app/src/main/java/me/rerere/rikkahub/data/orbis/activities/PetCatalog.kt').read_text())
cache={}
def sha(b):return hashlib.sha256(b).hexdigest()
def webp(p):
 if p not in cache:
  im=Image.open(p);b=io.BytesIO();im.save(b,format='WEBP',quality=90,method=6);cache[p]=b.getvalue()
 return cache[p]
cat_scene={'sage':('cat-room-tidy-v3.png','cat-room-messy-v3.png','cat-black-rest-v1.png'),'rose':('cat-room-rose-tidy.png','cat-room-rose-messy.png','cat-black-rose-rest.png'),'sky':('cat-room-sky-tidy.png','cat-room-sky-messy.png','cat-black-sky-rest.png')}
catalog={'version':1,'baseUrl':'https://raw.githubusercontent.com/AZHi-xinxin/Orbis/050709070532f806070a6be339c4ee021a7883a7/packs/','packages':{}}
previous=json.loads((R/'optional-assets/catalog-v1.json').read_text())
catalog['previousPackages']={k:[v] for k,v in previous['packages'].items()}
for ident,family in ids:
 files={'cat.webp':webp(S/(ident+'.png'))}
 tidy=S/f'{family}-room-tidy-v1.png';messy=S/f'{family}-room-messy-{"v2" if family=="cat" else "v1"}.png'
 if family=='cat':
  for color,(t,m,r) in cat_scene.items():
   files[f'room-{color}-tidy.webp']=webp(S/'scenes'/t);files[f'room-{color}-messy.webp']=webp(S/'scenes'/m)
   if ident=='cat-black-v1':files[f'rest-{color}.webp']=webp(S/'scenes'/r)
 else:files.update({'room-sage-tidy.webp':webp(tidy),'room-sage-messy.webp':webp(messy)})
 care=S/'care-v2'/f'{ident}.png'
 if ident!='cat-black-v1':
  assert care.is_file(),f'Missing care scenes: {ident}'
  files['care.webp']=webp(care)
 pose=S/'poses'/f'{ident}.png'
 if pose.exists():files.update({'pose.webp':webp(pose),'pose.json':(S/'poses'/f'{ident}.json').read_bytes()})
 data=io.BytesIO()
 with zipfile.ZipFile(data,'w',compression=zipfile.ZIP_STORED) as z:
  for name,value in sorted(files.items()):
   info=zipfile.ZipInfo(name,date_time=(2026,11,14,0,0,0));info.external_attr=0o100644<<16;z.writestr(info,value)
 b=data.getvalue();(O/(ident+'.zip')).write_bytes(b)
 catalog['packages'][ident]={'sha256':sha(b),'bytes':len(b),'files':{n:{'sha256':sha(v),'bytes':len(v)} for n,v in files.items()},'legacy':{'cat.png':sha((S/(ident+'.png')).read_bytes()),'tidy.png':sha(tidy.read_bytes()),'messy.png':sha(messy.read_bytes())}}
C.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
(O/'catalog.json').write_bytes(C.read_bytes())
print(json.dumps({'packages':len(ids),'all_zip_bytes':sum(p.stat().st_size for p in O.glob('*.zip')),'black_cat_bytes':catalog['packages']['cat-black-v1']['bytes'],'catalog_bytes':C.stat().st_size},ensure_ascii=False))
