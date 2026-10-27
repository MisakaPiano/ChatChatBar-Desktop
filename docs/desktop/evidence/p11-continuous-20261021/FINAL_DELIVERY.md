# 核心 UI、P9、P10、P11 连续推进交付

本轮已完成请求范围内的工程实现和相邻自动化自检，已制作统一 Windows 候选包，进入 Preview Gate 的统一人工/跨设备验收准备。没有跳过 P9 或 P10。工程测试不等于用户签署，也不把尚未测试的设备行为列为 FAIL。

## 提交与包

- 起点：`db198c096a5b03a42e8deff020195db3fd911f3c`，原 feature 分支。
- 核心 UI：`ab3d06abcaca2df8b21c734841d7c4e744099f51`。
- P9/P10：`f985a012d8edc33aa4e9e197c49a9fdb09368d05`。
- P11/更新发现生产源码：`c609e757cca94dca20015e91279c9629e5782ea6`。本证据提交随后追加，不改变包内源码身份。
- 完整 P11 diff：`p11/production.diff`；文件列表：`p11/changed-files.txt`；68 个文件包含官方代码提取、平台适配及对应回归。Main 仅新增一个控制器参数传递，未改关闭回调或 Window Chrome。
- 本次连续交付的全部产品/测试/技能改动另见 `p11/continuous-production.diff` 与 `p11/continuous-changed-files.txt`，包括核心 UI、P9、P10，未将它们从交付范围省略。
- Windows 候选版本：`0.1.0-preview.1`；原生安装器数字版本 `0.1.0`，与 Android 版本分离。这是本地工程候选，不是发布许可。
- 运行包：`H:\ChatChatBar-Desktop\app\desktopApp\build\p11-completion-20261021\windows-frozen-c609e757\runtime-image`。
- 清单：`H:\ChatChatBar-Desktop\app\desktopApp\build\p11-completion-20261021\windows-frozen-c609e757\package-manifest.json`，SHA-256 `4fb54816cf86e2025156626fb79dfbd7b3073f55f81e03c88490c3c00f8b7447`。
- `217/217` 文件路径、大小和 SHA-256 逐项一致；缺失/多余/不符均为 0。内置版本资源中的 commit 与生产提交一致。
- 可携带 ZIP：`H:\ChatChatBar-Desktop\app\desktopApp\build\p11-completion-20261021\windows-frozen-c609e757\ChatChatBarDesktop-0.1.0-preview.1-c609e757-windows.zip`，SHA-256 `a0e6b933005b17f8a1802a13d85293b46a61f4b3b128b5c24843381881490220`。
- MSI/EXE 身份详见 `p11/verification.json`。仅构建和校验，未安装、未启动新包；未覆盖任何旧包。

## 已落实的用户核心要求

| 关切 | 本轮实现 | 证据边界 |
| --- | --- | --- |
| User 工具栏 | 右对齐，仅复制/编辑 | 工程回归通过，新包视觉待统一验收 |
| 回复菜单 | 第一行等宽居中；窗口边界限制，行内横向可达 | 新包 Windows 手动观察待测 |
| 音频 Deck | 左状态/停止、中固定播放器、右旁白选择，单行、不覆盖；宽度不足才省略 | 不改变既有原位暂停/续播和维护预约 |
| 录音条 | 紧凑播放/时长/活动状态；右侧 … 与右键共用操作浮窗 | 重复点击播放所有权回归通过 |
| 截图录音 | 选择与渲染均排除录音；不再把只有录音当截图场景 | 历史手动选择 15 项事实保留 |
| 独立预览 | 选择窗口之外的独立可调整大小预览子窗口；双轴滚动、适应宽度/窗口、100%、缩放 | 原历史 Close 根因仍 UNKNOWN；新的 native Close 待统一验收 |
| 后续功能 | PDF、Word、拖动 Seek、整条语音重新生成仍需独立格式/行为合同 | 未冒充已实现；不是本轮可靠性或 P9–P11 的隐藏交付 |

## P9 / P10

P9 已实现文档切分/向量配置与有界请求、来源和凭据再验证、带恢复记录的索引发布、真实聊天的多路检索及 RRF、按 source-turn 的聊天记忆索引、错误可见的检索状态。没有用长记忆摘要冒充原始历史索引。

P10 已接入官方完整长期记忆运行时：Episode/Arc/Era、Archive/HEAD、Gap/backfill、压缩决定、编辑/版本历史/恢复、手动与自动维护、源变化修复和取消排水。使用共同的 sharedCore 权威实现；Prompt 文本与顺序未趁机改写。曾发现的旧会话缓存覆盖竞态已修复，原始 RED 留存。

## P11

- `.cbsave`：legacy 1–7 与 v8 检查、列表、导入、导出、创建和事务恢复；图片原图/压缩/不含图片，录音独立开关；当前记忆和 RAG 保留。消息/RAG 流式处理，不一次保留全部正文。恢复前自动保全当前会话。
- 会话复制与重新绑定：新会话身份、消息映射、草稿、版本/source-turn 和录音锚点；旧卡仍存在时也可选新卡，附加世界书可重绑，保留原格式卡，明确重置世界书计时并提示头像/名称呈现变化。
- `.cbbackup`：复用选定官方 1.4.5 的 v1 envelope、Tink 1.23.0、ZIP 元数据/清单/哈希。五份权威实现仅改 package 与末尾空行，逐文件证明见 `selected-official-format-proof.json`。正式上游基线仍为 1.4.1。
- 整应用导出、离线检查、确认恢复、准备下次启动替换已接入 Data。保留原备份和 old/new 恢复记录，源、暂存、身份或版本不符时拒绝。自动备份先 stop/join，再音频预约/确认关闭，再数据独占；不产生 audio → scheduler → audio 的等待环。
- 恢复预约之前不替换当前数据；预约原子持久化时封闭新写入并提示正常退出。下次持有数据根锁、且创建任何仓库或后台任务之前安装。中断安装回滚；未知外来字节不清理。提交后记录仍保留。
- 密码输入不持久化，交给服务的密码字符缓冲区用后清零。所有真实 API 凭据不进入普通导出；含非空凭据的输入在明文落盘前拒绝。未接入 SecretStore 导入接口。离线检查通过不等于授权安装凭据。
- Desktop 私有状态明确区分；未知 store/字段、未来版本、无法映射资源及超出预算的输入拒绝，不静默丢弃。原始归档保留。
- Preview 最低更新发现：只处理指定 Desktop 仓库的 `desktop-v<semver>`，支持 Preview/稳定版对比、日期、说明和严格白名单官方页面；一次检查最多一次实际 HTTP 请求，不隐式重试，不自动下载/安装。

## 实际验证

最终去重覆盖：Desktop **39 套件 / 340 项**，sharedCore **41 套件 / 206 项**，均 0 failure/error/skipped。按同一套件最新一次运行取值，不将重复执行累加。明细在 `p11/final-suite-ledger.json`。

- 第 46 阶段：Desktop 37 套件 / 318 项；共享层 41 套件 / 206 项。
- 第 47 阶段：最终草稿/阅读位置排水与取消密码清零回归，6 套件 / 70 项；其中与第 46 阶段重叠的套件替换旧结果。
- 第 48 阶段 Android `compileDebugKotlin` 通过，仅兼容编译，没有 APK 安装或设备测试。
- JDK 17、offline、单 worker、低优先级、in-process Kotlin；依赖首次缺失时第 34/35 阶段仅下载固定 Tink/Gson 依赖，原始日志保留。此后测试离线。
- `git diff --check` / staged diff check 通过。新包在独立构建目录生成，真实 API 请求 **0**。没有把 mock 测试写成真实服务 PASS。
- 全部原始失败、XML、日志见 `p11/test-ledger.json` 和各阶段目录。包含初始编译失败、无效测试夹具、Windows 深路径身份读取问题，以及第 42 阶段旧 Shell 测试参数、第 44 阶段 JUnit 返回值签名失败；修复后 GREEN 另存。未篡改第一次结果。

## 已知边界与统一 Gate

工程可进入统一 Preview Gate 准备；不宣称 P11 已由用户正式 ACCEPTED，不解除 Post-P8 HOLD，不发布。

候选包使用与恢复边界见 `PREVIEW_CANDIDATE_NOTES.md`。这是一份统一交付说明，不要求用户现在重新逐项执行碎片化验收。

仍为 **NOT TESTED**：新统一包的 Windows 核心 UI/听觉确认，真实 Android 1.4.5 `.cbbackup` 双向迁移，真实 Android `.cbsave` 往返，用户设备上的整应用退出/重启恢复，Windows 安装/卸载与 N→N+1 数据和凭据保留，真实 Provider smoke。无法执行这些项目不算产品 FAIL。

支持范围明确有界：SaveSlot 检查沿用 legacy 单 JSON 1 MiB、v8 256 MiB 输入/64 MiB 展开/单项 16 MiB 等限制；整应用归档 512 MiB/40,000 文件、单文件 256 MiB、实体 JSON 8 MiB。超限/不支持状态明确拒绝，不能据此宣称任意历史全量归档都可迁移。当前未知后续域（例如尚未共享支持的 Moments 数据）会拒绝整次恢复，保留原件。实际 Android 完整兼容必须由 Gate 提供文件与设备证据。

四份用户文件始终只检查路径，KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED。没有读取真实用户根或真实 SecretStore，没有真实迁移/恢复，没有合并 desktop、同步上游、修改 baseline/parity/CURRENT 或发布。保留所有历史 RED、SKIPPED、INCONCLUSIVE、Studio credential error OPEN、QQ native sending BLOCKED、WIN-AUTO-001/WIN-BRAND-001 和完整 bounded 非绿色记录。

## 用户对流程的明确意见（同步给 Project）

用户明确表示：“我不能接受每次验收就测试这么点东西”“我的人工验收必须是我核心关切的地方”“我可以允许出bug，但是我要求的东西没有达成就让我验收是不允许的”。问题在于核心 UI 尚未兑现时反复安排零碎验收，消耗了用户时间，不能用少数技术检查替代产品交付。

后续应围绕这份统一包和用户核心需求集中验收。一般疑点留到 P11 统一验收，不用已失败且不可靠的 Robot 方法拦进度；测不到应保留 NOT TESTED。用户已撤销“每个小阶段等 Project 审核”的工作流程，本轮按授权连续完成工程、自检和修复，没有再索取重复授权。

参考：选定官方 [1.4.5 备份源码](https://github.com/SaltyFishOTL/ChatChatBar/tree/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup) 与 [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases)。这些引用支持格式/发行元数据来源，不替代真机验收。
