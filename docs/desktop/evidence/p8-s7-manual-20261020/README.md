# P8 S7 manual gate — pre-GUI startup HOLD

2026-10-20 JST. User explicitly authorized W1–W5. Fixed source 008343bb26d2eb76c23cae9b98132af828a9f447; initial evidence HEAD 23b6e6b1422f693140936700bbd00d0bd6b761de.

PASS: local/tracking/live Git identity, unchanged source, complete 212-file package, four support members, eleven synthetic JSON members and DEFAULT bootstrap verified against committed manifests before starting. Four user-owned files remain untracked/unread/unmodified/unstaged.

TECHNICAL FAIL: first and only fake start attempt exited 1 before readiness. P8S7Support.mock calls Files.createDirectory(mock-service), while offline preparation pre-created that empty directory. FileAlreadyExistsException is preserved verbatim in mock.stderr.txt. This is a proven fixture preparation defect; no CCB launcher/window/watcher was started. It is not evidence of a product playback or UI defect.

W1/W2/W3/W4/W5: NOT TESTED. No user operation or listening PASS is signed. Real Fish GET0/TTS0; no paid interface or real credentials used. Fake listener never became ready, no TLS files were created, no preview or formal audio was created. Supervisor and fake exited, no frozen application process exists; see cleanup-process.json. Running-app lock exclusion and Launcher/JVM exit remain NOT TESTED.

The consumed single-use lock remains intact; no automatic retry, reset, source edit, Robot, JUnit or bounded execution. User authorization is needed before a new explicitly identified controlled start; the existing lock must not be deleted or bypassed. A fresh isolated attempt can retain the old directory/lock/logs and avoid pre-creating the fake-owned directory.

Status: HOLD / PRE-GUI FIXTURE STARTUP FAILURE. All historical RED, SKIPPED and INCONCLUSIVE records remain unchanged. No merge, release or P8 closure.
