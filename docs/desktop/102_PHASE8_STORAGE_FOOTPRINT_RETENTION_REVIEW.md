# 102 — Phase 8 Local Footprint Sizing and Three Mixed 507-MiB Package Review

**LATEST USER CLEANUP DECISION (2026-10-12):** The user declined the archive-first plan and explicitly approved **direct deletion of confirmed obsolete old project artifacts**. The prior copy-only approval in `103` is canceled (not executed); the new `104_PHASE8_DIRECT_STALE_ARTIFACT_CLEANUP.md` controls specific 30 historical distribution candidates and the already approved A+B 13 cache directories. Unique acceptance evidence and real user data remain protected; old binary SHA divergence alone does not require retaining every runnable distribution. No deletion has yet been performed.

**CURRENT FOLLOW-UP APPROVAL: COPY ONLY (2026-10-12).** User approved copying the three entire historical build/package roots unchanged from H: to `I:\CCB-Desktop-Archive\Phase7\`, with file-by-file SHA-256 verification, as specified in `103_PHASE8_THREE_PACKAGE_ARCHIVE_COPY_GATE.md`. H: source deletion or moves **not approved**; this approval supersedes only the earlier *no archive copy yet* decision at the end of this review. The 18 new deletion candidates and the other 30 historical distributions remain **not authorized** for deletion. Pre-approved A+B 13-path cleanup still blocked. Execution pending, 0 bytes recovered.

**PROJECT REVIEW: TARGETED READ-ONLY EVIDENCE ACCEPTED / HISTORICAL PACKAGES ACCOUNT FOR SPACE / NO NEW DELETION OR MOVE AUTHORIZED**

Date: 2026-10-12 JST. Input: user-supplied Codex read-only follow-up to `101_PHASE8_DISK_FOOTPRINT_PROJECT_REVIEW.md`. Project independently compared `38_PHASE7_FINAL_REVIEW.md`, `43_PHASE7_R2_MANUAL_ACCEPTANCE.md`, `44_PHASE7_R2_REVIEW.md`, and the older P6 cleanup baseline `32_PHASE7_ENTRY_HANDOFF.md` against the reported local directory topology and selected hashes. **Only Codex measured or hashed the local H: files; Project reviewed the contracts and math, not local bytes.**

## 1. Planning size for a healthy H: development checkout

- Counted current H: repository footprint: **9,569,861,448 bytes = 8.9126 GiB**, **48,167 files**, excluding **52 protected/unscanned directories**. This excludes unknown user/portable/application data from the capacity claim; logical bytes are not guaranteed freed allocated bytes.
- Of the counted bytes, **33 historical distributions ≈ 7.5843 GiB**; **everything else ≈ 1.3283 GiB**. Therefore keeping *all 33* complete distributions in the active build tree, rather than Kotlin/Android source itself, explains the bloat.
- Pure mathematical reference: retain one `~206.37 MiB` exact-source runnable package while cold-archiving the other 32: `1.3283 + 206.37/1024 ≈ 1.53 GiB` counted H: footprint, **before a new build regenerates caches**, excluding unknown/protected roots. Two retained packages ≈1.73 GiB. These are **counterfactual planning figures, not an accomplished reclaim**.
- Actual historical engineering evidence: `32_PHASE7_ENTRY_HANDOFF.md` reports the repo reduced from **~8.829 GiB to ~1.048 GiB after conservative cleanup and successful rebuild/package at the P6→P7 boundary**. That confirms a ~1-GiB order-of-magnitude lean development repository was achieved once, but does not impose a permanent fixed limit on Phase 8 builds.
- **Recommended active H: retention target**: **1.5–3 GiB between major gates**, allowing a recent full package and regenerable build outputs; **~3–6 GiB temporarily** for broad Gradle/JDK17/Android/Compose tests and packaging, subject to actual high-water observation. Separately keep substantial **free disk headroom** rather than treating zero spare space as acceptable: initial planning target >= **5 GiB free** before broad build/package runs, higher if several isolated packages are required. These are PROJECT ESTIMATES, not tested exact Gradle requirements or release gates.
- `.git` ≈216 MiB and docs ≈11 MiB are comparatively small and are **not proposed for pruning**. Store long-term historical runnable binaries outside the current repo build tree, keeping exact SHA, manifests, logs and test/acceptance provenance. Android SDK/JDK on other volumes are not automatically counted as H: repo footprint.

## 2. Codex targeted three-directory structure: provenance accepted

Root relative to `H:\ChatChatBar-Desktop\app\desktopApp\build\`:

| Root | Total MiB/files | Current genuine packaged runtime MiB/files | Candidate non-runtime copies/build outputs MiB | GitHub exact-source authority |
| --- | ---: | ---: | ---: | --- |
| `phase7-final-distribution` | 507.287 / 413 | 205.273 / 210 | 301.993 | `f8ca2619e67a975e392a7b4563b4ace834dca89a` (38) |
| `phase7-r2-distribution` | 507.681 / 413 | 205.470 / 210 | 302.191 | `b88d4088fe42c3cfa7ab1de8576acd464e5ba896` (44) |
| `phase7-r2-reaudit-distribution` | 507.919 / 413 | 205.602 / 210 | 302.296 | `1843dac42bcbcc8aac4fc7a9f46450946edc94d2` (44) |
| **Total** | **1,522.887 MiB** | **616.345 MiB** | **906.480 MiB** | All are separate historical checkpoints |

- A genuine exact package is the **entire** `compose/binaries/main/app/ChatChatBarDesktop/` subtree, including its `EXE`, `app/` Java JARs and `runtime/` Java modules. The fixed `exe` file itself is **not** a complete runnable artifact.
- Targeted six per-root *non-runtime* categories: `generated`, `resources`, `libs`, `compose/default-resources`, `compose/tmp/main/runtime`, `compose/tmp/skiko`. Codex reports these total **950,512,690 bytes** (906.480 MiB). `libs/desktopApp.jar` independently matched the respective packaged business JAR hash within each root; the **three different versions have different business JARs**, so no cross-version package identity is inferred.
- The remaining non-runtime control files **must be preserved**: `compose/tmp/createDistributable.args.txt`, `compose/tmp/createRuntimeImage.args.txt`, `compose/tmp/checkRuntime/properties.bin`, `compose/tmp/createDistributable/libs-mapping.txt`, and `tmp/jar/MANIFEST.MF`. Historical external `phase7-*-package.log`, `launch-result`, init scripts, `phase7-final-evidence`, `phase7-r2-evidence`, original screenshots and Native/ScrollBy RED remain protected.
- On local directed hashes: all three EXE SHA-256 matched historical `E5D45E...` launcher; R Desktop JAR `76730B78...` and re-audit Desktop JAR `9230C88A...` matched exact published evidence in `44`. F Desktop JAR hash `D924B33D...` was computed **locally** but `38` did not document that JAR hash, so its historical match is **not independently confirmed**. A sharedCore `D0A80F...` matched `44`; do not overwrite historic source evidence with new assumptions. Targeted hash proof is **not** a full 210/210 package reread.
- `44` actually configures independent `:desktopApp` `buildDirectory` overrides to these `*-distribution` roots, supporting the explanation that both compiled intermediates and packaged runtime accumulated in the same directory. No independent gate screenshots, JUnit XML or other acceptance evidence was found *inside* these three roots in Codex's read-only file walk.

## 3. Two alternatives, evidence class and expected H: recovery

| Strategy | Candidate logical bytes | Advantage | Required separate authorization |
| --- | --- | --- | --- |
| **A. Selectively remove only 18 exact disposable subdirectories** (three roots × six generated paths), retaining complete 210-file runtimes and metadata | **906.480 MiB** | Lowest change to current runnable versions | Explicit user approval of these **18 new exact paths**, full safe reparse/process/consumer checks |
| **B. Copy each entire historical root to another volume and independently verify before optionally removing H: source** | **1,522.887 MiB** when all 3 H: source copies are ultimately removed | Preserves full pre-R2 reproducibility including build intermediates and releases more H: space | Explicit user approval of destination, copying, archive validation, then **separate approval** of deleting three H: source directories after verified restore |
| **Long-term cold archive of other historical distributions** | Up to approx. `7.58 GiB - local retained packages` | Reaches 1.5–3 GiB active-footprint target | Distinct per-package classification and user-approved archive/removal plan; **NEVER** active B2-R3 / WIN processes or live data |

Alternative B is the **Project-preferred order**: archive whole, validate, then decide H: pruning. It does not require deleting unique historical evidence. Any permitted copy must preserve relative paths, file counts, manifests/production JAR identities and timestamps, reject reparse/unknown/user data, use explicit capacity and destination-root preflight, and be validated via independent file inventory/hash/restore before touching original source. **No actual archive was created or tested.**

- Reported available NTFS fixed volumes: `I:` ≈384.05 GiB free (**preferred capacity**), `D:` ≈13.15 GiB (**possible but small**); C 6.64 GiB, F 1.60 GiB, E 0.26, G 0.98, H 0.57 GiB. **Capacity alone does not prove archive destination path permissions, physical disk health or durability.** Archive directory name and storage policy require an explicit user decision.
- At latest `H:` reported free 587.54 MiB, *illustrative logical* free-space upper-order results if allocated bytes roughly track: A alone => **~1,494 MiB** free, approved original 13-path cleanup alone => **~1,295 MiB** free, A + original => **~2,201 MiB**, entire 3 roots removed after valid external archive => **~2,110 MiB**, entire 3 roots + original A+B => **~2,818 MiB**. These are **not measured physical post-action values**, and generated files may regrow when Gradle reruns.
- Reclaiming only 906.480 MiB while retaining 30 other old releases is not the ultimate solution: a bounded **active workspace vs immutable off-volume historical artifact store** is. Never run Git GC, `gradlew clean`, blanket `build` deletion, in-place hardlink dedupe, symlink substitution or raw destructive prune to achieve a target.

## 4. Windows resident JVM diagnosis boundary

Two historic `phase7-b2-r3-distribution` and `phase7-win-manual-distribution` JVMs remain reported as alive. Windows Wait states and 4s zero I/O/CPU deltas support only momentary quiescence. **Their shutdown stage and data-root ownership status remain UNRESOLVED.** Do not rename, archive, stop or modify those two executable roots as part of this work; do not remove root locks. Prior `96`/`98` A+B cleanup remains explicitly user-approved but **blocked by its original preflight**; this review does not broaden that authority to the 18 new paths or old distribution roots.

## 5. Disposition

**PROJECT PACKAGE AUDIT REVIEW PASS** with the distinct evidence grades above. Desktop production branch, validated upstream 1.4.1 baseline, P7 qualified acceptance and D-038 P11 `.cbbackup` Public Preview blocker remain unchanged. P8-S1 pure voice core extraction not yet started. No user data, binary, source code, runtime, process, cache or distribution was changed by review.

**NEXT DECISION:** Whether user chooses an off-H archive-first workflow (recommend `I:` on capacity grounds, staged verification then distinct H: source cleanup approval) or narrower exact 18-path runtime-retaining generated file deletion; no new deletion is yet authorized.
