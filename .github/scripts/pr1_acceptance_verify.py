import hashlib,json,pathlib,re,shutil,subprocess,sys,xml.etree.ElementTree as ET,zipfile

source,apk,output=map(pathlib.Path,sys.argv[1:])
identity=json.loads((output/'identity-overlay.json').read_text(encoding='utf-8'))
manifest=ET.fromstring((output/'final-manifest.xml').read_text(encoding='utf-8'))
ns='{http://schemas.android.com/apk/res/android}'
assert manifest.get('package')==identity['appId']
assert manifest.get(ns+'sharedUserId') is None
assert manifest.get(ns+'versionName')==identity['versionName']
assert int(manifest.get(ns+'versionCode'))==identity['versionCode']
sdk=manifest.find('uses-sdk')
assert sdk.get(ns+'minSdkVersion')=='26'
assert sdk.get(ns+'targetSdkVersion')=='36'
app=manifest.find('application')
assert app.get(ns+'debuggable')=='true'
assert app.get(ns+'label')==identity['label']
assert app.get(ns+'name')=='com.example.chatbar.ChatBarApp'
providers=[x.get(ns+'authorities') for x in app.findall('provider')]
assert identity['appId']+'.fileprovider' in providers
assert all(all(a.startswith(identity['appId']+'.') for a in value.split(';')) for value in providers)
assert not any(x=='com.example.chatbar.fileprovider' for x in providers)
assert any(x.get(ns+'name')=='com.example.chatbar.MainActivity' for x in app.findall('activity'))
schemes=[x.get(ns+'scheme') for x in app.findall('activity/intent-filter/data') if x.get(ns+'scheme')]
assert schemes==['chatbar-naipr1-e4d7997']
badging=(output/'badging.txt').read_text(encoding='utf-8')
assert "package: name='"+identity['appId']+"'" in badging
assert "launchable-activity: name='com.example.chatbar.MainActivity'" in badging
signature=(output/'signature.txt').read_text(encoding='utf-8')
assert 'CN=Android Debug' in signature
assert re.search(r'Verified using v[23].*: true',signature)
changed=subprocess.check_output(['git','diff','--name-only'],cwd=source,text=True).splitlines()
assert changed==['app/app/src/main/AndroidManifest.xml'],changed
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    abi=sorted({p.split('/')[1] for p in z.namelist() if p.startswith('lib/') and p.endswith('.so')})
    assert 'arm64-v8a' in abi
name='CCB-NAI-PR1-e4d7997-isolated-debug.apk'
dest=output/name
shutil.copy2(apk,dest)
record=dict(state='SIGNED_APK_IDENTITY_AND_INSTALLABILITY_METADATA_PASS',source=identity['source'],
    file=name,bytes=dest.stat().st_size,sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),
    appId=identity['appId'],label=identity['label'],versionName=identity['versionName'],versionCode=identity['versionCode'],
    minSdk=26,targetSdk=36,abis=abi,debuggable=True,providers=providers,callbackSchemes=schemes,
    launcher='com.example.chatbar.MainActivity',sharedUserId=None,
    dataIsolation=dict(androidPrivateRoot='/data/user/0/'+identity['appId'],entities='files/entities',images='files/images/generated',
        preferences='App-private preferences, separate package UID',credentials='App-private preferences and UID-scoped Android Keystore',
        officialAppId='com.example.chatbar',officialDataAccessed=False),
    signatureCertificateSha256=re.search(r'Signer #1 certificate SHA-256 digest: (.+)',signature).group(1),
    actualPhoneInstall='NOT_TESTED_USER_SELF_INSTALL',functionalProofReused=identity['functionalTestsReused'],
    testProviderRequests=False,releasePublished=False,privateSigningKeyUsed=False,
    trackedSourceChanges=changed,identityOnlyManifestSemantics=True)
(output/'APK_RECEIPT.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
print(json.dumps(record))
