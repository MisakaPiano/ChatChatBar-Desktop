# v1/v2 与迁移语义兼容矩阵

2026-10-22。DRAFT / SOURCE AUDIT；不批准适配。固定版本、状态定义和 O/D 源码编号见 [审计](UPSTREAM_145_146_147_IMPACT_AUDIT.md)。本轮没有执行新版互操作测试。

## 格式可读性和数据完整性是两个门槛

| 输入 → 接收端 | 格式结论 | 数据/运行结论 |
|---|---|---|
| Desktop 当前 v1/code84 → 当前 Desktop | CURRENT_SUPPORTED：已登记类型、无外来秘密的现有合同 | 不含尚未实现的 Moments 封存；不能代表 Android 往返 |
| 官方 1.4.5 v1/code84 → Desktop | CURRENT_SUPPORTED：限定已登记子集；BLOCKED：Moments/非空外来凭据整包 | 旧合成 POC 不是生产全包接受证明 |
| 官方 1.4.6/7 常规 v2 → Desktop | REQUIRES_ADAPTER：envelope==1、manifest 无 scope、版本上限84均阻止 | 必须先定义 scope 与合并语义，不得仅放宽数字 |
| 官方 1.4.6/7 底层生成 v1/code85或86 → Desktop | REQUIRES_ADAPTER：仍触发版本门槛 | 不得伪造 code84；高版本字段另审 |
| Desktop v1/code84 → 官方 1.4.7 | SOURCE_PROVEN_ONLY：codec/manifest版本门槛允许；PENDING_TEST：实际完整恢复 | 不保证每个 Desktop 类型、字段、资源被接收；未知类型、秘密、任务另设门槛 |
| 官方 1.4.5 v1 → 官方 1.4.6/7 | SOURCE_PROVEN_ONLY：旧无 scope 路径保留 | PENDING_TEST：本轮未运行这些版本的 APK |
| 官方 1.4.6 v2 → 官方 1.4.7 | SOURCE_PROVEN_ONLY：schema/选择语义相同，路径修复在导出端 | 旧坏路径输出不能假设由接收端修好 |
| v2 → 官方 1.4.5 | BLOCKED：1.4.5 codec仅版本1 | 需要明确派生 v1 适配及目标字段审查，不能改头部 |
| 新封存域 → 任何当前生产 Desktop/Android | BLOCKED：本轮没有注册 schema 或实现 | 原档案取回不等于新域可恢复 |

O01–04/D01–03 是上述格式事实来源。应用版本是声明，不证明源 APK 身份；将来证据应同时记录 APK SHA/签名、构建源码 SHA、envelope/manifest/code，而非相信用户可改的 manifest。

## 三种官方恢复模式

| 输入/模式 | 匹配及保留 | 含义与风险 |
|---|---|---|
| 无 scope v1 + REPLACE_ALL | 旧完整 incoming 替换；不走增量 preview | 原根恢复合同；不是合并 |
| 有 scope v2 + REPLACE_ALL | 选中集合按ID替换；未选保留；依赖被保留对象需要时可留存并提示 | 不是“先清根再恢复所选” |
| 任一版本 + UPDATE_EXISTING | ID优先；唯一规范化名称；歧义创建副本；部分类型用内容指纹 | 不同ID同名可能更新目标；指纹不等于原始全字节一致 |
| 任一版本 + ADD_COPIES | 主要实体新身份和引用映射；已有 live 集合加入副本 | 所选全局设置/偏好/单例仍可能改变，详见 [严格合并](ADD_ONLY_MERGE_CONTRACT_DRAFT.md) |

O08–14：增量 preview 构建完整 prepared，校验后密封并持有维护租约，匹配确认后 Schedule；不是边读边写 live。无 scope 时部分选择判定默认全选，不能赋予 v1“未提及字段不改”的新含义。

## scope 的缺省、空集合和省略

| 情况 | 源码行为 / 拟议 Desktop 边界 |
|---|---|
| v2 scope=null | 官方拒绝；Desktop适配也应拒绝，不猜全量 |
| 无 scope v1 | legacy全量语义；先确认整包替换或另批显式增量 |
| sessionDefaultChoice + sessionChoices | 稀疏选择描述；先展开后审查，各布尔值不能用集合是否有文件替代 |
| 旧 scope 缺详细 session choices | 官方兼容推断实体家族是否存在，缺家族保留目标；无法证明明确选空时不可冒充主动删除 |
| 图片/语音/SaveSlot明确未选 | 保留目标对应家族；导出当前会话媒体投影可能为空，但不等于请求清空 |
| 明确选中且集合为空 | 可能是清空所选范围的意图；只允许在替换/更新合同明确确认后实施；add-only不删目标 |
| momentsImages缺失 | 官方默认true，不得自行解释为false |
| 未知scope字段/未来schema | 官方宽松解码不证明无损；Desktop应保留原档案并拒绝不理解的运行语义 |
| 未知globalOptions、非法计数/不一致范围 | 拒绝；不能按已识别选项部分成功 |
| 外来 retainedPreferences非空 | 官方拒绝；它是内部事务指令，不是外来权限 |

应对明文和加密输入交叉测试每行。认证参数保持不变只证明算法来源；密码错误、截断、尾部篡改、超额解压、重复条目及 envelope/manifest 不一致必须有新负例证据。

## 类型与依赖边界

| 数据 | 新版官方已知能力（SOURCE_PROVEN_ONLY） | Desktop迁移限制 |
|---|---|---|
| Model / Embedding / retrieval alias | 选择依赖自动纳入；retrieval在1.4.6/7登记 | 同ID异配置拒绝；模型密钥可随依赖进入，不能默认无凭据 |
| FormatCard / WorldBook / 内嵌entry | 映射顶层和嵌套身份 | 不改现有默认、用户Prompt或自然语言；引用必须全部闭合 |
| Memory历史 / RAG / source-turn | 官方增量有专用身份和证明重算 | .cbsave已通过当前Memory互通不等于全备历史；需逐节点/资源/来源验证 |
| 图片 / voice / 草稿 | 按session选择、媒体和voice anchor随对应记录 | 保留消息版本及会话归属，不能仅数文件 |
| SaveSlot v8 | 独立选项、独立包，流式重绑定身份，媒体保留 | 包SHA在重写后可变化；分别验证逻辑映射和媒体SHA，旧包不改 |
| Moments | posts/tasks、历史头像例外、所选正文图投影 | 现生产整包拒绝；未来只读封存与运行准入分离 |
| 其他Desktop专有注册/证明/目录 | 不能由同名Entity推断 | 按固定目标registry逐类验证；未经证明不输出为Android全量包 |

1.4.5 未登记 retrieval_model_config 的旧结论不应扩展到1.4.7。反过来，1.4.7登记该别名也不自动授权将D-038目标改为1.4.7。完整 Desktop 备份须保留全部正式域；Android兼容子集必须展示排除清单并标为有限迁移。

## 测试及授权

本矩阵全部新版本运行项为 PENDING_TEST。后续需固定三版本构建身份、合成全字段/资源夹具、各模式双向测试、明文/加密、取消/崩溃及原档案保留；不得复用旧POC数字代替。GUI/模拟器/真机和真实数据访问按独立执行授权进行。本轮继续拒绝未知类型、非空外来凭据和未受控Task，未修改任何生产入口。
