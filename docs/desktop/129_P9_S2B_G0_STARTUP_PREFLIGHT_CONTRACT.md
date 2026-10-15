# P9-S2B-G0 Read-only Startup Preflight Contract

日期：2026-10-16。**IMPLEMENTATION AUTHORIZED / NOT ACCEPTED。** 起点 `8820462db2a968b10ef59488985edef21d025973`，同一feature分支。本文件只冻结G0最小探测范围，不批准127的生产journal/head/schema，不替代128的未来启动接入授权。

## Scope and results

新增独立Desktop main-source预检器及合成测试，**无生产调用入口**。输入只为已解析绝对root Path、现有ownership证明、固定扫描预算及只读NIO/Windows元数据能力。不得构造AppContainer、storage/repository/settings/runtime/coordinator/snapshot/SecretStore或网络服务。

候选目标常量封装在预检器内部：`entities/document_index_transactions/`。它不是正式注册域。任何存在的域（包括空目录、active/pending/completed/head/history/marker）均阻断；不解析JSON、不读取payload/Entity/文档/配置/凭据内容。

| Code | Meaning |
| --- | --- |
| NO_DOMAIN | 仅证明候选域在当前检查时缺失；entities缺失亦可，不创建目录 |
| DOMAIN_PRESENT_UNSUPPORTED | 发现普通候选目录，协议未批准；绝不表示COMMITTED/READY |
| OWNERSHIP_MISSING | 现有root锁未持有或已释放；不另取锁 |
| UNSAFE_PATH | 类型／别名／link/reparse／身份不可证明；拒绝 |
| READ_ERROR | IO／权限／枚举错误，不当成缺失 |
| BUDGET_EXCEEDED | 探测预算超限，不截断后视为安全 |
| CHANGED_DURING_CHECK | 发现此前观察的身份／目录元数据变化或目标出现／消失 |

CancellationException原样传播。结果只含固定枚举、有限计数及平台证明限制，不含路径、原始异常message或正文。结果不缓存、不授予App business-ready；旧.replace/.backup、坏向量等问题仍由各自安全合同处理，NO_DOMAIN不能绕过它们。

## Read-only path proof

先检查isHeld(root)，逐级NOFOLLOW验证祖先和root，再只枚举必要的root/entities目录名与候选域内元数据。禁止相对／未规范化路径、UNC/device/8.3别名、异常Windows名称；检测相关名称大小写碰撞。验证fileKey、类型、creation time以及root范围内mtime/size，结束前重新检查已观察身份、缺失目标及ownership。出现/消失与类型变换不能被误报NO_DOMAIN。

Windows逐路径GetFileAttributes拒绝reparse并与NIO类型核对；不递归进入其他root子目录，不读取任何文件内容。候选域普通文件仅有元数据／fileKey证据，不能证明外部硬链接不存在：**仍因整个域不支持而阻断**；同域可检测的重复fileKey拒绝。G0不提供对抗任意外部恶意替换的handle-relative完整TOCTOU保证，不宣称所有硬链接或断电语义已解决。fileKey或必要平台证据不可得时保守拒绝。

候选G0探测上限（非官方格式上限）：累计256项、候选域深度4、单名称255字符、累计名称8192字符、累计路径32768字符、祖先64级、普通文件元数据size总和64MiB；checked Long加法／深度计数，逐层有界DirectoryStream.use，留存的元数据同样有界。metadata size预算不代表读取了这些bytes。超限保全原数据。

现有ownership持有者映射不绑定不可变目录句柄；G0补充观察/复核可检测漂移，不能将其升级为全局永久readiness。预检不持新的协调器或后台Job；测试hook及NIO元数据/目录stream seam仅用于确定性故障与关闭验证，不伪造production ownership。

## Validation and delivery boundary

合成临时根先取得ownership再记录文件SHA-256／目录清单；根锁单独记录。正常、拒绝、故障、取消都检查零写入；竞态使用CompletableDeferred/明确hook，不用固定sleep。覆盖无域/无entities、空/未知域、ownership失效、类型/link/reparse、名称/case、预算、权限/删除/身份漂移、取消关闭stream、重复检查及不同旧布局。真实Windows junction/symlink夹具无法建立时明确记录NOT TESTED，不把纯模拟等同实际OS证据。

运行新增测试及现有ownership、实际相关路径/启动生命周期聚焦回归。JDK17、offline、单worker、low priority、in-process、原2GB heap；无clean/rerun/packaging。所有RED独立保留；源码与原始验证证据分开提交。只有此限定范围可称 **CODEX CANDIDATE / PROJECT REVIEW PENDING**。

Main/G1接入、恢复写入、持久writer、多代交接、Flow/cache屏障、真实EXE及完整P9/Public Preview均未授权。正式baseline1.4.1、D-036/D-038和既有F4/P11状态不变。
