from pathlib import Path
import urllib.request,re,json,hashlib,datetime
G=Path(__file__).resolve().parent
url='https://fish.audio/m/7f92f8afb8ec43bf81429cc1c9199cb1/'
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None
req=urllib.request.Request(url,headers={'User-Agent':'CCB-Anonymous-Sample-Source-Check/1.0'})
with urllib.request.build_opener(NoRedirect).open(req,timeout=30) as response:
    assert response.status==200
    raw=response.read(4*1024*1024+1);assert len(raw)<=4*1024*1024
(G/'official-voice-page-local-only.html').write_bytes(raw)
page=raw.decode('utf-8');start=page.index('streamController.enqueue(')+len('streamController.enqueue(')
encoded,_=json.JSONDecoder().raw_decode(page[start:]);table=json.loads(encoded)
def value(i):return table[i] if isinstance(i,int) and i>=0 else None
def obj(d):return {value(int(k[1:])):v for k,v in d.items() if k.startswith('_')}
approved=re.compile(r'https://platform\.r2\.fish\.audio/(?:task/[0-9a-f]{32}|tasks/[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9a-f]{32})\.mp3')
chosen=None;unsupported=0
for entry in table:
    if not isinstance(entry,dict):continue
    d=obj(entry)
    if '_id' not in d or 'samples' not in d:continue
    samples=value(d['samples']) or []
    for index in samples:
        sample=obj(value(index));audio=value(sample.get('audio',-1)) or value(sample.get('audio_url',-1))
        if not isinstance(audio,str) or not audio.strip():continue
        if approved.fullmatch(audio):chosen={'referenceId':value(d['_id']),'url':audio};break
        unsupported+=1;break # Do not invent a later-sample fallback for this model.
    if chosen:break
result={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'page':url,'sourcePageGET':1,
    'sourcePageStatus':200,'rawPageBytes':len(raw),'rawPageSha256':hashlib.sha256(raw).hexdigest(),
    'rawPagePublication':'LOCAL ONLY — may contain expiring signed capabilities',
    'chosen':chosen,'precedingUnsupportedModels':unsupported,'sampleGET':0,'TTS':0,
    'approvedOrigin':'https://platform.r2.fish.audio','authenticatedSampleOrigins':[],
    'basis':'Official public SSR sample metadata; official Audio element and non-CN URL mapping were independently frozen in F2 source-provenance evidence. Only the unchanged anonymous MP3 profile is eligible.'}
(G/'live-source-basis.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
if chosen is None:raise RuntimeError('UNSUPPORTED: no current approved first sample; do not expand profile')
print(json.dumps({'sourcePageStatus':200,'approvedFirstSampleFound':True,'origin':result['approvedOrigin'],'sourceSampleUrlSha256':hashlib.sha256(chosen['url'].encode()).hexdigest(),'sampleGET':0,'TTS':0}))
