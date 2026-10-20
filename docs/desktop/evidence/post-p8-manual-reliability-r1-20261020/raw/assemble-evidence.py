import hashlib, json, shutil, subprocess
from pathlib import Path

repo = Path('H:/ChatChatBar-Desktop')
scope = repo / 'app/desktopApp/build/post-p8-manual-reliability-r1-20261020'
dest = repo / 'docs/desktop/evidence/post-p8-manual-reliability-r1-20261020'
def git(*args): return subprocess.check_output(['git', *args], cwd=repo, text=True).strip()
def write(path, value):
    assert not path.exists(), str(path)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
assert git('rev-parse', 'HEAD') == '60cc44d0159528d2e6824fcaf88ee384964da307'
assert not git('diff', '--name-only') and not git('diff', '--cached', '--name-only')
protected = ['CCB_DESKTOP_STANDARD_CONVERSATION_HANDOFF.md'] + [f'.codex-remote-attachments/01a10e16-9252-7930-afe1-f07cb92177e6/{p}/1-1000072937.jpg' for p in ['4bb6706b-0132-41be-bd8e-03e6c0229e46','b5264b76-6c08-406d-af8d-bbb2f876c7c1','ea41235d-a0f1-44c3-8db1-47f5bb5b2883']]
statuses = {p: git('status', '--porcelain', '--untracked-files=all', '--', p) for p in protected}
assert all(v.startswith('?? ') for v in statuses.values())
write(scope / 'protection-final.json', {'head': git('rev-parse','HEAD'), 'parent': git('rev-parse','HEAD^'),
    'branch': git('branch','--show-current'), 'desktop': git('rev-parse','desktop'),
    'formalBaseline': git('rev-parse','5e76a9cb841736bbbf3499a2e35e5789af4c5ca8'),
    'protectedPathStatusOnly': statuses, 'contentRead': False, 'hashed': False, 'staged': False,
    'trackedWorktreeClean': True, 'indexCleanBeforeEvidence': True,
    'originalManualEvidenceChanges': git('diff','c4098e08bec84372911a5a8bc38c2cfa72f98978','HEAD','--name-only','--','docs/desktop/evidence/post-p8-windows-manual-20261020')})
raw = dest / 'raw'
assert not raw.exists()
raw.mkdir()
stages = ['01-initial-voice-red','02-click-and-screenshot-focused','03-affected-regression','04-fixed-fixture-focused','05-final-affected-regression','06-package']
for stage in stages: shutil.copytree(scope / stage, raw / stage)
root_files = ['preflight.json','resources.jsonl','resources-final.json','screenshot-diagnosis.md','deferred-screenshot-window-contract.md','source-paths.json','tested-source-identity.json','source-checkpoint.json','source.patch','frozen-package.json','protection-final.json','package.init.gradle','run-package.ps1','freeze-package.py','assemble-evidence.py']
for name in root_files: shutil.copyfile(scope / name, raw / name)
(dest / 'package').mkdir()
shutil.copyfile(scope / 'windows-frozen-60cc44d0/distribution-manifest.json', dest / 'package/distribution-manifest.json')
entries = []
for p in sorted(dest.rglob('*')):
    if p.is_file() and p != dest / 'manifest.json':
        entries.append({'path': p.relative_to(dest).as_posix(), 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()})
write(dest / 'manifest.json', {'sourceCommit': git('rev-parse','HEAD'), 'fileCount': len(entries), 'files': entries,
    'excludesOnly': 'root manifest.json (self)', 'rawEvidenceTextConversion': 'disabled by local .gitattributes'})
print(json.dumps({'files':len(entries)+1, 'totalBytes':sum(x['bytes'] for x in entries), 'source':git('rev-parse','HEAD')}, indent=2))
