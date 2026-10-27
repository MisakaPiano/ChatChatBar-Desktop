"""Freeze one verified full synthetic1112 package and PS5.1 guest tools. Never installs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile
import msi_safety as m
import build_directory_guard as guard


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('package','image','guard-build','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();repo=Path(__file__).resolve().parents[3]
    package=args.package.resolve();image=args.image.resolve();out=args.output.resolve()
    assert package.is_relative_to(repo/'app/desktopApp/build') and out.drive.upper()=='I:' and out.name=='CCB-OP1-DL1-20261027-r01'
    assert not out.exists() and not out.with_suffix('.zip').exists()
    for candidate in [package,image,out]:
        for p in [candidate,*candidate.parents]:
            if p.exists():assert not p.stat(follow_symlinks=False).st_file_attributes&0x400,'REPARSE_REFUSED'
    proof=json.loads((package.parent/'RESULT.json').read_text())
    dll=guard.reuse_production(args.guard_build,proof['embeddedGuard']['sha256'])
    assert proof['sha256']==m.sha(package) and proof['bytes']==package.stat().st_size
    with m.Database(package) as db:
        props=m.verify(db,'synthetic');assert props['ProductVersion']=='0.1.1112'
        assert dict(db.sql('SELECT `Name`,`Data` FROM `Binary`',2,streams=True))['CcbDirectoryGuard']['sha256']==m.sha(dll)
        graph=m.operation_resource_graph(m.operation_tables(db),props)
        files=[]
        for name in graph['files'].values():
            path=image/Path(name.replace('\\','/'))
            assert path.is_file() and path.resolve().is_relative_to(image)
            files.append({'path':name,'bytes':path.stat().st_size,'sha256':m.sha(path)})
        identity={'productName':props['ProductName'],'version':props['ProductVersion'],'productCode':props['ProductCode'],
            'upgradeCode':props['UpgradeCode'],'packageCode':db.package_code(),'fileName':package.name,'sha256':m.sha(package),
            'bytes':package.stat().st_size,'operationContract':1,'guardSha256':m.sha(dll),'files':files,
            'components':[{'id':r[0],'guid':r[1]} for r in db.rows('Component',6)],
            'registry':[{'key':r[2],'name':r[3],'value':r[4]} for r in db.rows('Registry',6)]}
    # The source image has217 files; JPackage's app/.jpackage.xml is tooling
    # metadata, not an authored File-table payload. Do not demand its installation.
    assert len(files)==216 and len(identity['components'])==218
    image_files={p.relative_to(image).as_posix() for p in image.rglob('*') if p.is_file()}
    assert image_files-{p['path'].replace('\\','/') for p in files}=={'app/.jpackage.xml'}
    identity['imageOnlyMetadataExcluded']=['app/.jpackage.xml']
    out.mkdir()
    shutil.copyfile(package,out/package.name)
    for name in ('Invoke-Operation1FreshGuest.ps1','Create-Operation1TestCopy.ps1'):
        shutil.copyfile(Path(__file__).with_name(name),out/name)
    (out/'PACKAGE_IDENTITY.json').write_text(json.dumps(identity,indent=2)+'\n',encoding='utf-8')
    helper_hash=m.sha(out/'Invoke-Operation1FreshGuest.ps1');identity_hash=m.sha(out/'PACKAGE_IDENTITY.json')
    wrapper='''param([switch]$ConfirmIsolatedGuest,[switch]$OpenSyntheticWindow)
$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'Invoke-Operation1FreshGuest.ps1'
if((Get-FileHash -LiteralPath $helper -Algorithm SHA256).Hash.ToLowerInvariant() -ne '__HELPER__'){throw 'FIXED_HELPER_HASH_MISMATCH'}
& $helper -ExpectedIdentitySha256 '__IDENTITY__' -ConfirmIsolatedGuest:$ConfirmIsolatedGuest -OpenSyntheticWindow:$OpenSyntheticWindow
'''.replace('__HELPER__',helper_hash).replace('__IDENTITY__',identity_hash)
    (out/'Run-FirstInstall-1112.ps1').write_text(wrapper,encoding='ascii')
    provenance=json.loads((package.parent/'SOURCE.json').read_text())
    (out/'CLASS_PROVENANCE.json').write_text(json.dumps(provenance,indent=2)+'\n')
    (out/'READ_ME_FIRST.txt').write_text('''INTERNAL UNSIGNED SYNTHETIC PACKAGE. NOT A PUBLIC RELEASE.
Full1112 first installation is NOT TESTED until the independent guest run.
Do not run on DD1/DL1 host or the old failure guest. Preserve all old packages/scenes.

DL1 administrator PowerShell, from this directory:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\\Create-Operation1TestCopy.ps1 -CreateCopy
This adds a NEW inactive clean-checkpoint copy and retained export, and disconnects
ONLY the new VM network. It does not revert/start/delete the original or old copy.
Stop on any error; do not clean/retry. Existing VM checkpoints/disks stay retained.

Open/start CCB-MSI-Op1-1112-1113 locally on DL1. Copy this whole directory into the
NEW guest, e.g. C:\\CCB-OP1-DL1-20261027-r01. In that guest, ordinary PowerShell:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\\Run-FirstInstall-1112.ps1 -ConfirmIsolatedGuest -OpenSyntheticWindow

Only synthetic1112 installs, ONCE, in its default per-user program directory.
It verifies full file hashes, native product/feature/component state, exact own
Registry values and the guard's rollback-enabled forward admission. After success
it opens the application with new isolated app-data/home/credential directories.
Look at that empty test window and close normally; do not configure accounts or
generate content. Job-descendant drain/root-lock proof is a later separate check.

Guest results: %LOCALAPPDATA%\\CCB-OP1-First-1112-r01\\RESULT.json,
INSTALLED_RESOURCES.json and install-1112.log. Preserve failure scene; no retry.
No installer upgrade/repair/uninstall, cleanup, real data import or provider call.
''',encoding='ascii')
    entries={p.name:{'bytes':p.stat().st_size,'sha256':m.sha(p)} for p in out.iterdir()}
    assert len(entries)==7
    archive=out.with_suffix('.zip')
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_STORED) as z:
        for p in sorted(out.iterdir()):z.write(p,arcname=out.name+'/'+p.name)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and len(z.infolist())==7
        for item in z.infolist():
            data=z.read(item);assert hashlib.sha256(data).hexdigest()==entries[Path(item.filename).name]['sha256']
    report={'zip':str(archive),'sha256':m.sha(archive),'bytes':archive.stat().st_size,'entries':entries,'sourceProvenance':provenance,
        'installerExecuted':False,'vmCreatedOrStarted':False,'sourcePackageUnchanged':m.sha(package)==proof['sha256']}
    (package.parent.parent/'TRANSFER_1112.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'zip':str(archive),'sha256':report['sha256'],'bytes':report['bytes'],'identitySha256':identity_hash}))


if __name__=='__main__':
    assert os.name=='nt' and __debug__
    main()
