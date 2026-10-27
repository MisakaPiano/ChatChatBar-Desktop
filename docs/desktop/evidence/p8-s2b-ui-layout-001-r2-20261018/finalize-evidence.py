from pathlib import Path
import json,hashlib,shutil,subprocess,re,collections,xml.etree.ElementTree as ET
R=Path.cwd();G=Path(__file__).resolve().parent;E=R/'docs/desktop/evidence/p8-s2b-ui-layout-001-r2-20261018'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],stderr=subprocess.PIPE).decode().strip()
assert git('rev-parse','HEAD')=='934a349c51eeb51c1d7b7b12799b48594cb50358'
assert git('branch','--show-current')=='feature/phase8-fish-audio'
assert not git('diff','--name-only') and not git('diff','--cached','--name-only')
source=json.loads((G/'final-source.json').read_text())
for row in source['files']:assert sha(R/row['path'])==row['sha256']
manifest=json.loads((G/'distribution-manifest.json').read_text())
for row in manifest['files']:
    path=G/'runtime-image'/row['path'];assert path.stat().st_size==row['bytes'] and sha(path)==row['sha256']
process=json.loads((G/'runs/manual01/process-result.json').read_text(encoding='utf-8-sig'))
assert process['allOwnedProcessesExited'] and len(process['processes'])==2
assert all(p['exited'] and p['exitCode']==0 for p in process['processes'])
assert json.loads((G/'active-lock.result.json').read_text(encoding='utf-8-sig'))['exitCode']==75
assert json.loads((G/'post-exit-lock.result.json').read_text(encoding='utf-8-sig'))['exitCode']==0
mock=json.loads((G/'mock-final.json').read_text());assert mock['exited'] and mock['exitCode']==0 and not mock['privateKeyRemains'] and not mock['trustRemains']
assert not json.loads((G/'post-exit-preview-files.json').read_text())['files']
assert json.loads((G/'before-synthetic-cards.json').read_text())==json.loads((G/'after-synthetic-cards.json').read_text())
events=[json.loads(x) for x in (G/'mock-service/events.jsonl').read_text().splitlines()]
assert all(e.get('realRequests',0)==0 and not e.get('sampleSecretHeader',False) for e in events)
counts=collections.Counter(e['interface'] for e in events if 'interface' in e)
hold=json.loads((G/'manual-hold.json').read_text(encoding='utf-8-sig'))
hold.update(manualFail='Escape closed picker; actual narrow-popup vs wide-inline context UNKNOWN per user',stopControlFeedback='User explicitly reports no stop button seen; current conditional footer control exists, visibility root cause UNKNOWN',sourceAndEvidencePush='NOT PERFORMED; Project disposition required for existing local source checkpoint')
(G/'manual-hold.json').write_text(json.dumps(hold,indent=2)+'\n',encoding='utf-8')
fake=dict(physicalFakeRequests=dict(counts),realFishGetIncrement=0,realFishTtsIncrement=0,anySampleSecretHeader=False,unattributedAdditionalCatalogGet=True,audioFixturesNotRepublished=True)
(G/'fake-request-summary.json').write_text(json.dumps(fake,indent=2)+'\n')
old=['p8-s2b-ui-layout-001-20261018','p8-s2b-ui-layout-001-r1-20261018','p8-s2b-f2-r2-20261018']
preserved=[]
for name in old:
    path='docs/desktop/evidence/'+name
    before=git('rev-parse','f073749a6f57490516e7c2b1df516c5e6c53d13f:'+path);after=git('rev-parse','HEAD:'+path);assert before==after
    preserved.append(dict(path=path,gitTreeBefore=before,gitTreeAfter=after,unchanged=True))
(G/'history-preservation.json').write_text(json.dumps(preserved,indent=2)+'\n')
live=git('ls-remote','origin','refs/heads/feature/phase8-fish-audio').split()[0]
assert live=='f073749a6f57490516e7c2b1df516c5e6c53d13f'
rawNames=['README.md','MANUAL_RESULTS_CN.md','GIT_DISPOSITION.md','DIAGNOSIS.md','stage-summary.json','user-signatures.jsonl','manual-hold.json','manual-status.json','before-synthetic-cards.json','after-synthetic-cards.json','card-preservation.json','fake-request-summary.json','history-preservation.json','post-exit-lock.result.json','post-exit-lock.stdout.log','post-exit-lock.stderr.log','post-exit-preview-files.json','mock-final.json','mock-process.json','mock.stdout.log','mock.stderr.log','manual01.watch.stdout.log','manual01.watch.stderr.log','prepare-evidence.py','finalize-evidence.py']
for name in rawNames:shutil.copyfile(G/name,E/name)
shutil.copytree(G/'runs',E/'runs')
(E/'mock-service').mkdir()
for name in ['ready.json','events.jsonl','cleanup.json']:shutil.copyfile(G/'mock-service'/name,E/'mock-service'/name)
(E/'helper-default-encoding-command.txt').write_text('Reconstructed from the issued tool command; the initial command was not separately saved at invocation time.\n"C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/javac.exe" -J-Xmx128m -cp "app/desktopApp/build/p8-s2b-ui-layout-001-r2-20261018/runtime-image/app/*" -d "app/desktopApp/build/p8-s2b-ui-layout-001-r2-20261018/support-classes" "app/desktopApp/build/p8-s2b-ui-layout-001-r2-20261018/F2UiSupport.java"\n',encoding='utf-8')
allXml=list(E.glob('*/junit/TEST-*.xml'));assert len(allXml)==175
for path in allXml:ET.parse(path)
for file in E.glob('*.md'):
    for dest in re.findall(r'\]\(([^)]+)\)',file.read_text(encoding='utf-8')):
        if not dest.startswith(('http:','https:','#')):assert (file.parent/dest).exists(),(file,dest)
validation=dict(sourceCheckpoint=git('rev-parse','HEAD'),evidenceParent=git('rev-parse','HEAD'),tracking=git('rev-parse','origin/feature/phase8-fish-audio'),liveOrigin=live,pushPerformed=False,packageFilesRecheckedAfterExit=len(manifest['files']),packageManifestSha256=sha(G/'distribution-manifest.json'),xmlFilesParsed=175,manualRawTextRecords=len((G/'user-signatures.jsonl').read_text(encoding='utf-8-sig').splitlines()),codeChangesAfterWindowsFail=False,sourceWorktreeMatchesTestedHashes=True,trackedWorktreeUnchanged=True,indexEmptyBeforeEvidence=True,protectedUserOwnedUntrackedFiles=4,protectedUserContentsRead=False,protectedUserFilesStaged=False,markdownLinks='PASS',supervisorExitCode=0,finalState=hold['state'])
(E/'final-validation.json').write_text(json.dumps(validation,indent=2)+'\n')
files=[]
for path in sorted(E.rglob('*')):
    if path.is_file() and path.name!='manifest.json':
        assert path.suffix not in ['.mp3','.p12','.jks','.exe','.jar','.jpg'],path
        files.append(dict(path=path.relative_to(E).as_posix(),bytes=path.stat().st_size,sha256=sha(path)))
(E/'manifest.json').write_text(json.dumps(dict(algorithm='SHA-256',scope='All evidence files excluding manifest.json itself; raw bytes',files=files),indent=2)+'\n')
print(json.dumps(dict(manifestMembers=len(files),manifestSha256=sha(E/'manifest.json'),xmlFiles=175,sourceParent=validation['evidenceParent'],liveOrigin=live,pushed=False,manualState='HOLD')))
