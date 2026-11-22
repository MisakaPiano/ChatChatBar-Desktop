#!/usr/bin/env bash
set -euo pipefail

lab="$RUNNER_TEMP/ccb-v7-scan"
serial=emulator-5554
adb -s "$serial" install "$lab/candidate86.apk"

scan_status=0
python -B tools/lan-next-control/v7_scan_camera_control.py \
  --adb adb --serial "$serial" --expected-api 36 --expect invalid \
  --result "$lab/camera-result.json" || scan_status=$?
if (( scan_status != 0 )); then
  CCB_V7_CAMERA_SYNTHETIC_DIAGNOSTIC=true timeout 75s \
    python -B tools/lan-next-control/v7_camera_preview_diagnostic.py \
      --adb adb --serial "$serial" --result "$lab/camera-preview-result.json" || true
fi
exit "$scan_status"
