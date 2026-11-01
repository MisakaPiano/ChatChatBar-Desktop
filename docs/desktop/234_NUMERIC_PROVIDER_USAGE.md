# P15 数值用量统计与实际尝试归属

2026-11-01。当前后续源码工作树，尚未云端编译、打包或真人验收；不在公开1227内。P13 cb60的CI69停在两处生产类型错误，3c4已修正送CI70；本页新增P15不由CI70覆盖。

## 已接源码

`DesktopProviderUsage`只持久化独立request／attempt UUID、已知父任务／真实会话／卡标识、实际模型及命名账户标识、时间、结果枚举和数值usage；没有请求URL、Prompt、回复、错误文本、账户名或凭据字段。缺失保持null，报告0保留0；累计SSE替换同attempt计数，重试生成独立attempt，同逻辑request不重复计次。各项Long汇总检查溢出，cache注记不重复加到prompt总量。

共享`ProviderTransportDiagnostics.onAttemptStarted`在真正dispatch前分别标记主聊天每次重试、isolated stream及Moment complete一次尝试；不改请求body、Prompt、模型参数或重试规则。原进程内原文诊断保持独立，清空界面日志不删除正在进行的数值捕获。Moments通过`DesktopUsageParent`传真实MomentTask／session／card，`moments:JUDGE/GENERATE`只用于界面分组。

当前接入聊天、角色卡／格式卡／世界书AI及朋友圈文本／调试；没有声称已覆盖所有NAI渲染、语音、向量与其他辅助请求。未知供应商／会话／父卡保持未知，不从URL、账户名或界面分组猜归属。管理的其他设置提供时间／来源筛选、总计和按模型／供应商／账户分组；尚非完整任务中心、托盘或收费账单。

本地文件位于现有local-only `diagnostics/provider-usage`，强制写入临时文件后原子替换，旧revision不能覆盖新快照，归属不能同ID变更；没有自动删除历史，也不随portable整应用备份迁移。`DesktopProviderUsageOwner`在IO合并待写快照，失败保留本次运行中的最新记录并显示独立保存错误；读失败不伪装为空统计。所有实际生产者排水后再收尾统计。已提交资料切换／恢复的RESTART_REQUIRED使用限定本地diagnostics的独占shutdown drain，普通数据准入保持关闭，完成后仍需重启。

## 待云端验证

内联合成测试覆盖重复／partial／zero／unknown／重试／Long溢出、实际HTTP SSE重试和非流式usage、真实父归属与清日志、原文／密钥不进入数值记录、重开与旧快照拒绝、本地拒写后最新快照重试、RESTART_REQUIRED下统计尾项排水且普通写入仍拒绝，以及420／1100宽度实际统计／来源筛选Scene。未在本机运行Gradle或JVM渲染。新增UI和后续source需要其自身云端结果，不借CI68／70通过。
