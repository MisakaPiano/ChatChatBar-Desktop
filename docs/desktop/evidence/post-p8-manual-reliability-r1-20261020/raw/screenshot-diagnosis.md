# Screenshot Close — UNKNOWN / NO PRODUCTION REPAIR

Observed user evidence remains unchanged: user identifies internal screenshot Close; raw packaged log enters Main CLOSE_REQUESTED and full drain, exit0/0. No exact AWT/Win32 Press/Release/WM_CLOSE source was captured.

Source chain at base694a8bdd:
- DesktopChatScreenshotHost: Popup onDismiss, Escape and StudioAction Close clear only messages/png/error state. No global close callback or exitApplication reference.
- Popup owns Compose focus/content, whereas Main owns Window.onCloseRequest; app also installs rememberDesktopWindowChrome on that actual ComposeWindow.
- DesktopWindowChrome.command(CLOSE) invokes app closeRequest; requestNativeClose dispatches on EDT.
- WindowsWindowChrome handles its owned HWND WM_CLOSE/SC_CLOSE and caption hit release, then requestNativeClose. Its heavyweight child hit-test hooks allow non-client hits to parent. A file picker is only entered by Save PNG; no export file exists in the manual gate.
- Main requestClose records CLOSE_REQUESTED, consults rootSwitchController.requestWindowClose and calls exitApplication. Existing application/container/ownership lifecycle drains afterward.

Thus both a native/window-close path and a pure screenshot-state-close path exist. Logs do not identify which native route was triggered by the reported physical click. Popup size/caption hit/owner/focus are candidate boundaries only. Source plausibility, offscreen semantic click or switching to Dialog cannot prove the historical root cause.

Decision under user contract: stop screenshot production repair, keep UNKNOWN; do not block ordinary Window Close, swallow global events, add delays, alter Main/window Chrome or implement the future standalone work window. Added offscreen Close regression checks only local composition persistence, plus existing root-switch/application lifecycle tests. These are NOT true Windows popup Close/owner/root-lock/Main-X verification. That packaged native matrix remains BLOCKED pending separate evidence/review.

No actual new Windows GUI, raw input injection or Robot run. The new independent-window/scroll/zoom/tree contract is preserved separately and not implemented.
