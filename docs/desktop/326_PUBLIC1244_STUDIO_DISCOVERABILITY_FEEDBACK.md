# 1244验收反馈：预设入口与工作室信息栏

2026-11-07。用户最新合并反馈原回执与截图仅本地保存在Continuity/updates/20261107-user-1244-functional-feedback；源码执行者已实际查看该图，不上传用户截图到GitHub、CI或Release。

用户已确认A2文档PDF、A3朋友圈最新成功图显示、A4更新代理、B1画风应用保存重开、B3 Desktop完整Prompt通过，保留这些未受影响回执，不要求重复基础验收。A1仍为1244旧版本USER_FAIL，既有77PASS补修尚未交付，不把它当当前后继源码新失败。C/D/E明确未测；实际合并及外部跨端范围不推断。

B2初次反馈是入口不易发现；后续用户明确完整生成预设可用，但“生成预设…”点一次后不可再用，需要切页回来。最新回执为本地`USER_B2_PRESET_REOPEN_FAILURE.json`。仅确认用户实际说明的可用范围，不推断所有资源分支或跨端兼容。当前明确入口“导入生成预设…”，继续选择.ccbpreset→校验/预览→用户确认替换原草稿，保留取消与原撤销，没有改成直接导入。

用户图中当前账户/齿轮/刷新虽然靠右，却独占第一行，额度和会员在其下，使信息栏偏高。后继将额度/会员文字左置、原账户/齿轮/刷新右置在同一个响应式信息区域，宽度不足时先信息后控制并合理回流。保留Anlas、V5估算、Opus百分比/用尽/未知、刷新中与错误及原进度条语义；不改账户密钥或刷新调度。

R18（42342909490/controlcff779/normal930e）原5套24方法=23PASS、1既有原生桌面前置SKIP、0FAIL/ERROR。账户未知/已加载余额与会员各220/360/600/640/1100共10合成场景、左右操作点击在原布局方法中全部执行；已实际查看loaded600及220/360图。宽时同区并排，窄时信息先于回流控制，原进度和账户语义保留。PresetExchange原1方法完成预览/取消/确认/撤销，但它仅测Content、working/closing固定false，不能证明最新真实窗口外层与同页再次打开问题已修；此缺陷继续定位和专门后继验证，不借原PASS代验。

原ZIP137232B，SHA256 `5deef6df021aca24f58cc4b02619b4de07861273a97e301a934d13cb35f5f732`，CRC/source/逐XML已核。R16/R17同一DSL编译失败且零XML，930e将BoxWithConstraints宽度先捕获再进入Row后执行R18，旧原件保留。生成审图素材只用人工合成账户/状态，不以用户截图作测试夹具。下一批成熟候选集中复测失败与受影响部分，不新发逐项问题或重复已通过项。公开1244/签26保持，不代装、不操作现用真实资料。

外层回归R19（42343842566/controlab79）原1FAIL/0SKIP；system-err显示首个FullPanel的T5读取收到通用JSON假asset，发生Not in GZIP format后界面协程退出，不能当作用户同条件重复入口业务反例。d82将夹具限定为仅presets路径使用inline空manifest，分词器/其他类型委托实际asset reader，不读取预制preset内容、不跳过token producer、不放宽等待，并记录每次open序号。实际Studio入口→实际Exchange Window的R22原生后继待原件；生产预设逻辑尚未猜改，用户FAIL仍待解决。

进一步源码分支：原主入口只有`showingPreset=true`，已存在但被遮挡/最小化的Window再次收到true不会还原置前。用户当次未说明是否灰掉或被遮挡，因此这只是明确存在且可能吻合症状的分支，不能推定其真实根因。692c的R23将主窗也改为真实native Window并用限定窗口内的Robot点击，先确认实际关闭/再开，再检验最小化旧child后主入口唤回同一实例。3ade仅增加预设显式activation revision，Window按该信号还原并下一frame请求置前；默认未传信号的其他工具窗不触发，不移除busy、working/closing、取消join、预览/确认或原草稿保护。R24同夹具及原Controller/Content/工具窗native关闭方法待原件，不先宣告用户问题闭合。

## 原生窗口后继结果

R22原件（42344551307，normald82/control996455）1PASS/0SKIP，实际关闭/原生X、chooser取消、无效导入、预览取消、导出、应用、undo及同页重开全部执行；因此有效dispose后重建没有复现用户失效。R23（42344939398，normal692c/control4b1d）原1实际FAIL/0SKIP，stdout为attempt1–4且stderr空，失败正是已有最小化child再点主入口不能还原；其后I/O阶段没有执行，不把R22拼成一次完整旧链。

R24（42345020110，normal3ade/control225f）与R23同一夹具blob，原4套7PASS、0FAIL/ERROR/SKIP，全部stderr空。真实native主窗/Robot点击与子窗完成attempt1–6，最小化后唤回同一实例，再完成chooser取消、坏包、预览取消、导出、明确apply/undo及重开。原Controller4/Content1/普通工具native关闭1亦通过，原进行中保护和默认工具窗行为保留。仅缺陷分支工程闭合，用户1244当时确切原因仍未知、原USER_FAIL等待成熟批次实际复测，不代装或单独发包。

R23原ZIP11687B SHA256 `7dd97ea983764c75250fd2dba244277bb732ae056acdb3ea04adce6fe755f65c`；R24原ZIP16454B SHA256 `f12dbf952b6ea6669fce6f84d2d394b210de9c44477697c6e9ebdb4b454ff0a4`，SHA/CRC/source/全部XML及stdout/stderr已核。本地`OWNER_PRESET_WINDOW_R23_R24_REVIEW.json`同时保留R19夹具失败与R22有效路径结果；四原件分开，不相加为新增10项。
