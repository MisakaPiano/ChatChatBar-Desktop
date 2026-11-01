# B2 角色补全／改写桌面基础链

2026-11-01。独立于已公开冻结1226／d94。共享策略828的CI55两job已成功；本页后续生成与桌面接线是新源码，不能由该结果代验。

本批把原八个角色卡Prompt常量及改写schema原文移入 `CharacterCardAiPromptAuthority`，Android `PromptTemplates`保留同名facade／目录，构建指纹纳入新物理源。I盘逐块回执确认原文字面量和schema相同，无文案修改。机器JSON payload与fill合并也改为共同入口，Android原研究、图片识别与checkpoint上下文继续负责准备输入。

`CharacterCardCandidateGenerator`共用最终生成、完整终态、原文checkpoint、至多一次JSON修复与候选策略。Android以原task context／512或256思考预算／600秒静默覆盖接入；Desktop独立gateway保留模型输出预算、custom参数与同一GENERAL envelope。终态Error／拒绝／截断／取消或没有Done的输出只保留预览，不作为checkpoint或修复输入；成功原文后修复失败，可以重试修复。此处没有自动重发整个请求的隐藏fallback。

Desktop角色编辑器新增AI补空／定向改写入口；自由模式只支持改写。模型列表和鉴权在生成时重新读取，指定模型丢失不回落其他模型。流式原文／思考／修复、日志、取消及重试可见。候选预览比较实际merge后的卡，而非原始patch；只显式应用到同一session、未改变的完整草稿，正常保存仍独立。人物ID／声线／头像／开关及STRUCTURED／FREEFORM边界由原共享策略保留，关闭／维护排水纳入现有TaskRuntime。

当前入口明确只有“当前卡片＋填写要求”。研究／URL／临时文档／多图准备及看图／封面／头像候选仍待下一步接入，没有提供然后丢弃这些选项，不能宣布完整B2或P12完成。新回归覆盖完整终态与修复checkpoint、Android／Desktop八个实际HTTP请求、模型刷新与失效草稿、普通保存、维护取消、自由模式、420／1100窄宽UI；尚未云端执行，不使用本机Gradle／渲染或用户真实资料。
