# 281 — Studio 1235验收修复

1235已公开包及签名17不改。本轮新修复仅准备后继私有候选；实际验收回执由协调端保留，不能将参数数字/滑条子项通过当成整个Studio通过。用户截图和原Prompt只在本机，不进入仓库/云端；测试只用合成文字和资料。

## 已定位与首片源码（待精确云验）

- S10：Desktop Tools把`DesktopPromptInspectorPanel`内嵌到`DesktopStudioWorkspace`，未给可选关闭回调；外层只有页签返回，预览专注/窄屏抽屉还会遮蔽编辑区及导航。现增加固定局部返回和未被子控件消费的Escape，诊断时暂时让出遮挡；关闭后保留原预览模式/抽屉偏好，不切顶层、不清草稿、不绕过真实Studio离开协议。这个检查器是Desktop自有工具，不冒认Android同名入口。
- S01：普通Prompt、人物、参数都走`controller.edit`，原本只updateDraft，载入/清空的`replace`才保存undo。现普通有效编辑也先保存原单步持久快照；no-op不覆写，原redo及资源引用保全路径继续使用。未擅自改成无限历史或增加持久格式。
- 新增实际Workspace窄抽屉/宽专注的局部返回与偏好保留检查、真实controller Prompt/参数/删角色/no-op/undo/redo检查；既有footer几何断言保持，仅修改符合实际范围的按钮名称。只能由后续精确云端结果确认通过。

## 继续项

S02双语光标、S03官方重置、S04按模型/effort的sampler、S05 V5 effort、S06透明背景、S07人物Prompt展开与正负页签、S08展开编辑窗native chrome、S09账户header融合、S11操作失败具体原因继续，不能因本片两项源码宣称关闭。所有Prompt authority原文保持。

V5能力以NovelAI公开原始资料及实际API字段为依据；文档确认不是实际序列化/资源alpha/预设/历史验证。无需真实Provider调用，不在用户机器重构建或代装。后继公开仍需本版授权。

## S02后继源码（待独立云验）

翻译保持34sp排版以容纳10sp注释；BasicTextField原生光标随这个段落高度伸长，故跨原文/译文。后继只在译文模式按源文字体度量和当前行baseline绘制闪烁光标；原生raw text、selection、composition与输入法数据不替换，不关闭翻译，非翻译光标保持原路径。新增真实文本测量的多行/自动换行/所有偏移及尾部检查；实际焦点/IME/物理截图仍需后续验证。此源码不在458/CI141内，不能混记其结果。

## 当前官网能力来源（实现继续中）

[Effort官方文档](https://docs.novelai.net/en/image/effort/)与[透明背景文档](https://docs.novelai.net/en/image/tags/)已读。匿名读取官方image页及其当时公开JS，版本b94bdee，原URL/字节/SHA只留本机公共参考清单，不抓登录页面/账户。公开代码明确Medium的生成ID为`nai-diffusion-5-full-medium`、inpaint为`nai-diffusion-5-full-medium-inpainting`；透明选项元数据为`tag_hint_transparent_background`，官网还会派生有效正向词。字段存在不等于本应用已接线或真实Provider已验；原Prompt authority不改，后续必须核实际serializer、历史/预设和alpha保留。

## 补交D01/H01-H03与141首轮失败后继（源码待云验）

9图均已本地逐张查看，M01-M04/D01/H01-H03接收回执只在I盘。Studio生产路由移除旧诊断页签和空Studio按钮，逻辑检查器迁到数据支持诊断高级区；实际HTTP日志投影沿原脱敏owner，增加场景/已记录版本/结果过滤、归属数量、折叠卡与快照已结束清空，未知模板版本不猜。尚须继续补实际版本/阶段来源覆盖，不能用UI选项冒认全部记录已有这两个字段。

账户、刷新与现有额度/进度纳入同一状态块，复用原受保护账户弹窗；历史入口移至预览侧，并为窄屏收起保留同一回调入口。所有原工具与模型选择保留。

141精确458：Windows日志2584=2564PASS+19SKIP+1FAIL，唯一新增返回用例assertFalse失败；ManualUndo1原XML通过，原普通测试断言不降低。首因是诊断暂时解除focus遮挡，却仍按mode=预览专注分配整窗previewWidth，返回区被挤至零宽。后继让诊断暂用正常预览宽度，保存原mode偏好，并加正宽/高原断言。保留原失败，不改成环境波动；新增历史入口/过滤/精确清空检查待新源云验。

## M01–M03后继源码（未在142内，待独立云验）

朋友圈发布者头像/名字左键只走动态保留的精确会话ID，删除或重新关联明确拒绝；右键原角色卡设置/朋友圈开关，复用原编辑器及未保存离开保护，不按名字或最近会话猜测、不创建新会话。跳到同卡开关不重载未保存草稿。

朋友圈按头像、强调作者、正文/图片同列、底部时间/动作和分隔线组织；原编辑、作者选择、私密、点赞、复制、确认删除、图片查看/重生成/设卡图及任务重试保留。维护/调度/日志折到更多操作，生成中停止仍常显。

独立朋友圈相册复用固定官方147的JVM-neutral `MomentAlbumPolicy/State/Filter`：按卡文件夹、搜索、日/月/年日期、数量与私密标记；同名卡按ID分组，已删卡历史仍可见。点击相册项携带postId回到当下完整时间线定位，不用过滤后的索引。新增真实控制器导航/编辑草稿及窄宽实际Panel相册往返检查，官方六项policy内联fixture回归；尚待其精确源码云端结果，不冒记通过。M04保留多版本图片及全资源/传输链仍待实现。

142精确aa4原XML主2587=2567PASS+19SKIP+1FAIL。ManualUndo1、TranslatedCaret1、GlobalDiagnostics1、Workspace6均通过；唯一旧B1C导航用例仍要求Tools存在已明确移走的“高级/诊断”，后继改为实际从Data支持诊断进入HTTP日志/逻辑检查器并返回，同时核Tools历史仍在、旧诊断/空按钮不存在。失败原件保留，不删除导航覆盖或冒认为整轮通过。Linux job、Windows NTFS/native/updater/exit/proxy步骤均success，后继M01-M03不在该轮。

## S03/S04当前模型与S07/S08后继源码（不在143内）

现有V4.5/V5 High增加显式生成参数重置：官网公开frontend b94bdee的两者23steps、CFG5/7、Euler Ancestral、832×1216、随机Seed/单图等；不自动迁移旧设置，不改原Prompt/人物和另一模型参数。Sampler菜单复用已有实际模型白名单，不再从全枚举放出DDIM。Medium及透明背景仍待实际链路实现，不能用该重置宣称覆盖它们。

人物Prompt默认紧凑，点击直接聚焦展开，失焦自动收起；可固定展开，正/负页签轮换显示，原启停/删除/排序/位置保留。展开编辑改复用图片工具的原生同色顶栏与最小化/最大化/还原owner，保留临时编辑确认/取消和外部值变化校验，未消费Escape才取消以免抢Tag补全键。新增实际Scene焦点/固定/页签/原文保留及reset最终wire断言，尚待精确云验，native实窗验收单独保留。

另查明一个能到达S11通用失败提示的具体路径：旧footer在没有undo快照时仍启用撤销，而controller将空undo抛给通用action。后继由原repository真实load/save/clear发布可用状态，footer按状态禁用；陈旧调用返回明确无可撤销修改，不变更草稿。新增真实controller空undo/可用/清空断言。此修复不等于已证明私人截图中的那次错误就是空undo，其他触发原因仍未确认。

143精确deda主2589=2569PASS+19SKIP+1FAIL：MomentsController4、MomentsPanel1（含窄宽相册往返）、CharacterEditorController63、Workspace6通过。唯一B1C负断言错误地要求所有“NovelAI Studio”文字都消失，而原`DesktopStudioContentFrame`底部被动状态标题仍合法；后继只核空导航按钮不存在（无OnClick），保留实际诊断进入/返回和历史入口断言。原失败保留。S03/4/7/8与E01/2不在143内。
