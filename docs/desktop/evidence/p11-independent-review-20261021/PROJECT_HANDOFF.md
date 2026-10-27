# P8–P11 独立复审问题处理交接

## 结论与检查点

本轮按 REVIEW-01 → 02 → 03 → 04 处理，没有重做 P8–P11，没有安排零散人工验收。

- 起点/此前证据：`e436f4223c70cf14d99ac84dfc6c128efda38249`；此前生产：`c609e757cca94dca20015e91279c9629e5782ea6`。
- 本轮生产：`70b5c30c91439e79728bf224c1a44d35b25cf733`，直接承接上述起点；分支仍为 `feature/phase8-fish-audio`。
- 正式基线仍是 ChatBar 1.4.1；只读参考官方 1.4.5 格式，不同步 upstream、不合并 desktop、不发布 Preview。
- REVIEW-01、REVIEW-02 为 CONFIRMED，已修复确定的工程缺陷；REVIEW-03 兼容边界仍存在；REVIEW-04 保持诚实说明。
- 最终按套件最新结果去重：12 个 Desktop 套件 / 141 tests，0 failures、0 errors、0 skipped。另有 3 个共享套件 / 14 tests 为 Gradle FROM-CACHE，明确不计入新执行的 141 项。
- 既有 546 项最终证据及历史 RED / SKIPPED / INCONCLUSIVE 未修改。P11 完整 CLOSED / ACCEPTED、D-038 全量跨端兼容和 Public Preview Gate 均不因此改为通过。

## REVIEW-01：明文暂存生命周期

**CONFIRMED。** 原始实现的加密导出会自检并留下展开条目；成功检查还保留冗余 `validated-payload.zip`。合成密码备份的两项原始失败见 `raw/01-plaintext-lifecycle-red/`。没有使用真实用户聊天、凭据或真实数据根。

修复后的生命周期：

| 路径 | 处理 |
| --- | --- |
| 检查成功 | central directory、清单、大小、哈希、CRC 校验完成后立即移除冗余解密 ZIP；仅保留用户确认恢复所需的 Prepared 条目。 |
| 检查失败/取消 | 删除本次创建且身份仍匹配的源副本、ZIP、条目和目录。原始外部归档不动。 |
| 替换检查/取消恢复/面板离开 | 释放旧 Prepared。操作进行中离开面板，待该应用所有的操作安全结束后释放，不中断已授权恢复事务。 |
| 导出成功/失败/取消 | 释放快照、自检 Prepared、输出暂存；最终归档采用新文件发布，不覆盖已有文件。 |
| 恢复准备成功 | 事务日志已独立复制所需 old/new 后，释放映射工作区和 Prepared。后续恢复不再引用这些暂存路径。 |
| 恢复失败/重试 | 映射工作区释放；仍在面板中的有效 Prepared 可继续重试，取消或离开后释放。未确认完成的事务日志不清理。 |
| 正常退出 | 原有应用关闭链调用 Controller.closeAndDrain，等待操作结束并释放检查文件；组件生命周期回归通过。未重新启动 GUI 验证。 |

`DesktopBackupScratch` 只登记本次创建的对象及 native file identity，删除前验证该对象和已登记祖先身份；只对确切路径执行非递归删除。未知子文件或身份替换会阻止清理并显示“清理尚未确认”，不会为清空目录而递归删除。取消发生在调度前、物化后、音频排水时均有定向回归。

映射 MP3 会通过 `DesktopVoiceAudioStore` 创建目录、partial、MP3 和 receipt，因此增加可选创建回调，把这些对象归属于本次映射暂存。普通音频调用仍采用空回调，现有 native close、normal lease、维护预约、自动备份 busy/deferred 语义未改。

**剩余边界：** 这不是磁盘安全擦除，也没有新增强制终止/断电后的持久化所有权账本。进程被强杀或崩溃可留下本次暂存；历史暂存不自动扫描删除，身份不明的残留也不自动处理。需要恢复的 old/new 日志可能含私人内容，按既有恢复保全合同继续保留。不能把“正常生命周期清理通过”扩写成“任何故障后磁盘绝无明文”。

锁顺序维持：服务 mutex → 原有 normal 或 audio maintenance → exclusive；恢复先等待 scheduler，再申请 audio maintenance，避免反向等待。暂存释放在服务 mutex 下执行 NonCancellable IO，不请求从 normal 升级 exclusive，不访问恢复日志。未发现新锁环路。

## REVIEW-02：删除 SaveSlot

**CONFIRMED。** 起点没有 Desktop 删除入口。本轮增加列表“删除…”及二次确认、取消、Controller action 和 Service.delete。没有把仅缺少功能伪造为一次曾执行的运行失败测试。

- 删除目标必须属于当前会话；在原有会话 reservation、数据协调、音频排水和事务提交路径中执行。
- 先有界扫描完整存档目录，校验轻量头、版本、包引用及字节身份；提交前在 exclusive 内重新验证整个目录引用证明。
- 只移除选定目录记录。其 package 仅在没有任何其他存档引用时删除；共享包保留至最后引用被删除。
- 图片、音频及其他媒体文件不加入删除集合，原始导入 `.cbsave` 不动。
- 不认识的目录项、坏记录、未来版本、晚到引用、跨会话请求均拒绝操作，不猜测独占关系。
- 原有 journal 仅增加严格限定的“包 old → null”分支，必须同时有对应删除的存档记录证据。普通媒体仍不允许删除；失败回滚、不确定提交封存和重启恢复保持原约束。
- 成功删除后恢复日志副本仍保留；确认文案已明确说明，不承诺安全擦除。

覆盖 v8 独占包、共享包最后引用、legacy 1–7、当前 live 图片/录音保留、提交失败回滚、未知字节保全、晚到引用和取消排水。Windows 列表确认按钮尚未进行人工点击，不以 JUnit 冒充 USER PASS。

## REVIEW-03：官方 1.4.5 兼容范围

详见 `REVIEW_03_COMPATIBILITY.md` 和 `registry-comparison.json`。官方 39 类，Desktop 注册其中 37 类，另有 3 类 Desktop 额外类型。

- `moment_posts`、`moment_tasks`：仍拒绝，保留原归档；没有已获证的不透明保留容器，未注册空壳或丢弃未知字段来绕过拒绝。
- 当前 credentials 排除规则不放宽；未向 Windows SecretStore 导入任何凭据。preferences 对应字段要求 null，空字符串也不作为迁移许可。
- 确认并修正一个诊断分类遗漏：`retrieval_model_config` 不在所选官方注册表内，纳入 Desktop-only 提示，不能暗示 Android 可接受。
- 37 类已实现路径不等同完整跨端接受；严格 schema、引用、媒体和资源预算仍可能拒绝具体官方归档。没有真实 Android 往返证据。
- 文档给出未知域无损保留及凭据迁移的安全方案边界；未获批准的策略没有实施。

安全拒绝既不是数据损坏 FAIL，也不是全量兼容 PASS。D-038 继续 PENDING。

## REVIEW-04：Prompt Inspector

不需要本轮代码修改。保留 `DesktopPromptInspector` 的说明：“只读预览不执行长期记忆校验与维护；真实发送时会重新校验并组装 Archive / HEAD。”

只读可确定部分、实际发送才确定的记忆/检索及最终 API 序列化真值仍分开。未修改任何官方 Prompt，也未用 Inspector 结果证明真实请求等价。

## 原始证据与验证范围

| 阶段 | 结果 |
| --- | --- |
| 01 plaintext lifecycle | 8 tests，2 failures；原始明文暂存 RED。 |
| 02 focused | Kotlin 编译失败，括号错误；没有产生新的测试通过结论。原始日志保留。 |
| 03 focused | 12 Desktop 套件 / 139 tests 全绿；共享 14 项缓存复用单独标注。 |
| 04 cancel/audio scratch | 5 个相关套件重新执行，补齐调度前取消及映射音频暂存清理。 |
| 05 panel departure | sandbox 拒绝资源指标读取，测试未启动；记录为环境预检失败，不算产品测试失败。 |
| 06 panel departure | 获准环境中执行 4 个相邻套件，面板操作中离开、关闭生命周期、数据协调及音频维护通过。 |

所有阶段和最新去重套件见 `TEST_RESULTS.json`；原始日志/XML/资源/命令的字节 SHA-256 见 `RAW_FILE_SHA256.json`。`production.diff` 是起点至生产提交的真实 diff，`CHANGED_FILES.txt` 列出文件。JDK17、offline、single worker；没有默认重跑历史全套，没有 Android 自动测试或真实 API 请求。

仍未验证：新包 Windows GUI、人为强杀/断电后的暂存收尾、真实 Android 1.4.5 全量往返、凭据跨平台迁移、Moments 不透明域保全。没有用户视觉/听觉签署的新项目。

## 文件、包与保全

源码修改限定为 Desktop backup/archive/mapping/controller/panel、SaveSlot 删除链及其 journal、映射音频创建回调、直接相关测试和三份项目技能说明。官方 Prompt、sharedCore、Entity/schema、Main/Window Chrome、音频维护实现、baseline/parity 未改。

新 Windows 包在独立 `p11-review-fixes-20261021` 目录制作；完整身份另见 `PACKAGE_VERIFICATION.json` 和 `package-manifest.json`。不启动、不安装、不覆盖既有统一候选或历史包。

- 运行目录：`H:\ChatChatBar-Desktop\app\desktopApp\build\p11-review-fixes-20261021\windows-frozen-70b5c30c\runtime-image`。
- 文件：217/217，逐项路径、大小和 SHA-256 一致，missing/extra/mismatch 均为 0；portable ZIP 也逐项解压流校验 217 个文件。
- Manifest SHA-256：`841924831a065c080e502860bdd4631a9d919975438414b8b474b16ed4add71c`。
- Portable ZIP SHA-256：`29997ff60a217665d8cf2a3385eb22397ee8032e0b47fd883e7ca5fa0a4c1351`。
- 包内 build identity 为本轮完整生产 SHA；`0.1.0-preview.1` 为现有候选版本标签，不是发布授权。本轮没有重新制作 MSI/EXE 安装器。

四份用户文件保持 KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED；仅核对路径状态。真实数据、SecretStore、旧 R3 数据根、原始恢复证据均未访问或清理。

本轮没有观察到新增数据损坏或真实凭据暴露。风险收敛为上述异常终止残留、恢复日志的既有保留政策及未完成的跨端兼容证据；不以缺少测试代替 FAIL，也不隐藏尚未验证事项。

提交只推送现有 feature 分支。交回 Project 独立审查后 STOP，不自动解除 P11 / Preview 门槛。
