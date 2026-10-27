# P8-S2A-R5-T1 集中 Windows 人工验收清单（未签署）

T1 基点 `96ee0496adba6ecb2bd0320ee5502042a5058947`，生产源码与 `d64f82f6` 相同；唯一测试修改见 `test-contract.patch`。

新 bounded 已通过：153 suites / 1492 tests，1490 reported PASS、2 SKIPPED、0 failures/errors。新精确 distribution 212 文件，见 distribution-manifest。隔离 Launcher/JVM 实际运行后正常退出0/0，新进程真实 ownership 重获及释放通过。

**WINDOWS UI BLOCKED**：一次局部捕获 FrameArrived timeout，一次只取可访问性的诊断仅返回窗口与 pane；没有可靠正文/控件观察，未进行 Fish 操作，没有截图或用户签署。窗口枚举与进程退出不能替代 UI 验收。以下25项保留集中操作、预期与签署栏，所有完整操作仍 NOT TESTED；W23/W24的退出子项已有自动证据，但清单并非人工PASS。当前测试窗口已正常关闭，不自动重开。

隔离环境已包含合成 STRUCTURED/FREEFORM 卡及 fake Fish DPAPI key。fake服务通过仅本进程代理与临时 TLS public truststore连接，原 runtime image未修改；临时私钥/truststore已正常清理。未来获授权的人工窗口需由测试方重新建立该合成服务，不能连接真实账户或旧包来填PASS。

## 一次集中验证的准备

- 由后续验证任务生成一次该精确源码 app image，完整 SHA-256 manifest 二次读回；只用独立 runtime-image copy 和隔离 LOCALAPPDATA。数据根为其 `ChatChatBarDesktop`，Secrets 为隔离的 `ChatChatBarDesktopCredentials`；绝不使用真实 profile。
- 准备已验证的本机 fake Fish 服务，返回固定个人/社区、两页、过滤、401/403/429/错误和可控延迟响应；确保所有目录请求只到本机，不请求音频、TTS 或外网。需要进程级测试路由时记录其与原 runtime image 的差别，不修改系统代理、truststore 或真实凭据。
- 全部凭据只用无有效账号的合成字符串；截图不得展示真实 Key。选择一张结构化、一张自由文本合成卡，每张两个人物，先记录 person ID、绑定快照和卡片文件身份。准备两个合成会话 A/B、一项可用辅助文本模型及一个显式失效 ID。
- 预先记录 Launcher/JVM 的 PID、创建时间、可执行路径/哈希和父子关系。只操作本次进程；每次重开复用同一合成 profile，进程/锁未确认退出时不再启动第二份。
- 以下中文入口来自当前源码，不代表本轮已在真实窗口看见。记录列仅允许 PASS / FAIL / NOT TESTED；人工操作项保持 NOT TESTED；进程退出/锁已有独立自动证据，不能替代界面签署。按组连续操作，最后再做一次正常关闭和重开，避免无必要的多次启动。

## 集中操作与记录

| ID | 实际入口及操作 | 预期结果 | 本轮记录 | 必要证据 |
| --- | --- | --- | --- | --- |
| W01 | 启动隔离 Launcher，进入主业务窗口 | 是正常 CCB 业务界面；不出现 Startup blocked/Unsafe Shutdown | NOT TESTED | 仅测试窗口局部截图；进程身份另记 |
| W02 | 管理 → 设置，滚动到“语音与朗读 · Fish Audio” | TTS/标签模型、听书模式、密钥状态和 PENDING 说明可见可操作 | NOT TESTED | 局部截图，窄高窗口仍可滚动到全部控件 |
| W03 | “设置 / 替换 Fish 密钥”→输入合成 Key→“取消密钥编辑” | 状态、OS-store 和其他设置不变；不自动请求网络 | NOT TESTED | 状态与 fake 服务请求计数 |
| W04 | 合成 Key 保存→替换→取消/确认清除；最后重新保存合成 Key | 保存独立于全局 draft；失败保留旧值；普通 JSON/Package 无 Key | NOT TESTED | 密钥输入隐藏；失败保全由隔离可控夹具记录，不破坏系统权限 |
| W05 | Fish TTS 模型逐一选择；“语音翻译 / 标签模型”选择辅助模型或“当前会话对话模型” | 仅官方支持的 TTS；辅助模型包含不在聊天列表的模型；null 正确保留 | NOT TESTED | 设置局部截图与合成持久化字段 |
| W06 | 打开显式失效模型设置，切换听书模式后“放弃设置更改” | 失效显示“不可用/不会自动替换”；放弃不保存，不静默 fallback | NOT TESTED | 保存前后合成 JSON 身份 |
| W07 | 修改 Fish 模型/标签模型/听书模式→“保存全局设置” | 并发的其他全局设置不被覆盖；重新进入保持已保存结果 | NOT TESTED | UI 和字段级重读；并发保存由已通过的控制器测试另作证据 |
| W08 | 聊天 → 会话设置 →“语音与朗读” | 有“会话听书模式”与“语音使用语言（留空使用原文）” | NOT TESTED | 局部截图 |
| W09 | 会话听书模式依次跟随全局/开启/关闭；语言逐字符输入 `Brazilian Portuguese`，含中间空格 | 输入不吞空格；保存/dirty 边界规范化，不自动翻译或生成 | NOT TESTED | 实际输入、保存后重读 |
| W10 | 会话 A 编辑后放弃或保存；切换 B，再回 A | 草稿不串会话；保存、放弃和离开保护正确 | NOT TESTED | A/B 身份及设置前后记录 |
| W11 | 保存 A 过程中继续改语言，再尝试切换会话或关闭设置 | 未保存新编辑保留 dirty，首笔保存不吞新值；明确第二次保存才持久化 | NOT TESTED | 可控挂起夹具；不能凭快速点击推断竞争覆盖 |
| W12 | 管理 → 角色 → 编辑结构化卡，选择人物 →“选择 / 更换音色” | 打开同一 Fish 音色库，正确显示绑定标题与可用性未验证说明 | NOT TESTED | STRUCTURED 窗口局部截图 |
| W13 | 编辑自由文本卡 → 人物头像册 → 选择人物 →“选择 / 更换音色” | 同样可打开音色库；不是仅结构化模式可用 | NOT TESTED | FREEFORM 窗口局部截图 |
| W14 | 音色库“我的音色”/“社区音色” | fake GET `/model` 的 self 区分正确；展示合成作者/权限/语言 | NOT TESTED | UI 与 fake 服务脱敏计数 |
| W15 | 音色标题、标签、语言、排序 → 查询/重试 → 上一页/下一页 | 筛选、页码及 hasMore 正确；输入变化取消旧查询，旧结果不可选择 | NOT TESTED | 两页截图及查询参数的合成记录 |
| W16 | fake 返回401/403/429、坏/过大响应，再恢复正常查询 | 显示脱敏错误，不泄露 Header/正文，不自动切换音色或重试 | NOT TESTED | 错误局部截图、调用计数 |
| W17 | 查询后取消/关闭音色库；切换人物或卡片后让旧查询结束 | 无绑定变化、无迟到错误绑定；不误写另一人物/新 draft | NOT TESTED | 稳定 person ID/草稿身份/绑定前后对照 |
| W18 | 明确点击“选择音色 · …”，保存卡片并重新编辑 | 仅当前 person 绑定变化，ID 和完整 snapshot 保留；两模式均成立 | NOT TESTED | 绑定字段、保存重开截图 |
| W19 | 移除音色绑定后放弃草稿；再显式移除并保存 | 放弃恢复原绑定；只有明确保存才持久化移除 | NOT TESTED | 合成卡片前后对照 |
| W20 | 合成卡 JSON/CCB PNG 导出、复制、导入新卡，再重新打开 | 绑定 snapshot、人物身份与编辑模式遵循既有 transfer 语义，不引入新 schema | NOT TESTED | 仅合成文件哈希与 UI；不导出用户数据 |
| W21 | 检查音色库和设置说明 | 试听/生成/播放仍 PENDING；没有假试听按钮或自动样例音频请求 | NOT TESTED | 局部截图与服务请求计数 |
| W22 | fake 查询挂起时正常关闭音色库/主窗口 | 请求取消和资源排水完成；不把窗口消失当 JVM 退出 | NOT TESTED | 取消、服务连接结束及后续独立进程检查 |
| W23 | 正常标题栏关闭后检查 Launcher | Launcher 真正结束并保存实际 exit code | NOT TESTED | 单独 PID/创建时间/退出码栏 |
| W24 | 独立检查 JVM 子进程 | JVM 真正结束并保存实际 exit code；不是仅 MainWindowHandle=0 | NOT TESTED | 单独 PID/jvm.dll/退出码栏 |
| W25 | 新独立进程取得同根 ownership，再重开同 profile | 锁真实释放/可重获；设置和绑定保持；未确认退出不得强行删锁 | NOT TESTED | ownership 取得/释放及重启读取 |

## 截图与失败处理

只捕获本轮已核实窗口，不截整个桌面、不碰 Chrome/NovelAI 或剪贴板。保存每组必要的局部截图及文件哈希；人工签署写明组ID、实际观察、日期和结果，不将 offscreen Compose、窗口标题或源码当成真实正文截图。

如实际窗口自动化发生 FrameArrived timeout，只做一次有界定位，记录“没检测到窗口/真实窗口未显示/启动异常/进程未退出”的证据差别；仍无法可靠操作就 WINDOWS UI BLOCKED，不无限重试、不改无关 UI、不强杀或释放锁。watchdog 只表示未确认完成，不保证底层 I/O 已排水。
