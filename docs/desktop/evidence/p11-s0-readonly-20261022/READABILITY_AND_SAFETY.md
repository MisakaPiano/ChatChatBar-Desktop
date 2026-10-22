# S0 真实可读范围与安全边界

`FORMAT_READABLE ≠ RESTORE_SUPPORTED ≠ FULL_PORTABILITY_ACCEPTED`。

## 结果类型与入口

新内部生产API：`DesktopBackupReadOnlyInspector.inspect(Path, CharArray?) → ReadOnlyInspection`。构造时不接收数据根、SecretStore、workspace或恢复service；没有Prepared/files/可调度计划。`restoreSupported`、`fullPortabilityAccepted`始终false。v1符合基础格式时只提示 `REQUIRES_EXISTING_V1_PREPARATION`，仍必须通过原inspect/映射/图验证/用户确认。

本轮没有GUI接线或新恢复按钮。现有Data页面/服务的inspect、scheduleRestore及v1导出行为不变；现有入口看到v2仍拒绝。S0可由后续获批展示层消费，不能把本结果强转/伪造为Prepared。

| 输入 | S0只读检查 | 现有恢复 |
|---|---|---|
| v1、code84、无scope | 严格认证及结构检查；已知实体schema检查 | 仍需原有完整路径，不由S0批准 |
| v1、code85/86、无scope | 可检查 | UNSUPPORTED，原恢复版本上限84不变 |
| v2、code85/86、有合法scope | 明文/加密均可检查 | UNSUPPORTED，禁止prepare/schedule |
| v2/code84、跨Envelope/Manifest、未知版本 | 拒绝身份声明 | 拒绝 |
| 其他code（含本轮未覆盖的更早版本） | S0拒绝，不猜测 | 既有v1入口的历史规则不被修改 |
| 未知metadata/scope字段、非法选项/数量 | 拒绝 | 拒绝 |
| 已登记实体的未知字段 | 拒绝schema声明 | 不放宽原有serializer |
| Moments/未登记实体 | 只能识别归档条目及严格JSON，计入uninterpretedEntities；unknownContentMayContainSecrets=true | UNSUPPORTED；不验证/保存/运行Task |
| 非空已知凭据 | 只返回类别集合，不回传值或可恢复对象 | UNSUPPORTED；现有外来凭据整包拒绝不变 |

## 有界、无解密磁盘暂存

S0源文件与完整解密ZIP各限64 MiB，累计展开限128 MiB，单个JSON限8 MiB，条目数沿用40000+3。只支持ZIP32、单磁盘、store/deflate；ZIP64或超限fail-closed。该上限独立于原v1恢复能力，不是全量大档案兼容承诺。

先通过安全源文件读取把输入限额/文件身份固定到内存；解密完整消费到EOF后才校验中央目录/所有条目/CRC/inventory/hash/manifest。没有明文文件、解密scratch或live写入。可控byte buffers在finally擦除；JVM不可变String/Json树依赖GC，**不声称内存取证不可恢复或秘密已被安全擦除**。

取消和错误只返回固定stage码，不附解析器cause，不回传输入文件名、源根、appVersionName、会话ID、聊天正文或Prompt。credential分类涵盖Preferences、Model/retrieval、Embedding/alias、AppSettings以及其他同名已知key；这不是任意正文的秘密检测器。未理解结构和嵌套包的风险不被伪装为credential-free。

## scope解释

按固定官方字段检查globalOptions、角色/会话计数、媒体计数范围、Moments/Studio选择与已存在家族。精确session default+overrides展开时核对所有session ID和三类计数；显示SELECTED_EMPTY、SELECTED_PRESENT、NOT_SELECTED。旧scope没有详细choices时保留声明数量并显示LEGACY_UNSPECIFIED，不推断它明确选空或允许删目标。

`momentsImages`缺省为true。图片、语音、SaveSlot选择互相独立；当前聊天图片省略不把存档包的图片计为冲突。模型、Embedding、FormatCard、WorldBook即使不在globalOptions仍可存在，返回实际记录数，不能据开关宣称依赖密钥被省略。

不做S1：不证明资源引用图、依赖闭包、v8嵌套ZIP或Memory/voice业务关系。报告中三个verified标志均false。归档层的inventory/hash通过，不等于嵌套SaveSlot可打开或完整平台互通。格式范围外的内容不静默丢弃或导出；源档原样保留。

## 未发生的操作

没有读取真实用户根/档案/Token，没有SecretStore实例，没有Windows GUI、Android或安装，没有网络provider调用、C1/C2、Moments封存域、Task激活、合并模式或更新器移植。正式baseline、D-038目标和P11/Preview HOLD未改变。
