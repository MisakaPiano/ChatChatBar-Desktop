# 96 — Local Artifact Audit, Retention and Safe Cleanup Policy

**CURRENT NARROW AUTHORIZATION ADDENDUM (2026-10-12):** The user has since explicitly approved **permanent deletion of precisely the 12 P8-S0C Group A generated directories and the single Group B `H:\ChatChatBar-Desktop\app\.gradle` directory**, enumerated in `98_PHASE8_S0C_CLEANUP_PROJECT_REVIEW.md`. This is **not** approval to delete anything else. Exact-directory and descendant reparse checks, clean Git worktree, inactive build processes, no newly discovered protected files and full pre/post audit remain hard STOP conditions. **Codex execution is PENDING / actual reclaimed 0**. The earlier `NO DELETION AUTHORIZED` statement below is the historical inventory-stage policy, superseded only for the explicit 13-path allowlist in `98`; every general protection remains in force.

**CURRENT — 2026-10-12 / PROJECT OPERATIONS / INVENTORY FIRST / NO DELETION AUTHORIZED**

User requested cleanup of no-longer-needed **local project files**. ChatGPT Project cannot read `H:\ChatChatBar-Desktop` directly. Codex may inspect the local machine and provide a detailed, reviewable inventory; **no automatic deletion is approved by this policy**. Protect user data and historical gate evidence during P8 and the future production-valued Public Preview era (D-037/D-038).

## 1. Classify by authority, NOT by extension or ignore rule

| Class | Examples | Disposition |
| --- | --- | --- |
| **PROTECTED — never auto-delete** | Selected/portable Desktop user data roots; `entities/`, `images/`, `audio/`, `documents/`, `save_slot_packages/`, `backups/`, recovery/snapshots, any root migration source/destination or ownership record; SecretStore/DPAPI envelopes; real credential files; unknown corrupt/unreadable bytes | No deletion or relocation; require explicit user migration/recovery workflow and proven root identity |
| **HISTORICAL EVIDENCE — retain by default** | P7 frozen exact-source isolated distributions, 210/210 SHA-256 manifests, R1 binary/JAR hashes, acceptance/handoff screenshots/fixtures, native RED XML/logs, original Codex test evidence, local `H:\CCB-Acceptance\*`, `docs/desktop` proofs | Keep canonical copy and provenance even if under gitignored `build/`. Do not rewrite/delete merely because another test passed |
| **POSSIBLE STALE GENERATED ARTIFACT** | Older duplicate runnable distributions after retained canonical exact-SHA release; repeated Gradle compile intermediates/test HTML output or temporary scratch **only when not unique evidence**; unused archive copies | **Audit first**, map consumers and exact SHA/provenance, quantify bytes. No action until reviewed allowlist |
| **TEMPORARY / QUARANTINE CANDIDATE** | Identified disposable test scratch from a clean synthetic profile or an explicitly approved experiment, not an active root or current clipboard file | Exact path and recorded safe provenance required; prefer reversible quarantine over immediate deletion, with owner consent and expiry |
| **UNKNOWN / ACTIVE** | Untracked files, generated previews, hidden `.env`/gitignored settings, orphan-looking media, secret-like filenames, pre-release fixtures or inaccessible paths | Protect, do not disclose secret content; do not classify untracked/ignored as disposable without source ownership proof |

Do **not** use `git clean -fdx`, `git reset --hard`, recursive wildcard deletion, `gradlew clean`, global Gradle-cache cleanup, `rm -rf`, automatic branch deletion or blind prune of all `build/`. Directories can contain referenced packages and historical RED evidence. No automatic cleanup of `java.io.tmpdir` image/voice clipboard staging while the application may be running.

## 2. First Codex task: read-only local disk inventory

- Verify local repo `H:\ChatChatBar-Desktop` and `origin` URL; record exact active local/remote branch HEADs, git clean status, project root and selected data directory **only if it can be read without launching/mutating app state**. If unsure which directory is active user data, classify as protected.
- Collect git tracked/untracked/ignored path summaries (`git ls-files`, `git status --porcelain=v1`, `git ls-files --others --exclude-standard`, `git ls-files --others --ignored --exclude-standard`), plus a bounded, read-only inventory of `app/**/build`, project-local `.gradle`, specifically named old phase-gate/distribution directories, manually created fixtures and local project root scratch.
- For candidate items report **exact path**, total size, file count, last modified UTC/JST, source/provenance, git status, canonical acceptance manifest or source SHA linkage, duplicate proof if any, and whether it is needed by current tests, current build or future package/release steps. Hash only candidate binary packages whose identity is needed; avoid hashing arbitrary user data or dumping private file contents.
- Output four classes: **KEEP / REVIEW-QUARANTINE / POSSIBLY DELETE WITH USER APPROVAL / UNKNOWN-PROTECT**. Include candidate bytes reclaimable and keep at least one verified canonical copy of every frozen user-accepted package and gate evidence. `H:\CCB-Acceptance` and user-selected/portable data directories must not be scanned deeply or disclosed without specific need.
- Redact personal data, API keys, secrets and user chat contents from output. Never upload backup files, media, snapshots, tokens or diagnostic logs with Prompt bodies.
- Produce the audit in Codex's final message; optionally a **gitignored local inventory file** only if user later authorizes disk write. Initial inventory is **read-only: no file creation**.

## 3. Two-step release of cleanup

1. Project reviews exact inventory and creates a **concrete path allowlist** with provenance/size/hash and reason. Unknowns default KEEP. Reconcile against `92`, `89`, `85`, `76`/`78` historical gates and new P8/P11 data requirements.
2. **Only after explicit user approval of the exact allowlist** may Codex perform a narrow reversible quarantine or deletion, using exact canonical paths (never wildcard root). Stop if any file changed identity, is locked/in-use, points outside the proposed path, has symlink/junction/reparse risk, or has not been demonstrated as disposable. Report before/after free space, affected paths and clean Git working tree.

For reversible candidate artifacts, prefer moving to a separately approved non-user-data quarantine with a manifest; do not automatically delete quarantine on a timer. Any permanent deletion requires further user decision.

## 4. Current stage

This policy does **not** identify any local path as safely deletable. All candidate bytes saved remain hypothetical until Codex's read-only local inventory is reviewed. The **P8-S0 source audit is complete**, independent Project review accepted pure-shared-core direction, and local artifact inventory should precede heavy P8 Gradle/build/package work. No impact to frozen P7 production or public Preview gate approval.

**CURRENT: LOCAL CLEANUP INVENTORY AUTHORIZED / DELETE NOT AUTHORIZED.**
