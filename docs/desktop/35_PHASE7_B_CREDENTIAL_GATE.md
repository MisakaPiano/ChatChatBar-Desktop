# Phase 7 B — secure credential gate

This is the user-required **pre-live credential checkpoint**, not P7-B review or Phase close.
P7-A was accepted at `37ea1e8b422c5b986c93cd43cce51f0f2d2b5d68`.
Continue on `feature/phase7-image-novelai`; do not merge desktop or create another planning task.

## Approved Prompt exception

Commit `bc2571e` adopts exactly the three official Prompt changes from upstream
`ace632cce58a3b5a57711e31990165d6a14e1c0f`. See the narrow exception in
`14_UPSTREAM_COMPAT.md`. The formal baseline remains 1.4.1 at
`5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`; parked sync/1.4.4 stays untouched.
No other upstream Prompt wording, identity-reminder drift, provider or schema change was adopted.

The subsequent extraction moves GENERAL auxiliary literals and image-description helpers to
shared `AuxiliaryPromptAuthority`, with Android `PromptTemplates` facades. All moved literals
were mechanically compared with the approved pre-extraction source. The three exception
literals were compared again against upstream `ace632c...` after extraction: exact equality.
`AuxiliaryMessageAssembler` is the single envelope owner; Android retains its AiTask facade.
Main-chat ownership and logical order are unchanged.

## A remainder closed

Text-only chat models now resolve their configured linked vision model through shared
`ImageUnderstandingService`, `EffectiveModelResolver`, image-description request parameters,
and shared Prompt/envelope authority. Desktop supplies transport/encoding adapters only.
The description is appended using the original shared builder before the user message becomes
durable, so history/restart retain the description and owned attachment. Direct multimodal
requests still send only the formal first USER image. No Desktop-only Prompt was introduced.

The formal unavailable-vision behavior remains visible: the text request proceeds without
image payload, the attachment remains stored, and the task reports the reason. Cancellation
and refusal remain terminal. Mock HTTP verifies actual vision envelope order, image inclusion,
text-request image exclusion, durable description and the unavailable path.

## Credential and live-request boundaries

- Current build UI: **管理 → 设置 → NovelAI → 输入凭据 / 替换凭据 → 安全保存**.
- Password input is transient and cleared on submission. Controller state exposes status only;
  loaded credentials are never copied back into the input field.
- `DesktopCredentialKey.NovelAiToken` uses existing WindowsSecretStore/current-user DPAPI.
  The credential stays outside app-data JSON, snapshots, repository configuration and artifacts.
  Settings edits use existing AppSettings fields, without schema changes.
- Saving/loading credential status performs **no network request**. There is no automatic smoke
  on startup or after saving. No real NovelAI account or image request has been sent at this gate.
- Runtime hydrates the credential just before execution, uses a fixed HTTPS host/path allowlist,
  disables redirects and connection retries, and calls shared HTTP with zero rate-limit retries.
  Provider error text and exception causes are not exposed by the Desktop credential/runtime
  boundary. The shared service has opt-in safe error events; Android's default behavior remains.
- User confirmation is required before calling `liveSmoke`. No confirmation has been received.
- The automation guard admits V4.5 Full only, text-to-image without guidance/reference assets,
  one sample, 1–28 steps, and Normal Square 1024×1024. Other modes remain forbidden for automatic
  live tests. This is intentionally narrower than the full production request API.
- Fresh account preflight requires active Opus and shared cost estimation of zero Anlas.
  Missing/ambiguous/failed account evidence sends no image request. Postflight checks membership
  and unchanged Anlas before publishing the smoke result.
- A cross-process file lease serializes requests. The durable journal is in the Windows credential
  root's `phase7-live-safety` directory, outside selected app-data roots/backups. It reserves a slot
  before image dispatch, requires ≥30 seconds between starts and at most 2 starts in 5 minutes,
  and permits at most 8 starts across the entire Phase 7. It has no UI reset path.
- Failed, cancelled, corrupt or crash-pending records block subsequent live testing. Rate limits,
  auth and account anomalies stop immediately. No fallback retries or silent counter reset.
- `DesktopTaskRuntime` owns NovelAI jobs and Stop/shutdown cancellation. Account and generation
  HTTP cancellation propagate to their calls; task failures contain only safe status text.
- Successful smoke output uses new-owned-resource → durable authority. The Desktop-only
  `desktop_phase7_novelai_smoke` singleton preserves image path and exact resolved seed/recipe
  for later local reuse. Ambiguous publication retains the resource. This is not Studio history.

Official eligibility sources consulted for the conservative guard:
[subscription](https://docs.novelai.net/en/subscription/),
[steps](https://docs.novelai.net/en/image/stepsguidance/).

## Focused automated evidence

- Shared image/runtime policies and main-chat assembler: **89 tests, zero failures**.
- Desktop NovelAI safety, real Windows DPAPI with fake credentials, SecretStore, image ownership,
  linked vision, TaskRuntime and PrimaryChat controller: **71 tests, zero failures**.
- Android auxiliary envelope, Prompt facade, image-description parameters and current-turn order:
  **38 tests, zero failures**.
- Shared, Desktop and Android affected compilation passed. `git diff --check` passed.
- Mock coverage includes zero HTTP before confirmation, token hydration, JSON/state/exception
  nondisclosure, auth/ambiguous-account rejection, 429 single attempt, free shape restrictions,
  cross-instance lock, interval/window/phase cap across reopen, corrupt/pending records,
  active HTTP cancellation, Anlas anomaly stop, resolved seed and owned-output restart.
- No preset content assertions, real credential inspection, real NovelAI request, Phase-close
  full regression, Android device interaction or manual acceptance were performed.

## Resume after credential confirmation

The user enters the credential directly in the current Desktop UI and confirms completion in
the same task. Never request its value through chat, environment variables or repository files.
Before an allowed smoke, use the durable guarded runtime and existing TaskRuntime; do not call
the shared generation service directly from a test/script or enable production retry loops.
Reuse a successful output for later persistence, preview, metadata and history checks.

P7-B is still in progress. Remaining B work includes full P7 settings/session controls,
production runtime adapters beyond the guarded smoke, Prompt Designer/research adapters,
Danbooru catalog/dictionary/tag suggestions, V5 authoritative routing and allowed live smoke.
Then form a clean pushed checkpoint and report `READY FOR PROJECT P7-B REVIEW`.
Studio and later Phase-7 scope remain after that review; no Phase-close docs reconciliation here.

Current-build handoff: launched via :desktopApp:run; Windows process inspection confirmed a ChatChatBar top-level window and no launcher/JVM error. The application is left open for credential entry. This is not manual acceptance or a distributable gate.
