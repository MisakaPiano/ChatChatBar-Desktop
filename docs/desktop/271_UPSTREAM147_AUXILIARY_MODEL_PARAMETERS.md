# 271 — 1.4.7 世界书与格式修复参数接线

固定上游147 `b4fa75d296680f777be095f3121ad0c4db979f17` 对比141：世界书生成/修复不再传 `isolatedTaskParameters=true`；检索规划/摘要同时取消 `enableThinking=false`、`reasoningEffort=low`；消息格式修复仅取消 `disableThinking=true`。这是模型请求参数变化，原Prompt文字不变。Desktop此前shared移植仍保留141规则，不能仅以Android路径/文件存在称147完成。

本片按原授权适配：`streamWorldBookAi` 与本Fork `AndroidWorldBookAiGateway` 采用所选模型的输出上限、思考/effort、自定义参数；仍用原operation/stage、模型/provider解析、任务封装和120秒socket/inactivity预算。CREATE/FILL的生成及JSON修复、RESEARCH规划和BRIEF摘要均走同一原transport；不引入额外请求/重试/模型或新Prompt。

格式修复在 `isolatedFormatRepairStream` 调用点明确 `disableThinking=false`，不改通用helper默认；Desktop原isolated字段过滤、消息封装保护、完整正文、600秒超时、TLS/代理/取消/拒答/截断处理保留。思考仍与候选正文分离。本Fork Android service同样移除原显式disable；未冒称两平台无关custom字段规则全部相同。

必要证据源码：新shared四个operation HTTP捕获例，各使用legacy enabled-thinking与reasoning-effort-only两配置，核实际endpoint/auth、完整原serializer等价、输出参数两种键、temperature/seed/top_p与思考原值、八消息角色和内容/思考分离。原Android/Desktop六stage HTTP逐值对比继续，新增显式字段检查而非只两端相等。原format HTTP/真实localhost HTTPS例保持golden/envelope/过滤断言并核typed enable=true/effort=high；新增Android service两实际HTTP例。不发真实provider请求。

此片在4e5f人物改名提交之后的独立源码，尚未编译/执行，不混入122/d953结果；validated141、公开1233/15与真人待保持，不安装日常根/手机APK。普通稳定JAR升级链及其他正式1.0缺口继续按216推进。
