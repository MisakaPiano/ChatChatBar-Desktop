"""Cloud-only targeted synthetic UI run; preserve screenshots even when assertions fail."""
from pathlib import Path
import subprocess, sys, json
root = Path(__file__).resolve().parents[1]
result = subprocess.run(["./gradlew", ":app:connectedDebugAndroidTest",
    "-Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.ui.chat.SafeBatchChatRuntimeTest",
    "--no-daemon", "--stacktrace"], cwd=root / "app")
target = root / "safe-batch-evidence/device-ui"
target.mkdir(parents=True, exist_ok=True)
pull = subprocess.run(["adb", "pull", "/sdcard/Download/ccb-safe-batch-review/.", str(target)], timeout=60)
(root / "safe-batch-evidence/device-run-custody.json").write_text(json.dumps({"gradleExit": result.returncode, "pullExit": pull.returncode,
    "screenshots": sorted(p.name for p in target.iterdir()), "syntheticOnly": True}), encoding="utf-8")
sys.exit(result.returncode or pull.returncode)
