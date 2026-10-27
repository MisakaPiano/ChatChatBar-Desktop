# P11 跨平台互操作工程交接 — 请求 Project 独立 Code Review

日期：2026-10-22 JST。基于证据 HEAD `aeac5bf02eff23b2712431b6206e3f088bf71a15`、分支 `feature/phase8-fish-audio`。本报告不签署 P11 ACCEPTED，不解除 Post-P8 HOLD，不签署 Public Preview Gate。

## 结论与范围

**合成 `.cbsave` 已完成真实 Android 运行时往返及双方续聊的工程验证。完整 `.cbbackup` 互通仍被明确的产品/安全边界阻断，真机验收仍为 NOT TESTED。**

- GUI R1 限定接受保持：验收生产 `d95501f87899be47d07537132793afc90b9f4acc`，证据提交 `aeac5bf02`；遵循原 [GUI 交接](../p11-gui-r1-windows-20261022/PROJECT_HANDOFF.md) 的 USER PASS / 技术 PASS / NOT TESTED / INCONCLUSIVE 区分，未扩大接受范围。
- 正式 upstream baseline 保持 **ChatBar 1.4.1**。本轮格式与运行时参照是官方 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`，不是 baseline 升级。
- 用户决定：**“保持拒绝，先交方案（推荐）”**。Moments 与外来凭据继续整包拒绝；不实施不透明存储、不写 Windows SecretStore，不静默删除未知字段。
- 本轮实际生产改动仅为安全拒绝的分类提示。协议、Package/Entity/schema、官方 Prompt、冻结 GUI 生产均未修改。

## 真实执行结果

| 路径 | 结果与可证明范围 |
|---|---|
| 官方主源码身份 | 378 个主 Kotlin/构建文件与精确 Git object 相同，仅规范化 Windows CRLF；不读取/断言 preset 资产内容。见 `raw/official-source-proof.json`。 |
| Android 应用 | 官方源码本地 debug 构建，API 34 x86_64 新隔离 AVD；主包 SHA 见 `raw/apk-identity.json`。**非官方签名 APK、非物理设备**。 |
| Desktop 创建材料 | 正式 SaveSlot service 导入合成 seed、恢复、保存备选消息版本、create/export；JUnit 1/1，`raw/08-desktop-materials-versions/`。另外分类 2/2、跨进程 3/3。 |
| Android `.cbsave` | 正式 `ChatViewModel.importSaveSlotJson → loadSaveSlot → sendMessage → createSaveSlot → exportSaveSlotJson`，loopback SSE 无鉴权合成续聊；instrumentation 1/1，`raw/15-android-roundtrip/`。 |
| Desktop 返回路径 | Android 实产档案经正式 import/restore，再由 `createRealChatRuntime().sendText` 进行 loopback 续聊；恢复后的真实媒体文件再与 Android 哈希逐项比对；JUnit 1/1，`raw/run-consume-20261021-194829-891/`。 |
| 图与媒体对照 | 原 4 条消息保序，原版本及备选版本完整；Android 续聊后 6 条，4 条录音和 1 张图逐字节 SHA 一致；RAG 1 块及引用保留。见 `raw/roundtrip-graph-proof.json`。 |
| 官方 `.cbbackup` | 官方 Android codec 实产明文/加密最小支持子集，Desktop inspect/verify 成功；Desktop 生成对应子集经官方 extract + `BackupEntityValidator.validateTree` 成功。Android instrumentation 1/1，`raw/11-android-backup/`。**仅 app_settings + chat_drafts 两实体，不是整应用迁移通过。** |
| 明确拒绝边界 | 官方 Android codec 生成含合成凭据、Moments post、Moments task、未知字段档案；Desktop 按安全类别拒绝，原档案字节不变，检查暂存归零。包含于 Desktop consume 1/1。 |
| 受影响回归 | **11 suites / 100 tests，0 failures / 0 errors / 0 skipped**，涵盖 archive、mapping、service、session/full-backup journal、startup，见 `raw/regression-summary.json` 和原始 JUnit。 |
| 进程与收尾 | 新测试 JVM 安排恢复→另一 JVM 应用；写入中途 `halt(71)`→下一 JVM 回滚；损坏 journal→新 JVM 拒绝。独立根锁可重新取得，旧快照和源保留。最后本轮 emulator/ADB server 进程 0；见 `raw/safety-closeout.json`。 |

Android 运行器退出码不等于测试通过；这里依据明确的 `OK (1 test)` 和 JUnit testcase。两项 Android 测试分别执行，不冒称同一回合一次运行两项成功。最终 APK hash 是最后的测试 APK；较早失败/备份回合未单独保留每次 APK hash，其源码差异是测试就绪等待，备份测试方法未变。

## `.cbsave` 数据合同的实测边界

- 消息：id、role、正文、顺序、source-turn 字段、当前版本、两项 alternatives/version IDs 等原始字段对照；新增续聊消息另计。
- 图片/录音：便携资源引用映射到各平台本地所有权路径，媒体哈希一致；录音 messageId/messageVersionId、片段顺序、文本与音色元数据对照。录音 record/anchor IDs 及 restore updatedAt 按正式恢复规则重建。
- Memory：此夹具包含 HEAD 和一个 LEGACY_REFERENCE 节点。节点 ID/会话 ID 重新绑定，根引用同步；内容及其余快照字段保持。官方补齐 source-turn timeline，并把 `memoryWasEnabled` 同步为会话已关闭的值；这两项**分别对返回消息和会话设置校验**，不简单忽略差异。嵌套 Episode/Arc/Era、修订历史、运行任务不能由此夹具宣称全部跨平台覆盖。
- RAG：验证会话块、向量和 message/source-turn 引用。全局文档库、索引数据库、检索模型配置不属于这份单会话档案的迁移证明。
- 草稿：官方 SaveSlot 没有输入框草稿字段。实测 Android 目标已有草稿保留，但不代表 Desktop 源草稿迁移。全应用 `chat_drafts` 子集单独经官方/ Desktop codec 校验。
- 角色、模型、格式卡、世界书定义不能由存档中的引用 ID 自动提供。本轮 Android 目标合成角色和 loopback 模型独立创建；不宣称完整外部库迁移。

## 历史与本轮 RED 保留

所有旧 USER FAIL、RED 和 GUI 截图未修改。新增失败均保留在本目录 `raw/`：

- 01：PowerShell 参数拆分；02：离线缓存缺 Tink 1.23.0；03 后联网只取构建依赖，主包构建成功。
- 04：测试容器关闭 API/配置缓存错误；05 只运行了分类测试，材料生成未被发现，**不算材料 PASS**。
- 06：并行构建缓存锁超时；07：子 JVM 缺 sharedCore classpath；08 修正后 6/6。
- 10：测试抢在官方依赖初始化前访问；13：正式模型配置刷新前即调用 send，返回 false。测试入口修正等待状态，未修改官方生产。
- 首次 Python 快照对照报 `current Memory snapshot` RED：节点 ID、timeline、启用状态差异。源码追踪确认正式恢复/续聊重建规则，最终校验增加对应图与消息约束；没有删除真实数据、隐藏字段或改变生产行为。该首次终端错误在 `raw/16-graph-proof/INITIAL_RED.md` 明确保留。

## 仍未满足的正式接受条件

1. Android 真机/真人集中往返确认：**NOT TESTED**。材料、精确步骤、权限界限见 [ANDROID_ACCEPTANCE.md](ANDROID_ACCEPTANCE.md)。未安装手机、未改手机设置、未访问真实用户数据。
2. 完整官方 1.4.5 → Desktop → 官方 Android `.cbbackup`：**BLOCKED**。Moments、非空凭据、Desktop 专有实体及未知字段仍不能宣称互通。相同 Entity 名称或成功读取加密 envelope 不消除此阻断。
3. Moments 不透明保留、专用凭据迁移或显式子集导出：均需新增产品/安全合同和授权。方案与影响见 [DECISIONS_REQUIRED.md](DECISIONS_REQUIRED.md)。本轮保持拒绝，不将 Token 导入 SecretStore。

## Public Preview A–G 状态

| Gate | 本轮贡献 | 仍需独立执行/裁定 |
|---|---|---|
| A 持久化/恢复 | 100 项受影响测试及新跨 JVM 中断恢复 | 候选级整体数据安全核验与明确 recovery procedure 审核；本轮不能签整体 Gate。 |
| B 安装/升级 | 沿用此前工程与 GUI 记录 | clean install、N→N+1 数据/凭据保留、更新发现/可信下载入口完整候选验证；本轮未安装升级。 |
| C 单会话互通 | 官方 Android 运行时实证及双方续聊 | 真人/实机 `.cbsave` 集中复核。 |
| D 秘密/隐私 | 合成外来凭据拒绝及无载荷错误类别 | 专用迁移策略未授权；候选导出/诊断隐私审计仍独立。 |
| E 支持性 | 可复现材料、错误类别、源码及 APK 身份、日志 | 用户支持文档与候选环境诊断路径最终审核。 |
| F 发布真实性 | 精确格式目标、baseline 不提升、局限披露 | 发布前最新 upstream drift 分类、D-016 权利确认、最终 Release Notes 与签名发行检查。 |
| G 整应用备份 | 官方 codec 双向最小子集及边界拒绝 | 完整数据域和真实迁移/重启、Desktop 专有域及 Moments/凭据决策，当前不能 PASS。 |

请求 Project 对本提交源码、测试真实性、协议边界、证据和当前治理记录独立复审，再决定 P11 是否满足正式接受条件。本轮不自动合并 `desktop`，不公开发布，也不新增 Windows GUI 启动。
