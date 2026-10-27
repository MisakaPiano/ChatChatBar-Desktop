# P11-S0 bounded read-only inspector — candidate evidence

Date: 2026-10-15 JST. PROJECT REVIEW PENDING. No P11 import/restore or full parity acceptance.

## Linear checkpoints and source identity

- Initial feature: `ac029e18e4a9d3cb9ac2414eb5389070b5dae3f2`.
- A, Project P9-S1 decision registration: `bf62645a4ce1f4eb873e0f4e3be5d8abeed40a58` (docs only).
- B, P9-S2 source audit: `5efedcda73d4964bc8f6b8e6827186f8ade5927b` (docs only; indexing NOT IMPLEMENTED).
- C, inspected source: `d93eaf51d9ac13cd29cc97fe1b8b13e8a7d6a50a`, parent B. 12 logical files, +636/-5; includes a 100% rename. Skill path ownership updated before commit. D is this independent evidence commit.
- `source-manifest.json` identifies each C file by working-file SHA-256 and committed Git blob. SaveSlot model moved byte-identically, blob `02bbd09d50dc501947f4b831ba6a8213d73418de`; package, fields, defaults and serializers unchanged.
- One Android non-null access was adapted after actual cross-module compiler RED. No exporter/restore behavior change. No second SaveSlot schema.
- `unchanged-authorities.json` checks initial-to-C identical blobs for storage, shared chunking/search, B5B safety and Prompt assembly authorities.

## Delivered boundary

Read-only inspector and existing native OPEN picker adapter, exposed by the same DesktopAppContainer. A synthetic picker integration test does not operate a real OS window. Management UI PENDING: synchronous native chooser, visible cancel/shutdown/leave integration require separate review; service-first is allowed by the task. No import/save/restore delegate, extraction, entity writes, journal replay or data-root migration.

Content-based legacy JSON (schema 1–7) / v8 ZIP recognition; strict shared serializers and bounded ZIP/JSONL validation; CRC/size/reference/identity checks. ZIP_CONTAINER is not called ZIP_V8 until manifest validation. Report contains fixed status/codes/counts, no content or private source paths. See contract 121 for budgets and limitations. Source file SHA-256 stays unchanged through success, rejection and cancellation tests.

Fixture corpus: `docs/desktop/fixtures/p11-s0-corpus.json`. Fixtures are reproducible SYNTHETIC temporary inputs, not official-exported golden. Official Android exporter execution: PENDING. PRIVATE_REAL_WORLD: NOT AUTHORIZED / NOT READ. Synthetic schema-tag coverage is not evidence from old APKs. Voice/RAG/Memory parsing does not imply Desktop playback, source-fingerprint reconciliation or recovery support; codecs not verified. `imported=false`, `desktopCanRestore=false` always.

## Final executed results (do not add intermediate runs)

| Evidence | Suites | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| junit-08-desktop-final | 4 | 48 | 0 | 0 | 0 |
| junit-04-shared-final | 3 | 8 | 0 | 0 | 0 |
| junit-07-android-final | 1 | 7 | 0 | 0 | 0 |

Desktop: inspector 14, DOCUMENT reader 18, B5B derived safety 15, main-request assembler integration 1. SharedCore: strict scan 3, ChatMessage serialization 3, shared SaveSlot contract 2. Android: unchanged original SaveSlot serialization suite 7. Android includes existing 2,000-message streaming roundtrip test, not real Android-to-Desktop import/restore.

Main/test compilation actually executed in the logged stages. Later `UP-TO-DATE` prerequisites are reported as such, not new compilation. Gradle configuration checks marked SKIPPED are not skipped JUnit tests. No full regression, package, GUI/manual test, official export, real archive roundtrip, embedding or paid Provider request.

## Commands, chronology and preserved failures

From `H:\ChatChatBar-Desktop\app`, `& .\gradlew.bat @arguments`. Every exact argument, start/end timestamp, seconds and exit code is preserved in each numbered JSON, together with raw log. Runner uses existing Temurin17 and existing 2GB heap; no clean or rerun-tasks. SDK was already installed; after stage05 only child-process ANDROID_HOME/ANDROID_SDK_ROOT were set. No tracked configuration or dependency changes/install.

- 01: initial inspector 1/12 PASS.
- 02: strengthened inspector and adjacent tests 4/47 PASS.
- 03: 2/5 PASS, but the requested `domain.chat.ChatMessageSerializationTest` selector matched no class. It is NOT counted as executed, skipped or PASS. Stage04 corrected package to `data.repository`.
- 04: final SharedCore 3/8 PASS.
- 05: FAIL before Android task execution: SDK location not found. No XML generated.
- 06: FAIL in actual Android Kotlin compilation: public cross-module nullable `packageRef` cannot be smart-cast, SaveSlotPackageStorage.kt:451. No XML generated. Fixed with `requireNotNull(decoded.packageRef).copy(...)` after the existing guard; no model/schema change.
- 07: actual Android main/test compilation and original SaveSlot suite 1/7 PASS.
- 08: actual final Desktop main/test compilation and four suites 4/48 PASS after final identity/reference hardening.

All historical and final XML (15 files) and all eight logs are retained independently; zero historical failures were rewritten. `junit-manifest.json` and `log-manifest.json` record original-byte SHA-256. `.gitattributes` preserves CRLF/raw XML and log bytes rather than Git newline conversion; whitespace suppression applies only to raw evidence, not source/Markdown.

## Resource and confidentiality boundaries

Single Worker, offline, no daemon reuse (Gradle forks a single-use daemon for configured JVM flags), no parallel, low priority, in-process Kotlin, existing -Xmx2048m. No concurrent Gradle builds. `resources.jsonl` has sanitized sampled Java PID/Working Set/private bytes and Windows counters only, no process command lines or window titles.

Initial available 2233.91MiB, commit headroom 11.37GiB, H: free 38.00GiB. Lowest observed available 202.10MiB during Android, 2020 pages/s, commit headroom 8.19GiB. This was a warning sample, not evidence of sustained <256MiB plus >8000 pages/s; no observed <128MiB, OOM or write error. Highest sampled single Java WS 2,596,073,472 bytes; private 2,979,045,376 bytes. Actual peak UNKNOWN (periodic samples). After Android: Java count0, available2830.09MiB. Last during Desktop:1626.65MiB, commit headroom10.47GiB. No Chrome/NAI interaction, process termination, paging/system configuration changes or cleanup.

Raw log/XML review and secret-pattern checks found no credentials/private keys/tokens/auth headers/private user content. Fixtures are isolated synthetic data; no real `.cbsave`, `.cbbackup`, production root or Secrets read. Source paths and local build environment paths in compiler diagnostics are developer paths, not user chat archive paths. No API requests; no certificates/media archives uploaded. Account weekly used reading:62% (shared account; not exact task charge).

## Remaining gates

P9-S2 write publication/crash recovery: source audit only, NOT IMPLEMENTED. P11 restore/import, actual official-exported golden, real cross-platform roundtrip, native management UI, user manual acceptance and full regression: PENDING / NOT RUN. Public Preview, P8 Fish, P10, D-040 implementation and upstream1.4.1 baseline unchanged. F4/B5B safety is not weakened. Project must independently review the candidate; no merge authorization.

## Per-command measurements
- **01-inspector**: exit 0, 86.952s, 10/15/2026 08:22:10 → 10/15/2026 08:23:37.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :sharedCore:compileKotlin :desktopApp:compileKotlin :desktopApp:compileTestKotlin :desktopApp:test --tests com.example.chatbar.desktop.DesktopSaveSlotInspectorTest`

- **02-desktop-focused**: exit 0, 61.699s, 10/15/2026 08:25:10 → 10/15/2026 08:26:11.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :desktopApp:test --tests com.example.chatbar.desktop.DesktopSaveSlotInspectorTest --tests com.example.chatbar.desktop.DesktopDocumentRagReaderTest --tests com.example.chatbar.desktop.DesktopFormatRepairDerivedSafetyTest --tests com.example.chatbar.desktop.DesktopMainChatRequestAssemblerIntegrationTest`

- **03-shared**: exit 0, 33.241s, 10/15/2026 08:27:02 → 10/15/2026 08:27:36.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :sharedCore:compileTestKotlin :sharedCore:test --tests com.example.chatbar.domain.chat.SaveSlotSharedContractTest --tests com.example.chatbar.data.local.JsonFileStorageStrictScanTest --tests com.example.chatbar.domain.chat.ChatMessageSerializationTest`

- **04-shared-final**: exit 0, 20.724s, 10/15/2026 08:28:36 → 10/15/2026 08:28:56.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :sharedCore:test --tests com.example.chatbar.domain.chat.SaveSlotSharedContractTest --tests com.example.chatbar.data.local.JsonFileStorageStrictScanTest --tests com.example.chatbar.data.repository.ChatMessageSerializationTest`

- **05-android**: exit 1, 18.446s, 10/15/2026 08:29:36 → 10/15/2026 08:29:54.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :app:compileDebugKotlin :app:testDebugUnitTest --tests com.example.chatbar.domain.chat.SaveSlotSerializationTest`

- **06-android-sdk**: exit 1, 44.544s, 10/15/2026 08:31:11 → 10/15/2026 08:31:55.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :app:compileDebugKotlin :app:testDebugUnitTest --tests com.example.chatbar.domain.chat.SaveSlotSerializationTest`

- **07-android-final**: exit 0, 362.737s, 10/15/2026 08:32:55 → 10/15/2026 08:38:58.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :app:compileDebugKotlin :app:testDebugUnitTest --tests com.example.chatbar.domain.chat.SaveSlotSerializationTest`

- **08-desktop-final**: exit 0, 157.881s, 10/15/2026 08:40:01 → 10/15/2026 08:42:38.

  `--offline --no-daemon --no-parallel --max-workers=1 --priority=low --console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process :desktopApp:compileKotlin :desktopApp:compileTestKotlin :desktopApp:test --tests com.example.chatbar.desktop.DesktopSaveSlotInspectorTest --tests com.example.chatbar.desktop.DesktopDocumentRagReaderTest --tests com.example.chatbar.desktop.DesktopFormatRepairDerivedSafetyTest --tests com.example.chatbar.desktop.DesktopMainChatRequestAssemblerIntegrationTest`
