# P8-S2B-F2-R1 — confirmed follow-up boundary / new test-contract RED

**P8-S2B-F2-R1 HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING.**

Base HEAD `ade2680b97397814b4f0686c2d2766655d37be63`, parent `ba7ad499e8113c9efd71760bbd4e4ed37f1ff673`. Project F1 documentation review PASS is recorded as task authority, not another engineering acceptance. No existing control document or historical evidence is changed.

## Identity and narrow scope

Preflight confirmed local/tracking/live origin and all five existing Kotlin draft byte identities. Original frozen F2 patch SHA-256 `9a7654e1c50d9e472dcf86934c166f8cdd48d692898134c5aab09f0b4e692477` matches the committed evidence. [preflight-source.json](preflight-source.json) records those identities.

R1 changes only `DesktopFishSampleTransport.kt` and `DesktopFishSampleTest.kt` relative to that frozen draft. The other three files (`DesktopFishAudioPanel.kt`, `DesktopFishPreviewRuntime.kt`, `DesktopFishPresentationTest.kt`) remain byte-identical. All five drafts remain local **uncommitted**; no validated source checkpoint is claimed.

- Before repair: [complete diagnostic source patch](diagnostic-source.patch), [manifest](diagnostic-source-manifest.json). Diagnostic production transport is the original frozen transport; only test observations were added.
- After repair: [complete fixed source patch](fixed-source.patch), [manifest](fixed-source-manifest.json).
- [R1 two-file delta against original F2 draft](r1-vs-frozen-draft.patch).

The full fixed patch passes `git apply --cached --check` against base without applying it or changing index/source. Existing source diff-check passes. The new directory's `.gitattributes` preserves raw CRLF/encodings; raw log/patch whitespace is not rewritten or falsely reported as an evidence-wide whitespace PASS. Related Skill relevance was reviewed: no unvalidated F2 implementation is added to an accepted map; F1's existing Skill and historical W22 clarification are unchanged.

## One bounded diagnosis: facts independently observed

[Original diagnostic records](01-followup-diagnosis/http-followup-diagnostics.jsonl), [command/exit](01-followup-diagnosis/stage.json), [XML](01-followup-diagnosis/junit/TEST-com.example.chatbar.desktop.DesktopFishSampleTest.xml), full logs and resources are retained.

| Response | Server requests | Physical request headers sent | Interceptor entries | Guard hits | Actual first response | Exit path / exception |
| --- | --- | --- | --- | --- | --- | --- |
| 301/302/307/401/403/429, each | 1 | 1 | 1 | 0 | Same code | onResponse / DesktopFishPreviewHttpFailure |
| 503 with Retry-After:0 | 1 | 1 | 2 | 1 | 503 | onFailure / generic IllegalStateException |

Every row recorded zero Authorization/Cookie/Proxy-Authorization headers. Test observers log only counts, numeric statuses, safe exception class names and callback classification; no URL, header value or response body. The second interceptor entry is **blocked before chain.proceed** and is not counted as a second physical send.

This confirms the original configured 503 follow-up path: first503 was received, OkHttp attempted another exchange, the guard threw, and onFailure replaced the typed status with a generic error. No second physical request was observed. The single original failing test was reproduced once: **1 suite / 1 test / 1 FAIL, exit1**, 68.4079225 seconds including resource observer. This is the authorized diagnostic reproduction of the historical RED, not a new safety violation.

## Narrow repair and separately preserved new RED

The transport now keeps first response status and the blocked-follow-up flag in the existing **per-call** tag. Only a blocked follow-up with an actually received error status300..599 can preserve that typed HTTP classification from onFailure. Other failures remain the fixed network error. There is no retry, server-response mutation, allowlist/authentication change, shared-client/request change, new player or change to F1 playback. Metadata contains no URL or credential.

[Fixed-run records](02-fixed-status/http-followup-diagnostics.jsonl), [command/exit](02-fixed-status/stage.json), [XML](02-fixed-status/junit/TEST-com.example.chatbar.desktop.DesktopFishSampleTest.xml) preserve the result:

- All seven HTTP subcases passed their concrete status, first-response, actual-send, guard and header assertions. 503 remains serverRequests1 / physicalHeadersSent1 / networkEntries2 / guardHits1, and now returns **DesktopFishPreviewHttpFailure(503)** through **onFailure**, not a fabricated onResponse. Redirects were not followed; HTTP errors were not broadly accepted as arbitrary exceptions.
- The same method then entered newly added injected network-fault checks. For the first DNS case, the non-HTTP type and exact fixed sanitized message assertions passed.
- A newly introduced `assertNull(failure.cause)` at `DesktopFishSampleTest.kt:160` failed: XML shows a cause that is also an IllegalStateException with the same fixed sanitized message. The null-cause condition was an **over-strong test assumption introduced in R1**, not a required public error contract.
- Coroutine exception recovery is a plausible explanation for the sanitized wrapper cause, but the complete recovery chain was not independently diagnosed. Do not claim a proven framework cause or a proven credential leak from this assertion alone.
- DNS request-count/empty-directory assertions after that line were not reached; the TLS and generic-I/O subcases were **NOT RUN**. The finally close still ran, but this is not a complete network-fault/cleanup PASS.

The fixed run is **1 suite / 1 test / 1 FAIL, exit1**, 52.7297436 seconds including observer. HTTP subcase success does **not** make the enclosing JUnit test green. Do not add two separate runs to invent a single two-test execution.

The user explicitly requires STOP on new repair RED. No source or assertion changed after this new RED; no timeout increase, filtering, exclusion or retry was used. A next authorized repair should verify the required sanitized behavior (including any cause chain) without assuming the framework must leave `cause == null`; that repair is only a proposal here.

## Remaining gates and resource limits

F2 two-class/further F1 focused regression, bounded regression, exact distribution, real Windows hearing/stop/cache separation/body-drain, Launcher/JVM exit, packaged lock exclusion/reacquisition and fake-service/TLS cleanup: **NOT RUN / NOT TESTED** this round. No packaged application/fake service was created; F1 accepted results do not replace these F2 gates.

Real sample GET **0/2**, TTS **0**. Recorded status-matrix mock traffic is seven actual requests per run; guard entries are not physical requests. No real Fish key/SecretStore/user data was read, no paid service called, and no real audio fetched. The earlier anonymous origin candidate is unchanged; signed WAV/other CDN remain unsupported, not a reason to broaden policy.

JDK17/offline/no-daemon/single worker/low priority/Kotlin in-process/existing2GiB heap; no clean/rerun-tasks, system configuration changes or user-process termination. [stage-summary.json](stage-summary.json) gives separate sampled extrema: diagnosis6 samples, min available859791360 bytes/max3135 pages/sec; fixed5 samples, min817262592 bytes/max633 pages/sec. Not absolute peaks; no OOM observed. Stop reason is the new assertion RED.

[raw-publication-ledger.json](raw-publication-ledger.json) records original/published lengths and hashes and any profile-only path redaction. Both XML copies are unchanged bytes. Historical F1/F2 evidence and four protected untracked user files remain unchanged/unread/unstaged. After evidence publication index is empty, but the five local drafts mean the worktree is **not clean**. No source acceptance, S2B closure, S3/chat voice/SaveSlot/G2 Writer/Recovery/dispatch, Prompt/schema/baseline/parity modification, desktop merge or release is authorized or claimed. WIN-AUTO-001 remains OPEN.
