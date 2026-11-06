---
name: chatbar-model-request-runtime
description: Maintain and diagnose ChatBar model resolution, OpenAI-compatible request construction, provider-specific parameters, API authentication, cleartext local-model policy, thinking/reasoning controls, streaming transport, retry classification, output truncation, connection tests, and response parsing. Use for model fallback bugs, HTTP 400 compatibility errors, empty or truncated responses, unexpected one-shot failure, excessive retries, SSE stalls, stuck stop controls, user-cancellation persistence, timeout or stream-reset failures, auth differences, and auxiliary-model request issues.
---

# ChatBar Model Request Runtime

`ProxyAwareClient.builder` and its two-argument `modelApiBuilder` can receive a proxy-settings supplier; the original one-argument model builder retains its default dynamic application policy and trailing-lambda API. Update owners freeze a currently saved policy per new check/helper call, without changing other owners' restart settings. The shared selector still resolves SYSTEM properties/default selector and bypasses loopback; never reinterpret every system route as HTTP. The physical regression now distinguishes NAI from the update checker and verifies typed signed-helper routes separately.

- `ModelConfig.desktopContextWindowTokens` is an optional positive user-declared capacity used by Desktop budget projections, not a transport parameter or enforced context/output cap. Model templates preserve the optional extension and omit its absent default. `OpenAiChatRequestSerializer` remains unchanged; final-wire equality with/without capacity is covered by `PromptBudgetEstimatesTest`. Do not infer this capacity from `maxOutputTokens` or `ChatSession.contextWindowSize`.

- Desktop proxy physical routing uses `DesktopProxyPhysicalRoutingTest`: fresh JVMs load a persisted synthetic root through the production startup ingress, then record actual CONNECT/SOCKS bytes from `secureNovelAiClient`/account requests. `proxyPackagedRoutingTest` builds the production app-image and places its JARs before the test probe, checking actual class origins; its XML is separate from the normal suite. A chosen proxy-selector route alone is not physical-socket evidence. The probe replaces business UI and does not establish native GUI acceptance.
- `runDesktopMain` installs `DesktopSelectedRouteSocketFactory` before startup creates business/network owners. It makes HTTP/DIRECT raw sockets with `Socket(Proxy.NO_PROXY)` so the JDK cannot run a second environment proxy selection. OkHttp retains its own proxy selector and explicit SOCKS socket path, plus the original TLS/retry policies. Shared `ProxyAwareClient.platformSocketFactory` is optional and remains null on Android; this hook is not a global DIRECT application setting.
- `HttpConnectionTraceSnapshot.selectedProxyPort` is the OkHttp-selected port, never proof that CONNECT bytes were sent there. Existing proxy-connect and TLS phases keep that distinction; the physical fixture records actual bytes separately.

- Desktop numeric usage capture is separate from bounded raw diagnostics; see `chatbar-desktop-provider-usage`. `ProviderTransportDiagnostics.onAttemptStarted` marks physical attempts without altering wire bodies or retry policy. Main-chat retries and isolated stream/nonstream calls emit this hook immediately before dispatch. Keep parent task/session/card identity separate from display namespaces.

- Desktop request text estimates are diagnostic only: `DesktopTransportDiagnosticRecorder.onRequest` passes the complete pre-scrub/pre-truncation serialized body to shared `WirePromptBudgetEstimator`. Logical sections apply only when actual adapted role/content messages match and contain no extra message fields; overridden/merged messages stay unattributed. Text counts exclude images, audio, message overhead and tools/response-format structure. Invalid wire yields unavailable, not zero. Provider usage is independent. The estimator never changes request parameters, retry policy or prompts, and its numeric report contains no message body.

Separate model selection, request construction, transport, and output parsing. A successful HTTP call can still fail at stream or protocol parsing.

- FORMAT_CARD creation uses shared `FormatCardAutoFillGenerator`/`FormatCardAutoFillGateway`; character fill/rewrite uses `CharacterCardCandidateGenerator` and `streamCharacterCardCandidate`. Official147 character generation/repair, research PLAN/SUMMARIZE, appearance JSON, Fish text and Memory/RAG inherit the selected model's output cap, thinking/effort and custom fields. Character generation no longer injects512/256 budgets; research policies retain600-second inactivity with nullable control overrides. The generic isolated helper and explicit isolated callers retain their own defaults. Actual final-wire tests include `SelectedAuxiliaryModelParametersTransportTest` and `CharacterCardAiRequestParityTest`.

- Desktop formal chat exposes `DesktopChatDebugPanel` through the existing model-details disclosure. `DesktopTransportDiagnosticsOwner` holds bounded, scrubbed, session-filtered memory-only records; `DesktopRealChatRuntime` records the actual plan on its existing recorder and actual serialized provider body. System view derives from that body, never by rebuilding a preview. Output/reasoning and usage survive eviction of raw SSE fragments; expose truncation. Clearing one session must not resurrect in-flight records. Maintenance remains explicit inside this panel, owned by `DesktopChatMaintenanceController`, separate from log reads.

- `DesktopRealChatRuntime.generate` receives the operation's `DesktopChatGenerationControl`. Explicit Stop persists a nonblank interrupted reply under `NonCancellable` even when cancellation reaches collection as a generic wrapper; ordinary shutdown/caller cancellation without that stop flag still discards the partial draft. Regeneration appends exactly one alternative to the same row. `DesktopRegenerationTest` forces wrapped delivery and checks both explicit and generic paths; do not replace these distinctions with an unconditional cancellation save.

- Desktop memory/RAG uses shared `MemoryRagTextClient` and `OpenAiStreamingTransport.streamMemoryRag`, preserving the auxiliary message envelope while defaulting parameter isolation to false. Explicit isolation still omits output limits and protects envelope fields. `DesktopMemorySelectedModelParametersTest` covers real planner/summary/repair/episode/RAG HTTP; usage/head and cancellation tests retain original ownership. RetrievalPlanner's production total deadline is120s; its dispatcher seam lets `RetrievalPlannerDeadlineTest` verify20s success and exact120s cancellation on virtual time. Android adapts AiTaskRun without a second builder via its existing factories. Desktop text retains a1MiB response budget and terminal refusal/cancel/budget handling; embedding's one-shot/no-replay and shutdown drain remain separate.

## Shared isolated format repair transport

- `OpenAiStreamingTransport.streamFormatRepair` delegates to `FormatRepairAuxiliaryTransport` and alone assembles FORMAT_REPAIR/REPAIR's frozen eight messages from rules plus full text. It reuses the existing client, parser, serializer and refusal policy; `streamMainChat` remains unchanged.
- Repair follows upstream1.4.7 selected thinking/effort at its own callsite; Desktop retains isolated custom-field/envelope protection and the helper's other callers retain their defaults. It keeps 600-second socket and meaningful-output inactivity timeouts, no absolute call deadline, redirects/authenticator retries/connection retries disabled. Heartbeats and usage do not refresh inactivity. Reasoning is only timeout/empty-result evidence, not emitted repair text.
- `StreamEvent.Error` preserves shared failure types; `AuxiliaryProtocolException` distinguishes malformed SSE, early EOF and unconfirmed finish reasons. Observed transport failure after finish is still an error. A bounded finish grace retains trailing usage. Coroutine cancellation cancels EventSource without inventing user Stop or persistence.
- `FormatRepairAuxiliaryTransportTest` uses the B1 golden and MockWebServer; its localhost TLS identity is generated at runtime with the current JDK keytool, loaded into memory, and removed from the dedicated temporary directory in finally. No fixed private-key fixture is tracked. Short timing/client seams are internal; no UI, task coordinator or notice strategy is implemented here.

## Desktop linked image understanding

- World-book1.4.7 AI uses `WorldBookAiGateway`, `streamWorldBookAi` and `AndroidWorldBookAiGateway`: selected-model cap/thinking/effort/custom parameters without isolation or research false/low overrides, default120s socket/inactivity budget, no forced JSON response or character thinking budgets. Six-stage actual Android/Desktop HTTP parity is `WorldBookAiRequestParityTest`; four-operation legacy-thinking/reasoning-only requests are `WorldBookModelParametersTransportTest`.

- Shared `domain/chat/ImageUnderstandingService.kt` owns direct-image vs linked-vision resolution, description indexing and unavailable/terminal behavior. Android injects `StreamingChatService::describeImageStreaming`; Desktop injects `DesktopAuxiliaryImageUnderstanding`, consuming shared GENERAL envelope/image Prompt and `ImageDescriptionRequestPolicy.forImageDescriptionRequest`.
- Character image-to-appearance text is separate: `CharacterAppearanceImageCore` resolves the current default chat model or exact linked multimodal model. `streamCharacterAppearance` retains JSON output and120-second socket/inactivity budgets while inheriting selected model parameters, matching official147. General image description keeps its separate policy.
- Desktop appends the shared user-image description before durable user-message persistence; unavailable vision remains an explicit task notice and retains the owned attachment, while refusal/cancellation terminates the request. No separate Desktop Prompt literal is introduced.

- Desktop AI Design error presentation: `DesktopNovelAiTextTransport` maps shared provider events to fixed safe categories without exposing HTTP bodies; refusal/cancellation retain their terminal exception types. `DesktopDesignPresentation.desktopDesignFailure` and `DesktopNovelAiStudioController.runDesignTurn` own the user-facing category and failed-turn persistence, preserving the shared Designer and retry context.

## Desktop formal voice auxiliary owner

- `DesktopChatVoiceRuntime` owns one lazy `OpenAiStreamingTransport` for Fish translation/tagging. Its explicit `closeAndDrain()` follows collector cancellation/join before root release. Fish keeps neutral messages and the selected model's cap/thinking/custom parameters; the original semaphore, retry and numeric logical-request owner remain with the shared service and Desktop gateway.

## First Read

- Model selection and fallback: domain/model/EffectiveModelResolver.kt
- Chat model availability and send gating: ui/chat/ChatViewModel.kt
- Shared HTTP client, proxy, cleartext policy, Authorization helper: domain/ProxyAwareClient.kt
- Shared `ProxySelectorDelegate` checks literal loopback (localhost, 127/8, numeric IPv6) and `http.nonProxyHosts` whole-host `|`/`*` patterns before manual HTTP → JVM HTTP → JVM SOCKS → default selector. Host matching never resolves arbitrary names and does not bypass all private LANs. Desktop launch args opt into `java.net.useSystemProxies=true` at VM startup; this app-local setting is distinct from explicit JVM/manual proxy tests and does not prove a particular Windows proxy/PAC configuration works. Routing regressions: `ProxyLocalRoutingTest` uses parallel local origin/proxy HTTP and real IPv6 loopback; no provider calls.
- Desktop model editor's `MODEL_ENDPOINT_NOTE` / `LOCAL_MODEL_NOTE` explain the service-defined OpenAI-compatible base URL, optional `/v1`, exact model ID, `/models` data contract, manual entry, explicit HTTP permission and blank local key. They do not describe in-app model deployment. Packaged system-proxy opt-in belongs in `desktopApp/build.gradle.kts`, not a late `System.setProperty` inside a client.
- Chat/text request body, SSE parsing, retries, thinking fields: domain/chat/StreamingChatService.kt
- Auxiliary task identity, neutral confirmation, refusal evidence: domain/prompt/AiTaskRequests.kt; scene text/symbol index: PromptTemplates.aiTaskProfile. Global request inspection: ui/manage/AiRequestLogsContent.kt and utils/DebugLogManager.kt.
- Dynamic task thinking adaptation: domain/chat/ThinkingRequestPolicy.kt. Inspect original model before isolated parameters are stripped. Existing enableThinking or custom enable_thinking/thinking_budget/max_thinking_tokens selects legacy controls; otherwise explicit effort selects effort, then existing supportsDisableThinking selects legacy, and unknown models default to effort. No new model setting or name-based capability list.
- Cleartext HTTP strict-template role adaptation: domain/chat/CleartextHttpChatTemplatePolicy.kt
- Final serialized request diagnostics: utils/DebugLogManager.kt and ui/chat/DebugLogDialog.kt
- Connection-test caller: ui/manage/ManageViewModel.kt. Tracked application-scope job and isApiTesting drive the settings stop button; cancellation bypasses error conversion and prevents the next probe. EmbeddingService uses cancellable OkHttp callbacks, cancels the active call, and closes responses.
- Model editor discovery: ui/model/ModelEditViewModel.kt and ModelEditScreen.kt call domain/model/ModelDiscoveryService.kt for OpenAI-compatible `GET {baseUrl}/models` (`data[].id`). Reuses effective key and cleartext/proxy policy; no redirects or hidden retries. URL/key edits cancel and invalidate results. Search opens ModelPickerDialog.kt and fetches only when no successful list exists in the editor; refresh appears after success, retains the previous list with explicit stale/error status on failure, and never reopens a dismissed picker on completion. Picker has name-derived series filters (not capability metadata), multi-keyword search, name sorting, and selected-ID pinning. Selection only changes model ID and fills a blank display name. Listing does not prove chat capability; unsupported/empty/error results remain visible with manual entry available.
- Embedding-specific transport: domain/rag/EmbeddingService.kt
- Shared Android foreground/background protection: use chatbar-background-work-runtime.
- Callers with fixed auxiliary parameters: Android domain/card/CharacterAutoFillService.kt, CharacterRewriteService.kt, CharacterAppearanceImageService.kt and domain/voice/FishAudioTagService.kt; sharedCore domain/memory/MemoryAiGateway.kt and domain/rag/RetrievalPlanner.kt. NovelAiPromptDesigner.kt and NovelAiTagResearchService.kt inherit the selected model's thinking configuration.
- Tests: ModelConfigurationTest.kt, CleartextHttpPolicyTest.kt, StreamingChatServiceThinkingTest.kt, StreamingChatServiceTerminalTest.kt, InterruptedReplyPolicyTest.kt, and request-body tests near each caller

Use chatbar-message-format-repair for repair state behavior, chatbar-image-generation-runtime for NovelAI image HTTP generation, and chatbar-fish-audio-voice for Fish tag protocol, confirmation, TTS, and playback behavior.

## Resolution Rules

- Post149 shared `SiliconFlowChatTemplatePolicy` is the exact fixed12e neutral request adapter for official SiliconFlow hosts and the DS3.2 family. `OpenAiChatRequestSerializer` invokes it after building the complete ordinary body, shared by main, auxiliary, vision and probe producers. Existing identity tails stay unchanged; a final user is retained, while an actual assistant tail is quoted with all trailing roles/content. Pure-policy and actual serializer tests cover custody/idempotence/native images/host-model boundaries and existing auxiliary envelope preservation. This serializer change does not adopt315's pending Prompt deletions or establish real-provider verification.

- Desktop `DesktopNetworkProxyController` persists `ApplicationProxySettings` in the selected data root's `desktop-settings.json`, outside versioned Program. Production startup installs it before network owners; Save requires a normal restart, leaving current requests/pools unchanged. SYSTEM retains existing manual/JVM/default-selector routing, with HTTPS-specific JVM settings before the legacy HTTP path; HTTP/SOCKS/DIRECT are explicit app overrides after loopback bypass. Invalid persisted settings pause networking visibly; selector failures never silently become direct. Optional route inspection runs on IO, suppresses repeated clicks and reports separate account/image/local routes; PAC/WPAD resolution may itself access configuration services, so this is not an offline connection test. No credentials or system/VPN settings are edited.

- Distinguish unset selection from explicitly stale selection.
- Apply fallback only where feature policy defines unset as follow-default.
- Keep selected custom, preset, embedding, and auxiliary model sources distinct. No dedicated retrieval model exists: ChatViewModel passes the current chat model to RetrievalPlanner; character/world-book research uses the operation's selected generation model.
- `ModelRepository.initialize` migrates the retired `retrieval_model_config/default` into ordinary model storage before publishing models. Preserve ID/credentials for explicit auxiliary bindings, expose it as a normal chat model, clear preset provenance, save before removing the old record, and serialize initialization. Colliding ordinary IDs retain priority; the legacy configuration gets a stable separate ID. Restoring bundled models only restores chat models and the embedding model, never the retired slot.
- Prefer each model's own API key. Use the global default key only when an HTTPS or otherwise authenticated model has a blank key.
- For an existing chat, compute usability with `status(session.modelId, appSettings)` and resolve the send model from the same ID. Use unscoped `status(appSettings)` only for flows that intentionally follow the app default, such as new-session gating.
- Gate every `ModelConfig` request, including image-prompt design, through `hasConfiguredAuthentication`; do not infer usability from raw `apiKey` blankness.
- Resolve effective API keys once. For allowed cleartext HTTP local models, a blank model key means no Authorization header and must not inherit the global key.
- Never emit an empty Bearer header.

## Request Rules

- Map model/provider capabilities before adding request fields.
- Do not blindly send max_tokens, max_completion_tokens, thinking_budget, reasoning_effort, and thinking controls together.
- `ModelConfig.outputTokenParameter` selects exactly one output-token key. Auxiliary isolated tasks strip sampling, stop, penalties, token overrides, and thinking parameters without adding task-owned output limits.
- Main chat and auxiliary callers impose no computed or fixed output-token limits. Non-isolated requests preserve explicit ModelConfig output settings; isolated tasks strip both custom aliases and maxOutputTokens. New model templates and bundled presets omit output limits. Reply-length prompts, context budgets, and thinking controls remain independent.
- Main chat uses resolved `ModelConfig.formatPromptPosition` to place combined current-turn format/length/speaker requirements after CCB contract confirmation and before character for `START`, after the current user and character post-history for `END`, or both. Old model data defaults to both positions.
- Official147 long-term memory preserves explicit selected-model thinking/output/custom fields without provider-name guesses or forced off controls. JSON Mode still follows supportsJsonMode; planner output stays non-JSON.
- Task budget/enable overrides become reasoning_effort=low for effort models; disableThinking or enableThinking=false wins over other overrides and becomes none. Legacy models retain task budgets and switches, suppressing effort during task overrides. Ordinary chat without task overrides preserves configured fields. This uses configuration evidence, not API probing; unsupported effort values remain visible request errors. Image description changes configured effort to none while retaining legacy sanitization.
- `streamText`, `completeText`, and `completeTextStreaming` accept an optional per-request `readTimeoutSeconds`; callers with legitimately long silent reasoning can extend inactivity timeout without changing the shared 120-second default. Character-card AI owns a 600-second override across generation, repair, planning, and research cleaning.
- Connection probes retain their separate withoutOutputTokenLimit/thinking-off policy. Official147 Fish translation/tag requests preserve selected limits and thinking; shared typed retry and manual cancellation remain. Provider finish_reason=length is still an explicit incomplete-output error.
- Main-chat logical tail is real current user, optional strong-prompt system, CCB acknowledgement assistant, then CCB identity-reminder user. For opted-in `http://` model requests, preserve the first system role and serialize non-trailing later system roles, including the strong prompt, as assistant. The final CCB reminder remains user. Never end a cleartext request with an adapted assistant prompt. Do not apply this adaptation to HTTPS or when cleartext access is disabled.
- Treat Debug Request JSON as the final `buildRequestBody` output after cleartext role adaptation, not the logical `ChatApiMessage` list assembled by the caller.
- Treat strict JSON or protocol parsing as a separate failure layer from HTTP transport. Preserve raw diagnostic evidence within privacy limits.
- Use `ModelRequestException` as shared request-failure evidence: missing HTTP status, 408/425/429, and 5xx are retryable; 401/403 identify authentication failure; other HTTP statuses are terminal. Preserve status, trace ID, `Retry-After`, and root cause when wrapping it.
- Assign retry ownership before adding loops. Shared request code performs one request and reports a typed result; a feature task may own bounded transport retries and separate output/parse/validation attempts. Never count a retryable request failure against an output-quality budget, and never stack caller retries over hidden shared retries.
- Auxiliary callers explicitly supply AiTaskContext(kind, stage). StreamingChatService assembles the confirmation once; completeTextStreaming delegates to streamText. AiTaskRun groups nested stages and retries; each actual request gets a fresh request ID. Main chat and connection probes bypass the auxiliary envelope. BuildConfig.AI_PROMPT_SOURCE_SHA256 fingerprints normalized PromptTemplates source; per-scene fingerprints include kind and stage, never user input.
- Auxiliary transport uses the eight-message GENERAL_* structure (system/assistant/user/assistant/user/assistant/assistant/user). All feature systems are merged into its first system; actual inputs/history occupy the fifth message. HTTP adaptation leaves this single-system structure unchanged. Logs include GENERAL_* template symbols and estimate confirmation plus multi-input heading overhead; no extra model call is made.
- IMAGE_DESIGN/GENERATE preserves the actual non-system input roles inside the GENERAL_* envelope; this variable-length block retains planner/revision assistant history. Input-heading estimates are omitted for that path. JSON repair and other scenes still merge inputs. The sole system and final user keep HTTP adaptation stable.
- AiTaskRefusalException is terminal through repair, retry and research fallback boundaries. StreamEvent.Error retains failureKind/cause; collectors must preserve asException() when forwarding failure. Do not turn a refusal or cancelled task into a fallback brief or reusable final-output checkpoint.
- withAiTaskRun records final processing/validation exceptions against the task's last request. Starting a REPAIR stage marks the immediately preceding successful response of the same task/kind as a format failure; existing transport/refusal errors retain their classification.
- Truncation remains an output failure; memory retries preserve stage/count diagnostics without adding or growing output-token limits. Cancellation bypasses retry and wrapping.
- Keep Fish voice-tag calls on the shared streaming text service with thinking disabled. Keep strict ID/tag/text validation and confirmation policy in chatbar-fish-audio-voice rather than weakening shared stream parsing.
- Verify current provider behavior against official provider documentation when compatibility may have changed.

## Streaming Diagnosis

- Desktop main chat records `Completed` metadata before assistant-body validation. Blank body with `finishReason=length` returns the existing typed truncation message and failed task with finish evidence; a nonblank partial remains persistable and shared automatic-image policy rejects its `length`. Blank stop/Done and explicit-Stop/generic-cancellation policies remain separate. `DesktopTaskRuntimeTest` covers both length paths; the direct cancellation fixture owns child coroutines through teardown and drains the POST before holding SSE open.
- Auxiliary `streamText` observes coroutine-scoped `domain/chat/AiStreamProgress.kt`; nested research/repair inherit the operation observer. UI uses `ui/components/AiStreamProgressPanel.kt` for bounded, separately rendered reasoning/content, model identity, inactivity age and per-request history. Observers never enter model messages or candidate/checkpoint data. Final/error/cancel flush pending previews.
- `streamText` has a meaningful-output inactivity watchdog in addition to socket read timeout, using the same per-request duration. Only nonblank reasoning/content or finish reason refreshes it; SSE heartbeats and usage-only events cannot keep a stalled request alive forever. Preserve whitespace-only content deltas in the actual output.

- Main `streamChat` exposes optional `onReplyCompletion` evidence before its terminal event, preserving finish reason, refusal/content-filter markers, and transport failure after a finish reason. Legacy `Done` semantics remain unchanged; automatic images require explicit `stop` without refusal or transport failure plus local content checks, with no separate AI judgment. `[DONE]` alone cannot authorize automatic images.

- Auxiliary `streamText` reports `finish_reason=length` as `StreamEvent.Error`, preserving preceding deltas and emitting no `Done`; callers must reject that error even when partial content exists.
- HTTP 200 proves stream establishment only.
- stream was reset: CANCEL after 200 is an HTTP/2 transport failure, not a 200 business error.
- A fixed read timeout measures silence between bytes/events; reasoning models can hit it after emitting a short reasoning prefix.
- Treat either `[DONE]` or a non-null `finish_reason` as terminal success evidence. Because `finish_reason` can precede a usage-only chunk and `[DONE]`, keep a short bounded grace before cancelling transport; still deliver one terminal event only. A peer close without either signal is an explicit protocol error.
- SSE callback flows use an unbounded handoff buffer because provider callbacks cannot suspend. Never ignore terminal delivery behind the default 64-slot callbackFlow capacity.
- Keep main-chat user stop distinct from transport failure. Persist raw assistant drafts with nonblank body or reasoning through the normal repository path; reasoning-only regeneration selects an empty-body version. Make the save non-cancellable and idempotent across completion races, refresh the durable timeline while the same-ID streaming item remains, then clear streaming UI state and release the responding gate. Never filter a normal persisted draft by the active streaming ID. Interrupted drafts skip full-reply-only post-processing.
- `streamText` has no retry; it logs the final serialized request and uses a 250 ms terminal grace to retain usage after finish_reason. Refusal/filter, truncation, unparseable chunks, server errors, empty/reasoning-only output, early EOF and cancellation remain distinct failures. Array-form content and nullable choice/delta/usage objects remain supported. completeText uses the same refusal policy and records non-stream usage.
- DebugLogManager mutations use request IDs (legacy session lookup remains compatible), immutable snapshots, nullable provider input/output/cache usage, and separately labeled estimates. Logs are process-local, limited to 100 entries and 4 Mi characters overall, with 65,536-character text previews and visible truncation. Known request keys and image bytes are redacted; logging never mutates the outbound body.
- Distinguish user cancellation, background-protection cancellation, client timeout, peer reset, proxy/VPN reset, and parser failure.
- Record timestamps for stream open, reasoning delta, content delta, terminal event, request ID, protocol, and exception class when improving diagnostics.
- Do not retry after partial visible output without a duplication and billing policy.

## Workflow

1. Identify actual selected model for the failing feature.
2. Capture final endpoint, headers presence, request keys, stream/non-stream mode, and parser contract.
3. Compare working chat and failing auxiliary request bodies field by field.
4. Reproduce with request-builder tests before changing transport.
5. Trace retry counters and exception wrapping across every layer before deciding retry owner.
6. Fix the shared lowest owner when all callers should inherit behavior.
7. Keep feature-specific parsing, retry budgets, and fallback decisions in feature owners.

## Regression Matrix

- Unset, valid explicit, and stale explicit model IDs.
- Preset and custom models with local/global API keys.
- Valid session model with its own key while the app default model or global key is unavailable.
- Allowed HTTP local model with and without its own key; HTTPS inheritance.
- Thinking enabled, disabled, reasoning effort, and custom conflicting fields.
- Chat streaming, auxiliary text streaming, and connection test.
- Cleartext HTTP enabled, HTTPS with cleartext enabled, and HTTP with cleartext disabled role serialization.
- HTTP error, empty content, reasoning-only content, malformed JSON, timeout, peer reset, user cancellation before content, cancellation after partial content, and cancellation during final persistence.
- Exact request counts for retryable and non-retryable failures; output failures must not consume transport budget. Verify `Retry-After`, cancellation passthrough, output-limit omission and final stage/attempt diagnostics.

## Stop Conditions

- Do not assume two UI features use the same model.
- Do not diagnose a parsing failure as an API failure without response evidence.
- Do not duplicate provider retries or fallback logic in multiple callers.

- Shared `domain/chat/StreamEvent.kt` supplies the unchanged auxiliary stream event contract. NovelAI design/research use `NovelAiTextTransport`; Android delegates to StreamingChatService with original IMAGE_DESIGN/IMAGE_RESEARCH task contexts, while Desktop adapts shared provider events and enforces refusal/empty/truncation completion checks.
