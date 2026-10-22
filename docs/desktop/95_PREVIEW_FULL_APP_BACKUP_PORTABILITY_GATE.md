# 95 — Public Preview Full-App Backup & Migration Compatibility Gate

**CURRENT R07 USER DIRECT-IMPORT ADDENDUM / 2026-10-23:** [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) supersedes the previous *product target* of denying an entire user-selected `.cbbackup` only because it contains self-exported API keys. Normal goal is authenticated direct import of safe data and keys into Windows protected storage with correct account bindings and crash-safe coordinated transaction; full advanced C2 can come later. Existing production rejection must remain until a verified secure adapter replaces it; no license to silently drop safety checks. Default ordinary shareable Desktop exports still omit keys. D-038/Preview remains BLOCKED.

**APPROVED PROGRAM CONTROL — 2026-10-12 JST / D-038 / P11 OWNER / PUBLIC PREVIEW BLOCKER / NOT IMPLEMENTED**

## 1. Authority and rationale

The user explicitly approved strengthening Public Preview readiness to include the official **ChatBar 1.4.5 full-app migration archive** rather than requiring only Desktop-local backups and one-conversation SaveSlots. This is an additive amendment to D-037 and D-035; neither the phase numbering nor P17/P18 responsibilities change.

Official reference: `SaltyFishOTL/ChatChatBar v1.4.5 @ 1198a13cf3008de1358b8a69aa5e40b64797c8d9`, particularly `.agents/skills/chatbar-app-backup/SKILL.md`, `domain/backup/*`, `data/security/*CredentialStore.kt`, `domain/voice/FishAudioStorage.kt`, `domain/chat/SaveSlotPackageStorage.kt`, and tests. **This is a selected post-baseline migration target, not a validated upstream baseline promotion.** CCB Desktop's formal validated baseline remains **1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8** until an independently authorized/validated sync.

## 2. Distinguish four products

| Product | Responsibility and authority |
| --- | --- |
| Character/FormatCard/WorldBook **transfer Package** | Existing domain-specific Package, unrelated to full-app backup |
| Single-conversation **.cbsave SaveSlot** | P11 native v8 + legacy 1–7, media/RAG/memory/session transfer; D-037's original separate gate remains mandatory |
| Whole-app **.cbbackup** | **NEW P11 REQUIRED**: official 1.4.5-compatible export, offline validate, safe import/restore and data-root-independent cross-device portability of applicable committed application state |
| Live BYOC/cloud/data-root synchronization | D-035 future owner, **not** a Preview gate; no multi-master merge, remote sync service or automatic watcher is required |

The archive is **not** a raw Android root tree copied onto Windows. Shared logical schema, typed resource mapping, portable ownership and transactional publication are required. No browser/WebView runtime.

## 3. Required P11 implementation contract

Before P11 is CLOSED / ACCEPTED:

1. Audit **exact official 1.4.5** envelope, bounded v1 format, encryption/authentication, ZIP metadata/inventory, length/hash/CRC, duplicate/path traversal/symlink controls, resource token mapping, destination-space checks, preference/setting and entity registries, prepare/commit/restart/rollback states. Do not invent a Desktop-private format when the official protocol is reusable.
2. Provide user-driven **full-app archive export**, **offline archive inspection**, explicit confirmation and **transactional import/restore**, including crash/restart/cancel/failure rollback. No silent destructive overwrite or default reset of existing data, corrupt bytes or unknown stores. Preserve source archive/root and make recovery steps available.
3. Support **official Android v1.4.5 .cbbackup → Desktop** on genuine files and **Desktop → Desktop** full restore at the Preview gate. Validate **Desktop-exported archive → official Android v1.4.5 import** for supported portable data; if some state is platform-only or not yet implemented in P12–P17, disclose its exact handling, preserve unsupported bytes losslessly or reject transaction before commit; **never claim a complete round-trip after discarding data**.
4. Minimum portable inventory includes supported committed settings, models, characters/cards, chats and versions, worldbooks, memories, RAG documents/index-owned resources, images, voices and media/anchors, drafts, SaveSlot packages and current supported references. Newly implemented P8–P10 owners must hand off known persisted resource paths/serializers/references to P11. Platform-specific session/permission state must be explicitly classified; no fabricated desktop equivalents or silent loss.
5. Full backup must cooperate with `DesktopDataOperationCoordinator`, atomic JsonFileStorage persistence, selected root, portable mode, snapshot/backup, isolated SecretStore, foreground task drain and no-write-during-restore boundary. No raw Android absolute paths left as broken Windows references.
6. P11 owns initial usable UI for export/inspect/import/recovery. Full P17 Backup-Recovery Center and broad OS hardening remain P17; full installer/updater and OS shell integration remain their previous owners except the narrow Public Preview installer/manual-upgrade data-retention gate.
7. During P11, assert official format version/field authority from selected source and tests; do not treat this document as a license to hardcode stale versions indefinitely.

## 4. Strict credential-transfer policy

Official 1.4.5 .cbbackup can include encrypted or non-encrypted credential payloads. That is **not** blanket permission for Desktop to expose secrets:

- **Ordinary shareable Desktop backup/export excludes API keys, Fish/NovelAI tokens, OAuth secrets, service-role keys and protected SecretStore material by default**, as D-037 already requires.
- Recognize and validate incoming archives containing credential fields; **no silent credential installation**. A separate, explicitly approved encrypted credential-transfer UX/security design is required before copying credentials into DPAPI. Until approved, report exclusion/preservation accurately and keep the original archive for the user; never log extracted credentials.
- Password-based encrypted container support may be necessary to read official .cbbackup while *not* authorizing credential import/export. Decryption/authentication, secure temporary handling, no plaintext backups/logs and memory lifetime must be tested independently.
- Treat export/import failure, an unavailable decryption key, invalid credential envelope or unrecognized store as **failure, not success with missing data**. If secure full fidelity cannot be offered, Preview must not claim full portability.

This decision strengthens D-035's ordinary-secret-exclusion rule and does not silently modify the official upstream implementation.

## 5. Blocking Public Preview Data-Safety Gate evidence

P11 acceptance is necessary but not sufficient. After P11 CLOSED / ACCEPTED, collect **separate** evidence:

- **Format/golden tests:** Android 1.4.5 exact-source archive compatibility, encrypted/plain envelopes with no secret leakage, selective resource mapping and no excessive-memory copying; malformed/unsupported/truncated/duplicate/path-escape/corrupt/auth-failure cases.
- **Real cross-device migration:** Android v1.4.5 whole-app .cbbackup → clean/snapshotted Desktop → reopen/continue real chat, inspect settings/card/chat/version/memory/image/voice/draft and owned media; record unsupported states precisely. Test Desktop-generated official-compatible .cbbackup in Android for supported portable data, or mark explicit blocker if that claim cannot be made.
- **Desktop round-trip:** old Desktop persisted root → whole-app export → fresh isolated Desktop root → restore → restart/use, including data root/portable mode where supported.
- **Fail-safe tests:** existing Desktop data left valid and recoverable on wrong password, out-of-space, canceled copy, crash/restart midway, invalid media refs, unauthorized credential handling and incomplete restore; snapshot/source retention and rollback evidence.
- **Release honesty:** exact upstream/desktop commit and tested format versions, migration instructions and confidentiality warning, unsupported domains and known limits, tester support/recovery steps and D-016 direct upstream-author authorization.

**Any material data-loss, silent partial restore, unhandled official 1.4.5 archive, insecure ordinary backup, or inability to safely handle imported unsupported bytes = Public Preview RELEASE BLOCKER.** No bypass by saying “only Preview”.

## 6. Boundaries and exit

- This is **P11 + Public Preview Gate**, not P8 implementation. P8 only preserves Fish voice entities/files/owned references and defines P11 contract handoff; P9/P10 do the same for their persisted domains.
- **No upstream sync, schema bump, Prompt edit, migration implementation or public release is authorized by adopting this policy.**
- P12–P17 parity PENDING rows remain PENDING; P17 user-experience and feedback hardening remain distinct; P18 is still the full 1.0 gate.
- P11 cannot close until its `.cbsave` and `.cbbackup` planned owners meet the scoped implementation/test conditions. The independent Public Preview Gate cannot PASS until actual Windows and Android transfer/restore evidence is accepted by Project and user explicitly approves public release.

**CURRENT: D-038 APPROVED / FULL-APP MIGRATION BLOCKER ADDED / IMPLEMENTATION PENDING.**
