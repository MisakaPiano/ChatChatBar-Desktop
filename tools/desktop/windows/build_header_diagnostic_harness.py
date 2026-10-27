"""Test-only old/new API fault shims. Never installs, opens a session or packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import build_directory_guard as pe

PREFIX=r'''
#define UNICODE
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#define _WIN32_MSI 500
#include <windows.h>
#include <msi.h>
#include <msiquery.h>
#include <bcrypt.h>
static UINT fault_api,fault_occurrence,fault_value,fault_mode,counts[32],events[65536],event_count;
static int hit(UINT api,UINT field){
 counts[api]++;if(event_count<65536)events[event_count++]=(api<<16)|field;
 return api==fault_api&&counts[api]==fault_occurrence;
}
#define UWRAP(id,name,params,args,field) static UINT H_##name params {if(hit(id,field))return fault_value;return name args;}
UWRAP(1,MsiDatabaseOpenViewW,(MSIHANDLE a,LPCWSTR b,MSIHANDLE*c),(a,b,c),0)
UWRAP(2,MsiViewExecute,(MSIHANDLE a,MSIHANDLE b),(a,b),0)
UWRAP(3,MsiViewFetch,(MSIHANDLE a,MSIHANDLE*b),(a,b),0)
UWRAP(4,MsiRecordGetStringW,(MSIHANDLE a,UINT b,LPWSTR c,DWORD*d),(a,b,c,d),b)
UWRAP(5,MsiRecordSetStringW,(MSIHANDLE a,UINT b,LPCWSTR c),(a,b,c),b)
UWRAP(6,MsiGetSummaryInformationW,(MSIHANDLE a,LPCWSTR b,UINT c,MSIHANDLE*d),(a,b,c,d),0)
static UINT H_MsiSummaryInfoGetPropertyW(MSIHANDLE a,UINT b,UINT*c,INT*d,FILETIME*e,LPWSTR f,DWORD*g){
 int selected=hit(7,b);UINT result;
 if(selected&&!fault_mode)return fault_value;
 result=MsiSummaryInfoGetPropertyW(a,b,c,d,e,f,g);
 if(selected&&fault_mode==1&&result==0)*c=fault_value;
 if(selected&&fault_mode==2&&result==0){f[0]=L'!';f[1]=0;}
 return result;
}
UWRAP(8,MsiRecordReadStream,(MSIHANDLE a,UINT b,char*c,DWORD*d),(a,b,c,d),b)
UWRAP(9,MsiViewGetColumnInfo,(MSIHANDLE a,MSICOLINFO b,MSIHANDLE*c),(a,b,c),(UINT)b)
static int H_MsiRecordGetInteger(MSIHANDLE a,UINT b){if(hit(10,b))return (int)fault_value;return MsiRecordGetInteger(a,b);}
static MSIHANDLE H_MsiCreateRecord(UINT a){if(hit(11,a))return 0;return MsiCreateRecord(a);}
static MSICONDITION H_MsiDatabaseIsTablePersistentW(MSIHANDLE a,LPCWSTR b){if(hit(12,0))return (MSICONDITION)fault_value;return MsiDatabaseIsTablePersistentW(a,b);}
static NTSTATUS H_BCryptOpenAlgorithmProvider(BCRYPT_ALG_HANDLE*a,LPCWSTR b,LPCWSTR c,ULONG d){if(hit(13,0))return (NTSTATUS)fault_value;return BCryptOpenAlgorithmProvider(a,b,c,d);}
static NTSTATUS H_BCryptCreateHash(BCRYPT_ALG_HANDLE a,BCRYPT_HASH_HANDLE*b,PUCHAR c,ULONG d,PUCHAR e,ULONG f,ULONG g){if(hit(14,0))return (NTSTATUS)fault_value;return BCryptCreateHash(a,b,c,d,e,f,g);}
static NTSTATUS H_BCryptHashData(BCRYPT_HASH_HANDLE a,PUCHAR b,ULONG c,ULONG d){if(hit(15,0))return (NTSTATUS)fault_value;return BCryptHashData(a,b,c,d);}
static NTSTATUS H_BCryptFinishHash(BCRYPT_HASH_HANDLE a,PUCHAR b,ULONG c,ULONG d){if(hit(16,0))return (NTSTATUS)fault_value;return BCryptFinishHash(a,b,c,d);}
__declspec(dllexport) void __stdcall HarnessConfigure(UINT api,UINT occurrence,UINT value,UINT mode){
 fault_api=api;fault_occurrence=occurrence;fault_value=value;fault_mode=mode;event_count=0;ZeroMemory(counts,sizeof(counts));
}
__declspec(dllexport) UINT __stdcall HarnessTrace(UINT index){return index==65536?event_count:(index<event_count?events[index]:0);}
#define CCB_GUARD_TEST 1
#include "observed_directory_guard.c"
'''

APIS=('MsiDatabaseOpenViewW','MsiViewExecute','MsiViewFetch','MsiRecordGetStringW','MsiRecordSetStringW',
      'MsiGetSummaryInformationW','MsiSummaryInfoGetPropertyW','MsiRecordReadStream','MsiViewGetColumnInfo',
      'MsiRecordGetInteger','MsiCreateRecord','MsiDatabaseIsTablePersistentW','BCryptOpenAlgorithmProvider',
      'BCryptCreateHash','BCryptHashData','BCryptFinishHash')


def shim(source):
    # Preserve string literals (including API names logged by the new code).
    token=re.compile(r'''//[^\n]*|/\*[\s\S]*?\*/|(?:L)?"(?:\\.|[^"\\])*"|(?:L)?'(?:\\.|[^'\\])*'|\b(?:'''+ '|'.join(APIS)+r')\b')
    return token.sub(lambda x:'H_'+x[0] if x[0] in APIS else x[0],source)


def build(out,source,baseline):
    out.mkdir();identities=[]
    for name in ('directory_guard.c','operation_guard.h'):
        data=(source/name).read_bytes();(out/name).write_bytes(data)
        identities.append({'file':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
        text=shim(data.decode('utf-8'))
        if name=='directory_guard.c':text=text.replace('#include "operation_guard.h"','#include "observed_operation_guard.h"')
        (out/('observed_'+name)).write_text(text,encoding='utf-8')
    body='''
typedef void (__stdcall *HarnessSink)(const wchar_t *);
__declspec(dllexport) int __stdcall HarnessRun(MSIHANDLE db,const wchar_t*p,const wchar_t*n,const wchar_t*u,const wchar_t*v,HarnessSink sink){
 __BODY__
}
'''.replace('__BODY__','(void)sink;return package_header(db,p,n,u,v);' if baseline else 'return CcbTestPackageHeader(db,p,n,u,v,sink);')
    (out/'harness.c').write_text(PREFIX+body,encoding='utf-8')
    vs=Path(os.environ.get('ProgramFiles(x86)','C:/Program Files (x86)'))/'Microsoft Visual Studio/Installer/vswhere.exe'
    installation=subprocess.check_output([str(vs),'-latest','-products','*','-requires','Microsoft.VisualStudio.Component.VC.Tools.x86.x64','-property','installationPath'],text=True).strip()
    setup=Path(installation)/'VC/Auxiliary/Build/vcvars64.bat';assert setup.is_file()
    args=['cl.exe','/nologo','/W4','/WX','/O2','/MT','/LD','/DWINVER=0x0601','/D_WIN32_WINNT=0x0601',str(out/'harness.c'),'/link','/OUT:'+str(out/'header_harness.dll'),'msi.lib','advapi32.lib','shell32.lib','ole32.lib','uuid.lib','bcrypt.lib']
    for arg in [str(setup),*args]:assert not any(c in arg for c in '&|<>^%!\r\n')
    command='cmd.exe /d /s /c "call "'+str(setup)+'" >nul && '+subprocess.list2cmdline(args)+'"'
    (out/'compile-command.json').write_text(json.dumps(command)+'\n',encoding='utf-8')
    with (out/'compile.log').open('wb') as f:r=subprocess.run(command,cwd=out,stdout=f,stderr=subprocess.STDOUT,timeout=120)
    (out/'compile.exit').write_text(str(r.returncode)+'\n');assert r.returncode==0,'TEST_HARNESS_COMPILE_FAILED'
    dll=out/'header_harness.dll';exports=pe.pe_exports(dll)
    assert {'HarnessRun','HarnessConfigure','HarnessTrace'}<=exports
    (out/'HARNESS_IDENTITY.json').write_text(json.dumps({'inputs':identities,'baseline':baseline,'dllSha256':hashlib.sha256(dll.read_bytes()).hexdigest(),
        'dllBytes':dll.stat().st_size,'testOnly':True,'apiRedirect':'Identifier tokens only, outside string literals; fixed named wrappers call native read APIs except explicit injected result.',
        'exports':sorted(exports),'installerExecuted':False},indent=2)+'\n')


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--baseline-source',type=Path,required=True);args=p.parse_args()
    repo=Path(__file__).resolve().parents[3];out=args.output.resolve();old=args.baseline_source.resolve()
    assert out.is_relative_to(repo/'app/desktopApp/build') and old.is_relative_to(repo/'app/desktopApp/build') and not out.exists()
    for name,digest in [('directory_guard.c','6f4977b5200e999b87814c2d145699a776c1e3a7c5a96e41f0067e0cc930b0cc'),('operation_guard.h','790e8ff5494e33aa55583fc641cfa8f969f1cfaf6b2fd23ec36711953893cd79')]:
        assert hashlib.sha256((old/name).read_bytes()).hexdigest()==digest,'FIXED_1112_BASELINE_REQUIRED'
    out.mkdir();build(out/'baseline',old,True);build(out/'current',Path(__file__).resolve().parent,False)
    print('BASELINE_CURRENT_TEST_SHIMS_COMPILED_NO_INSTALLER_EXECUTION')


if __name__=='__main__':
    if not __debug__ or os.name!='nt':raise SystemExit('WINDOWS_VALIDATION_REQUIRED')
    main()
