# 340 人物生图Prompt的标签辅助入口

固定32906a76的人物编辑增量把结构化人物 `imagePrompt` 改为Tag输入、聚焦辅助与中文注释、隔离的展开编辑。Desktop原 `CharacterEntryFields` 只有普通BasicTextField；Studio已有Tag组件不能代替人物入口。

本次复用 `StudioPromptField`，由应用懒提供既有本地目录／翻译服务与全局注释偏好；不新增AI请求、不改Prompt默认值或原文。沿用原人物workspace的draftSession／person／detail key，不新建选择框架；局部历史额外使用card／draft／person scope。回调捕获当时的会话／卡／人物标识，字段卸载后停止发布，controller核身份后仍走原updateCharacter语义。未配置辅助服务的最小controller会明确提示并保留原文本编辑，不冒称Tag功能已可用；日常应用factory实际接入完整服务。

新定向用例使用实际人物入口与真实云端Window、合成本地词库：候选选择、注释偏好、展开取消／确认、切人物丢弃未确认内容、旧字段回调与新卡草稿隔离、实际保存原人物字段。只跑此入口，不重测全角色／Studio或真实Provider。

R70／42359540653 原47598B、SHA332e4a70…00288的CRC/source/XML及两张原图已核。一方法到保存断言失败：合成卡未填必需greeting，实际validate:934正常拒绝；不能记为人物保存业务回归，也不能绕过校验。此前候选、中文注释、展开确认／取消、切人物和旧inline回调隔离断言已过；保存后回读、新卡隔离及最终无任务尚未到达。host图底部被任务栏遮住，不作短窗footer验收；展开图控件可见。

c7086ee0仅给合成卡补开场白、为保存断言增加problem枚举诊断，不改生产行为。R71／42360606156 同一native单类后继原45415B、SHAaaf92a6a…6cdac5，已独核CRC/source/全部XML与空stderr：唯一方法完整PASS、0FAIL/ERROR/SKIP。实际保存／仓库回读、新卡复用人物ID的旧草稿拒绝及最终无任务均已走到；两张原图已查看。详本地 `OWNER_CHARACTER_PERSON_TAGS_R71_FINAL_REVIEW.json`。不是全角色／全进程／真实Provider或真人验收，不改变旧1244真人FAIL。
