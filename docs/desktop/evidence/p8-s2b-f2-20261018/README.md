# P8-S2B-F2 — Existing official sample preview / first focused RED

**P8-S2B-F2 HOLD / RAW RED PRESERVED / PROJECT REVIEW PENDING.**

Start: `7333923a1c224301da7c00c688afaedbecb8a404`. Independent F1 acceptance documentation/related historical W22 Skill clarification: `ba7ad499e8113c9efd71760bbd4e4ed37f1ff673`, direct parent is start. Five files +49/-1; this is the user's Project decision registration, not a new F1 engineering gate. Documentation links and diff-check pass; no F1 tests were rerun for this registration.

## F2 draft identity and bounded result

Five F2 Kotlin drafts remain local/uncommitted: new `DesktopFishSampleTransport.kt`, new `DesktopFishSampleTest.kt`, modifications to `DesktopFishPreviewRuntime.kt`, `DesktopFishAudioPanel.kt`, `DesktopFishPresentationTest.kt`. [source.patch](source.patch) is the full draft patch against the documentation checkpoint; [source-manifest.json](source-manifest.json) freezes working-tree byte lengths/SHA-256 and base blobs. The patch passes `git apply --cached --check` against that base; this check does not apply or discard the local draft. No production source checkpoint is committed because validation is RED.

The candidate adds explicit sample download/play, distinct kind/reference/source-hash cache identities, the existing Windows output/store owner, and cancel/join before switching sample. It does not call shared `preview(url,key)` or change shared serialization. New transport has no credential provider. Only canonical HTTPS `platform.r2.fish.audio` MP3 task paths are allowed; no authenticated sample origin, arbitrary URL, query/signed capability, redirect, alternate port, IP target or implicit second wire exchange is approved. A test-only injected connection route maps already-validated URLs to MockWebServer; production uses identity routing. Existing F1 limits remain 16/64 MiB, four outstanding leases, 120 seconds, real callback/native drain. This is an **unaccepted draft**, not a production safety claim.

JDK17 / offline / no-daemon / no-parallel / single worker / low priority / Kotlin in-process / existing 2 GiB heap. No clean or rerun-tasks. [Exact command](01-new-focused/command.txt), [exit/time](01-new-focused/stage.json), [stdout](01-new-focused/gradle.stdout.log), [stderr](01-new-focused/gradle.stderr.log), [resource samples](01-new-focused/resources.jsonl), [derived counts](stage-summary.json).

| Actual first run | Result |
| --- | --- |
| Desktop compileKotlin / compileTestKotlin | Executed successfully, not old F1 PASS reused |
| DesktopFishPresentationTest | 8 tests PASS, 0 failures/errors/skips |
| DesktopFishSampleTest | 8 tests, 7 PASS / 1 FAIL, 0 errors/skips |
| Combined | 2 suites / 16 tests, 15 PASS / 1 FAIL, Gradle exit1; 104.0678078 seconds including observer |

Raw XML is preserved byte-for-byte under `01-new-focused/junit/`. Do not add it to older F1 counts. Source snapshot before execution and after STOP is identical. Raw encodings, CRLF, log whitespace and unified-patch context are retained, not normalized to manufacture a whitespace PASS.

## Failure, known facts and unproven explanation

Failed test: `status failures redirects and retry after never resend or leak response content` (`DesktopFishSampleTest.kt:94`). It loops over 301/302/307/401/403/429/503. The assertion requires `DesktopFishPreviewHttpFailure`; raw XML reports the fixed generic `IllegalStateException` “官方样本网络失败；未自动重试” from sample transport's `onFailure`.

The source guards a second network exchange by throwing IOException. A plausible explanation is OkHttp's `503 Retry-After: 0` follow-up encountering that guard and entering `onFailure`, rather than exposing a typed HTTP response. **The failing loop iteration and physical request count were not separately recorded before this assertion, and no diagnostic rerun occurred.** Therefore 503-specific causation and the failing iteration's one-request invariant remain **NOT PROVEN**. Do not call this an established no-resend PASS, credential leak, actual second request, or known production regression merely from this exception type.

The Project instruction says “遇到新的安全或相关回归 RED 及时 STOP.” No assertion was broadened, exclusion added, timeout increased, retry performed or production behavior changed after the RED. No bounded regression, distribution, native hearing or real sample GET followed. A next task may authorize one bounded diagnostic to record the failing status/physical exchanges, then decide whether the test must distinguish a guarded follow-up failure or whether preserving the original status needs a narrow production change. Neither option is implemented here.

## Official origin evidence and limits

[source-provenance.json](source-provenance.json) records exact public page/module URLs, bytes, hashes and small code excerpts; [official-sample-origins.json](official-sample-origins.json) resolves official SSR model/sample records without signed query values. The official voice-card module uses sample.audio as an Audio element src without a Bearer header; the URL mapping preserves platform.r2.fish.audio outside CN and rewrites it to a separate CN mirror inside CN. The candidate approves only the former origin. Shared official 1.4.1 `preview()` adds Bearer and is unchanged; Desktop's anonymous policy is an explicit adapter difference.

The F1 AD public example currently exposes signed WAVs, which this MP3 profile rejects. Cover-image origins, signed Cloudflare host and CN mirror are not inferred into an audio allowlist. Direct-resolution DNS filters reject local/reserved addresses; when a user-configured proxy owns remote DNS, that remote resolution is not locally attested. Exact HTTPS origin/TLS and no credentials remain required; full network/TOCTOU proof is not claimed.

Raw SSR contains expiring signed capabilities and is **local-only**. Public evidence contains source hashes and the derived query-free table, not these capabilities; Git-only reviewers cannot rehash the complete local SSR from this evidence. Public hashed module URLs and compact excerpts support independent current-source examination. No successful anonymous sample HTTP200 is claimed: the mock-first STOP occurred before the live gate.

## Unexecuted gates and preservation

- Real sample GET **0/2**, TTS **0**; no real key/SecretStore read, user chat/card/data root accessed or audio downloaded.
- Bounded / all earlier Fish classes / new distribution / actual Windows hearing/stop / F2 packaged Launcher/JVM exit and lock reacquisition: **NOT TESTED / NOT RUN**. F1 evidence does not substitute for these F2 gates.
- Only unit MockWebServer fixtures ran; no new GUI, packaged fake service, TLS material or production root was created. No user process killed or system configuration changed.
- Ten resource samples: min available462254080 bytes, min commit headroom15254675456 bytes, max22804 pages/sec. Sampling extrema, not absolute peaks; no OOM observed. Stop reason is JUnit RED, not a reinstated memory threshold.
- Historical RED/evidence and four protected user-owned untracked files remain unchanged/unread. Five Kotlin drafts remain local; index is empty after evidence publication, but tracked worktree is **not clean**.
- F1 remains qualified accepted under139; old S2A manual records are not rewritten. WIN-AUTO-001 stays OPEN. No S3/chat voice/SaveSlot/QQ/G2/Writer/Recovery/dispatch, schema/Prompt/baseline/parity update, desktop merge or release.

Before staging, related Skill relevance was reviewed: only the separately committed F1 W22 historical clarification is accepted. An unvalidated F2 draft is not added to the accepted production feature map. Final candidate status is withheld pending the RED disposition and remaining gates.
