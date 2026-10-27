# 104 — P8 Direct Prune of Proven-Obsolete Local Build Artifacts

**LATEST EXECUTION STATUS (2026-10-12 JST): CODEX REPORTED COMPLETE / PROJECT PATH-CONSISTENCY REVIEW PASS.** All 30 listed historical distribution dirs and 13 A+B cache dirs were reported DELETED (43/43, 0 skipped or failed), 28,585 files / 8,236,160,764 logical bytes (7.6705 GiB). Project independently matched exact deleted path names to this allowlist; H: filesystem operations are **Codex-local evidence**, not Project-run verification. Three protected P7 full runtimes plus gate/RED/manifest, code, data/backup/SecretStore reported retained. H: free afterward ~38.95 GiB; physical available-space delta cannot all be attributed to this deletion because it exceeds logical bytes and had already changed before the run. **Execution review authority: `106_PHASE8_DIRECT_CLEANUP_PROJECT_ACCEPTANCE.md`.** The original pending text below is the historical pre-execution specification; do not rerun this 43-path batch.

**CURRENT USER DECISION (2026-10-12 JST): DIRECT DELETE WHEN OBSOLETE IS VERIFIED / PREVIOUS I: COPY PLAN CANCELED / EXECUTION PENDING**

## 1. Most recent user instruction supersedes archive-first policy

The user explicitly rejected the previous **copy-to-I:-then-review-then-delete** process as unnecessarily cautious and authorized **direct permanent removal of old local project artifacts once their lack of further use is confirmed**. The latest instruction replaces the previous `103_PHASE8_THREE_PACKAGE_ARCHIVE_COPY_GATE.md` *copy-only plan*; Codex **must not** perform that copy or create cold archives merely because the prior plan was accepted.

**Correct test is ongoing necessity, not whether old JAR hashes match.** Frozen P7 source SHAs, user/manual/code acceptance decisions, gate logs/XML, SHA-256 manifests and original historic failure records may outlive their old executable directory. Different historical business JAR hashes prove different versions, **not that every version needs a permanent standalone Windows runtime**.

This is a **one-pass permission for verifiably obsolete artifacts**, not permission to discard current data, historical evidence, currently executing binaries, or unknown content. The user does not need to reapprove each independently verified obsolete path in the allowlisted class. Uncertain items are individually **SKIP / HOLD with reason**, while verified unrelated candidates can proceed. Do not let two residual JVMs block deletion of unrelated *proven-unused* paths.

## 2. Exact project roots, intended retained products and estimated headroom

Root: `H:\ChatChatBar-Desktop`.

Last Codex inventory: 33 historical distribution directories totaled approximately **7.5843 GiB**. The intended **single current baseline runnable** is `app\desktopApp\build\phase7-apng-r1-distribution` (qualified/frozen P7 production `44b50a4f1f613ee05dce33bbcd3e720adde9bc91`). This **KEEP** is not an assertion of full public-release readiness. Also **temporarily exclude** `phase7-b2-r3-distribution` and `phase7-win-manual-distribution` while their respectively confirmed JVM child processes (historically 4492 and 27568) may still use their executable/JAR/runtime files. Re-check PID+creation time+path on every run, as PID numbers are not stable.

**Expected obsolete distribution candidates (30 exact directory names).** All are direct children of `H:\ChatChatBar-Desktop\app\desktopApp\build\`; no wildcards are authorized for deletion:

1. `phase7-r2-reaudit-distribution`
2. `phase7-r2-distribution`
3. `phase7-final-distribution`
4. `phase6-final-acceptance-distribution`
5. `post-phase6-clean-repro-distribution`
6. `phase7-w3-metadata-manual-distribution`
7. `phase7-final-one-session-distribution`
8. `phase7-d-manual-distribution`
9. `phase7-b1-c-integration-r1-distribution`
10. `phase7-slice-c-r2-caption-distribution`
11. `phase7-slice-c-r1-native-final-distribution`
12. `phase7-slice-c-scheme-a-distribution`
13. `phase7-slice-c-r2-distribution`
14. `phase7-b1-r2-3-distribution`
15. `phase7-b1-r2-2-distribution`
16. `phase7-b1-r2-4-distribution`
17. `phase7-b1-r2-7-distribution`
18. `phase7-b1-r2-6-distribution`
19. `phase7-b1-r2-5-distribution`
20. `phase7-slice-b1-distribution`
21. `phase7-slice-a-r2-distribution`
22. `phase7-slice-a-distribution`
23. `phase7-chat-image-action-distribution`
24. `phase7-design-parity-distribution`
25. `phase7-current-upstream-r1-distribution`
26. `phase7-current-upstream-distribution`
27. `phase7-final-blocker-distribution`
28. `phase7-final-product-distribution`
29. `phase7-ux-presentation-distribution`
30. `phase7-r2-r1-distribution`

The prior read-only inventory estimates these 30 total **~7,147.29 MiB (~6.98 GiB)**, contingent on each passing current unused/no-unique-evidence preflight. The **three ~507 MiB roots**, `phase7-final-distribution`, `phase7-r2-distribution`, `phase7-r2-reaudit-distribution`, are deliberately eligible for **whole-root removal**, not forced to preserve and separately archive their 205 MiB `compose/binaries` runtimes. Project exact-source history `38`/`44` confirms they were superseded by later corrected releases; recent Codex inspection found no standalone acceptance screenshots or gate XML inside these three roots. Small historical build arguments inside are no longer an active product dependency if their relevant commands, source identity and test evidence are retained outside. If unique external evidence unexpectedly points to a file only in the root, **SKIP that root**.

**Previously approved and still eligible: A+B thirteen exact regenerable build/cache directories**, relative to the repo root:

1. `app\app\build\generated`
2. `app\app\build\intermediates`
3. `app\app\build\kotlin`
4. `app\app\build\tmp`
5. `app\desktopApp\build\classes`
6. `app\desktopApp\build\generated`
7. `app\desktopApp\build\kotlin`
8. `app\desktopApp\build\resources`
9. `app\sharedCore\build\.transforms`
10. `app\sharedCore\build\classes`
11. `app\sharedCore\build\kotlin`
12. `app\sharedCore\build\resources`
13. `app\.gradle`

Their previous estimate **~707.32 MiB** remains conditional. Together with 30 old packages total candidate logical reclaim **~7,854.61 MiB (~7.67 GiB)**. Last recorded H: free ~587.54 MiB; theoretical free **~8.24 GiB**, assuming allocated bytes roughly match logical bytes. Never claim measured reclaim before execution.

After this bounded batch, Codex may **report**, but not guess/delete, any other newly discovered stale classes (e.g. obsolete debug APKs in `app\app\build\outputs`, historical package temp/Gradle overrides, redundant Java distributions). The user wants eventual removal of *all proven obsolete* local build products, but every additional path must first have specific necessity/classification evidence; treat it as a separate subsequent deletion batch if not in this list.

## 3. Minimal decisive preflight: do not recreate months of approval gates

For each of the 43 listed *literal* candidate paths:

1. Verify actual Git root, origin, current `feature/phase8-fish-audio` ancestry and **clean worktree**. If remote moved, only safe FF-only alignment; no rebase/reset/force. No `desktop`, `master`, `sync/*` changes. Only exact repo-root children are considered; never follow reparse/junction/symlink ancestors or descendants.
2. Confirm the candidate is a **build-generated/gitignored** artifact and not any tracked source, active user-data root, Portable data, backup, snapshot, profile, media/SecretStore, or an unrecognized sensitive/unknown directory. Do not recursively read private data; file names, sizes and scoped README/manifest references suffice.
3. Check canonical executable/module paths of **currently alive processes**, any active Gradle/Java/Compose build tasks, and any genuine runtime/test/installer dependency in current tracked code/scripts. Treat historic documentation path references as provenance, not evidence that old EXEs remain a current runtime dependency. If a candidate is active/locked, **SKIP that candidate only**, never kill processes to clear it. If active mutation is ongoing, skip the affected item. Two old JVMs must not globally block unrelated directories.
4. Gate manifests, JUnit XML/logs/Native-HWND/ScrollBy RED, failure screenshots and acceptance `docs/desktop` history **outside** candidate roots are **KEEP**. If a candidate itself houses the only irreproducible evidence or data of unresolved ownership, **SKIP**. The old Windows packaged runtime is not automatically irreplaceable merely because binary JAR hashes differ; accepted source remains in Git and concise file hash identities remain in documents or can be noted in the final delete report.
5. No iterative multi-GB full-source SHA rehash is needed simply to dispose of a superseded old build. Existing verified package manifest, reported source SHA, and real path/consumer checks are sufficient unless individual evidence is ambiguous. Stop and disclose unusual paths rather than deleting them.

**Do not use `git clean -fdx`, `gradlew clean`, recursive glob `phase7-*`, `reset --hard`, global Gradle cache prune, forced process termination, handle/lock removal, or entire `app\desktopApp\build` tree deletion.**

## 4. Execution and reporting

After the preflight for a given exact candidate passes, **permanently delete that one directory**, safely without following links. Processing per item permits SKIP on an unsafe candidate without abandoning the other 42 independently validated items. Preserve/recheck path identity before each operation. If tools cannot safely constrain deletion to the literal validated directory, SKIP rather than falling back to a broad wildcard. Apply throttling/low I/O priority if user desktop becomes unresponsive.

For the 3 large Gradle-mixed roots, historical logs, gate summaries, screenshots and native RED evidence live in other explicitly referenced paths; retain them. Do not start a new app build or paid model API call in this cleanup turn. Do not copy to `I:` or any archive first.

Report per path: **DELETED / SKIPPED / FAILED**, verified source/size, actual file count/bytes removed, reason for skips, H: free bytes before/after and changed physical storage (avoid adding double-counted parent/child sizes), final Git HEAD/clean status and whether B2/WIN JVM PIDs remain. **No false claims that failed/skipped paths were cleaned or that P7 historical acceptance vanished.**

## 5. Hard protected set

**KEEP ALWAYS during this task:** `.git`, tracked source/test/docs/skills, original upstream/master/sync refs, `phase7-apng-r1-distribution` latest accepted source package, in-use `phase7-b2-r3-distribution` & `phase7-win-manual-distribution`, all `*-gate` and `*-evidence` files, `phase7-final-evidence`, RED/JUnit/manifest/log/support bundles, `device-entities`, `.phase7-live-data`, data root(s), backup/snapshots, `app\build\wix311`, `app\desktopApp\build\libs` (separate pending review), credentials/DPAPI/SecretStore, unknown/corrupt or symlinked content.

This local maintenance task does **not** reclassify Phase 7 acceptance, promote upstream baseline 1.4.1, implement P8 Fish core S1, release Preview, or change D-037/D-038 data-safety. It is separate from the unresolved JVM shutdown defect in `99`/`100`.

**CURRENT: USER AUTHORIZATION RECORDED FOR 30 OLD DISTRIBUTIONS + PREVIOUS A+B 13 CACHE DIRS, CONDITIONAL PER-PATH DIRECT DELETION; ZERO ACTUAL DELETIONS CONFIRMED YET.**
