#!/usr/bin/env bash
set -euo pipefail
trap 'mkdir -p batch-ux-evidence/device-ui; adb pull /sdcard/Download/ccb-safe-batch-review/. batch-ux-evidence/device-ui || true' EXIT
./app/gradlew -p app :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.ui.imageprompt.SafeBatchStudioRuntimeTest,com.example.chatbar.ui.components.ImageBatchOptionsEditorTest,com.example.chatbar.ui.components.SessionImageRegenerationCostTest,com.example.chatbar.ui.components.NovelAiImageRegenerationDialogTest --no-daemon --stacktrace
