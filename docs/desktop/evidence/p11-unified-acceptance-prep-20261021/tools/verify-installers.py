from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as ET,pefile
scope=Path(__file__).resolve().parent;repo=scope.parents[3];stage=scope/'01-installers'
frozen=repo/'app/desktopApp/build/p11-review-fixes-20261021/windows-frozen-70b5c30c'
rows={r['path']:r for r in json.loads((frozen/'package-manifest.json').read_text())}
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
exe=stage/'exe/ChatChatBarDesktop-0.1.0.exe'
pe=pefile.PE(str(exe),fast_load=True);pe.parse_data_directories(directories=[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_RESOURCE']])
data=next(n for t in pe.DIRECTORY_ENTRY_RESOURCE.entries for n in t.directory.entries if str(n.name)=='MSI').directory.entries[0].data.struct
offset,size=pe.get_offset_from_rva(data.OffsetToData),data.Size;pe.close()
embedded=stage/'embedded-from-final-exe.msi'
with exe.open('rb') as src,embedded.open('xb') as dst:
 src.seek(offset);left=size
 while left:
  b=src.read(min(left,1048576));assert b;dst.write(b);left-=len(b)
assert sha(embedded)==sha(stage/'exe-work/images/win-exe.image/ChatChatBarDesktop-0.1.0.msi')
with (stage/'exe-extract.log').open('xb') as log:
 r=subprocess.run([str(repo/'app/build/wix311/dark.exe'),'-nologo','-x',str(stage/'exe-extracted'),str(embedded),str(stage/'exe.wxs')],stdout=log,stderr=subprocess.STDOUT)
assert r.returncode==0
n={'w':'http://schemas.microsoft.com/wix/2006/wi'}
proof=[]
for kind in ['msi','exe']:
 tree=ET.parse(stage/(kind+'.wxs')); product=tree.getroot().find('w:Product',n)
 install=tree.find('.//w:Directory[@Id="INSTALLDIR"]',n);seen={}
 def walk(node,path=''):
  for component in node.findall('w:Component',n):
   for file in component.findall('w:File',n):
    name=path+file.attrib['Name'];p=Path(file.attrib['Source']);p=p if p.is_absolute() else repo/p
    seen[name]=dict(size=p.stat().st_size,sha256=sha(p))
  for child in node.findall('w:Directory',n):walk(child,path+child.attrib['Name']+'/')
 walk(install)
 omitted=set(rows)-set(seen);extra=set(seen)-set(rows)
 assert omitted=={'app/.jpackage.xml'} and not extra,(omitted,extra)
 for name,record in seen.items():assert record['size']==rows[name]['size'] and record['sha256']==rows[name]['sha256'],name
 proof.append(dict(format=kind,installedPayloadFiles=len(seen),allPayloadBytesMatchPortable=True,omittedPackagingMetadata=sorted(omitted),extraFiles=[],product=product.attrib,payload=seen))
out=dict(production='70b5c30c91439e79728bf224c1a44d35b25cf733',version='0.1.0-preview.1',installerNumericVersion='0.1.0',portableFiles=217,embeddedMsiSize=size,embeddedMsiSha256=sha(embedded),installerVerification=proof,installed=False,guiStarted=False,limitations=['WiX dark emitted UI table decompilation warnings; payload proof is independent of UI decompilation.', 'Static payload verification does not validate install/upgrade/uninstall behavior.'])
(stage/'payload-verification.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(formats=2,payloadFilesPerInstaller=216,contentMatches=True,packagingMetadataExcluded=['app/.jpackage.xml'])))
