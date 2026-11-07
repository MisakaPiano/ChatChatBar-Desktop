# Studio 仅恢复默认采样 / 2026-11-07

固定上游a96313d9为角色卡和Studio增加“使用 NovelAI 默认采样参数”。角色卡入口已存在；Desktop Studio只有“重置生成参数”，会重建尺寸、Seed和数量等设置，不能代替窄采样重置。

高级设置现在单独提供窄重置：按当前模型只恢复Steps 23、CFG Scale（V4.5为5，V5为7）、CFG Rescale 0和Euler Ancestral。保留尺寸、Seed、数量、V+/sigma、Effort、透明背景、人物位置、另一模型草稿与所有Prompt。明确点击在Medium也可重置保存的采样值；这与防止已排队High-only控件误改Medium的保护不同。仍走原Controller编辑和持久化撤销，不改变现有整组重置。

新增实际UI→Controller→仓库→全局撤销/重做检查涵盖两模型与Medium，另核原宽重置仍生效。必要云端原件待核，不以实现或测试源码声明通过；不涉及Provider、真实资料、新包或公开发布。
