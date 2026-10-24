# Test/source/log ledger — Codex attribution

Storage/source checkpoint: **`3c789ee5ab6b065b7303fb410efda3e46e043411`**, pushed
to original feature from `ceeeb09e3327aa70a4858548b94520d87f4c2690`.
All 90 raw files (691,587 bytes) are indexed by raw SHA-256, byte length and Git
blob in [STORAGE_RAW_INDEX.json](STORAGE_RAW_INDEX.json). This is **Codex self-check**,
not Project independent review. All original `.log` files were explicitly staged
despite repository ignore rules; raw `-text` attributes preserve their bytes.

## Actual runs

All commands below ran from repository root. No Gradle/JVM/JUnit/native-VM suite
ran in LR6; therefore **new JUnit XML: N/A**, not “zero-failure native PASS”. Old
LR5/earlier XML and raw evidence are untouched, independently indexed historically.

| Actual command | Result / retained raw receipt |
|---|---|
| `python tools/desktop/interop/lr6/StorageAudit.py before` | exit0, stdout observed in tool session; original JSON `LOCAL_DISK_AUDIT_BEFORE.json`; no separate redirected stdout log was created |
| `python -B -m unittest discover -s tools/desktop/interop/lr6 -p test_storage_audit.py -v` | first12: eight PASS, three FAIL, one ERROR, exit1; `policy-tests-initial.log/.exit` |
| same focused command after direct candidate lstat fix | 12 PASS, exit0; `policy-tests-final.log/.exit` |
| `python -B tools/desktop/interop/lr6/StorageAudit.py candidates` | exit0, stdout observed; original `CLEANUP_DRY_RUN.json`, 1,332 prior raw/tool identities checked; no separate redirected stdout log |
| `python -B -m unittest discover -s tools/desktop/interop/lr6 -p 'test_*.py' -v` | 17 PASS, exit0; `cleanup-policy-tests.log/.exit` |
| `python -B tools/desktop/interop/lr6/RetireAuthorizedAvds.py plan` | exit0; `avd-plan.log/.exit` and original dry run |
| same retirement tool `apply` | **exit2, partial**, five files absent on immediate original-manifest reconciliation; `avd-apply.log/.exit`; read-only failure retained |
| full unittest command after readonly-skip handling | 17 PASS, exit0; `readonly-skip-policy-tests.log/.exit` |
| retirement tool `plan-readonly-skip` | exit0; log/exit + revised dry run; retained read-only counts explicit |
| retirement tool `apply-readonly-skip` | exit0; log/exit + second-interval ledger; 45 files deleted, two readonly files retained |
| `python -B tools/desktop/interop/lr6/StorageAudit.py after` | exit0 command completion, **coverage incomplete** on direct stat; original JSON + `audit-after.log/.exit`; not usable for full savings |
| `python -B tools/desktop/interop/lr6/AuditSources.py` | exit0; `source-audit.log/.exit`; 45 current files + seven fixed official identities, exact app tree unchanged |
| full unittest command after census/identity separation | final17 PASS, exit0; `enumeration-policy-tests.log/.exit` |
| `python -B tools/desktop/interop/lr6/StorageAudit.py after-enumeration` | exit0; `audit-after-enumeration.log/.exit`, comparable metadata census |

Do not sum repeated rounds as unique new tests: final suite is **two Python
unittest classes /17 methods, zero failures/errors/skips**. Five methods concern
ordinary Windows cleanup handles on newly created fake files; twelve concern
metadata/retention policy. None establishes native credential, power-loss,
production recovery, old-writer fence, VM or Android behavior.

## Pre-run exact snapshots and fixes

- `source-before-policy-tests`: first audit policy code and tests; actual Windows
  DirEntry identity shortage generated the initial RED. Direct `lstat()` remains
  required for deletion candidates; no safety threshold was lowered.
- `source-before-policy-final`: corrected candidate identity, 12-test GREEN.
- `source-before-cleanup-tests`: cleanup tool/guard tests and audit tests before
  17-test GREEN and first actual plan/apply.
- `source-before-readonly-skip-tests`: exact revised retirement script; other
  files unchanged from previous snapshot. It retains readonly files without
  attribute edits, rather than elevating/retrying blocked deletion.
- `source-before-enumeration-tests`: final audit code; other files unchanged.
  Census uses enumerated sizes; the intermediate direct-stat subtotal is retained
  as incomplete. This does not weaken pinned cleanup identity/stream guards.
- `raw/audited-source`: 45 current production source snapshots for static review
  only. Snapshot files under docs are not deployed Kotlin or registered source sets.

`SOURCE_AND_BOUNDARY_CHECK.json` / final evidence index record current tool/source
Git identities and exact app/baseline equality. Logs/commands do not contain real
tokens, archive passwords, private archive digests or user content. Local full-path
CSV/identity manifests never enter Git. Private export directories and real
SecretStore were not read. Process checks recorded names/counts only.

An ad-hoc final Markdown-link audit first returned exit1 because Python used the
host default GBK decoder on UTF-8 documentation. This was not a unit/JVM result;
the original tool output remains in the task history (its interpreter profile
path is not republished). The versioned `CheckEvidence.py` explicitly uses UTF-8
and then actually returned exit0: all90 original raw items matched SHA/length/Git
blob,45 snapshots matched current source, and17 local links resolved. The check
does not establish independent Project acceptance or native platform capability.
Three supplemental pinned145 source identities are recorded separately in
`SELECTED_OFFICIAL_SUPPLEMENT.json`; the earlier seven remain unchanged.

Skill review before commits: existing full-backup and SecretStore behavior unchanged;
no Skill update was necessary. New one-time tooling is described in its own README.
No prior raw log/XML was rewritten. CLR/DPAPI/native filesystem durability and
JDK17 guest capability remain NOT TESTED; Gradle reruns would not remove the
independent VM precondition and were neither needed nor performed.
