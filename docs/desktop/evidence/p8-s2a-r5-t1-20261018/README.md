# P8-S2A-R5-T1 — test-contract correction and continued gates

**P8-S2A-R5-T1 ENGINEERING CANDIDATE / PROJECT REVIEW PENDING** — 2026-10-18 JST.

**Automation PASS; WINDOWS UI BLOCKED. Not S2A CLOSED.** Only the authorized Desktop test contract changed. No production action eligibility, F4 state machine, Fish source/test, persistence, Prompt, baseline or Feature Parity change. No S2B/S3/S4 or G2 implementation.

## Exact identities and test change

Start: `96ee0496adba6ecb2bd0320ee5502042a5058947` (parent/tested production `d64f82f6fae4579353d77c114f92741cbf731b66`). Fetch verified local/tracking/live equality, tracked worktree clean and index empty. Source/test commit **`d90a166cbf639a9a5f19d20becf706f3dcf378d6`**, direct parent96ee0496; **one file +6/-2**: `DesktopChatDensityTest.kt`.

The existing exhaustive 256 eligible-subset loop stays. Its expected footer is explicitly COPY/REGENERATE/RETRY; expected overflow explicitly EDIT/DELETE/REPAIR_FORMAT/RESTORE_FORMAT/STOP_FORMAT. Ordered list equality derives expectations from each input eligible list, independently of production partition functions. Conservation/count assertions remain; an explicit distinct-size assertion adds duplicate detection. F4-only subsets therefore positively require overflow placement. No eligibility or permission policy changed. Source patch SHA-256 **`bc6b8276b31eb6d2cd77cce9966fafc0f298d59637e271f7b8562c3b30888cb7`**; `source-manifest.json` includes current normalized Git blob and raw worktree bytes. Nineteen other files from the original20-file targeted ledger remain byte-identical. Relevant skills reviewed; this narrow test correction makes no skill stale.

Formal validated upstream remains1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.

## Actual Gradle validation

All from app/, Temurin **17.0.20.1**, Gradle **9.1.0**, unchanged **-Xmx2048m**, sequential/offline/no-daemon/no-parallel/max-workers=1/priority=low/console=plain/no-configuration-cache/in-process Kotlin. Named test tasks use task-local `--rerun` to ensure actual execution; **no `--rerun-tasks`, clean or cache deletion**. Each stage's exact `command.txt`, exit/time `stage.json`, original stdout/stderr/resources and original JUnit XML are preserved before the next run.

| Stage | Exit | Seconds | Suites/tests | Reported PASS | Failures/errors/skips |
| --- | ---: | ---: | --- | ---: | --- |
|01 density + F4 interaction/presentation/controller + message actions |0|52.0299127|5 /59|59|0 /0 /0|
|02 same bounded Desktop non-Native regression |0|312.0378455|153 /1492|1490|0 /0 /2|
|03 createDistributable |0|31.3199813|not a JUnit suite|n/a|n/a|

Stage01: Density7, FormatRepairInteraction8, FormatRepairPresentation6, MessageFormatRepair31, MessageActionsUx7. Test compilation actually executed successfully; main compile was UP-TO-DATE. Stage02 main/test compile remained UP-TO-DATE and test actually ran. Do not add overlapping stage counts as unique tests. There are **158 original XML** files.

Bounded exclusions remain exactly the four prior exclusions in `bounded.init.gradle`; no added exclusions, skipped assertions, fail-fast workaround or production changes. Two existing symlink JUnit skips remain SKIPPED; four historical fixture early-returns remain NOT TESTED, not extra skips or coverage.

Per Project authorization, unchanged-source R5 SharedCore132/889, Android123/715 and Fish-focused12/160 are **referenced prior evidence**, not re-executed or recounted as T1 tests. Their compile status and all limits remain in96ee0496.

## Old RED remains independent

[Original R5 evidence](../p8-s2a-r5-20261018/README.md) remains at96ee0496:153 suites/1492 tests,1489 reported PASS,1 failure,2 skipped, exit1. Original density XML, logs and README were not edited. Before and after T1,475/475 manifest members verify with manifest SHA-256 **`fc921964f6b3e5ea5a8fb11d614842d58b1f29ec61e9c6ab54adbb6c2fbde70d`**. Both verification records are retained here. A successful new run never relabels old RED.

## New exact Windows image and native gate

One createDistributable invocation actually executed (no installer). **212 files** in `distribution-manifest.json`; complete isolated runtime copy matched before and after native execution. Business JAR matches current Gradle build/libs output. Original image/copy configuration unchanged; `chatbar.desktop.applicationHome=$ROOTDIR` resolves within the copy. No old P7/f020 runtime substituted.

- Launcher SHA-256: `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`.
- Main business JAR SHA-256: `7f5e2c4ccd75558c0c88fd903b0deeb61d71ee05de8f67058c91c6c530167d57`.
- Synthetic profile only: isolated LOCALAPPDATA, STRUCTURED/FREEFORM cards and fake DPAPI Fish key. No actual user data/root/chat/card/WorldBook used. The fixture seed acquired real ownership before writing its own data.
- A loopback-only HTTP CONNECT fake service served ephemeral TLS for api.fish.audio; process-only public truststore/proxy retained default certificate/hostname verification. No global proxy/truststore/Registry/pagefile modification. Validation helper, not UI, made3 local GETs (200/200/intentional401) proving personal/community-page2/auth-error fixture routing. No audio fetching, TTS or proxy forwarding to the Internet. This is synthetic Provider evidence, not native picker operation.
- Real owned Launcher6088 and child10976 were identified by executable path, creation time and parent relationship. Child loaded jvm.dll. Window enumeration returned the copy's ChatChatBar window.
- **One** screenshot-backed observation failed with exact `FrameArrived timed out: timed out waiting on channel`. **One bounded diagnostic** refreshed owned window and requested accessibility without screenshots: only root window/pane, no reliable Fish controls or visible content. No repeated capture or repackaging; no screenshot or user visual signature exists. Window title/process presence do not prove UI rendering or operation.
- Normal Alt+F4 targeted only the freshly identified owned window. Launcher **exit0**, JVM **exit0**, both actually exited; no force-kill. Fresh independent JVM used actual DesktopDataRootOwnership.acquire/close successfully (**exit0**). During-run independent lock-contention was NOT TESTED. The watcher finished before observing the close-marker file, so its original closeObservedUtc remains null; no timestamp was invented.
- Fake server drained, exit0; ephemeral private P12 and public truststore deleted by finally. No certificate/key bytes are published. Temporary runtime/data roots are retained; no deletion while I/O may be live.

**WINDOWS UI BLOCKED** remains for native Fish settings, save/reopen, sessions and both character-picker modes. [Concentrated25-item Chinese manual checklist](WINDOWS_MANUAL_ACCEPTANCE_CN.md) supplies actual entry labels, steps, expectations and signing fields; all complete manual operations remain NOT TESTED. Launcher/JVM/lock observations are separate automatic evidence and cannot fill UI PASS. No automatic second launch or FrameArrived retry loop.

Post-exit synthetic-root inventory is explicitly post-only: no complete pre-launch root snapshot was taken. Legitimate settings/preset/bootstrap business writes are not claimed to be G0 writes or whole-startup zero-write. Process records use synthetic path aliases; exact local originals and hashes remain available, with disclosure in raw-copy-provenance. Gradle logs and all JUnit XML are untouched byte copies.

## Authorized real Fish GET gate

After mock/offline GREEN, one-shot `LiveCatalogGate.java` used **only the existing OS-protected Fish credential via the existing strict read-only load**. No chat history, historical binding, SecretStore blob, API Key, Authorization header or response body was exported. The local synthetic profile used by native tests is separate.

Actual **3 logical GET /model requests /3 HTTP forwards**, all200, typed decoding succeeded: MINE page1 itemCount0, COMMUNITY page1 itemCount10, COMMUNITY page2 itemCount10. Single concurrency; monotonic wait >=30 seconds after each previous completed call. No per-request server timing trace is claimed. Redirects/auth follow-ups/connection retry disabled; a network interceptor also rejects a second forward in any logical request and caps total forwards3. Any first error stops. Only statuses/counts are recorded, not voice metadata or sensitive remote content.

**TTS0 / NOT TESTED**: account-specific s2.1-pro-free eligibility was not established; no audio produced. No TTS model, paid model or subsequent S2B/S3 runtime invoked. Fee status is GET-only/no TTS generation; account billing was not inspected and no verified account balance/charge claim is made. No account/key/network switching or limit evasion. This limited catalogue compatibility is not S2A acceptance.

## Resources and publication limits

38 periodic Gradle-stage samples: minAvailable1,725,005,824 bytes (~1.607GiB), minCommit headroom14,028,206,080 (~13.065GiB), max9,785 pages/sec, max4 Java processes. These are sampled extrema, not absolute peaks or complete native/live sampling. Before bounded:3,185,098,752 available bytes,16,096,100,352 commit headroom, pages0, Java0. No observed OOM or forced interruption. No user Chrome/NAI process operated/terminated; foreground responsiveness not independently verified. Final resource/process check is separate.

Only one authorized test and this new evidence directory are committed. User-owned root Markdown and three historical JPEG remain unread/unchanged/untracked/unstaged; therefore whole worktree is **not clean**, while tracked/index can be clean. No Kotlin business edits, baseline/parity/CURRENT/Prompt changes, release, protected-ref moves or next slice.

Evidence manifest covers all other published files. Raw console logs and raw patch are binary-attributed solely to preserve original bytes/trailing whitespace; ordinary Markdown/JSON/XML checks remain active. Git text insertions exclude those binary-attributed artifacts; local all-UTF8 splitlines includes them, so report their commands/definitions separately instead of asserting they are equal. No real credential/profile/runtime binary/certificate/audio or user attachment is included. Empty-output Tee commands created no log files; provenance explicitly records these rather than inventing originals.

**P8-S2A-R5-T1 ENGINEERING CANDIDATE / PROJECT REVIEW PENDING — WINDOWS UI BLOCKED / MANUAL SIGN-OFF PENDING.** Stop for independent Project review. Not S2A CLOSED.
