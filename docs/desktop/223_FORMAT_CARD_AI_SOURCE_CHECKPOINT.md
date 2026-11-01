# P12 格式卡 AI 源码检查点

2026-11-01 JST。此为**后续独立源码，尚未交付或完成验证**，不属于已公开1225/source54008。用户已自行更新，Studio／模型配置及草稿重开明确通过；三种导出报PREVIEW_RENDER，未通过，不把局部结果扩大为整批验收。

## 已接通的纵向流程

- 新格式卡正文／工具为空时出现AI入口；参考一张已保存角色卡，选择精确模型或跟随默认，填写可选要求并支持展开编辑。
- 共享`FormatCardAutoFillGenerator`保留原解析、完整字段、按序工具校验、流完成要求和至多一次JSON修复；Android原服务继续负责同一AiTaskRun及fresh model/auth，改为薄适配。原11个测试搬到共享模块，不删除用例。
- `FormatCardAutoFillPromptAuthority`是原格式卡Prompt块的逐字搬迁，Android目录／facade保留，指纹包含新owner。不改已有文案，不读预设样例。结构化人物完整文字与当前自由正文边界保持，无图片／声线／文档／世界书／路径进入资料输入。
- 桌面流式原文、修复文、独立思考、候选、取消及请求日志已连接。实际应用重新检查`targetId/base`、draftSessionId及payload UUID，保持应用时手填名称／默认标志；只编辑并安排普通草稿保存，仍需明确保存实体，不绑定角色或会话。
- 应用级`withCardDesign`登记结构化子任务，纳入维护与关闭排水；数据普通操作lease保持。未复用Fish/Memory去cap或禁思考的facade。

## 已完成与未完成检查

首轮编译因未指定本机Android SDK在准备阶段失败，原log保留；指定既有SDK后桌面与Android主编译SUCCESS。一次实际HTTP RED准确揭示共享辅助通道会过滤custom envelope字段而原Android格式卡不滤，原失败保留；现仅格式卡`protectEnvelope=false`，原其他辅助任务默认保护不变。

`format-ai-r02`共享四类**37项新执行全PASS**：原parser/policy/collector11、generator5、实际格式卡HTTP1、原format-repair transport20。包含完整长文本、模型cap／thinking／custom参数、独立reasoning、单次修复和终态错误不进修复。

**完整r02没有通过结论**：用户反馈整台电脑卡到无法验收，已立即中断本机编译／测试；对应Gradle client、单次daemon和Kotlin编译器均退出，用户确认“已缓解，刚才整台电脑都卡”。桌面test compilation当时进行中，桌面新测试／图形与Android实际HTTP对照均未执行出结果，不能拿37项代替。该中断不是产品测试FAIL或PASS，不提高超时、不删除断言。末尾另补的合成asset reader和关闭保护亦待云端编译测试。

保留本机`format-ai-compile-r01/r02.log`、`format-ai-red-r01/`、`format-ai-r02/`及中断日志。后续重负载检查转云端，验收期间不在此电脑重跑；源码检查点推送后核实际CI结果，再修实际失败。没有生成P12新包、执行日常安装器或新增公开发行。P12其他角色／世界书AI子项尚未据此完成；社区最后及T23/SF10/自由正文绑定等具体暂缓保持。

73e0bd63已正常提交／推送并触发CI50／41265225594。Linux实际失败在新Android对照测试缺`mockwebserver`测试依赖，尚未执行该测试，不是模型请求行为断言失败；原job log本机保留。后续仅补既有版本目录的`testImplementation(libs.okhttp.mockwebserver)`，不改运行依赖／协议或删除对照。Windows当时仍在运行，不预判通过。另补重复关闭fixture保护与仅写现有CI测试报告目录的合成Scene图环境，结果需以后续精确源核对。
