# 202 — LR7 User Authorization: Work Cloud + Conditional Ephemeral Windows Runner

**USER AUTHORIZATION — 2026-10-24 JST — BOUNDED LR7-A AUDIT AND CLOUD-FIRST LR7-B FEASIBILITY PREPARATION. NO ACTUAL PRODUCTION G2C/PERSISTED SCHEMA AUTHORIZATION; CLOUD RUN/RESULT NOT YET OBSERVED.**

## User consent and interpretation

After [200 LR6 Project acceptance](200_P11_LR6_BOUNDED_PROJECT_ACCEPTANCE.md) the user wrote **「好，继续」** to advance the [201 LR7 native lab/authority candidate](201_P11_LR7_NATIVE_LAB_AND_AUTHORITY_ADAPTER_DRAFT_NOT_AUTHORIZED.md), followed by the explicit execution preference **「实在不行就用cloud环境，给work做」**. Taken together, these messages authorize performing the bounded LR7 source/architecture review **using ChatGPT Work's cloud computer if available**, and preparing/testing only **fake-data, independently isolated cloud Windows-native feasibility** when a platform receipt establishes an actual disposable Windows environment. No assumption is made that Work's generic cloud computer itself has Windows, DPAPI, NTFS, OS reboot, poweroff control or an isolated interactive user.

**No Work cloud job, Actions workflow or native test is launched by this GitHub document.** A user must open Work mode and submit [203](203_P11_LR7_CLOUD_WORK_EXECUTION_CONTRACT.md), or a connected executor must actually demonstrate tool/job start. Do not report `RUNNING`, `PASS` or a new native artifact without source-backed execution receipts. D-045's B+inactive fresh root, retained OLD Keys, inert historic voice and C-A sealed raw/media are already user-approved targets, and must not be reasked.

## Git and source scope

- Fork `MisakaPiano/ChatChatBar-Desktop`, original feature `feature/phase8-fish-audio`. Exact reviewed LR6 HEAD before this registration: `601e61e30f161fa3f8dfed418b0c4b4fb0eaf8d2`. Executor must verify LIVE feature HEAD and clean FF ancestry before source edits. Never force/reset unknown work. Do not modify protected `desktop`, mirror `master`, official upstream, or `sync/*`. Do not merge or release.
- Formal validated upstream ChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`, with **selected** D-038 official1.4.5 backup `1198a13cf3008de1358b8a69aa5e40b64797c8d9` and separately observed private code86/v2. No baseline promotion or Prompt/API request/Package/Entity semantic edits.
- Source audit, proposed architecture/deployment alternatives, strictly opt-in **test-only** code and redacted evidence may be committed/pushed only on original feature. Work's cloud build or test workspace cannot change production data roots or introduce a deployed broker/service/schema.

## Approved cloud fallback

**Preferred order:**
1. Work reads Git CURRENT, existing official Skills, [200](200_P11_LR6_BOUNDED_PROJECT_ACCEPTANCE.md), [201](201_P11_LR7_NATIVE_LAB_AND_AUTHORITY_ADAPTER_DRAFT_NOT_AUTHORIZED.md), plus exact source, to compare broker/principal/old-writer/same-user alternatives with operational and cost burden.
2. Work verifies its own cloud OS/capabilities. Non-Windows Work cloud may run static analysis and safe portable synthetic tests **only**; its output cannot count as Windows native evidence.
3. Work may prepare a **separate**, feature-only, least-privilege GitHub Actions `windows-2022` (optional `windows-2025`) *ephemeral GitHub-hosted runner* test plan/workflow that needs **no repository or organization secrets**. GitHub Actions currently has only `android-ci.yml` and `release.yml`, both `ubuntu-latest`; do not repurpose the signed Android release pipeline.
4. Work may trigger a limited, source-hash-pinned Windows CI run **only after** confirming repository Actions policy, branch isolation, permissions, hosted Windows VM identity/OS/version, expected billing and non-destructive test scope. If triggering would incur unapproved charges, require admin changes or access private secrets, mark `WINDOWS_CI_BLOCKED`; continue source/contract preparation.
5. If Windows CI job genuinely starts, use **fake tokens and fresh runner temp directories ONLY**, no personal files. Test native directory/file identity, per-process Windows user-protection fake DPAPI, safe file flush/readback and cooperative/uncooperative write limits; record actual API error categories. **No VM power-off or native OS reboot** on GitHub-hosted CI; CI native success remains **`PRELIMINARY_CLOUD_WINDOWS_NATIVE`**, not production power-loss or release-gate evidence. An isolated dedicated VM with a controllable snapshot remains required for reboot/power-cut experiments.

**Trigger discipline:** GitHub `workflow_dispatch` typically requires the workflow to exist on the default branch. The project policy prohibits Desktop-specific changes to mirror `master`; Work must **NOT** write such a workflow into `master` merely to enable manual dispatch. For test-only `feature` workflow, a tightly branch/path-scoped `push` trigger is a candidate only if cost/permissions/safe tests are checked and one bounded run is intended. Work must avoid repeated automatic runs and avoid changing `release.yml`.

## Strict security exclusions

No real `WindowsSecretStore`, current user's DPAPI/SID, ordinary %LOCALAPPDATA%/%APPDATA%/%ProgramData% root, desktop-installed executable, actual data roots or private `H:\手机CCB导出` uploads. Never transfer real API Keys/NovelAI/Fish/Community Tokens, plaintext credentials, personal archive digests/identifiers/media/chat into cloud runner, Work browser or Git logs. Avoid using GitHub repository/Actions secrets and give `GITHUB_TOKEN` **contents: read** only for native CI. Do not create an elevated service/principal, install a broker, change host accounts/registry, reboot a user's PC or run any old executable. No actual cloud production credential transfer, G2C, Startup/GC/restore/provider wiring, installer/uninstall/release, upstream sync or Prompt edits.

The previous **two Android AVD deletion consent is CLOSED**. This LR7 authorization does NOT permit further local storage cleanup or deletion, does not touch earlier ignored private witnesses, and does not authorize managed-host/cloud VM deletion outside hosted-runner automatic teardown.

## Handoff/stop

Work should produce the bounded [203 execution packet](203_P11_LR7_CLOUD_WORK_EXECUTION_CONTRACT.md) deliverables and stop at **`P11-LR7 CLOUD WORK PROJECT CODE REVIEW CANDIDATE`** or explicit `WINDOWS_CLOUD_ENVIRONMENT_NOT_PROVEN/NOT_TESTED`. Native test observations must be tied to actual runner job ID, OS version, commit, logs, exit/testcase and GitHub-origin link; never invent Windows-native results from generic cloud tests.

Keep `G1B OPEN / G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / A–G 0/7 formally signed`. No new release or production schema decision is conferred by this permission.
