# G1A-R1 static narrow-repair handoff

**G1A-R1 LOCAL SOURCE DRAFT / PROJECT STATIC RE-REVIEW PENDING / BUILD BLOCKED**

Parent Git HEAD: `a85fdf83aa76f2afd9d3fd019626de54f210b087` on `feature/phase8-fish-audio`.
This evidence-only checkpoint contains source patches, NOT compiled or committed Kotlin files.
Original seven-file source draft baseline: `a03fbb997de75084cda1a836663042ee183156b5`. Prior evidence commit: `a85fdf83aa76f2afd9d3fd019626de54f210b087`.

## R1-1 ownership retention

Main.kt now verifies that an incomplete close outcome can actually reach the outer ownership boundary. If a close Throwable rejects suppression but the primary accepts it, retain the same primary object and append a diagnostic after the existing ordered secondary failures. For a suppression-disabled primary that cannot carry that outcome, throw the explicit `DesktopLifecycleCloseUnconfirmed` carrier. The exact original Throwable is preserved in `cause` AND `primaryFailure`; the exact close failure is preserved in `closeFailure`, including a shared instance. The outer boundary recognizes the carrier by TYPE, without depending on suppressed-list insertion succeeding or on thread-local state.

The extreme suppression-disabled case intentionally changes the thrown top-level object to this carrier. It does not replace, stringify or discard the original exception. Ordinary exceptions and ordinary CancellationException keep their original primary identity and existing suppression order. No timeout is treated as drain completion. A successful close continues through the original ownership-release path.

## R1-2 fixture teardown

The two container/task fixtures now acquire ownership before container construction. A separately owned synthetic scope and bounded join avoid both the former unbounded explicit join and an implicit structured-parent wait in the outer runBlocking. The timeout is inside NonCancellable; the default fixture budget is 15 seconds. A timeout means drain NOT CONFIRMED, never success. It does not kill a process, close a live container a second time, or release a held root lock. Incomplete fixture scopes and roots remain retained for diagnosis; root deletion is also suppressed if the lifecycle may still be queued before ownership acquisition. Real blocking filesystem I/O is not claimed to have an absolute timeout.

## Identity and file boundaries

- Original published directory `p9-s2b-g1a-startup-foundation-20261016/`: all 14 files byte-identical to `a85fdf83aa76f2afd9d3fd019626de54f210b087`; no original evidence overwritten.
- Original complete patch: `22ea98b74c82043a52c0cfd9be590bde78b37ce82a1117bae441068b22874f68` (unchanged).
- Original seven source snapshots reconstructed from the original patch and HEAD and matched 7/7 original SHA-256 values.
- R1 changes ONLY Main.kt, DesktopApplicationLifecycleTest.kt and DesktopProcessLifecycleTest.kt relative to that reviewed draft. The other four drafts remain byte-identical.
- All seven Kotlin source files stay LOCAL, UNSTAGED and UNCOMMITTED. No source acceptance implied.
- Full R1 patch SHA-256: `d5061fb3f2001310370b9aee71c340418ec276305757d71fa199a5d2e8fbeb37`.
- `r1-vs-reviewed-g1a.patch` is a normalized-LF delta against the previously reviewed SOURCE DRAFT, not a patch against clean HEAD. The complete patch contains all seven final source changes relative to the parent HEAD.
- Both patches are text-only synthetic/source evidence. No real user data, API secrets, certificates or raw new diagnostic logs are included.

## Static checks and limitations

`git diff --check`: PASS. Three-file R1 scope and four unchanged file hashes: PASS. No source staging or source commit. Project skill relevance reviewed: no feature ownership or existing skill facts changed; no skill edits needed.

COMPILE: NOT RUN / BUILD BLOCKED (historical resource block retained).
AUTOMATED TESTS: NOT RUN. New test methods are uncompiled source only.
GUI / EXE / PACKAGING: NOT RUN. G1B: NOT STARTED.
No Gradle, test, application, recovery, index writer, clean/reset/stash/rebase/merge or paid API operation executed.
This static handoff is not evidence of runtime correctness, drain completion, Windows behavior or Project acceptance.
