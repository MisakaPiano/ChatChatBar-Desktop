# P11 统一验收准备报告

## 状态

**PREPARATION READY / GUI NOT STARTED / INSTALL NOT EXECUTED。** 本轮准备完成，未签署 P11 ACCEPTED、Post-P8 HOLD 解除或 Public Preview Gate PASS。

Project 已提供 `70b5c30c` / `ec2b3a8e` 的 QUALIFIED ENGINEERING ACCEPTED。本轮控制点为 `ec2b3a8e52b0d5ad6bb8bb78126266f78e77d141`；冻结生产为 `70b5c30c91439e79728bf224c1a44d35b25cf733`。分支仍为 `feature/phase8-fish-audio`。没有修改生产 Kotlin、sharedCore、官方 Prompt、Entity/schema、基线、parity、Main/Chrome 或音频维护机制。

工作目录：`H:\ChatChatBar-Desktop\app\desktopApp\build\p11-unified-acceptance-prep-20261021`。

## 同源便携包、MSI、EXE

为避免把证据提交冒充生产身份，本轮从已核验的 `windows-frozen-70b5c30c/runtime-image` 复制独立输入镜像，使用 JDK17 jpackage 重新封装 MSI/EXE。没有重写 build identity。打包前后原件及输入副本均复核 217/217 路径、大小和 SHA-256。

| 形式 | 路径 / 身份 |
| --- | --- |
| 便携运行目录 | `app/desktopApp/build/p11-review-fixes-20261021/windows-frozen-70b5c30c/runtime-image`；217 文件，未覆盖。 |
| 便携 manifest | SHA-256 `841924831a065c080e502860bdd4631a9d919975438414b8b474b16ed4add71c`。 |
| MSI | 新工作目录下 `01-installers/msi/ChatChatBarDesktop-0.1.0.msi`；165507755 bytes；SHA-256 `08b1923201f67e83792d89d7f64f1b49ae30794ba1f45157be7bb5860ff8fc46`。 |
| EXE | 新工作目录下 `01-installers/exe/ChatChatBarDesktop-0.1.0.exe`；166063104 bytes；SHA-256 `1a5b63a1ca0b40b8807fde393ae45a91e20200188e5505d60cb6040cdf9b3266`。 |

应用版本均为 `0.1.0-preview.1`，安装器数字版本 `0.1.0`，应用内 commit 均为完整生产 SHA。没有变更版本号以伪造 N→N+1。

MSI 离线解包；EXE 从最终 PE 的 MSI 资源中提取实际内嵌归档，再离线解包。两种安装载荷各 **216/216** 路径、大小、SHA-256 与便携内容一致；仅不安装便携包中的 `app/.jpackage.xml`（jpackage 工具状态元数据），无额外应用文件。详情见 `payload-verification.json`。

WiX dark 对安装 UI 表发出 decompile 警告，日志完整保留；本次文件内容核验不依赖反编译 UI 完整性。**没有运行 MSI/EXE，没有验证实际安装、升级、卸载、SmartScreen 或安装后启动。** 本地工程候选不等于可公开发布。

## 全新合成环境与材料

- `environments/synthetic-source/app-data`：自动集成源，保留其原始数据和恢复日志。
- `environments/manual-ready/app-data`：通过独立 JVM StartupGate 从加密归档恢复的新目标；随后添加长合成录音作为集中人工使用根。
- `environments/rollback-only/app-data`：独立失败注入根，验证完整恢复中失败后旧数据保留；不得作为人工启动根。
- `profile/Local`：新隔离 profile，bootstrap 指向 manual-ready；WindowsSecretStore 仅写入一份随机、无真实 provider 效力的合成模型凭据。未访问真实 profile。凭据值未输出、未哈希、未提交，保护目录不进入材料清单。
- `launch-admission.json`：新许可记录，`guiAuthorized=false`、`installAuthorized=false`、`privateMigrationAuthorized=false`、`consumed=false`；没有借用历史许可，没有自动启动脚本执行。
- 自动备份关闭；离线模型仅指向 `127.0.0.1:18793`。loopback fake 自检后正常关闭，不转发外网，不提供付费 API 代理。

人工根包含：3 个会话、13 条消息、1 张双角色卡及图片/文档、8 条有对应文字的录音、3 份 SaveSlot、3 个 RAG chunk、2 个记忆节点与2份状态。主会话有4条 **31.608 秒**录音，来自历史已验证的合成 MP3（SHA-256 `fb93b911ab31c264881df8f167cbb655bb42ddcd148fa372e5363cf09e3dfd94`），经生产音频存储重新落盘并验证 owner。旧材料不动。

`materials/` 包含：

- `complete.cbsave`、`manual-ready.cbsave`；legacy 1–7 合成 `.cbsave`。
- `complete.cbbackup`（无密码）、`encrypted.cbbackup` 和 `manual-ready.cbbackup`（有密码）。
- 测试密码为 `synthetic-unified-only`，仅用于公开合成数据，不用于用户私人备份。

`exports/png`、`exports/mp3`、`exports/cbsave`、`exports/cbbackup` 为全新输出目录。完整材料清单及哈希见 `fixture-manifest.json`。所有录音均有文字，不再构造“只有录音的回复”作为截图需求。

RAG/Memory 材料的精确范围：有合成聊天和文档向量、当前 legacy-reference 快照及 HEAD；不是实时模型生成的 Episode/Arc/Era，不代表真实 embedding 或长记忆生成质量。Alice 具有音色绑定，Bob 仅作为第二角色；不假称已完成双真实音色生成验收。

## 无需真人的实际验证

本轮重新执行 **8 套件 / 72 项 JUnit，0 failures、0 errors、0 skipped**，日志显示 test 执行而非 FROM-CACHE。范围为 SaveSlot service/archive、full backup archive/mapping/service/journal、session journal、StartupGate。没有无意义地重跑所有历史阶段；旧 546 项和复审 141 项作为已有证据保留。

独立辅助程序使用冻结 runtime-image JAR 放在 classpath 首位；JVM class-load 原始记录确认实际 SaveSlot、FullBackup、StartupGate 类来自 `windows-frozen-70b5c30c`，而不是把新测试构建的证据 commit identity 当生产包。

| 进程阶段 | 实际验证 |
| --- | --- |
| seed-and-integrate | 创建/导出完整 SaveSlot；复制后源清单不变，媒体/RAG/当前记忆可用；删除独占存档；部分 session commit 失败后原有效状态恢复；正常重试恢复；普通及加密完整备份导出；临时检查目录释放；图引用验证。 |
| schedule | 新目标根检查加密归档，安排恢复，确认 RESTART_REQUIRED；日志持有独立恢复副本，Prepared 清理；进程正常退出。 |
| recover | **另一个 JVM** 运行生产 StartupGate，根锁先取得、恢复先于业务仓库打开；3会话/消息/媒体/RAG/记忆可读；pending结束；退出后重获锁。 |
| rollback | 独立合成根注入完整恢复中的写入失败，旧 app settings 字节一致，新会话未部分留下，事务证据与源归档保留。 |
| manual-enrich | 长录音经生产音频 owner 保存，自动备份关闭，生成供人工链路使用的新存档和加密备份，引用图验证通过。 |
| final lock probe | 再用独立 JVM 取得并释放 manual-ready 的根文件锁，exit 0。 |

所有进程正常退出0；没有强杀、没有 Main GUI、没有付费 API。此“重启恢复”是分进程持久化边界证据，**不是** Windows Launcher 主窗口 X、安装后启动或断电验收。失败回滚为明确的合成注入，不冒充物理磁盘耗尽。

fake 自检实际发送1次合成 streaming chat、1次合成 embedding及1次health，随后服务关闭；real request 0。日志只记方法/端点/状态，不记录输入正文或 Authorization。

## 环境与保全

准备期资源快照：可用物理内存 2351016 KiB（约2.24GiB）、提交余量8636516 KiB（约8.24GiB）、H盘4021710848 bytes（约3.75GiB）；当次未发现 CCB/Java 残留。该快照不是之后 GUI 启动的有效许可，实际启动前需重新检查，不能把固定保守内存门槛当产品失败。

四份用户自有文件只核对未跟踪路径，KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED。旧包、历史 evidence、真实根、原始恢复日志未修改或清理。新 profile 的保护凭据文件也不加入证据哈希清单。

首次 Python 清点深层合成事务目录遇到 Windows 普通路径长度限制；保留 `inventory-first-failure.json`，使用扩展 Windows 路径完成清点。它是证据工具失败，不是恢复产品失败。未删掉长路径恢复现场来让检查通过。

## 集中验收及尚未验证

核心操作清单见 `UNIFIED_ACCEPTANCE.md`，A–G逐项证据与缺口见 `PREVIEW_A_G.md`。优先完整体验用户关切的菜单/User工具栏、单行Deck、紧凑录音条、独立截图预览，然后连贯完成资料/记忆/存档与会话生命周期、完整备份/正常退出/重启。

尚未验证：新包真实 Windows GUI/听觉、安装升级与卸载、真实 N→N+1 版本对、Android真机 `.cbsave` 往返和官方1.4.5 `.cbbackup`迁移、真实 provider、断电/强制终止矩阵。Moments和凭据迁移边界仍在，不提升D-038。发布前最新upstream影响与D-016原始授权证据仍需确认，不访问受保护附件猜测内容。

本轮完成的是一套集中验收准备。下一步在取得对应明确授权并通过即时检查后，才启动/安装；不得把本报告变成 P11 或 Preview Gate PASS。
