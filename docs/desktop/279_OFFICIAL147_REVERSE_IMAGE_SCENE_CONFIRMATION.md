# 279 — 官方147图片反推的场景确认流程

固定官方源`b4fa75d296680f777be095f3121ad0c4db979f17`。这是冻结1234/c2d之后的独立适配，不修改其原包、签名16或草稿；公开仍1233/15，日常H不操作。

现有Desktop直接将导入图片送入最终设计，只有最终候选，缺少官方147的识图后纠正步骤。现复用官方`describeReferenceImage`：视觉描述可用时沿用描述，否则调用已有`planSceneOnly`；识别完成停在可编辑场景，不生成最终Prompt。用户明确确认后，`confirmedSceneDescription`成为既有`existingSceneDescription`，复用检索与最终JSON设计，不再次识图或附加原图。不改Prompt authority或模板原文。

确认阶段保留导入人物参考、Studio extraRequirement、所选设计模型和自然语言模式；自然语言启用时目标为V5。最终候选保存实际目标，显式应用调用官方可选target helper，停止跟随默认模型；style、negative、两套生成参数和原撤销保留。旧调用未提供target时保持原行为。

Desktop在进入原action资产读取之前捕获点击当时的识图修订／确认描述／待应用候选，运行态绑定精确草稿与反推修订：换图/移除/返回清理并取消自己的任务；迟到进度和结果不能复活旧状态。空描述不发请求，失败/取消保留已编辑描述供重试；草稿已改变则拒绝确认/应用。应用在原undo保存之后、真实updateDraft transform内再次核对selection及草稿，防止换图或重新编辑描述期间旧候选写入；编辑描述本身也推进修订。

## 本片验证（待精确云端执行）

- 共享`NovelAiReverseSceneTest`四项（关联视觉分支为137之后后补，待其源云验）：识图只规划场景、确认文本不附图/不要求视觉模型；空输入前置拒绝；optional/explicit target保全；真实关联视觉resolver/description不再二次scene planner，确认后不再调用视觉。
- `DesktopReverseSceneTest`八项（第八项为137之后后补）：真实loopbackHTTP三请求及两次明确确认、V4.5默认草稿到V5候选与应用/撤销；空场景/草稿改变；迟到候选拒绝；换图精确停止；失败/取消可重试；undo保存期间换图／重新编辑场景两种真实提交拒绝；实际Pane420/1100输入/确认/应用与四张云端图；原action前置读暂停时Stop使尚未启动的重识别／确认请求失效，描述再编辑也不能被旧确认动作隐式采用。暂停只定位到第二次真实undoMutex lock（第一次为action资产读取），用事件确认已捕获候选，不用sleep猜测。
- 原`DesktopDesignParityTest`的真实共享Designer例升级为两阶段，原extraRequirement归属、全局设置/草稿不变断言保留。

本机仅编辑/静态检查；无编译、测试、渲染、真实Provider调用或日常安装。脚本/测试源码不是PASS；云端原件与实际图片另核。

137/1e6de51c正在云验。后补关联视觉回归与Stop在任务预处理期间使修订失效的保护，仅后继源码，不归137；测试/渲染仍不在本机执行。

## 精确137结果与后继范围

137/1e6de51c已实际结束：主350suite/2581=2561PASS+19SKIP+1FAIL。唯一失败为原DesktopPhase7ReauditTest第211行静态检查仍在旧Panel源查找已移动的Markdown控件；实际新Pane及其父页面接线存在。最小修正改为先核Panel调用Pane，再在Pane源核原两Markdown控件，原stage/fullcontent/reasoning与guidance/history断言全部保留。

新增DesktopReverseScene7及原DesignParity7实际PASS；共享反推3/shared1191、Android586、proxy5/NTFS7/native37/TUF14/exit4通过。协调端唯一原件`I:/CCB-Desktop-Continuity/updates/20261103-coordinator-ci137-review/`，四图已归档，视觉评审另记。整轮仍FAIL，不能称本片完整绿。

后补关联视觉第四共享例、Desktop第八前置Stop/确认/再编辑例、实际提交时描述再编辑失效、点击时快照绑定、原其他图像任务停止入口，以及这条静态归属修正，均等待下一精确源云验；不倒算入137。
