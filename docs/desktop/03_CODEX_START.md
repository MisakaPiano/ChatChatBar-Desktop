# Codex 连续实施入口

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

执行依据：[用户授权边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)、[开发原则](01_PROJECT_INSTRUCTIONS.md)及用户实际已有授权。当前连续任务：[203 P11→Public Preview](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

## 接手与连续执行

核对当前 branch/HEAD、工作区、已有执行者和相关增量；保留未提交/忽略/私有文件。读取 Source Map、baseline、CURRENT、相关官方 Skill 和必要源码，不无差别全库审计。观察到 upstream 新版本不等于 baseline 已提升。

按已有授权连续完成定位、方案、实现、最小修复、必要测试、构建和交付，可跨多个 phase。Git 检查点用于恢复和进度说明，不是 Project 审批点。旧 slice/合同的 STOP、NOT AUTHORIZED、HOLD 只按当时范围和用户后续指令解释，不当永久禁令。

Project 在实现后阶段性审查，未经用户采纳的新限制仅为建议。审计发现转为具体修复或后续项；不因“尚未审计/未签 Gate”停下独立工作。有效证据复用；有相关变更或实际失败才补测，不重复无关检查。

## 诊断与真实阻断

说明具体字段/类型、调用链、源码依据、可复现条件和最小修复。Desktop 缺陷在当前修复授权内解决；官方问题给出依据和复现材料，推测单列。只暂停受真实阻断影响的动作，其他工作继续。必要人工操作直接向用户提供产物、最短步骤和预期结果。

## 授权与数据边界

诊断与修复不自动授权原备份改写、真实用户根操作、真实凭据写入、自动合并或公开发布；已有实际用户授权的动作直接执行，不重复询问。合并/发布不能由模板自行扩权，也不增加 Project 签字门槛；遵守实际仓库保护，不 force/reset 未知工作或写官方 upstream。

提供的 API/密码/存档按授权在本地使用，原件保留。真实数据、.cbsave/.cbbackup/.cbback、解包内容和凭据不进 GitHub，含 Actions Secrets/日志/cache/artifact/Release；使用忽略的私有目录，提交与打包前检查。

保留官方 Package/Entity/最终 Prompt 区分、共享逻辑、原子写入和 Prompt ownership。未测试/未验收如实记录，不能自行宣布 Project PASS 或 Gate 通过。交付报告简明写已完成、进行中、下一步和确切需用户动作。
