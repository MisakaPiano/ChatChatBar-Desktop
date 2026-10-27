# P11-S0 R1 — legacy audio reference candidate evidence

Date: 2026-10-15 JST. PROJECT REVIEW PENDING. No import/restore/playback acceptance.

## Identity and narrow scope

- Parent and original local/tracking/live origin: `16b7fdea9d78d098ae2986675f4fef4dc70ad82a`, feature/phase8-fish-audio, initially clean index and worktree.
- R1 source: `e8855db66e2669cb089eca3935e8f2b2bfe83f5d`.
- Exactly two Kotlin files: DesktopSaveSlotInspector.kt (+9/-1), DesktopSaveSlotInspectorTest.kt (+88/-0); total +97/-1.
- `source-manifest.json` records exact working SHA-256 and committed Git blobs. `validated-source.json` is the source identity captured during the run and rechecked before commit.
- No new branch/worktree, protected branch updates, upstream sync, Prompt/schema/storage changes, production user data reads, API calls or historical evidence cleanup.

## Official source and intended compatibility

Formal baseline `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`, app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt: materializeSaveSlotAudio at 4442, ordered audioPath/voice.id lookup at 4450–4451; validateSaveSlotImport at 4489, schema>=5 required association at 4505–4508. Exact source blob and references are recorded in official-authority.json. SaveSlot/GeneratedVoiceMessage remain the existing shared entities.

The old Desktop inspector checked only audioPath. R1 resolves path first, then voice ID for schema5–7 only, before the existing missing-reference rejection. Tests assert the exact resolver key AND inspect an archive with both distinct valid resources, making priority observable without decoding or playing audio. Missing references remain UNSUPPORTED/REFERENCE; corrupted Base64 remains INVALID/SCHEMA. Identity, session, message/version guards precede resource lookup.

Official validation exempts schema<5 from mandatory audio presence, and official materialization's fallback is not explicitly schema-gated. This narrow R1 deliberately does NOT broaden earlier Inspector rules: schemas1–4 keep their pre-existing path-required checks. This is recorded as an existing limitation, not complete legacy restore parity. v8 remains solely on its ZIP reference/byteLength path; no legacy fallback can bypass it. All supplied inline resources still undergo existing bounded Base64 syntax checks, including unselected resources; codecs remain unverified. No path or payload is returned in inspection output.

## Actual R1 execution

Existing Temurin17.0.20.1, Gradle9.1.0, existing -Xmx2048m. From H:\ChatChatBar-Desktop\app, exact arguments in 01-inspector.json:

```powershell
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache '-Pkotlin.compiler.execution.strategy=in-process' :desktopApp:compileKotlin :desktopApp:compileTestKotlin :desktopApp:test --tests com.example.chatbar.desktop.DesktopSaveSlotInspectorTest
```

Exit0; runner elapsed232.292s (Gradle reports3m51s). Desktop main/test Kotlin compilation, jar and test actually executed (4 executed tasks); shared prerequisites UP-TO-DATE, not claimed as newly compiled. Full Inspector class: **1 suite / 23 tests / 0 failures / 0 errors / 0 skips**, JUnit3.960s. Gradle configuration-task SKIPPED statuses are not JUnit skips. Original14 assertions/tests retained; 9 tests added, no new RED or retry.

New coverage (schema loops are cases within the reported tests, not extra JUnit tests):

1. schema5/6/7 voice.id fallback; sanitized output and runtime limitation retained.
2. schema5/6/7 audioPath keys.
3. both keys present, path has priority.
4. neither key present (unrelated resource only), fail-closed.
5. invalid Base64 under either accepted key.
6. missing message, wrong session, wrong version IDs.
7. schema1–4 path behavior retained; voice-ID-only v8 reference rejected; existing valid v8/audio-length tests also execute.
8. explicit CompletableDeferred suspension at legacy voice processing, cancellation, SHA unchanged and safe reopen; no sleep race.
9. >1MiB inline audio rejected by the existing pre-decode source budget.

Every inspection helper compares source SHA-256 before/after success or failure; cancellation explicitly checks the same and file list. No decoder, extracted files, audio backend or data-root write was added.

## Reused contracts — NOT RUN in this R1

`unchanged-contract-trees.json` proves entire sharedCore and Android app trees unchanged relative to the parent. `reused-evidence.json` lists original XML hashes checked against preserved prior files:

- SharedCore prior final3 suites/8 tests PASS (including SaveSlotSharedContractTest2 and ChatMessageSerializationTest3), directory ../p11-s0-inspector-20261015/junit-04-shared-final/.
- Android original SaveSlotSerializationTest prior1 suite/7 tests PASS, directory ../p11-s0-inspector-20261015/junit-07-android-final/.

These are prior valid evidence, not new R1 execution counts. Prior SDK/compiler RED, all earlier XML/logs and accepted P9/B5B evidence are untouched.

## Resources, safety and raw-byte evidence

No concurrent build was present. Periodic sanitized process/memory samples are included; no command lines, browser pages or window titles. Initial available1072.20MiB, Commit headroom10.96GiB, H:37.99GiB, Java0. Lowest observed available110.56MiB, followed by167.68MiB; only one sub128MiB sample. One earlier186.43MiB sample had12047pages/s; next sample1040pages/s. No observed sustained severe paging45s condition, consecutive sub128MiB samples, low Commit headroom, OOM or write failure. Absolute minima/peaks between samples UNKNOWN. End:2099.26MiB, Commit11.62GiB, Java0. No browser/user process was modified or ended.

Original raw XML/log were reviewed: empty XML stdout/stderr, synthetic test names only; no credentials, private content, auth headers or key material found. log-manifest.json and junit-manifest.json provide SHA-256; .gitattributes preserves raw XML, log and executed runner bytes, including the runner's original trailing blank line (whitespace override only for raw evidence). Staged Git blob hashes are checked before the evidence commit. No historical files overwritten.

## Remaining boundaries

R1 is a source/test candidate, not Project acceptance. Management UI and Windows manual test NOT RUN/PENDING; no package, official exporter golden, real cross-platform roundtrip, formal import/restore, P9 index writing, global backup or D-040 implementation. P11-S0 and broader parity claims remain for independent Project review.
