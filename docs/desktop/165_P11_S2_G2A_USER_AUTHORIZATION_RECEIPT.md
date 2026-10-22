# 165 — P11-S2-G2A Bounded Production Source: User Authorization Receipt

**CURRENT USER AUTHORIZATION — 2026-10-23 JST — G2A LIMITED PRODUCTION SOURCE WORK APPROVED; NO LIVE IMPORT OR REAL-DATA TEST AUTHORIZED BY THIS RECEIPT.**

## 1. Explicit user approval and provenance

Following the Project review of [162 G1A](162_P11_S2_G1A_CREDENTIAL_TRANSACTION_PROJECT_ACCEPTANCE.md), the user explicitly **批准** the immediately proposed **G2A first bounded production-source slice**. In the same message the user offered to provide real data for more aggressive validation **if requested**. No concrete user archive, password, root path or dataset is supplied in this authorization message; the offer is not blanket permission to access previously attached/private files, read existing Windows SecretStore, start network calls, import real data or publish raw evidence.

The approval covers the narrow work contemplated by [164 initial draft](164_P11_S2_G2A_BOUNDED_IMPLEMENTATION_CODEX_CONTRACT_DRAFT_NOT_AUTHORIZED.md) as adapted into the actual executable [166 task](166_P11_S2_G2A_AUTHORIZED_TYPED_CREDENTIAL_PLAN_CODEX_TASK.md). The original 164 remains a historical draft; **166 takes precedence for what Codex may execute now**.

## 2. Approved scope

- Add a small number of **new internal, non-wired Kotlin/JVM `src/main` modules** in the Desktop backup area for typed, redacted credential-source/target mapping, explicit conflict categorization, and a **pure, not-yet-durable intent/phase contract validator**. Prefer reusing official 1.4.5 format DTOs and current Desktop `DesktopCredentialKey` semantics over maintaining a second production serialization/SecretStore implementation.
- Add synthetic isolated JVM tests, targeted related regressions and a new bounded evidence directory; deterministic failure cases must represent prior G1A observations (`A_NEW/B_OLD`, `DATA_NEW/KEY_OLD`, postpublication ambiguity and rollback failure) without falsely claiming cross-root atomic recovery exists.
- Strict fail-closed on unsupported or unmapped source credentials, e.g. the real `communitySession` missing a protected Desktop sink, and on Model ID/Endpoint conflict; never silently drop incoming keys or infer accounts from display names.
- Freeze newly authored source/type contracts and evidence at real Git SHA, stop at **G2A Project Code Review Gate**, and do not expand past the task.

## 3. NOT approved (hard stop)

- **No** existing production `AppBackupCodec`, `DesktopFullBackupArchive`, `DesktopBackupPolicy`, `DesktopFullBackupMapping`, `DesktopFullBackupService`, `DesktopFullBackupCommitJournal`, `DesktopStartupGate`, `WindowsSecretStore`, `DesktopSecretStore`, Repository/GUI/Task/GC/Root/Portable/Installer or upstream Android/Prompt modifications. If required, return precise blocker and proposed minimal diff for separate authorization.
- **No** live import enablement, no relaxation/removal of `CREDENTIAL_APPROVAL_REQUIRED`, no real SecretStore write/read, no full backup output containing credentials by default, no persisted seal/schema version/production root/adapters, no `restoreSupported=true` or Task activation.
- **No** actual user archive/Key testing as part of G2A. The user's offer is conditional on a future specific request and a separate bounded private **read-only** test authorization with location, password-handling and output redaction. An authenticated backup with API Key should ultimately import securely under user-approved [158 R07](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md), but this *product goal* does not override G2A runtime isolation.
- No upstream sync, baseline promotion, direct `desktop` merge, installation/release/Preview or reuse of real attached files.

## 4. Gate distinction

| Gate | State after user approval |
|---|---|
| S0/S1/G0/G0R/G1A scoped acceptances | Remain accepted only within their previous evidence limits |
| G2A first internal production-source slice | **AUTHORIZED FOR CODEX**, bounded to 166 and Project Review Gate |
| G1B multi-participant durable recovery design | Candidate in [163](163_P11_S2_G1B_UNIFIED_DATA_SECRET_TRANSACTION_DESIGN_GATE.md), not proven or implemented |
| G2B/G2C live data/secret apply, persistent C-A seal, GC/backup/root and old-version write barrier | **NOT AUTHORIZED** |
| Real user dataset, real Windows DPAPI/profile, official Android, actual GUI, real provider, old exe | **NOT AUTHORIZED BY THIS APPROVAL** |
| P11 whole / D-038 / Post-P8 / Preview A–G / full upstream baseline | Remain PENDING/BLOCKED/HOLD; baseline 1.4.1 |

## 5. Real-data acceleration path

If a real-data validation becomes useful, first draft a **named isolated read-only test** and ask the user for **one specific source archive** of a specified official version (prefer v1.4.5), at a user-controlled location, with its password entered locally without posting it or the API keys to GitHub, source code or task text. Do not auto-upload or auto-index it. For later mutation/import trials, require a separately authorized **disposable copy** of the data root, pre-test snapshot and recovery evidence; never use a primary user root. Until then G2A remains exclusively synthetic. See [167 private real-data plan](167_P11_REAL_DATA_INTEROP_READONLY_PREPARATION_NOT_AUTHORIZED.md).

**This file registers the user's real G2A authorization, not implementation completion or a signed data-safety gate.**
