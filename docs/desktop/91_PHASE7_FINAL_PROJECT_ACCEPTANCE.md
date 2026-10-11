# 91 — CCB Desktop Phase 7 Final Project Acceptance

**PROJECT FINAL DECISION (2026-10-12 JST): QUALIFIED PHASE 7 ENGINEERING ACCEPTANCE / MERGE-ELIGIBLE WITH DECLARED LIMITS / NOT MERGED / NOT RELEASE-READY**

This decision closes the Phase 7 **engineering milestone**, not all 115 user checkboxes, not the public-release gate, and not compatibility with observed unvalidated upstream. The user must explicitly authorize any integration into `desktop`. No production/test files, protected branches, formal upstream baseline, or parked sync branch are modified by this record.

## 1. Audited identities and immutability

- Fork: `MisakaPiano/ChatChatBar-Desktop`; reviewed feature docs HEAD before this record: `4992af94002489652dc4c1249fac6f0ec717f96d`.
- Frozen final **production** SHA: `44b50a4f1f613ee05dce33bbcd3e720adde9bc91`; `44b50a4f..4992af94` contains **13 docs-only commits / 12 docs/desktop paths / 0 production changes** (Project GitHub compare PASS).
- Integration base: `desktop @ b3ecd41267906526e7b603972f7388e59c90648d`. GitHub compare confirms feature ahead **118** / behind **0** at `4992af94`; local Codex complete inventory independently *reported* 118 ahead / 0 behind, **425 changed-file entries** (including renamed paths counted individually: 479), comprising Desktop 133, sharedCore 101, Android 33, Skills 7, Docs 149, Other 2. The GitHub connector returned only **300** entries, therefore Project does **not** claim independent verification of all 425 individual paths.
- Fork `master @ 550409689df8c51f459fb50b4e04c8ac2fa4bf35` and parked `sync/1.4.4 @ 9b6378dbb595dd2f3ff5143a7a8e46653c99e721` unchanged.
- Formal validated upstream `1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`; selectively approved later image-domain ports are documented in `14` / `60`, and the existing `ace632c` Prompt safety exception remains the only approved exception identified there. Latest observed upstream/master `c90718420b8c82b298eceee5ae1665773ac120cb` remains **observed/unvalidated**, six commits beyond fork mirror. **NO SYNC / NO BASELINE PROMOTION**.
  
## 2. Final automated gate (Codex-reported local evidence, Project-independent source review)

The user supplied Codex's final read-only gate report at feature `4992af94`. The Project independently confirmed head, ancestry, production/docs boundary, authorized selective-source mapping and existing exact-source reviews, but **did not access H: files or independently execute Gradle/Windows tests**.

| Gate | Suites | Tests | Fail / error / skip |
| --- | ---: | ---: | --- |
| sharedCore JVM | 111 | 722 | 0 / 0 / 0 |
| Android JVM | 135 | 805 | 0 / 0 / 0 |
| Desktop bounded non-Native | 29 | 234 | 0 / 0 / 0 |
| **Sum** | **275** | **1,761** | **0 / 0 / 0** |

- Three module JDK17 compilation and full changed-tree `git diff --check`: **PASS per Codex**.
- An initial Android SDK environment failure was corrected by a process-local `ANDROID_HOME`; the passing test run followed, and the initial log was retained. This is not production regression evidence.
- Desktop **is not a full final-HEAD suite**. Native/HWND, historical synthetic `ScrollBy` and actual-window methods were excluded by the approved limited gate. Earlier production `56ac3063f4817ce5b0eb4dfea3f5a6d98bfd14a1` had Codex-reported Desktop full **114 suites / 1036 tests PASS** in `68`; this is **historical**, not a claim that 1036 tests were rerun at `44b50a4f`. Final clipboard-focused JDK17 regression **2 suites / 19 tests PASS**, and exact-source package **210/210 SHA-256 PASS** were reported separately in `89`.
- Codex local complete inventory and sensitive-boundary check reported **no new unapproved scope**, six NovelAI literals preserved against declared baseline and three safety literals preserved against approved official commit. The Project cannot independently audit 125+ additional files truncated from GitHub compare; only previously reviewed actual source/diffs and the Codex complete local inventory support this conclusion.
- Final gate runtime **7m33s**, user-reported Pro weekly allowance **80%→78%**; no source/docs change, commit, package rebuild, paid AI calls or merge in that Codex run.

## 3. User and independent code evidence

- Previously reviewed/frozen A, B1, C, B1+C, B2, D, WIN, W2 History/Studio and metadata-dialog M1–M3 retain their own exact-source user/Project acceptance `69`, `71`–`76`, `78`, `80`–`83`. Their GUI checks do not need repeating.
- `86` remains the **historical** original 18-group Windows record: **16 PASS / Group 11 FAIL / Group 15 NOT TESTED** at old source `698f0cd5`.
- `87`/`88` original APNG clipboard defect/HOLD were resolved by reviewed R1 code at `44b50a4f`; `89` records real Windows **3/3 PASS** (file-copy button, Explorer file paste, animation restoration). The specific old Group 11 APNG Copy/Share defect is **FIXED/CLOSED**. This is not a claim that the full Group 11 K privacy workflow was manually retested.
- `66` literal checklist now **59/115 checked**, exactly matching `79`'s authoritative **59 SIGNED / 12 USER-EVIDENCED / 9 PARTIAL / 28 TECH-EVIDENCE / 7 OPEN**. The 56 unchecked items remain what they were; historical Group 15 NT remains NT.

## 4. Project source spot-check of the seven OPEN items

These seven entries stay **OPEN as user acceptance**, but a focused Project GitHub source inspection at current feature source did not find an unimplemented UI entry:

| Item | Grounded implementation evidence | Acceptance limitation |
| --- | --- | --- |
| A-11 local Tag/Codex research progress | `DesktopDesignConversation`: collapsible `本地 Tag 检索 / 设计进度`, reasoning section | User did not confirm this path |
| J-02 before/after comparison | `DesktopImagePostProcessPanel`: drag-updated split and two images | No genuine postprocess output manually checked |
| J-04 stop | Same panel: RUNNING `停止` calls `controller.stop()` | Real running cancellation not user tested |
| J-05 save/share/re-use result | Same panel: `保存 PNG`, `复制图片`, `用作当前导入图片` | Desktop save/copy/re-use provides intended equivalents; real postprocess result not user tested |
| K-01 privacy export | `DesktopImageToolsDialog` explicit `隐私导出 · 清除元数据与像素隐写` | Actual local privacy output not user tested |
| K-02 privacy explanation | Same dialog explains LSB clearing and source retention | User did not explicitly verify label |
| P-01 Chinese Prompt annotation | `DesktopNovelAiStudioPanel` translated toggle; `DesktopStudioPromptField` overlay/source-identity logic | No exact last-batch per-ID signature |

This is **SOURCE-PRESENT**, not a new `SIGNED` classification or full live user behavior acceptance.

Group 15 `高级 / 诊断` also has the actual Studio row and Inspector navigation adapter plus earlier six-width targeted tests, but remains **USER NT / TECH-EVIDENCE ONLY**. Its untested real Windows alignment is accepted as a **specific low-impact UI evidence limitation for this milestone**, not user approval or a regression claim.

## 5. Residual-risk decisions

**Accepted solely for Phase 7 qualified engineering milestone, retained as documented limitations:**

1. **Clipboard temp-file lifetime:** cache root under `java.io.tmpdir/ccb-image-clipboard-v1`; external cleaners can invalidate an active clipboard path. App's own retention/quotas are reviewed; user Save fallback exists. Escalate on real receiver failure; consider nonvolatile per-user cache at a separate platform/release gate.
2. **Unobserved clock jumps:** durable release timestamps and conservative uncertainty handling protect observable rollback cases; cannot infer elapsed monotonic time across unobserved offline clock resets. This limitation is not a claimed universal seven-day OS guarantee.
3. **Incomplete user-surface sampling:** Group 15 NT; K-01/02 OPEN, K-03/04 TECH; other OPEN/PARTIAL/TECH rows retain their actual evidence. No 115/115 claim. Historical Native/HWND and synthetic ScrollBy RED remain historical RED; actual user Windows acceptance was the already authorized narrow alternative.
4. **Limited paid/live API evidence:** NovelAI historical real generation **1/8**, no extra paid Design/Enhance/Upscale/live calls solely for acceptance. Transport/policy/fake evidence supports implementation, **not today's full provider model/sampler capability**; reopen on authoritative provider capability or an observed rejection of previously supported allowed behavior (`49`, `55`, `20`).
5. **Version boundary:** CCB formal upstream **1.4.1**, including only individually documented selective later image exceptions; no full 1.4.4 or observed `c9071842` compatibility assertion. New upstream changes are risk-audited at a future sync window; before any public release, reassess the accumulated high-risk drift.
6. **Future separately owned product enhancements:** full formatted Studio Prompt cross-app interchange and complete generation presets P17, OS shell integration P15, audio P8, RAG/Memory/SaveSlot/Moments/Community in their recorded later phases (`55`/`64`/`17`). Do not relabel these as completed P7 features.

**No unresolved blocker demonstrated within the explicitly tested Phase 7 engineering scope.** The absence of a blocker does not prove every untested user path.

## 6. Final Project disposition and integration authority

### Phase 7 milestone

**QUALIFIED ACCEPT / ENGINEERING COMPLETE FOR THE DECLARED PHASE 7 SCOPE.**

This upgrades the earlier `90` **MERGE HOLD pending automated final gate** to **MERGE-ELIGIBLE**, while retaining the complete evidence scope/risks above.

### Merge authorization

**NOT MERGED; USER EXPLICIT APPROVAL STILL REQUIRED.** Project's qualified acceptance is a recommendation/technical gate, not a user command to move a protected branch.

Once the user explicitly authorizes integration, the Codex handoff must:

1. Verify exact current origin feature and `desktop` SHAs, clean worktree, feature ancestry/ahead-behind, and unchanged production-source tree versus `44b50a4f` (docs-only commits allowed).
2. If any other party advanced either branch, STOP for fresh Project impact review; never overwrite/rebase/force.
3. Perform **FF-only integration** of the reviewed feature into `desktop`, with no automatic upstream or parked sync changes.
4. Confirm origin `desktop` equals the reviewed feature docs closeout SHA and no extra merge commit. No required rerun of the already reviewed regression simply because of docs-only finalization.
5. Return exact final SHA, affected refs, and clean status to Project. Do not advance Phase 8 or perform an upstream sync without a separate task.

This is **not a release/installer approval**. Public release remains governed by `20_RELEASE_CHECKLIST.md`.

**CURRENT PHASE 7: QUALIFIED ACCEPTED / MERGE-ELIGIBLE / USER MERGE AUTHORIZATION PENDING.**
