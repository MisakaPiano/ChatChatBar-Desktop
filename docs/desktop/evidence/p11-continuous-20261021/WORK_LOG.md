# Continuous delivery through P11 — work in progress

User authorization: 2026-10-21 JST, continue directly through P11, self-check and repair without another Project review prerequisite or fragmented manual gates. Serious data safety blockers still require an honest report. Unmeasurable behavior is NOT TESTED/INCONCLUSIVE, never fabricated PASS or product failure. No publication authorization.

Starting branch/HEAD: `feature/phase8-fish-audio` / `db198c096a5b03a42e8deff020195db3fd911f3c`. Tracked/index clean; four protected user files checked only by path. Formal upstream and all historical evidence/packages retained.

## Core UI implementation checkpoint

- User toolbar: right-aligned copy/edit only.
- Assistant menu: first two actions equal-width, centered; popup bounded to host viewport, row scrolling retained.
- Voice dock: left status/cancel, fixed center player, right narrator in one row. Names truncate only under width pressure; dropdown rows use common width.
- Recording rows: compact playback/duration/activity, shared ellipsis/secondary-click popup. Existing click ownership, pause/resume, native close and maintenance policies unchanged.
- Screenshot selection and renderer: text/images only; rejects recording IDs before raster/resource work. No voice-only reply support is claimed. Shared Android block policy unchanged.
- PNG preview: separate resizable owned child of selection workspace, independent close/Escape, dual-axis numeric scrolling, Fit Width/Window/100%, unchanged export raster and verified no-overwrite save. Sub-1% label uses decimals.

Engineering evidence: `core-ui/01-screenshot`: 5 suites/24 tests, 1 RED. Old test shell had not injected the now-independent preview window and timed out looking for embedded preview. `core-ui/02-core-ui`: 12 suites/62 tests, 0 failures/errors/skips. Shell injection corrected and Escape asserts preview returns to selection before workspace closes. RED/XML/logs retained; assertions for retained host and no network kept. Offscreen tests do not claim Windows native/manual acceptance.

The first runner setup attempt hit a PowerShell file self-pipeline sharing error and sandbox denied CIM. No Gradle tests ran in that attempt. Runner corrected to read content before write; authorized resource read and offline JDK17 single-worker tests then ran. An empty `01-screenshot` directory was created under the old ignored boundary build scope before the runner path was corrected; no old file/package/evidence was overwritten.

## Remaining implementation work (not completed)

- P11 SaveSlot legacy/v8 create/list/import/export/restore/duplicate; full-app official 1.4.5 backup; existing session resource rebinding.
- Preview Gate preparation and honest pending cross-device/native evidence. No release/parity status promotion.

Exact official 1.4.5 backup reference files were fetched read-only at `1198a13cf3008de1358b8a69aa5e40b64797c8d9` into the new ignored build scope. No upstream branch synchronization, baseline change or remote mutation occurred. Public source metadata and blob identities are retained during implementation.

## P9/P10 implementation checkpoint

P9 is implemented through bounded document publication/recovery, official chunking and embedding identities, history-only CHAT_MEMORY indexing, split multi-route retrieval, visible failure/deferred states, protected embedding settings and real-chat request planning. Network work precedes revalidated publication; idle-only audio reservation precedes exclusive index writes. Production retrieval reads use normal admission and do not stop paused audio. Index publication checks the root lock and keeps forced undo evidence; uncertain recovery seals restart. No long-term-memory nodes are indexed as raw history.

P10 moves the official complete LongTermMemoryService/coordinator/repository and policies into sharedCore, preserving Android adapter entry points. Episode/Arc/Era, Archive/HEAD, gaps/backfill, source repair, node/revision editing, regeneration and explicit compression decisions use one state machine. Desktop uses strict reads, real normal-operation leases and an application-owned cancellable scope. Stop joins manual and automatic actions and keeps admission sealed through recovery. Full regeneration journals derived-state reset; raw messages remain intact. Memory/session field updates now merge against the latest session, avoiding rollback of a concurrent message preview or setting.

Desktop UI provides the memory window, tiers, current HEAD, edits, history review/restore, node candidate generation, backfill estimates/progress, source repair and stop. Production planning uses shared Archive/history/RAG/HEAD ordering and retains pending raw sources; the read-only inspector explicitly explains omitted model-backed work. Exact original Memory/RAG Prompt literals/builders moved to MemoryRagPromptAuthority with Android facades. No intentional Prompt text/order or Entity/schema change.

Tests: stage 20 has 16 Desktop suites / 188 tests, zero failures/errors/skips. Stage 17 also contains the fresh shared-memory XML; subsequent shared tasks were UP-TO-DATE and are not counted again as fresh tests. Consult p9-p10/test-ledger.json for every attempt. Stages 03–20 are additive raw logs/XML; all RED preserved. Stage 16 found a real stale session write during background memory refresh; the fix preserves fields outside the current mutation and adds signal-controlled regression. Stages 18/19 exposed invalid synthetic Archive/header and pending-source fixtures; tests now use the official Archive marker and the actual User source turn rather than the automatic greeting. Safety assertions were retained.

Embedding requests are one-shot (including HTTP 503/408 retry hints). Desktop memory responses are bounded to 1 MiB, and budget failures are terminal. All provider interaction in this checkpoint is loopback/fake; real API count is 0. This checkpoint does not assert live-provider, native Windows UI, Android-device, Preview or full-parity acceptance. P11 remains in progress.

The user explicitly rejects fragmented manual gates while core requirements are unfinished. Unified manual acceptance must follow the requested UI work and P9/P10/P11 engineering, not substitute minor technical checks for those deliverables. Tests that cannot be performed remain NOT TESTED/INCONCLUSIVE; preserved historical failures are not erased.

P9/P10 compatibility: stage 21 Android compileDebugKotlin PASS (JDK 17/offline/single worker), no APK/device run. Stage 17 shared-memory result: 31 suites / 156 tests PASS. Stage 20 Desktop: 16 suites / 188 tests PASS. These are separate suite populations; repeated prior attempts are not added into the final 344-test claim.

Production checkpoints: core UI `ab3d06abcaca2df8b21c734841d7c4e744099f51`; P9/P10 `f985a012d8edc33aa4e9e197c49a9fdb09368d05`. This is a preservation checkpoint; continuous P11 work proceeds without requesting another manual gate.

## P11 engineering completion

Earlier remaining-work sections above are historical checkpoints. P11 engineering and final adjacent regression are now complete at `c609e757cca94dca20015e91279c9629e5782ea6`; see `FINAL_DELIVERY.md` for exact scope, candidate identity and honest NOT TESTED gates. No historical acceptance status was promoted.
