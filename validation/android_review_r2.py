import pathlib,subprocess,json,shutil,sys,hashlib,os,re,zipfile
from xml.etree import ElementTree as ET
root=pathlib.Path.cwd();out=root/'android-review-r2';out.mkdir(exist_ok=True)
SOURCE='45ee52fa96db426c4daf4d2469ff85d613b67ef7'
APP_ID='com.example.chatbar.nextreview.r2'
CLASSES=pathlib.Path('/tmp/android_r2_native_classes.txt').read_text().strip()

def gradle(*args):
    return subprocess.run(['./gradlew',*args,'--no-daemon','--stacktrace'],cwd=root/'app')

def report(label,native=False):
    src=root/('app/app/build/outputs/androidTest-results/connected' if native else 'app/app/build/test-results/testDebugUnitTest')
    dst=out/label
    if src.exists():shutil.copytree(src,dst)
    cases=[]
    for p in dst.rglob('TEST-*.xml'):
        for c in ET.parse(p).iter('testcase'):
            e=c.find('failure');e=e if e is not None else c.find('error')
            cases.append(dict(name=c.get('name'),classname=c.get('classname'),skipped=c.find('skipped') is not None,
                failure=None if e is None else dict(type=e.get('type'),message=e.get('message'),text=e.text)))
    (out/(label+'.json')).write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    return cases

def metadata(label,app_id):
    apk=root/'app/app/build/outputs/apk/debug/app-debug.apk'
    signer=sorted(pathlib.Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))[-1]
    (out/(label+'-signature.txt')).write_text(subprocess.check_output([str(signer),'verify','--verbose','--print-certs',str(apk)],text=True))
    badging=subprocess.check_output([str(signer.with_name('aapt')),'dump','badging',str(apk)],text=True)
    assert "package: name='"+app_id+"'" in badging
    (out/(label+'-badging.txt')).write_text(badging)
    with zipfile.ZipFile(apk) as z:
        assert all(b'SafeBatchOfflineActivity' not in z.read(n) for n in z.namelist() if n.endswith('.dex'))
    d=dict(source=SOURCE,applicationId=app_id,bytes=apk.stat().st_size,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
        signatureVerified=True,humanAcceptance=False,realAccountCalls=False)
    (out/(label+'.json')).write_text(json.dumps(d,ensure_ascii=False,indent=2));return d

phase=sys.argv[1]
if phase=='prepare':
    assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==SOURCE
    (root/'app/gradlew').chmod(0o755)
    (out/'source.json').write_text(json.dumps(dict(source=SOURCE,baseline='9f30c105c5c8dd8dbcbab372d8d37e55e540ebaa',
        includes=dict(pr7='3132f4658eb33cea3eee02ca432f1fbc526086ae',ui='8d8e8f6dc612582b55f0aa74e6b0a8ac6c1a2fc1',
            data='0858292d431a23d5639b21e1403b13f664ec2213',action='3856ea28e4048aeb6af8fa1aa49ce8f12be206a9',format='9c4856f4883ad83df2d555eefd5b465bad7bef41'),
        syntheticOnly=True,realAccountCalls=False,humanAcceptance=False)))
    (out/'native-classes.txt').write_text(CLASSES)
elif phase=='jvm-standard':
    r=gradle(':app:testDebugUnitTest',':app:compileDebugAndroidTestKotlin',':app:assembleDebug')
    cases=report('jvm-standard')
    allowed={('com.example.chatbar.domain.backup.BackupLargeStreamTest','encryptedGiBRoundTripPreservesRawBytesWithConstantBuffers')}
    skips={(c['classname'],c['name']) for c in cases if c['skipped']}
    assert r.returncode==0 and len(cases)>=1500 and all(not c['failure'] for c in cases) and skips<=allowed
    metadata('standard-apk','com.example.chatbar')
elif phase=='prepare-review':
    p=root/'app/app/build.gradle.kts';s=p.read_text();assert s.count('applicationId = "com.example.chatbar"')==1
    p.write_text(s.replace('applicationId = "com.example.chatbar"','applicationId = "'+APP_ID+'"'))
    p=root/'app/app/src/main/res/values/strings.xml';s=p.read_text();assert '<string name="app_name">' in s
    p.write_text(re.sub(r'(<string name="app_name">)[^<]*(</string>)',r'\g<1>CCB 集中验收 r2\2',s,count=1))
    (out/'review-package-projection.patch').write_bytes(subprocess.check_output(['git','diff']))
    assert gradle(':app:assembleDebug',':app:compileDebugAndroidTestKotlin').returncode==0
    metadata('review-apk-before-native',APP_ID)
elif phase.startswith('native-'):
    r=gradle(':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+CLASSES)
    cases=report(phase,True)
    subprocess.run(['adb','pull','/sdcard/Android/data/'+APP_ID+'/files/ui-previews',str(out/(phase+'-screenshots'))],check=False)
    assert r.returncode==0 and len(cases)==111 and len({c['classname'] for c in cases})==24
    assert all(not c['failure'] and not c['skipped'] for c in cases)
elif phase=='package':
    d=metadata('review-apk',APP_ID)
    target=root/'review-apk/CCB-consolidated-review-r2.apk';target.parent.mkdir(exist_ok=True)
    shutil.copy2(root/'app/app/build/outputs/apk/debug/app-debug.apk',target)
    (out/'delivery.json').write_text(json.dumps(dict(apk=target.name,metadata=d,notPublishedRelease=True,humanAcceptance=False)))
else:raise ValueError(phase)
