"""Thin fixture selection over the existing exact-source Windows component runner."""
from pathlib import Path
import desktop_group_ui_control as control

SOURCE = "c1a59880b42e4d6097801d4d641dde9fdaa2a8e9"
original = control.read_request
def exact(path):
    request = original(path)
    control.require(request["sourceSha"] == SOURCE, "V7_REVERSE_SOURCE")
    return request
control.read_request = exact
control.CLASS = "com.example.chatbar.desktop.lanSync.DesktopV7ReverseSyntheticHostDraftTest"
control.METHOD = "productionLanReceivesActualAndroidReverseMessageAndPng"
control.TARGET = "app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/lanSync/DesktopV7ReverseSyntheticHostDraftTest.kt"
control.FIXTURE = Path(__file__).with_name("DesktopV7ReverseSyntheticHostDraftTest.kt")
if __name__ == "__main__":raise SystemExit(control.main())
