# 55 — Phase 7 Deferred Owner Map

本文件不是“以后再说”。`64_PHASE7_SCOPE_OWNER_UPDATE.md` 为当前窄范围增补：Studio/chat 窗口内图片粘贴/拖入、多选/重排、规范 APNG 自动还原、角色 visual collapse、V5 电量条与 caret inspection 已移入 P7；不再按本表旧 OS drag/drop 行 deferred。

每一个 deferred item 都必须有明确 owner、是否阻塞、reopen 条件。

| 项目 | Owner | 当前是否阻塞 P7 | 为什么不在本轮做 | Reopen 条件 |
|---|---|---:|---|---|
| NovelAI sampler/model capability 与当前 provider 漂移 | **长期 `49_NOVELAI_PROVIDER_CAPABILITY_DRIFT_AUDIT.md` + `14_UPSTREAM_COMPAT.md`；release gate `20_RELEASE_CHECKLIST.md`** | 否，当前 formal 1.4.1 parity 不阻塞 | upstream CCB 自身没有完整 current provider capability authority；Desktop 不应私自硬编码第二套 | authoritative provider evidence、upstream change、或确认 CCB-allowed request 被当前 provider 拒绝 |
| V5 Curated / V4.5 Curated / provider 新模型 | 同上 provider capability track | 否 | 属 provider/upstream drift，不是 Desktop UI closure | official API capability + shared authority design |
| model-specific sampler filtering | 同上 | 否；若确定现网组合失败则升级 blocking | 当前 official docs 未给完整 model×sampler API matrix | 获得 authoritative matrix / current provider schema |
| 完整 Studio generation preset 导入/导出 | **P17 Beta hardening / Desktop enhancement** | 否 | formal CCB 1.4.1 无该用户功能；不能为了方便临时造 schema | P17 开始；优先基于 `NovelAiGenerationRecipe` 定义 versioned Desktop preset |
| 完整格式化 Studio Prompt 跨应用文本复制/粘贴（**新独立 owner 记录，待规范**） | **P17 proposed; scope review required** | 否 | 用户之前要求复制可跨应用使用的**完整格式化提示词**；早期对话曾提 P12/P17，原 CURRENT map 未给它独立 owner。它既不是 P7 的官方 `NovelAiStudioPromptClipboard` **正向分段**，也不自动等于上述完整 generation preset **文件**。不可在 P7 偷换产品语义或发明第二套 Prompt | P17 设计审查时确定人类可读分段、完整性边界、外部粘贴兼容/格式识别与 round-trip；独立验收，不预先冻结新 schema |
| 全应用 Settings / Editor IA 统一重审 | **P17 Desktop IA / UX consistency audit** | 否 | P7 只修被图片功能直接撑坏的 Session Settings；否则 scope 无限扩张 | P17 开始或某页面在自身 owner phase 人工验收失败 |
| Character / Format / WorldBook / Data 等长页面 tab/sidebar 统一化 | P17；若对应功能 owner phase 更早触发则前移 | 否 | 属全局 Desktop 产品一致性 | owner phase manual fail 或 P17 |
| Moments / 根据现有对话生成朋友圈 | **P13 Moments** | 否 | 正式 roadmap 独立 domain：timeline/scheduler/generation/images/etc. | P13 开始 |
| Moments scheduler Desktop runtime | P13 + Desktop task/runtime adapter | 否 | 不属于图片 Studio | P13 |
| Community | P14 | 否 | 独立 domain | P14 |
| Windows Open With / file associations / URI / shell registration | P15 | 否 | OS-wide platform integration；window-local paste/drop 已由 `64` 授权移入 P7 | P15 |
| Installer / updater / tray / notifications | P15 | 否 | platform integration | P15 |
| Tutorial / onboarding / broad UX consistency polish | P17 | 否 | release/beta hardening | P17 |

**边界说明：** 已有 P7 `复制正向 Prompt` / `粘贴覆盖` 是实际存在的正向 Prompt editor-interchange 行为（基础、可选画风/补充、角色正向）；它不是跨应用通用的完整格式化 Prompt。未来功能先在 P17 Product/UX 规格审查中与完整 Studio preset 文件交换和 P12 AI authoring / Message Format Repair 分开定 owner；这里 P17 只是当前建议归属，不声称它以前已有正式 P17 冻结规格。不得为了本事项修改官方已有 Prompt 文本、Prompt ownership 或最终 serialized API request。

## Provider drift 不是普通 backlog

`49_NOVELAI_PROVIDER_CAPABILITY_DRIFT_AUDIT.md` 必须持续保持 CURRENT relevance。

每次 upstream 出现 NovelAI 相关 diff：

1. 对照 `49`
2. 检查 model/sampler/request capability
3. 标记 `14_UPSTREAM_COMPAT`
4. 若真实 provider 已拒绝 formal CCB 允许组合，则升级为 blocking compatibility defect
5. 不允许 Desktop-only silent divergence

公开 release 前：

`20_RELEASE_CHECKLIST.md` 必须明确 NovelAI provider compatibility state。

## P17 IA Audit 规则

以后不要再靠用户逐页撞到“一列滚到底”。

P17 应逐页面审：

- Tabs
- sidebar
- collapsible sections
- sticky footer
- searchable settings
- advanced section
- destructive action placement
- responsive density
- keyboard navigation

但 Phase 7 只修当前 Session Settings。
