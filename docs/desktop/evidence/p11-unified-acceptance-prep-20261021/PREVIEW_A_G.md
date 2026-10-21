# 正式 93 政策 A–G：统一验收准备映射

Authority：`docs/desktop/93_PUBLIC_PREVIEW_POLICY.md`，包括 D-038 G 项及 D-041 B 项；`95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md`。政策的旧“未实现”描述不抹去已审查生产实现，但本轮不修改政策原文或自动晋升门槛。

控制点 `ec2b3a8e52b0d5ad6bb8bb78126266f78e77d141`；生产 `70b5c30c91439e79728bf224c1a44d35b25cf733`；Project 提供的裁定为 QUALIFIED ENGINEERING ACCEPTED。以下均是准备与证据映射，不是正式 Gate PASS。

| Gate | 可使用的工程/合成证据 | 真实 Windows 状态 | Android 真机状态 | 尚缺内容 / 门槛状态 |
| --- | --- | --- | --- | --- |
| A 持久化、损坏、恢复 | 本轮 8 套件/72 tests；冻结 JAR 合成链路覆盖存档创建/复制/删除/恢复、部分提交失败回滚、完整备份、另一 JVM 的 StartupGate 恢复、根锁重获。旧 546 项和复审 141 项证据原样保留。 | 在真实 Windows OS 上运行无 GUI Java/native 文件与锁操作；**不是** Launcher/GUI 主窗口退出或用户数据恢复证据。 | 未测试。 | GUI 退出/重启、真实安装环境恢复与真实中断矩阵仍未测试。原始来源/日志保留。A PENDING。 |
| B 安装与升级、最低更新发现 | 新 MSI/EXE，内容逐项匹配冻结便携包；既有 DesktopUpdateCheckerTest 可复用（可信 URL、渠道、版本、无隐式下载/重试）。 | 未安装、未升级、未卸载、未启动 GUI。静态安装器检查不等于实际行为。 | 不适用 Desktop 安装器。 | clean install、真实 N→N+1 数据和合成凭据保留、卸载说明、应用内更新发现 UI。当前同版 `0.1.0` 候选不能冒充版本升级。B PENDING。 |
| C 单会话跨端 `.cbsave` | legacy 1–7/v8 合成材料；生产创建/导出/复制/恢复包含图片、录音、RAG、当前记忆及草稿，普通删除共享资源边界已测。 | 新材料已生成，GUI 导入/继续聊天未测试。 | 未导入、未继续聊天、未导出回 Desktop；未读取任何真实 Android 归档。 | 必须完成正式 Desktop→Android→Desktop 真实往返及受支持媒体/记忆内容核对。C PENDING。 |
| D Secrets / 隐私 | 生产 ordinary export 排除凭据；复审明文暂存生命周期测试；本轮新 profile 只写合成 DPAPI 凭据，实体 API key 为空，凭据目录不进入材料/哈希/证据包。fake 不转发。 | 本轮仅合成 DPAPI 写入/读回验证；没有读取真实 SecretStore。 | 无凭据接触。 | 强制终止可留明文暂存，恢复日志按合同保留；没有获准加密凭据迁移。support bundle/真实故障披露仍要按实际场景验证。D 不自动 PASS。 |
| E 可支持性 | 精确版本/生产 commit、OS资源、命令/退出码、原始 XML、包清单、fixture清单、恢复流程与集中清单。日志不包含真实聊天/凭据。 | 新包信息页/用户错误反馈可用性仍待本次统一验收。 | 未测。 | 用户可找到根目录/备份/恢复路径的实际体验及适当故障信息。E PENDING。 |
| F 发行真值 / 兼容 | 生产与控制点、1.4.1 baseline、1.4.5 选定格式来源、兼容表、P12–P17 未完成边界均保留；D-016 决策记录已存在。 | 本地候选未发布。 | 无新版上游设备核验。 | 本轮未做最新 upstream drift 网络调查；正式发布前需重新分类影响并确认 D-016 原始授权证据。未打开受保护附件来猜授权内容。F PENDING。 |
| G 官方1.4.5全应用迁移 | 既有格式5/5核对；本轮生产 encrypted/plain `.cbbackup`、独立 JVM scheduled→recover、失败回滚；37/39官方类型已登记。 | 合成 Desktop→Desktop恢复及媒体身份检查通过；Launcher/GUI reopen/use 待测。 | 真正 Android1.4.5 归档→Desktop及反向导入均未测试。 | Moments两类、未知字段/资源及不符合凭据排除规则的输入继续拒绝，原归档保留。D-038全量跨端继续 PENDING，安全拒绝既非数据损坏 FAIL，也非兼容 PASS。 |

## 证据来源的界限

- 新自动化：`raw/02-service-regressions/` 原始 JUnit；`raw/03-*` 独立 Java 进程退出与 production-class-origins；`integration/` 各步断言。
- 实际包：`installer-build-result.json`、`payload-verification.json`。MSI 与最终 EXE 的嵌入 MSI 均离线解包检查，未运行安装器。
- 既有已审查工程：`../p11-independent-review-20261021/` 与 `../p11-continuous-20261021/`，没有改写或重复冒充本轮执行。
- 既有用户 Windows 反馈属于当时包；新生产的核心 UI 仍需用户亲自集中确认。历史 Screenshot Close 根因 UNKNOWN，不因这次无 GUI 测试改变。
- 没有 Android 真机、真实 provider 或实际安装升级证据；NOT TESTED 不标 FAIL。

## 风险保留

本轮的独立 JVM 恢复属于正常进程退出后的持久化恢复，不是断电实验。失败回滚通过明确的合成写入失败注入完成，不声称重现物理磁盘耗尽或 OS 崩溃。受保护凭据仅为无效、不会被真实 provider 接受的合成数据，测试密码只用于本轮公开合成归档。

真实私有数据迁移、凭据转移、Android archive 访问、安装升级和 GUI 启动各有独立授权边界；没有因工程裁定或准备完成而获得隐含发布许可。
