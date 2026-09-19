# Upstream sync playbook

1. Require a clean worktree.
2. Fetch origin/upstream and tags; report the validated baseline, observed upstream, drift and compatibility separately.
3. Fast-forward mirror `master` to `upstream/master` only, then verify they have no content difference.
4. Diff the recorded validated baseline commit against the new mirror master.
5. Classify changed files by feature domain and identify high-risk runtime paths.
6. Create `sync/ccb-X.Y.Z` from the current `desktop`, then merge mirror master into that sync branch with upstream ancestry preserved.
7. If the merge conflicts, a downstream file rewrites upstream behavior, or Prompt differs from official upstream, stop for separate Project review instead of guessing a resolution.
8. Run source-integrity checks, targeted compatibility tests and Android/Desktop compile checks.
9. Manually review Package, Prompt, WorldBook, Provider, Memory and SaveSlot changes. Prompt diffs require source/runtime impact review through final logical messages and serialized transport.
10. Sync official upstream Prompt text and semantics as-is; do not retain an old Desktop Prompt or create a Desktop Prompt fork.
11. Repair shared code or Desktop adapters only when the impact review proves it is required.
12. Keep unimplemented parity rows PENDING. Mark a row IN_PROGRESS only when Desktop implementation work actually begins, not merely because upstream changed.
13. Promote `10_UPSTREAM_BASELINE.json` only after source integrity, target tests and manual high-risk review pass.
14. Merge `sync/*` into `desktop` only after Project review.

A future GitHub Action may detect upstream changes and open a report, but must not automatically merge or declare compatibility.
