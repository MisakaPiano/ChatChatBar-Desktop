# G1A-R2 test-fixture narrow repair

**G1A-R2 STATIC CANDIDATE / PROJECT REVIEW PENDING / BUILD BLOCKED**

Parent HEAD: `4a080b49a8c107b685f5cfb4173c3ee1972542ce` on `feature/phase8-fish-audio`.
This is an evidence-only Git handoff. All seven Kotlin drafts remain local, unstaged and uncommitted.
Project has given R1-1 Main.kt static review PASS; Main.kt is byte-identical to R1 and is not changed again.

## Narrow behavior change

Only DesktopProcessLifecycleTest.kt changes relative to the reviewed R1 draft. withRoot now fails rather than printing stderr when it cannot reacquire ownership. It also rejects an explicitly declared incomplete teardown, retaining its files/scope/ownership protection; even a caller swallowing the original teardown error cannot silently make the test green.

If the body already failed, its original exception identity remains in flight and the cleanup diagnostic is appended after existing suppressed exceptions. If that Throwable disables suppression, a test-only SyntheticRootCleanupUnconfirmed carrier keeps the exact original in cause and primaryFailure and the cleanup exception in cleanupFailure. This exceptional carrier changes the top-level thrown object explicitly rather than losing either failure. It is confined to this test file and changes no production exception contract.

A blocked ownership probe does not release the existing owner or delete the root. A declared-incomplete root is not probed or deleted at all, preserving R1's protection even for a lifecycle queued before acquire. No joins, waits or process termination are added. The no-writer synthetic regression tests dispose only locks they explicitly acquired themselves after asserting rejection.

## Identity evidence

- Reviewed R1 complete patch: `d5061fb3f2001310370b9aee71c340418ec276305757d71fa199a5d2e8fbeb37`.
- R1 seven-source snapshots reconstructed from that patch and checked against all seven R1 source-manifest hashes.
- R2 changes exactly one source file; the other six, including Main.kt, are byte-identical to R1.
- Original G1A fourteen files and R1 eight files: all 22 byte-identical to their committed versions, not regenerated or overwritten.
- Full R2 patch SHA-256: `e18b10b53d577e21ca97b656147df668849f9ae29f5b514d5d42178e19cef4d8`.
- `complete-working-tree-g1a-r2.patch` contains ALL seven final source changes relative to parent HEAD.
- `r2-vs-reviewed-r1.patch` is the normalized-LF delta against the reviewed R1 SOURCE DRAFT, not against clean HEAD.

## Static handoff limits

`git diff --check`: PASS. File-scope and SHA-256 checks: PASS. Sensitive content review: source-only synthetic fixtures and metadata; no real user data, API credentials or new raw logs included. New evidence manifest excludes itself from its entry list.

COMPILE: NOT RUN. JUNIT / AUTOMATED TESTS: NOT RUN. GUI / PACKAGING: NOT RUN.
BUILD remains BLOCKED; no new runtime pass or acceptance is asserted. G1B NOT STARTED.
No Gradle, JVM validation, data recovery, index writer, source commit, cleanup/reset/stash/rebase/merge or upstream sync.
Skill relevance reviewed: test helper only; no project skill ownership or facts made stale, so no skill edit needed.
