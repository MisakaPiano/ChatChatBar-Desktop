# Phase 7 — 一次启动完成剩余 Windows 人工验收（预检与批次测试单）

**PROJECT CODE REVIEW PASS / CODEX EXACT-SHA PACKAGE READY 210/210 / USER SINGLE-SESSION WINDOWS ACCEPTANCE READY / NOT MERGED**

## 0. 源码依据与状态

- 本测试单最初由 Project 在 `3a14d92a` 源码状态准备，后续 N 布局修正已提交 `feature/phase7-win-auxiliary-ux @ 698f0cd55e0ae370650fac012cdb50f0dd7e033f`；本次状态调整为后续 docs-only commit，不改变生产源码。
- 上一个经过用户实机验收的生产 SHA：`f43c08564a89607b9fdb53658f0a2eb044384f22`（metadata 弹窗 M1–M3）。本批次**待验收的生产 SHA**：`698f0cd55e0ae370650fac012cdb50f0dd7e033f`；Project 已完成 GitHub 代码审查，尚无该 SHA 的正式发行包/用户实机验收。
- 旧 W3 包仍属于 `f43c0856` 的历史验收证据；**本批次新 EXE**：`H:\ChatChatBar-Desktop\app\desktopApp\build\phase7-final-one-session-distribution\ChatChatBarDesktop.exe`，对应生产 SHA `698f0cd5`。Codex 报告 Temurin JDK17 `--rerun-tasks` 和 210/210 全载荷 SHA-256 PASS，0 缺失/额外/不匹配。Project 核实 GitHub 生产/文档树，没有直接读取用户 H: 本地封存；独立记录见 `85_PHASE7_FINAL_ONE_SESSION_PACKAGE_HANDOFF.md`。
- `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` 当前 115 项：12 已用户签署，12 其他切片有直接用户证据，5 部分重叠，39 有相关技术证据，47 无逐项用户签署，**不是 47 个代码缺陷**。本单不修改原状态、不重复已冻结的 A/B1/C/B2/D/WIN、W2 History、W2 Studio 与 M1–M3。
- 正式 validated upstream baseline：1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；没有同步 1.4.4、没有更新 schema/Prompt。

## 1. 前置 CODE REVIEW：N Advanced/Diagnostics 位置

**PROJECT GITHUB CODE REVIEW PASS / CODE SCOPE CLOSED / USER VISUAL ACCEPTANCE PENDING.**

- 产品契约 `62_PHASE7_IMAGE_PRODUCT_CLOSURE.md` §18 要求 `NovelAI Studio` 与 `高级 / 诊断` 同行，后者在工具导航右侧，且 Prompt Inspector 能正常进入，原 `66` N-01..04 仍未用户签署。
- 原 `f43c0856` 源码未将 Diagnostics 放到该行的右侧。Codex 已在 `698f0cd55e0ae370650fac012cdb50f0dd7e033f` 将 `DesktopStudioWorkspace.kt` 的该行改为 `Row(fillMaxWidth().horizontalScroll(...), Arrangement.SpaceBetween)`；**实际右对齐参考范围是 Studio 编辑栏右侧，而非整个主窗口标题栏最右端**。主窗口原生标题栏/命中映射、Prompt Inspector 绑定和 Studio draft owner 未改。
- Project GitHub exact diff 独立审查：唯一修改的生产文件 `DesktopStudioWorkspace.kt`（+5/-1），另有 `DesktopStudioWorkspaceTest.kt`（+29/-0）；相对 `ac2ebc8f` 为一提交、仅两文件。**CODE REVIEW PASS**。
- 新测试涵盖 `320/360/720/900/1240/1600` 宽度下同一行纵坐标、Studio 左侧与 Diagnostics 编辑栏右侧、唯一 Diagnostics、来回切换后的 Inspector/editor 内容。Codex 报告 JDK17 compile/diff-check/scope 与该定向测试 1/1 PASS；**Project 没有在 Windows 上自行运行 GUI，也不声称当前 SHA 全量回归全绿**。
- 原 `66` N-01/N-02/N-03/N-04 保留未签。真实 Windows 上若仍觉得 Diagnostics 没有处于预期的“最右侧”，应准确记录它相对于**编辑栏**与**整个工具窗体**的位置，列为本批次 FAIL 供 Project 决策，不自行把规格换成整窗右端，也不要求用户为 N 单独开一次应用。
- **精确打包已完成（Codex 报告 210/210 PASS）**，现在可以按下方完整 18 组清单集中验收；不重跑 Native Robot/HWND 或旧 synthetic GUI RED。

## 2. 一次启动的准备及安全规则

1. **现在可以进行整批验收。** 先准备下列安全资料与统一回报格式，再运行新 EXE（路径见 §0）；不要混用旧 W3 包。缺少测试夹具直接记录 NT，不为填表进行付费生成。
2. 测试资料：已有的 Studio History、普通非敏感 PNG/JPG **测试副本**（至少 2–3 张）；若已有带角色 metadata 的 NovelAI PNG、规范 CCB 伪装 APNG、普通第三方 APNG，可选带上，不需要新生成图片。
3. **可丢弃的 Studio 草稿与 Chat 会话**才可以测试角色开关/位置、角色 metadata 实际导入、Session Settings 草稿保存或图片导入；没有可牺牲对象就 `NOT TESTED`。不要把正式角色、历史或生产数据当作测试夹具，不使用真实用户数据目录的副本作为可删除对象除非由用户明确创建、保护和确认。
4. 全程不点击 **生成、新种子重绘、反推 Prompt、重新设计/重试、Enhance/Upscale 执行、聊天 Send** 等会发起真实模型/付费调用的操作。不移除/删除任何历史图、角色卡或会话，不操作 SecretStore，不授予额外请求授权。
5. 状态只允许 **PASS / FAIL / NOT TESTED**。FAIL 附上实际窗口/操作及可选截图；NOT TESTED 附原因（无 fixture、需要真实 API、数据不能安全改变）；**未测不算通过也不算失败**。
6. 如果需要保持正在测试的状态，可中途休息，但**不要求在任何一组之后关闭 CCB**。整个批次只有最后一项可选的预先计划重启；全部记录完成后才可关闭应用。

## 3. 整批测试顺序（非 N 入口基于 `f43c0856` 源码审计；N 位置已按 `698f0cd5` 重新审查）

| ID | 区域 | 条件 | 实际入口 | 只做的检查及 PASS 标准 | 对应 `66` | 跳过/风险边界 |
| --- | --- | --- | --- | --- | --- | --- |
| 01 | Studio / AI Design | 只读 | TOOLS → `AI 设计` | 检查独立聊天布局、顶部真实设计模型/Provider/认证状态；在「画面需求」输入中文（不发送），观察 IME/caret；无认证时应该提示「打开模型设置」且发送不可用 | A-01/02/03/12 | 无已有模型/凭据可记录未测；严禁点击发送 |
| 02 | 已有 AI Design 对话 | 已有对话 | AI 设计 → `设计历史` → 已有对话 | 如果原有设计对话存在，阅读 USER/AI 按时间排列、基础/角色 Prompt 模块与「复制 …」；只检查「编辑并分支」「重新设计/重试」「应用到 Studio」入口和折叠进度是否存在，不点击会产生请求或覆盖草稿的动作 | A-05/06/07/08/09/10/11 | 无历史记录全部未测；不为测试生成新设计 |
| 03 | 角色 Prompt 卡 | 可丢弃 Studio 草稿 | TOOLS → Studio Prompt → `添加角色`、角色卡 | 如有安全草稿，确认启用/停用不消失、停用变灰、排序↑↓、独立折叠、同级区块边界与 Base Negative 位于 Extra 后；把改动恢复到原值，不在真实工作草稿上删除角色 | B-01..05/07..11 | 启用角色是否进入最终 request（B-06）只以共享运行测试审查，不靠 UI 视觉签署 |
| 04 | 角色位置 | 可丢弃草稿且至少一个角色 | Studio → `角色位置 · 自动/自定义` | 检查自动/自定义、画布点按及拖动位置；有合适草稿时比较 V4.5 5×5 网格与 V5 连续位置，恢复原模型/位置 | C-01..04 | C-05/06 disabled/排序后的 request identity 交给现有 policy 测试；不为此生成图片 |
| 05 | V5 电量与费用 | 只读或可恢复模型选择 | Studio → `生成设置` 的「模型」选择 V5；查看 Studio 顶部 | 确认「V5 Opus」「额度 xx%/额度未知」、电量条、约可生成张数或 —，显示未知时如实为未知；查看生成按钮显示的实际消耗提示，但不点击 | D-01..05 | 没有 V5 账户数据时只确认未知状态；不要点「刷新」造成额外账户请求 |
| 06 | Tag 补全 / caret | 可丢弃草稿 | Studio → `基础 Prompt` 编辑框 → 「展开」 | 输入半个已有 Tag、上下箭头/Enter/Tab 测试候选；把光标移入已有完整 Tag，确认 Inspection 不自动改文本，手动选择才替换；有条件再看加权/`source#`/`target#` 与全屏编辑，撤销测试改动 | E-01..09; P-01 | 不必同时测试每种复杂语法；没有本地词库或可恢复草稿时记未测 |
| 07 | History Shift 范围 | 已有至少三张历史图 | TOOLS → `历史` | 仅观察自适应网格，选择缩略图再 Shift+点击另一张，检查「范围起点」、已选 badge 与取消选择；不要点击「删除选中」 | H-01/05/06 | H-02/03/04 已在 80 验收，H-07/08 在 D/WIN 验收，全部不重测 |
| 08 | Viewer 旧清单余差 | 可选已有图片 | 历史或 Studio 既有图片 → Viewer | 只在顺路时辨认「重置」/双击 Fit 的实际效果以及窗口标题无重复；已通过的缩放、平移、箭头、resize/maximize 不重测 | G-03/06 | 找不到独立 Fit 按钮先记录实际 UI，不把 UI 命名差异算作模型/数据缺陷 |
| 09 | 角色 metadata 导入 | 含 NovelAI metadata 的测试图片；可丢弃 Studio 草稿 | Studio → `导入图片` → `当前图片` → `元数据` | 查看「角色 Prompt」模式菜单的关/覆盖/新增；如能安全使用测试草稿且准备了含角色 metadata 的 PNG，再实际导入并对比角色 ID、enabled、center 是否保持。否则取消 | I-05/06/07 | I1 元数据入口、M1–M3 弹窗布局已通过，不重复；没有独立测试草稿不点「导入」 |
| 10 | Enhance / Upscale 面板 | 普通本地静态图（可为副本） | Studio → `导入图片` → `当前图片` → `Enhance / Upscale` | 观察两种 tab、参数、适用条件与「预计…Anlas」或真实不可用提示；切换 tab 不应修改 Studio Prompt/history | J-01/03/06 | 不点执行 Enhance/Upscale。J-02/04/05 只有处理结果或安全 fake 资料才能验收，缺资料记未测 |
| 11 | 隐私本地工具 | 静态测试图副本 | 当前图片 → `打码 / 旋转 / 隐私 / 保存` | 检查「隐私导出 · 清除元数据与像素隐写」文字以及另存结果；仅在准备测试文件时进行本地隐私副本/另存，核对原始文件仍在 | K-01/02/03 | K-04 动画不被压平以代码/历史测试证据为主；不要把动画当静态图强制处理 |
| 12 | 图像引导入口 | 只读/安全测试图副本 | Studio → `图像引导` | 检查操作/参考下拉、导入入口和有效性信息；如需编辑蒙版，只在可丢弃的副本上并最终取消 | P-02 | 不调用 Vibe 编码、图生图、重绘或任何网络接口 |
| 13 | 聊天待发送附件 | 现有可丢弃聊天会话、2–3 张本地图片 | CHAT → 会话 → 输入框右下「添加图片附件」 | 一次选择多图，观察待发送缩略图；多图超宽横向滚动、拖动重排、点图看预览、hover × 删除；展开输入框观察工具动作仍靠右下 | L-01..04/07 | 只检查待发送状态，不点 SEND；事后移除所有测试附件 |
| 14 | 剪贴板/拖文件/APNG | 可丢弃聊天会话；PNG 与可选 APNG 测试文件 | CHAT → 同一输入框 | Ctrl+V 图片、拖 PNG 到 composer，确认待发送附件加入与删除；有规范 ChatBar 伪装 APNG/普通 APNG 样本可检查「已还原伪装」/不会误判 | L-05/06/08..11 | 不发送到模型。无 APNG 样本标记 NOT TESTED；发送后的上传链路只能复用历史 fake test |
| 15 | Advanced / Diagnostics | 只读；**代码审查通过，待视觉验收** | TOOLS 编辑区顶部「NovelAI Studio」/「高级 / 诊断」 | 两入口应同行，「高级 / 诊断」靠编辑栏右端且切换可读 Prompt Inspector，返回 Studio 正常；若你预期它在整个工具窗口最右侧而当前不是，请标 FAIL 并写位置差异 | N-01..04 | 源码仅保证编辑栏右对齐，**不声称整个窗口右端**；本项只在此次综合测试中检查，不单独启动 |
| 16 | Session Settings tabs / dirty | 可丢弃会话，或只读 | CHAT → 会话标题右侧设置图标 → 「基础 / Prompt / 上下文 / 图片 / 高级」 | 查看四 tab；在可丢弃会话修改非敏感字段，切 tab 确认 dirty 保留、Save/Cancel 固定且退出有保存/放弃确认；选放弃并确认原值恢复 | O-01..04 | 不要修改有重要内容的真实会话；无可丢弃会话只验收 UI 可见，dirty/save 记未测 |
| 17 | 背景库与即时保存 | 可丢弃会话或只读 | Session Settings → `图片` → `Desktop 角色背景库` | 只读检查背景库打开/返回；若有安全测试会话且可恢复旧值，再验证背景即时保存与普通 draft Save/Cancel 不互相覆盖 | O-05; P-04 | D-05 已验证背景库 Viewer；不删除背景，不改正式角色首选；不能安全恢复则 NOT TESTED |
| 18 | 聊天与持久化边界 | 可选有 fake chat 的测试资料 | CHAT → 已有会话；本轮检查完毕后 | 不需要发送真实 AI 请求。正常消息发送/编辑只在已授权的本地 fake 测试环境进行；如有可牺牲持久化数据，可在完整批次末尾执行**预先计划的一次应用重启**，检查保留状态 | P-06/07 | 无 fake/test profile 则记录 NOT TESTED，复用 Phase 4–6 已有持久化/逻辑测试；P-05 Character Start Chat 已在 74 INT-05 验收 |

说明：ID 01–12 从 Studio/TOOLS 走一遍，建议**顺路完成 15 Diagnostics** 后再离开 TOOLS；13–14 去 CHAT 处理**待发送**图片，16–18 在 CHAT/Session Settings 完成。原 ID 保持稳定，方便批量回报。可因当前界面省略重复导航，但**全部结论在同一次启动完成并集中回报**。

## 4. 本批次明确**不再要求人工重复**的范围

- Slice A 默认生图模型显示；B1 聊天已保存图片 More/右键；B2 图片过程卡/Stop；C 预览分隔条与 Focus；D Viewer 前进后退/拖动/滚轮/单图；WIN 大型辅助窗口图标/缩放；W2 History 排序/相册/搜索日期；W2 Studio filmstrip/窄窗/图片按钮；metadata M1–M3 弹窗布局。
- `I-02/I-03` 仅属于已有**官方结构化正向 Prompt**剪贴板交换，源码和逻辑测试有证据，用户选择暂不测；它**不是**用户要求的完整格式化 Prompt 跨应用交换，后者在 `55` 中作为 P17 proposed enhancement，**不阻塞 P7**。
- 角色位置的最终 request/角色 disabled/ID、alpha metadata、privacy-bit 清除、APNG 真伪、额外 AI prompt/设计真实调用、Enhance/Upscale 请求与 cancel/source persistence 属协议/数据测试证明范围；无法从视觉测试推导最终序列化，不能把一个 UI PASS 冒充 API parity。
- 当前产品 EXE 没有提供已验证的、无需发请求即可构造 post-processing before/after 结果的入口，所以 **J-02/J-04/J-05 在无已有合法 fake/结果时直接记 NOT TESTED**，不要求用 Anlas 交换一个人工勾选。
- 已有真实聊天基础能力/Character Start Chat 与 data-store safety 的 Phase 4–6 acceptance 不应在本轮重新做 destructive work；如果已有最近真实结果不必重复发送。

## 5. 一次性回报模板

把以下一行复制回对话，每项填 `PASS`、`FAIL` 或 `NT`，并在后面列出有异常的 ID 和简短说明即可；**不要把这个模板提交为新源代码或修改用户数据**。

```text
CCB Desktop P7 Final One-Session Windows Acceptance
SOURCE SHA: 698f0cd55e0ae370650fac012cdb50f0dd7e033f
01=   02=   03=   04=   05=   06=
07=   08=   09=   10=   11=   12=
13=   14=   15=   16=   17=   18=
FAIL/NT 补充说明：
```

Project 收到后会对照每个 ID 的原清单项和覆盖子条件逐项记证据，不会因为大组 PASS 就自动签署没有真正测试的请求/数据语义。必要代码修复只针对具体 FAIL，随后进行最终集成风险审查；本单不授权自动 merge 到 `desktop`。

**最终操作规则：这 18 项全部有结果（包括 NT）并且可选的计划重启也已完成/标为 NT 时，你就可以关闭应用。之后 Project 不能再把新的零散 GUI 测试当作这次同一批次的遗漏随意追加。**
