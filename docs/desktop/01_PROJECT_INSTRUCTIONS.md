# CCB Desktop Project Instructions

更新：2026-10-25 JST。本项目开发和长期维护 Windows 原生 ChatChatBar Desktop。当前目标是让 Codex 按用户已有授权连续完成 P11、Public Preview A–G 必要工程并推进到 Preview。

## 用户授权与 Project 权限

以 `docs/desktop/USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md` 为协作边界。用户决定产品方向、工程范围和数据使用授权；用户最新明确指令可以调整此前合同范围。Project 文档只记录授权和事实，不产生高于用户的权限。

Project 只在实现后做阶段性独立审查、指出具体缺陷、评价证据和提出建议。用户没有明确采纳的 Project 新限制只能作为建议，不得自动撤销、收窄或暂停已授权工作，也不得写成强制任务合同、全局 STOP、逐切片审批或新的 Gate。Project 不担任 Codex 开工、跨 phase、测试、常规修复或已授权交付动作的前置批准人。

Codex 连续完成定位、方案、实现、必要验证、构建与交付，可自主拆分多个 phase 和 Git 检查点，不因阶段编号、未审计或未签 Gate 返回等待 Project。保留当前执行者，换对话、引继、模板和旧合同不会撤销用户已有授权。已成立且未失效的证据复用；仅对受改动影响或实际失败的部分补测，不重复全项目审计和无关回归。

真实技术阻断必须说明具体字段、类型、调用链、可复现条件、受影响动作及最小修复；只暂停依赖该阻断的动作，继续其他已授权工作。不得用 ENTITY_SCHEMA_UNSUPPORTED 等泛化阻断码代替可以查清的原因。Desktop 缺陷在当前修复授权内解决；官方问题给出对应源码依据和复现材料，推测与已证实事实分开。

必要人工验收由 Codex 直接向用户提供可运行产物、最短步骤和预期结果，不经 Project 转批。审计发现转为具体修复或已知问题，不以新增设计报告和授权回执代替实现。进度写清已完成、当前工作、下一步、确切阻塞和需用户做的事。

## 权限不能由文档扩张

诊断与修复本身不自动授权改写手机导出原件、操作真实用户根、写入真实凭据、自动合并或公开发布；这些动作按用户实际已给出的授权执行。已有授权不重复索取；缺失授权只询问确切相关动作，其他工作继续。Project 不能把审查建议伪装成用户拒绝授权；Codex 不能自行宣布 Project 已验收或 Gate 已通过。平台及更高优先级约束按实际适用范围执行。

## 本地数据与隐私

用户已说明本地开发数据为测试数据，手机导出资料用于桌面验证。已提供的 API、密码和数据可以按已有授权在本地读取、解密、诊断、导入测试和调用对应 API，不因含密钥或并非 synthetic 就反复讨论是否许可使用。覆盖已授权测试副本前保留可用恢复副本；原始导出和无关个人数据不顺手改写或删除。Git 用于恢复代码，数据依靠本地快照。

真实用户数据、备份原文、API Key、Token、密码、.cbsave、.cbbackup（含用户称作 .cbback 的备份）及解密/解包内容不得提交或上传 GitHub。该边界覆盖历史、PR/Issue、评论、附件、Actions 日志、Secrets、cache、artifact、Release 和支持包。放本地忽略目录并检查提交/打包内容；GitHub 仅放代码、脱敏结果和人工合成样本，不回显凭据。此授权不自动允许向其他云服务转移私密内容；CI 使用人工合成凭据。

Desktop 数据必须落盘。保留官方 JsonFileStorage 原子写入语义、数据目录可查看/选择、迁移前 snapshot、自动备份、Portable Mode 和可导出/恢复能力；不得通过清空数据掩盖修复失败。SecretStore 抽象化，Windows 持久化使用 OS 保护机制，不把 token 当普通明文 JSON 保存。

## 产品目标与技术依据

Desktop 是 CCB 的长期下游移植：
- 尽量直接复用官方数据结构、领域逻辑和运行语义；能共享 JVM-neutral 逻辑时不维护第二套实现。
- Android 每项用户功能有同功能实现或合理桌面等位替代；UI 可重新设计，核心行为不得偷偷改变。
- Windows-first：Kotlin/JVM、Compose Desktop、JDK 17；核心不依赖浏览器、WebView、localStorage 或 IndexedDB。不要求先把整个 Android 项目改成 KMP。
- 始终区分传输 Package、内部持久化 Entity 与最终 Prompt/API request。Prompt 真值是最终序列化请求，不是 UI Preview 或字段名字。
- Package、Entity、Prompt、WorldBook、Context、Provider、RAG、Memory、SaveSlot 与声明的 upstream baseline 一致；不长期硬编码历史 schemaVersion。FREEFORM 和 STRUCTURED 均为合法模式。

技术事实依据顺序：对应 commit 的官方源码、实际 Fork 代码、匹配版本的 CURRENT 文档、旧 Project Sources 和历史资料。执行授权依据用户直接指令；技术文档的排序不能改变用户权限。涉及当前开发时核对活动分支，不能把旧 desktop 或 Sources 快照误当最新工程状态。

## Feature Parity 与平台等价

功能状态使用 EXACT、EQUIVALENT、PENDING、BLOCKED，或有充分理由的 N/A；完整兼容声明不留 UNKNOWN。EXACT 需要共享逻辑或行为一致证据，不能因允许开发就提升评级。

Android 平台能力寻找桌面替代：SAF → 文件选择/拖放，Share Sheet → 保存/复制/Explorer，前台服务 → Desktop Task Runtime/托盘，通知 → Windows 通知/任务中心，Content URI → 文件路径，Media3 → Desktop audio backend，APK updater → Desktop installer/updater，OAuth deep link → URI scheme/localhost callback。尚未实现项可如实 PENDING，不直接删除。

## 上游同步与 Prompt ownership

上游为 SaltyFishOTL/ChatChatBar；Fork 为 MisakaPiano/ChatChatBar-Desktop。master 仅镜像 upstream/master，desktop 为集成线，feature/* 开发，sync/* 吸收上游，release/* 按需发布准备。不得写官方 upstream，不 force/reset 未知工作；遵守实际 GitHub 分支保护。

同步时比较 baseline 与 upstream/master，定向分析 changed files 和官方 Skills、受影响 parity，适配并验证。Project 可后置审查高风险语义；只有对应兼容验证完成才能更新正式 baseline，不因观察到新版自动宣称兼容或停止全部独立开发。

保留官方 Prompt ownership：不顺手重写已有模型 Prompt。确需修改原文时向用户说明原文、diff、原因及预期行为变化，取得明确同意；非 Prompt 的平台适配、序列化和参数修复正常推进，其他任务不因此停下。

## Public Preview 连续推进

P11 与 A–G 放在同一轮工程实现和验证中，不以 Project acceptance/CLOSED 或七次签字作为编码许可。A 持久化恢复、B 安装升级/最低更新发现、C .cbsave 跨端、D 凭据隐私、E 支持诊断、F 真实发布范围/已有权利依据、G .cbbackup 全应用迁移，可按依赖连续完成。

Gate 是工程和验收清单，未签字数量不是完成百分比或全部开发冻结理由。按真实结果记录 PASS、FAIL、NOT_TESTED、已知问题和必要人工步骤；不得删检查、伪造通过或错称兼容。一般缺陷、未覆盖环境和后续功能可登记后续；已复现数据丢失、凭据外泄、无法安装启动或已承诺核心迁移失败由 Codex 在同一工作中修复。Project 只能指出具体缺陷，不能自行创造新的发布权限要求。

最终合并/公开发布依据用户实际已有授权及真实可用产物执行，不凭本文件自动扩权，不重复索取同一授权。必要外部或真人步骤直接交用户。Public Preview 不等于 1.0 parity；P12–P16 可继续开发，P17 根据真实反馈 hardening，P18 保持 1.0 目标。从首次公开 Preview 起，持久化用户数据按有价值数据维护，普通升级不要求常规清空目录。

## 开始与引继

先读本说明和权限边界，再核对 Source Map、UPSTREAM_BASELINE、CURRENT、相关官方 Skill、parity/compat 和必要源码。只看与下一步有关的增量，不无差别全库审计。旧 NOT AUTHORIZED/HOLD/STOP 按当时合同与用户后续指令解释，不能当永久禁令。已有执行者按已有授权连续推进到 Preview。
