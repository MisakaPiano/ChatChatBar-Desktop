#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
test -n "${CCB_GROUP_PLATFORM_SOURCE:-}"
source_root="$CCB_GROUP_PLATFORM_SOURCE"
evidence="$GITHUB_WORKSPACE/lan-sync-group-native-evidence"
mkdir -p "$evidence"
adb devices -l > "$evidence/device-inventory.txt"
serial="$(python3 -c 'import subprocess,re; rows=[x.split() for x in subprocess.check_output(["adb","devices"],text=True).splitlines()[1:] if x.strip()]; assert len(rows)==1 and rows[0][1]=="device" and re.fullmatch(r"emulator-[0-9]+",rows[0][0]); print(rows[0][0])')"
app="$(python3 -c 'import glob,os; p=glob.glob(os.environ["CCB_GROUP_PLATFORM_SOURCE"]+"/app/app/build/outputs/apk/lanPreview/*.apk"); assert len(p)==1; print(p[0])')"
test_apk="$(python3 -c 'import glob,os; p=glob.glob(os.environ["CCB_GROUP_PLATFORM_SOURCE"]+"/app/app/build/outputs/apk/androidTest/lanPreview/*.apk"); assert len(p)==1; print(p[0])')"
adb -s "$serial" shell pm list packages com.example.chatbar.lansync > "$evidence/package-before.txt"
adb -s "$serial" install -r "$app"
adb -s "$serial" install -r "$test_apk"
export CCB_GROUP_PROOF_SERIAL="$serial"
runner="$(python3 -c 'import subprocess,re,os; s=subprocess.check_output(["adb","-s",os.environ["CCB_GROUP_PROOF_SERIAL"],"shell","pm","list","instrumentation"],text=True); r=re.findall(r"instrumentation:([^ ]+) \(target=com\.example\.chatbar\.lansync\)",s); assert len(r)==1; print(r[0])')"
if [[ "${CCB_GROUP_PLATFORM_PROOF_MODE:-group-custody}" == 'large-images' ]]; then
  git -C "$source_root" rev-parse HEAD > "$evidence/source-sha.txt"
  adb -s "$serial" shell am instrument -w -r -e lanSyncLargeImageFixture true -e class 'com.example.chatbar.lanSync.LanSyncLargeImageTest#fullyWrittenLargePngOriginalsSurviveNativeValidationTransferReopenAndPreview' "$runner" > "$evidence/large-image.txt"
  python3 -c 'import pathlib,re,sys; s=pathlib.Path(sys.argv[1]).read_text(); assert "OK (1 test)" in s and "FAILURES!!!" not in s and "INSTRUMENTATION_CODE: -1" in s; assert re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)",s)==["1","0"]; assert s.count("INSTRUMENTATION_STATUS: class=com.example.chatbar.lanSync.LanSyncLargeImageTest")==2 and s.count("INSTRUMENTATION_STATUS: test=fullyWrittenLargePngOriginalsSurviveNativeValidationTransferReopenAndPreview")==2 and s.count("INSTRUMENTATION_STATUS: numtests=1")==2' "$evidence/large-image.txt"
  python3 -c 'import pathlib,json,sys; p=pathlib.Path(sys.argv[1]); (p/"summary.json").write_text(json.dumps(dict(sourceSha=(p/"source-sha.txt").read_text().strip(),api=26,proofMode="large-images",nativeTests=1,encodedPngMebibytes=[33,101],decodedPixels=[16,12],fullOriginalHashes=True,workspaceCaptureApplyReopen=True,typedEnhanceMetadataRetained=True,boundedFrozenPreview=True,artifacts="reports only; no image payloads, app roots, keys or APK"),indent=2))' "$evidence"
  exit 0
fi
test "${CCB_GROUP_PLATFORM_PROOF_MODE:-group-custody}" = group-custody
class_name=com.example.chatbar.lanSync.LanSyncGroupKeystoreProcessTest
adb -s "$serial" shell am instrument -w -r -e lanSyncGroupNativeFixture true -e class "$class_name#seedApprovedSourceIntent" "$runner" > "$evidence/seed.txt"
python3 -c 'import pathlib,sys; s=pathlib.Path(sys.argv[1]).read_text(); assert "OK (1 test)" in s and "GROUP_NATIVE_KEYSTORE_SEED_PASS" in s and "ROOT_CREATION_AND_LEDGER_SEED_PASS" in s and "MANAGEMENT_VAULT_SEED_PASS" in s and "FAILURES!!!" not in s' "$evidence/seed.txt"
adb -s "$serial" shell am force-stop com.example.chatbar.lansync
adb -s "$serial" shell am instrument -w -r -e lanSyncGroupNativeFixture true -e class "$class_name#reopenInAnotherProcessAndRecoverOnlyTheHistoricalReceipt" "$runner" > "$evidence/reopen.txt"
python3 -c 'import pathlib,sys; s=pathlib.Path(sys.argv[1]).read_text(); assert "OK (1 test)" in s and "GROUP_NATIVE_KEYSTORE_REOPEN_PASS" in s and "ROOT_CREATION_AND_LEDGER_REOPEN_PASS" in s and "MANAGEMENT_VAULT_REOPEN_PASS" in s and "FAILURES!!!" not in s' "$evidence/reopen.txt"
git -C "$source_root" rev-parse HEAD > "$evidence/source-sha.txt"
python3 -c 'import pathlib,json,sys; p=pathlib.Path(sys.argv[1]); (p/"summary.json").write_text(json.dumps(dict(sourceSha=(p/"source-sha.txt").read_text().strip(),api=26,seedTests=1,reopenTests=1,distinctProcesses=True,realAndroidKeystore=True,rootCreationAndLedgerReopened=True,independentRecoveryCodeAdminReopened=True,adminLockedBeforeDailyAuthoring=True,sourceAndAuthorJournalReopened=True,nativeSourceReceiptRecovered=True,laterNativeEditPreserved=True,artifacts="reports only; no private roots, keys or APK"),indent=2))' "$evidence"
