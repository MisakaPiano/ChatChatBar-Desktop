"""Loopback-only synthetic responder; no proxy/forward/provider client exists."""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from pathlib import Path
import argparse,json,threading,time,urllib.request
events=[]
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def send(self,status,body):
  self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps(body).encode())
 def do_GET(self):
  events.append(dict(method='GET',path=self.path))
  if self.path=='/health':self.send(200,dict(ready=True,loopbackOnly=True,forwarding=False))
  elif self.path=='/v1/models':self.send(200,dict(object='list',data=[dict(id='unified-offline',object='model')]))
  else:self.send(404,dict(error='UNSUPPORTED_SYNTHETIC_ENDPOINT'))
 def do_POST(self):
  length=int(self.headers.get('Content-Length','0'))
  if length<0 or length>1024*1024:return self.send(413,dict(error='BUDGET'))
  try:body=json.loads(self.rfile.read(length))
  except Exception:return self.send(400,dict(error='INVALID_JSON'))
  events.append(dict(method='POST',path=self.path,stream=body.get('stream') is True))
  if self.path=='/v1/embeddings':
   values=body.get('input',[]);values=values if isinstance(values,list) else [values]
   return self.send(200,dict(object='list',data=[dict(object='embedding',index=i,embedding=[.1,.2]) for i,_ in enumerate(values)]))
  if self.path!='/v1/chat/completions':return self.send(404,dict(error='UNSUPPORTED_SYNTHETIC_ENDPOINT'))
  text='<n="Alice"/>[离线合成回复：灯塔资料已收到，可以继续核对菜单、复制和存档。](平静)'
  if not body.get('stream'):return self.send(200,dict(choices=[dict(index=0,message=dict(role='assistant',content=text),finish_reason='stop')]))
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  try:
   for offset in range(0,len(text),4):
    chunk=dict(id='synthetic',object='chat.completion.chunk',choices=[dict(index=0,delta=dict(content=text[offset:offset+4]),finish_reason=None)])
    self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode());self.wfile.flush();time.sleep(.12)
   self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()
  except (BrokenPipeError,ConnectionResetError):events.append(dict(event='CLIENT_CANCELLED'))
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--self-test',action='store_true');parser.add_argument('--serve',action='store_true');args=parser.parse_args()
 if args.self_test:
  server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever);thread.start()
  base='http://127.0.0.1:'+str(server.server_port)
  try:
   opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
   assert json.load(opener.open(base+'/health'))['forwarding'] is False
   for endpoint,body in [('/v1/chat/completions',dict(stream=True,messages=[dict(role='user',content='synthetic')])) ,('/v1/embeddings',dict(input=['synthetic']))]:
    result=opener.open(urllib.request.Request(base+endpoint,data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})).read()
    assert result and (b'[DONE]' in result if body.get('stream') else b'embedding' in result)
  finally:server.shutdown();server.server_close();thread.join()
  Path(__file__).with_name('offline-api-selftest.json').write_text(json.dumps(dict(result='PASS',events=events,externalRequests=0,serviceStopped=True),indent=2),encoding='utf-8')
 elif args.serve:
  server=ThreadingHTTPServer(('127.0.0.1',18793),Handler)
  try:server.serve_forever()
  finally:
   server.server_close();Path(__file__).with_name('offline-api-session.json').write_text(json.dumps(dict(events=events,externalRequests=0)),encoding='utf-8')
 else:parser.error('Specify --self-test or --serve; GUI is never launched here')
