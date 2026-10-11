# 89 — Phase 7 APNG Clipboard R1 · Windows Receiver Acceptance

**CURRENT: APNG COPY/SHARE DEFECT USER VERIFIED / CLOSED · PROJECT CODE REVIEW PASS · PHASE 7 OVERALL NOT ACCEPTED / NOT MERGED**

Date: **2026-10-12 (JST)**. Source: user-reported real Windows acceptance in ChatGPT Project, linked to the exact reviewed production commit. This is a new post-fix observation; it does **not** change historical results in `86`.

## Source and authority

- Fork/feature: `MisakaPiano/ChatChatBar-Desktop` / `feature/phase7-win-auxiliary-ux`.
- Final reviewed production: `44b50a4f1f613ee05dce33bbcd3e720adde9bc91`. Project independently fetched the GitHub production helper/test and actual diffs.
- Initial APNG-copy implementation: `cdfbd0feaff0492c49784a6108aba1b26da69784` — initial review **HOLD**; see historical `88`.
- R1-A/R1-B cache hardening: `2c83db1d21d5116ed03e79b22f5aef3f911c1eec` → `4d92fefc31ba1af3591ffe9ec564298820b455e1` → final `44b50a4f1f613ee05dce33bbcd3e720adde9bc91`.
- Project code review **PASS** covers incomplete/foreign cache isolation, conservative bounded quota, v1/v2 metadata, first confirmed clipboard release-based seven-day grace, clock rollback/reactivation protection and original animation/file clipboard flavor. No unrelated upstream/Prompt/Package/Entity/Provider changes were authorized.

## Codex exact-source validation and package (reported evidence, not Project-run)

- Temurin JDK 17 focused regression: **2 suites / 19 tests, 0 failures / 0 errors / 0 skips**; compile and diff/scope checks PASS.
- `:desktopApp:createDistributable --rerun-tasks`: **PASS**, 12 executed tasks.
- Independently reread distribution manifest: **210/210 SHA-256 matched**, 0 missing/extra/mismatched, as reported by Codex. Project did not read the user's `H:` drive files.
- Package: `H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-apng-r1-distribution\ChatChatBarDesktop.exe`.
- EXE SHA-256: `E5D45E5389E7584B9D8477EFE34543DE1069E6550560A3A3C53D164152958B8F`.
- Desktop JAR: `desktopApp-f2efedf41f5f43635f4fb5db8e56c349.jar`, SHA-256 `63FDAC21586DE26B15E8E7874E87466625289F41BD3AD125395B09A691B46949`.
- Shared JAR: `sharedCore-ab642c64b398a3d31ab9cd258c653bd.jar`, SHA-256 `CBEE22DDDFA081AC4A7F788201C6CAFB9A08950E2849A2B114767F1F4DCAA847`.
- Identical EXE launcher hash to the older package is **not** evidence of old application code; the Desktop JAR changed.

## Focused Windows user acceptance — 3/3 PASS

The user reported, in one concise Project acceptance result:

| Verification | User result |
| --- | --- |
| APNG disguise exposes **复制文件** | **PASS** — button visible |
| Windows Explorer `Ctrl+V` receives an actual file | **PASS** — pasted file created |
| Animation restoration | **PASS** |

**Disposition: the original `87` “伪装后没有复制功能，只能选择保存” APNG Copy/Share gap is FIXED / USER VERIFIED / CLOSED for this precise source and Windows Explorer receiver.**

## Strict evidence boundaries

- `86` remains the historical one-launch **18 reported / 16 PASS / Group 11 FAIL / Group 15 NOT TESTED** on older source `698f0cd55e0ae370650fac012cdb50f0dd7e033f`. The new fix **supersedes the specific Group 11 APNG copy failure**, not the historical report or all conditions within that group.
- `66` / `79` checklist K-01..K-04 concern privacy export, source retention and non-flattening within the privacy workflow. The user did **not** report executing the complete privacy export/privacy metadata-strip workflow in this acceptance. **No K item is newly signed by this APNG copy test.** `79` remains 59 SIGNED / 12 USER-EVIDENCED / 9 PARTIAL / 28 TECH-EVIDENCE / 7 OPEN.
- Group 15 N-01..04 remains **USER NOT TESTED / TECH-EVIDENCE**; do not turn this into user PASS.
- Explorer file paste does **not** imply all destination applications accept file-list clipboard or display disguised APNG animation in-place.
- Clipboard staging currently uses `java.io.tmpdir/ccb-image-clipboard-v1`; app-managed seven-day retention does not prevent OS/third-party temp cleaners from removing the file. Known risk: future app-owned nonvolatile cache design may be considered at a separate platform/release gate, not as an R1-A/B regression.
- Unobserved clock jumps cannot be reconstructed. No real AI/NovelAI call, upstream sync, protected-branch movement or `desktop` merge was performed.

This acceptance closes the **narrow APNG clipboard defect only**. Phase 7 final integration risk and merge decision remain governed by `90_PHASE7_FINAL_RISK_AND_MERGE_GATE.md`.