# 可编辑Word角色文本语义排版

用户1237先报Word可打开/可编辑及图片正常，随后明确纠正旁白/对白/内心全同排版。保留打开/编辑/图片分项，不记Word整组PASS，PNG/PDF既有both-on当前选择保存通过另记，行动off原FAIL不变。

源projection知道RoleplaySegmentKind却只调用通用Markdown后丢类型；新增只用于导出的DesktopWordSegmentStyle，保留类型和来自实际desktopSegmentMetrics/安全字号缩放的字号/行高/宽度。旁白使用原13sp/96%/淡前景、对白14sp/86%/头像相应缩进/卡片底、内心12sp/72%/muted前景与底色；不擅自把内心变成斜体，原Markdown粗体/斜体/代码等runs保留。分段关闭的普通正文沿原样式，STATUS保持原独立选择。

DOCX实际增加按类型命名paragraph style及有效styles关系/part，段落直接保留捕获的字号、行距、缩进、颜色与底色；头像、图片、主题/背景/媒体哈希继续原链，文字仍是可编辑w:t。Word允许重排版，不声称等像素圆角/alpha背景；底色以捕获主题基底合成，实际用户reader复测仍待。

合成角色三类+粗体+状态/长横线行动+头像/图片/背景用例检查真实document/styles/relationships XML、三字号/缩进/背景、可编辑字与内嵌图及内容开关；所有XML可解析、原消息不变。仅受影响Word与共同content-options suite定向待；UI17/4336原结果独立保留，不重复未受影响PNG/PDF/费用/UI或将新Word改动混记旧源。公开1237/304/sign19冻结，无新公开/merge/真实H安装授权。

9436原UI18/41770529860生产编译通过，测试编译因新用例缺ThemeMode import拒绝，0方法执行；后继只补导入并保原全部Word结构/样式/可编辑与资源断言，不把它当排版验证通过。

中断续接核对：2fb原UI19/41771363068同样停于测试ThemeMode import，0方法；972已补该导入，UI20/41771483509继续原运行，不重跑。另将新增语义段落pPr子元素按OOXML顺序写为pStyle/shd/spacing/ind/jc，并由真实生成段落断言顺序；此小修独立于UI20源码，必须另获精确后继Word结果，不能借UI20记通过。用户外出只暂停人工检查，独立工程与私有候选准备继续。
