"""Compile the synthetic-only read-only deferred fault probe; never authors/runs MSI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from build_directory_guard import pe_exports

if not __debug__:
    raise RuntimeError('SYNTHETIC_FAULT_VALIDATION_REQUIRED')


def reuse(directory,expected_hash):
    source=Path(__file__).with_name('synthetic_write_fault.c').resolve();root=source.parents[3]/'app/desktopApp/build';directory=Path(directory).resolve()
    assert directory.is_relative_to(root),'OWNED_SYNTHETIC_PROBE_REQUIRED'
    for path in [directory,*directory.parents]:assert not path.stat(follow_symlinks=False).st_file_attributes&0x400
    identity=json.loads((directory/'IDENTITY.json').read_text());dll=directory/'synthetic_write_fault.dll'
    assert identity['kind']=='SYNTHETIC_ONLY_READONLY_FAILURE_PROBE' and identity['testExports'] is False,'TEST_PROBE_CANNOT_BE_PACKAGED'
    assert (directory/source.name).read_bytes()==source.read_bytes() and identity['sourceSha256']==hashlib.sha256(source.read_bytes()).hexdigest(),'SYNTHETIC_PROBE_SOURCE_CHANGED'
    assert not dll.stat(follow_symlinks=False).st_file_attributes&0x400 and dll.stat().st_size==identity['bytes']
    assert hashlib.sha256(dll.read_bytes()).hexdigest()==identity['sha256']==expected_hash,'SYNTHETIC_PROBE_HASH_CHANGED'
    assert pe_exports(dll)=={'CcbSyntheticFailAfterWrites'},'SYNTHETIC_PROBE_EXPORT_CHANGED'
    return dll


def build(output,test=False):
    source=Path(__file__).with_name('synthetic_write_fault.c').resolve();repo=source.parents[3];out=Path(output).resolve()
    assert out.is_relative_to(repo/'app/desktopApp/build') and not out.exists()
    for p in [out,*out.parents]:
        if p.exists():assert not p.stat(follow_symlinks=False).st_file_attributes&0x400
    vs=Path(os.environ.get('ProgramFiles(x86)','C:/Program Files (x86)'))/'Microsoft Visual Studio/Installer/vswhere.exe'
    installed=subprocess.check_output([str(vs),'-latest','-products','*','-requires','Microsoft.VisualStudio.Component.VC.Tools.x86.x64','-property','installationPath'],text=True).strip()
    setup=Path(installed)/'VC/Auxiliary/Build/vcvars64.bat';assert installed and setup.is_file()
    out.mkdir(parents=True);(out/source.name).write_bytes(source.read_bytes())
    args=['cl.exe','/nologo','/W4','/WX','/O2','/MT','/LD','/DWINVER=0x0601','/D_WIN32_WINNT=0x0601']
    if test:args+=['/DCCB_FAULT_TEST=1']
    args+=[str(out/source.name),'/link','/OUT:'+str(out/'synthetic_write_fault.dll'),'msi.lib','advapi32.lib','bcrypt.lib']
    assert not any(any(c in value for c in '&|<>^%!\r\n') for value in [str(setup),*args])
    invocation='cmd.exe /d /s /c "call "'+str(setup)+'" >nul && '+subprocess.list2cmdline(args)+'"'
    (out/'COMMAND.json').write_text(json.dumps(invocation))
    with (out/'compile.log').open('xb') as log:r=subprocess.run(invocation,cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    (out/'compile.exit').write_text(str(r.returncode));assert r.returncode==0,'SYNTHETIC_FAULT_COMPILE_FAILED'
    dll=out/'synthetic_write_fault.dll';exports=pe_exports(dll)
    expected={'CcbSyntheticFailAfterWrites'}
    if test:expected|={'CcbTestSyntheticWriteEvidence','CcbTestSyntheticFileHash'}
    assert exports==expected
    (out/'IDENTITY.json').write_text(json.dumps({'kind':'SYNTHETIC_ONLY_READONLY_FAILURE_PROBE','sha256':hashlib.sha256(dll.read_bytes()).hexdigest(),'bytes':dll.stat().st_size,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'testExports':test,'exports':sorted(exports),'msiAuthoredOrExecuted':False},indent=2))
    return dll


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--test',action='store_true');a=p.parse_args()
    assert __debug__ and os.name=='nt';print(build(a.output,a.test))
