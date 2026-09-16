---
name: chatbar-format-card-ai
description: Maintain ChatBar character-specific format-card AI creation, creative guidance and example, streaming candidate preview, tool validation, and blank-new-card apply. Excludes rewriting existing format cards and chat message format repair.
---

# ChatBar Format Card AI

## Entry Points

- `ui/format/FormatCardEditScreen.kt`: new-card fill entry.
- `ui/format/FormatCardAutoFillDialog.kt`: character/model selection, optional request, fullscreen request editor, candidate and raw-output preview.
- `ui/format/FormatCardEditViewModel.kt`: form lifetime, fresh source lookup, background job, cancellation, apply and draft save.
- `domain/card/FormatCardAutoFillService.kt`: service, `FormatCardAutoFillDraft`, parser, terminal stream collection, and `FormatCardAutoFillPolicy`.
- `domain/prompt/PromptTemplates.kt`: `FORMAT_CARD_AUTO_FILL_*`, `formatCardAutoFillSystemPrompt`, `formatCardAutoFillUserPrompt`.
- `ChatBarApp.kt`: service wiring. Tests: `domain/card/FormatCardAutoFillServiceTest.kt`.

Paths above are relative to `app/app/src/main/java/com/example/chatbar/`; tests live under the matching `app/app/src/test/java/com/example/chatbar/` package.

## Feature Contract

- FORMAT_CARD requests use GENERATE/REPAIR contexts under one AiTaskRun. collectFormatCardAutoFillText propagates StreamEvent.Error.asException() so typed refusal cannot enter the single JSON-repair attempt. Actual request bodies and usage are available in the global AI request logs.

- Only new cards with blank content and no tools can be filled. A supplied name survives generation and apply. The entry and final apply both use the blank-target gate; existing cards have no AI entry.
- Candidate contains name, content, and ordered tools. Applying changes editor fields and schedules its ordinary draft save; entity save remains separate. No character/session binding is persisted.
- One saved character card plus optional request is the entire source. Generation re-reads the selected card. Structured mode sends every character's text fields; freeform mode sends only active freeform text. No image, voice, document, world-book or storage metadata enters the request; source text is not truncated.
- Creative guidance teaches a format card as a writing guide plus a concrete reply layout. The user-selected complete `黄文叙事` example is a literal snapshot in `FORMAT_CARD_AUTO_FILL_EXAMPLE_JSON`; runtime does not load presets. Keep the example separate from guidance so its genre and old markers do not become universal defaults.
- The selected model is resolved freshly and exactly; null follows the global chat default. Generation and single JSON repair use the same resolved model, model-owned output limits, and 600-second inactivity timeout.
- Stream completion is required before parsing/repair. Errors, early EOF, empty content and cancellation cannot produce an applicable candidate; both original and repair output remain visible. Parsing accepts a whole JSON object or enclosing Markdown fence, never a nested fragment salvaged from broken output. Tools use `FormatCardUserToolPolicy` validation.
- Closing an idle dialog retains form and candidate within the ViewModel. Changing character, request, or model invalidates the candidate. Busy dismissal is blocked; cancellation keeps the partial preview. Only applied editor content is durable across process death.
