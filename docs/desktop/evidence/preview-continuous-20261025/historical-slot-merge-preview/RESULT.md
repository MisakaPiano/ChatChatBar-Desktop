# Historical voice SaveSlot and explicit merge selection

D-046 engineering checkpoint, no whole-app merge/import or Preview completion claim. Original Android Prompt/default repository behavior unchanged.

## Actual rounds
- historical-slot-process-red: test fixture used positional GeneratedVoiceMessage constructor without synthesisText slot; compile failure, exit1,0 tests. No stale XML retained here.
- historical-slot-process-red-runtime: exit1,1 test FAIL; child actually refused REFERENCE, but assertion harness read Windows default-encoded stderr as UTF8 and raised MalformedInputException. Raw child bytes/log retained.
- historical-slot-process-red-admission: after specifying child UTF8, exit1,1 test FAIL, explicitly demonstrates actual service.import refusing pruned version at DesktopSaveSlotArchive REFERENCE.
- historical-slot-and-merge-green: production smart-cast compile error across module boundary; exit1,0 tests. Fixed with local version variable; no stale XML retained here.
- historical-slot-and-merge-verified: exit0,8 suites/64 PASS,0 failures/errors/skips.
- historical-slot-final: added explicit opt-in history-count/permission and invalid-owner/session/media/blank/length cases; exit0,8 suites/66 PASS,0 failures/errors/skips. These66 are current count, not summed with historical rounds. Nine are merge preview policy tests, three are historical package/process tests; child executions are not extra JUnit tests.

## Behavior and evidence
Production Archive enables historical custody with an immutable original package. Inspector default and Handoff default still refuse unknown versions. Opt-in accepts only nonblank bounded explicit old IDs with owner/session/audio/count/length/CRC evidence; it reports history count and cannot grant restoration. RestorePlan uses the matching explicit Handoff mode, actual verified MP3 materialization/owner receipts and strict Desktop repository; no text matching or active-version substitution for an explicit old ID. The standalone inspector now describes its actual limitation (structure/codec/playback not verified) instead of falsely claiming Desktop has no voice/restore runtime.

Three independent child JVMs run actual service/journal import+restore, close/reopen/read and export. Final parent asserts exits0, exact audio bytes via verified audio store, retained old version/sourceText/creation/length, no current-version playback and retained independent PC session plus safety archive. Original synthetic source bytes remain unchanged. child/ contains only synthetic process output; no .cbsave/media/native protected blobs are uploaded. Windows host JVM evidence, not Android, GUI, reboot or power-loss proof.

DesktopMergePreview is a production selection policy, still awaiting materializer/UI integration. Whole conversations/drafts/Memory/voices/slots remain one conflict unit; same-name, account/endpoint and cross-session message-ID collisions require explicit applicable choices. BOTH marks required dependency remapping and cannot itself publish. Selective scope cannot reset unselected PC settings. Authenticated media digests support equality without path equality. Tests do not claim completed fork/reference mapping, data/secret CAS or merge activation.

Code dependencies bind to head.txt plus raw pre-test modified-source snapshots. Each log/XML/source is indexed byte-exact in RAW_INDEX.json. All content synthetic; real archives/reports/API credentials remain local ignored. Baseline1.4.1 unchanged. Continuous implementation proceeds after this checkpoint.
