"""Read-only metadata verification of the exact internal candidate MSI; no install APIs."""
from pathlib import Path
import hashlib,importlib.util,json,sys,zipfile
repo=Path('H:/ChatChatBar-Desktop');case=repo/'app/desktopApp/build/preview-continuous-private-20261025/windows-msi-candidate-1010-owned-root';build=repo/'app/desktopApp/build/preview-win-msi-candidate-1010';image=Path('I:/CCB-Desktop-Preview-Private-Candidate-1010-20261025/compose/binaries/main/app/ChatChatBarDesktop')
source=repo/'tools/desktop/windows/msi_safety.py';spec=importlib.util.spec_from_file_location('msi_check',source);tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
msi=build/'compose/binaries/main/msi/ChatChatBarDesktop-0.1.1010.msi';receipt=json.loads((case/'SAFETY_RECEIPT.json').read_text());assert tool.sha(msi)==receipt['outputSha256']
with tool.Database(msi) as db:
    properties=tool.verify(db,'desktop');assert properties['ProductVersion']=='0.1.1010'
    dirs=db.rows('Directory',3);assert any(r[0]=='INSTALLDIR' and r[1]=='LocalAppDataFolder' and r[2].split('|')[-1]=='ChatChatBarDesktopApp' for r in dirs)
    shortcuts=db.rows('Shortcut',12);assert shortcuts
    assert db.cabinets()==receipt['applicationCabinets']
    result={'profile':'desktop','productName':properties['ProductName'],'productVersion':properties['ProductVersion'],'upgradeCode':properties['UpgradeCode'],'perUserOnly':True,'programRootOutsideUserData':True,'shortcutCount':len(shortcuts),'recursiveCleanupRemoved':True,'downgradeBlocked':True,'removalInsideTransaction':True,'syntheticFailureActionAbsent':True,'cabinetIdentityMatchesReceipt':True,'signingPerformed':False,'installerExecuted':False,'ordinaryUpgradeRollback':'NOT_TESTED','syntheticRegistrationRollback':'FAIL_RETAINED_SEPARATE_RUN'}
main=build/'libs/desktopApp.jar';candidates=list((image/'app').glob('desktopApp-*.jar'));assert len(candidates)==1
h1=tool.sha(main);h2=tool.sha(candidates[0]);result['desktopMainJarByteIdenticalToNativeTestedImage']=h1==h2
with zipfile.ZipFile(main) as jar:
    props=dict(line.split('=',1) for line in jar.read('desktop-build.properties').decode().splitlines());assert props['commit']=='946c3791153634b0af65286313a497d141d5c598' and props['packagingProfile']=='desktop' and props['version']=='0.1.0-preview.1010'
result['buildIdentity']=props;result['msiBuildMainJarSha256']=h1;result['nativeImageMainJarSha256']=h2
(case/'MSI_STATIC_METADATA.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');(case/'Inspect-Candidate-Msi-1010.py').write_bytes(Path(__file__).read_bytes());print('CANDIDATE_MSI_READONLY_METADATA_PASS byteIdenticalMainJar='+str(h1==h2))