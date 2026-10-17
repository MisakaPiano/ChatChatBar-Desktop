# P8-S2A-R5-T2 — partial Windows manual gate / UI hierarchy issue

**P8-S2A-R5-T2 WINDOWS MANUAL GATE / PROJECT REVIEW PENDING — HOLD (T2-UI-001).** 2026-10-18 JST. This records actual user observations; it does not close S2A or reopen T1 engineering acceptance.

## Frozen source and runtime

Branch `feature/phase8-fish-audio`, source/checkpoint **`0f2f4c5d62114474c9e878f549ce97bd714a2b0a`**, parent `d90a166cbf639a9a5f19d20becf706f3dcf378d6`. Fetch/local/tracking/live identities matched at start and live was checked again before publication. No application/test source changed. Existing root personal Markdown and three JPEGs remain unread/unmodified/untracked/unstaged. Whole worktree is not clean; tracked/index are independently checked.

Reused T1 exact distribution: **212 files**, original image and retained T1 copy verified before creating the isolated T2 copy; all212 T2 runtime files verified again after normal close. **No distribution rebuild, Gradle, JUnit, Android, focused or bounded rerun.** Only the small local fixture helper was compiled with existing JDK17; helper classes are outside the runtime and business source tree. Original T1 test evidence remains unchanged and is only referenced, not counted as T2 execution.

- Launcher SHA-256 `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`.
- Business JAR SHA-256 `7f5e2c4ccd75558c0c88fd903b0deeb61d71ee05de8f67058c91c6c530167d57`.
- Full [distribution manifest](distribution-manifest.json), [initial identity](image-verification.json), [post-manual verification](post-manual-image-verification.json).

Only an isolated LOCALAPPDATA and synthetic cards/sessions/models/Fish DPAPI credential were used. Two cards (STRUCTURED/FREEFORM), two persons each, A/B sessions, normal/auxiliary-only model choices and a deliberate missing Tag ID were seeded under acquired ownership. Only targeted synthetic settings were read back. No actual user root, chat, Fish secret, card package or real account was accessed.

## Actual manual observations and pause

User first confirmed a normal interactive CCB window with both T2 cards. Broad instructions were then split into one-step actions after the user said they had not understood the initial batch; that batch is not treated as completed.

Evidence comprises **9 user-supplied PNGs**, byte-identical to the supplied attachments, and user text in `user-signatures.jsonl`. **No automated screenshot or FrameArrived attempt occurred in T2.** A window title or process existence is not used as UI PASS.

Observed successful subcases:

- Global voice controls and unavailable-model warning were visible, including in the narrow window.
- Audiobook off -> on showed unsaved state; discard restored off and cleared dirty state.
- Audiobook enable/save and navigate away/return preserved on. Synthetic persisted field confirmed true. This is page reentry, not full app restart.
- User explicitly said “刚才我点错了，我试了全都可以保存。” with a screenshot listing the four TTS options. This is recorded as the user's ordinary TTS-save report; screenshots directly show s2.1-pro then s2-pro, not fabricated per-option captures.
- Tag selection first visibly used T2 normal chat model, later the auxiliary-only “T2 辅助文本模型（不发请求）” and no unsaved warning. Initial failed-ID warning remained until an explicit valid selection. Final readback: TTS `s2-pro`, Tag `t2-aux`, audiobook true.

Full25-item table: [MANUAL_RESULTS_CN.md](MANUAL_RESULTS_CN.md) and [machine-readable final table](manual-results-final.json). Conservative whole-item counts: **4 PASS /1 FAIL /20 NOT TESTED**. PASS includes startup, ordinary invalid-model/discard, and two process-exit items. Partial subcases remain visible under NOT TESTED rather than being promoted. NOT TESTED does not mean no user work occurred.

Credential edit/cancel/replace/clear, null-follow-session Tag, session settings, two-mode voice picker, personal/community/filter UI, binding save/reopen/remove/import/export, late-query races and app reopen were **not completed**. Injected storage/concurrency cases can reference prior T1 technical evidence but have no T2 manual PASS. No automatic second instance, package replacement or code patch occurred during the user's gate.

## Confirmed issue T2-UI-001

User report: “我注意到一个问题语音和朗读的标题显示方式和其在设置里对应原CCB的模块等级不符”. [Supplied screenshot](user-screenshots/tag-aux-saved-heading-issue.png) and [issue record](T2-UI-001-module-hierarchy.json) retained.

Narrow exact-source comparison:

- Official1.4.1 `GlobalSettingsScreen.kt:318,321`: `appearance` and `voice` are peers in the top-level SettingsCategory list. “语音与朗读” owns voice entries, alongside appearance/images/reply categories.
- Current Desktop `DesktopFishAudioPanel.kt:40`: title is rendered using ordinary `StatusText("语音与朗读 · Fish Audio")`.
- `DesktopManagePanel.kt:264`: Fish controls follow appearance/font-size controls without a distinct category header/card. These calls are siblings in the settings branch; the finding is **visual/information hierarchy**, not a claim that Kotlin literally nests voice inside the appearance composable.

[issue-source-ledger.json](issue-source-ledger.json) pins complete Git blobs/SHA-256 and small numbered excerpts. Formal baseline remains1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. No1.4.6 semantics or broad settings redesign adopted.

W02 records visible controls PASS as a subcase but module hierarchy FAIL. All prior successful observations remain in screenshots/signatures. Further manual steps were paused and the user was asked to close normally. **No production fix applied.** Project should decide the narrow heading/group-boundary remediation and remaining manual gate scope; this evidence does not authorize another implementation slice.

## Native processes, ownership and cleanup

User explicitly confirmed normal T2 close and requested verification/handoff. Retained handles in [process-result.json](runs/manual01/process-result.json) prove:

- Launcher **PID31172**, created2026-10-17T19:28:31.481723Z, actual exit **0**.
- Child **PID19392**, parent31172, created2026-10-17T19:28:31.781525Z, jvm.dll observed, actual exit **0**.
- Both exited, processLifetimeBlocked=false. Raw watcher closeObservedUtc=null because the user closed directly without a control marker; no close timestamp was invented. Lifetime duration899.2591418 seconds is from the original UTC start/end, not startup latency.
- During runtime a fresh independent JVM using actual DesktopDataRootOwnership.acquire was refused (expected exit75, OWNERSHIP_HELD). After both processes exited another independent JVM acquired and released the same synthetic root (**exit0**).
- Only after that confirmation was the supervisor sent `finish`. Fake server drained; it and the supervisor exited, [cleanup.json](mock-service/cleanup.json) confirms private P12/public truststore deletion. [mock-final.json](mock-final.json) confirms neither remains. Exact mock exit code was not captured; it is not invented as0.
- [Final owned-process query](final-owned-process-check.json) found no exact T2 image process and no remaining known owned PIDs. This supplements retained-handle results; no PID-number-only inference or force-kill.

The app used a process-only loopback proxy and ephemeral TLS truststore; normal hostname/certificate verification remained enabled. The service never forwarded requests to the Internet. Three **local fixture-readiness** GETs (200/200/intentional401) preceded manual use; these are not UI actions or real API requests. **Actual real Fish increment GET0 / TTS0.** No global proxy, certificate store, Registry or pagefile changed. Synthetic data/image/history retained, credential profile excluded from publication. Only ephemeral TLS fixture files were cleaned.

Before/post synthetic inventories and their diff are included. They cover legitimate first-launch initialization plus user settings saves; they are **not zero-write** and do not prove data preservation for untested real roots. No production data migration, cleanup, Writer or Recovery ran.

## Preparation/error and publication provenance

A first local preparation command failed because Python default GBK could not read a UTF-8 helper file. Its supervisor launch then found the not-yet-created script; no business app/fake service started in that attempt. `preparation-attempt01.json` is an explicit transcription of the tool exception, not an invented raw traceback. The follow-on supervisor stderr and process record are preserved. Preparation was corrected before the user's window was launched; no application source changed.

Local originals remain under the gitignored T2 gate. Published text can remove BOM and alias synthetic/user-home paths; `publication-provenance.json` records original versus published bytes/hashes. PNGs are exact copies. Local CMD wrappers with interpreter-specific user paths, runtime binaries, profile entities, secrets, private/public certificate bytes are excluded. The manifest describes published bytes; it does not pretend aliased records are identical to originals.

Only this new evidence directory is published. No CURRENT/Feature Parity/baseline/Prompt/Entity/schema/Skill/production/test edits; no Fish next slice, G2, merge, release or upstream sync. T1 automation PASS and original R5 RED stay independent and untouched. Relevant Fish skill was reviewed; evidence-only recording does not change its implementation contract.

**P8-S2A-R5-T2 WINDOWS MANUAL GATE / PROJECT REVIEW PENDING — HOLD / T2-UI-001 PRESERVED.** Not S2A CLOSED. Stop for Project disposition.
