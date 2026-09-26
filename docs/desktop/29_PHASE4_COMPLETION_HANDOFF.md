# CCB Desktop Phase 4 Completion Handoff

更新时间：2026-09-26

## 1. Phase 4 final status

**Phase 4 — Core Chat / Prompt / WorldBook：COMPLETE / ACCEPTED**

Final Phase 4 production implementation:

`0a89b111e93c3c1a2b9a24127608e0b25e4ef9c4`

All Phase 4 production slices are complete and integrated:

- 4A1 Shared Chat Entity Contract Core — COMPLETE / PROJECT REVIEW PASS
- 4A2 Shared Chat Repository + Session Creation — COMPLETE / PROJECT REVIEW PASS
- 4B Context + WorldBook Request Runtime — COMPLETE / PROJECT REVIEW PASS / INTEGRATED
- 4P Main-chat Prompt Ownership Closure — COMPLETE / PROJECT REVIEW PASS / INTEGRATED
- 4C Shared Prompt + Logical Request Assembly — COMPLETE / PROJECT REVIEW PASS / INTEGRATED
- 4D1 Desktop Fake Chat Runtime — COMPLETE / PROJECT REVIEW PASS / INTEGRATED
- 4D2 Prompt Inspector + Phase 4 Acceptance — COMPLETE / PROJECT REVIEW PASS / INTEGRATED / PACKAGED MANUAL ACCEPTANCE PASS

The GitHub `desktop` branch containing this file is the handoff control point for new GPT/Codex conversations. Verify its exact SHA before any Phase 5 implementation work.

## 2. Upstream / compatibility control

Formal compatible upstream baseline remains:

`ChatChatBar 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`

Latest observed upstream at Phase 4 close:

`354f15166d8bc0462cb87d62a0ba4613794560a3`

Observed drift:

- 1 commit ahead of formal baseline
- 12 changed files
- classification: HIGH
- decision: **NO SYNC / queued for selective-batch sync review**

The observed commit includes a main-chat Prompt literal change plus NovelAI character-position work. Phase 4 did **not** absorb that Prompt change.

Do not claim compatibility with `354f151...` until an explicit sync/revalidation cycle promotes the baseline.

Before beginning Phase 5, perform a fresh upstream watch. Do not auto-sync solely because drift exists.

## 3. Authoritative Phase 4 shared core

Phase 4 established the following authoritative JVM-neutral/shared contracts:

### Chat/session persistence

- `ChatSession`
- `ChatMessage`
- `ChatRepository`
- source-turn / ordering / repair / timeline / display-title policies
- `CharacterSessionService`

Session creation contract:

- missing Character → explicit failure
- session title = current CharacterCard name
- only a live nonblank Character default FormatCard binding is copied
- stale/blank default FormatCard binding → null
- later Character default changes do not mutate existing sessions
- opening greeting is always persisted as first ASSISTANT, including blank greeting

### Context / WorldBook

- `PlaceholderRenderer`
- `ContextWindowManager`
- `WorldBookEngine`
- `WorldBookScanContext`
- `WorldBookRequestPlanner`

Preserved WorldBook request semantics include:

- source order: embedded → boundWorldBookId → card worldBookIds → session extraWorldBookIds
- duplicate ID: later/repository object wins, first occurrence position retained
- effective scan depth across books/enabled-entry overrides
- repository scan snapshot + optional transient current input
- character token extraction
- composite `<bookId>::<entryId>` timed key with legacy plain-ID fallback
- BEFORE_CHAR then AFTER_CHAR in one normal block
- OUTLET excluded from normal block and collected separately
- planner returns proposed timed state; caller decides persistence

### Main-chat Prompt authority

D-030 was explicitly approved and implemented.

`MainChatPromptAuthority` is the authoritative physical owner for Phase 4 main-chat Prompt literals/builders.

Android `PromptTemplates` remains an upstream-compatible facade.

4P guarantees:

- Prompt literal text unchanged
- Prompt runtime behavior unchanged
- Android facade behavior preserved
- no second Android/Desktop copy for moved Prompt literals
- observed `354f151...` Prompt drift not absorbed

### Logical request assembly

Authoritative shared request core includes:

- `PromptAssembler`
- `PromptCachePromptLayers`
- `PromptCacheKeyFactory`
- `ChatHistoryPromptPolicy`
- `ChatRequestMemoryPolicy`
- `FormatCardUserToolPolicy`
- `RetrievedKnowledgeCard`
- `FormatPromptPosition`
- `ChatApiMessage`
- minimal `RoleplayStatusStripper`
- `MainChatRequestAssembler`

Authoritative final logical order:

1. core system + creator identity
2. first acknowledgement ASSISTANT
3. creative contract USER
4. contract confirmation ASSISTANT
5. optional START requirements
6. character/stable context
7. setting reference = WorldBook + non-memory RAG
8. supplementary
9. player
10. context approval ASSISTANT
11. Archive
12. earlier-history heading
13. earlier history
14. memory RAG
15. HEAD/timeline
16. previous-turn heading
17. previous-turn messages
18. continuation SYSTEM
19. current USER exactly once
20. post-history + optional END requirements
21. optional STRONG_PROMPT_SUFFIX
22. post-user acknowledgement ASSISTANT
23. final identity-reminder USER

`BOTH` means the same requirements at both START and END.

The stable cache prefix ends at context approval.

Phase 4 ends at transport-neutral logical `ChatApiMessage` + logical cache key.

## 4. Desktop Phase 4 runtime / Inspector

Desktop common request planning:

- `DesktopChatRequestPlanner`

Fake persisted chat runtime:

- `DesktopFakeChatRuntime`

Read-only inspection:

- `DesktopPromptInspector`
- `DesktopPromptInspectorController`
- `DesktopPromptInspectorPanel`

The fake runtime and Inspector use the same Desktop planning authority and shared request assembler.

Inspector guarantees:

- logical messages come from authoritative assembler output
- message source/provenance comes from assembly-time trace, not text heuristics
- stable-prefix membership/messages/cacheability/key come from assembler authority
- WorldBook evidence comes from planner/engine debug path
- persisted timed state and proposed timed state are shown separately
- Inspector is strictly read-only
- read-only repository paths do not repair/recreate the persisted message index
- Inspector is explicitly transport-neutral and is not provider request serialization

Normal fake runtime may persist changed WorldBook timed state; Inspector never does.

## 5. Phase 4 final validation

P4-S8 / 4D2 final automated validation:

- MainChatRequestAssembler focused: 6 PASS
- Desktop Prompt Inspector focused: 5 PASS
- existing DesktopFakeChatRuntime: 10 PASS
- CharacterSession Desktop integration: 1 PASS
- WorldBook planner Desktop integration: 1 PASS
- sharedCore: 52 suites / 358 tests / 0 failures
- desktopApp: 33 suites / 272 tests / 0 failures
- Android JVM: 163 suites / 1030 tests / 0 failures
- Desktop compile: PASS
- Android compile: PASS
- `git diff --check`: PASS
- `:desktopApp:createDistributable`: PASS

User manual acceptance:

- packaged executable launch / UI-smoke / Prompt Inspector empty-state and all executable manual checks: PASS
- populated-session Inspector manual visual scenario: NOT RUN because no disposable persisted Desktop chat fixture was available
- equivalent populated-session semantics are covered by real-repository automated integration for persisted USER / restart / logical request / WorldBook / cache / Inspector zero-write: PASS

This limitation is non-blocking and must not be rewritten as if the populated-session visual check was manually performed.

## 6. Phase 4 Codex telemetry

Phase 4 implementation weekly actual, derived from completed slices:

- P4-S1: 8%
- P4-S2: 2%
- P4-S3 / 4B1: 3%
- P4-S4 / 4B2: 3%
- P4-S5 / 4P: 3% (actual execution was Medium after accidental selection)
- P4-S6 / 4C: 9%
- P4-S7 / 4D1: 4%
- P4-S8 / 4D2: 9%

Derived total: **41% weekly**

Program Budget total envelope: **39–61% weekly**

Phase 4 overall actual therefore remained within Program Budget.

P4-S8 actual:

- GPT-5.6 Sol / High
- runtime 23m05s
- 5h burn 58%
- weekly burn 9%

## 7. Phase 5 boundary

Next stage:

**Phase 5 — Model Runtime + Real Chat**

Phase 5 owns:

- ModelConfig/runtime boundary needed by real requests
- model discovery
- provider/auth integration
- ProxyAwareClient
- StreamingChatService
- SSE
- thinking/reasoning controls
- local HTTP behavior
- cancellation
- transport/debug request behavior
- Desktop background/task runtime as required by real chat

Target vertical acceptance eventually becomes:

```text
import card
→ create session
→ greeting
→ user message
→ WorldBook
→ final shared logical request
→ provider transport
→ stream response
→ persist assistant response
→ restart
→ continue
```

Phase 5 must preserve the Phase 4 logical request authority rather than reimplement it.

## 8. Phase 5 non-negotiable boundaries

Do not collapse these layers:

1. Package transfer schema
2. persisted Entity
3. logical Prompt / ChatApiMessage
4. provider-specific serialized transport request

Phase 4 owns layer 3 through the transport-neutral boundary.

Phase 5 may adapt/serialize logical messages for provider transport, but must not silently rewrite the shared logical Prompt contract.

Cleartext/local HTTP role adaptation remains transport behavior, not logical Prompt behavior.

Do not move Provider secrets into ordinary JSON. Desktop SecretStore remains a later/Phase-5 platform concern and Windows implementation must use OS protection.

Do not change Prompt literals merely while implementing provider transport.

## 9. New GPT conversation startup

Read first:

1. `00_PROJECT_SOURCE_MAP.md`
2. `10_UPSTREAM_BASELINE.json`
3. `21_CURRENT_STATE.md`
4. relevant upstream Skills
5. `13_FEATURE_PARITY.md`
6. `14_UPSTREAM_COMPAT.md`
7. `28_PHASE4_CONTRACT_AUDIT.md`
8. this file
9. `23_CODEX_BUDGET.md`
10. `24_CODEX_USAGE_EMPIRICAL_BASELINE.md`

Then:

- verify current `desktop` HEAD
- perform fresh upstream watch
- plan/audit Phase 5 before issuing production Codex work
- keep observed HIGH drift separate from the formal baseline until explicitly synchronized

## 10. New Codex conversation startup

The fresh Codex thread should receive only concise execution context:

- repo: `MisakaPiano/ChatChatBar-Desktop`
- local path: `H:\ChatChatBar-Desktop`
- `master` mirrors upstream only
- work starts from the exact current `desktop` SHA supplied by Project
- formal compatible upstream baseline: `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
- Phase 0–4 complete; Phase 4 production implementation SHA: `0a89b111e93c3c1a2b9a24127608e0b25e4ef9c4`
- Phase 4 shared authorities listed above are not to be forked/reimplemented
- next work is Phase 5 only after Project provides the audited slice/task
- observed upstream `354f151...` is HIGH / NO SYNC unless Project changes that decision
- never merge to `desktop` without Project review
- never write upstream
- never change docs/skills unless the Project task explicitly allows it

The old Codex thread is historical after this handoff.
