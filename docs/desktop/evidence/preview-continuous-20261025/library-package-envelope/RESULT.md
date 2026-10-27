# Imported SaveSlot library envelope in whole-backup merge

An authorized local modern whole-backup re-publication passed authentication of 10,185 entries but refused during catalog construction at `MERGE_PACKAGE_OWNER_IDENTITY`. It did not activate the new root. Original archive, prior root/keys and inert failure evidence remain local and unchanged. No real data, archive/hash, witness source, native protected blob or real-run log/XML is included here.

## Source contract and correction

Pinned official147 `b4fa75d296680f777be095f3121ad0c4db979f17`, `app/app/src/main/java/com/example/chatbar/domain/chat/SaveSlotPackageStorage.kt`, Git blob `fba12262bf5567322a4c95aa9fe03392dce6d3bc`, establishes `importPackage`: the library receives a new id, selected session, name, creation time, physical package filename and actual length, while the source package bytes remain unchanged. The shared Desktop codec retains that behavior. Formal validated baseline remains 1.4.1.

The old merge/custody checks incorrectly required library id/session to equal the immutable manifest. `DesktopSaveSlotLibraryEnvelope` now validates the exact permitted envelope differences and requires every other decoded field, snapshot, count and media policy to match. A changed session with the same id is still refused; a distinct imported id must match its library package filename; positive outer length must match the actual file. This recognizes a format relationship, not cryptographic proof that a particular importer created it. Authentication, inventory and package validation remain mandatory.

Package-local owner aliases apply before global merge/fork identity mappings. They do not mutate global bindings for the original live conversation. Rewritten inner manifest and outer library record use the same verified metadata; original package and original raw outer record are separately preserved with exact bytes. Message versions, source-turns and historical recording identity are not rebound. Media bytes are copied unchanged. No official Entity/schema/Prompt or S0/v1 restore admission was changed.

## Actual execution

| Round | Suites/tests | Result | Exit |
|---|---:|---|---:|
| Original shared-import reproducer | 1 / 1 | 1 FAIL at the same owner check | 1 |
| First bounded run | 5 / 34 | 33 PASS, 1 fixture failure (missing custody root ownership) | 1 |
| Expanded run | 9 / 72 | 71 PASS, 1 incorrect expected negative-stage assertion | 1 |
| Verified run | 9 / 72 | 72 PASS, no failures/errors/skips | 0 |

The negative-stage correction retained the existing `SLOT_CUSTODY_PACKAGE_LENGTH` guard; it did not change production admission. Negative cases now assert the exact refusal stage for catalog/preparation/custody. Older package fixtures were corrected to declare their actual message/audio counts, rather than relaxing the new equality check.

Coverage includes actual shared-codec import, renamed foreign library owner, a conflicting global source-session fork, unchanged global mappings, preserved original package/record/audio, unchanged unknown historical version, independently published prepared-root files, modern export/authentication/re-catalog, forged content/count/length/filename/owner, cancellation and existing SaveSlot/merge-service/source-custody/remapping/writer/independent-process regressions.

A separate local structural observation found ten current isolated-library packages, five with a different library owner and no other field differences after excluding the six defined import fields. This is an aggregate diagnosis, not substituted for authenticated validation, nor a claim that the earlier frozen exported archive contains all ten later library entries. Real re-publication with the corrected runtime and independent cold reopen remain the next checks.

The broad 2,150-test headless RED and unrelated remaining cases stay recorded separately under `integrated-oracle-correction`. Windows Installer rollback, native visual/manual checks, physical Android and power-loss/cross-account behavior are not closed by these 72 tests. Studio's results are not counted.
