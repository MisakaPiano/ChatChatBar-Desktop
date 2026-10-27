from pathlib import Path
import json,hashlib,subprocess,shutil,datetime
base=Path(__file__).resolve().parent
repo=base.parents[3]
run=base/'gui-run-20261022'
root=base/'environments/manual-ready/app-data'
evidence=repo/'docs/desktop/evidence/p11-unified-windows-gui-20261022'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()=='37c67c724fe21107d7eaff0306439481c6f2bf07'
assert not subprocess.check_output(['git','diff','--name-only','70b5c30c','--','app','.agents'],cwd=repo).strip()
image=repo/'app/desktopApp/build/p11-review-fixes-20261021/windows-frozen-70b5c30c/runtime-image'
manifest=image.parent/'package-manifest.json'
entries=json.loads(manifest.read_text())
assert len(entries)==217 and sha(manifest)=='841924831a065c080e502860bdd4631a9d919975438414b8b474b16ed4add71c'
assert {p.relative_to(image).as_posix() for p in image.rglob('*') if p.is_file()}=={e['path'] for e in entries}
for e in entries:
 p=image/e['path'];assert p.stat().st_size==e['size'] and sha(p)==e['sha256']
save(run/'package-after.json',dict(verified=217,missing=0,extra=0,mismatch=0,manifestSha256=sha(manifest),production='70b5c30c91439e79728bf224c1a44d35b25cf733'))
java=r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
p=subprocess.run([java,'-cp',str(base/'support-classes'),'RootLockProbe'],capture_output=True,text=True)
save(run/'lock-after.json',dict(exitCode=p.returncode,stdout=p.stdout,stderr=p.stderr));assert p.returncode==0
scratch=list((root/'backups/.full-app-work').rglob('*'))
assert not any(p.is_file() for p in scratch)
backup=base/'exports/cbbackup/gui-unified-20261022.cbbackup'
original=repo/'app/desktopApp/build/post-p8-screenshot-windows-manual-20261021/exports/png/ChatBar.cbbackup'
assert sha(backup)==sha(original)=='0c038bfc9a76bd0209aa7e088ef2f4f9dcdacd17bb9f4c9d26cdcad00ce69799'
files=[]
extended=Path('\\\\?\\'+str(root))
for path in sorted(extended.rglob('*')):
 if path.is_file():files.append(dict(path=path.relative_to(extended).as_posix(),size=path.stat().st_size,sha256=sha(path)))
save(run/'synthetic-root-final-manifest.json',dict(root=str(root),credentialProfileExcluded=True,files=files))
save(run/'cleanup-and-backup.json',dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(),scratchFilesAfter=0,scratchFilesBeforeCancel=193,backupBytes=backup.stat().st_size,backupSha256=sha(backup),originalExportPreserved=True,previewAndPartFiles=[f['path'] for f in files if f['path'].endswith('.part')],generatedAudioRetained=True,scope='No recursive cleanup performed; application owned its cleanup. Historical journals retained. No native handle sweep performed.'))
last=run/'feedback-017';last.mkdir()
shutil.copy2(Path(r'C:\Users\1\AppData\Local\Temp\codex-clipboard-0ab9a081-3229-4cd9-80ab-5c7cf1a679fb.png'),last)
save(last/'feedback.json',dict(userVerbatim='已关闭',observed='截图显示已取消恢复，当前数据未改变；用户报告随后关闭主窗口',status='USER confirmed close; technical evidence separate'))
evidence.mkdir()
(evidence/'raw').mkdir()
for path in run.rglob('*'):
 if path.is_file() and path.suffix!='.class':
  target=evidence/'raw'/path.relative_to(run);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
(evidence/'tools').mkdir()
for name in ['gui-run-20261022.py','finalize-gui-20261022.py']:shutil.copy2(base/name,evidence/'tools'/name)
(evidence/'.gitattributes').write_text('raw/** -text -whitespace\ntools/** -text -whitespace\n*.json -text -whitespace\n',encoding='utf-8')
save(evidence/'RAW_SHA256.json',[dict(path=p.relative_to(evidence).as_posix(),size=p.stat().st_size,sha256=sha(p)) for p in sorted((evidence/'raw').rglob('*')) if p.is_file()])
print(json.dumps(dict(packageFiles=217,rootFiles=len(files),scratchFiles=0,evidence=str(evidence))))
