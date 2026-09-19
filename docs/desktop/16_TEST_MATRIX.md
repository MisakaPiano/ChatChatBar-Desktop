# Compatibility test matrix

## Build
- Android compile
- shared JVM tests
- Desktop compile/run
- native package smoke test

## Storage
- save/load/restart
- atomic replacement
- transactional prefix replacement
- custom data root
- Portable Mode
- migration snapshot / backup / restore

## Character / Package
- Character schemas 3..9 read
- current schema write
- FREEFORM / STRUCTURED
- default FormatCard
- Android -> Desktop -> Android JSON/PNG roundtrip
- ST V1/V2/PNG/World Info compatibility

## FormatCard
- v1/v2
- user tool order
- strong prompt suffix
- embedded default-card import/reuse

## WorldBook
- keys / secondary logic
- regex / probability / groups
- recursion
- sticky / cooldown / delay
- filters / budgets
- outlet / roles / positions
- timed effects
- same input snapshot -> same selected IDs/order/reasons

## Prompt
Compare final logical/serialized request:
- roles/order
- character/worldbook/RAG/persona
- Archive/history/memory RAG/HEAD
- previous turn/current user/post-history
- format suffix/final tail
- local HTTP role adaptation

## Model
- auth / fallback / discovery
- SSE EOF / finish / refusal / length
- cancel / timeout
- thinking / token parameter policy
- custom params

## RAG / Memory
- chunk/embed/search/retrieval
- Episode/Arc/Era
- Archive/HEAD
- Gap/backfill
- repair/regeneration/cancel

## SaveSlot
- schemas 1..7
- .cbsave v8 manifest/messages/rag/voices/media
- image modes / audio
- cancellation/rollback
- Android <-> Desktop restore

## Extended systems
NovelAI, Fish, Moments, Community, Shared Import and Update each require domain tests plus Desktop platform smoke tests.

## Release gate
Also require clean install, upgrade, backup restore, secret scanning and Windows packaging.
