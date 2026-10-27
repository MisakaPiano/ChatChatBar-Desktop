# 184 — P11-LR2 Bounded Read-Only and Cross-Process Project Acceptance

**PROJECT DECISION — 2026-10-24 JST — LR2 CLOSED / QUALIFIED ENGINEERING ACCEPTED WITH DECLARED LIMITS. P11, D-038, G1B/G2C AND PUBLIC PREVIEW NOT ACCEPTED OR AUTHORIZED.**

This decision accepts test-classpath, opt-in safety validation and reported private real-input results *only within the observed bounds*. It does not sign a production recovery/format, physical Android interoperability, DPAPI, persistent Moment seal or release gate. The private real source archives, hashes, logs and source records are not Git material; Project did not directly access/recompute them.

## 1. Source and Git authority

| Item | Exact source |
|---|---|
| Authorized start | `feature/phase8-fish-audio @ 33789eba37eda8952a806671bcfd76eb95cead1c` |
| First tool/evidence checkpoint | `eb17445de1e4d67f34164749cc990a8f88d8882c` |
| Final code/evidence checkpoint | `31a551eb5319e287f872bd1f2657bd8eea1af489` |
| Codex docs-only delivery | `d8a94fdfb58466edb42d2c246c6c109c7a2a3f8e` |
| Protected desktop | `5ef95428421f7c693b010ac88f51d470804ab07a` (unchanged) |
| Formal upstream baseline | ChatBar 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` |
| Selected D-038 backup wire | Official 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` |
| Actual private newer source | Manifest code86/v2 (not an official 1.4.5/code84 real portability proof) |

[LR2 Project handoff](evidence/p11-lr2-20261024/P11_LR2_PROJECT_REVIEW_PACKET/PROJECT_HANDOFF.md) · [Finite executed test ledger](evidence/p11-lr2-20261024/P11_LR2_PROJECT_REVIEW_PACKET/FINITE_TEST_MATRIX.md) · [Source/limitations](evidence/p11-lr2-20261024/P11_LR2_PROJECT_REVIEW_PACKET/PROTOCOL_PARITY.md) · [Full starting diff](https://github.com/MisakaPiano/ChatChatBar-Desktop/compare/33789eba37eda8952a806671bcfd76eb95cead1c...d8a94fdfb58466edb42d2c246c6c109c7a2a3f8e).

GitHub's exact baseline and delivery **root tree** entries match for the entire `app/` subtree (`0237db3c547c7bb55b7a2229d497be0496db7f00`) and `.agents/` subtree (`79365460a113bd252b273864b9d8ff7b69821a40`). This independently establishes no tracked application code, formal Gradle, Android/shared source, existing Skill, Prompt, runtime restore, startup/SecretStore or business persistence changes. New Kotlin resides under `tools/desktop/interop/lr2/src/` and is opt-in through a test-source-only Gradle init script. No `desktop` merge, official upstream write or baseline promotion was performed.

## 2. Project source-level review

- **StrictStreamingZip:** bounded ZIP32 local records verified against central name, flags/method, offset, CRC, lengths, EOCD and actual input EOF before result publication; source remains a stream, with no extracted whole-archive plaintext file. Supports only its declared ZIP32 subset. ZIP64, split archive, stored descriptors, uncommon flags and alternative legal central-order cases are not accepted as a general official compatibility promise.
- **LargeArchiveReadOnly / LargeArchiveTool:** use existing AppBackupCodec, official-version transport, typed serializers, bounded JSON records and strict source path/inventory/scope checks. Source is an explicitly selected read-only file, and password uses masked console. The tool always reports `restoreSupported=false` / `SEMANTICALLY_BLOCKED`; a successfully parsed subset is **not** a complete portable graph or a license to bypass `CREDENTIAL_APPROVAL_REQUIRED`. Some metadata and JSON objects are retained in process memory, so bounded file input does **not** prove a production low-RAM streaming admission. No user secret text or source IDs should enter public reports.
- **NestedSlotClaims:** validates inner v8 structure, official count/reference conventions, named image/audio resources and a message/explicit-version relationship through the existing shared policy. The source actually refused; no guessed version, silent drop or false success was observed. However the exact *source-version meaning* of the affected real voice references is not yet proven: missing message owner and unknown explicit version remain a deliberately merged public category. **Do not label the user's original archive corrupt without further source-grounded diagnosis.**
- **SlotProcessProbe:** uses the *existing* DesktopSaveSlotService to write **one new task-owned isolated target** and a separate OS JVM for readback, ownership reacquisition, journal recovery, byte witness and scoped field/media checks. The synthetic child `halt(71)` occurs **after committed restore**, not at an arbitrary mid-transaction point. Foreign file tampering is rejected in the synthetic negative. This is stronger than same-JVM repository reopen but is **not** a Windows reboot, physical power-loss or Android real-device direction.
- **CaOwnershipPreflightTest / G1B report:** prove only synthetic raw-Moments preservation, three independent image edges/copies and capacity refusal; offer falsifiable A/B/B+fresh-root design. No published persistent seal, GC/backup/Portable participant, credential scope, OS protector proof, durable cross-domain intent or Community sink.
- Existing update checker is *rerun regression*, not an installed GUI/installer Gate B pass.

## 3. Independent original evidence review

Project **directly read all 11 original final `nested-classification-final` JUnit XML** and checked per-suite declared tests against actual `<testcase>` elements:

**11 suites / 113 tests / 113 PASS / 0 failures / 0 errors / 0 skips**, exact final Gradle log with `BUILD SUCCESSFUL in 2m 49s` and final exit `0`. Final113 comprises 27 new LR2 tests and 86 selected existing regressions, **not** cumulative historical run totals.

Project also read **five genuine earlier RED XML**, covering central-local filename discrepancy (1/1 fail), process-first and process-diagnostic (each 3 tests, 2 fail), resource-ID failure (1/1 fail), and logical source-name failures (2/2 fail). The central-red wrapper failed and emitted genuine JUnit failure, **but original Gradle numeric exit was not captured**; do not change it to a known Gradle exit1. `final-focused` 10/110 GREEN is **not** exact frozen final source: edits happened during that build. The final submitted snapshot and genuine later 11/113 are the accepted tested source boundary. Prepare-only rounds are **zero tests**, not additional passes.

Project independently matched **10/10 final Kotlin Git blob identities** against the original frozen final test-source snapshots. Project recomputed **8/8 selected original public evidence byte lengths and SHA-256**, confirming matching Git blob identities. For `nested-classification-final/gradle.log`, the original bytes were **UTF-16LE with BOM**; rebuilding UTF-16LE before hashing is necessary, not treating the decoded text as UTF-8. These are representative Project checks. Codex's `744` sealed index / `746` delivery-index checks and complete source-byte verification remain explicitly **Codex-attributed self-checks**, not Project-wide rehash. Project did not rerun Windows Gradle/JVM, inspect private raw archives, run a GUI or read original personal data.

## 4. Private real source, scoped evidence only

**Real SaveSlot:** fixed-token local tool-response transcription reports separate producer JVM and consumer JVM, same retained new isolated root, root lock reacquisition and session journal recovery, source/persisted private byte witness, **59 messages / 244 audio**, supported message and session fields, media bytes and **normalized current Memory** agreement. Existing image resources were compared by reference/media positions but the LR2 receipt does not independently promote a new image-count claim. Source RAG count is **0**; real nonempty RAG and universal RAG equivalence remain NOT TESTED, with separate synthetic RAG tests. Excluded canonical derived Memory source/coverage hashes and regenerated voice ID/anchor/duration/timestamp stay explicit. This is not live Provider continuation, full general SaveSlot parity or actual Android double-direction physical test.

**Real full backup:** the LR2 public transcript reports `LR2=REFUSED_ZIP_CONTENT_NESTED_V8_VOICE_VERSION_REFERENCES_RECORDS_3148`, process exit2. It means a bounded nested voice→message/explicit-version relationship could not be established; it does **not** prove the official archive's actual structural corruption or even whether the particular source relation is legal under newer official runtime semantics. This LR2 attempt **did not reach whole outer central/inventory/authenticated EOF publication** and emitted no final source-after digest on refusal; [181](181_PREPREVIEW_AUTONOMOUS_BOUNDED_PROJECT_ACCEPTANCE.md)'s earlier narrower envelope/local inventory observation is a **separate historical run** and must not be upgraded. Tooling never authorized restore, key write or record dropping.

Source paths, IDs, archives, passwords, private memory and authentic token values remain outside the public review. Only test-source contracts and privacy-safe category transcripts are available to Project; no direct independent examination of private bytes was performed.

## 5. Acceptance boundary and next action

**ACCEPTED / NO TOOL-ONLY R1 REQUIRED:** LR2's internal opt-in ZIP32/typed/nested fail-closed validation, separate-JVM isolated SaveSlot test apparatus, 27 new tests + focused regressions, synthetic C-A evidence and explicit G1B design review. Their failure/coverage descriptions are truthful within their scope.

**NOT ACCEPTED / STILL BLOCKED:** complete code86/v2 or official145 full-app semantic or transactional restore, persisted Moments seal, G1B topology/persistence, G2C, Community protected adapter, real SecretStore/DPAPI, old-binary enforcement, actual Android continuation/round-trip, installed Windows upgrade/DPAPI, published Preview. `P11 PENDING / D-038 BLOCKED / G1B OPEN / G2C NOT AUTHORIZED / Post-P8 HOLD / Public Preview HOLD`; **0/7 A–G formally signed**, no parity or upstream baseline promotion.

The next high-impact **LR3** should begin by source-grounded **private-safe categorical classification of the real nested v8 voice/message/version reference** (missing owner vs unknown explicit version vs official legacy representation vs selected-domain omission), comparing pinned official145 and the actual code86 transport/runtime authority. Never log user IDs/text, drop voice records or invent versions. Follow with a minimum full-domain compatibility/unknown-byte retention plan and an explicit **B+fresh-root credential cutover / C-A sealed-domain design freeze** presented to the user for authorization. An isolated read-only diagnostic and synthetic regressions may proceed on previous narrow permissions; **persistent identity/namespace/schema, SecretStore writes, StartupGate cutover and G2C still require independent approval**. Android and disposable Windows platform gates remain external.

No write access to protected branches or public release is granted by this Project acceptance.
