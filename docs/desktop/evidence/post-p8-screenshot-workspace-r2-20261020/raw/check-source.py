import json,hashlib,subprocess
from pathlib import Path
from datetime import datetime,timezone
repo=Path('H:/ChatChatBar-Desktop');scope=repo/'app/desktopApp/build/post-p8-screenshot-workspace-r2-20261020'
def git(*args): return subprocess.check_output(['git',*args],cwd=repo).decode().strip()
assert git('rev-parse','HEAD')=='22a8612b9076880ff6d2520068947cd23ea7c029'
assert git('branch','--show-current')=='feature/phase8-fish-audio'
assert not git('diff','--cached','--name-only')
assert not git('diff','60cc44d0159528d2e6824fcaf88ee384964da307','HEAD','--name-only','--','app','.agents')
base='app/desktopApp/src/'
files=['.agents/skills/chatbar-image-generation-runtime/SKILL.md']
files += [base+'main/kotlin/com/example/chatbar/desktop/'+n+'.kt' for n in ['DesktopChatScreenshotHost','DesktopChatScreenshotSelection','DesktopChatScreenshotRenderer','DesktopScreenshotWindow','DesktopScreenshotPreview']]
files += [base+'test/kotlin/com/example/chatbar/desktop/'+n+'.kt' for n in ['DesktopChatScreenshotTest','DesktopChatScreenshotUiTest','DesktopScreenshotWorkspaceTest','DesktopScreenshotWorkspaceUiTest']]
protected=['CCB_DESKTOP_STANDARD_CONVERSATION_HANDOFF.md']+[f'.codex-remote-attachments/01a10e16-9252-7930-afe1-f07cb92177e6/{p}/1-1000072937.jpg' for p in ['4bb6706b-0132-41be-bd8e-03e6c0229e46','b5264b76-6c08-406d-af8d-bbb2f876c7c1','ea41235d-a0f1-44c3-8db1-47f5bb5b2883']]
assert set(git('diff','--name-only').splitlines())<=set(files)
untracked=set(git('ls-files','--others','--exclude-standard').splitlines())
assert untracked-set(files)==set(protected)
statuses={p:git('status','--porcelain','--untracked-files=all','--',p) for p in protected}
assert all(v.startswith('?? ') for v in statuses.values())
final=json.loads((scope/'07-final-focused/summary.json').read_text(encoding='utf-8-sig'))
assert final['gradleExit']==0 and final['totalTests']==121 and final['failures']==final['errors']==final['skipped']==0 and len(final['suites'])==12
subprocess.run(['git','diff','--check'],cwd=repo,check=True)
identity=[]
for name in files:
 b=(repo/name).read_bytes()
 identity.append({'path':name,'rawBytes':len(b),'rawSha256':hashlib.sha256(b).hexdigest(),'normalizedGitBlob':git('hash-object','--path='+name,name)})
(scope/'tested-source-identity.json').write_text(json.dumps({'base':git('rev-parse','HEAD'),'files':identity},indent=2)+'\n',encoding='utf-8')
(scope/'preflight-and-protection.json').write_text(json.dumps({'utc':datetime.now(timezone.utc).isoformat(),'head':git('rev-parse','HEAD'),'branch':git('branch','--show-current'),
 'localDesktopUnchanged':git('rev-parse','desktop'),'formalBaseline':git('rev-parse','5e76a9cb841736bbbf3499a2e35e5789af4c5ca8'),
 'protectedPathStatusOnly':statuses,'protectedContentReadOrHashed':False,'protectedStaged':False,
 'sourceDiffCheck':'PASS','baselineToPreviousProductionNoSourceDrift':True,'allowedFiles':files},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'files':len(files),'focusedSuites':len(final['suites']),'tests':final['totalTests'],'protectedPaths':len(statuses),'sourceDiffCheck':'PASS'},indent=2))
