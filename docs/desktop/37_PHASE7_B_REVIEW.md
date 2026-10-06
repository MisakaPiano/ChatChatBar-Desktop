# Phase 7 B — NovelAI runtime / Prompt and tag infrastructure

Continuation of accepted P7-A (`37ea1e8b`) on `feature/phase7-image-novelai`.
This is the meaningful P7-B review boundary, not Phase close or manual acceptance.
Formal baseline remains CCB 1.4.1 at `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.
Desktop and parked `sync/1.4.4` are not merged or advanced.

## Authority and scope

- `bc2571e` contains the approved official-upstream Prompt safety forward-port from
  `ace632cce58a3b5a57711e31990165d6a14e1c0f`. Only those three literals are a compatibility
  exception; no final identity-reminder or other newer Prompt drift was adopted.
- `4acb6cd` establishes credential UI / Windows DPAPI / runtime hydration / live fuse and
  closes A's text-only model + linked-vision remainder. Details and focused evidence are in
  `35_PHASE7_B_CREDENTIAL_GATE.md`.
- `a58ccfe` records the subsequent user-authorized single live smoke. Details are in
  `36_PHASE7_B_LIVE_SMOKE.md`. The historical pre-credential checkpoint remains historical.
- Shared `NovelAiPromptAuthority` now physically owns the official NovelAI literals/builders;
  Android `PromptTemplates` retains its signatures and delegating symbols. Source blocks were
  compared verbatim to pre-extraction `a58ccfe`: revision builder, card/Moment builders, and the
  complete NovelAI section all match. The three safety literals were again compared exactly
  with upstream `ace632c...` in their current shared owners.
- Shared `NovelAiPromptDesigner`, `NovelAiTagResearchService`, codex parser/retrieval,
  post-processor, design conversation models, tokenizer, dictionary policy, annotation parser,
  completion fragment/insertion and streamed ranking retain the official behavior.
- `NovelAiTextTransport` is the platform seam. Android retains original task-run identity,
  IMAGE_RESEARCH/PLAN and IMAGE_DESIGN/GENERATE/REPAIR routing. Desktop uses the shared GENERAL
  envelope and provider serializer, preserving roles for GENERATE and merged input for PLAN/
  REPAIR, with refusal, empty-response, truncation and cancellation checks.
- V5 natural-language design uses the official dedicated system and repair route, keeps
  Chinese prose/weights and skips tag post-processing. V4.5 rejects that active mode while
  retaining the session preference. Existing model/size/seed/batch and V5 request policies
  remain shared. No new AI Prompt was introduced.

## Desktop foundation for Studio

- Global settings expose secure credential status, design model, image model, ratio, Studio
  extra requirement and local Chinese-annotation preference. Session settings expose design
  model override, NovelAI override, image requirement, natural-language and automatic-image
  preferences using existing upstream fields and existing draft-save conflict handling.
  Automatic-image execution is part of the post-B Studio/chat stage.
- `DesktopNovelAiInfrastructure` owns one reusable graph for design, linked image understanding,
  tag lookup, dictionary, streamed suggestions, annotations and token counting. Asset loading
  runs off the UI thread; scope shutdown drains background preparation.
- `NovelAiCatalogQueries` and `RankedTagCompletion` are the common SQL/ranking authority.
  Desktop adds read-only JDBC/cursor and verified immutable compressed-resource installation;
  Android delegates its existing SQLite queries and retains its cancellation signal adapter.
  Research still uses general/copyright/character and eight candidates; live completion retains
  all five categories, catalog-first ordering/deduplication, then alphabetical dictionary results.
  Corrupt/mismatched assets fail visibly and are retained; they are not silently replaced.
- Desktop packages the existing codex, Danbooru/dictionary, completion indexes and tokenizer
  assets. Desktop-only JDBC dependency is pinned to `org.xerial:sqlite-jdbc:3.53.4.0`; its
  [official project](https://github.com/xerial/sqlite-jdbc) documents bundled Windows native support.
  This adds no Android dependency or upstream entity/package field.
- `DesktopNovelAiGenerationRuntime` hydrates credentials only at execution and delegates HTTP,
  model request shape, progress, batch and bounded 429 retries to shared `NovelAiImageService`.
  Launch settings resolve random seed once, and history preserves per-image seed increments.
  Provider bodies/causes and reflected credentials do not enter published errors or output.
- `DesktopNovelAiGenerationStore` writes all new owned files before one durable shared
  `novelai_generation_history` entry. Failed batches clean only their exact unreferenced new
  files; corrupt/indeterminate authority is retained. No old image is removed. The shared
  `NovelAiGenerationHistoryEntry` / recipe schema is unchanged.
- Production runtime is separate from the explicitly guarded automation runner. It is not
  invoked by credential save, settings edits, startup or any test. No production generation UI
  is wired at this milestone; Studio owns the post-B execution entry and user actions.

## Live and local evidence

Exactly **one** Phase-7 real generation request has been used, with zero automatic retries:
V4.5 Full, text-to-image, 1024×1024, 28 steps, one sample, no guidance/reference. Free eligibility
was confirmed before dispatch and unchanged Anlas/membership after completion. Durable fuse
is 1/8, with no pending or blocked marker. No V5, batch, image guidance or paid-shape request
was sent live. Credential contents were never inspected or reported by Codex.

The resulting cup still-life PNG and actual-seed recipe survive runner shutdown in the isolated,
ignored `.phase7-live-data` repository-root directory (verified copy of the initial build output,
preserved outside Gradle clean). Reopening the saved PNG confirmed its
1024×1024 local preview. No extra generation was used for local inspection.

Focused verification (no live endpoints in automated tests):

- Shared: **169 passed** (`*NovelAi*`, `AiJsonOutputExtractorTest`, main-chat authority/order).
- Desktop: **35 passed** (NovelAI safety/runtime/JDBC/envelope/batch/rollback, real chat with
  linked vision, Phase-7 image foundation). Fake V5 batch, bounded production 429 retry,
  auth/cost failure redaction and cancellation never use the saved real credential.
- Android: **77 passed** (image feature, scene history, natural-language mode, shared facade,
  auxiliary envelope, Prompt templates and current-turn logical order).
- `:sharedCore:compileKotlin`, `:desktopApp:compileKotlin`, `:app:compileDebugKotlin` passed;
  affected Android/desktop test sources compile. `git diff --check` passed.
- Two new JUnit test signatures initially inferred a non-void result; corrected before the
  successful Desktop run. A build-daemon memory-pressure stall was recovered using one worker,
  in-process Kotlin, no build/configuration cache and a bounded 1536 MiB Gradle heap.
  No app data was removed and no repository build defaults were changed.
- Runtime tests construct inline fixtures. No test asserts bundled preset/seed content.

Validation invocation used JDK 17 from `app/`, Gradle `--offline --console=plain
--no-build-cache --no-configuration-cache --no-daemon --max-workers=1`,
`-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8`, and
`-Pkotlin.compiler.execution.strategy=in-process` to keep machine memory bounded.

## Continue after Project PASS

Remain on this branch and task. Continue Studio, history/regeneration/metadata UI, guidance/
vibe/inpaint platform preparation, automatic chat images, APNG/mosaic/save/copy/reveal, then
consolidated regression/distributable/launch smoke and Phase-7 close evidence. B does not
perform Phase-close docs reconciliation, full regression, user manual acceptance or desktop merge.
