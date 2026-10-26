import ctypes as C
from ctypes import wintypes as W
import sys,json,uuid,subprocess,os
from pathlib import Path
repo=Path(r'H:\ChatChatBar-Desktop');sys.path.insert(0,str(repo/'tools/desktop/windows'))
import msi_safety
out=Path(__file__).resolve().parent; target=out/'readonly-server-probe.msi';assert not target.exists()
source=repo/'app/desktopApp/build/preview-continuous-private-20261025/component-contract-real-msi-20261026-r01/A/ChatChatBarDesktopSynthetic-0.1.1102.msi'
guard=out.parent/'green-01/production/directory_guard.dll'
assert msi_safety.sha(source)=='af3fbb1d5f652d15d45a5072c93bf5461b47669301e217df0d5ff39cf67d424d'
assert msi_safety.sha(guard)=='38bc34ac1ef15b1d85e37b6ede1ab3fcaa4f2b81059899d8e69bf158ee657f71'
lib=C.WinDLL('msi');lib.MsiOpenDatabaseW.argtypes=[W.LPCWSTR,W.LPCWSTR,C.POINTER(W.UINT)]
h=W.UINT();assert lib.MsiOpenDatabaseW(str(target),C.cast(C.c_void_p(3),W.LPCWSTR),C.byref(h))==0
assert lib.MsiDatabaseCommit(h)==0;lib.MsiCloseHandle(h)
columns={
 'Property':(('Property','Value'),('CHAR(72) NOT NULL','CHAR(0)')),
 'Component':(('Component','ComponentId','Directory_','Attributes','Condition','KeyPath'),('CHAR(72) NOT NULL','CHAR(38)','CHAR(72) NOT NULL','SHORT NOT NULL','CHAR(255)','CHAR(72)')),
 'Registry':(('Registry','Root','Key','Name','Value','Component_'),('CHAR(72) NOT NULL','SHORT NOT NULL','CHAR(255) NOT NULL','CHAR(255)','CHAR(0)','CHAR(72) NOT NULL')),
 'Directory':(('Directory','Directory_Parent','DefaultDir'),('CHAR(72) NOT NULL','CHAR(72)','CHAR(255) NOT NULL')),
}
with msi_safety.Database(source) as src,msi_safety.Database(target,writable=True) as db:
 for table,(names,types) in columns.items():
  db.sql('CREATE TABLE `'+table+'` ('+', '.join('`'+n+'` '+t for n,t in zip(names,types))+' PRIMARY KEY `'+names[0]+'`)')
  for row in src.rows(table,len(names)):
   if table=='Property' and row[0] not in ('ProductName','ProductCode','ProductVersion','Manufacturer','ProductLanguage','UpgradeCode','CCB_COMPONENT_CONTRACT','CCB_DIRECTORY_CONTRACT'):continue
   values=[str(int(v)) if n in ('Root','Attributes') else "'"+v.replace("'","''")+"'" for n,v in zip(names,row)]
   db.sql('INSERT INTO `'+table+'` ('+','.join('`'+n+'`' for n in names)+') VALUES ('+','.join(values)+')')
 db.sql('CREATE TABLE `Binary` (`Name` CHAR(72) NOT NULL, `Data` OBJECT NOT NULL PRIMARY KEY `Name`)')
 db.sql('CREATE TABLE `CustomAction` (`Action` CHAR(72) NOT NULL, `Type` SHORT NOT NULL, `Source` CHAR(72), `Target` CHAR(255) PRIMARY KEY `Action`)')
 db.sql("INSERT INTO `CustomAction` (`Action`,`Type`,`Source`,`Target`) VALUES ('CcbResolveInstallDirectory',1,'CcbDirectoryGuard','CcbResolveInstallDirectory')")
 db.sql('CREATE TABLE `InstallExecuteSequence` (`Action` CHAR(72) NOT NULL, `Condition` CHAR(255), `Sequence` SHORT PRIMARY KEY `Action`)')
 db.sql("INSERT INTO `InstallExecuteSequence` (`Action`,`Condition`,`Sequence`) VALUES ('CcbResolveInstallDirectory','',60)")
 lib.MsiRecordSetStreamW.argtypes=[W.UINT,W.UINT,W.LPCWSTR]
 v=W.UINT();assert lib.MsiDatabaseOpenViewW(db.handle,"INSERT INTO `Binary` (`Name`,`Data`) VALUES ('CcbDirectoryGuard',?)",C.byref(v))==0
 record=lib.MsiCreateRecord(1);assert record
 assert lib.MsiRecordSetStreamW(record,1,str(guard))==0
 assert lib.MsiViewExecute(v,record)==0
 lib.MsiCloseHandle(record);lib.MsiCloseHandle(v)
 lib.MsiGetSummaryInformationW.argtypes=[W.UINT,W.LPCWSTR,W.UINT,C.POINTER(W.UINT)]
 lib.MsiSummaryInfoSetPropertyW.argtypes=[W.UINT,W.UINT,W.UINT,C.c_int,C.c_void_p,W.LPCWSTR]
 summary=W.UINT();assert lib.MsiGetSummaryInformationW(db.handle,None,7,C.byref(summary))==0
 for pid,val in ((2,'Read-only guard server probe'),(3,'No file or registration actions'),(7,'x64;1033'),(9,'{'+str(uuid.uuid4()).upper()+'}')):
  assert lib.MsiSummaryInfoSetPropertyW(summary,pid,30,0,None,val)==0
 for pid,val in ((14,200),(15,2)):
  assert lib.MsiSummaryInfoSetPropertyW(summary,pid,3,val,None,None)==0
 assert lib.MsiSummaryInfoPersist(summary)==0;lib.MsiCloseHandle(summary)
 assert lib.MsiDatabaseCommit(db.handle)==0
with msi_safety.Database(target) as db:
 assert db.rows('InstallExecuteSequence',3)==[['CcbResolveInstallDirectory','','60']]
 assert db.rows('CustomAction',4)==[['CcbResolveInstallDirectory','1','CcbDirectoryGuard','CcbResolveInstallDirectory']]
 assert not {'File','Media','Feature','FeatureComponents','InstallUISequence','ServiceInstall'}.intersection(x[0] for x in db.rows('_Tables',1))
command=[str(Path(os.environ['SystemRoot'])/'System32/msiexec.exe'),'/i',str(target),'/qn','/norestart','/l*v',str(out/'readonly-server-probe.log'),'INSTALLDIR='+str(out/'never-created-server-program'),'REBOOT=ReallySuppress']
(out/'server-command.json').write_text(json.dumps(command,indent=2)+'\n')
result=subprocess.run(command,timeout=90)
report={'exit':result.returncode,'mode':'MICRO_MSI_SINGLE_READONLY_ACTION','installInitializePresent':False,'fileActionsPresent':False,'registryActionsPresent':False,'programDirectoryCreated':(out/'never-created-server-program').exists(),'sourceMsiUnchanged':msi_safety.sha(source)=='af3fbb1d5f652d15d45a5072c93bf5461b47669301e217df0d5ff39cf67d424d'}
(out/'SERVER_PROBE.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
