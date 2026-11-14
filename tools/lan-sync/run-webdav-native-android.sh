#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
test -n "${CCB_WEBDAV_PLATFORM_SOURCE:-}"
source_root="$CCB_WEBDAV_PLATFORM_SOURCE"
evidence="$GITHUB_WORKSPACE/webdav-native-api26-evidence"
test ! -e "$evidence"
mkdir -p "$evidence"
adb devices -l > "$evidence/device-inventory.txt"
serial="$(python3 -c 'import subprocess,re; rows=[x.split() for x in subprocess.check_output(["adb","devices"],text=True).splitlines()[1:] if x.strip()]; assert len(rows)==1 and rows[0][1]=="device" and re.fullmatch(r"emulator-[0-9]+",rows[0][0]); print(rows[0][0])')"
test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 26
app="$(python3 -c 'import glob,os; p=glob.glob(os.environ["CCB_WEBDAV_PLATFORM_SOURCE"]+"/app/app/build/outputs/apk/lanPreview/*.apk"); assert len(p)==1; print(p[0])')"
test_apk="$(python3 -c 'import glob,os; p=glob.glob(os.environ["CCB_WEBDAV_PLATFORM_SOURCE"]+"/app/app/build/outputs/apk/androidTest/lanPreview/*.apk"); assert len(p)==1; print(p[0])')"
adb -s "$serial" shell pm list packages com.example.chatbar.lansync > "$evidence/package-before.txt"
python3 -c 'import pathlib,re,sys; s=pathlib.Path(sys.argv[1]).read_text(); assert not re.search(r"^package:com[.]example[.]chatbar[.]lansync(?:[.]test)?\s*$",s,re.M)' "$evidence/package-before.txt"
adb -s "$serial" install -r "$app" > "$evidence/install-app.txt"
adb -s "$serial" install -r "$test_apk" > "$evidence/install-tests.txt"
export CCB_WEBDAV_PROOF_SERIAL="$serial"
runner="$(python3 -c 'import subprocess,re,os; s=subprocess.check_output(["adb","-s",os.environ["CCB_WEBDAV_PROOF_SERIAL"],"shell","pm","list","instrumentation"],text=True); r=re.findall(r"instrumentation:([^ ]+) \(target=com\.example\.chatbar\.lansync\)",s); assert len(r)==1; print(r[0])')"
class_name=com.example.chatbar.lanSync.LanSyncWebDavPlatformTest
method_name=productionSaxRejectsDtdAndParsesSegmentedDavOnApi26
adb -s "$serial" shell am instrument -w -r -e lanSyncWebDavPlatformProbe true -e class "$class_name#$method_name" "$runner" > "$evidence/instrumentation.txt"
git -C "$source_root" rev-parse HEAD > "$evidence/source-sha.txt"
python3 - "$evidence" <<'PY'
import json,pathlib,re,sys
p=pathlib.Path(sys.argv[1]); text=(p/'instrumentation.txt').read_text(encoding='utf8')
assert 'OK (1 test)' in text
assert not any(value in text for value in ['FAILURES!!!','INSTRUMENTATION_FAILED','INSTRUMENTATION_ABORTED','INSTRUMENTATION_RESULT: shortMsg='])
statuses=[int(value) for value in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$',text,re.M)]
assert statuses==[1,2,0],statuses
assert re.search(r'^INSTRUMENTATION_CODE: -1\s*$',text,re.M)
assert 'INSTRUMENTATION_STATUS: test=productionSaxRejectsDtdAndParsesSegmentedDavOnApi26' in text
assert 'INSTRUMENTATION_STATUS: class=com.example.chatbar.lanSync.LanSyncWebDavPlatformTest' in text
markers=re.findall(r'^INSTRUMENTATION_STATUS: stream=WEBDAV_API26_SAX_CONTRACT_PASS (\{[^\n]+\})\s*$',text,re.M)
assert len(markers)==1
facts=json.loads(markers[0])
assert facts['api']==26 and facts['productionPositiveCalls']==2
assert facts['inputBudgetBytes']==65536
assert facts['productionDtdIntegrityRejections']==4 and facts['nativeStartDtdCallbacks']==4 and facts['nativeResolverCallbacks']==0
assert facts['nativeStartDtdInvokesProductionHandler'] is True
assert facts['externalGeneralEntities'] is False and facts['externalParameterEntities'] is False
assert facts['lexicalHandlerInstalled'] is True and facts['productionSegmentedUtf8'] is True and facts['productionNetworkDeathGuard'] is True
assert facts['webDavHttpsTransportTested'] is False and facts['realAccountTested'] is False and facts['largeTransferTested'] is False
assert facts['factoryClass'] and facts['readerClass']
source=(p/'source-sha.txt').read_text().strip(); assert re.fullmatch(r'[0-9a-f]{40}',source)
for name in ['install-app.txt','install-tests.txt']: assert re.search(r'^Success\s*$',(p/name).read_text(),re.M)
summary=dict(sourceSha=source,api=26,testClass='com.example.chatbar.lanSync.LanSyncWebDavPlatformTest',testMethod='productionSaxRejectsDtdAndParsesSegmentedDavOnApi26',executed=1,passed=1,skipped=0,instrumentationStatusCodes=statuses,productionSaxViaReflection=True,parserFacts=facts,distinctProcessesRequired=False,webDavHttpsTransportTested=False,realAccountTested=False,largeTransferTested=False,artifacts='original text/summary/source SHA only; no APK, app data or keys')
(p/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
PY
