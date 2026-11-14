#!/usr/bin/env python3
"""Fixed API35 companion using the same strict fresh-emulator control guards."""
from pathlib import Path

import group_ui_control as control


def main(argv: list[str] | None = None) -> int:
    # The trusted entry point selects the profile; request JSON cannot select a path or API.
    profile = {
        "CLASS": "com.example.chatbar.lanSync.LanSyncGroupBackgroundUiControlTest",
        "METHOD": "backgroundServicePauseSurvivesForegroundAndResumesExplicitly",
        "TARGET": "app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncGroupBackgroundUiControlTest.kt",
        "FIXTURE": Path(__file__).resolve().parent / "fixtures/LanSyncGroupBackgroundUiControlTest.kt",
        "API": 35,
        "INSTRUMENTATION_ARGUMENT": "lanSyncGroupBackgroundFixture",
        "PROOF_SCOPE": "actual API35 connected-device foreground service, explicit pause across Activity lifecycle and explicit resume with local publication; not OS death/Doze, physical peers or cloud",
    }
    previous = {key: getattr(control, key) for key in profile}
    try:
        for key, value in profile.items():
            setattr(control, key, value)
        return control.main(argv)
    finally:
        for key, value in previous.items():
            setattr(control, key, value)


if __name__ == "__main__":
    raise SystemExit(main())
