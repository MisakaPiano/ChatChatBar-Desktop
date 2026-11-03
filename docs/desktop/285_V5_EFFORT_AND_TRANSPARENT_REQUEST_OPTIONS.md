# V5 Effort 与透明背景（源码接线，待对应云验）

用户 S05/S06 要求补齐 V5 Medium/High 与 Transparent BG。Studio 负责人只读确认其当前工作区也没有这两项实现，不能用 Studio 截图或版本替代 Desktop 证据。官方依据为 https://docs.novelai.net/en/image/effort/ 与 https://docs.novelai.net/en/image/tags/，以及公开网页构建 b94bdee 的模型能力、请求求解与源模型映射；本地只读哈希回执在连续总账。

## 请求与保存

- `NovelAiGenerationSettings` 增加默认不编码的 High/false 偏好。V4.5 暂停 V5 专属选项，原值保留；切回 V5 恢复。
- Medium 请求实际使用 `nai-diffusion-5-full-medium`，局部重绘使用对应 `-inpainting`。有效参数固定14 Steps、Euler Ancestral、CFG Rescale 0，Guidance 仍可调。原 High 的 Steps/Sampler/Rescale 和自定义负面词保留。
- 新 Medium 固定 UC 与透明背景标签集中在现有共享 Prompt authority，并保留 Android `PromptTemplates.kt` facade；不修改已有 Prompt 文本。Medium 请求不混入自定义基础/人物负面词，Enhance 的原参数不能反向覆盖此限制。
- 透明提示只作用于实际请求的第一块、文字渲染标记之前；请求携带真正的透明背景 hint。默认 High/不透明的旧请求结构保持。
- 完整预设、草稿/撤销、历史 recipe 通过原序列化 owner 保存新选项与旧值。PNG 参数导入识别实际 Medium ID 与93F4BD30/70AB5786已知源标记；不把未知 Enhance 源默认猜成 Full。
- 成本估算/流进度使用有效 Steps；Studio 实际请求诊断记录实际 Medium/inpaint 模型。估算不是真实计费或 Provider 验收。

## 验证边界

新增人工合成测试覆盖 Medium/High 请求、V4.5 暂停、透明提示位置、预设/recipe 回读、PNG/Enhance、实际生成适配器到历史与原图字节、透明 alpha 与局部重绘，以及普通参数 UI 开关。只在云端执行；不调用付费服务，不读用户资料。

新字段是 Desktop/shared 扩展，官方147/独立 Studio 未因此获得对应 UI，也不宣称未知旧版往返完整保留。原生窗口、真实 Provider 与用户实际验收另计。公开1235/签名17和日常H保持，当前源码不等于已交付版本。
