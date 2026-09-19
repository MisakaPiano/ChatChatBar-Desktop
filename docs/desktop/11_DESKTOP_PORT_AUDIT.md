# Phase 0 Desktop port audit

Baseline: CCB 1.3.48 @ 4c8c1eac51dc632bf9042468819cb86091b7660c

## Upstream shape
Current upstream is an Android Kotlin project with one Gradle module (:app), organized around data/domain/ui/utils.

JVM-friendly areas include kotlinx.serialization, coroutines-compatible domain logic, OkHttp/SSE, Retrofit, JSoup and pure policy/state-machine code.

Android/platform-heavy areas include Context/Activity, ContentResolver/Uri/Intent/SAF, Android Bitmap/Canvas, Keystore/SharedPreferences, Media3, Services/Notifications, AccessibilityService and APK installer APIs.

## Transfer/package facts
CharacterCardPackage:
- current schema 9
- reads 3..9
- v9 adds optional defaultFormatCard: FormatCardPackage

FormatCardPackage:
- current 2
- reads 1..2

WorldBookPackage:
- current 1

SaveSlot:
- streaming .cbsave v8
- manifest.json
- messages.jsonl
- rag.jsonl
- optional voices.jsonl
- media/images and media/audio

CCB PNG:
- Package JSON is UTF-8/Base64 and stored in PNG text metadata.
- Payload codec should be EXACT.
- Android visual cover renderer must be replaced with a Desktop equivalent.

SillyTavern compatibility:
- reuse upstream parser/mapper semantics; do not recreate old-editor mapping rules.

## Package / Entity / Runtime separation
Character Entity contains local IDs, local resource paths, worldBookIds, defaultFormatCardId, RAG status, timestamps and runtime metadata.
Package contains portable resource IDs and embedded resources.
Prompt/runtime transforms Entity state into model messages.
These are separate contracts.

## Persistence
JsonFileStorage uses filesDir/entities on Android and already provides:
- ignoreUnknownKeys
- per-entity locking
- Flow cache
- temp files
- atomic move/replace with fallback
- streaming/uncached APIs
- transactional prefix replacement

Desktop should make storage root Path-driven rather than replace it with SQLite.

## Prompt
High-risk shared target:
- PromptTemplates
- PromptAssembler
- ContextWindowManager
- ChatViewModel orchestration
- StreamingChatService

Final serialized model messages/request are the compatibility truth, not a UI preview.

## WorldBook
WorldBookEngine supports recursion, probability, groups, sticky/cooldown/delay, selective logic, regex, character filters, budgets, role/position/outlet and timed effects.
Desktop target: EXACT.

## Model runtime
Mostly JVM-shareable:
- model discovery
- auth inheritance
- fallbacks
- SSE
- thinking/reasoning
- output token parameter policy
- cleartext local model adaptation
- retry/error handling

Background/lifecycle protection is platform-specific.

## RAG / long-term memory
Do not simplify. Upstream contains document/chat-memory RAG and Episode/Arc/Era/Archive/HEAD/Gap/backfill/compression/repair/regeneration state machines.

## NovelAI / images
Share API/prompt/policy/metadata logic where possible.
Replace Bitmap/Canvas/resource APIs with a Desktop image backend.

## Fish Audio
Share API/domain. Replace Android Keystore and Media3.

## QQ voice
Upstream relies on Android AccessibilityService and QQ UI gestures. Desktop requires a separate Windows feasibility decision. Do not claim brittle coordinate automation as EXACT.

## Moments
Domain/generation can be shared. Runtime scheduling needs Desktop ownership. Preserve the upstream product behavior that Moments do not continue generating while the app is closed.

## Community
Share backend contracts and package validation. Replace Android deep-link OAuth with Desktop URI scheme or localhost callback.

## Shared Import
Share content-first classifier/staging/FIFO. Replace ACTION_SEND/VIEW with Open With, file association, command-line arguments and drag/drop.

## App Update
Share version/release metadata logic where appropriate. Replace APK installer flow with Windows EXE/MSI updater.

## Primary architectural risk
Do not duplicate upstream domain logic into a Desktop-only copy. That guarantees semantic drift and makes upstream sync expensive.
