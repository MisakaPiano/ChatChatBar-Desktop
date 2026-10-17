# WIN-AUTO-001 — Windows 桌面自动化可靠性调查

**OPEN / CAPTURE INFRASTRUCTURE BLOCKED / ROOT CAUSE NOT YET PROVEN**

用户要求登记并分析历史上“有时能控制、有时不能控制”的差异。本记录独立于已发布的 Fish R1A 验收证据；不改变其结果，不重新启动 CCB，不修改生产源码或验收状态。

调查锚点：`2092806f8e4a203a1d2b0ddf7aa14549e3179dd0`。R1A 源码仍为 `fa5d3f18eebbd6c34f607d7b52d47e24825cb411`，父 `c4dece60cddc1aedf673cb532b5976554690b4ea`。本次只新增独立调查记录及无业务诊断夹具源码，不修改旧证据。

## 已证实的差异

| 路径 | 本次核实的事实 | 不能替代的证明 |
| --- | --- | --- |
| AWT Robot + Win32 | P7 B2 R3 的原始 XML、日志及冻结源码可用；2 个 Native 测试实际通过。截图使用 `PrintWindow`，输入前核实前台 HWND、命中窗口和物理坐标 | 不能证明 Computer Use 的截图后端可用 |
| Computer Use / `@oai/sky` 截图 | Fish T1、G1B 历史存在 `FrameArrived` / capture timeout；本次独立 WinForms 窗口也复现 | 不能据此判定 CCB 没有显示，或全部桌面输入均不可用 |
| Windows 可访问性读取 | 本次 WinForms 能返回输入框、按钮、滚动条；同一 API 在历史 CCB/Swing 界面只返回 Window/Pane，而 Win32 AlreadyInUse 正文可读 | 控件树存在不代表几何、鼠标输入或像素捕获可用 |
| 直接可访问性设置 | 本次 `set_value` 成功，并由下一次读取确认合成文本值 | 不证明普通鼠标点击可用；counter 点击明确报几何不可用 |
| 键盘关闭 | 本次 `Alt_L+F4` 后正常关闭事件、消息循环返回、窗口消失和独立进程查询相互支持；历史 T1 有真实退出 0/0 | 不证明窗口内业务控件已操作或截图成功 |
| ImageComposeScene | R1A 修复的是离屏测试帧时钟/滚轮驱动；不是 Windows.Graphics.Capture 修复 | 不能把 offscreen GREEN 当作原生视觉验收 |

`history-matrix.json` 分列 12 个已定位的历史事件组；`source-ledger.json` 给出 53 个 Git/本地文件身份及相关行号。历史原始 XML 经定向读取，**没有重新执行历史测试**。本调查没有恢复所有历史聊天或全部工具 RPC，因此不宣称列出了每一次尝试。多份文档重复写“历史 FrameArrived”不计作多次新失败。

## 历史成功与失败的解释边界

1. P7 R2 的拖动失败发生于获取正确前台窗口；R3 成功时实际前台、命中目标和坐标均符合要求。P7 D 最终失败点被外部 Chrome 占据，安全检查阻止了误点击。它们是前台/目标条件问题；未证明 OS 拒绝激活的最终原因。
2. 用户记得的拖动、缩放成功确有对应 Native 自动化证据；P7 WIN 的三项最大化/滚动签署则明确是用户人工执行。这两类成功都保留，不能互相冒充。
3. G1B-BV 曾连续三次 `list_windows` 超时，包括一次内核重置后；那次尚未进入截图阶段。B2/B4 窗口观察超时、后来人工看见窗口及 BV-B4 退出码 UNKNOWN 仍分别保留。
4. 初始 R5 被 bounded 测试 RED 阻断，并没有发生新的截图超时。T2/R1A 人工门禁也没有重试截图接口。
5. 原 WIN 离屏滚动 RED 与本次 R1A 的帧时钟修复有关联线索，但没有重跑旧 WIN 测试，不能宣布旧 RED 已被解释或修复。

## 本次独立最小复现

夹具：本地生成的 .NET WinForms 测试窗口，固定标题 `CCB Automation Diagnostic - SYNTHETIC ONLY`；无 CCB 数据、凭据、网络、根锁或持久化业务。只使用文档化 `sky` API 操作该唯一返回窗口；没有读取其他窗口正文或截取整个桌面。

- 初始化、窗口发现、唯一目标选择：通过。
- 首次截图：`FrameArrived timed out: timed out waiting on channel`。
- 新鲜的 text-only 读取：得到合成输入框、按钮、滚动条和焦点。
- 点击已观察的 counter 按钮：`coordinate input geometry is unavailable`；夹具没有 counter 事件。
- 激活该窗口后的唯一截图重试：`window capture timed out: timed out waiting on channel`。
- 新鲜 text-only 读取后直接设置合成输入框：成功，后续读取值为 `synthetic-probe-only`。
- Tab 后的即时焦点仍显示编辑框；未隔离异步时序，记 NOT CONFIRMED，不宣称键盘必然失败。
- 正常键盘关闭完成。即时枚举曾仍看到窗口；后续事件和新鲜枚举才确认关闭。没有强杀，没有触发 240 秒自动关闭定时器。
- 后续独立只读查询：该路径下诊断进程数为 0。启动 API 未提供保留的进程句柄，**实际退出码 NOT CAPTURED**，不伪造为 0。

工具结果见 `probe-observations.json`，它是实际工具响应的转录，**不是原始 RPC 导出或截图**。夹具事件原始字节见 `fixture-events.jsonl`。初次 C# 编译因相对路径解析失败 CS1504、exit1；改用绝对源/输出路径后 exit0，失败未归为产品问题。可执行文件留在 gitignored build 目录，仅提交源码和哈希，不提交二进制。

Windows 10 build19045，读取上下文和 DWM 均为 session1；记录到 NVIDIA GTX1080Ti 与 MuMu Virtual Display Adapter。**虚拟显示驱动、GPU、多会话、helper 内部状态都是待检验假设，不是已证实原因。** 未关闭 MuMu、Chrome 或任何用户进程，未修改驱动、系统捕获设置、证书、代理或 pagefile。SESSIONNAME 为空不证明本机/远程会话类型。Computer Use bundle 路径标识为26.1013.40934；本轮没有得到独立 helper 二进制版本，历史版本也不完整。

## 处置与负责人

| 项目 | 状态 | 负责人/下一步 | 关闭标准 |
| --- | --- | --- | --- |
| 将不同机制混为“控制电脑失败” | 已纠正记录方式 | Codex：按发现、捕获、UIA、几何/焦点、输入、效果、退出分别记录 | 每项有实际证据；未执行明确标注 |
| 历史成功/失败无法比较 | 部分补证完成 | Codex：保留本次矩阵与 ledger；缺失历史 RPC/版本继续标 UNKNOWN | 不以重复文字代替尝试次数，不反向抹去 RED |
| 当前非 CCB 窗口截图也失败 | **BLOCKED / 未修复** | Computer Use 平台维护方：定位 WGC/frame channel、窗口几何缓存和超时；用户/Project 决定单独基础设施诊断切片 | 固定窗口局部截图真实取得并复核；同一环境可稳定重放，失败仍可诊断 |
| CCB 的 text-only 仅 Window/Pane | **OPEN** | Project：单独界定 Compose/Java 原生可访问性适配是否应进入产品范围；Codex 提供可复现控件树差异 | 实际包能识别并操作必要控件，读回/存储证据一致 |
| 不具备截图几何时仍需鼠标输入 | **REJECT** | Codex：不猜坐标、不绕过目标检查；仅在能证明效果的独立 UIA 路径继续 | 目标身份、焦点/几何、动作与读回均可信 |
| 跨驱动/会话对照 | **DECISION REQUIRED** | 用户/平台维护方：独立批准一次有控制变量的诊断；不在验收中任意关闭进程、切换驱动或更改系统 | 每次只改变一个变量，记录版本、会话、窗口、结果；有恢复方案 |

本轮已建立可复用最小复现、分层证据和禁止静默成功的记录规则；**没有修复或关闭截图后端问题**。不能靠增加超时、无限重试、切换截图手段后省略 provenance，或把人工验收记作自动化 PASS 来结案。

后续原则见 `CAPABILITY_PROTOCOL_CN.md`。本调查不启动 Fish 后续切片、G2、Writer/Recovery、生产 dispatch、发布或合并；不修改 CURRENT、parity、baseline、Prompt、Package/schema。也未向任何外部维护方发送日志或报障消息。

官方背景说明仅用于驱动/观察原则，不能证明本地错误根因：[Computer use drivers](https://developers.openai.com/api/docs/guides/tools-computer-use-sdk-drivers)、[Plugin troubleshooting](https://developers.openai.com/plugins/deploy/troubleshooting)。本地 Computer Use 技能明确区分 text-only、capture、状态刷新和有界恢复；本轮按其接口执行。
