from pathlib import Path
import os,json,subprocess,time,hashlib,socket,ssl,re
G=Path(__file__).resolve().parent
JAVA=Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe')
CP=str(G/'support-classes')+os.pathsep+str(G/'runtime-image/app/*')
PROFILE=G/'environments/r7-profile';ROOT=PROFILE/'ChatChatBarDesktop'
ENV=dict(os.environ)
for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:ENV.pop(k,None)
ENV['LOCALAPPDATA']=str(PROFILE)
PROFILE.mkdir(parents=True,exist_ok=False)
for n in ['preview-temp','uds','synthetic-home']:(G/n).mkdir(exist_ok=False)
def save(n,v):(G/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def helper(mode,*args):return [str(JAVA),'-Xmx128m','-Duser.home='+str(G/'synthetic-home'),'-cp',CP,'R7UiSupport',mode,*map(str,args)]
def logged(n,args):
 with (G/(n+'.stdout.log')).open('wb') as out,(G/(n+'.stderr.log')).open('wb') as err:
  p=subprocess.Popen(args,env=ENV,stdout=out,stderr=err);pid=p.pid
  rc=p.wait(timeout=90)
 save(n+'.json',dict(pid=pid,exitCode=rc,command=args))
 if rc:raise RuntimeError('Synthetic preparation helper failed; raw logs retained')
logged('synthetic-seed',helper('seed',ROOT))
assert json.loads((ROOT/'entities/app_settings.json').read_text(encoding='utf-8'))['fishAudioTtsModelId']=='s2.1-pro-free'
assert json.loads((PROFILE/'ChatChatBarDesktop.bootstrap.json').read_text())==dict(formatVersion=1,mode='DEFAULT')
cards={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'entities/character_cards').glob('*.json')}
save('synthetic-data-identity.json',dict(localAppData=str(PROFILE),selectedDataRoot=str(ROOT),selection='DEFAULT in isolated bootstrap',
 settings='Synthetic explicit s2.1-pro-free; no automatic AI or real model credentials',cards=cards,userDataAccess=False,
 credential='New synthetic string written to the fresh root DPAPI store; never read real Fish credentials',
 bootstrapSha256=hashlib.sha256((PROFILE/'ChatChatBarDesktop.bootstrap.json').read_bytes()).hexdigest()))
logged('prepared-root-lock',helper('lock',ROOT))
mock=None;responses=[]
folder=G/'preparation-mock-service'
try:
 out=(G/'preparation-mock.stdout.log').open('wb');err=(G/'preparation-mock.stderr.log').open('wb')
 mock=subprocess.Popen(helper('mock',folder),env=ENV,stdin=subprocess.PIPE,stdout=out,stderr=err)
 save('preparation-mock-process.json',dict(pid=mock.pid,role='owned loopback fake only; no GUI'))
 deadline=time.monotonic()+90
 while not (folder/'ready.json').exists():
  if mock.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Loopback fake readiness failed')
  time.sleep(.25)
 ready=json.loads((folder/'ready.json').read_text());assert ready['loopbackOnly'] and ready['realProviderRequests']==0
 port=ready['port']
 # Export only the public certificate to let the probe verify this local TLS server and hostname.
 pem=folder/'public-only.pem'
 logged('preparation-public-cert-export',[str(JAVA.parent/'keytool.exe'),'-exportcert','-rfc','-alias','r7','-keystore',str(folder/'public-trust.jks'),'-storepass','','-file',str(pem)])
 ctx=ssl.create_default_context(cafile=str(pem));ctx.check_hostname=True
 def request(host,method,path,body=b'',authenticated=False):
  plain=socket.create_connection(('127.0.0.1',port),timeout=10)
  plain.sendall(('CONNECT '+host+':443 HTTP/1.1\r\nHost: '+host+':443\r\n\r\n').encode('ascii'))
  head=b''
  while not head.endswith(b'\r\n\r\n'):
   b=plain.recv(1)
   if not b:raise RuntimeError('Local CONNECT rejected unexpectedly')
   head+=b
  assert head.startswith(b'HTTP/1.1 200')
  with ctx.wrap_socket(plain,server_hostname=host) as s:
   h=method+' '+path+' HTTP/1.1\r\nHost: '+host+'\r\nConnection: close\r\n'
   if authenticated:h+='Authorization: Bearer r7-synthetic-no-valid-account\r\n'
   if body:h+='model: s2.1-pro-free\r\nContent-Type: application/json\r\nContent-Length: '+str(len(body))+'\r\n'
   s.sendall(h.encode('ascii')+b'\r\n'+body);data=b''
   while True:
    b=s.recv(16384)
    if not b:break
    data+=b
    if len(data)>17*1024*1024:raise RuntimeError('Synthetic probe budget exceeded')
  head,payload=data.split(b'\r\n\r\n',1);assert head.startswith(b'HTTP/1.1 200')
  responses.append(dict(interface='catalog' if path.startswith('/model') else 'fake-tts' if method=='POST' else 'anonymous-sample',bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest(),destination='127.0.0.1',realRequests=0))
  return payload
 for page in [1,3]:
  data=json.loads(request('api.fish.audio','GET',f'/model?page_number={page}&page_size=20&self=false&sort_by=score',authenticated=True))
  assert data['total']==60 and len(data['items'])==20
  assert data['items'][0]['_id']==('r7-001' if page==1 else 'r7-041')
  assert data['has_more']==(page==1)
 sample=request('platform.r2.fish.audio','GET','/task/11111111111111111111111111111111.mp3')
 assert sample==(G/'live-official-sample.mp3').read_bytes()
 tts=request('api.fish.audio','POST','/v1/tts',json.dumps(dict(reference_id='r7-001',text='Synthetic readiness probe; no real TTS')).encode(),True)
 assert tts==(G/'synthetic-tts-long.mp3').read_bytes()
 # Deliberately unknown CONNECT is refused locally, never forwarded.
 with socket.create_connection(('127.0.0.1',port),timeout=10) as s:
  s.sendall(b'CONNECT unapproved.invalid:443 HTTP/1.1\r\n\r\n');assert s.recv(1)==b''
 save('fake-readiness-probes.json',dict(result='PASS',responses=responses,unknownOriginConnectRefused=True,
  catalogQueries=2,sampleGet=1,fakeTtsPost=1,realFishGet=0,realFishTts=0,scope='Support fixture readiness only; not packaged app acceptance or audio output proof'))
finally:
 if mock is not None and mock.poll() is None:
  mock.stdin.write(b'\n');mock.stdin.flush();mock.stdin.close();mock.wait(timeout=45)
 if mock is not None:
  out.close();err.close();save('preparation-mock-final.json',dict(pid=mock.pid,exitCode=mock.poll(),
   cleanup=json.loads((folder/'cleanup.json').read_text()) if (folder/'cleanup.json').exists() else None,
   privateKeyRemains=(folder/'ephemeral-private.p12').exists(),trustStoreRemains=(folder/'public-trust.jks').exists()))
  assert mock.returncode==0 and not (folder/'ephemeral-private.p12').exists() and not (folder/'public-trust.jks').exists()
 # Public validation certificate is temporary too; no certificate material is kept in evidence.
 if (folder/'public-only.pem').exists():(folder/'public-only.pem').unlink()
print('R7 isolated synthetic data and loopback fixture ready; preparation fake drained, temporary TLS removed; no GUI started.')
