# Phase 7 — Final Manual Evidence Reconciliation (Project-only)

**EVIDENCE AUDIT / NO NEW USER SIGN-OFF / NO MERGE / NO RETEST**

## Source control and authority

- Audited branch: `feature/phase7-win-auxiliary-ux`.
- Exact inspected source/docs HEAD: `030f986c473cb57df3ac25e0ebfda911b188d928` (accepted P7-WIN docs HEAD); this audit is a subsequent **docs-only** commit, not a new production baseline.
- Source checklist: `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md`, 17 sections A–Q. Current official validated upstream baseline stays 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.
- Evidence: user-accepted frozen `69` (Slice A), `71` (B1), `72` (C), `74` (B1+C), `75` (B2), `76` (D), `78` (P7-WIN); earlier implementation/test evidence `68`; Phase 6 integrated acceptance `31`; image upstream mapping `14` and official baseline image-generation runtime skill.
- Scope: **reconciliation only**. No new test was run, no package built, no Windows input simulated, no real AI/NovelAI request, no production or test source modified. `desktop`, `master`, prior frozen branches and parked sync stay untouched.

## Exact checklist accounting

| Status | Number | Interpretation |
| --- | ---: | --- |
| **SIGNED** | 3 | Already checked [x] by explicit user sign-off (WIN-01/02/03 in `66` section Q) |
| **USER-EVIDENCED** | 12 | Original `66` row has an explicitly matching **user** Windows acceptance in a later frozen slice; **do not ask to repeat**; row itself remains unsigned pending Phase-7-wide reconciliation |
| **PARTIAL** | 9 | Later user acceptance proves only a subset of the old row; request **only the missing delta**, not the already-accepted behavior |
| **TECH-EVIDENCE** | 37 | Related source/automated validation is documented, but not an exact item-level later user sign-off or necessarily an exact-final-SHA all-green test. Audit technical evidence by change impact; not automatic manual PASS |
| **OPEN** | 54 | No matching item-level later **user** acceptance in authoritative frozen records; may still be fully implemented and historically tested. Group into realistic end-to-end sessions, not one Codex task per checkbox |
| **TOTAL** | 115 | Exactly matches the `66` checklist; 112 checkboxes remain visually unsigned, **not** 112 proven product defects |

`69` accepts **default-model visibility**, which is not equivalent to `66`'s V5 usage battery (D). B1's image More/right-click is not equivalent to Assistant generation Requirements (M); M is directly covered by **B2-01** in `75`. `74` INT-05 directly covers Character → Start Chat, but not ordinary chat Send/Edit or a new restart-persistence trial. `76` accepts actual Viewer wheel/pan/reset, but Reset does not automatically prove an extra Fit control. `78` accepts WIN window UX, but not History's search/date behavior. Avoid these common false equivalences.

## Recommended evidence owners and minimized future work

**W1 — Studio / AI Design / roles / positions / V5 / Tags (A–E).** One authentic UI walkthrough with existing local images and previously configured data; fake/local Designer for conversations and errors. Prior shared/request policy and parser evidence remain technical proof for behavior not demonstrable without paid calls. Windows Chinese IME remains an actual-user-only check. Do **not** generate a new paid NovelAI image or call a real text model solely for an acceptance checkbox.

**W2 — Studio result / Viewer / History (F–H).** Reuse C, D and WIN accepted interactions. Do not recheck splitter, preview focus, zoom/pan, maximize, History-to-Viewer. Only close the gaps: newest-result semantics if a suitable local fixture exists, explicit filmstrip collapse, exact narrow/top placement, Fit/title only where distinct, History newest/fold/search/date/Shift-range badge. No additional image generation.

**W3 — Import / metadata / Enhance/Upscale UI / privacy (I–K).** Reuse local PNG/APNG samples. Test user-visible import choices, source preservation and actual UI (including comparison slider/Stop). Use mock/fake/local results for costly Enhance/Upscale; do not demand real network work. Shared metadata merge and privacy/animation invariants are protocol/unit-test authorities, not things to guess from pixels.

**W4 — Chat attachments / Assistant image actions / diagnostics (L–N).** Reuse B2's M-01–03 3/3 accepted compact actions and B1 image More; avoid duplicated image-user checks. Target pending-attachment picker/reorder/paste/drop/hover remove, APNG handling using known local files, and Advanced/Diagnostics placement. Offline fake Send where needed; no live AI.

**W5 — Session Settings and cross-feature regression (O–P).** Phase 6 and B1+C acceptance already cover core navigation and Character → Start Chat. Concentrate on still-unproven tab dirty/save/cancel/background semantics, a bounded existing-data restart-persistence check and ordinary chat send/edit only if a local/fake test route is available. Do not reinterpret Phase 6 PASS as proof of every new Phase-7-specific interaction.

The five walkthroughs are **proposed acceptance grouping**, not five automatic blanket PASSes; test only unconfirmed deltas and record any `NOT TESTED` separately. Project can use already validated local fixture/test reports when a claim concerns purely deterministic request/serialization semantics. GUI evidence belongs to the user; protocol/data evidence belongs to deterministic tests and source review.

## Per-section count and exact ID crosswalk

| Section | Total | Signed | User-evidenced | Partial | Technical | Open | Route |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| A AI Design | 12 | 0 | 0 | 0 | 3 | 9 | W1 |
| B 角色 Prompt 卡 | 11 | 0 | 0 | 0 | 3 | 8 | W1 |
| C 角色位置 | 6 | 0 | 0 | 0 | 4 | 2 | W1 |
| D V5 Usage | 5 | 0 | 0 | 0 | 2 | 3 | W1 |
| E Tag 库 | 9 | 0 | 0 | 0 | 5 | 4 | W1 |
| F Studio result pane | 8 | 0 | 2 | 3 | 2 | 1 | W2 |
| G Viewer | 6 | 0 | 4 | 2 | 0 | 0 | W2 |
| H History | 8 | 0 | 2 | 1 | 3 | 2 | W2 |
| I 图片导入 / metadata | 7 | 0 | 0 | 0 | 3 | 4 | W3 |
| J Enhance / Upscale | 6 | 0 | 0 | 0 | 1 | 5 | W3 |
| K Privacy | 4 | 0 | 0 | 0 | 2 | 2 | W3 |
| L Chat attachments | 11 | 0 | 0 | 1 | 5 | 5 | W4 |
| M Message image actions | 3 | 0 | 3 | 0 | 0 | 0 | W4 |
| N Advanced / Diagnostics | 4 | 0 | 0 | 0 | 0 | 4 | W4 |
| O Session Settings | 5 | 0 | 0 | 0 | 2 | 3 | W5 |
| P 回归 | 7 | 0 | 1 | 2 | 2 | 2 | W5 |
| Q 图片辅助窗口 UX（P7-WIN-01/02/03，用户 3/3 PASS；其余 Phase 7 待验收） | 3 | 3 | 0 | 0 | 0 | 0 | Accepted |
| **TOTAL** | **115** | **3** | **12** | **9** | **37** | **54** | |

The ID format, e.g. `G-04`, is the ordinal of the exact checkbox under section `G` in `66` at this audit SHA; this is a **traceability ID, not a schema or upstream feature ID**. Original leading code-line continuations remain in `66`. Each row below preserves the original checkbox item text; classifications are limited to the evidence available at the audited frozen commits.

## Complete 115-item reconciliation

### A. AI Design

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| A-01 | 打开后明显是聊天界面，不是表单。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-02 | 顶部直接看见实际设计模型名、Provider、认证状态。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-03 | 没有凭据时发送按钮不可用，并能直接去设置。 | TECH-EVIDENCE | 68 (fake Design, preflight and conversation proof) |
| A-04 | 有有效模型时能正常进入生成流程，不再先发请求再报认证失败。 | TECH-EVIDENCE | 68 (fake Design, preflight and conversation proof) |
| A-05 | 用户消息和 AI 回复按对话顺序出现。 | TECH-EVIDENCE | 68 (fake Design, preflight and conversation proof) |
| A-06 | AI 回复按基础 Prompt / 角色 Prompt 模块显示。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-07 | 每个 Prompt 模块有 Copy。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-08 | Apply to Studio 明显。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-09 | Edit & branch 属于用户消息动作。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-10 | Regenerate 属于 AI reply 动作。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-11 | Tag/Codex research 过程可展开看，默认不淹没回复。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| A-12 | 中文 IME/caret 仍正常。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |

### B. 角色 Prompt 卡

The initial `header` and `停用` lines in checklist `66` have continuation code lines; keep the original checklist as the exact visual contract.

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| B-01 | header： | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| B-02 | 停用： | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| B-03 | 停用卡整体灰。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| B-04 | 停用后卡不消失。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| B-05 | 停用后仍可移动/删除/启用/展开折叠。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| B-06 | 停用角色实际不计入生成角色数/token/request。 | TECH-EVIDENCE | 68/14 (role state and request policy) |
| B-07 | 重新启用内容完整恢复。 | TECH-EVIDENCE | 68/14 (role state and request policy) |
| B-08 | 折叠与启用完全独立。 | TECH-EVIDENCE | 68/14 (role state and request policy) |
| B-09 | 普通折叠最多约三行，不影响生图。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| B-10 | Base Negative 在 Extra 后面。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| B-11 | 各同级 Prompt 区块都有独立卡片边界。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |

### C. 角色位置

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| C-01 | 可以选择 AI 自动 / 自定义位置。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| C-02 | 可以点/拖画布。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| C-03 | V4.5 吸附 5×5。 | TECH-EVIDENCE | 60/14/68 (upstream position parity and request tests) |
| C-04 | V5 可自由位置。 | TECH-EVIDENCE | 60/14/68 (upstream position parity and request tests) |
| C-05 | disabled 角色不进入位置列表/最终 request。 | TECH-EVIDENCE | 60/14/68 (upstream position parity and request tests) |
| C-06 | 排序后角色位置仍跟角色 ID。 | TECH-EVIDENCE | 60/14/68 (upstream position parity and request tests) |

### D. V5 Usage

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| D-01 | 顶部有 V5 usage bar。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| D-02 | 显示百分比。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| D-03 | 显示约可生成张数。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| D-04 | exhausted/unknown 有清楚状态。 | TECH-EVIDENCE | 68 (V5 unknown/usage tests) |
| D-05 | 不伪造 “14.3%/day” 等当前 API 没有 authority 的信息。 | TECH-EVIDENCE | 68 (V5 unknown/usage tests) |

### E. Tag 库

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| E-01 | 输入半个 Tag 自动出现候选。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| E-02 | 显示英文/中文/category/count。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| E-03 | 上下键 + Enter/Tab 可用。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| E-04 | 只移动 caret 到已有完整 Tag 中间也会查询。 | TECH-EVIDENCE | 68/official image skill (Tag parser/lookup) |
| E-05 | exact Tag 优先。 | TECH-EVIDENCE | 68/official image skill (Tag parser/lookup) |
| E-06 | 不会因为 inspection 自动改文字。 | TECH-EVIDENCE | 68/official image skill (Tag parser/lookup) |
| E-07 | 明确选择候选才替换整个 Tag。 | TECH-EVIDENCE | 68/official image skill (Tag parser/lookup) |
| E-08 | `1.5::tag::`、source#/target# 正常。 | TECH-EVIDENCE | 68/official image skill (Tag parser/lookup) |
| E-09 | 全屏编辑同样可用。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |

### F. Studio result pane

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| F-01 | 默认选最新。 | TECH-EVIDENCE | 68 (newest selection tests) |
| F-02 | 新生成结果自动选最新。 | TECH-EVIDENCE | 68 (newest selection tests) |
| F-03 | 大图随右 pane 尺寸变化。 | PARTIAL | 72/74 cover full-height preview/splitter; image scaling at each width not separately signed |
| F-04 | pane 可调宽。 | USER-EVIDENCED | 72,74（Studio draggable splitter） |
| F-05 | 可折叠成右侧 filmstrip。 | PARTIAL | 72/74 cover 116dp thumbnail rail; explicit collapse action not separately signed |
| F-06 | 可进入 preview-focus。 | USER-EVIDENCED | 72,74（preview-focus） |
| F-07 | 窄窗口变成顶部 compact image panel，而不是把预览挤到很下面。 | PARTIAL | 72/74 cover narrow Studio layout; exact top compact-panel placement not expressly signed |
| F-08 | selected-image actions 不重复两套。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |

### G. Viewer

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| G-01 | 鼠标滚轮放大/缩小。 | USER-EVIDENCED | 76 D-06（Viewer wheel zoom） |
| G-02 | 放大后拖动看局部。 | USER-EVIDENCED | 76 D-06（Viewer drag/pan） |
| G-03 | Reset/Fit 正常。 | PARTIAL | 76 D-06 covers Reset; explicit Fit behavior not separately itemized |
| G-04 | 可 resize。 | USER-EVIDENCED | 78 WIN-03（resize 下仍可使用） |
| G-05 | 可 maximize/restore。 | USER-EVIDENCED | 78 WIN-01（maximize/restore） |
| G-06 | 标题不重复。 | PARTIAL | 77/78 source owns one OS window title/icon; no separate prior user 'no duplicate title' check |

### H. History

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| H-01 | 主历史是自适应网格。 | TECH-EVIDENCE | 68/78 (grid/Shift selection deterministic history) |
| H-02 | newest first。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| H-03 | 折叠相册仍正常。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| H-04 | 搜索/日期正常。 | PARTIAL | 76 D-04 covers filtered History Viewer sequence; exact search/date control behavior not separately signed |
| H-05 | 范围选择可用。 | TECH-EVIDENCE | 68/78 (grid/Shift selection deterministic history) |
| H-06 | 选中 badge/范围状态清楚。 | TECH-EVIDENCE | 68/78 (grid/Shift selection deterministic history) |
| H-07 | viewer 正常。 | USER-EVIDENCED | 76 D-04（History Viewer） |
| H-08 | History 窗口可 resize/maximize。 | USER-EVIDENCED | 78 WIN-01（History maximize/restore） |

### I. 图片导入 / metadata

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| I-01 | 主 Prompt 区没有孤立 PNG metadata import 按钮。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| I-02 | Copy structured positive Prompt 清楚。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| I-03 | Paste structured positive Prompt 清楚。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| I-04 | 导入图片后在工具内选择 metadata。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| I-05 | 角色 metadata 有 关 / 覆盖 / 新增。 | TECH-EVIDENCE | 14/68 (metadata OFF/REPLACE/APPEND and alpha-stealth) |
| I-06 | 新增不会破坏已有角色 ID/enabled/center。 | TECH-EVIDENCE | 14/68 (metadata OFF/REPLACE/APPEND and alpha-stealth) |
| I-07 | 普通 PNG metadata 和 alpha stealth metadata 都能读。 | TECH-EVIDENCE | 14/68 (metadata OFF/REPLACE/APPEND and alpha-stealth) |

### J. Enhance / Upscale

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| J-01 | Image Tools 有 Enhance/Upscale。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| J-02 | before/after 可拖分隔线比较。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| J-03 | 参数/费用说明清楚。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| J-04 | Stop 存在。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| J-05 | result 可以保存/分享/继续作为工具输入。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| J-06 | 不会因为打开工具就改 Studio Prompt/history。 | TECH-EVIDENCE | 14/68 (Enhance/Upscale and no unintended state writes) |

### K. Privacy

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| K-01 | Image Tools 有明确 privacy export。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| K-02 | 文案说明会清除 metadata + stealth carrier。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| K-03 | 原图保留。 | TECH-EVIDENCE | 14/68 (Privacy encoder and animation invariants) |
| K-04 | animation 不会被偷偷压平。 | TECH-EVIDENCE | 14/68 (Privacy encoder and animation invariants) |

### L. Chat attachments

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| L-01 | 图片按钮/全屏/Send/Stop 靠输入框右下。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| L-02 | picker 可一次选多张。 | TECH-EVIDENCE | 68 (picker, APNG/GIF, ownership tests) |
| L-03 | 多图超宽可横向滚动。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| L-04 | pending 图片可拖动排序。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| L-05 | Ctrl+V 图片可加入附件。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| L-06 | 拖文件到 composer 可加入附件。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| L-07 | 点击看大图；hover × 删除。 | PARTIAL | 71/76 cover image preview/viewer entry; hover × removal not separately signed |
| L-08 | APNG 正常上传，不重复错误。 | TECH-EVIDENCE | 68 (picker, APNG/GIF, ownership tests) |
| L-09 | 换图/删图后旧 APNG 错误消失。 | TECH-EVIDENCE | 68 (picker, APNG/GIF, ownership tests) |
| L-10 | ChatBar disguise APNG 会自动还原 owned copy。 | TECH-EVIDENCE | 68 (picker, APNG/GIF, ownership tests) |
| L-11 | 普通第三方 APNG 不会被错误当 disguise。 | TECH-EVIDENCE | 68 (picker, APNG/GIF, ownership tests) |

### M. Message image actions

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| M-01 | Assistant 的“生成图片”变成清楚的 compact image action。 | USER-EVIDENCED | 75 B2-01（compact Generate Image） |
| M-02 | “生图要求/设定”变成 compact settings action。 | USER-EVIDENCED | 75 B2-01（compact Requirements） |
| M-03 | 不再是两个巨大按钮。 | USER-EVIDENCED | 75 B2-01（compact action replaces oversized controls） |

### N. Advanced / Diagnostics

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| N-01 | 和工具主 tabs 同一行。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| N-02 | 在最右侧。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| N-03 | 不再独占一行。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| N-04 | Prompt Inspector 仍在里面。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |

### O. Session Settings

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| O-01 | tabs 可用。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| O-02 | dirty state跨 tab 正常。 | TECH-EVIDENCE | 31/68 (Phase6 settings/ownership) |
| O-03 | Save/Cancel 始终可见。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| O-04 | 图片 tab 内容正确。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| O-05 | background immediate-save 语义未破坏。 | TECH-EVIDENCE | 31/68 (Phase6 settings/ownership) |

### P. 回归

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| P-01 | 中文 Prompt annotation 仍通过。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| P-02 | Guidance 仍通过。 | OPEN | No exact later user sign-off in 69/71/72/74/75/76/78; may have implementation or historical test evidence |
| P-03 | CharacterCard Prompt import 仍通过。 | TECH-EVIDENCE | 31 (Phase6 foundation; regression is not final-P7 manual) |
| P-04 | Background library 仍通过。 | PARTIAL | 76 D-05 covers background library Viewer/unchanged preferences, not all library operations |
| P-05 | Character → Start Chat 仍通过。 | USER-EVIDENCED | 74 INT-05（MANAGE → newly created Character chat） |
| P-06 | 普通聊天发送/编辑仍通过。 | PARTIAL | 74 INT-02 covers CHAT↔Studio↔CHAT state/navigation; ordinary send/edit not independently covered |
| P-07 | 重启 persistence 仍通过。 | TECH-EVIDENCE | 31 (Phase6 foundation; regression is not final-P7 manual) |

### Q. 图片辅助窗口 UX（P7-WIN-01/02/03，用户 3/3 PASS；其余 Phase 7 待验收）

| ID | Original check (verbatim item line) | Evidence class | Anchor / residual gap |
| --- | --- | --- | --- |
| Q-01 | **P7-WIN-01 最大化/还原：**逐窗检查 Studio AI Design、History、Viewer、Image Tools 等大型辅助窗口；可正常最大化并利用新增空间，恢复后布局正常，关闭/Esc、焦点和生命周期不退化。 | SIGNED | 78 — user 3/3 Windows acceptance |
| Q-02 | **P7-WIN-02 CCB 图标：**适用图片辅助窗口在 Windows 标题栏和任务栏显示与主窗口一致的 CCB 图标，无缺失或默认 Java 图标。 | SIGNED | 78 — user 3/3 Windows acceptance |
| Q-03 | **P7-WIN-03 初始尺寸/内容可见性：**默认大小能看到主要信息及常用 Save/Cancel/Apply/Close；长内容可合理滚动，缩放后无裁切或不可到达的操作区，并在常见缩放和实际分辨率下检查。 | SIGNED | 78 — user 3/3 Windows acceptance |

## Phase-7-wide closeout guard

1. **Do not auto-check** `66`'s remaining boxes just because the slice was accepted or the underlying code exists. At this checkpoint only Q-01/02/03 remain checked; `USER-EVIDENCED` is a reusable, previously authorized user acceptance, not an independent newly issued Phase-7-wide sign-off.
2. For the `PARTIAL` rows, test or source-review **only** what earlier acceptance does not cover; no repeated C/D/B2/WIN window tours.
3. The `TECH-EVIDENCE` rows require item-appropriate existing test/source mapping before relying on them as non-GUI gates. Avoid claiming a final-SHA strict-full PASS from older passing suites.
4. The D Native HWND/Chrome and WIN synthetic `ScrollBy` RED evidence remains **historically RED**. User acceptance verifies actual GUI interactions but does not prove that the synthetic failures disappeared. New GUI automation-infrastructure work needs explicit separate risk justification; do not run repeat-until-green.
5. **No Phase 7 merge or full acceptance** until the minimum remaining meaningful user flows are covered or explicitly bounded as NOT TESTED and the final integration risk/technical test evidence is reviewed. No extra NovelAI generation or unapproved Prompt edit.
6. No upstream baseline promotion or parked sync absorption. Any Phase7-wide compatibility statement must say what commit and what subset it covers.
