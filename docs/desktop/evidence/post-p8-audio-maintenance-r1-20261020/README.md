# Post-P8 Audio Maintenance Safety R1

**ENGINEERING CANDIDATE / PROJECT REVIEW PENDING**. Source `694a8bdd6e48bd6696d2eeb59f141935c67163b0`, direct parent/base `c6d94abd6540416d1a6a90e8d088b0240bd0e13d`. Evidence is a separate child commit; its SHA is read from Git, not self-embedded.13 source/test/skill files,+587/-40. Frozen UX source22d04333 remains historical; it is not overwritten. No CURRENT/parity/baseline/sharedCore/Android/Prompt/schema/Package or accepted voice-store change.

## Observed execution

1. Main compilation PASS; first test compilation RED (nested fixture called an outer helper; a JUnit method inferred internal non-Unit return). No test ran/no new XML. Original log and exact source bytes retained.
2.31 tests executed,29PASS/2FAIL. One exposed existing migration omission: successful runtime stabilization was not copied into precommit Failure.runtimeDisposition; this prevented truthful retry classification. Fixed the result plumbing, preserving authority/seal/materialization rules. The other was virtual runTest time advancing against the real IO player; switched to an independently/manual-stepped TestCoroutineScheduler and real bounded signal waits. No threshold/assertion removal. Raw XML/stack and source bytes retained.
3. Tightened close-failure behavior: failed native close now retains the original normal registration in a noncancellable drain handshake. Explicit stop sees the failure without joining forever; a later explicit retry requests another close, and only confirmed close releases that registration. No timer/autonomous retry. Added a real-coordinator exclusive assertion while alias remains open.03:2 suites/32PASS/0skip.
4. Affected voice/snapshot/scheduler/runtime/migration/coordinator:14 suites/151PASS/0skip, actual execution.
5. Prior final UX scope:18 suites/124PASS/0skip, actual execution. Counts overlap; do not sum as unique cases. Native Windows silent-position test is automation, not user hearing.
6. New exact-source distribution build exit0;212 raw-hashed files. Separate build/frozen directories. Old212-file UX and accepted R3 packages independently rehashed unchanged. New package not launched, no artificial root/fake seeded. No full bounded, Robot, Android or GUI gate.

## Admission and ordering

AppContainer owns one DesktopAudioMaintenance over the existing DesktopAudioFocus/native output. Migration reserves before destination prepare; stops/joins formal playback and directly confirms native close; then uses the unchanged scheduler pause/stop/join, coordinator exclusive, preflight/safety snapshot/materialization/authority ordering. Audio focus mutex is only held around short state/native operations, not while joining player/scheduler or awaiting the coordinator. Reservations do not wait for one another. A migration concurrent with a backup reservation returns a precommit AUDIO_MAINTENANCE failure; retry remains explicit.

Automatic backup uses a nonblocking idle reservation before requesting exclusive. Native owner/paused state, uncertain close, another reservation, or focus mutex busy causes DesktopAudioMaintenanceBusyException. Existing scheduler emits Failed with explicit deferred/retry message and retries next check. Busy is never SkippedNotDue/Created. When idle is reserved, epoch/under-lock validation prevents a new formal open racing into the exclusive interval. Tests also exercise a real scheduler in-flight backup vs migration, not just isolated functions.

Per-request epochs captured by playFrom and per-queue item plus validation inside the focus mutex reject stale opens. Foreground generation is rechecked under autoplay mutex before manual replacement, preventing old intents becoming fresh intents. Resume cannot open an alias during maintenance. Normal pause/position/same-alias resume/second-stop/cross-User queue remain covered by passing regressions.

Cancellation during drain completes real close before reopening admission, then propagates cancellation before file work. Cancellation while pending exclusive or inside preflight releases reservations/destination using existing stabilization. Duplicate migration remains serialized by service lifecycle mutex. Successful/indeterminate authority seals audio admission until restart. Native close failure stops migration before destination preparation/materialization/authority, leaves source/audio bytes and its original registration preserved, quarantines admission, and permits only explicit confirmed recovery. Precommit stabilization reports actual Resumed; no authority outcome was weakened.

## Resource and cleanup evidence

Resource snapshots (not continuous monitoring) in resources.jsonl. During compilation one sample had486268KiB free physical memory, pages/s6766, commit still below limit; no OOM observed. Final snapshot has no Java/javaw, pages/s0, free physical1716308KiB, commit32902983680/41946214400, disk9972486144 bytes. No user process killed, pagefile/cache/package cleanup, or settings change.

New maintenance fixture finally confirms native alias=false/coordinator=CLOSED, closes backup/migration services and removes only its owned synthetic temp tree. The reused legacy ChatVoiceRuntime fixture closes runtime and MockWebServer but retains its small synthetic temp files by existing design; no global temp sweep was performed. New package Launcher/JVM/root-lock/TLS/manual-cache cleanup is NOT TESTED because it was never launched. Do not substitute fixture exit for packaged lifecycle evidence. Test HTTP is loopback only; aggregate mock count across legacy suites is not instrumented. The added direct-playFrom case asserts0 requests. Real Fish GET0/TTS0, paid helper0, no real credentials/user-data access.

## Limits / review disposition

The original risk remains a source-confirmed conditional wait, not a historically reproduced UI deadlock. These focused results validate the scoped repair, not final acceptance. MCI native calls still have no physical deadline; if the OS never returns, drain remains unconfirmed and file maintenance cannot proceed. This change intentionally fails closed rather than pretending release.

The app-owned protection is wired to data-root migration and the coordinated snapshot facade (create/restore/prune/automatic). It does not globally change AppDataOperationGate or automatically retrofit every other raw coordinator-exclusive business caller (e.g. separate image/RAG operations); those retain their existing ownership contracts. No generic transaction framework was introduced. Existing device/native-fault UI matrix and broader maintenance interaction require independent review as appropriate.

Historical cmd.exe/Robot RED, symlink SKIPPED, R6I/J INCONCLUSIVE, WIN-AUTO-001, WIN-BRAND-001 and Studio credential error recovery remain unchanged. No manual PASS, no parity promotion/desktop merge/release. Project may review this candidate and authorize a fresh isolated consolidated Windows visual/hearing gate; no such gate has been started. Real root migration/restore needs its own synthetic-data manual contract. STOP for Project.
