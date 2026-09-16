---
name: chatbar-prompt-pipeline
description: Maintain and diagnose ChatBar prompt text, templates, builders, assembly, and request ordering across PromptTemplates, PromptAssembler, cache layers, history grouping, RAG cards, World Book outlets, Archive/HEAD injection, and final ChatApiMessage serialization. Use whenever adding, editing, deleting, renaming, or moving any model-facing prompt in PromptTemplates.kt; also use for prompt section order, roles, headings, caching, previous-turn placement, RAG usage notes, or when actual model input differs from a preview.
---

# ChatBar Prompt Pipeline

Treat the serialized API message list as source of truth. Constant declaration order and assembled preview text do not prove what the model receives.

## First Read

- Prompt text and section labels: app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt
- Section collection, layer rendering, RAG cards, outlets: app/app/src/main/java/com/example/chatbar/domain/chat/PromptAssembler.kt
- History and previous-turn grouping: app/app/src/main/java/com/example/chatbar/domain/chat/ContextWindowManager.kt
- Final role/message insertion and request launch: app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt
- Per-model format prompt placement: app/app/src/main/java/com/example/chatbar/data/local/entity/ModelConfig.kt
- Cleartext HTTP final role adaptation: app/app/src/main/java/com/example/chatbar/domain/chat/CleartextHttpChatTemplatePolicy.kt
- Request diagnostics: app/app/src/main/java/com/example/chatbar/utils/DebugLogManager.kt and ui/chat/DebugLogDialog.kt
- Core tests: PromptAssemblerCharacterModeTest.kt, ContextWindowManagerTest.kt, CurrentTurnMessageOrderTest.kt, RoleplaySpeakerPromptTest.kt, PromptTemplatesTest.kt, and CleartextHttpPolicyTest.kt

Use chatbar-long-term-memory when Archive, HEAD, timeline constraints, source-turn boundaries, or RAG grouping are involved. Use chatbar-novelai-prompt for NovelAI tag-design prompts and chatbar-character-card-ai for card-generation prompts.

## Ownership Model

- Auxiliary text/vision requests use AiTaskContext and AiTaskMessageAssembler in domain/prompt/AiTaskRequests.kt: common + original first system → task-confirmation user → static acknowledgement assistant → remaining original messages. User-only inputs gain a first system. Main chat keeps its separate CCB assembly. Neutral confirmation text, scene boundaries and related template symbols live in PromptTemplates.aiTaskProfile; repair stages use repair boundaries. Confirmations are request-only and never enter persisted history.
- PromptTemplates also owns characterRewriteOutputSchema, characterResearchSummary, worldBookPromptSummary, worldBookQueryContext, novelAiRevisionWithCharacterReference and indexedImageDescription. Scene services retain serialization and business validation; editable model-facing prose belongs in these builders.

- Automatic image eligibility uses isolated `AutomaticChatImageJudge` messages: `AUTOMATIC_CHAT_IMAGE_JUDGE_SYSTEM` then `automaticChatImageJudgeUser` JSON with story setting, recent history, current user input, raw reply, and final display body. It requires complete, story-related, non-refusal content; this judgment does not alter the main chat prompt or NovelAI design prompt.

- Keep model-facing task text in PromptTemplates.
- Treat the `AI 提示词目录` KDoc at the start of PromptTemplates as mandatory navigation metadata. Every PromptTemplates prompt change must review it; add, remove, rename, recategorize, or revise entries in the same change whenever symbols or purposes change. Use exact searchable symbol names and never line numbers.
- A PromptTemplates prompt change is incomplete until the header directory remains accurate. Keep template constants beside their builders so directory search lands in one local area.
- Keep section selection, titles, and layer assignment in PromptAssembler.
- Keep logical ChatApiMessage roles and interleaving with raw history in ChatViewModel. StreamingChatService adapts later system roles and merges a trailing requirement into the current user only for opted-in `http://` requests; Debug Request JSON records this adapted transport body.
- Keep conversation grouping in ContextWindowManager and shared turn policies.
- Verify transport request fields with chatbar-model-request-runtime.

Do not move behavior between these owners without tracing every caller and test.

## Layer Invariants

- Core contains the resolved character-card system prompt plus CCB creator identity. Character, supplementary and player settings are separate logical system messages; reply constraints belong only to positioned requirements.
- Chat request uses separate character, setting-reference (World Book + non-CHAT_MEMORY RAG), supplementary, player, reply-constraint, and memory-RAG fields. Legacy `dynamicSystemPrompt` remains an aggregate for compatibility; never inject it alongside these request fields.
- Final logical order: core + creator identity → CCB first ack / contract / confirmation → optional START requirements → character → World Book + setting RAG → supplementary → player → CCB context approval → Archive → earlier history → memory RAG → HEAD/timeline → previous turn → CCB continuation → current user → character post-history + optional END requirements → optional format-card strong suffix → CCB assistant/user tail.
- CCB continuation is a standalone system immediately before the real current user. Its existing next-user wording remains valid. Archive is independent before the history heading; memory RAG is after earlier history.
- Move a complete adjacent USER + ASSISTANT previous turn into the tail hot zone when available. Earlier assistant history may omit status and option blocks when configured, but every assistant message in the previous turn must retain its full content. Preserve opening assistants, consecutive users, unanswered users, and other abnormal messages in original order.
- Resolve the active format card by available entities: use an available session override first, then an available global default; a stale session ID must not suppress the default card.
- Build current-turn requirements once through `PromptTemplates`, then combine with the assembler's reply-length/language constraints and reply-tail length/speaker requirements. START is after CCB contract confirmation, before character; END is after the current user and character post-history; BOTH uses the exact same text twice. No separate reply constraints remain in character settings. Missing persisted values default to BOTH.
- Format-card random-number tools remain a request-only suffix inside the current user message. Extract every STRONG_PROMPT_SUFFIX in configured order into one logical system after character post-history/END requirements. CCB assistant acknowledgement and user identity reminder follow; the reminder is always the last logical message.
- Derive the prompt cache key from the exact logical prefix through CCB context approval, including roles, conditional START, World Book/setting RAG and all settings. Edits or retrieval changes alter the key. Archive, history heading and END remain outside that prefix.
- Render session placeholders in separately inserted Archive and HEAD text before creating their final `ChatApiMessage`; keep persisted memory text unchanged.
- Cleartext HTTP adaptation changes the non-trailing strong-prompt system and other later system roles to assistant. The final CCB reminder remains user, so cleartext and HTTPS requests both end with user.
- Omit empty sections and their headings.
- Base cacheability on rendered stable content. An unresolved World Book outlet in stable content disables stable-prefix caching.
- Keep cache keys aligned with the exact sent prefix through context approval.

## RAG Rendering

- Partition cards by ChunkSourceType, not display labels.
- Render non-CHAT_MEMORY cards before CHAT_MEMORY cards.
- Inject RAG_CHAT_MEMORY_USAGE_NOTE once, immediately before the first memory card.
- Do not add the memory-card note to document cards.
- Keep card numbering continuous after partitioning.

## Workflow

### World Book request scan

- `ChatViewModel.buildWorldBookPrompt` reloads explicitly linked books each request. For duplicate IDs, a linked repository book wins over an embedded copy; first book occurrence still defines book order. Editor drafts do not replace saved books.
- `ChatRepository.getWorldBookScanSnapshot` reads enough recent messages for maximum book/entry scan depth independently of direct chat context; timed effects use full indexed message count, excluding a regeneration target. Non-persisted current input participates in scanning.
- `WorldBookEngine` scans displayContent; enabled/character filters, delay, sticky/cooldown, keys/secondary keys, probability, per-depth group competition, recursion and per-book token budget govern selection. Blank keys never match; nullable whole-word settings inherit from the book. Sticky activations preserve their original deadline. Timed maps use book-ID + entry-ID keys and read legacy entry-only keys for compatibility.
- World Book reasons and source book IDs/updatedAt are included in existing request retrieval diagnostics (`ragDebugLogs`). OUTLET selections still require a matching placeholder. BEFORE_CHAR/AFTER_CHAR define internal ordering in the user's unified post-character World Book block.
- Entry caseSensitive and matchWholeWords are nullable: null inherits the book, explicit true/false overrides. Existing persisted booleans retain their values; the entry editor and draft materialization preserve all three states.
- `WorldBookScanContext.fromCard` supplies opt-in description/personality/scenario/creator-notes/persona matching sources. Structured description uses character descriptive fields; personality uses habits/speakingStyle; scenario uses basicSetting. Freeform cards use current editable importer sections 人物描述/性格特点/背景场景; unsectioned text is description and examples are excluded. Persona uses the active session/global player setting; placeholders render before matching. Both primary and secondary keys inspect selected sources, including with scanDepth=0 and during recursion; source text itself is not injected as lore.
- `WorldBookTransferService` owns both standalone and character-embedded import. Matching options load from entry root or extensions and survive ST export. `WorldBookEntryModalState` exposes/preserves all five extra-source flags. Regression coverage: WorldBookMatchingOptionsTest.

1. Read the PromptTemplates header directory and classify the change as prompt text, section assembly, turn grouping, cache behavior, or transport.
2. Write expected final message roles and order before editing.
3. Trace both assembleSystemPrompt and assembleCachePromptLayers callers.
4. Update or verify the header directory in the same change for every PromptTemplates prompt edit.
5. Check direct chat, regeneration, cache fallback, and empty-section paths.
6. Add behavior tests for inclusion, omission, relative order, and role.
7. Inspect serialized Request JSON when delivery or ordering is disputed.

## Regression Matrix

- No history, one incomplete turn, and multiple complete turns.
- Format prompt placement at START, END, BOTH: START before character, END after current user and character post-history, BOTH at both positions; length/language/speaker constraints move together.
- Opening assistant, consecutive users, unanswered user, and regeneration.
- Empty-message continue: blank user input is replaced by `PromptTemplates.continueGenerationUserPrompt()` as the current user message and is not persisted; format-requirement placement still follows the resolved model configuration.
- Format-card user tools: direct send, multimodal send, regeneration, and empty-message continue keep random values inside the real current user, then append one configured strong-prompt system followed by the CCB assistant/user tail; retries reuse already assembled random values.
- Empty versus populated World Book, RAG, Archive, HEAD, and post-history sections.
- Stable outlet present versus absent.
- Document-only, memory-only, and mixed RAG cards.
- Cache path and non-cache fallback produce equivalent semantic order; cache key covers exact stable role/content sequence.
- Cleartext HTTP serialization preserves non-trailing message/content order, adapts the strong-prompt system to assistant, preserves the final CCB reminder user, and never ends with an adapted assistant prompt.
- Expected Archive and HEAD markers exist in final serialized messages.

## Stop Conditions

- Do not infer order from SECTION constants.
- Do not accept preview-only evidence for a request-delivery bug.
- Do not change source-turn grouping without checking direct context, RAG, and long-term memory together.
