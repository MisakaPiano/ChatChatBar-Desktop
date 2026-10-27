"""Static source identity recheck only. No app imports, root reads or protector calls."""
from pathlib import Path
import hashlib
import json
import subprocess

repo = Path(__file__).resolve().parents[4]
raw = repo / 'docs/desktop/evidence/p11-lr6-20261024/raw'
previous = repo / 'docs/desktop/evidence/p11-lr5-20261024/raw'
main = json.loads((previous / 'source-audit-identities.json').read_text())
consumers = json.loads((previous / 'source-consumer-expanded.json').read_text())
paths = {r['path']: r for r in main['sources'] + consumers['sources']}
extra = [
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsDpapiSecretProtector.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDocumentIndexReadEvidence.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopCredentialImportPlan.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopCredentialSourceCensus.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/RecoverableIntentContract.kt',
]
for p in extra:
    paths.setdefault(p, None)
result = dict(attribution='CODEX_STATIC_REAUDIT_NOT_RUNTIME_OR_PROJECT_ACCEPTANCE',
              sourceHead=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
              appTree=subprocess.check_output(['git', 'rev-parse', 'HEAD:app'], cwd=repo, text=True).strip(),
              sources=[], official=[])
for relative, old in sorted(paths.items()):
    p = repo / relative
    b = p.read_bytes()
    blob = subprocess.check_output(['git', 'rev-parse', 'HEAD:' + relative], cwd=repo, text=True).strip()
    row = dict(path=relative, bytes=len(b), sha256=hashlib.sha256(b).hexdigest(), gitBlob=blob)
    if old:
        assert all(row[k] == old[k] for k in ('bytes', 'sha256', 'gitBlob')), 'CURRENT_SOURCE_CHANGED_SINCE_LR5'
        row['unchangedSinceLR5'] = True
    destination = raw / 'audited-source' / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open('xb') as f:
        f.write(b)
    result['sources'].append(row)
for old in main['selectedOfficialReferences']:
    b = subprocess.check_output(['git', 'show', old['commit'] + ':' + old['path']], cwd=repo)
    assert len(b) == old['bytes'] and hashlib.sha256(b).hexdigest() == old['sha256']
    result['official'].append(old)
with (raw / 'source-audit-current.json').open('x', encoding='utf-8') as f:
    json.dump(result, f, indent=2)
print(json.dumps(dict(sources=len(result['sources']), official=len(result['official']), appTree=result['appTree'], status='STATIC_IDENTITY_MATCH')))
