import ctypes as C
from ctypes import wintypes as W
from pathlib import Path
import sys,json,collections,platform
repo=Path(r'H:\ChatChatBar-Desktop');sys.path.insert(0,str(repo/'tools/desktop/windows'))
import msi_safety
r=Path(__file__).resolve().parent
source=repo/'app/desktopApp/build/preview-continuous-private-20261025/component-contract-real-msi-20261026-r01/A/ChatChatBarDesktopSynthetic-0.1.1102.msi'
CB=C.WINFUNCTYPE(W.UINT,W.LPCWSTR,W.DWORD,C.c_void_p,C.POINTER(W.DWORD));EN=C.WINFUNCTYPE(W.UINT,W.DWORD,C.c_void_p,C.POINTER(W.DWORD))
lib=C.WinDLL('msi');lib.MsiEnumComponentsExW.argtypes=[W.LPCWSTR,W.DWORD,W.DWORD,W.LPWSTR,C.POINTER(W.DWORD),W.LPWSTR,C.POINTER(W.DWORD)];lib.MsiEnumComponentsExW.restype=W.UINT
counter=collections.Counter();calls=[]
def enum(index,code,context):
 rc=lib.MsiEnumComponentsExW(None,7,index,C.cast(code,W.LPWSTR),context,None,None);counter[rc]+=1;calls.append(index);return rc
inventory=EN(enum);missing=CB(lambda *_:2)
old_dll=repo/'app/desktopApp/build/preview-continuous-private-20261025/component-contract-20261026-r01/green-01/test/directory_guard.dll'
old=C.WinDLL(str(old_dll)).CcbTestComponentContract;old.argtypes=[W.UINT,C.c_int,W.LPCWSTR,CB];old.restype=C.c_int
new=C.WinDLL(str(r/'green-01/test/directory_guard.dll')).CcbTestComponentContractWithProof;new.argtypes=[W.UINT,C.c_int,W.LPCWSTR,CB,EN];new.restype=C.c_int
before=msi_safety.sha(source)
with msi_safety.Database(source) as db:
 baseline=old(db.handle,0,'',missing)
 candidate=new(db.handle,0,'',missing,inventory)
assert baseline==0
report={'os':platform.win32_ver(),'rawClientStatusInjected':2,'baselineReject':baseline==0,'candidateAcceptWithActualNativeInventory':candidate==1,'nativeInventoryStatusCounts':dict(counter),'nativeInventoryCallCount':len(calls),'inventoryReusedAcross218Components':len(calls)==len(set(calls)),'sourceMsiUnchanged':before==msi_safety.sha(source),'installerExecuted':False,'notWindows10Evidence':True}
(r/'DD1_NATIVE_INVENTORY_ADAPTER.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));assert candidate==1
