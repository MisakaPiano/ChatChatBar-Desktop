---
name: chatbar-worldbook-ai
description: Maintain ChatBar world-book AI creation, blank-content filling, candidate preview/apply, research sources, checkpoints, and related service tests.
---

# ChatBar World Book AI

Desktop world-book generation tags its original TaskRuntime child WORLD_BOOK_AI. Task navigation carries the existing draft session and uses revealTaskDraft only while the original full book/target pin still matches; it does not load another book, refresh models, regenerate, apply or save. Normal Manage leave guards remain active, and a changed/closed pin is unavailable rather than recreated.

Read these entry points before broad search.

## First Read

- UI, source selection, candidate preview, resume controls: `app/app/src/main/java/com/example/chatbar/ui/worldbook/WorldBookEditScreen.kt`.
- State, generation orchestration, checkpoint compatibility, apply/save split: `app/app/src/main/java/com/example/chatbar/ui/worldbook/WorldBookEditViewModel.kt`.
- Batched creation/fill, JSON parsing and repair: shared `domain/worldbook/WorldBookAiCore.kt`; Android `WorldBookAiService.kt` retains the task-run wrapper, public API and companion policy facade.
- Candidate/checkpoint/response models and constraints/selected apply are shared `WorldBookAiModels.kt` / `WorldBookAiPolicy.kt`. `WorldBookResearchPort`, `WorldBookResearchEngine` and `WorldBookResearchModels` own the original per-batch research/session/cache behavior. Android `WorldBookResearchService` retains its task-run wrapper.
- Encyclopedia/manual-page/reference-document preparation: shared `WorldBookResearchEngine.kt` and `CharacterReferenceDocumentRetriever.kt`; owned HTTP and embedding clients remain platform-lifetime resources.
- Model calls: shared `WorldBookAiGateway` carries operation and stage. Android `AndroidWorldBookAiGateway` maps CREATE/FILL GENERATE/REPAIR and RESEARCH PLAN/BRIEF SUMMARIZE to the original AiTaskContext. `OpenAiStreamingTransport.streamWorldBookAi` is the Desktop transport seam.
- Model-facing text: shared `WorldBookAiPromptAuthority` physically owns the original literals/builders; Android `PromptTemplates` keeps exact facades and fingerprints the shared source. Use `chatbar-prompt-pipeline` for assembly changes.
- Wiring/settings: `app/app/src/main/java/com/example/chatbar/ChatBarApp.kt` and `data/local/entity/AppSettings.kt`.
- Tests: `WorldBookAiServiceTest.kt` and `WorldBookEntryModalStateTest.kt` under `app/app/src/test/java/com/example/chatbar/`.
- Desktop entry: `DesktopWorldBookEditorPanel` → `DesktopWorldBookAiController` / `DesktopWorldBookAiPanel`. The controller pins the full book/session/target, resolves a fresh exact selected model, tracks selected empty targets and candidate selections, and executes shared core under TaskRuntime plus data gate. `DesktopWorldBookEditorController.applyAi` checks the basis again before changing only draft entries; normal save and draft recovery are unchanged.
- Desktop sources reuse `DesktopCharacterResearchSources.worldBook(gateway)` with owned manual/encyclopedia/embedding clients. World-book controllers drain before this source owner and the auxiliary transport close. The transient document and raw/source/reasoning previews are released when the candidate dialog closes.

## Domain Rules

- Create/fill jobs have separate coroutine-scoped `AiStreamProgress` observers. `WorldBookAiDialog` renders the matching `AiStreamProgressPanel`, including nested research, batch generation and JSON repair reasoning/content. Observer previews are transient and independent of candidate/checkpoint data.

- streamJsonTask receives an explicit WORLD_BOOK_CREATE/FILL context and generation/repair stage; research uses WORLD_BOOK_RESEARCH/BRIEF. Batches and nested research share AiTaskRun. Refusal terminates the current operation before JSON repair or research fallback; completed candidates remain checkpointed.

- Creation produces at most 50 candidates in batches of 5. Blank-content fill freezes target entries at task start and processes batches of 5.
- Generated results remain selectable candidates. Applying selected candidates updates editor draft only; normal world-book save remains separate.
- Resume reuses completed batches only when operation input, selected model, research material, and target book signature remain compatible. Changed input invalidates checkpoint and starts fresh.
- Desktop also compares the freshly resolved model configuration on resume, so changing a saved model under the same ID restarts old batches. Input/model/source/target edits clear candidates, research, selection and checkpoint together. Fresh attempts and invalidated signatures clear the resume affordance before the first new checkpoint; cancelled pre-start jobs reset busy in completion rather than a body-only finally.
- World-book requests follow upstream1.4.7 selected-model parameters: CREATE/FILL generation and repair, RESEARCH planning and BRIEF summarization inherit output cap, thinking/effort and custom fields; neither platform gateway opts into parameter isolation or forces research false/low. They retain the default 120-second socket/inactivity budget; character-card 512/256 thinking budgets, 600-second timing and cross-model research retry semantics remain separate. Actual Android/Desktop six-stage HTTP parity is covered by `WorldBookAiRequestParityTest`; `WorldBookModelParametersTransportTest` records all four operation requests with both legacy thinking controls and reasoning-effort-only configurations.
- Research modes share `CharacterResearchSourceMode`: offline, encyclopedia search, specified URLs, or both. One transient reference document may augment every mode; source files and fetched pages are not persisted into world book.
- Keep candidate identity stable across checkpoints. Fill may update only selected target IDs with nonblank content; creation appends selected new entries without rewriting current entries.
- Parsing/repair failures and unusable requested research must stay visible. Partial encyclopedia/manual-source failures may continue only when other usable requested material exists.

## Workflow

1. Trace UI operation through ViewModel into `WorldBookAiService` and research service.
2. Preserve candidate-preview, explicit-apply, checkpoint, and draft-save boundaries.
3. Update inline-fixture JVM tests for batching, parsing, merge, or resume behavior.

## Verification

Run focused JVM tests, then repository release checks when publishing.
