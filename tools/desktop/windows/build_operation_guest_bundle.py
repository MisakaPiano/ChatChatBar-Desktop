"""Freeze one verified full synthetic1112 package and PS5.1 guest tools. Never installs."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import zipfile
import msi_safety as m
import build_directory_guard as guard


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('package','image','guard-build','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--sequence',type=int,default=1112)
    args=parser.parse_args();repo=Path(__file__).resolve().parents[3]
    assert 1100 <= args.sequence <= 1199, 'BOUNDED_SYNTHETIC_SEQUENCE_REQUIRED'
    package=args.package.resolve();image=args.image.resolve();out=args.output.resolve()
    legacy=args.sequence==1112 and out.name=='CCB-OP1-DL1-20261027-r01'
    assert package.is_relative_to(repo/'app/desktopApp/build') and out.drive.upper()=='I:'
    assert legacy or re.fullmatch(r'CCB-OP1-DL1-'+str(args.sequence)+r'-\d{8}-r\d{2}',out.name), 'FIXED_VERSIONED_DELIVERY_NAME_REQUIRED'
    assert not out.exists() and not out.with_suffix('.zip').exists()
    for candidate in [package,image,out]:
        for p in [candidate,*candidate.parents]:
            if p.exists():assert not p.stat(follow_symlinks=False).st_file_attributes&0x400,'REPARSE_REFUSED'
    proof=json.loads((package.parent/'RESULT.json').read_text())
    dll=guard.reuse_production(args.guard_build,proof['embeddedGuard']['sha256'])
    assert proof['sha256']==m.sha(package) and proof['bytes']==package.stat().st_size
    with m.Database(package) as db:
        props=m.verify(db,'synthetic');assert props['ProductVersion']=='0.1.'+str(args.sequence)
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
    helper_source=Path(__file__).with_name('Invoke-Operation1FreshGuest.ps1')
    helper=helper_source.read_text(encoding='utf-8')
    assert "'0.1.1112'" in helper and 'install-1112.log' in helper
    (out/helper_source.name).write_text(helper.replace('1112',str(args.sequence)),encoding='utf-8')
    if legacy:
        shutil.copyfile(Path(__file__).with_name('Create-Operation1TestCopy.ps1'),out/'Create-Operation1TestCopy.ps1')
    (out/'PACKAGE_IDENTITY.json').write_text(json.dumps(identity,indent=2)+'\n',encoding='utf-8')
    helper_hash=m.sha(out/'Invoke-Operation1FreshGuest.ps1');identity_hash=m.sha(out/'PACKAGE_IDENTITY.json')
    wrapper='''param([switch]$ConfirmIsolatedGuest,[switch]$OpenSyntheticWindow)
$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'Invoke-Operation1FreshGuest.ps1'
if((Get-FileHash -LiteralPath $helper -Algorithm SHA256).Hash.ToLowerInvariant() -ne '__HELPER__'){throw 'FIXED_HELPER_HASH_MISMATCH'}
& $helper -ExpectedIdentitySha256 '__IDENTITY__' -ConfirmIsolatedGuest:$ConfirmIsolatedGuest -OpenSyntheticWindow:$OpenSyntheticWindow
'''.replace('__HELPER__',helper_hash).replace('__IDENTITY__',identity_hash)
    (out/('Run-FirstInstall-'+str(args.sequence)+'.ps1')).write_text(wrapper,encoding='ascii')
    provenance=json.loads((package.parent/'SOURCE.json').read_text())
    (out/'CLASS_PROVENANCE.json').write_text(json.dumps(provenance,indent=2)+'\n')
    legacy_readme='''INTERNAL UNSIGNED SYNTHETIC PACKAGE. NOT A PUBLIC RELEASE.
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
'''
    bounded_readme='''INTERNAL UNSIGNED SYNTHETIC CANDIDATE. NOT A PUBLIC RELEASE.
Actual installation is NOT TESTED. This delivery contains no VM-creation entry.
Do not execute on DD1/DL1 host or against daily installations/data/credentials.
The host/guest selection and actual install need separate explicit confirmation.
No installer has been run by the build or hash-only verification.

Only after authorization in the selected isolated Win10 Pro19045 x64 guest:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\\Run-FirstInstall-__SEQUENCE__.ps1 -ConfirmIsolatedGuest -OpenSyntheticWindow

This is one complete synthetic first installation, not a metadata-only probe.
Expected: engine0, native product/feature/components,216 file hashes, own Registry,
forward admission with rollback enabled; isolated empty app window then normal close.
Any error: keep scene/logs, do not retry or clean. No upgrade/repair/uninstall.
Guest results: %LOCALAPPDATA%\\CCB-OP1-First-__SEQUENCE__-r01.
The helper was version-stamped from the retained common first-install source;
only fixed1112 identity/result/log tokens change. The actual UI/Job drain and
native lifecycle remain NOT TESTED until executed and independently checked.
'''.replace('__SEQUENCE__',str(args.sequence))
    (out/'READ_ME_FIRST.txt').write_text(legacy_readme if legacy else bounded_readme,encoding='ascii')
    entries={p.name:{'bytes':p.stat().st_size,'sha256':m.sha(p)} for p in out.iterdir()}
    assert len(entries)==(7 if legacy else 6)
    archive=out.with_suffix('.zip')
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_STORED) as z:
        for p in sorted(out.iterdir()):z.write(p,arcname=out.name+'/'+p.name)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and len(z.infolist())==len(entries)
        for item in z.infolist():
            data=z.read(item);assert hashlib.sha256(data).hexdigest()==entries[Path(item.filename).name]['sha256']
    report={'zip':str(archive),'sha256':m.sha(archive),'bytes':archive.stat().st_size,'entries':entries,'sourceProvenance':provenance,
        'installerExecuted':False,'vmCreatedOrStarted':False,'sourcePackageUnchanged':m.sha(package)==proof['sha256']}
    report['helperSourceSha256']=m.sha(helper_source)
    report['helperVersionTokenReplacement']={'from':'1112','to':str(args.sequence),'vmCreationEntryIncluded':legacy}
    transfer=package.parent.parent/('TRANSFER_'+str(args.sequence)+'.json')
    assert not transfer.exists(), 'NEW_TRANSFER_RECEIPT_REQUIRED'
    transfer.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'zip':str(archive),'sha256':report['sha256'],'bytes':report['bytes'],'identitySha256':identity_hash}))


if __name__=='__main__':
    assert os.name=='nt' and __debug__
    main()
