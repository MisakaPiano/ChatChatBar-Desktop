from pathlib import Path
import hashlib,json,subprocess,shutil,os,re,datetime,difflib,ast
B=Path(__file__).resolve().parent
R=B.parents[3]
O=B/'windows-final-gate-r2-20261020'
F=B/'windows-final-gate-r3-20261020'
E=R/'docs/desktop/evidence/p8-s7-r3-offline-preflight-20261020'
J=Path(r'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin')
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as s:
  for b in iter(lambda:s.read(1048576),b''):h.update(b)
 return h.hexdigest()
def write(p,v):p.write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
assert not (F/'mock-service').exists() and not (F/'environments/p8s7-profile/ChatChatBarDesktop').exists()
preserved=json.loads((E/'preflight.json').read_text(encoding='utf-8'))['preservedR1R2']
manifest=json.loads((F/'distribution-manifest.json').read_text(encoding='utf-8'))
# Resume only the offline compile/seed/hydrate tail, without re-creating or clearing directories.
# A distinct stage name preserves all bytes of the original compile RED.
source=(B/'prepare-r3.py').read_text(encoding='utf-8')
tail=source[source.index('env=dict(os.environ);'):]
tail=tail.replace("run('01-helper-compile'","run('01b-helper-compile'")
assert not (E/'01b-helper-compile.result.json').exists()
exec(compile(tail,str(B/'prepare-r3.py')+' [offline continuation]','exec'),globals())
