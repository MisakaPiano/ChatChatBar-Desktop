# P11 Desktop 专有类型与迁移范围矩阵

**DRAFT / SOURCE-AUDITED / 非完整互操作通过记录**，2026-10-22 JST。

Desktop `8c378e8cf487f6dbcb0df3f3a6f1408028185091`，官方目标 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`；正式 baseline 仍为 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。本轮不修改类型、序列化、导出或恢复。

## 1. 依据和术语

固定源码 [SOURCE_AUDIT](SOURCE_AUDIT.md)：O05/O06/O09/O13/O19/O22，D01–D05/D09–D13；旧实测范围 D15。表中“注册”仅表示 registry 接受该类型名称，不表示任意版本/字段/资源/图均兼容；“保留”不包括秘密默认排除策略下的凭据值。

| 数据域 | Desktop 全量备份当前处理 | 官方 Android 1.4.5 | 当前可声称范围/阻断 |
|---|---|---|---|
| `retrieval_model_config` | D01 显式注册为 ModelConfig，普通导出排除 apiKey；保留检索模型非秘密配置 | O06 未注册此类型，即使其字段像 model_configs 也不接受 | 含此类型的 Desktop 全包不能称 Android 可导入；禁止改名塞进 model_configs |
| `desktop_character_backgrounds` | D01 注册，要求 images/preferred 完整关系；D02 映射图片及 preferred | 未注册 | Android 子集若排除此偏好将损失背景选择语义；只保留共享图片不足以声称无损 |
| `desktop_backup_preferences` | root 内部非秘密偏好 carrier；D02 导出时转为顶层 preferences.json，恢复时重建，显式拒绝重复偏好权威 | 未注册同名 Entity，但接受正式 BackupPreferences | 不能把此 carrier 与前两项一概称为直接导出的专有 Entity。brushSize/brushType 有正式通道，凭据仍按策略拒绝/排除 |
| 共有配置/角色/格式卡/世界书/会话/消息/版本 | D01 注册；D02 路径/模型物理文件名映射，D03 严格校验 | O06 注册相应类型 | 类型交集是候选集合；完整字段、引用、库依赖和实际恢复仍须证明，不能由两实体测试外推 |
| `embedding_configs` / `embedding_model_config` | 注册；凭据不进普通备份 | 注册 | Legacy 与 singleton 含义不能互换；没有把 retrieval 映射到 embedding 的已批准合同 |
| `chat_drafts` | 正式全应用数据类型 | 注册，已与 app_settings 做最小互操作验证 | 仅两类实体的官方 codec/validator PASS；不等于全应用运行恢复 PASS |
| `vector_chunks` / documents | 注册的逻辑数据和资源路径需要一致 | 注册/资源 roots 接受 | 路径、向量依赖、模型匹配、外部引用须逐项检查；不能将整库 RAG 迁移等同 `.cbsave` 的 1 块 |
| Memory 各实体及事务/修订记录 | registry 存在并有图校验 | 多类均注册 | 完整历史/运行状态需独立证明；已验 `.cbsave` 只含当前 HEAD/LEGACY_REFERENCE |
| `generated_voice_messages` / `voice_anchor_states` / audio | Desktop 恢复重新物化已验证 MP3，生成所有权 receipt，并保留原导入媒体 | Android 注册语音/anchor；没有因此证明所有 Desktop receipt 和路径都适配 | 核对媒体 bytes 和版本绑定，不能只比 record ID 或能播放 |
| images / documents 文件名 | Desktop 为平台安全映射为 flat 路径；模型逻辑 ID 与 Windows 文件名分离 | 官方使用资源 token→目标绝对路径 | 路径字节不同可以是正式映射，必须有可逆关系和资源 hash；路径不可读不得丢文件 |
| `moment_posts` / `moment_tasks` | 未注册，整包拒绝 | 已注册、有运行/调度语义 | 仅有 [封存草案](P11_MOMENTS_OPAQUE_PRESERVATION_DRAFT.md)，未实现，完整互通 BLOCKED |
| NovelAI studio/history/design 等共有名称 | D01 注册多类，资源如 vibe payload 要验证 | O06 注册相应名称 | 不等于 Desktop 完整业务 parity；共同类型的未知字段仍可能拒绝 |
| 未知实体/未知字段/未来版本 | 未知类型或字段整包拒绝，原包保留 | 类型白名单；实体 decoder 对未知字段宽松 | 不能利用 Android 宽松解码来掩盖丢字段；未证明往返原 bytes 就不放行 |
| SecretStore / 外来非空凭据 | 目标 SecretStore 独立保留；普通导出排除已知秘密；外来非空值整包拒绝 | 官方可以把凭据写进备份，恢复可覆盖/清空原偏好 | [C1/C2](P11_CREDENTIAL_MIGRATION_DESIGN_DRAFT.md) 均未授权实施；加密格式可读不等于凭据迁移许可 |
| root 选择、desktop-settings、锁、diagnostics/backups | 设备本地状态，由全应用恢复保留目的端；不是普通 portable 内容 | 无直接等价的 Desktop 运行语义 | 不能复制 Windows 锁/启动路径到 Android；不在迁移清单中伪造等价值 |
| 未来 Moments 保全 sidecar/秘密事务日志 | 尚不存在，本轮没有新增 | 官方不认识私有封存格式 | 需要新的合同及再导出通道，不能把它们塞进现有 registry 免审 |

共有名称完整集合以固定 O06/D01 表为准，本表按行为分组，不把“注册”升级为运行兼容。Desktop 已支持的全量备份仍必须在当前严格资源/图约束内成功；未受支持的输入不能部分恢复。

## 2. 三种导出产品必须分开

1. **Desktop 全量备份**：保留所有受支持的 Desktop 非秘密持久化数据和资源，明确设备本地状态及秘密的既有边界；不得因为 Android 不支持就删掉 retrieval/background 等数据。
2. **官方 Android 可接受子集（未来产品）**：用户明确选定目标版本后生成新档案，逐项声明排除哪些类型、字段、设置、秘密及连带资源/引用。它有意不完整，不能叫全量原样恢复。
3. **完整跨平台全应用互操作**：所有数据域、所有权、资源、秘密策略及恢复/回滚都在批准合同内，且有真实官方应用证据。当前不具备。

目前没有第 2 项正式用户产品合同。本轮仅定义研究方向，不增加 UI、自动筛除或新格式。

## 3. 未来 Android 子集导出合同草案

必须先批准 PORT-01，再考虑实现：

- 输出前生成按类型/字段/数量/资源的差异清单。默认取消；用户主动确认目标官方版本和所有排除项，来源 root/原完整备份不变。保留一份已验证 Desktop 完整备份作为前提，不能拿部分包覆盖完整包。
- 计算传递依赖闭包：保留的消息/会话/卡/模型/记忆/RAG/媒体不得引用被排除对象。仅集合交集不足；无法闭合即拒绝整个子集导出，不用空值或默认配置补洞。
- `retrieval_model_config` 不重命名为 Android 模型；若 Android 可通过手工配置恢复检索体验，须明确为目标端新配置，不能算源设置迁移。
- 背景清单/首选背景不能只保留某张图片就报告配置完整。独有且无保留对象引用的资源如何处理也必须在排除清单中明确；共有资源不能误删。
- 仅 `desktop_backup_preferences → preferences.json` 有现成正式映射。拒绝同包两份权威，不把这项映射扩展成任意私有 Entity 可自动转换的先例。
- 每个保留类型执行固定版本 schema、未知字段、资源 hash/长度/路径、版本/引用图验证，再使用官方 codec 和实际官方应用恢复测试。官方忽略未知字段的 decode 成功不足以通过。
- 保留字段不允许“通过宽松 decode 再 encode”丢失未知内容。未知数据不能解释、原始文件损坏、任何必需资源缺失均拒绝，原始完整备份留存。
- 档案身份/结果必须标为 Android 兼容子集，附目标版本和排除清单；不能把有意删减解释为“迁移自动优化”。

## 4. 失败安全与测试要求

| 测试 | 预期 |
|---|---|
| Desktop 全量往返含 retrieval/background/偏好 carrier | 非秘密字段、选择关系、资源全部保留；carrier 往返不重复；未知项拒绝 |
| 官方 Android 输入 Desktop 完整包含未注册类型 | 明确拒绝，不把失败改成仅两实体验证 |
| 子集排除与传递引用闭包 | 排除需主动同意；悬空引用使整个子集输出失败 |
| 同名 Entity 新字段/嵌套扩展 | 不被宽松解码静默丢失；未支持即拒绝 |
| Windows 逻辑/物理 ID 和资源路径映射 | 名称转义/大小写碰撞受控，原逻辑身份和媒体 SHA 对应 |
| 取消/磁盘不足/认证损坏/中断 | 不替换完整备份，不修改 source root；仅清理自有未完成输出 |
| 官方真实恢复/续聊/再导出 | 不是 codec-only；核验声明子集全部数据与引用，不外推完整应用 |
| 原包保留及秘密排除 | 原档案 hash 不变；输出/日志不携带秘密或间接嵌套含密原包 |

以上为未来测试要求，本轮没有执行。本轮权限只允许文档审计；实施、schema/所有权/导出变化及物理设备操作仍需独立批准。

## 5. D-038 结论

明确的子集可改善有限迁移可用性，但不会自行满足完整全应用 Gate。完整 Desktop 数据保留、官方目标的可接受部分、必须拒绝部分应各自报告。Moments 封存、外来秘密、未来未知字段与目标运行一致性仍是独立阻断；用户对 C1 或子集的接受不能由开发者解释成整个 D-038 降级或完成。
