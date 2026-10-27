# Native probe interpreter admission

The existing standalone probe used assertion-based identity/path/native checks without refusing an optimized interpreter. The fix is three lines at module entry, before imports: explicitly refuse when `__debug__` is false. Normal, non-optimized action implementations and their command lines are unchanged.

## Actual bounded tests

- RED: 3 unittest methods, exit1. Normal help passes; the optimized CLI method fails for both `-O` and `-OO`, and optimized import also fails: **3 failure events across 2 failed methods**, not 3 extra test methods.
- GREEN: 3 methods, 0 failures/errors/skips, exit0. Both optimized CLI variants and optimized import refuse with the fixed category; ordinary help still works.
- Every subprocess runs help or import only, in a tiny synthetic directory whose marker and file set remain unchanged. **No install, repair, registration query, GUI, DPAPI, user data or registry operation was executed by these tests.** The RED demonstrates missing interpreter admission, not an actual unsafe installation.

Raw arguments, exits, logs and source snapshots are indexed in `RAW_INDEX.json` (12 byte-exact files). Final tested source blobs are in `SOURCE_IDENTITY.json`. These are Python tests, not JUnit/JVM counts; no Gradle or native installer acceptance was rerun.

## Scope and retained failures

- Existing MSI registration/rollback failures remain FAIL. This change does not repair them.
- The old administrator one-shot helper already ran and continues to pin its original probe hash. It was not edited to adopt this new source, and must not be replayed. Future actions require their own concrete reviewed receipt.
- The pending 1009 manual UI helper does not reference the native probe and is unchanged.
- The running real relocation uses frozen production source `98746a73` and its private JVM companion; it does not import this Python module. No second import or additional heavy build was started.
- Kotlin, Gradle, recovery, credentials, source archives and ordinary installations are unchanged by this checkpoint.
