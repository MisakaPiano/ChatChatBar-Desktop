# Post-P8 Windows 人工 Gate：详细交接 Project

日期：2026-10-20 JST。状态：**HOLD / WINDOWS MANUAL USER FAILURES / PROJECT REVIEW PENDING**。

本附录按用户追加要求补全，供当前 ChatGPT Project 独立复审。首份证据冻结提交：`67e8b63cd43842866a9fd30d4bc9a1ce45263a03`，直接 parent：`c280dca127ca246d03d301c5fa8af8a78b69fd20`。本附录所在补充提交 SHA 由 Git/最终回复给出，不在自身文件中伪造自引用。

## 1. 权威、权限与实际范围

- 分支：`feature/phase8-fish-audio`；生产源码：`694a8bdd6e48bd6696d2eeb59f141935c67163b0`。
- 精确 Windows 包：`app/desktopApp/build/post-p8-audio-maintenance-r1-20261020/windows-frozen-694a8bdd/runtime-image`。
- 包 manifest SHA-256：`d98691a663275c99aeee2318514db1e528e28c74a71349c232e235cc7f076ed1`。启动前、退出后均212/212一致，无多余或缺失。
- W0 按授权从 `cc8d633` FF-only 到 `c280dca`，仅D-044四文档；没有产品漂移。D-044不实施、不测试。
- 用户随后明确授权W1播种、一个fake和一个冻结实例，并扩展live预算：主动LLM最多1次、NovelAI生图最多1次、Fish短TTS最多1次，重试也计数。真实密钥只能由用户在应用正常受保护入口输入，不交给Codex。
- **实际只执行fake阶段；真实LLM/NovelAI/Fish全部0，预算未用，live结果NOT TESTED。** UI/播放/截图问题使本轮停在HOLD；没有为耗尽预算而继续，也未把fake冒充real。
- 没有Gradle/JUnit/Robot/bounded、R6 runner、产品重构建、真实迁移/恢复或重启。只编译了新gitignored验证辅助Java工具。
- 没有改Kotlin、测试、Prompt、Entity、Package/schema、CURRENT、parity、baseline、正式音频存储或R1维护实现。只有独立evidence提交。

## 2. 启动与隔离的技术证据

新根：`app/desktopApp/build/post-p8-windows-manual-20261020/environments/post-p8-profile/ChatChatBarDesktop`。旧R3和历史包未复用或重置。

实际WindowsSecretStore/DPAPI保存三个不同的无效合成凭据槽：两个ModelApiKey和FishAudioApiKey。ModelConfig JSON apiKey均为空；实际持久化策略+ModelRepository hydrate、reopen、负向明文拒绝、Studio模型列表、角色/会话读取均通过离线预检。DesktopSettingsStore实读确认自动备份关闭。没有把SecretStore复制或哈希进证据。

一次性guard只准入一个真实CCB实例。fake先ready，仅监听127.0.0.1:64200，无向provider转发代码。Launcher PID30452、加载jvm.dll的子进程32396、fake PID25348与具体包/root/log关联，保留真实进程句柄。窗口有有效主句柄并响应；没有定时关闭或Kill。

原始证据：[W1预检](../w1/)、[运行进程](../runtime/manual01/)、[fake事件](../fake/events.jsonl)、[最终核验](../manual-results/final-verification.json)。

## 3. 人工矩阵：不能合并成整体验收PASS

| 项目 | 实际签署／观察 | 限定含义 |
|---|---|---|
| A1宽窗右键 | USER PASS | 三行图文、不换行、Escape关闭 |
| A2右键/…与权限 | USER PASS | 同菜单；…不伪造片段；旧回复再生成禁用、最新合法回复可用；外部点击关闭 |
| A3窄窗/用户工具栏 | USER FAIL | User气泡靠右而底部按钮靠左且有多余操作；用户要求仅复制/编辑、右对齐 |
| User头像 | 后续澄清 | 当前User明确走文字“你”占位，未接入自定义User图像；未证明头像文件丢失 |
| 菜单第一行 | 新增明确修订要求 | 重新生成/修复格式各占一半操作区，图文居中，禁用仍保持位置 |
| B1听书开启 | USER PASS | 角色音色标签、下拉、Alice/Bob可见；不代表已经选择 |
| 未选声线生成失败 | 前置条件拒绝 | 用户确认未选就点喇叭/生成，FAILED0/0时请求0；不是已证实声线状态丢失 |
| B2可听/首次Stop | USER PASS | 本机音频实际可听；首次Stop静音、播放器显示已暂停 |
| B3续播/二次Stop | USER PASS | 用户听觉确认原位续播、再暂停、暂停后再Stop关闭。没有本轮独立native position遥测 |
| B4听书关闭 | USER PARTIAL；指导预期有误 | 常驻下拉隐藏；对话目标没有旁白时确认窗不需声线选择。不能把“按需”说成必现，也不补签整组PASS |
| Deck/音色/录音操作窄窗 | USER FAIL／修订要求 | 超出边界、覆盖、角色名截短、展开操作栏换行；见下一节 |
| 播放中反复点语音条 | USER REPORTED FAIL | 首次重播、再点卡住；没有重复复现。后续用户确认已无声、播放器关闭、界面可操作；根因仍UNKNOWN |
| 跨User连播 | NOT TESTED独立分支 | 虽有前后两条回复的录音，不得据此补签连续播放 |
| 替换反馈/生成取消/编辑删除与版本切换 | NOT TESTED本轮限定分支 | 不借旧R3或focused结果冒充当前人工执行 |
| C1单片段初次预览 | USER PASS，保留原始范围 | 用户最初确认目标、文字、头像、背景；后续异议和全选预览独立保留 |
| C2导出 | USER FAIL / BLOCKED | 无实际PNG落盘、未完成系统查看器打开；不能宣称保存编码链路已验证 |
| 15项全选与录音条 | 用户手动选择 | 用户明确纠正：15项是自己点的。包含录音项的全选预览不能证明自动增选或单片段污染 |
| 截图内部关闭 | USER FAIL，应用退出有技术佐证 | 用户明确点截图内文字“关闭”；全应用退出，而非用户主窗口X正常关闭PASS |
| 深浅色/宽窄/整条多选完整导出矩阵 | NOT TESTED独立流程 | 有用户自发多选截图，不等于整组签署 |
| live smoke/重启/真实维护并发 | NOT TESTED | 没有调用、重启、迁移或恢复 |

完整逐步记录见[人工序列](../manual-results/user-chronology.jsonl)、[矩阵JSON](../manual-matrix.json)和[用户意见原话](USER_FEEDBACK_VERBATIM.md)。

## 4. 用户具体意见：后续合同必须逐项保留

### 4.1 普通消息和菜单

1. User底部只保留复制、编辑，整个操作组右对齐；不沿用助手的重新生成/更多等组合。
2. 菜单第一行两个按钮平分可用操作区并分别居中。
3. 窄窗菜单受主窗口边界约束，不能被裁掉或超出窗口；三行本身不换行，末端操作可达。
4. 头像疑点已澄清为当前文字占位和未配置User图像能力；不能在报告中继续断言图片丢失。

### 4.2 单行音频区域

1. 拆为左生成状态/生成中停止、中间固定预留播放器、右侧音色下拉，三者严格同一行、样式统一、不可互盖、不加纵向高度。
2. 中间播放器预留位置固定；右侧不是漂浮到任意位置的独立按钮。
3. 正常宽度显示完整角色名，只有宽度不足才省略为角色名…；最窄保留“旁白”两个字。左侧生成状态可以更积极省略。
4. 下拉展开的各选项统一宽度，以最长预览名称为依据；仍须满足主窗口边界。
5. 原文同时出现“旁边：角色名”和“极限…旁白”，按其最终旁白收缩意图保全；最终字段文字与现有官方“角色音色”关系应在下一合同明确，不在本轮静默改字。

### 4.3 语音条和录音操作

1. 语音条右侧固定预留一个“…”位置，操作菜单由该入口与语音条右键共用；用类似编辑消息的小浮窗，不在原地展开一大片操作栏。
2. 语音条仅保留播放、时长、动态正在播放图标，不靠角色名/长标题撑宽或换行。
3. 播放中再点不应重置到开头；应有暂停/停止语义。用户报告再点卡住必须单独诊断，不能说原位续播签署已覆盖所有点击路径。
4. 用户希望拖动播放进度，且不要增加纵向长度。这是新增平台/播放队列能力需求，不是已有PASS。
5. “整条生成没有重新生成”已单独登记：当前补齐缺失与整条重新生成不是一回事。整条重生成对旧录音是新增保留还是替换，须冻结具体合同，不能擅自删旧音频。

### 4.4 截图选择、折叠与导出

1. 不是零散独立按钮列表。应按对应User发言/轮次组织，助手完整回复作为一个内容块挂在该User发言下面，**不用AI第一句当标题**。
2. 每条完整回复独立展开、折叠，折叠后仍支持整条选择，大量多选/全选不必逐段展开；折叠不清除勾选。
3. 顶部工具栏：全部展开、全部折叠、全选、全不选、反选。
4. 重做稀疏、难理解的排版，让预览、保存、关闭位置明确。截图内关闭只能离开截图界面，不能退出应用。
5. PNG继续保留；PDF、Word导出是用户新增需求，需要独立格式/资源/内容合同，不冒称已实现或当前已授权开发。

## 5. 关闭事件：观察、推断和未知必须区分

**USER OBSERVED：**用户点击截图界面右上角文字“关闭”，随后整个应用消失；经单独回忆确认，不是Windows X/Alt+F4。

**TECHNICAL OBSERVED：**stderr有`CLOSE_REQUESTED -> COMPOSE_RETURNED -> TASK/DRAFT/BACKUP/COORDINATOR/MIGRATION drain -> OWNERSHIP_RELEASED -> MAIN_RETURNED -> SAFE_EXIT_HANDOFF`；Launcher/JVM实际0/0。并非已证明原生崩溃，也不是用户正常结束主窗口的PASS。

**SOURCE OBSERVED：**`DesktopChatScreenshotHost.kt`的文字Close当前仅清理本地截图状态；Main有独立requestClose。事件为何到达全局关闭尚不明确。禁止凭单函数声称已找到根因。下一窄合同应关注popup owner/focus/close事件归属、截图关闭后主窗口仍存活且保持锁；不在本Gate大改全局生命周期。

## 6. 请求、数据与资源收尾

- fake实际：TTS6、辅助LLM1，日志均200；real LLM0、NovelAI0、Fish0，无费用/配额变化观测。用户有额外自发生成/选择，不能将请求数直接视作一点击的因果证明或隐藏重试证明。
- 运行中独立根锁拒绝75；退出后独立获取并释放0；Launcher/JVM0/0，fake排水退出0；无残留相关进程、无fake监听。
- 六条正式MP3、六条语音记录保留；六条MP3与HOLD时hash相同，退出后均可独占只读打开/关闭。
- 六份聊天消息、两张原合成角色卡字节不变。另出现两张带sourcePresetKey的卡（Rupa/MujicaMyGO，推断为应用启动内置目录填充；没有事件级出处证明），已完整披露/保留，不将“全目录只有两卡”当真。
- 没有PNG/MP3导出；没有preview MP3/.part及app-root .part残留，临时TLS删除；不声称全部temp DLL/标记都为空。
- 数据、旧R3、旧包、四份user-owned文件、历史RED/SKIPPED/INCONCLUSIVE均保全。四份用户文件仅查路径，未读/未哈希/未暂存。
- 收集器出现过脚本语法错误、过宽的卡片数量假设、嵌套manifest遗漏及Windows原始日志CRLF检查差异；均作为证据收集问题记录，未改变原始日志、数据、产品或测试。最终125个首份manifest成员与Git index原始字节核验一致。

## 7. 建议Project下一步裁定，而非本轮自动执行

1. 优先冻结截图内部Close导致全应用关闭的最小事件归属诊断合同。
2. 独立冻结语音条重复点击/暂停/队列替换行为；保持R1原生close确认、维护排水、数据登记和失败保留语义。Seek另明确平台合同。
3. 合并用户已明确的单行三区、音色收缩与录音浮窗UI要求，再列消息工具栏/菜单布局修复；覆盖窄宽/焦点/键盘/越界/所有权。
4. 截图选择布局按User轮次及回复块重做，保留稳定selection/block/version ID；批量选择及折叠独立测试。PDF/Word另行冻结边界。
5. 修复源码经定向验证后准备新精确包，再恢复适用的人工Gate和live smoke；不得用本次PASS补齐未测分支或复用一次性启动锁。

Studio credential error recovery、QQ native sending、WIN-AUTO-001/WIN-BRAND-001、完整bounded非绿色与所有历史限制不变。不关闭完整P8，不merge desktop、不发布。**STOP，交Project独立复审。**
