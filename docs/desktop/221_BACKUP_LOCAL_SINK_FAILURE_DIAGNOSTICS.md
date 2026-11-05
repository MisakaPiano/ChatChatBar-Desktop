# 备份准备的本机读写错误分类

2026-11-01；FB20261031-04 的具体备份余缺。公开／日常仍1224，未安装、公开或改真实资料。

## 实际问题

独立现用1224 JAR对照复现：合法明文／加密合成包正常均Verified，但 tentative sink 的entity回调抛AccessDeniedException被归为 `ZIP_CONTENT_ENTITY_SCHEMA`，UI说“当前版本无法识别的数据结构”；media回调同样拒写则被归为 `ZIP_CONTENT_ENTRY`，UI要求核对结构。ZIP包装还丢弃原错误类型和首个程序位置。

回执 `I:/CCB-Desktop-Continuity/updates/20261101-backup-error-probe/RESULT.json` 保留旧行为。早期媒体继承AUTHENTICATION_ZIP／误提示密码的推测没有成立，不登记成已复现或已修问题。

原设计仍然成立：sink只准备不可运行的独立临时资料；完整认证、CRC／清单／图验证和用户选择之前没有提交或启用。格式／安全拒绝不能被降低，错误也不能带出原内容、路径或凭据。

## 改动

- entity／preferences／media回调仅把本机AccessDeniedException、SecurityException及一般IOException映射为固定 `SINK_ACCESS_DENIED`／`SINK_SECURITY`／`SINK_IO`。其他格式、逻辑和资源拒绝沿用原路径；取消照常传播。
- 只保留首个错误的类型名与最多8条应用程序class／method／line，穿过ContentRefusal与ZIP Refusal传到Source.Refusal；不保留原Throwable、message、cause、路径、正文或Key。
- media回调同时读取认证输入。CountingHashInput只记录是否发生过读取异常；若读取已失败，即使consumer把它包装成IOException，也不归为本机准备错误。该标记不持有原异常。
- UI明确提示检查本机工作目录权限、文件占用或可用空间；没有从错误文字猜测“磁盘已满”、具体安全软件或密码。真正schema不兼容、缺密码、鉴权失败、预算和完整性拒绝保持。

## 验证

| 轮次／证据 | 结果与限定 |
| --- | --- |
| 标准RED r01 | 新3项中1过2失败：本地IO／拒写确实误归schema，原失败完整保留。 |
| 标准 r02 | Gradle2m54s SUCCESS，五类32项新执行全PASS，0fail/error/skip：新sink4、Source11、Staging6、Writer9、LargeBackup2。未匹配的Controller测试pattern没有对应suite，不计为已测Controller。 |
| 新sink检查范围 | v1/v2各明文／加密baseline仍Verified，三个回调各拒写共12场景分类／首因程序位置／原字节保全；IO、模拟带磁盘空间私密文字的FileSystemException和Security分类；取消；真实未知schema在回调前拒绝；损坏DEFLATE输入被media consumer包装为IOException仍归输入错误。 |
| 独立新旧对照 | 原样复用同一wire夹具，候选编译JAR的明文／加密baseline及四个entity/media拒写PASS，首因／固定提示成立；`COMPARISON.json`与`candidate/RESULT.json`保留。它是独立诊断classpath，不替代标准Gradle、完整CI、真实磁盘故障或安装验收。 |

测试日志与XML位于 `I:/CCB-Desktop-SmallFixes-20261028/backup-sink-*`。所有合成包原字节保持；未读写用户原备份、现用数据、SecretStore或H盘日常程序。未改Prompt、备份格式、容量预算、恢复提交或凭据选择。

## 交付接续

当前源码需要完整CI、普通冻结候选、隔离生命周期及本版真人验收，不能用先前CI46（d44账户子项）代替。SF12模型／账户限定源码结果保持；SF14两次native capture超时只挂人工像素步骤，不停其他独立工作。候选冻结后如用户暂不在，只挂安装／验收／公开依赖动作，可继续独立P12 Git检查点；不把后续源码混进已冻结候选。
