from pathlib import Path
import hashlib,json,shutil,subprocess,zipfile
repo=Path(r'H:\ChatChatBar-Desktop')
scope=repo/'app/desktopApp/build/p11-review-fixes-20261021'
dest=repo/'docs/desktop/evidence/p11-independent-review-20261021'
production='70b5c30c91439e79728bf224c1a44d35b25cf733'
base='e436f4223c70cf14d99ac84dfc6c128efda38249'
def git(*args): return subprocess.check_output(['git',*args],cwd=repo)
assert git('rev-parse','HEAD').decode().strip()==production
frozen=scope/'windows-frozen-70b5c30c'
proof=json.loads((frozen/'verification.json').read_text())
manifest=json.loads((frozen/'package-manifest.json').read_text())
with zipfile.ZipFile(proof['portableZip']['path']) as z:
    assert set(z.namelist())=={'ChatChatBarDesktop/'+r['path'] for r in manifest}
    for r in manifest:
        h=hashlib.sha256(); size=0
        with z.open('ChatChatBarDesktop/'+r['path']) as f:
            for b in iter(lambda:f.read(1024*1024),b''): h.update(b); size+=len(b)
        assert size==r['size'] and h.hexdigest()==r['sha256']
proof['portableZipVerifiedEntries']=len(manifest)
(dest/'PACKAGE_VERIFICATION.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
shutil.copyfile(frozen/'package-manifest.json',dest/'package-manifest.json')
(dest/'production.diff').write_bytes(git('diff','--binary',base,production,'--','app','.agents'))
(dest/'CHANGED_FILES.txt').write_bytes(git('diff','--name-status',base,production))
protected=['CCB_DESKTOP_STANDARD_CONVERSATION_HANDOFF.md']+[
    '.codex-remote-attachments/01a10e16-9252-7930-afe1-f07cb92177e6/'+p+'/1-1000072937.jpg'
    for p in ['4bb6706b-0132-41be-bd8e-03e6c0229e46','b5264b76-6c08-406d-af8d-bbb2f876c7c1','ea41235d-a0f1-44c3-8db1-47f5bb5b2883']]
states=[]
for p in protected:
    untracked=git('ls-files','--others','--exclude-standard','--',p).decode().strip()
    tracked=git('ls-files','--',p).decode().strip()
    assert untracked==p and not tracked
    states.append(dict(path=p,status='UNTRACKED / UNSTAGED',contentRead=False,hashed=False,modified=False))
assert not git('diff','--name-only',base,production,'--','docs/desktop/evidence').strip()
assert not git('diff','--name-only','--','app','.agents').strip()
(dest/'FINAL_SOURCE_AND_PROTECTION.json').write_text(json.dumps(dict(base=base,production=production,branch=git('branch','--show-current').decode().strip(),protected=states,historicalEvidenceChanges=[],productionWorkingTreeClean=True,realApiRequests=0,guiStarted=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(zipEntriesVerified=len(manifest),protectedPathsVerified=len(states),production=production)))
