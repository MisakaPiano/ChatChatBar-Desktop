# Phase 7 B2 — Chat image process UX contract

**UX CONTRACT ONLY — B2 NOT IMPLEMENTED / NOT ACCEPTED.**

本文件仅登记用户已同意的未来设计，不授权本轮实现 B2。

1. 聊天生图运行时，在所属 Assistant 消息下显示可折叠的设计过程卡片。
2. 默认展开，显示真实模型返回的 Prompt 设计阶段和累计流式内容；内容过长时在卡片内部滚动。
3. 折叠后保留当前阶段和简短进度，不影响后台任务运行。
4. Stop 使用与已验收图片 More 类似的小图标样式。
5. **Stop 位于过程卡片右侧、底边对齐；不在卡片内部，不在卡片下方另起一行。**
6. 展开和折叠状态均可停止正确的 source-owned task。
7. 从 Prompt 设计进入 NovelAI 图片生成时，更新同一个任务过程区域。
8. 不伪造模型 reasoning，不将临时过程持久化为 ChatMessage。
9. 不改变官方已有 Prompt 文本、请求语义、图片保存和取消权限。
10. 本文件为 UX CONTRACT ONLY；B2 NOT IMPLEMENTED / NOT ACCEPTED。

B1 与 C 的冻结记录保持有效。当前 B1+C 组合已 ACCEPTED / FROZEN，Project 独立审查及用户集成人工验收均 PASS，见 `74_PHASE7_B1C_INTEGRATION_ACCEPTANCE.md`；B2、D、P7-WIN-01/02/03 不因集成而关闭。Phase 7 NOT ACCEPTED / NOT MERGED TO DESKTOP；正式 upstream baseline 仍为 1.4.1。
