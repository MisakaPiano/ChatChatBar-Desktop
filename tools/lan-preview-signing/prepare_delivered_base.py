"""Cloud-only provenance for the exact raw input behind delivered preview2-81."""
import json
import os
from pathlib import Path
import urllib.request

request = json.load(open('.github/lan-next-preview-build-request.json'))
base = request.get('previousDeliveredBase')
if base is None:
    raise SystemExit(0)
assert request.get('testFixtureOverlay') is None
expected = {
    'sourceSha': '08db90c2cec1611b7f56c688fb5f219ea7f051c5',
    'runId': 43333044270,
    'artifactId': 13995997920,
    'controlSha': '7da4418a300d03ffd808b31b4095f10ee8d55469',
    'outerBytes': 212829843,
    'outerSha256': '56f8f6bf8d3ee0d7f383d4eae63a5a71b467ba797cb23d00aa35c014d9b5c376',
    'rawBaseSha256': '4a65cc6da4b53cdef75cb848e3783a72ab08afe0f0870a14d6f5b3c25d80acdf',
    'deliveredBaseSha256': '98c925a0ffd30f0dff38970a1b57157c0a3c9c64c88f47fd210c220909653554',
}
assert base == expected
api = 'https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + '/actions/'
def get(path):
    headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'}
    return json.load(urllib.request.urlopen(urllib.request.Request(api + path, headers=headers)))
run = get('runs/' + str(base['runId']))
artifact = get('artifacts/' + str(base['artifactId']))
assert run['conclusion'] == 'success'
assert run['head_sha'] == artifact['workflow_run']['head_sha'] == base['controlSha']
assert artifact['workflow_run']['id'] == base['runId'] and not artifact['expired']
assert artifact['name'] == 'ccb-lan-next-signing-inputs'
assert artifact['size_in_bytes'] == base['outerBytes']
assert artifact['digest'] == 'sha256:' + base['outerSha256']
Path('raw-inputs/delivered-base-provenance.json').write_text(
    json.dumps({'previousDeliveredBase': base, 'artifact': artifact}, indent=2), encoding='utf8')
with open(os.environ['GITHUB_ENV'], 'a') as env:
    env.write('LAN_NEXT_DELIVERED_BASE=true\nLAN_NEXT_DELIVERED_BASE_RUN=' + str(base['runId']) + '\n')
