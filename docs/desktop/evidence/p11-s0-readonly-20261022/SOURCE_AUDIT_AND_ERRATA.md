# S0 来源审计与追加勘误

2026-10-22。起点 `4c8cbafe6708ef8e84521daa122f6fb7d9d736e6`。仅S0实施；不授权S1–S6。正式baseline仍ChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。

## 固定来源

| 内容 | 官方源码 |
|---|---|
| v1传输与恢复起点 | [1.4.5 AppBackupModels](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt) / [Codec](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt) |
| v2新增协议 | [1.4.6 Models](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt) |
| S0 transport DTO依据 | [1.4.7 Models](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt) / [ExportSelection](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupExportSelection.kt) |
| Envelope/加密测试来源 | [1.4.7 Codec](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt) |
| scope合法性/CRC/inventory | [1.4.7 Archive](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt) |
| scope缺详细选择的旧规则 | [1.4.7 MergeEngine](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeEngine.kt) |

`Official147FixtureCodec.kt`为测试专用官方文件，只有包名、object名称和来源注释改变；不是生产第二套codec。生产复用既有AppBackupCodec密码/流式认证逻辑，通过单独入口允许检查1/2；原decrypting仍只接受1，导出仍为1。BackupInspectionModels的字段/默认值取固定官方transport，51个globalOptions ID来自固定Selection；没有复制Android业务、Prompt、Entity或恢复模式。

新ZIP32中央目录校验是Desktop memory-only安全实现，不能称上游原代码移植。它交叉核对local/central名字、大小、CRC、压缩长度、offset和EOCD；实际解压/CRC由JDK ZipInputStream执行。额外采用有界内存、严格未知metadata字段、重复JSON键及Envelope/Manifest一致性门槛。

## 追加勘误（原历史文件不改）

旧生产合同审计中对官方 `backupJson` 宽松解码的推断错误。固定1.4.5/6/7的 `AppBackupModels.kt` 明确 `ignoreUnknownKeys=false`；因此旧文档涉及未知scope字段被该decoder静默忽略的推论应撤回。S0使用严格decoder，并在解码前拒绝重复JSON key。

不要与 [BackupEntityValidator.entityJson](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt) 混淆：该独立实例确实为true。S0对当前已登记实体仍复用Desktop严格serializer，不借官方entityJson放宽恢复。未登记的Moments/其他实体仅可计为未解释内容；不授予schema、秘密完整性或恢复兼容声明。

所有旧RED、设计文件和原始证据保持原样。本目录是勘误与新S0证据的唯一追加位置。
