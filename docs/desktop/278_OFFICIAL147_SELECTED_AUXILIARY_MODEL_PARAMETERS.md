# 278 — 官方147辅助任务继承所选模型参数

本片是1234冻结应用`c2d3640e`之后的独立后继源码，不修改其原包、签名16或草稿431654495。固定上游为`b4fa75d296680f777be095f3121ad0c4db979f17`；正式完整baseline未批量提升。

## 首因与最小修正

- 官方MemoryAiGateway的thinking-disable helper返回false，planner model保持this，planner/JSON/stream三出口不再强制isolated。当前共享源码、MemoryRagTextClient默认值、Desktop适配及streamMemoryRag此前仍逐层清思考参数、禁止非隔离调用并去掉输出上限。现普通路径保持所选ModelConfig与原custom优先级；显式isolated=true出口仍保留旧保护，其他任务的helper默认不动。
- 官方RetrievalPlanner保持所选model、disable=false，生产总期限为120000ms。现对齐生产期限与实际错误提示；仅注入默认仍为Dispatchers.IO的dispatcher缝供虚拟时钟核20秒成功与精确120秒取消，不扩大测试watchdog。
- 角色研究PLAN/SUMMARIZE不再强制false/low和64/128预算，600秒读／无输出期限保留。角色补全／改写GENERATE/REPAIR不再注入512/256预算。Android原facade和Desktop transport同步，不改候选／修复／保存边界。
- 图片转外貌文字仍强制JSON并保留120秒读／无输出期限，但不再强制关闭思考或参数隔离；原默认／linked视觉模型路由和资源取消保护不变。
- Fish翻译／标签沿原ModelConfig发送，去掉共享service与Desktop transport的二次无上限／关思考覆盖。原Semaphore2、JSON envelope、传输／输出分类重试、任务／日志／usage归属及持久化保留。

未修改任何Prompt authority、模板原文、JSON schema、serializer通用规则或数据提交逻辑；WorldBook、FormatCard和Moments既有已对齐路径不重写。

## 验证范围

新增`SelectedAuxiliaryModelParametersTransportTest`：原默认、非默认legacy和reasoning-only三模型实际loopbackHTTP核研究两阶段、角色生成／修复、图片JSON及Fish原service两任务的final body、cap alias、thinking/effort、sampling/custom与一调用一请求。

新增`DesktopMemorySelectedModelParametersTest`：真实共享memory planner→summary→JSON repair、streaming Episode和RAG经Desktop最终HTTP；原head retry/usage与正式voice runtime统计例扩展同一final-wire断言，既有请求计数、取消、重试和数据断言保留。原显式isolated无上限例改为明确传true，继续覆盖该独立出口。

新增`RetrievalPlannerDeadlineTest`使用虚拟时钟验证生产120秒边界，非真实服务或等待两分钟；Android原参数policy/parity测试改为官方147目标语义，不放松无关断言。精确35c5216/CI136（41485455158）双job与原件已闭合：主349suite/2574=2555PASS+19SKIP、proxy5、NTFS7、shared1188、Android586、native37/TUF14/exit4通过；新增共享HTTP4+虚拟deadline1、DesktopMemorySelected2及原HEAD/Fish usage方法实际执行。协调端原件位于`I:/CCB-Desktop-Continuity/updates/20261103-coordinator-ci136-review/REVIEW.json`，不重复下载。后继279图片反推不由此代验。本机不编译、不启动应用或付费调用；原R7/R19/R20/R23无关证据复用。
