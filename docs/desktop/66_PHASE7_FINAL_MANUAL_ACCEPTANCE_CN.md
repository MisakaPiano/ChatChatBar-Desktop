# 66 — Phase 7 Current-Upstream Image Closure 最终人工验收

**CURRENT 2026-10-12 docs-only evidence synchronization:** 依据 `86` 的原始 18 组结果和 `79` 的逐 ID 115 行 crosswalk，精确补勾此前未同步的 **47** 项；与早先已勾选的 **12** 项合计，当前字面 **59/115 `[x]`**，其余 **56** 项不勾选。只更新已有用户可见验收的证据表示，不增加新实机结果；历史原 Group 11 FAIL / Group 15 NOT TESTED 保留。之后的独立 APNG `复制文件`→Windows 资源管理器→动画还原 **3/3 PASS** 记录于 `89`，并不新增签署 K-01..04。完整最终风险审查见 `90`；下文包含历史操作说明，勿重新执行 18 组。
历史 image-closure 状态：当时的自动化验证 / 新隔离 package / launch smoke 已 PASS；原始 checkbox 等待用户验收。**后续 2026-10-11 用户已独立签署本页 Q 节 P7-WIN-01/02/03 三项（3/3 PASS），见 `78_PHASE7_WIN_ACCEPTANCE.md`；2026-10-12 用户另签署 H-02/H-03/H-04 三项，见 `80_PHASE7_W2_HISTORY_ACCEPTANCE.md`；同日用户签署 F-03/F-05/F-07/F-08 四项，见 `81_PHASE7_W2_STUDIO_ACCEPTANCE.md`；W3 I1 另外签署 I-01/I-04 两项，I2/I3 未测，见 `82_PHASE7_W3_IMAGE_IMPORT_ACCEPTANCE.md`；本页其它未勾选项仍待验收，不代签。**
聊天图片展示与菜单 Slice B1 已单独由用户验收并冻结；责任归属和证据见 `71_PHASE7_SLICE_B1_ACCEPTANCE.md`。Project 已在 `79_PHASE7_FINAL_MANUAL_EVIDENCE_RECONCILIATION.md` 逐项对照本页 115 项与后来已验收切片，避免重复测试；原有证据对照**不会自动签署 PASS**；后续 W2 History 3/3 与 Studio 3/3 均由用户明确验收，分别在 H 节签署三项、F 节签署四项；W3 I1 用户 PASS 另在 I 节签署两项，I2/I3 未测。详情见 `80_PHASE7_W2_HISTORY_ACCEPTANCE.md`、`81_PHASE7_W2_STUDIO_ACCEPTANCE.md`、`82_PHASE7_W3_IMAGE_IMPORT_ACCEPTANCE.md`。其余未确认 checkbox 仍保持未签署状态。
生产源码 `56ac3063f4817ce5b0eb4dfea3f5a6d98bfd14a1`，哈希与完整证据见 `68_PHASE7_CURRENT_UPSTREAM_IMAGE_REVIEW.md`。

本轮验收 EXE：
`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-current-upstream-distribution\compose\binaries\main\app\ChatChatBarDesktop\ChatChatBarDesktop.exe`

建议流程：先检查 Studio 宽/窄布局、角色启停与位置、图像导入/本地隐私导出/History/附件；再检查 AI Design 无凭据 preflight、结构化 fake 对话与中文 IME；最后检查聊天、背景库、角色 Start Chat 和 Session Settings 附近回归。可重用已有成功图片或本地 fixture，真实 NovelAI generation/Enhance/Upscale 仍冻结，不需要为勾选 UI 项消耗额度。

本地 AI Design 手工交互可使用既有 test-classpath-only `:desktopApp:runPhase7FinalProductFixture`（JDK17 和 `68` 的 bounded 参数）；它使用独立临时数据、in-memory secrets 和 fake transport，不是生产 EXE 的模式。实际 Windows IME 仍需人工确认，自动 Compose 测试不替代这一签署。


这份给用户。

原则：
- 不需要真实烧 NovelAI 生图额度；
- Enhance/Upscale 不要求真实调用；
- AI Design 如果使用真实模型测试会消耗文本模型额度，但不是 NovelAI 生图；优先先做本地/fake验收包能覆盖的部分。

## A. AI Design

- [x] 打开后明显是聊天界面，不是表单。
- [x] 顶部直接看见实际设计模型名、Provider、认证状态。
- [ ] 没有凭据时发送按钮不可用，并能直接去设置。
- [ ] 有有效模型时能正常进入生成流程，不再先发请求再报认证失败。
- [x] 用户消息和 AI 回复按对话顺序出现。
- [x] AI 回复按基础 Prompt / 角色 Prompt 模块显示。
- [x] 每个 Prompt 模块有 Copy。
- [x] Apply to Studio 明显。
- [x] Edit & branch 属于用户消息动作。
- [x] Regenerate 属于 AI reply 动作。
- [ ] Tag/Codex research 过程可展开看，默认不淹没回复。
- [x] 中文 IME/caret 仍正常。

## B. 角色 Prompt 卡

- [x] header：
  `角色 N  已启用 ... [↑][↓][✓][删除][⌃/⌄]`
- [x] 停用：
  `角色 N  已停用 ... [↑][↓][×][删除][⌃/⌄]`
- [x] 停用卡整体灰。
- [x] 停用后卡不消失。
- [ ] 停用后仍可移动/删除/启用/展开折叠。
- [ ] 停用角色实际不计入生成角色数/token/request。
- [x] 重新启用内容完整恢复。
- [x] 折叠与启用完全独立。
- [ ] 普通折叠最多约三行，不影响生图。
- [x] Base Negative 在 Extra 后面。
- [x] 各同级 Prompt 区块都有独立卡片边界。

## C. 角色位置

- [x] 可以选择 AI 自动 / 自定义位置。
- [x] 可以点/拖画布。
- [x] V4.5 吸附 5×5。
- [x] V5 可自由位置。
- [ ] disabled 角色不进入位置列表/最终 request。
- [ ] 排序后角色位置仍跟角色 ID。

## D. V5 Usage

- [x] 顶部有 V5 usage bar。
- [ ] 显示百分比。
- [ ] 显示约可生成张数。
- [ ] exhausted/unknown 有清楚状态。
- [ ] 不伪造 “14.3%/day” 等当前 API 没有 authority 的信息。

## E. Tag 库

Completion：
- [x] 输入半个 Tag 自动出现候选。
- [ ] 显示英文/中文/category/count。
- [x] 上下键 + Enter/Tab 可用。

Inspection：
- [x] 只移动 caret 到已有完整 Tag 中间也会查询。
- [ ] exact Tag 优先。
- [x] 不会因为 inspection 自动改文字。
- [x] 明确选择候选才替换整个 Tag。
- [ ] `1.5::tag::`、source#/target# 正常。
- [x] 全屏编辑同样可用。

## F. Studio result pane

2026-10-12 W2 Studio 用户 Windows 验收 S1–S3 **3/3 PASS**：S1 filmstrip 折叠/展开 → F-05；S2 窄窗口预览随可用宽度变化且顶部 compact 布局 → F-03/F-07；S3 selected-image actions 无重复 → F-08。只签署这四项，证据见 `81_PHASE7_W2_STUDIO_ACCEPTANCE.md`。其它项目不因本轮自动签署。

- [ ] 默认选最新。
- [ ] 新生成结果自动选最新。
- [x] 大图随右 pane 尺寸变化。
- [ ] pane 可调宽。
- [x] 可折叠成右侧 filmstrip。
- [ ] 可进入 preview-focus。
- [x] 窄窗口变成顶部 compact image panel，而不是把预览挤到很下面。
- [x] selected-image actions 不重复两套。

## G. Viewer

在聊天、Studio、History、背景库分别抽查：

- [ ] 鼠标滚轮放大/缩小。
- [ ] 放大后拖动看局部。
- [x] Reset/Fit 正常。
- [ ] 可 resize。
- [ ] 可 maximize/restore。
- [x] 标题不重复。

## H. History

2026-10-12 W2 用户实际 Windows 验收：H1 历史顺序、H2 相册折叠、H3 搜索与日期筛选均 PASS，分别签署本节 `newest first`、`折叠相册`、`搜索/日期`。独立证据及冻结源码见 `80_PHASE7_W2_HISTORY_ACCEPTANCE.md`；本节其它条目尚未因此签署。

- [x] 主历史是自适应网格。
- [x] newest first。
- [x] 折叠相册仍正常。
- [x] 搜索/日期正常。
- [x] 范围选择可用。
- [x] 选中 badge/范围状态清楚。
- [ ] viewer 正常。
- [ ] History 窗口可 resize/maximize。

## I. 图片导入 / metadata

2026-10-12 W3 图片导入用户实机验收：**I1 PASS，I2/I3 未测**。I1 直接对应本节「主 Prompt 区无孤立 metadata 导入」与「导入图片后在工具内选择 metadata」两项，签署 I-01/I-04；I2 结构化正向 Prompt 复制/粘贴和 I3 角色 metadata 模式均未得到用户实机确认，保留未勾选。源码确有中文「复制正向 Prompt」「粘贴覆盖」入口及共享编码/解析，不等于用户已经测试，参见 `82_PHASE7_W3_IMAGE_IMPORT_ACCEPTANCE.md`。后续 metadata 弹窗 M1–M3 布局和取消操作 **3/3 PASS**，见 `83_PHASE7_W3_METADATA_DIALOG_UX_ACCEPTANCE.md`；不等于 I3 实际导入已测，以下 checkbox 不增加签署。

- [x] 主 Prompt 区没有孤立 PNG metadata import 按钮。
- [ ] Copy structured positive Prompt 清楚。
- [ ] Paste structured positive Prompt 清楚。
- [x] 导入图片后在工具内选择 metadata。
- [x] 角色 metadata 有 关 / 覆盖 / 新增。
- [ ] 新增不会破坏已有角色 ID/enabled/center。
- [ ] 普通 PNG metadata 和 alpha stealth metadata 都能读。

## J. Enhance / Upscale

仅 UI/fake 路径验收，不需要真钱调用：

- [x] Image Tools 有 Enhance/Upscale。
- [ ] before/after 可拖分隔线比较。
- [x] 参数/费用说明清楚。
- [ ] Stop 存在。
- [ ] result 可以保存/分享/继续作为工具输入。
- [x] 不会因为打开工具就改 Studio Prompt/history。

## K. Privacy

- [ ] Image Tools 有明确 privacy export。
- [ ] 文案说明会清除 metadata + stealth carrier。
- [ ] 原图保留。
- [ ] animation 不会被偷偷压平。

## L. Chat attachments

- [ ] 图片按钮/全屏/Send/Stop 靠输入框右下。
- [x] picker 可一次选多张。
- [x] 多图超宽可横向滚动。
- [x] pending 图片可拖动排序。
- [x] Ctrl+V 图片可加入附件。
- [x] 拖文件到 composer 可加入附件。
- [x] 点击看大图；hover × 删除。
- [ ] APNG 正常上传，不重复错误。
- [ ] 换图/删图后旧 APNG 错误消失。
- [ ] ChatBar disguise APNG 会自动还原 owned copy。
- [ ] 普通第三方 APNG 不会被错误当 disguise。

## M. Message image actions

- [ ] Assistant 的“生成图片”变成清楚的 compact image action。
- [ ] “生图要求/设定”变成 compact settings action。
- [ ] 不再是两个巨大按钮。

## N. Advanced / Diagnostics

- [ ] 和工具主 tabs 同一行。
- [ ] 在最右侧。
- [ ] 不再独占一行。
- [ ] Prompt Inspector 仍在里面。

## O. Session Settings

- [x] tabs 可用。
- [x] dirty state跨 tab 正常。
- [x] Save/Cancel 始终可见。
- [x] 图片 tab 内容正确。
- [ ] background immediate-save 语义未破坏。

## P. 回归

- [ ] 中文 Prompt annotation 仍通过。
- [ ] Guidance 仍通过。
- [ ] CharacterCard Prompt import 仍通过。
- [ ] Background library 仍通过。
- [ ] Character → Start Chat 仍通过。
- [ ] 普通聊天发送/编辑仍通过。
- [ ] 重启 persistence 仍通过。

## Q. 图片辅助窗口 UX（P7-WIN-01/02/03，用户 3/3 PASS；其余 Phase 7 待验收）

用户于 2026-10-11 确认本节 WIN-01/02/03 三项全部通过，验收源码和精确证据见 `78_PHASE7_WIN_ACCEPTANCE.md`。用户未提交逐窗分辨率和缩放数据，不补造。上方 Viewer、History 的既有未勾选总清单项与其它未签署项保持原状态；小型确认弹窗不要求最大化。原问题范围见 `70_PHASE7_OUTSTANDING_UX_ISSUES.md`。

- [x] **P7-WIN-01 最大化/还原：**逐窗检查 Studio AI Design、History、Viewer、Image Tools 等大型辅助窗口；可正常最大化并利用新增空间，恢复后布局正常，关闭/Esc、焦点和生命周期不退化。
- [x] **P7-WIN-02 CCB 图标：**适用图片辅助窗口在 Windows 标题栏和任务栏显示与主窗口一致的 CCB 图标，无缺失或默认 Java 图标。
- [x] **P7-WIN-03 初始尺寸/内容可见性：**默认大小能看到主要信息及常用 Save/Cancel/Apply/Close；长内容可合理滚动，缩放后无裁切或不可到达的操作区，并在常见缩放和实际分辨率下检查。

本节 Q 的 3/3 PASS 只冻结 P7-WIN；**Phase 7 整体验收及 merge 仍要求其它未确认项由用户逐项签署并完成独立集成审查，不自动放行。**
