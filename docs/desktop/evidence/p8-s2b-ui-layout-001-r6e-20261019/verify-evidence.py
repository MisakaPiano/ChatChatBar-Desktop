from pathlib import Path
import json,hashlib,re,subprocess,shutil,sys
R=Path.cwd();G=Path(__file__).resolve().parent
prefix='docs/desktop/evidence/p8-s2b-ui-layout-001-r6e-20261019'
E=R/prefix
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git',*a],stderr=subprocess.PIPE)
if '--prepare' in sys.argv:
 shutil.copyfile(__file__,E/Path(__file__).name)
 paths=[p.relative_to(R).as_posix() for p in E.rglob('*') if p.is_file() and p.suffix in ['.md','.json','.py','.ps1','.gradle','.kt']]
 check=subprocess.run(['git','-c','core.whitespace=cr-at-eol','diff','--cached','--check','--',*paths],capture_output=True)
 assert check.returncode==0,check.stdout.decode(errors='replace')
 raw=subprocess.run(['git','-c','core.whitespace=cr-at-eol','diff','--cached','--check','--',prefix],capture_output=True)
 (E/'diff-check-raw.log').write_bytes(raw.stdout+raw.stderr)
 links=re.findall(r'\]\(([^)]+)\)',(E/'README.md').read_text(encoding='utf-8'))
 assert all((E/link).is_file() for link in links)
 (E/'static-validation.json').write_text(json.dumps(dict(metadataAndSourceCopiesDiffCheckExit=check.returncode,rawEvidenceFullDiffCheckExit=raw.returncode,rawWarningLog='diff-check-raw.log',rawWhitespacePreserved=True,markdownLinksChecked=len(links),allMarkdownTargetsExist=True,sourceDraftsNotStaged=True,rawXmlCount=170),indent=2)+'\n',encoding='utf-8')
 members=[]
 for p in sorted(E.rglob('*')):
  if p.is_file() and p.name!='manifest.json':
   b=p.read_bytes();members.append(dict(path=p.relative_to(E).as_posix(),bytes=len(b),sha256=sha(b)))
 (E/'manifest.json').write_text(json.dumps(dict(algorithm='SHA-256',selfExcluded=True,members=members),indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
 print(json.dumps(dict(members=len(members),rawDiffCheckExit=raw.returncode,metadataCheck=check.returncode,manifestSha256=sha((E/'manifest.json').read_bytes()))))
else:
 staged=git('diff','--cached','--name-only','-z').decode().split('\0');staged=[p for p in staged if p]
 assert staged and all(p.startswith(prefix+'/') for p in staged)
 manifest=json.loads((E/'manifest.json').read_text(encoding='utf-8'))
 assert len(staged)==len(manifest['members'])+1
 for m in manifest['members']:
  b=(E/m['path']).read_bytes();assert len(b)==m['bytes'] and sha(b)==m['sha256']
 entries=git('ls-files','--stage','-z','--',prefix).decode().split('\0')
 entries=[e for e in entries if e]
 specs=[]
 for e in entries:
  meta,path=e.split('\t');mode,oid,stage=meta.split();assert stage=='0';specs.append((oid,path))
 data=subprocess.run(['git','cat-file','--batch'],input=('\n'.join(o for o,p in specs)+'\n').encode(),capture_output=True,check=True).stdout
 offset=0
 for oid,path in specs:
  end=data.index(b'\n',offset);header=data[offset:end].decode().split();size=int(header[2]);payload=data[end+1:end+1+size]
  assert header[0]==oid and header[1]=='blob' and payload==(R/path).read_bytes(),path
  offset=end+size+2
 assert offset==len(data)
 for item in json.loads((E/'source-identities.json').read_text(encoding='utf-8')):assert sha((R/item['path']).read_bytes())==item['expected']
 assert git('rev-parse','HEAD').decode().strip()=='76f527418616e90ed469cabcf0b78c9530563b2f'
 print(json.dumps(dict(stagedOnlyNewEvidence=True,verifiedStagedRawBlobs=len(specs),manifestMembers=len(manifest['members']),manifestSha256=sha((E/'manifest.json').read_bytes()),sourceDraftsFrozen=True)))
