from pathlib import Path
import re,json,urllib.parse,hashlib
G=Path('app/desktopApp/build/p8-s2b-f2-20261018');s=(G/'official-voice-page.html').read_text(encoding='utf-8');pos=s.index('streamController.enqueue(')+len('streamController.enqueue(')
encoded,n=json.JSONDecoder().raw_decode(s[pos:]);table=json.loads(encoded)
def value(i):return table[i] if isinstance(i,int) and i>=0 else None
def obj(d):return {value(int(k[1:])):v for k,v in d.items() if k.startswith('_')}
models=[]
for entry in table:
 if not isinstance(entry,dict):continue
 d=obj(entry)
 if '_id' not in d or 'samples' not in d:continue
 samples=[]
 for si in value(d['samples']) or []:
  sd=obj(value(si));audio=value(sd.get('audio',-1))
  if not isinstance(audio,str):continue
  u=urllib.parse.urlsplit(audio)
  samples.append(dict(origin=u.scheme+'://'+u.netloc,path=u.path,hasQuery=bool(u.query),audioUrlSha256=hashlib.sha256(audio.encode()).hexdigest()))
 models.append(dict(id=value(d['_id']),samples=samples))
(G/'official-sample-origins.json').write_text(json.dumps(dict(page='https://fish.audio/m/7f92f8afb8ec43bf81429cc1c9199cb1/',pageSha256=hashlib.sha256((G/'official-voice-page.html').read_bytes()).hexdigest(),models=models,rawPageLocalOnly='contains expiring signed capabilities; not public evidence'),indent=2)+'\n',encoding='utf-8')
print(json.dumps(models))
