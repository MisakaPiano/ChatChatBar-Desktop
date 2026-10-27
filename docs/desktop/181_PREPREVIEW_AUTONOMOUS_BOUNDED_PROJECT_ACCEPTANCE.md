# 181 — Pre-Preview Autonomous Engineering: Project Bounded Acceptance

**CURRENT PROJECT DECISION — 2026-10-24 JST — TWO SOURCE FIXES CLOSED / QUALIFIED ENGINEERING ACCEPTED WITH DECLARED LIMITS. OVERALL PREVIEW ENTRY, P11 AND D-038 NOT ACCEPTED.**

This is a scoped review of the user's long Codex run and authorized **private real-Android-export testing**, not release certification or approval to write existing production user roots/SecretStore. The private real archives, private hashes, detailed data, tool console logs and isolated test root were intentionally **not** provided to Project/GitHub; those outcomes remain **Codex-attributed local real-file evidence**, not Project independent archive-byte verification.

## 1. Exact Git source control

| Point | SHA | Boundary |
|---|---|---|
| Start | `cac1a811a21e6f99181c716e438b6b8df8dbf4f7` | T1 qualified acceptance + 179 draft |
| First source/evidence | `8fd4f337b20d566b4104263c6c8461557de36fdb` | Hidden-byte export refusal + synthetic tooling/record |
| Second source/evidence | `5160acbc7c5490f1b65a86152bf620b5d2ea393e` | SaveSlot bounded media/legacy version + isolated proof |
| Final delivery | `743adbaad53c5d8d48928b93d69e542b25b910e2` | Review packet, receipts and Windows package evidence; **no later production edit** |
| Unmoved `desktop` | `5ef95428421f7c693b010ac88f51d470804ab07a` | No integration/merge |
| Formal upstream | ChatBar 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` | No baseline change |
| Selected backup wire | official 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` | D-038 only; not a full upsync |

[Full diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/cac1a811a21e6f99181c716e438b6b8df8dbf4f7...743adbaad53c5d8d48928b93d69e542b25b910e2) · [Codex handoff](evidence/prepreview-autonomous-20261023/PROJECT_REVIEW_HANDOFF.md) · [raw evidence index](evidence/prepreview-autonomous-20261023/RAW_EVIDENCE_INDEX.md).

The only **existing production Kotlin** modifications are:
- `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupMapping.kt`
- `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopSaveSlotInspector.kt`

No existing Prompt/Entity/SharedCore/Android, codec, SecretStore, StartupGate, restore journal, GUI or formal Gradle source was modified. Supporting Kotlin/Python test-only tools and new synthetic tests are distinct from production activation. No G1B topology, persisted identity, transaction/namespace schema, G2C, real credential write, `desktop` merge or release.

## 2. Independent source-level review

**Hidden bytes:** `DesktopFullBackupMapping.inventory()` now refuses any registered-root file beneath a dot-prefixed path component before the old temporary-file suffix exclusions can hide it, and rejects other dot-prefixed files unless already recognized as an unfinished single-file publication. `export()` calls inventory before creating scratch. This converts the tested prior **silent successful omission** to `UNCLASSIFIED_HIDDEN_BACKUP_ENTRY` without altering original source bytes. Known original single-file `.part/.tmp/.backup` and explicit derived-cache exclusions remain. This is **a fail-closed repair**, not a production owned Moments seal, full hidden-domain inclusion, proof that every excluded temporary-looking file is always disposable, or a complete lossless D-038 backup.

**SaveSlot streamed media:** `DesktopSaveSlotInspector.zip()` separates the bounded total stream/expanded budget (256 MiB) from bounded non-media/structured bytes (64 MiB). Existing source<=256 MiB, per-entry<=16 MiB, record<=1 MiB, manifest<=256 KiB, entry count, compression-ratio, strict-schema and CRC checks remain; media is consumed with a fixed buffer. Source files are not changed. Scope is external-file **inspection**, not an automatic authorization to restore.

**Legacy voice version:** `DesktopSaveSlotInspector.Records.message()` obtains version IDs via existing shared official `MessageAlternativeVersionPolicy.versions(message)`, consistent with current restore-preparation normalization. The submitted test oracle and pinned official 1.4.5 source distinguish singleton legacy message ID, deterministic legacy alternative ID, and an invalid explicit version, which remains refused. No Prompt/Entity/voice-content heuristics or unknown version rebinding was observed.

**Private isolated restore tool:** `SlotIsolatedRestoreProbe` lives under opt-in `tools/desktop/interop/prepreview/`, not wired to AppContainer or production GUI. It creates a new task-private root, invokes the existing `DesktopSaveSlotService.import/restore`, compares message identities/content/version/source-turn and media-byte SHA multisets, closes owners and reopens repositories in the same JVM. This is stronger than format-only inspection but is **not** a physical OS restart, full session/RAG/Memory parity, Android round-trip, Provider continuation or SecretStore test.

## 3. Execution evidence, provenance and correction

Project **directly read 50 unique original JUnit XML** for the principal final and real functional RED rounds, reconciling each XML suite counter against actual `<testcase>` elements:
- Hidden-data final: **15 suites/186 tests PASS/0 failure/error/skip**; genuine prior RED 3 tests, **2 FAIL**.
- Bounded SaveSlot-media final: **7 suites/65 tests PASS/0 failure/error/skip**; genuine prior RED 3 tests, **1 FAIL**.
- Legacy voice/session final: **8 suites/58 tests PASS/0 failure/error/skip**; genuine prior RED 3 tests, **2 FAIL**.
- P8–P10/update/stream scope: **13 suites/55 tests PASS/0 failure/error/skip**.
- Container tool: **2 suites/8 tests PASS/0 failure/error/skip**.
- Isolated probe synthetic and runner timestamp: **one test each, both PASS/0 failure/error/skip**.

These are **separate overlapping execution rounds; they cannot be summed into a unique-test total**. Their genuine RED is retained without aggregation. The early `slot-budget-functional-red` copied **two stale XML suites / 8 apparent testcases** after a Gradle cache-packer interruption; its explicit `JUNIT_DISQUALIFICATION.json` marks **0 actually executed tests**, and the corrected `snapshot-checkpoint2final/rounds.json` does not count them. The original incorrect snapshot remains retained with adjacent correction. Python census original and intermediate each failed 1 of 8; the final real Python `unittest.log` reports **8 PASS**. Build/compiler failures are not JUnit failures.

Project independently recalculated **16/16 selected raw public Git evidence SHA-256 and UTF-8 byte lengths** against `raw/final-index.json`; **7/7 selected current source Git blobs** matched frozen `snapshot-checkpoint2final/source` evidence. Project read the final `package-903/build.log` with `:desktopApp:createDistributable` and `BUILD SUCCESSFUL in 2m 16s`, plus `exit.txt=0`, identity metadata and 217-entry app-image manifest. **Project did not rerun Gradle/Python, inspect the actual 217 packaged file bytes, or launch a Windows GUI.** Codex full-index self-check `90/199/233` and 23 source identities remains **Codex-attributed**, not Project independent verification.

## 4. Real Android export evidence — strictly differentiated

Codex's fixed-category local tool-response receipts and reviewed opt-in tool sources support a reported:
- **REAL_ANDROID_ARCHIVE_READONLY:** one encrypted code86/v2 full archive (~1.34 GiB) streamed to authenticated EOF, supplemental local ZIP CRC/inventory checked across **5,625 records**; real manifest/transport code86/v2 and an unproved Android-app origin. **No** production S0/S1/G2B semantic admission, central-directory vs local-header verification, full entity/scope/reference graph, credential transfer, Moments seal or full restore. This archive is **not** official 1.4.5 code84/v1 golden Android→Desktop migration proof. The production S0 64 MiB full-backup limit was not silently raised.
- **REAL_ANDROID_ARCHIVE_ISOLATED_RESTORE:** one actual schema8 single-session archive via existing Desktop service into a new private local root. Fixed-token response reports **59 messages / 33 images / 244 audio entries**, scoped text/version/turn and media-byte comparison, source SHA unchanged in private memory, and **same-JVM repository reopen** counts. Original private archives/logs/hashes were withheld; Project **did not** examine or rehash real user bytes.
- **WINDOWS PACKAGE_BUILD:** source commit `5160acbc`, version `0.1.0-preview.903`, current 217-file app-image evidence. **Not** executed installer, update UI, OS-process restart, Windows DPAPI migration, Android physical-device use or release.
- **PRIVACY:** public changed-file audit lists 272 tracked files with zero detected private source filename/raw archive magic hits; Project read selected new public source, report/receipt/index/log evidence and saw no actual archive/private text. This is **not** independent exhaustive secret scanning or guarantee against arbitrary disclosure. Real data remains under authorized user-local ignored directories; no Project/GitHub read of original private archive.

## 5. Remaining blockers, unchanged

`G1B OPEN`: A/B topology and per-root protected key binding, legacy shared key retention/migration consent, CommunitySession protected sink, durable data+Key transaction, root authority/restart recovery, Portable/clone/protector ID and enforceable old-binary downgrade barrier remain undecided/unproven. `G2C NOT AUTHORIZED`. Do not bypass `CREDENTIAL_APPROVAL_REQUIRED`.

`P11 PENDING`: actual `.cbsave` Android physical round-trip with reopen/continue, full SaveSlot session/RAG/Memory/voice semantics, independent OS restart remain required. C-A original MomentPost/Task JSON and owned media seal/backup/GC consistency remain unimplemented.

`D-038 BLOCKED`: official 1.4.5 full-app archive genuine Android→Desktop, Desktop→Desktop, supported Desktop→Android interoperable data/credentials/unknown bytes and crash-consistent complete restore remain not proven. Large code86/v2 needs separate bounded real semantic admission work and is not interchangeable with D-038 exact-source target.

`Public Preview A–G HOLD`: clean Windows installer, N→N+1 upgrades, uninstall retained roots/DPAPI, rendered update UI, actual OS restart, D-016 direct author permission, upstream drift impact and required Android real-device evidence remain outside this run. `Post-P8 HOLD` remains.

**Disposition: CLOSED / QUALIFIED ENGINEERING ACCEPTED for exactly the two production safety fixes and bounded evidence. PREVIEW-ENTRY OVERALL NOT ACCEPTED / NO PUBLIC RELEASE.** No new production data-root, restore guard, SecretStore, Prompt, schema, parity, validated baseline or protected-branch change is approved. The next high-value work should be a separately authorized concentrated P11 safety/format engineering block and external-platform evidence plan, not repeating T0/T1 or counting more format-only tests as full migration.
