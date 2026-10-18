from pathlib import Path
import json,subprocess,hashlib
G=Path(__file__).resolve().parent;R=Path.cwd()
def git(*args):return subprocess.check_output(['git',*args],stderr=subprocess.PIPE)
expected='f073749a6f57490516e7c2b1df516c5e6c53d13f'
assert git('rev-parse','HEAD').decode().strip()==expected
assert git('rev-parse','origin/feature/phase8-fish-audio').decode().strip()==expected
live=git('ls-remote','origin','refs/heads/feature/phase8-fish-audio').decode().split()[0];assert live==expected
assert not git('diff','--cached','--name-only').strip()
rows=json.loads((G/'final-source.json').read_text())['files'];paths=[r['path'] for r in rows]
for row in rows:assert hashlib.sha256((R/row['path']).read_bytes()).hexdigest()==row['sha256']
tracked=set(git('diff','--name-only').decode().splitlines());assert tracked.issubset(paths)
for stage in ['02-repaired-methods','03-fish-ui-window','04-affected-regression','05-bounded-desktop']:
    assert json.loads((G/stage/'stage.json').read_text(encoding='utf-8-sig'))['exitCode']==0
review=dict(skills=['chatbar-fish-audio-voice','chatbar-shadcn-compose'],reviewedBeforeStaging=True,changedSkills=[],reason='Existing Fish panel remains the feature entry point; this scoped presentation candidate changes no documented API, storage, runtime or Android feature contract. No accepted runtime map or authority is promoted. Prior unrelated skill debt is not bundled into this narrow change.')
(G/'skill-review.json').write_text(json.dumps(review,indent=2)+'\n')
subprocess.run(['git','add','--',*paths],check=True)
assert set(git('diff','--cached','--name-only').decode().splitlines())==set(paths)
subprocess.run(['git','diff','--cached','--check'],check=True)
for row in rows:assert hashlib.sha256(git('show',':'+row['path'])).hexdigest()==row['normalizedSha256']
(G/'source-staged.patch').write_bytes(git('diff','--cached','--binary'))
(G/'source-staged-stat.txt').write_bytes(git('diff','--cached','--stat'))
result=subprocess.run(['git','commit','-m','优化 Fish 音色库布局与原生窗口适配'],capture_output=True)
(G/'source-commit.stdout.log').write_bytes(result.stdout);(G/'source-commit.stderr.log').write_bytes(result.stderr);assert result.returncode==0
commit=git('rev-parse','HEAD').decode().strip()
record=dict(commit=commit,parent=git('rev-parse','HEAD^').decode().strip(),files=paths,stat=git('show','--format=','--stat','HEAD').decode(),liveBeforeCommit=live,indexOnlyAuthorizedFiles=True,stagedBlobsMatchTestedNormalizedBytes=True,diffCheck='PASS')
(G/'source-commit.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))
