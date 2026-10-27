"""Freeze the existing1113 MSI and a one-shot1112->1113 guest helper. No install."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import msi_safety as m


def local(path):
    path=path.absolute()
    for p in [path,*path.parents]:
        if p.exists():assert not p.stat(follow_symlinks=False).st_file_attributes&0x400,'REPARSE_REFUSED'
    return path.resolve()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('pair-root','a-delivery','b-image','output'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args();repo=Path(__file__).resolve().parents[3]
    pair=local(args.pair_root);a_delivery=local(args.a_delivery);image=local(args.b_image);out=local(args.output)
    assert pair.is_relative_to(repo/'app/desktopApp/build')
    assert out.drive.upper()=='I:' and out.name=='CCB-OP1-UPGRADE-DL1-20261027-r01'
    assert not out.exists() and not out.with_suffix('.zip').exists()
    expected=('5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79',
              'ab80cf68ba82c42af2ea4a61c8ccdca4d30b79f74db6ae12925ae59e892ebf8e')
    a=local(pair/'A/ChatChatBarDesktopSynthetic-0.1.1112.msi')
    b=local(pair/'B/ChatChatBarDesktopSynthetic-0.1.1113.msi')
    assert (m.sha(a),m.sha(b))==expected,'FIXED_PACKAGE_PAIR_REQUIRED'
    old_identity=local(a_delivery/'PACKAGE_IDENTITY.json')
    assert m.sha(old_identity)=='d6f1701fd16130c7a371c5b2eae2999de077f84f2cf4d8e04c609b35487c8f19'
    old=json.loads(old_identity.read_text());assert old['sha256']==expected[0]
    prior=local(pair/'PAIR_METADATA_VERIFIED.json');prior_data=json.loads(prior.read_text())
    assert prior_data['A']['sha256']==expected[0] and prior_data['B']['sha256']==expected[1] and prior_data['sharedGuidResourceLayoutVerified']
    with m.Database(a) as db:m.verify(db,'synthetic')
    with m.Database(b) as db:
        props=m.verify(db,'synthetic');assert props['ProductVersion']=='0.1.1113'
        graph=m.operation_resource_graph(m.operation_tables(db),props)
        rows={r[0]:r for r in db.rows('File',8)};files=[]
        for key,name in graph['files'].items():
            path=local(image/Path(name.replace('\\','/')))
            assert path.is_relative_to(image) and path.is_file() and path.stat().st_size==int(rows[key][3])
            files.append({'path':name,'bytes':path.stat().st_size,'sha256':m.sha(path)})
        binary=dict(db.sql('SELECT `Name`,`Data` FROM `Binary`',2,streams=True))
        assert set(binary)=={'CcbDirectoryGuard'} and binary['CcbDirectoryGuard']['sha256']==old['guardSha256']
        identity={'productName':props['ProductName'],'version':props['ProductVersion'],'productCode':props['ProductCode'],
            'upgradeCode':props['UpgradeCode'],'packageCode':db.package_code(),'fileName':b.name,'sha256':m.sha(b),'bytes':b.stat().st_size,
            'operationContract':1,'guardSha256':old['guardSha256'],'files':files,
            'components':[{'id':r[0],'guid':r[1]} for r in db.rows('Component',6)],
            'registry':[{'key':r[2],'name':r[3],'value':r[4]} for r in db.rows('Registry',6)]}
    assert len(files)==216 and len(identity['components'])==218 and len(identity['registry'])==220
    common=local(a_delivery/'Invoke-Operation1FreshGuest.ps1')
    assert m.sha(common)==m.sha(Path(__file__).with_name(common.name)),'ISSUED_COMMON_HELPER_CHANGED'
    out.mkdir();shutil.copyfile(b,out/b.name);shutil.copyfile(old_identity,out/'A_IDENTITY.json')
    shutil.copyfile(common,out/common.name)
    helper=Path(__file__).with_name('Invoke-Operation1UpgradeGuest.ps1');shutil.copyfile(helper,out/helper.name)
    (out/'B_IDENTITY.json').write_text(json.dumps(identity,indent=2)+'\n',encoding='utf-8')
    hashes={name:m.sha(out/name) for name in ('A_IDENTITY.json','B_IDENTITY.json',common.name,helper.name)}
    wrapper='''param([switch]$ConfirmIsolatedGuest)
$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'Invoke-Operation1UpgradeGuest.ps1'
if((Get-FileHash -LiteralPath $helper -Algorithm SHA256).Hash.ToLowerInvariant() -ne '__HELPER__'){throw 'FIXED_UPGRADE_HELPER_HASH_MISMATCH'}
& $helper -ExpectedAIdentitySha256 '__A__' -ExpectedBIdentitySha256 '__B__' -ExpectedCommonHelperSha256 '__COMMON__' -ConfirmIsolatedGuest:$ConfirmIsolatedGuest
'''
    for key,value in (('__HELPER__',hashes[helper.name]),('__A__',hashes['A_IDENTITY.json']),('__B__',hashes['B_IDENTITY.json']),('__COMMON__',hashes[common.name])):wrapper=wrapper.replace(key,value)
    (out/'Run-Upgrade-1112-1113.ps1').write_text(wrapper,encoding='ascii')
    provenance=json.loads((pair/'B/SOURCE.json').read_text())
    (out/'CLASS_PROVENANCE.json').write_text(json.dumps(provenance,indent=2)+'\n')
    (out/'READ_ME_FIRST.txt').write_text('''INTERNAL UNSIGNED SYNTHETIC UPGRADE. NOT A RELEASE. DO NOT RUN BEFORE1112 SUCCESS.
Use only the same independent Win10Pro19045 guest/user that completed full1112.
Do not run on DD1,DL1,originalVM,or old1102 failure copy. No VM manipulation here.
Close the synthetic application normally. Preserve first-install results.
Copy this directory into that guest. Run ONCE only after the1112 result is accepted:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\\Run-Upgrade-1112-1113.ps1 -ConfirmIsolatedGuest

The helper requires successful same-user first-install receipt and current exact
A registration/files/components/Registry. It creates a NEW invented nonpackage
canary in the already-verified synthetic program directory, then invokes full B
quiet install once, preserving the existing selected install directory.
Afterwards it checks B files/components/Registry, A native registration absence,
old-only version Registry removal, same directory, preserved canary, unchanged
B source MSI, and both U parent/C old-child rollback-enabled admission markers.
Any error: preserve all files,registration,logs; no retry,cleanup or repair.
Results: %LOCALAPPDATA%\\CCB-OP1-Upgrade-1112-1113-r01.

This does not test custom first install,repair,missing directory,uninstall,in-use
files,actual failure rollback,full Job/descendant drain or data/credential migration.
It does not open an app,call providers,read secrets,alter policy,reboot,or clean.
''',encoding='ascii')
    entries={f.name:{'bytes':f.stat().st_size,'sha256':m.sha(f)} for f in out.iterdir()}
    archive=out.with_suffix('.zip')
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_STORED) as z:
        for f in sorted(out.iterdir()):z.write(f,arcname=out.name+'/'+f.name)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for i in z.infolist():assert hashlib.sha256(z.read(i)).hexdigest()==entries[Path(i.filename).name]['sha256']
    assert (m.sha(a),m.sha(b))==expected
    report={'zip':str(archive),'bytes':archive.stat().st_size,'sha256':m.sha(archive),'entries':entries,
        'sourceCommitBeforeNewTools':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),
        'toolGitBlobs':{f.name:subprocess.check_output(['git','hash-object','--path='+f.relative_to(repo).as_posix(),str(f)],cwd=repo,text=True).strip() for f in (Path(__file__).resolve(),helper)},
        'runtimeClassProvenance':provenance,'priorPairEvidenceSha256':m.sha(prior),'installerExecuted':False,'sourcePackagesUnchanged':True,
        'actualUpgrade':'NOT_TESTED','requiresSuccessfulPhysical1112FirstInstall':True}
    report_path=pair/'TRANSFER_UPGRADE_1112_1113.json'
    with report_path.open('x',encoding='utf-8') as f:json.dump(report,f,indent=2);f.write('\n')
    print(json.dumps({'zip':str(archive),'bytes':report['bytes'],'sha256':report['sha256'],'actualUpgrade':'NOT_TESTED'}))


if __name__=='__main__':
    assert os.name=='nt' and __debug__
    main()
