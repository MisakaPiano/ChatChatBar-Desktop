# CCB Desktop — Phase 7 Full-Phase Codex Mega Task

Model recommendation:
GPT-6 Astra / High / Fast OFF

Task style:
FULL PHASE IMPLEMENTATION
LARGE-SCOPE, DURABLE INTERNAL CHECKPOINTS
NO MICRO-TASK USER ROUNDTRIPS

Estimated runtime:
60–180 minutes depending on existing shared seams and build speed.

Quota:
Large task. Current weekly baseline at handoff is about 24%.
Use durable commits so capacity/quota interruption is recoverable.

Repository:
H:\ChatChatBar-Desktop

Start from:
latest `origin/desktop` after an ff-only local update.

Required ancestry check:
production code baseline `6737f0ed382170a7891d0bd5f7e4708ca2840009` must be an ancestor; commits after it on `desktop` are Phase-7 handoff/docs-only.

Create:
feature/phase7-image-novelai

Formal compatibility target:
CCB 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8

Important:
master mirrors 1.4.4.
sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721 is PARKED.
DO NOT merge/review/promote/chase it in this task.

==================================================
MISSION
==================================================

Implement and close the complete Phase 7:

IMAGE RESOURCES + NOVELAI

Do not split this into many user-facing tasks.

Work continuously through the whole Phase.

Use multiple internal commits when useful, push them, and continue.

Only stop for a real blocker:
- schema authority ambiguity
- Prompt text would need modification
- data-loss risk
- SecretStore/security ambiguity
- inability to share official JVM-neutral logic
- unrecoverable build/test failure
- quota/capacity interruption

==================================================
READ FIRST
==================================================

Read narrowly:

docs/desktop/00_PROJECT_SOURCE_MAP.md
docs/desktop/10_UPSTREAM_BASELINE.json
docs/desktop/21_CURRENT_STATE.md
docs/desktop/13_FEATURE_PARITY.md
docs/desktop/14_UPSTREAM_COMPAT.md
docs/desktop/17_ROADMAP.md
docs/desktop/18_DECISIONS.md
docs/desktop/27_EDITOR_REFERENCE_ADOPTION.md
docs/desktop/32_PHASE7_ENTRY_HANDOFF.md

Editor Reference access rule:
- `27_EDITOR_REFERENCE_ADOPTION.md` is the controlling distilled adoption map and is sufficient to proceed.
- The raw `CCB_EDITOR_REFERENCE_PACK.zip` is optional REF evidence, not schema/Prompt/runtime authority.
- If the raw pack is not present in the local repository/workspace, DO NOT stop or ask the user to copy it before implementation; proceed from the adoption map and current source.
- If the raw pack is locally available, it may be inspected only for UX/workflow/regression details.

Relevant formal-baseline Skills:
- chatbar-feature-map
- chatbar-image-generation-runtime
- chatbar-novelai-prompt
- chatbar-character-card-ai only where image ownership intersects
- chatbar-background-work-runtime if image generation uses it
- chatbar-model-request-runtime for AI prompt-design transport only

Do not indiscriminately read entire repo.

==================================================
PHASE 7 REQUIRED OUTCOME
==================================================

Complete every P7-owned user capability or document a concrete BLOCKED external dependency.

No UNKNOWN.

P7 parity should no longer remain broadly PENDING at phase close.

==================================================
A. IMAGE RESOURCE FOUNDATION
==================================================

Build/reuse one Desktop image ownership/storage/workspace foundation.

Requirements:

- durable owned image storage
- safe import/copy
- restart persistence
- current-owned replacement cleanup only
- no unrelated image deletion
- safe filename/path policy
- no browser storage
- high-DPI aware rendering

Create reusable Desktop Image Workspace for:
- pan/drag
- mouse-wheel zoom
- zoom slider
- crop rectangle/viewport
- reset
- recrop
- apply/cancel
- keyboard/mouse sensible interaction

Use Editor Reference Pack only as UX/regression evidence.

Do not transplant Web implementation.

Avoid flashing/recomposition loop.

==================================================
B. CHAT BACKGROUND
==================================================

Implement formal-baseline chat background behavior.

Consume existing:
CharacterCard.chatBackground
ChatSession.chatBackground
AppSettings background opacity/settings as applicable

Requirements:

- Primary Chat rendering
- correct session/card fallback
- scaling
- opacity
- DPI
- readability
- no mutation merely from rendering
- replacement through owned image workflow

Expose required global/session settings.

==================================================
C. CHAT IMAGES
==================================================

Implement formal-baseline Desktop equivalent:

- add/pick image
- pending image UX
- persist message image
- display image in chat
- preview
- previous/next where appropriate
- remove pending image
- save/copy/reveal equivalents
- restart persistence
- deletion ownership safety

Use native Desktop file picker.

Global OS drag/drop/Open With remains P15.

==================================================
D. CHARACTER IMAGE UX
==================================================

Integrate existing:

- CharacterCard.avatar
- CharacterCard.chatBackground
- CharacterInfo.appearanceImage

with shared Desktop Image Workspace where useful.

Do not change Package schema.

No fake cover field.

==================================================
E. CCB PNG COVER
==================================================

Implement formal-baseline cover-adjustment behavior:

- chatBackground as normal official source
- crop center X/Y
- crop zoom
- gradient
- logo scale
- title scale
- usable preview

Approved Desktop enhancement:

export-only replacement cover source

Rules:

- export-only
- does not mutate CharacterCard.chatBackground
- not persisted as fake upstream Entity field
- not serialized into Package schema

Reuse existing official CCB branding.

==================================================
F. NOVELAI SECRET + SETTINGS
==================================================

Implement NovelAI credential via Desktop SecretStore/OS protection.

Never plaintext JSON.

Implement P7-owned global/session settings.

At minimum:
- credential/status
- image prompt design model
- NovelAI model
- ratios/sizes
- automatic image toggle
- natural-language mode where formal baseline owns it
- related image generation preferences
- background opacity if P7-owned

Preserve default/session override semantics.

==================================================
G. NOVELAI HTTP RUNTIME
==================================================

Prefer sharing/moving JVM-neutral official logic.

Implement Desktop adapter for:

- auth
- request
- model
- size
- seed
- samples/batch
- stream/progress events
- retry policy
- cancellation
- errors
- owned output persistence

Do not create a second semantic implementation if official logic can be shared.

No token logging.

==================================================
H. PROMPT DESIGNER / TAG SYSTEM
==================================================

Implement formal-baseline:

- Prompt Designer
- Danbooru catalog
- tag search/suggestion
- translation/annotation behavior
- research/evidence behavior where baseline owns it
- V5 natural-language mode
- prompt regeneration/editing
- token/prompt behavior as formal source defines

Prompt protection:

DO NOT rewrite official model Prompt text.

If moving Prompt builders/shared code:
exact semantic/text preservation.

If a Prompt literally must change:
stop only that specific change and report it;
continue unrelated work where safe.

==================================================
I. STUDIO
==================================================

Implement formal-baseline Studio Desktop equivalent:

- draft/settings persistence
- generate
- image results
- history
- selection
- regeneration
- metadata import
- guidance
- vibe
- inpaint where baseline includes it
- undo/reset behavior
- model/size/seed controls
- image preview

Desktop layout may differ but behavior must remain equivalent.

==================================================
J. GENERATED IMAGE METADATA / REGENERATION
==================================================

Preserve formal metadata required to regenerate.

Support:
- saved generation metadata
- read/import metadata
- editable regeneration
- correct source/style/base/character/negative semantics
- size/seed/model preservation as baseline defines

Do not infer schema from old editor.

==================================================
K. AUTOMATIC CHAT IMAGES
==================================================

Implement formal automatic chat image behavior.

Requirements:

- explicit opt-in
- completed persisted assistant reply only
- correct eligibility policy
- no duplicate AI judge if official baseline no longer uses it
- generation handoff only after valid completion
- cancellation/refusal/truncation/failure prevent false image success
- durable message linkage
- restart persistence
- user-visible non-persisted skip/error UX where appropriate

==================================================
L. IMAGE TOOLS
==================================================

Implement formal-baseline Desktop equivalents for:

- APNG disguise
- APNG restore
- mosaic editor
- rotation where owned
- image save
- image copy
- reveal/open in Explorer
- privacy/metadata behavior only to formal baseline scope

Do not pull global OS associations into P7.

==================================================
M. SHARED AUTHORITY
==================================================

When formal Android logic is JVM-neutral:

prefer pure move/share into sharedCore.

Android should delegate/facade when appropriate.

Do not maintain:
- Android image policy copy
- Desktop image policy copy

if one shared implementation is possible.

If moving production authority:
preserve Android behavior and run Android regressions.

==================================================
N. DATA SAFETY
==================================================

No auto-clearing.

No broad recursive deletion.

Replacement:
- write/validate new file
- persist authority
- only then clean old current-owned file where safe

Cancellation around durable writes must not leave lying UI state.

Respect existing app-data operation coordination where required.

==================================================
O. TASK RUNTIME
==================================================

Long image generation must integrate with existing Desktop task/runtime lifecycle where appropriate.

Do not invent unrelated second task system.

User stop/cancel must propagate to actual network/image operation.

==================================================
P. TEST STRATEGY
==================================================

During development:
focused tests + affected compile only.

Do not run full 3-module regression after every tiny commit.

At final Phase gate run once:

- sharedCore full tests
- desktopApp full tests
- Android JVM tests for shared changes
- Desktop compile
- Android compile
- relevant image/NovelAI focused tests
- git diff --check
- createDistributable

Use system JDK:
C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot

Process-local JAVA_HOME/PATH only.

ANDROID_HOME process-local if needed.

Do not change local.properties just for CI-like invocation.

==================================================
Q. PACKAGED / MANUAL EVIDENCE
==================================================

Create one final Phase-7 isolated distributable.

Launch smoke:
- EXE starts
- window exists
- normal shutdown
- no launcher/JVM fatal

Computer-use screenshot:
optional one attempt only.
Timeout = BLOCKED_ENVIRONMENT.
Do not repeatedly retry.

Prepare one consolidated manual acceptance checklist for user.

Do not stop for manual acceptance halfway through P7.

==================================================
R. INTERNAL CHECKPOINTS
==================================================

Suggested internal commits, but continue automatically:

1. image storage/workspace foundation
2. chat background + chat images
3. Character/PNG cover UX
4. NovelAI secret/settings/runtime
5. Prompt Designer/tag system
6. Studio/history/regeneration
7. automatic chat images + image tools
8. final tests/package

These are NOT separate Project tasks.

If a better implementation ordering emerges, use it.

==================================================
S. PARKED 1.4.4 SYNC
==================================================

Do not work on:
sync/1.4.4

Do not:
- counter-review it
- merge it
- promote it
- adapt P7 to all 1.4.4 extras

If formal-baseline implementation encounters a direct incompatibility that newer upstream already resolves:
record it and only then decide the minimal necessary adoption.

Otherwise ignore drift until later batch sync.

==================================================
T. DOCS
==================================================

Do not stop for docs after each checkpoint.

At Phase-7 close update CURRENT docs in one reconciliation pass:

- 13_FEATURE_PARITY.md
- 14_UPSTREAM_COMPAT.md
- 16_TEST_MATRIX.md
- 17_ROADMAP.md
- 18_DECISIONS.md only if new decisions were made
- 21_CURRENT_STATE.md

Do not promote upstream baseline merely because P7 finished.

==================================================
U. FINAL REPORT
==================================================

When Phase 7 is complete, report:

- branch
- starting desktop SHA
- final feature HEAD/origin
- commit list by subsystem
- shared authority moves
- Android adapters
- Desktop image workspace
- chat background/images
- Character image/cover behavior
- NovelAI SecretStore
- NovelAI runtime
- Prompt Designer/tag system
- Studio/history
- regeneration/metadata
- automatic images
- APNG/mosaic/save/reveal
- data ownership/deletion rules
- tests
- compile
- package
- launch smoke
- manual acceptance checklist
- parity rows now EXACT/EQUIVALENT/PENDING/BLOCKED
- any true blockers
- upstream baseline unchanged unless separately authorized
- parked sync unchanged
- worktree status

If all required scope is implemented and automated gate passes:

READY FOR PROJECT PHASE-7 REVIEW

If quota/capacity interrupts:

push clean durable checkpoint and report:

PHASE-7 IN PROGRESS — RESUME FROM <SHA>

Do not create another planning task.
