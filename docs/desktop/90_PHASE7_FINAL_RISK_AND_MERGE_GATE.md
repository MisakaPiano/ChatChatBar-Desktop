# 90 — Phase 7 Final Risk and Integration/Merge Gate

**PROJECT FINAL RISK AUDIT: COMPLETED / SCOPED APNG ACCEPTANCE CLOSED / PHASE 7 OVERALL NOT YET ACCEPTED / MERGE HOLD**

Date: **2026-10-12 (JST)**. No production/test code, protected branches or upstream source is changed by this review.

## 1. Exact authority and Git state

- Final independently reviewed **production source**: `feature/phase7-win-auxiliary-ux @ 44b50a4f1f613ee05dce33bbcd3e720adde9bc91`. Any subsequent doc-only HEAD must remain distinct from this production SHA.
- `desktop @ b3ecd41267906526e7b603972f7388e59c90648d`; fork `master @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35`; parked `sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721`. All remained unchanged at audit.
- Formal validated upstream: ChatChatBar **1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**. `14` / `60` individually authorize specified post-baseline image forward ports; these do not assert full 1.4.4 compatibility.
- Latest observed upstream/master at audit: `c90718420b8c82b298eceee5ae1665773ac120cb` (six commits beyond fork master 1.4.4). Changes include backup, SaveSlot, storage, image, memory and runtime domains. **HIGH-risk queued future sync assessment; NO SYNC** for Phase 7 closeout. Do not promote the formal baseline.
- GitHub compare from `desktop` to production `44b50a4f`: ahead **105**, behind **0**, and reports 300 file entries (GitHub compare output size may be bounded). These are *integration blast-radius facts*, not proof of full-file inventory/source-integrity review; a complete local `git diff --name-status desktop...HEAD` remains a final gate.

## 2. Accepted and unchanged evidence

- Phase 7 frozen Slice A / B1 / C / B1+C integration / B2 / D / WIN, W2 History/Studio, metadata dialog M1–M3: previous Project+user acceptance retains the exact evidence and limits in `69`, `71`, `72`, `74`, `75`, `76`, `78`, `80`–`83`. **Do not repeat frozen GUI testing**.
- `86`: prior production `698f0cd55e0ae370650fac012cdb50f0dd7e033f`, 18/18 reported; **16 PASS / Group 11 FAIL / Group 15 NOT TESTED**. Historical results are immutable evidence.
- `89`: post-fix production `44b50a4f` Project R1-A/B code review PASS; Codex-reported JDK17 compile and **2 suites/19 tests PASS**, exact-source package **210/210 SHA-256 PASS**; user-reported Windows receiver **3/3 PASS**. **The specific Group 11 APNG copy/share defect is CLOSED**, not the complete privacy-export checklist.
- `66` and `79`: exact 115-row crosswalk independently reconciled. Evidence class remains **59 SIGNED / 12 USER-EVIDENCED / 9 PARTIAL / 28 TECH-EVIDENCE / 7 OPEN**. The historical `66` checkbox discrepancy (12 literal vs 59 supported) is **NOW CLOSED** by a separately scoped docs-only edit: exactly 47 additionally signed IDs, 59 literal `[x]`, 56 unsigned. Zero checklist/ledger mismatches; no user GUI retest.

## 3. Risk register and dispositions

| Risk / evidence limit | Evidence | Project disposition |
| --- | --- | --- |
| APNG animation file copying missing | `87`, `88`, new `89` | **CLOSED** for Windows Explorer copy-file and restoration on reviewed source; other receiver apps not claimed |
| Interrupted or foreign clipboard cache entries / retention clocks | R1 source and 19 focused tests in `89` | **PROJECT CODE REVIEW PASS**; unknown bytes retained, 32-entry/256-MiB bounded quota, confirmed-release seven-day policy |
| External OS temp cleanup of active staged file | `java.io.tmpdir/ccb-image-clipboard-v1` | **RESIDUAL PLATFORM RISK / not R1 blocker**; operating-system cleaning can bypass app policy. Provide Save fallback; evaluate more durable app-owned per-user cache at a separate platform/release gate if failure observed |
| Group 15 Advanced/Diagnostics (N-01..04) | `84` / `86`: user **NT**, six-width layout focused code/test evidence and source Inspector wiring | **TECH-EVIDENCE ONLY**. No user sign-off, no repeat of 18 groups. This may be accepted only as an explicit limited-risk exception at final product gate |
| K-01..04 privacy export / strip / original / animation | `79` K section; user tested APNG Copy, not full privacy export | K-01/02 **OPEN**; K-03/04 **TECH-EVIDENCE**. New APNG receiver result does not auto-sign K or establish privacy bit-clearing outcome on real user file |
| Other 7 OPEN, 9 PARTIAL, 28 TECH-EVIDENCE rows | `79` exact crosswalk | **Evidence gaps, not 44 established defects**. Preserve individual labels. No paid AI use or destructive data mutations merely to fill checks |
| Real current NovelAI/provider capability and paid operations | `49`, `55`, `68`, `20`; historical live NovelAI count 1/8, no new AI/Enhance/Upscale live calls | **LIMITED PROTOCOL/FAKE EVIDENCE**; do not advertise full provider compatibility. Actual rejection or authoritative provider change reopens targeted compatibility risk |
| Historical D Native/HWND and WIN synthetic `ScrollBy` RED | `76` / `78` | Retain **HISTORICAL RED**; established Windows GUI acceptance remains valid. Do not burn quota rerunning known unstable tests as a proxy for user behavior |
| Structured positive Prompt clipboard vs full formatted Prompt exchange | `55`, `82`, `79` | Current positive clipboard implementation **technical**, W3 I2 user NT; proposed complete cross-app formatted Prompt belongs to **P17** pending design, not a Phase 7 parity false claim |
| Current upstream changes after 1.4.4 | observed upstream/master `c9071842` vs fork master `55040968` | **HIGH / parked** until independent sync-window audit; no baseline promotion or absorption in Phase 7 |
| Large merge blast radius | 105 commits ahead; connector result max-size uncertainty | **MERGE HOLD** pending complete local changed-file inventory, exact-source automated integration evidence and explicit decision |

## 4. Required final integration gate before any merge decision

1. On the feature branch, verify clean local worktree and precise origin HEAD (after docs-only reconciliation, local Codex must FF-only sync any docs commits; production SHA remains `44b50a4f`).
2. Produce complete `git diff --name-status origin/desktop...HEAD` and changed-file totals locally. Confirm no unexpected files, secrets or unintended upstream/Prompt/Package/Entity/Provider behavior; review exceptions under `14` / `60`.
3. Reuse already accepted slice-level evidence. Execute **one targeted milestone automated gate** at exact source for affected Desktop/shared/Android compile/regressions and `git diff --check`; do not rerun old full GUI 18 groups or Native HWND/synthetic-scroll RED. Explicitly separate earlier full-regression evidence at older SHAs from fresh R1 tests at `44b50a4f`.
4. Reconcile exact source/package identity, JDK17, checksum manifest, current parity scope, and current evidence register; Project independently reviews actual GitHub evidence/diff and any introduced changes. No real AI/NovelAI paid tests solely to sign boxes.
5. Project then issues a **new explicit final disposition**, choosing whether a qualified Phase 7 acceptance is supportable with the listed user-NT / protocol / platform risks. An independent **user-authorized merge decision** is still required; no automatic merge.
6. The actual `desktop` merge, if later authorized, must be controlled and **FF-only where ancestry remains valid**. `master`, parked `sync/1.4.4`, and official upstream are not merge targets.

## 5. Current decision

**APNG narrow defect = CLOSED. Phase 7 final product = NOT YET FULLY ACCEPTED; feature = NOT MERGED.**

Do not interpret 59/115 signatures as complete 115/115 user acceptance. Do not retroactively turn Group 15 NT into PASS. No new feature implementation, full manual suite, upstream sync, Prompt rewrite, local data deletion or `desktop` integration is authorized by this audit. Documentation reconciliation is **COMPLETE** on the current feature docs HEAD, with no production change. The next work is the bounded automated final integration gate, followed by Project/user explicit risk and merge decisions.