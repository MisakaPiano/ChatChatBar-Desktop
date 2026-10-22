# P11-S0 R1 — Project Review handoff

Status: **R1 TEST ORACLE FIX COMPLETE / PROJECT REVIEW REQUIRED**. This is not P11 acceptance, S1–S6 authorization, or a Preview/HOLD change.

## Scope and identity

- Branch: `feature/phase8-fish-audio`.
- Review input / unchanged production identity: `b531ff0263ab70857c716730049771e8a103fd51`.
- Changes: two test source files and this new evidence directory only. No production, build, Entity, Prompt, SecretStore, archive/restore transaction, or historical evidence changes.
- The exact R1 commit and remote verification are supplied by the follow-up delivery receipt and final handoff. Test source byte hashes are in `raw/test-source-sha256.json`; production tree identities and empty working diffs are in `raw/production-identity.json`.

## Review issue and correction

The old fixture serialized production Inspector DTOs with defaults enabled. Consequently its claimed official 1.4.5 v1 manifest contained `scope:null`, and preferences contained `retainedPreferences:[]`. Those fields do not belong to official 1.4.5's strict wire DTOs. The old positive input identity claim is superseded by this R1; old RED, XML, and reports remain unchanged.

`OfficialBackupWireFixtures.kt` now defines independent test-only wire DTOs reviewed against pinned official source. It uses its own strict `Json` configuration. The fixture generator no longer uses Inspector transport DTOs, production transport JSON, production inventory models, or production hash/count helpers to build those records. Existing entity-specific synthetic fixtures still use shared entity serialization; this R1 does not claim an independently generated complete official application backup or Android interoperability.

The v1/code84 branch uses the nine-field 1.4.5 Manifest and five-field Preferences DTOs. Tests decrypt the actual plain/encrypted archives, assert exact field sets, and decode using those independent strict DTOs. Injecting either offending later-version field is required to fail strict decoding.

For code85/86, the modern DTOs preserve official `@EncodeDefault(NEVER)` for scope, sessionChoices, sessionDefaultChoice, and retainedPreferences. Tests inspect actual serialized archive JSON, check omission/defaults, and directly check explicit nondefault serialization. `momentsImages` is emitted as true with encodeDefaults=true and decodes as true when absent; it is not incorrectly marked NEVER. A nonempty retainedPreferences record remains a negative Inspector input, not a new accepted migration capability.

## Verification

- Real R1 RED: 1 test, 1 assertion failure, process exit 1. Expected legacy field set versus actual set containing `scope`. The pre-fix source and original XML/log are preserved.
- Final affected headless JVM run: **19 tests, 0 failures, 0 errors, 0 skipped; exit 0** (18 Inspector + 1 restore-rejection suite).
- All original 16 test methods remain, with their negative cases and v2 restore denial. Three R1 methods were added. See `raw/test-preservation.json` and final XML.
- Existing 81 regression results are reused by exact unchanged production source identity, **not rerun**. The total applicable set is 81 historical + 19 current, not 100 newly executed tests.
- Six no-secret synthetic archives (v1/code84 and v2/code85/86, each plaintext/encrypted), extracted original JSON, field lists, explicit nondefault JSON, lengths and SHA-256 are retained.
- No GUI, emulator, ADB, real user root/archive/credential, provider, upstream synchronization, or installer was used.
- Feature skill reviewed before staging: existing backup skill statements remain accurate; no skill edit required for this test-only correction.

## Gate and limitations

**FORMAT_READABLE ≠ RESTORE_SUPPORTED ≠ FULL_PORTABILITY_ACCEPTED.** Existing v1 recovery admission is unchanged; v2 cannot become Prepared or reach scheduleRestore. Unsupported Moments, foreign credentials and unsupported entity/schema cases retain their existing restrictions. No new restore capability or full official round trip is claimed.

Project should review the independent wire DTO field/default contracts, exact serialized output, strict legacy negative controls, preservation of historical tests, and production identity. Stop at **P11-S0 R1 Project Review Gate**; S1–S6, P11, Post-P8 HOLD, baseline and Public Preview status remain unchanged.
