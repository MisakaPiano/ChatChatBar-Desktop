# Tracked API35 cloud service proof

`cloud_foreground_control.py` selects the committed
`LanSyncGroupCloudForegroundTest` method. It reuses the existing guarded fresh
emulator/SDK/runner/raw-report/single-method verifier. It creates no source
overlay: both preparation and execution require a clean exact source and matching
tracked test bytes.

The control checkout must fetch full history so the fixed test path can be read
with `git show SOURCE:path`. The request contains only `format`, `sourceSha` and
the SHA-256 of those exact committed test bytes. Class, method, API35 and the
instrumentation argument are selected by the trusted entry point.

The separate workflow builds a fresh preview83 test application from the selected
source; this is not a signed public Android84 candidate or a real phone upgrade.
The one method checks actual dataSync foreground promotion, HOME continuation and
refusal of the next background batch using synthetic work. It does not prove real
cloud delivery, quota exhaustion, notification-stop races, Doze or process death.

Preserve every original stdout/stderr report and its source/control/test hashes.
Only an exact executed method with zero skips counts as this bounded native
proof. Other pending fixes and tests retain their own requirements and evidence.
