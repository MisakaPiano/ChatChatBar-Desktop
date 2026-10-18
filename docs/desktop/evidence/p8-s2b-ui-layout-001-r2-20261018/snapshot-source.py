from pathlib import Path
import subprocess,json,hashlib,difflib
R=Path.cwd();G=R/'app/desktopApp/build/p8-s2b-ui-layout-001-r2-20261018'
F=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r1-20261018'
old=json.loads((F/'pre-focused-source.json').read_text(encoding='utf-8-sig'))
paths=[x['path'] for x in old['files']]+['app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopStudioControls.kt']
patch=subprocess.check_output(['git','diff','--binary','HEAD','--',*paths])
delta=b'';rows=[]
for n in paths:
    b=(R/n).read_bytes();norm=b.replace(b'\r\n',b'\n')
    try:base=subprocess.check_output(['git','show','HEAD:'+n],stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        lines=norm.splitlines(keepends=True)
        patch+=(f'diff --git a/{n} b/{n}\nnew file mode 100644\n--- /dev/null\n+++ b/{n}\n@@ -0,0 +1,{len(lines)} @@\n').encode()+b''.join(b'+'+x for x in lines)
        base=b''
    prior=F/'draft-sources'/Path(n).name
    frozen=prior.read_bytes().replace(b'\r\n',b'\n') if prior.exists() else base
    delta+=''.join(difflib.unified_diff(frozen.decode().splitlines(keepends=True),norm.decode().splitlines(keepends=True),fromfile='r1/'+n,tofile='r2/'+n)).encode()
    rows.append(dict(path=n,bytes=len(b),sha256=hashlib.sha256(b).hexdigest(),normalizedSha256=hashlib.sha256(norm).hexdigest()))
(G/'final-source.patch').write_bytes(patch);(G/'r2-vs-r1.patch').write_bytes(delta)
(G/'final-source.json').write_text(json.dumps(dict(base=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),files=rows,patchSha256=hashlib.sha256(patch).hexdigest(),r2DeltaSha256=hashlib.sha256(delta).hexdigest()),indent=2)+'\n',encoding='utf-8')
copies=G/'final-sources';copies.mkdir(exist_ok=True)
for n in paths:(copies/Path(n).name).write_bytes((R/n).read_bytes())
print('Frozen nine source/test files; complete patch and R1 delta saved.')
