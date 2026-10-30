# 146 — Multi-API Account Profiles & Provider Visibility（D-044）

2026-10-30 调度更新：用户已明确把 SF06 纳入当前七项小修并授权实施，旧文下方“只登记／等待 Project”是历史状态，不能收窄此次授权。实现与验证进度见 [210](210_SELECTED_SMALL_FIXES_AFTER_1218.md)；此批仍未发布，不把源码实现等同于真人验收。

日期：2026-10-20 JST
状态：**USER REQUESTED / REQUIREMENTS CAPTURED / DESIGN & MIGRATION AUDIT PENDING / NOT IMPLEMENTED**。

## 1. 需求来源与边界

用户明确要求 CCB Desktop 以后允许分别保存和切换多个 **LLM API、NovelAI 生图账号/Token、Fish Audio API**，每个 API 可设置自定义名称/账号备注；特别要求 **LLM 在实际调用位置直接显示所使用 API 的真实服务平台**。这是 Desktop 增强，不是已有官方 1.4.1 upstream EXACT 功能；不属于当前 P8 集中 Windows 人工验收，不自动成为首次 Public Preview blocker。

### 已确认的用户需求

1. 每种服务均可保存多个账户/凭据，不需为切换账户反复重输 API Key 或 Token。
2. 每个账户可使用自定义别名（例如「主号」「备用号」「某平台第二账号」）；同一平台可有多个账户。
3. 提供可理解的账户选择/切换 UI；切换不应让用户误认当前使用的账号。
4. LLM 请求入口显示**实际 provider/platform + 用户别名 + 生效模型**，并在任务进行中保持可辨识的请求身份；具体显示位置需 UI 审查，至少覆盖聊天主调用和各类辅助 LLM 调用入口。
5. 不把协议模板等同于供应商：`ModelConfig.templateType` 的 `OPENAI / CLAUDE / GEMINI / CUSTOM` 表示模板/兼容策略，不能因此断言真实 endpoint 属于 OpenAI/Anthropic/Google。自定义服务应显示用户配置的供应商名称或明确标记自定义/未标注，不凭域名猜测。
6. NovelAI 生图的登录身份/配额等账户信息与所选 Token 关联；Fish 的个人音色与权限可能因账户不同而不同，不可静默借用另一账户同名音色。

## 2. 当前源码依据（只读基于已冻结源码）

- Formal validated upstream：ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；`.agents/skills/chatbar-model-request-runtime/SKILL.md` 规定 per-model 优先、global fallback、HTTPS/本地 HTTP 无认证等特殊规则，不能为账户 UI 重写。
- Shared `ModelConfig` 已含 `id/displayName/baseUrl/apiKey/modelName/templateType`；现有 Desktop `DesktopModelCredentialPersistencePolicy` 通过 `DesktopCredentialKey.ModelApiKey(modelId)` 使用 OS-protected SecretStore。**多模型已存在，不等于统一多 API 账户身份管理已经实现**。
- Desktop `DesktopCredentialKey.NovelAiToken`、`FishAudioApiKey` 当前分别是单一全局 secret slot；`SiliconFlowApiKey` 亦为既有 fallback 槽。现有 `DesktopNovelAiSettingsController` / `DesktopFishAudioCredentials` 使用此语义。
- 当前还有 `DesktopPrimaryChatController` 的 effective model/diagnostic、NovelAI Studio、Fish voice/catalog 与各类 auxiliary LLM 入口，需要在实施前逐项映射 effective account identity。
- 以上位置是本次只读设计依据；实施前必须按当时 HEAD 重新审计，不能以本文件冻结永久字段或路径。

## 3. 建议的实现方向（未批准持久化 schema）

将 **API account/profile identity、真实服务平台、endpoint、ModelConfig、实际 SecretStore credential** 视作不同概念；在 UI 展示身份时，从与本次请求一致的 effective resolution 结果获取，而非从前端模型标签推断。

- 推荐保留稳定账户身份和非敏感别名/平台元数据，与 OS-protected credential reference 分离；允许每个平台存在多个账户。
- 选择默认账户、按模型指定账户、会话覆盖、辅助 LLM 继承规则、NovelAI/Fish 的切换粒度，均待独立设计审核；不得默认推导并修改上游 existing fallback。
- 单个正在执行的请求以启动时的有效凭据与账户身份快照为准；之后 UI 切换只影响后续调用。取消、重试、并发、账单与请求 attribution 要可追踪，但不记录原始 Key/Token。
- 不允许未经明确用户选择的账户轮换、自动跨账号 failover、跨供应商重试或隐式额外付费请求。
- Key/Token 的存储、读取、撤销、清除必须继续符合 Windows SecretStore/DPAPI、安全文件与失败保全约束；不得写入普通 JSON、请求/诊断日志或完整 Prompt。
- 删除/禁用被模型、会话、Fish 绑定或当前任务引用的账户时，应显式显示影响和安全失败策略，不自动改写历史资源所有权。

## 4. 数据迁移、Portable 与官方语义保护

实施前必须分别审计：旧 `ModelApiKey(modelId)`、`NovelAiToken`、`FishAudioApiKey`、`SiliconFlowApiKey` 与 embedding credential；无损升级、原值保全、失败/回滚、同名账户冲突、失效账户与 root switch、备份/恢复、Portable Mode、Windows OS 身份绑定。

不可自动清空旧 Token，不能假设 DPAPI 密文可跨设备直接迁移，不能为便利而导出明文账户凭据。P11 `.cbsave/.cbbackup` 与 full-app portability 仍按独立既定权威审查，不能以多账户功能顺手改变 Package/Entity/Prompt/API message schema。

**不修改官方 Prompt 原文、Prompt ownership、WorldBook、模型解析和 provider transport 规则。** 任何必要 schema 或 fallback 改动必须先给出旧行为、diff、数据迁移及精确 API serialization 影响，经 Project 单独确认。

## 5. 功能阶段、验收和禁止事项

- 建议归属：P17 统一「API 账户管理」设置与账号 UX；P15 请求入口身份展示、诊断及 D-042 统计身份衔接；LLM/P7 NovelAI/P8 Fish 的 domain adapters 在后续独立 slices 审查。具体排期尚未批准，不插入当前 P8 Gate。
- 要求回归：多服务、多平台、同平台多账户、自定义别名、账户切换、任务进行中的身份冻结、秘钥非泄露、单 Key 旧用户升级、失效/删除引用、无 Key 的本地 HTTP、模型级凭据优先与 global fallback、Fish 个人音色权限、NovelAI account metadata、离线/错误/撤销、备份/恢复。
- 自动化优先使用 synthetic/loopback；无真实付费 API。实际设备验收独立授权。
- 当前只登记需求与候选方向，**不实施代码、不创建或迁移凭据、不修改 CURRENT/parity/baseline、不合并、不发布**。

状态保持 **PENDING FEATURE / FUTURE DESIGN AUDIT**；当前 P8 post-UX frozen production `694a8bdd` 和旧人工 Gate 的工程身份不因本文件变化。
