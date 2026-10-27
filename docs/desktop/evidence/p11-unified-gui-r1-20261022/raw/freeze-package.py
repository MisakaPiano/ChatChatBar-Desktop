from pathlib import Path
import hashlib, json, shutil, subprocess, zipfile
root = Path(r'H:\ChatChatBar-Desktop')
scope = root / 'app/desktopApp/build/p11-gui-r1-20261022'
result = json.loads((scope / '12-windows-package/result.json').read_text(encoding='utf-8-sig'))
assert result['exitCode'] == 0
sha = result['sourceCommit']
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()==sha
build = Path(result['buildDirectory'])
source = build / 'compose/binaries/main/app/ChatChatBarDesktop'
assert source.is_dir(), source
destination = scope / ('windows-frozen-' + sha[:8])
assert not destination.exists()
image = destination / 'runtime-image'
image.mkdir(parents=True)
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
rows=[]
for path in sorted(source.rglob('*')):
    assert not path.is_symlink()
    if not path.is_file():continue
    relative=path.relative_to(source)
    row={'path':relative.as_posix(),'size':path.stat().st_size,'sha256':digest(path)}
    target=image/relative
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(path,target)
    assert target.stat().st_size==row['size'] and digest(target)==row['sha256']
    rows.append(row)
assert {p.relative_to(image).as_posix() for p in image.rglob('*') if p.is_file()}=={r['path'] for r in rows}
identities=[]
for jar in (image/'app').glob('*.jar'):
    with zipfile.ZipFile(jar) as z:
        if 'desktop-build.properties' in z.namelist():identities.append(z.read('desktop-build.properties').decode())
assert len(identities)==1 and 'commit='+sha in identities[0] and 'version=0.1.0-preview.1' in identities[0]
manifest=destination/'package-manifest.json'
manifest.write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
installers=[]
for suffix in ('msi','exe'):
    folder=build / ('compose/binaries/main/'+suffix)
    for path in sorted(folder.glob('*.'+suffix)):
        target=destination/'installers'/path.name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(path,target)
        assert digest(path)==digest(target)
        installers.append({'path':target.relative_to(destination).as_posix(),'size':target.stat().st_size,'sha256':digest(target)})
archive=destination/('ChatChatBarDesktop-0.1.0-preview.1-'+sha[:8]+'-windows.zip')
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=5) as z:
    for row in rows:z.write(image/row['path'], 'ChatChatBarDesktop/'+row['path'])
proof=dict(sourceCommit=sha,version='0.1.0-preview.1',runtimeImage=str(image),files=len(rows),
    verified=len(rows),missing=0,extra=0,mismatch=0,manifest=str(manifest),manifestSha256=digest(manifest),
    portableZip=dict(path=str(archive),size=archive.stat().st_size,sha256=digest(archive)),
    installers=installers,buildIdentity=identities[0],guiStarted=False,installed=False)
(destination/'verification.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(proof,ensure_ascii=False,indent=2))


