"""Bind the V7 Windows live-pair fixture to one cloud component invocation."""
from pathlib import Path
import desktop_group_ui_control as control

SOURCE = "f5fc060546eb9479b2566375761903d383c538b1"
CLASS = "com.example.chatbar.desktop.lanSync.DesktopV7LiveSyntheticHostDraftTest"
METHOD = "liveProductionLanInvitesActualAndroidAndRetainsSyntheticMessageAndImage"
TARGET = "app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/lanSync/DesktopV7LiveSyntheticHostDraftTest.kt"

def main():
    original_read = control.read_request
    def exact(path):
        request = original_read(path)
        control.require(request["sourceSha"] == SOURCE, "V7_LIVE_PAIR_SOURCE")
        return request
    control.CLASS = CLASS
    control.METHOD = METHOD
    control.TARGET = TARGET
    control.FIXTURE = Path(__file__).resolve().parent / "fixtures/DesktopV7LiveSyntheticHostDraftTest.kt"
    control.read_request = exact
    return control.main()

if __name__ == "__main__":
    raise SystemExit(main())
