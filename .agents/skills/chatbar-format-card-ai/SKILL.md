---
name: chatbar-format-card-ai
description: Maintain ChatBar character-specific format-card AI creation, creative guidance and example, streaming candidate preview, tool validation, and blank-new-card apply. Excludes rewriting existing format cards and chat message format repair.
---

# ChatBar Format Card AI

## Entry Points

- `ui/format/FormatCardEditScreen.kt`: new-card fill entry.
- `ui/format/FormatCardAutoFillDialog.kt`: character/model selection, optional request, fullscreen request editor, candidate and raw-output preview.
- `ui/format/FormatCardEditViewModel.kt`: form lifetime, fresh source lookup, background job, cancellation, apply and draft save.
- Shared `app/sharedCore/.../domain/card/FormatCardAutoFillGenerator.kt`: draft, parser, terminal collection, blank-target policy and generation/single-repair algorithm. `FormatCardAutoFillGateway` carries GENERATE/REPAIR without Android dependencies.
- Shared `domain/prompt/FormatCardAutoFillPromptAuthority.kt`: verbatim `FORMAT_CARD_AUTO_FILL_*` and builders. Android `PromptTemplates.kt` retains exact facades and its header directory; Android build fingerprint includes this owner.
- Android `domain/card/FormatCardAutoFillService.kt`: fresh exact model/auth resolution and one AiTaskRun, adapting the shared gateway to `StreamingChatService.streamText`.
- Desktop `app/desktopApp/.../DesktopFormatCardAutoFillController.kt` and `DesktopFormatCardAutoFillPanel.kt`: editor entry, saved-character/model/request form, full request editor, reasoning/raw/repair/candidate preview, cancellation, request logs and explicit apply. `DesktopAppContainer` wires the owned transport and drains it after editor jobs.
- `ChatBarApp.kt`: service wiring. Tests: `domain/card/FormatCardAutoFillServiceTest.kt`.

Unqualified Android paths are relative to `app/app/src/main/java/com/example/chatbar/`; shared paths use `app/sharedCore/src/main/kotlin/com/example/chatbar/`. Parser/policy/collector tests moved to sharedCore `FormatCardAutoFillServiceTest`; generator/HTTP tests are adjacent. Android `FormatCardAutoFillRequestParityTest` compares actual final HTTP bodies from both transports; Desktop controller/editor/scene tests cover draft and lifecycle boundaries.

## Feature Contract

- The ViewModel generation job carries `AiStreamProgress`; `FormatCardAutoFillDialog` shows `AiStreamProgressPanel` for generation and repair, keeping provider reasoning separate from candidate JSON and raw-content validation.

- FORMAT_CARD requests use GENERATE/REPAIR contexts under one AiTaskRun. collectFormatCardAutoFillText propagates StreamEvent.Error.asException() so typed refusal cannot enter the single JSON-repair attempt. Actual request bodies and usage are available in the global AI request logs.

- Only new cards with blank content and no tools can be filled. A supplied name survives generation and apply. The entry and final apply both use the blank-target gate; existing cards have no AI entry.
- Candidate contains name, content, and ordered tools. Applying changes editor fields and schedules its ordinary draft save; entity save remains separate. No character/session binding is persisted.
- One saved character card plus optional request is the entire source. Generation re-reads the selected card. Structured mode sends every character's text fields; freeform mode sends only active freeform text. No image, voice, document, world-book or storage metadata enters the request; source text is not truncated.
- Creative guidance teaches a format card as a writing guide plus a concrete reply layout. The user-selected complete `黄文叙事` example is a literal snapshot in `FORMAT_CARD_AUTO_FILL_EXAMPLE_JSON`; runtime does not load presets. Keep the example separate from guidance so its genre and old markers do not become universal defaults.
- The selected model is resolved freshly and exactly; null follows the global chat default. Generation and single JSON repair use the same resolved model, model-owned output limits, and 600-second inactivity timeout.
- Stream completion is required before parsing/repair. Errors, early EOF, empty content and cancellation cannot produce an applicable candidate; both original and repair output remain visible. Parsing accepts a whole JSON object or enclosing Markdown fence, never a nested fragment salvaged from broken output. Tools use `FormatCardUserToolPolicy` validation.
- Closing an idle dialog retains form and candidate within the ViewModel. Changing character, request, or model invalidates the candidate. Busy dismissal is blocked; cancellation keeps the partial preview. Only applied editor content is durable across process death.

## Desktop boundaries

- New editor payloads already have a UUID: use editor `targetId == null` plus `base == null`, not payload ID, for the blank-new gate. Pin candidate apply to `draftSessionId` and payload ID; recheck blank content/tools and preserve the current manual name/default flag. Apply schedules the existing editor draft; it does not save an entity or bind a character/session.
- `DesktopTaskRuntime.withCardDesign` registers a structured caller child for maintenance/shutdown drain, independently from chat session admission. Data reads/generation hold the existing normal-operation lease. Preview/form state is process-local and belongs to that editor draft; another draft resets it.
- Task-center `ownedWork` projects that same child UUID/Job and actual terminal cause, without another generation. Completion indicates candidate preparation only; application and entity save remain explicit editor actions.
- `OpenAiStreamingTransport.streamFormatCardAutoFill` uses 600-second inactivity/read clocks, one HTTP request, model-owned output cap/thinking/custom parameters, no forced JSON mode, and separate reasoning deltas. Its `protectEnvelope=false` is a narrow official FORMAT_CARD compatibility exception, including custom envelope-key precedence; existing repair/Fish/Memory defaults remain protected. Do not substitute those tasks' without-output-cap/disable-thinking facades.
- Generation and JSON repair use the same freshly resolved model/account. Explicit missing model IDs fail rather than fall back. Controller logs are bounded/scrubbed under `format-card:<draftSessionId>`; raw candidates stay separate from request evidence, and local schema failures mark the corresponding request failed.
