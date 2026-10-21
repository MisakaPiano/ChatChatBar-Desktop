# P11 真机准备与全应用迁移安全设计交接

**DRAFT / DESIGN + PREPARATION ONLY / 请 Project 独立设计复审**，2026-10-22 JST。

## 控制点与本次交付

- 仓库：`MisakaPiano/ChatChatBar-Desktop`；分支：`feature/phase8-fish-audio`。
- 设计依据 HEAD：`8c378e8cf487f6dbcb0df3f3a6f1408028185091`。本目录所属 docs-only 提交为交付版本；提交身份请以 Git 历史和聊天交付 SHA 为准，不在提交内循环写入自身 SHA。
- 正式 baseline：ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。
- 选定官方备份来源：1.4.5 @ `1198a13cf3008de1358b8a69aa5e40b64797c8d9`，没有 baseline 升级。
- 沿用 Project 对前轮 `.cbsave` 模拟器互操作工程的限定接受；不扩大到物理 Android、完整 `.cbbackup` 或 P8–P11 parity。GUI R1 限定接受也不变。

交付七份独立文档：

| 文档 | 主要内容 |
|---|---|
| [真机集中验收计划](P11_ANDROID_PHYSICAL_ACCEPTANCE_PLAN_DRAFT.md) | 设备隔离、包签名身份、loopback/reverse 权限、合成往返、数据对照、失败与退出 |
| [Moments 无损封存草案](P11_MOMENTS_OPAQUE_PRESERVATION_DRAFT.md) | 原包/封存/P13 三层、引用闭包、独立所有权、再导出、事务及 12 类验收 |
| [凭据迁移草案](P11_CREDENTIAL_MIGRATION_DESIGN_DRAFT.md) | 独立 C1/C2、准确类别、目标冲突、DPAPI 与跨域恢复、12 类验收 |
| [Desktop 类型矩阵](P11_DESKTOP_ONLY_PORTABILITY_MATRIX.md) | 专有类型、共有候选类型、实际映射、明确拒绝与未来子集合同 |
| [源码审计](SOURCE_AUDIT.md) | 40 份固定源码/治理/工具证据的永久链接、入口行、原 blob SHA-256；事实与推论分开 |
| [待批准决定](DECISIONS_REQUIRED.md) | 设备授权、封存、凭据、子集与保留策略决策，未批准时行为 |
| 本交接 | 结论、边界与 Project 复审请求 |

## 需要 Project 评估的结论

**真机材料具备审阅条件，执行尚不具备条件。** 未指定物理设备/测试用户、未证明隔离、未核验官方发行 APK 签名，也没有安装/文件传输/日志/reverse 或新 Windows GUI 的执行授权。状态保持 **BLOCKED / NOT TESTED**。推荐独立无正式 CCB 数据设备；不能以 debug APK 或工作资料夹名称推定安全。

**Moments 封存有条件可行，但不是“保存两个 JSON”即可完成。** 推荐固定已审计版本、独立只读记录/资源容器、原始 bytes 与字段位置映射、历史关系及独立所有权。需要新持久化合同、备份/root switch/恢复事务/旧版本防写入机制。未知字段或资源闭包、未来完整再导出不能证明时继续整包拒绝。封存绝不运行 task；向官方 Android 回注 pending task 可能重新调度，必须先解决目标端安全激活合同，否则不得称恢复已满足要求。

**C1 可提供明确排除秘密的有限迁移；C2 有望提供经逐项授权的安全秘密迁移，但尚无实现。** 官方 1.4.5 正式导出没有源端无凭据开关。C1 不能清空/覆盖已有 Desktop secret，还必须防止新模型沿用旧 ID 意外调用旧凭据。C2 需要 DPAPI 专用事务区、逐项目标账户/冲突选择、跨数据根与秘密根的一致提交和回滚，不能以现有逐 key save 代替。社区会话目前没有 Desktop 目标合同，建议首版拒绝。

**Desktop 专有状态不能静默丢弃。** `retrieval_model_config` 与 `desktop_character_backgrounds` 不在官方 registry；`desktop_backup_preferences` 有映射到顶层 preferences 的正式现有通道，应区别对待。未来 Android 子集必须是另一个有明确排除清单和依赖闭包验证的产品，不是完整 Desktop 备份的替代。

**D-038 未满足。** 固定版本封存若完成保全、资源闭包、事务和安全再导出证明，可能满足未运行数据域的保全分支；C2 可能满足独立凭据分支。C1 和 Android 子集只提供有限兼容，不能自动解除完整全应用迁移要求。整套方案即使设计通过，仍需要实施、真实官方迁移/恢复和独立验收。

## 本轮实际行为与验证等级

- 只新增本目录七份 Markdown，读取本地固定 Git object 和历史合成材料。没有修改生产代码、协议、schema、Prompt、SecretStore、备份恢复逻辑或旧报告。
- 没有构建、启动 Windows GUI/模拟器、查询或操作物理 Android、读取真实数据/Token、执行迁移或功能测试。本轮不存在新增技术运行 PASS 或 USER PASS。
- 文档检查已通过：7 份新文件、40 份固定源码 blob SHA-256 与入口行、26 个本地链接；检查时 HEAD 仍为指定控制点，既有 tracked 文件无改动、暂存区为空。这不等于实现测试或真机验收。
- 四份既有用户文件继续 KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED。原 RED、USER FAIL、JUnit、合成档案与历史恢复证据均不改动。
- 技能审查完成，现有技能不因未实施的设计失效；本轮不把草案写成现行产品行为。

## Public Preview A–G 仍需独立完成

| Gate | 本轮贡献 | 仍需的证据/决定 |
|---|---|---|
| A 持久化/恢复 | 新域失败与中断矩阵 | 实施后真实旧/新原子一致、损坏/取消/空间不足/恢复证据；候选整体复核 |
| B 安装/升级 | 将封存旧版本防写、N→N+1 保留列为要求 | 实际安装升级/保留/可信更新入口候选验收；本轮无安装授权 |
| C 单会话迁移 | 物理 Android 集中计划 | 隔离与操作授权后真实 Android/Windows `.cbsave` 往返和人工确认 |
| D 秘密/隐私 | C1/C2 分离、冲突与泄漏矩阵 | 用户安全设计批准、合成 canary 检查、目标存储/恢复事务实现和候选隐私审计 |
| E 支持性 | 明确拒绝、失败证据和退出程序 | 对最终实现验证错误提示、恢复说明与支持包无敏感内容 |
| F 发布真实性 | 保持准确 baseline/目标及限制 | 发布时 upstream drift、D-016 权利确认、签名发行/Release Notes 等独立审核 |
| G 全应用迁移 | 源码审计和阻断解决方向 | Moments/凭据/专有类型已批准实现，官方全域迁移和重启恢复实证；两实体子集不能替代 |

## 请求的复审动作与停止点

请 Project 审核源码引用是否支持设计、三类保全对象是否清楚、资源闭包与未来再导出是否可证明、C1/C2 是否满足 D-038 安全边界、事务是否覆盖所有新持久化域，并对 [决策表](DECISIONS_REQUIRED.md) 给出明确建议。

决定须由用户批准，设计通过也不等于实施批准。本次提交后停止；不自行实施方案、操作物理 Android、再次启动 GUI、合并 `desktop`、签署 P11 CLOSED/ACCEPTED、解除 Post-P8 HOLD 或启动 Public Preview Gate。
