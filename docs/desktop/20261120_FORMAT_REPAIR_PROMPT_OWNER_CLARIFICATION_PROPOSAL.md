# 格式修复 Prompt 所有权澄清（用户已同意）

用户于 2026-11-20 在当前 Desktop 对话明确回复“同意你的提案”，采纳下述仅限格式修复家族的位置澄清。本次同步 AGENTS.md 的最窄例外，不移动、复制或改写任何模型可见文字。此前待确认提案保留在文档提交 `a1c63ce940bbe35a3d8eba56a32727fb059ac628`。

## 已核事实

- 仓库通用 AGENTS.md 要求新增硬编码 AI Prompt 放在 Android `app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt`。
- Desktop 既有抽取提交 `52b8854d` 将格式修复常量和 builder 的物理所有者放在 `app/sharedCore/src/main/kotlin/com/example/chatbar/domain/prompt/FormatRepairPromptAuthority.kt`；Android `PromptTemplates` 保留转发。Desktop 不能依赖 Android 模块。
- `chatbar-prompt-pipeline` 和 `chatbar-message-format-repair` 描述了这个现有单一共享所有者。技能描述不能覆盖通用规则对新增文字的明确要求。
- 当前新增 `actionOptionsRepairRequirement` 在 Desktop 共享 authority，Android facade 仅转发。因此其行为与放置规范应分开审查，不能因两端功能一致就关闭 P3。

## 已采纳的最窄澄清

仅对 Desktop 仓库中**已经抽取的格式修复 Prompt 家族**，新增同家族 builder 的物理文字继续放在 `FormatRepairPromptAuthority.kt`，由 `PromptTemplates.kt` 维持可搜索的转发入口。保留一份共享文字，不引入 Desktop 对 Android 的依赖。

该澄清不扩展到其他尚未抽取的 Prompt 家族，不授权任何既有文字变化，也不覆盖 Android 上游仓库。Android 上游 PR11 继续按其仓库规则将文字放在 `PromptTemplates.kt`。

## 确认与验证范围

用户明确决定已提供位置规则的授权；本次只同步该范围。独立复审核对规则变更及未改 Prompt 的证据后，才能记录 P3 已关闭。该决定不代表解析 P2 或任何运行验证已经通过。

关闭 P3 的证据应包含接受的规则文本、固定 source/blob、实际 Android facade 和 Desktop 修复调用链，以及对应序列化请求中该 builder 的使用。原 Prompt 字节、格式修复协议和行为 P2 回归分别保留证据，不以规则澄清代替功能验证。
