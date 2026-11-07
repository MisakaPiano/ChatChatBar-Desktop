# 340 人物生图Prompt的标签辅助入口

固定32906a76的人物编辑增量把结构化人物 `imagePrompt` 改为Tag输入、聚焦辅助与中文注释、隔离的展开编辑。Desktop原 `CharacterEntryFields` 只有普通BasicTextField；Studio已有Tag组件不能代替人物入口。

本次复用 `StudioPromptField`，由应用懒提供既有本地目录／翻译服务与全局注释偏好；不新增AI请求、不改Prompt默认值或原文。沿用原人物workspace的draftSession／person／detail key，不新建选择框架；局部历史额外使用card／draft／person scope。回调捕获当时的会话／卡／人物标识，字段卸载后停止发布，controller核身份后仍走原updateCharacter语义。未配置辅助服务的最小controller会明确提示并保留原文本编辑，不冒称Tag功能已可用；日常应用factory实际接入完整服务。

新定向用例使用实际人物入口与真实云端Window、合成本地词库：候选选择、注释偏好、展开取消／确认、切人物丢弃未确认内容、旧字段回调与新卡草稿隔离、实际保存原人物字段。只跑此入口，不重测全角色／Studio或真实Provider。R70待原件，未提前记通过或交付。
