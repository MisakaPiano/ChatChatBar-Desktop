# Upstream sync playbook

1. Require a clean worktree.
2. Fetch upstream and tags.
3. Fast-forward mirror master to upstream/master only.
4. Diff the recorded baseline commit against new master.
5. Classify changed files by feature domain.
6. Create sync/ccb-X.Y.Z from current desktop.
7. Merge mirror master into the sync branch.
8. Mark affected parity rows IN_PROGRESS.
9. Repair shared code and Desktop adapters.
10. Run targeted compatibility tests plus Android/Desktop compile checks.
11. Manually review Package, Prompt, WorldBook, Provider, Memory and SaveSlot changes.
12. Update 10_UPSTREAM_BASELINE.json only after validation.
13. Merge sync/* into desktop.

A future GitHub Action may detect upstream changes and open a report, but must not automatically merge or declare compatibility.
