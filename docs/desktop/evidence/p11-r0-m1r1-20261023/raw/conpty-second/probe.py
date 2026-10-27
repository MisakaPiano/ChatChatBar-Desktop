"""Windows-only automated PTY test. Only the fixed owned synthetic archive and public dummy password."""
import ctypes as C
from ctypes import wintypes as W
import json, os, re, subprocess, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / 'docs/desktop/evidence/p11-r0-m1r1-20261023/raw/conpty'
FILE = ROOT / 'app/desktopApp/build/readonly-r0/m1r1/SYNTHETIC-code84-encrypted.cbbackup'
PASSWORD = 'FAKE_G2B_PASSWORD_NOT_REAL'  # Public synthetic fixture value, never a user password.

class Coord(C.Structure):
    _fields_ = [('X', C.c_short), ('Y', C.c_short)]
class SI(C.Structure):
    _fields_ = [('cb',W.DWORD),('reserved',W.LPWSTR),('desktop',W.LPWSTR),('title',W.LPWSTR),
                ('x',W.DWORD),('y',W.DWORD),('xs',W.DWORD),('ys',W.DWORD),('xc',W.DWORD),('yc',W.DWORD),
                ('fill',W.DWORD),('flags',W.DWORD),('show',W.WORD),('cb2',W.WORD),('res2',C.c_void_p),
                ('stdin',W.HANDLE),('stdout',W.HANDLE),('stderr',W.HANDLE)]
class SIX(C.Structure):
    _fields_ = [('si',SI),('attrs',C.c_void_p)]
class PI(C.Structure):
    _fields_ = [('process',W.HANDLE),('thread',W.HANDLE),('pid',W.DWORD),('tid',W.DWORD)]

k = C.WinDLL('kernel32', use_last_error=True)
def bind(name,args,ret=W.BOOL):
    fn=getattr(k,name);fn.argtypes=args;fn.restype=ret;return fn
pipe=bind('CreatePipe',[C.POINTER(W.HANDLE),C.POINTER(W.HANDLE),C.c_void_p,W.DWORD])
pty=bind('CreatePseudoConsole',[Coord,W.HANDLE,W.HANDLE,W.DWORD,C.POINTER(W.HANDLE)],C.c_long)
closepty=bind('ClosePseudoConsole',[W.HANDLE],None)
close=bind('CloseHandle',[W.HANDLE])
init=bind('InitializeProcThreadAttributeList',[C.c_void_p,W.DWORD,W.DWORD,C.POINTER(C.c_size_t)])
update=bind('UpdateProcThreadAttribute',[C.c_void_p,W.DWORD,C.c_size_t,C.c_void_p,C.c_size_t,C.c_void_p,C.c_void_p])
delete=bind('DeleteProcThreadAttributeList',[C.c_void_p],None)
create=bind('CreateProcessW',[W.LPCWSTR,W.LPWSTR,C.c_void_p,C.c_void_p,W.BOOL,W.DWORD,C.c_void_p,W.LPCWSTR,C.POINTER(SIX),C.POINTER(PI)])
peek=bind('PeekNamedPipe',[W.HANDLE,C.c_void_p,W.DWORD,C.c_void_p,C.POINTER(W.DWORD),C.c_void_p])
read=bind('ReadFile',[W.HANDLE,C.c_void_p,W.DWORD,C.POINTER(W.DWORD),C.c_void_p])
write=bind('WriteFile',[W.HANDLE,C.c_void_p,W.DWORD,C.POINTER(W.DWORD),C.c_void_p])
wait=bind('WaitForSingleObject',[W.HANDLE,W.DWORD],W.DWORD)
exitcode=bind('GetExitCodeProcess',[W.HANDLE,C.POINTER(W.DWORD)])
terminate=bind('TerminateProcess',[W.HANDLE,W.UINT])

def check(ok):
    if not ok: raise OSError(C.get_last_error(), 'SYNTHETIC_CONPTY_API_FAILURE')

def run(label,password,expected):
    ir,iw,orr,ow,hpc=(W.HANDLE() for _ in range(5))
    pi=PI();attrs=None;created=False;capture=bytearray();sent=False;timed=False
    try:
        check(pipe(C.byref(ir),C.byref(iw),None,0));check(pipe(C.byref(orr),C.byref(ow),None,0))
        hr=pty(Coord(140,40),ir,ow,0,C.byref(hpc));check(hr==0)
        close(ir);ir=W.HANDLE();close(ow);ow=W.HANDLE()
        size=C.c_size_t();init(None,1,0,C.byref(size));attrs=C.create_string_buffer(size.value)
        check(init(attrs,1,0,C.byref(size)))
        check(update(attrs,0,0x20016,hpc,C.sizeof(W.HANDLE),None,None))
        si=SIX();si.si.cb=C.sizeof(SIX);si.attrs=C.cast(attrs,C.c_void_p)
        cp=(ROOT/'app/desktopApp/build/readonly-r0/runtime-classpath.txt').read_text().strip()
        args=[str(Path(os.environ['JAVA_HOME'])/'bin/java.exe'),'-Xmx768m','-XX:+DisableAttachMechanism',
              '-XX:-HeapDumpOnOutOfMemoryError','-Djava.awt.headless=true','-cp',cp,
              'com.example.chatbar.desktop.backup.RealArchiveReadOnlyTool','--single-file-read-only',str(FILE)]
        check(create(None,C.create_unicode_buffer('cmd.exe /d /c '+subprocess.list2cmdline(args)),None,None,False,0x80000,None,str(ROOT),C.byref(si),C.byref(pi)))
        created=True;deadline=time.monotonic()+45
        while True:
            available=W.DWORD()
            if peek(orr,None,0,None,C.byref(available),None) and available.value:
                buf=C.create_string_buffer(available.value);n=W.DWORD()
                check(read(orr,buf,available.value,C.byref(n),None));capture.extend(buf.raw[:n.value])
            if not sent and b'Archive password (hidden):' in capture:
                data=(password+'\r').encode();n=W.DWORD();check(write(iw,data,len(data),C.byref(n),None));sent=True
            if wait(pi.process,0)==0:
                # Drain the final output before closing our own PTY.
                time.sleep(.15)
                available=W.DWORD()
                if peek(orr,None,0,None,C.byref(available),None) and available.value:
                    buf=C.create_string_buffer(available.value);n=W.DWORD();check(read(orr,buf,available.value,C.byref(n),None));capture.extend(buf.raw[:n.value])
                break
            if time.monotonic()>deadline: timed=True;terminate(pi.process,99);break
            time.sleep(.02)
        code=W.DWORD();check(exitcode(pi.process,C.byref(code)))
        text=capture.decode('utf-8',errors='replace');clean=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text)
        echo=password in clean
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/(label+'.txt')).write_text(clean,encoding='utf-8')
        result={'terminal':'AUTOMATED_WINDOWS_CONPTY','humanTerminal':'NOT_TESTED','promptObserved':sent,
                'passwordEchoObserved':echo,'exit':code.value,'expectedExit':expected,'timeout':timed}
        (OUT/(label+'.json')).write_text(json.dumps(result,indent=2))
        assert sent and not echo and not timed and code.value==expected
        if expected==0: assert 'S0=FORMAT_READABLE' in clean and 'SOURCE_BYTES=UNCHANGED' in clean
        else: assert 'S0=REJECTED_AUTHENTICATION' in clean
        return result
    finally:
        if created and wait(pi.process,0)!=0:terminate(pi.process,99)
        if hpc:closepty(hpc)
        if attrs:delete(attrs)
        for h in [ir,iw,orr,ow,pi.thread,pi.process]:
            if h:close(h)

if __name__=='__main__':
    assert os.name=='nt' and FILE.is_file()
    for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:assert not os.environ.get(name)
    print(json.dumps([run('correct',PASSWORD,0),run('wrong','FAKE_M1_WRONG_PASSWORD',2)]))
