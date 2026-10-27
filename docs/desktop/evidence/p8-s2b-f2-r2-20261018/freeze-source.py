from pathlib import Path
import subprocess,json,hashlib,sys
R=Path.cwd();G=R/'app/desktopApp/build/p8-s2b-f2-r2-20261018';name=sys.argv[1]
rows=json.loads((G/'preflight-source.json').read_text(encoding='utf-8'))['files'];files=[f['path'] for f in rows]
allowed=['DesktopFishSampleTest.kt']
for f in rows:
 if Path(f['path']).name not in allowed:assert hashlib.sha256((R/f['path']).read_bytes()).hexdigest()==f['sha256'],f['path']
patch=subprocess.check_output(['git','diff','--binary','HEAD'])
for n in files:
 if subprocess.run(['git','cat-file','-e','HEAD:'+n],capture_output=True).returncode!=0:
  b=(R/n).read_bytes().replace(b'\r\n',b'\n');lines=b.splitlines(keepends=True)
  patch+=('diff --git a/'+n+' b/'+n+'\nnew file mode 100644\n--- /dev/null\n+++ b/'+n+'\n@@ -0,0 +1,'+str(len(lines))+' @@\n').encode()+b''.join(b'+'+x for x in lines)
(G/(name+'.patch')).write_bytes(patch)
data={'base':'e3e0dcc1ee3324caf5e1f8f321b03449e716a061','patchSha256':hashlib.sha256(patch).hexdigest(),'unchangedFrozenFiles':4,'files':[{'path':n,'bytes':len((R/n).read_bytes()),'sha256':hashlib.sha256((R/n).read_bytes()).hexdigest(),'normalizedSha256':hashlib.sha256((R/n).read_bytes().replace(b'\r\n',b'\n')).hexdigest()} for n in files]}
(G/(name+'-manifest.json')).write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8');print(name+': source snapshot saved; other four drafts unchanged')
