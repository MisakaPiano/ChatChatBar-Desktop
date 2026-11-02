# 279 — 官方147图片反推的场景确认流程

固定官方源`b4fa75d296680f777be095f3121ad0c4db979f17`。这是冻结1234/c2d之后的独立适配，不修改其原包、签名16或草稿；公开仍1233/15，日常H不操作。

现有Desktop直接将导入图片送入最终设计，只有最终候选，缺少官方147的识图后纠正步骤。现复用官方`describeReferenceImage`：视觉描述可用时沿用描述，否则调用已有`planSceneOnly`；识别完成停在可编辑场景，不生成最终Prompt。用户明确确认后，`confirmedSceneDescription`成为既有`existingSceneDescription`，复用检索与最终JSON设计，不再次识图或附加原图。不改Prompt authority或模板原文。

确认阶段保留导入人物参考、Studio extraRequirement、所选设计模型和自然语言模式；自然语言启用时目标为V5。最终候选保存实际目标，显式应用调用官方可选target helper，停止跟随默认模型；style、negative、两套生成参数和原撤销保留。旧调用未提供target时保持原行为。

Desktop运行态绑定精确草稿与反推修订：换图/移除/返回清理并取消自己的任务；迟到进度和结果不能复活旧状态。空描述不发请求，失败/取消保留已编辑描述供重试；草稿已改变则拒绝确认/应用。应用在原undo保存之后、真实updateDraft transform内再次核对selection及草稿，防止换图期间旧候选写入。

## 本片验证（待精确云端执行）

- 共享`NovelAiReverseSceneTest`三项：识图只规划场景、确认文本不附图/不要求视觉模型；空输入前置拒绝；optional/explicit target保全。
- `DesktopReverseSceneTest`七项：真实loopbackHTTP三请求及两次明确确认、V4.5默认草稿到V5候选与应用/撤销；空场景/草稿改变；迟到候选拒绝；换图精确停止；失败/取消可重试；undo保存期间换图的真实提交拒绝；实际Pane420/1100输入/确认/应用与四张云端图。暂停只定位到第二次真实undoMutex lock（第一次为action资产读取），用事件确认已捕获候选，不用sleep猜测。
- 原`DesktopDesignParityTest`的真实共享Designer例升级为两阶段，原extraRequirement归属、全局设置/草稿不变断言保留。

本机仅编辑/静态检查；无编译、测试、渲染、真实Provider调用或日常安装。脚本/测试源码不是PASS；云端原件与实际图片另核。
