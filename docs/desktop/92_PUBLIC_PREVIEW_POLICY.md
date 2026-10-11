# CCB Desktop Public Preview Policy

更新时间：2026-10-12

状态：**APPROVED PROGRAM POLICY / NOT YET REACHED**

Authority: D-037. This document defines the first public Preview release boundary; it does not change upstream schemas, Prompt semantics, parity targets, or Phase numbering.

## 1. Why this milestone exists

CCB Desktop should begin collecting real-player feedback **before** P17/P18, but not by treating tester data as disposable. The first public Preview therefore occurs only after the core long-lived user-data path is mature through P11 and a dedicated data-safety gate passes.

The intended sequence is:

```text
P7 Image / NovelAI
→ P8 Fish / Audio
→ P9 RAG
→ P10 Long-Term Memory
→ P11 SaveSlot / Session Lifecycle
→ Public Preview Data-Safety Gate
→ Public Preview
→ P12–P16 continue while collecting real-player feedback
→ P17 Public Beta hardening driven by Preview evidence
→ P18 1.0 Gate
```

This is a milestone, **not a new Phase number**.

## 2. Entry condition

The Preview gate may not start until P11 is CLOSED / ACCEPTED. In particular, the single-conversation portability contract must already exist rather than being invented by the release gate:

- SaveSlot legacy compatibility required by the selected upstream baseline;
- current `.cbsave` package contract;
- message/session state;
- image/audio media owned by the SaveSlot contract;
- transactional restore;
- session lifecycle / duplicate behavior owned by P11;
- Android ↔ Desktop cross-platform archive evidence.

Full application-directory synchronization and automatic multi-master merge are **not** Preview prerequisites; D-035 remains separate.

## 3. Public Preview data promise

From the first public Preview onward, persisted user data is **production-valued data**.

Therefore:

- ordinary Preview/Beta upgrades must not require routine data-directory clearing;
- a new version may not silently discard unknown/corrupt user bytes merely to recover startup;
- persisted schema/resource changes must preserve compatibility, migrate safely, or provide an explicit user-controlled preservation/recovery path;
- migration must use the existing snapshot/source-retention/restart-safe principles where applicable;
- if a proposed build cannot safely preserve supported Preview data, that build is **not releasable** until the issue is resolved or a Project-approved recovery path exists;
- Preview status is never sufficient justification for destructive reset behavior.

This promise does not mean every Preview bug is impossible; it means data loss is treated as a release blocker, not normal tester cost.

## 4. Blocking Public Preview Data-Safety Gate

### A. Persistence / corruption / recovery

- atomic persisted writes remain intact;
- Missing is distinguishable from Corrupt / ReadError;
- corrupt/read-error/partial-write evidence is retained rather than default-overwritten;
- automatic backup and manual safety snapshot are usable on realistic persisted data;
- restore success returns the expected data and restore failure does not silently destroy the currently valid state;
- migration/root switch preserves source and follows mandatory snapshot / ownership / restart boundaries;
- crash/process interruption/restart does not misrepresent incomplete work as committed success.

A full P17 Backup-Recovery Center UI is **not** required yet. A clear recovery procedure is required.

### B. Upgrade / installation safety

- a normal user can install the application without Gradle/developer tooling;
- clean install is verified;
- Preview N → Preview N+1 installation/upgrade preserves the selected data root and supported persisted data;
- uninstall behavior and documentation must not imply that user data is disposable;
- if no automatic updater exists yet, manual installer upgrade is sufficient for this gate.

Full automatic updater, file associations, Open With, tray/task center and notification completion remain P15.

### C. Single-conversation Android ↔ Desktop portability

Both automated contract evidence and real-device evidence are required and must be labelled separately.

```text
Desktop session
→ export .cbsave
→ Android import
→ open and continue conversation
→ Android export
→ Desktop import
→ open and continue conversation
```

Where the selected SaveSlot contract owns media/RAG/Memory-related payload, the gate must verify those supported fields/resources rather than only plain text messages.

This proves **single-conversation portability**. It does not claim whole-app live sync or multi-master merge.

### D. Secrets / privacy boundary

- API keys, NovelAI tokens, Fish credentials, OAuth secrets and other SecretStore values are excluded from ordinary SaveSlot/full-data export, backup-sharing artifacts, logs and support bundles unless an explicitly designed encrypted credential-transfer feature is separately approved.
- support diagnostics default to version/commit/environment/error metadata, not Prompt/chat body.
- any opt-in diagnostic inclusion of conversation/Prompt content must be explicit and separately disclosed.

### E. Minimum supportability

Public testers must have a usable way to report failures with at least Desktop version/commit, validated upstream baseline, relevant OS/runtime identity, redacted error/crash evidence, and clear backup/recovery steps.

A complete P15 request-log/diagnostics surface is not required to open Preview, but a failure must produce more actionable evidence than a bare user description.

### F. Release truth / compatibility

- re-check latest upstream drift and classify release impact;
- do not automatically sync or promote the baseline;
- any security/data-loss/external-API break relevant to the Preview must be resolved or explicitly block release;
- release notes state the exact Desktop commit and validated upstream baseline;
- missing P12–P17 capabilities and material known limitations are disclosed;
- D-016 direct upstream-author authorization evidence is retained/confirmed before public packaging.

## 5. What may remain PENDING at first Public Preview

- P12 — AI Authoring / Repair;
- P13 — Moments;
- P14 — Community / OAuth;
- P15 — full OS integration, automatic updater, global ingress, full diagnostics/request-log surfaces, tray/notifications;
- P16 — upstream automation;
- P17 — full onboarding, Backup-Recovery Center and broad beta hardening.

These omissions must be visible in release notes and must not be disguised as completed parity.

## 6. Feedback stage ownership

- Public Preview becomes the main channel for discovering real UX/performance/environment edge cases;
- Program Control classifies feedback into blocker, parity defect, data-safety defect, performance/environment issue, or later-phase enhancement;
- P12–P16 development continues without waiting for every Preview UX request;
- data-loss, security, destructive migration and broken supported SaveSlot portability outrank feature schedule;
- P17 consumes accumulated Preview evidence as the basis for Public Beta hardening.

## 7. Relationship to P17 and P18

**P17 Public Beta hardening** remains broader than this Preview gate. **P18 1.0 Gate** remains the final full parity/release gate. Public Preview approval never converts PENDING rows to EXACT/EQUIVALENT by itself.

## 8. Evidence discipline

Distinguish Project GitHub/source review, Codex automated test/build/package evidence, user real-Windows evidence, Android real-device evidence, and external tester reports. Do not relabel one evidence class as another.

## 9. Release naming

This policy does not freeze a specific version number. A future Preview may be named `0.x Public Preview`, `Preview 1`, or another Project-approved scheme. The release name must not imply 1.0 parity.

## 10. Exit condition

The Public Preview Gate is PASS only when blocking sections A–F have evidence adequate to their claim and Program Control records an explicit release decision. Until then the milestone is **PENDING**, even if P11 implementation itself is complete.

