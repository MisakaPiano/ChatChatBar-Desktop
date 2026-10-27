from pathlib import Path
import shutil,json,hashlib
R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r2-20261018'
E.mkdir(exist_ok=False)
(E/'.gitattributes').write_text('* -text\n',encoding='utf-8')
names=['preflight.json','diagnostic-source.json','final-source.json','final-source.patch','r2-vs-r1.patch','source-staged.patch','source-staged-stat.txt','source-commit.json','source-commit.stdout.log','source-commit.stderr.log','skill-review.json','DIAGNOSIS.md','stage-summary.json','pre-bounded-resources.json','distribution-manifest.json','source-checkpoint.json','run-stage.ps1','bounded.init.gradle','snapshot-source.py','summarize-stages.py','commit-source.py','freeze-package.py','prepare-manual.py','F2UiSupport.java','manual-supervisor.py','runtime-watch.ps1','helper-compile.json','helper-compile.stdout.log','helper-compile.stderr.log','helper-compile-utf8.json','helper-compile-utf8.stdout.log','helper-compile-utf8.stderr.log','active-lock.result.json','active-lock.stdout.log','active-lock.stderr.log','synthetic-seed.result.json','synthetic-seed.stdout.log','synthetic-seed.stderr.log']
for name in names:shutil.copyfile(G/name,E/name)
for name in ['01-bounded-diagnostic','02-repaired-methods','03-fish-ui-window','04-affected-regression','05-bounded-desktop','06-create-distributable','diagnostic-sources','final-sources']:
    shutil.copytree(G/name,E/name)
source=json.loads((G/'final-source.json').read_text());diagnostics=json.loads((G/'diagnostic-source.json').read_text(encoding='utf-8-sig'))
for row in source['files']:assert hashlib.sha256((E/'final-sources'/Path(row['path']).name).read_bytes()).hexdigest()==row['sha256']
for row in diagnostics:assert hashlib.sha256((E/'diagnostic-sources'/Path(row['path']).name).read_bytes()).hexdigest()==row['sha256']
print('Copied immutable automated phases, source identities and package manifest. Manual gate remains pending; no evidence commit yet.')
