# 348 后处理换图时的输入身份

核对148完整Screen时发现Desktop后处理Panel的输入 `produceState(..., path)` 在换key首帧仍可能保留旧value，而来源metadata等 `remember(path)` 已换新；新图片读取未完成时会短暂显示旧尺寸／费用并允许提交旧bytes。先前的结果recordId／bytes绑定只保护结果，不能保护这一输入边界；未据此推断真人已发生错图请求。

整个来源Panel增加 `key(path)`，换图时卸载旧输入／元数据／费用／状态和页面action scope，新来源从空输入开始。应用任务仍归原controller，不取消已经明确启动的任务；原文件、结果归属、全局准入与API不变。输入dispatcher默认仍IO，仅可为实际回归注入受控dispatcher以保持新文件读取，避免用文件大小或延时制造概率性竞态。

定向一个实际Panel方法：首图读取后可用→换第二图但保持物理读取排队时无旧尺寸且禁止提交→旧来源捕获的按钮回调不能启动任务→完成读取后显示第二图正确尺寸→换不存在来源仍拒绝，两个原文件bytes和空任务／结果保持。只检查这一个新边界，不重跑R79旧DDIM／V+、R80数值、R77窗口或R67大图。原件待核，148整个Screen暂不提前提升。

实现e3b24697，后继R81／42366591503（control `97fd0fbd9e4d94eff7767e42d5d83050c5e7a029`）只跑新的实际来源Panel身份方法。原R79／R77／R80有效结果继续复用；R79 Panel整体blob已是历史源，本次仅加来源key封装和默认IO注入，不能说当前文件仍完全等于R79。

R81／42366591503原10112B，SHA256 `3c6d1961b31100c1fa993ad110a956de91ddd8d95076f73c2fe7edd05db8b602`，已核CRC／source／全部XML与空stderr，唯一实际Panel方法完整PASS／0FAIL/ERROR/SKIP，所有持有读取／旧按钮／正确第二图／失效来源及原bytes断言走完。当前Panel和test匹配e3普通源；内层Panel body对R79仅dispatcher注入一处替换，默认仍IO，其余逐字相同，因此R79原DDIM等不重跑。仅新来源身份链，不冒称付费请求／现用原生窗或真人验收。
