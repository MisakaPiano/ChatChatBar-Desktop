# STUDIO-CREDENTIAL-ERROR-PATH-001

**OPEN / PRODUCT ERROR RECOVERY REVIEW REQUIRED** — 2026-10-20 JST.

This is a separate product issue registration and test plan, not an implemented production fix. Frozen P8 source/package remains `008343bb26d2eb76c23cae9b98132af828a9f447`. R2 evidence remains `39fdc97d1b34591fe2f61329788f239ddff2b0d3`.

## Observed and source-confirmed

- R2 user sequence: Chat showed the unsafe-credential error → user switched to Studio → error dialog appeared → user acknowledged it → application exited. It was not the main-window X or a signed normal-close PASS.
- `DesktopNovelAiStudioPanel.kt:72` calls `controller.availableModels()` inside `produceState` without a local error-state boundary. The initial `emptyList` is loading state, not evidence that an error may be treated as an empty successful catalog.
- `DesktopNovelAiStudioController.kt:238` delegates to `resolver.availableChatModels()` before removing keys from returned presentation copies. Exceptions occur before that projection.
- Shared `EffectiveModelResolver.kt:145` reads `models.getAllModels()`. `ModelRepository.kt:58–61` refreshes the cache by hydrating persisted entities. `DesktopRepositoryCredentialPolicies.kt:71–77` intentionally rejects nonblank persisted model keys and loads valid runtime keys only from the typed protected store.
- R2 stderr contains `UnsafeDesktopPlaintextCredentialException` on AWT-EventQueue-0 with LaunchedEffect context, followed by CLOSE_REQUESTED and full drain/ownership-release traces. Exit codes 0/0 prove process completion, not correct error recovery or user-requested exit. The exact global Compose dialog acknowledgment → close callback was not instrumented; that remaining causal detail is UNKNOWN.
- The panel also has asynchronous authentication and load entry points (`produceState` near line57, `LaunchedEffect` near line58) that should be considered in the narrow future audit. This registration does not authorize rewriting all Studio behavior.

Git blobs/line excerpts are recorded in `studio-source-analysis.json`. No global error handler, Main close path, controller, policy or Studio production source was changed.

## Candidate narrow repair boundary (Project contract required)

Use an explicit model-list load state with a recoverable credential error. Surface that recovery is required; distinguish loading, valid empty results, stale results and failed reads. Catch only at an appropriate UI/controller ownership boundary, rethrow cancellation, preserve the security refusal and original data. An error acknowledgment dismisses the notice; it must not be interpreted as a user request to exit. Any recovery navigation must use existing explicit credential recovery/settings ownership, separately reviewed before implementation.

Do not ignore the exception, erase model records, silently migrate plaintext keys, return a fabricated empty list, relax Desktop credential policy, send a probe request, or force process exit. Do not log keys, model `toString`, authorization headers, full request/response payloads or protected blobs.

## Future focused verification plan — NOT EXECUTED here

| Case | Required assertions |
|---|---|
| Valid protected model | Real repository/policy hydrate; Studio list succeeds; UI projection contains no secret; no network request |
| Persisted plaintext model | Real policy still throws; Studio displays recovery-required state; no model, JSON or SecretStore mutation; no fake empty-success state |
| Malformed/unreadable protected envelope | Explicit safe error, preserved files/keys and previous valid state; no fallback credentials or request |
| Dialog acknowledgment | Dismisses error only; main window remains usable, task/runtime owners and root lock remain held; no CLOSE_REQUESTED from acknowledgment |
| Explicit normal X after an error | Existing shutdown drain and root-release ordering unchanged; distinguish actual user close from error acknowledgment |
| Cancellation/tab leave | No uncaught cancellation, late model-state publication, duplicate dialog or implicit model refresh loop |
| Explicit recovery and retry | Authorized recovery only; fresh real hydrate after success; unchanged unrelated models/settings; no hidden migration |
| Sanitization | Top-level and observable nested errors contain no injected credential/Authorization/Cookie/body; stable error classification retained |

A later exact Windows package must verify the error dialog/window behavior with user participation and process/lock observations. Offscreen assertions or R3's successful dependency read do not close this issue. WIN-AUTO-001 and WIN-BRAND-001 remain separate.

## R3 scope actually verified

R3 invoked the real `WindowsSecretStore`, `DesktopModelCredentialPersistencePolicy`, `ModelRepository`, and `EffectiveModelResolver.availableChatModels()` dependency used by Studio. This proves the repaired synthetic models no longer fail that dependency due to credential format. It did not instantiate Studio Compose, invoke the exact controller presentation projection, repair global error handling, or re-test error acknowledgment. This product issue stays OPEN.
