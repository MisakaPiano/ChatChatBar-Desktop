import hashlib, json, shutil, subprocess
from datetime import datetime, timezone
from pathlib import Path

repo = Path('H:/ChatChatBar-Desktop')
scope = repo / 'app/desktopApp/build/post-p8-manual-reliability-r1-20261020'
source = '60cc44d0159528d2e6824fcaf88ee384964da307'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip() == source
assert not subprocess.check_output(['git', 'diff', 'HEAD', '--name-only'], cwd=repo, text=True).strip()
def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()
def files(root):
    return [{'path': p.relative_to(root).as_posix(), 'bytes': p.stat().st_size, 'sha256': sha(p)}
            for p in sorted(root.rglob('*')) if p.is_file()]
def write(path, value):
    assert not path.exists(), str(path)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
stage = scope / '06-package'
assert json.loads((stage / 'result.json').read_text(encoding='utf-8-sig'))['exitCode'] == 0
original = repo / 'app/desktopApp/build/post-p8-audio-maintenance-r1-20261020/windows-frozen-694a8bdd'
manifest = original / 'distribution-manifest.json'
assert sha(manifest) == 'd98691a663275c99aeee2318514db1e528e28c74a71349c232e235cc7f076ed1'
old = json.loads(manifest.read_text(encoding='utf-8-sig'))
assert files(original / 'runtime-image') == sorted(old['files'], key=lambda x: x['path']) or {x['path']:(x['bytes'], x['sha256'].lower()) for x in files(original / 'runtime-image')} == {x['path']:(x['bytes'], x['sha256'].lower()) for x in old['files']}
built = scope / 'package-60cc44d0-build/compose/binaries/main/app/ChatChatBarDesktop'
before = files(built)
assert len(before) == 212, f'Unexpected package count: {len(before)}'
frozen = scope / 'windows-frozen-60cc44d0'
assert not frozen.exists()
shutil.copytree(built, frozen / 'runtime-image')
after = files(frozen / 'runtime-image')
assert before == after
new_manifest = {'sourceCommit': source, 'base': 'c4098e08bec84372911a5a8bc38c2cfa72f98978',
    'formalUpstream': '5e76a9cb841736bbbf3499a2e35e5789af4c5ca8', 'fileCount': len(after), 'files': after}
write(frozen / 'distribution-manifest.json', new_manifest)
assert files(frozen / 'runtime-image') == after
write(scope / 'frozen-package.json', {'utc': datetime.now(timezone.utc).isoformat(), 'sourceCommit': source,
    'runtimeImage': str(frozen / 'runtime-image'), 'manifest': str(frozen / 'distribution-manifest.json'),
    'manifestSha256': sha(frozen / 'distribution-manifest.json'), 'verifiedFiles': len(after),
    'copyAndRehash': 'PASS', 'original694a8bdd212Files': 'PASS / UNCHANGED', 'guiStarted': False,
    'fakeStarted': False, 'syntheticAppRootSeeded': False})
print((scope / 'frozen-package.json').read_text(encoding='utf-8'))
