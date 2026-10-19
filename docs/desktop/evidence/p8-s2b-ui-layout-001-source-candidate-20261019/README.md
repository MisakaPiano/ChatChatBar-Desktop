# UI-LAYOUT-001 — CONDITIONAL SOURCE CANDIDATE

Project-authorized source-candidate exception only. **Not final UI acceptance, full bounded PASS, Robot root-cause closure, or new Windows distribution acceptance.**

Source candidate: `ec32fdbc8383853b3024b585c19db0c3b840f47f`.
Direct parent: `044919ed9fd0c75b56b1843644012ff263ce99c3`.
Three files: **+787/-35** (Presentation +64/-29; Window +24/-6; new NativeUxTest +699).

## Exact source and preservation

The two production sources retain reviewed raw bytes: Presentation17883 / SHA256 `194d6b7fd2e270ca29eb90734303c8a7898825ecf8939cc60ab266e8d16c4917`; Window4280 / `a003e7ad1e31a0c356df1a4291cc7eb1e4eb9fa958a0a6353ee6a77ac8f88125`.

NativeUxTest is an exact raw-byte copy of R6G tested source:49505 bytes / `8beee5971355cd894b44a4743f89b6ad857c342274ae8410cfab5e99a3bbb423`. The four JUnit method bodies and pager/Robot driver section are byte-identical to the R6D source. R6G owned-window event observation correction remains; R6H–R6J temporary Win32/manual-runner/handshake code is absent.

The replaced86411-byte human runner is fully preserved in the ignored build directory and [this evidence copy](preserved-manual-runner.kt), SHA256 `32777017563fc625d3678a9acbde3b18d35262178ea8afdbbb15aa1984ccde77`. No historical record was overwritten.

## New execution

Exactly one authorized offline JDK17 `:desktopApp:compileTestKotlin`, single worker, no daemon/parallel, low priority, Kotlin in-process,2048MiB. Exit0,41.8857188s. **compileTestKotlin FROM-CACHE**: successful cache restoration, not a fresh compiler execution. Production compile was UP-TO-DATE. No clean/rerun-tasks, tests, Robot, manual runner, GUI, packaging or distribution gate. Real Fish GET0/TTS0. No residual Java process in post-inspection.

[Raw command/logs/resource sampling](01-compile-only/command.txt), [current validation](current-validation.json), [exact patch](staged-source.patch), [candidate identities](candidate-identity.json), [static comparison](static-source-audit.json).

## Historical limits retained

R6D Native4/4 PASS and R6E bounded Native4/4 PASS used test SHA `fdddf82225d120777c96fd8127f1584f4139c2767e5bbf0436c12db31783d238`, with these same production bytes. They are not a full GREEN result for candidate R6G test SHA. R6E full bounded had a cmd.exe CreateProcess error5 RED; R6F bounded had Native Press0 RED. R6G candidate version itself had1 test/1 FAIL after successful compilation. R6F/R6G physical-input causes remain UNKNOWN. R6H failed its pre-Press destination gate without sending Press.

R6I remains INCONCLUSIVE due pre-arming protocol defect despite user-reported scroll. R6J verified ARMED and observed owned physical input, but measured-thumb rejection prevented a complete before/after capture; INCONCLUSIVE remains despite late user-reported successful scroll. No manual report replaces missing instrumented data.

Two historical symlink JUnit SKIPPED and old NOT TESTED remain unchanged. WIN-AUTO-001 OPEN; WIN-BRAND-001 OPEN/DEFERRED. UI-LAYOUT-001 remains OPEN / WINDOWS MANUAL HOLD. See [historical identities and classifications](history-and-limits.json).

## Handoff

Only the three source files were included in the source commit. This evidence is a separate commit. Four user files remain unread/unmodified/untracked/unstaged; old local desktop is untouched. CURRENT, parity, baseline, dependencies, Prompt, entities and data contracts unchanged.

Project must review the actual Git diff and separately authorize any exact distribution / Windows manual gate. No automatic S3, later Phase, merge or release. **STOP / PROJECT REVIEW PENDING.**
