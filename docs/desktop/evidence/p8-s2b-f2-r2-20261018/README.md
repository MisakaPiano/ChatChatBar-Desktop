# P8-S2B-F2-R2 independent validation

Status: P8-S2B-F2-R2 ENGINEERING CANDIDATE / PROJECT REVIEW PENDING.
This records Codex execution, not Project acceptance or complete S2B closure.

## Source identity and bounded change

- Start `e3e0dcc1ee3324caf5e1f8f321b03449e716a061`; parent `ade2680b97397814b4f0686c2d2766655d37be63`.
- Source/test checkpoint `0b0a2f6c88555cc1eecbf1ea6370cd88582418bc`, direct parent above start, 5 files +505/-11. Full checkpoint patch SHA-256 `ea66a23a203d52981c6b07dbcb26cdd52a81cf00251fd71e938b3c86e1488b38`.
- R1 draft frozen patch SHA-256 `18849e8b1035dbf78de08944ed36069e25288f1d8d093f71b5fc0c0a702f1a0f` matched all five preflight drafts. R2 changes only `DesktopFishSampleTest.kt` relative to that draft: +34/-5; four other raw working-file identities unchanged. Delta SHA-256 `b2fecd0a8d0034420ccf51d6464b08640f645d134f4370d5211acb069e182303`.
- Replaced unnecessary cause-null assertions with exact top-level type/message and bounded identity-based cause/suppressed traversal (16 nodes, depth 8, fanout 8). It checks sanitized observable types/messages and forbids injected details without logging them. It assumes neither a present nor absent coroutine recovery cause.
- Request counts, HTTP status, guard/header assertions, directory cleanup and transport production draft remain intact. `source-checkpoint.json` distinguishes Git LF bytes/blobs from raw working-tree CRLF bytes.
- Formal upstream stays 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`. No shared request factory, schema, Prompt, baseline, parity, CURRENT or accepted G2 production contracts changed.

## Actual independent executions

| Stage | Suites/tests | Reported PASS | SKIPPED | failures/errors | exit |
|---|---:|---:|---:|---:|---:|
| 01 exact `DesktopFishSampleTest.status*` method | 1/1 | 1 | 0 | 0/0 | 0 |
| 02 F2 Sample/Presentation | 2/16 | 16 | 0 | 0/0 | 0 |
| 03 affected F1 Preview/Windows/Library/Settings | 6/60 | 60 | 0 | 0/0 | 0 |
| 04 bounded Desktop | 156/1515 | 1513 | 2 | 0/0 | 0 |
| 05 createDistributable | no JUnit execution | n/a | n/a | n/a | 0 |

These runs overlap and MUST NOT be summed into a single test total. Each stage contains its own command, actual exit, full stdout/stderr, resource samples and fresh XML copied before the next test run (165 XML files in total across the four executions). The bounded exclusion script retains exactly the four previously authorized exclusions. Two JUnit skips and four older symlink-fixture early returns (NOT TESTED) remain distinct.

JDK17 / offline / no-daemon / single worker / low priority / in-process Kotlin / original 2048 MiB heap; no clean or rerun-tasks. Desktop test compilation actually ran in stage 01. Main compilation and sharedCore tasks were UP-TO-DATE where recorded; Android was not rerun. No claim of fresh sharedCore/Android validation. Stage 05 createDistributable ran once; createRuntimeImage/main compile/JAR reuse is explicitly recorded, with packaged class identities checked against current outputs.

Seven HTTP cases 301/302/307/401/403/429/503 each made one actual MockWebServer request and one request-header send. The 503 case retained first HTTP503, network interceptor entries2 / guard1 / physical sends1 / secret header events0; callback onFailure produced typed `DesktopFishPreviewHttpFailure`. DNS/TLS/IO injections each made zero server requests and traversed two sanitized exception nodes in this environment. Every scenario and cleanup completed.

## Windows and limited live gate

- New exact-source distribution: 212 files, fully hashed before and after manual execution. Manifest SHA-256 `d83c7a8f0ed342a25c4a76d46bdbc03b448e6ff5b6e35b2f702eca7629b3c054`.
- Launcher SHA-256 `e5d45e5389e7584b9d8477efe34543de1069e6550560a3a3c53d164152958b8f`.
- Business JAR `app/desktopApp-1ec1aea21d9c36f8198180a85524a5.jar`, SHA-256 `6e4fbdb70a1df7b5708766e04703e9bd20772bedcb3ef83a4455ad9d254df721`; six key packaged production class byte identities checked against compiled output. applicationHome remains `$ROOTDIR`.
- One real anonymous official sample GET, HTTP200, one wire send, zero credential headers, TTS0, no retry. Only fixed approved `https://platform.r2.fish.audio` MP3 profile. One additional official public HTML GET is source provenance, not a sample request. Remaining sample budget unused. No account/billing or real SecretStore access.
- Downloaded official sample 405420 bytes, SHA-256 `c730e4c467f8dcf465acca9e426ea6349137e3302ff65e648f66dc2a63e3301f`; store validated then transport drained/store cleaned. Downloaded public audio remains LOCAL ONLY; no redistribution right assumed. Public source HTML also LOCAL ONLY because it includes unrelated expiring signed capabilities. Published record contains selected public unsigned source and raw page hash/size, not those capabilities.
- Packaged UI used a new synthetic LOCALAPPDATA/profile, synthetic DPAPI Fish credential and child-process-only loopback proxy/trust settings. No real user root/key/chat inspected. The fake accepts only approved local test hosts and has no outbound forwarding. It replays the validated official clip and a separately hashed F1 synthetic Chinese clip. Its canned TTS POST is not a real Fish TTS call.
- Official live transport success and packaged fake/manual playback are separate evidence chains; this is NOT a claim of packaged GUI direct-to-Fish end-to-end validation.
- Native hearing/stop/switch, generated-versus-sample cache, hung-body picker close and both editor entries were confirmed in user text; see `MANUAL_RESULTS_CN.md`. Two voluntary user screenshots demonstrate the layout issue; no automatic screenshot claim.
- Launcher PID800 and JVM-loaded child13200 were identified by exact executable, creation time and parentage, with process handles retained. Both actually exited0/0. Independent active lock probe75 (AlreadyInUse), post-exit acquire/release0. Three stopped cached clips could be opened exclusively without writing. Hang test sent128 bytes, observed client EOF after about0.645s, with no .part/MP3 left after picker close. This is observed timing, not an absolute cancellation deadline.
- fake exited0, worker/listener cleanup recorded; only owned ephemeral private P12 and temporary public trust store removed. No test roots/evidence/audio fixtures deleted. Preview MP3/.part inventory after exit empty; two original synthetic character-card hashes unchanged. The close confirmation marker was written AFTER actual process exit, so no close-to-exit latency is claimed.

## Resources, history and limitations

50 system samples across stages01–05: minimum available1.3456GiB, minimum commit headroom15.3418GiB, maximum5114 pages/sec. These are sampled extrema, not absolute peaks. No OOM, uncontrolled pressure or forced termination observed. Resource thresholds were not reinstated; no user process/system/pagefile changes.

Original F2 and R1 RED remain in independent committed trees, with unchanged blobs and original manifest hashes in `historical-evidence-preservation.json`. No new test RED, exclusion, skipped branch or historical reclassification was introduced. A local report-summary helper first used Windows default GBK decoding and failed; explicitly specifying UTF-8 fixed that bookkeeping read. It was not a Gradle/test retry and did not alter test outputs.

Known limits: legacy MCI/device/codec coverage; complete Windows TOCTOU/ACL/hostile concurrent file replacement; physical absolute deadlines for blocking native/I/O calls; power-loss durability; all audio devices/complete GUI; unsupported/signed/WAV/other-origin samples. Direct DNS checks cannot attest remote DNS resolution performed by an explicitly configured proxy. No allowlist or authentication scope expansion. Future free-model qualification still requires release-time recheck; no TTS or free-period revalidation performed here. WIN-AUTO-001 remains OPEN; real screenshot infrastructure was not repaired or used.

`UI-LAYOUT-001` remains OPEN: user requests later layout optimization. See exact 1.4.1 source/button comparison and two supplied screenshots. No UI redesign was added during R2 validation. Four protected user-owned untracked files remain unread, unchanged, unstaged and uncommitted; tracked/index cleanliness does not imply a wholly clean worktree. No S3, permanent audio repository, chat generation, SaveSlot, G2 Writer/Recovery/dispatch, merge or release authorization.

Publication preserves raw bytes where safe; `raw-publication-ledger.json` lists any host-profile-only redaction and original/published hashes. Raw XML remains byte-identical. `.gitattributes` disables line-ending conversion for this directory. `evidence-manifest.json` covers every other published file and excludes itself. Skill scope reviewed before staging: explicit five-file source authorization was preserved; future mapping documentation remains separately scoped.

Static checks: source checkpoint diff-check PASS; staged metadata diff-check PASS. Full immutable evidence diff-check returns2 for raw log trailing whitespace/EOF and unified-patch blank context lines; exact diagnostics are preserved in `raw-evidence-diff-check.txt`. Raw evidence was not trimmed.
