# 339 V5 最终请求的 straight_alpha

固定12e的 `NovelAiImageService.buildRequestBody` 在Enhance元数据覆盖后对所有V5请求写 `straight_alpha=true`，来源为54c透明背景参数增量。Desktop共享owner此前没有此字段；已有透明开关／hint或V5Options测试不能替代该最终字段。当前只确认序列化差异，不据此宣称真人透明图片已损坏。

最小补该字段到最终参数对象，覆盖High／Medium、透明开／关以及普通图生图／聚焦重绘／Enhance。V4.5不新增。保留Desktop原High关闭透明时的params_version=3和省略hint选择，不改Prompt原文、UC、费用、存储、读取白名单、重试或凭据。

`NovelAiV5GenerationPolicyTest` 新增最终action体字段检查，并在既有真实parseEnhance路径补断言；其他未变断言一并保留。R69仅定向该共享类，待原件，不做真实付费出图或全项目重跑。旧字段缺失有确定源差异，不另造旧源CI配对。
