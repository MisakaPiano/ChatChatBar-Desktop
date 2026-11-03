import copy, hashlib, json, pathlib, subprocess, sys, xml.etree.ElementTree as ET

source, init, output = map(pathlib.Path, sys.argv[1:])
expected = 'e4d7997c05f77e31b8650935830acc583c112f89'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=source,text=True).strip()==expected
assert not subprocess.check_output(['git','status','--porcelain'],cwd=source,text=True).strip()
manifest=source/'app/app/src/main/AndroidManifest.xml'
original=manifest.read_bytes()
root=ET.fromstring(original)
before=copy.deepcopy(root)
ns='{http://schemas.android.com/apk/res/android}'
app=root.find('application')
assert app.get(ns+'label')=='@string/app_name'
app.set(ns+'label','CCB NAI PR1 Test')
callbacks=[x for x in app.findall('activity/intent-filter/data') if x.get(ns+'scheme')=='chatbar']
assert len(callbacks)==1
callbacks[0].set(ns+'scheme','chatbar-naipr1-e4d7997')
before.find('application').set(ns+'label','CCB NAI PR1 Test')
for x in before.findall('application/activity/intent-filter/data'):
    if x.get(ns+'scheme')=='chatbar': x.set(ns+'scheme','chatbar-naipr1-e4d7997')
assert ET.tostring(root)==ET.tostring(before)
ET.register_namespace('android','http://schemas.android.com/apk/res/android')
manifest.write_bytes(ET.tostring(root,encoding='utf-8',xml_declaration=True))
init.write_text('''gradle.beforeProject { project ->
    project.pluginManager.withPlugin('com.android.application') {
        project.extensions.getByName('androidComponents').finalizeDsl { android ->
            android.buildTypes.getByName('debug').applicationIdSuffix = '.naipr1.e4d7997'
            android.buildTypes.getByName('debug').versionNameSuffix = '-test'
        }
    }
}
''',encoding='utf-8')
output.mkdir(parents=True,exist_ok=True)
record=dict(source=expected,appId='com.example.chatbar.naipr1.e4d7997',label='CCB NAI PR1 Test',
    versionName='1.4.7-nai-pr1-e4d7997-test',versionCode=86,namespace='com.example.chatbar',
    originalManifestSha256=hashlib.sha256(original).hexdigest(),overlayManifestSha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),
    initSha256=hashlib.sha256(init.read_bytes()).hexdigest(),
    onlyManifestSemanticChanges=['application label','OAuth URI scheme'],
    onlyBuildIdentityChanges=['debug applicationIdSuffix','debug versionNameSuffix','versionName property','OAuth redirect property'],
    functionalTestsReused=dict(run=41632722508,tests=313,failed=0,skipped=0),
    signing='Generated generic Android debug keystore; no private user release key',
    installedOnDevice=False,realProviderCalled=False,publicReleaseCreated=False)
(output/'identity-overlay.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
print(json.dumps(record))
