"""Cloud-only one-file test API compatibility overlay; production inputs are reused."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

request = json.load(open('.github/lan-next-preview-build-request.json'))
overlay = request.get('testFixtureOverlay')
if overlay is None:
    raise SystemExit(0)
path = 'app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncPreviewUpgradeTest.kt'
assert overlay['path'] == path and re.fullmatch('[a-f0-9]{40}', overlay['sourceSha'])
assert re.fullmatch('[a-f0-9]{64}', overlay['sha256'])
source = Path(os.environ['LAN_NEXT_SOURCE'])
old = (source/path).read_bytes().replace(b'\r\n', b'\n')
new = subprocess.check_output(['git', 'show', overlay['sourceSha']+':'+path]).replace(b'\r\n', b'\n')
assert old.count(b'Path.of(') == 3
expected = old.replace(b'import java.nio.file.Path\n', b'import java.nio.file.Path\nimport java.nio.file.Paths\n').replace(b'Path.of(', b'Paths.get(')
assert new == expected and hashlib.sha256(new).hexdigest() == overlay['sha256']
(source/path).write_bytes(new)
changed = subprocess.check_output(['git', '-C', str(source), 'diff', '--name-only', 'HEAD'], text=True).splitlines()
assert changed == [path]
original = request['reusedOriginalArtifact']
assert original['id'] == 13993664446 and original['runId'] == 43330457689
assert original['controlSha'] == 'a2ce60578f4f1aea08bf3ff481ed2cc6b738f65c'
api = 'https://api.github.com/repos/'+os.environ['GITHUB_REPOSITORY']+'/actions/'
def get(path):
    headers = {'Authorization':'Bearer '+os.environ['GH_TOKEN'], 'Accept':'application/vnd.github+json'}
    return json.load(urllib.request.urlopen(urllib.request.Request(api+path,headers=headers)))
run = get('runs/'+str(original['runId']))
artifact = get('artifacts/'+str(original['id']))
assert run['head_sha'] == artifact['workflow_run']['head_sha'] == original['controlSha']
assert run['conclusion'] == 'failure'  # Preserved versionless-test wrapper failure.
assert artifact['workflow_run']['id'] == original['runId'] and not artifact['expired']
assert artifact['size_in_bytes'] == original['bytes'] and artifact['digest'] == 'sha256:'+original['outerSha256']
assert artifact['name'] == original['name'] == 'ccb-lan-next-signing-inputs'
jobs = get('runs/'+str(original['runId'])+'/jobs')['jobs']
assert len(jobs) == 1
steps = {s['name']:s['conclusion'] for s in jobs[0]['steps']}
for name in ['Build isolated81 and its instrumentation APK','Preserve81 inputs before the next variant build','Build isolated82 with matching source and retained fixture']:
    assert steps[name] == 'success'
assert steps['Pin all raw inputs and verify manifest identities'] == 'failure'
receipt = {'productionSourceSha':request['sourceSha'], 'testFixtureOverlay':overlay,
           'onlyChangedPath':changed, 'assertionsUnchanged':True, 'reusedOriginalArtifact':original}
Path('raw-inputs/fixture-overlay.json').write_text(json.dumps(receipt,indent=2),encoding='utf8')
with open(os.environ['GITHUB_ENV'],'a') as env:
    env.write('LAN_NEXT_FIXTURE_ONLY=true\nLAN_NEXT_REUSED_RUN='+str(original['runId'])+'\n')
