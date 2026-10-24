"""LR6-only static/public evidence check; no app imports or user input discovery."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

root = Path(__file__).resolve().parents[4]
prefix = 'docs/desktop/evidence/p11-lr6-20261024'
packet = root / prefix / 'P11_LR6_PROJECT_REVIEW_PACKET'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=root)

base = 'ceeeb09e3327aa70a4858548b94520d87f4c2690'
app = git('rev-parse', 'HEAD:app').decode().strip()
assert app == git('rev-parse', base + ':app').decode().strip() == 'b148fecde3f3604cdf2298d089dfc14eb362ae1a'
protected = ['app', '.agents', 'docs/desktop/10_UPSTREAM_BASELINE.json',
             'docs/desktop/13_FEATURE_PARITY.md', 'docs/desktop/14_UPSTREAM_COMPAT.md',
             'docs/desktop/18_DECISIONS.md', 'docs/desktop/93_PUBLIC_PREVIEW_POLICY.md',
             'docs/desktop/95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md']
assert not git('diff', '--name-only', base, '--', *protected).strip()
index = json.loads((packet / 'STORAGE_RAW_INDEX.json').read_text(encoding='utf-8'))
tree = git('ls-tree', '-r', 'HEAD', '--', prefix + '/raw').decode()
blobs = {line.split('\t')[1]: line.split('\t')[0].split()[2] for line in tree.splitlines()}
for row in index['raw']:
    b = (root / row['path']).read_bytes()
    assert len(b) == row['bytes'] and hashlib.sha256(b).hexdigest() == row['sha256']
    assert blobs[row['path']] == row['gitBlobRaw']
assert len(index['raw']) == len(blobs) == 90
source = json.loads((root / prefix / 'raw/source-audit-current.json').read_text(encoding='utf-8'))
for row in source['sources']:
    b = (root / row['path']).read_bytes()
    assert len(b) == row['bytes'] and hashlib.sha256(b).hexdigest() == row['sha256']
    assert b == (root / prefix / 'raw/audited-source' / row['path']).read_bytes()
links = 0
for p in packet.glob('*.md'):
    for dest in re.findall(r'\[[^\]]*\]\(([^)]+)\)', p.read_text(encoding='utf-8')):
        if '://' in dest or dest.startswith('#'):
            continue
        assert (p.parent / dest.split('#')[0]).exists(), 'LOCAL_PACKET_LINK_MISSING'
        links += 1
assert not git('ls-files', '--', 'app/desktopApp/build/p11-lr6-private-space').strip()
files = [p for p in (root / 'tools/desktop/interop/lr6').rglob('*') if p.is_file()]
files += [p for p in (root / prefix).rglob('*') if p.is_file()]
privacy_hits = 0
for p in files:
    b = p.read_bytes()
    privacy_hits += len(re.findall(rb'C:\\Users\\[^\\]+', b))
    privacy_hits += len(re.findall(rb'\bsk-[A-Za-z0-9]{32,}\b', b))
assert privacy_hits == 0, 'PUBLIC_PRIVACY_PATTERN_HIT'
result = dict(attribution='CODEX_SELF_CHECK_NOT_PROJECT_INDEPENDENT_REVIEW',
              checkpointHead=git('rev-parse', 'HEAD').decode().strip(), appTree=app,
              appAndBaselineAndPolicyUnchanged=True, raw90ShaLengthAndGitBlobPass=True,
              productionSourceSnapshots=45, selectedOfficialEarlier=7,
              officialSupplement='SELECTED_OFFICIAL_SUPPLEMENT.json',
              publicPrivacyPatternHits=privacy_hits,
              privacyLimit='Pattern check plus bounded synthetic/static provenance, not exhaustive secret detection',
              packetLocalLinksChecked=links, privateLedgerGitTracked=False,
              newJUnit='N/A_NO_JVM_OR_NATIVE_VM_TEST_RUN',
              nativeWindowsVm='ENVIRONMENT_NOT_PROVEN_NOT_TESTED',
              businessWiring='UNCHANGED_NO_APP_TREE_DIFF')
(packet / 'SOURCE_AND_BOUNDARY_CHECK.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result))
