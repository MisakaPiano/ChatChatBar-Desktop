from pathlib import Path
import json,hashlib,subprocess
R=Path.cwd();E=R/'docs/desktop/evidence/p8-s2b-f2-r1-20261018';G=R/'app/desktopApp/build/p8-s2b-f2-r2-20261018';sha=lambda b:hashlib.sha256(b).hexdigest()
p=(E/'fixed-source.patch').read_bytes();assert sha(p)=='18849e8b1035dbf78de08944ed36069e25288f1d8d093f71b5fc0c0a702f1a0f'
m=json.loads((E/'fixed-source-manifest.json').read_text(encoding='utf-8'))
for f in m['files']:
 b=(R/f['path']).read_bytes();assert len(b)==f['bytes'] and sha(b)==f['sha256'],f['path']
assert subprocess.check_output(['git','rev-parse','HEAD']).decode().strip()=='e3e0dcc1ee3324caf5e1f8f321b03449e716a061'
assert not subprocess.check_output(['git','diff','--cached','--name-only'])
assert subprocess.run(['git','apply','--cached','--check',str(E/'fixed-source.patch')]).returncode==0
(G/'preflight-source.json').write_text(json.dumps({'head':'e3e0dcc1ee3324caf5e1f8f321b03449e716a061','parent':'ade2680b97397814b4f0686c2d2766655d37be63','frozenPatchSha256':sha(p),'filesVerified':5,'files':m['files'],'protectedUserContentsRead':False},indent=2)+'\n',encoding='utf-8')
print('R1 frozen draft 5/5 and full patch identity PASS')
