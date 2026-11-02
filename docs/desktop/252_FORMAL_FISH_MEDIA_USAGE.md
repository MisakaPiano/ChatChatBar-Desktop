# 正式 Fish TTS 请求与本地媒体用量

2026-11-02：P15-06／07、D-042 原范围源码增量，尚待此精确源码的云端执行；不提升完整媒体覆盖或公开交付状态。

## 实际接线

`DesktopChatVoiceRuntime` 的原默认 `DesktopFishVoiceTransport` 接现有 diagnostics owner；正式 TTS 在原 enqueue、HTTP response 和 callback/body drain 边界记录。一份逻辑请求保留原拒绝重试产生的物理尝试，沿用共享序列化、冻结凭据／账户、免费资格、one-shot／one-exchange、取消与关闭排水。没有新增供应商调用、重试或付费回退。

统计字段只有实际来源／任务／会话／卡／消息、模型／供应商／账户 ID、时间／结果及本地媒体测量。发送字符取真正传入原请求工厂的 trim 后字符串，按 Unicode 码点计数；每次真实重试独立计数。有效音频时长和字节只取原持久化返回值，失败或未知不填 0。HTTP 状态取实际 response。供应商计费量为 UNREPORTED，不用这些本地数值推算账单，也不混入 Token。

`DesktopMediaDiagnosticRecorder` 只提供有界、进程内元数据；不读取或存储合成正文、请求头、reference ID、音频字节或路径。清空日志保留原数值统计；统计写入故障可见且不触发重放。原数值 owner 的异步失败待存和关闭重试仍有效。

## 数据与界面

Token 本地记录保持 schema 1、默认省略 media，既有文件不重写。媒体记录用本地 schema 2；不能混填 Token、负数、错误 schema 或改写物理尝试身份。这不是官方 Entity、备份或 Android baseline 升级。旧版诊断 reader 可能拒绝新媒体记录，不声称已实测旧二进制读取能力。

汇总先按 revision 去重，再以 checked Long 相加；媒体尝试不计入“未报告 Token”次数。统计页可筛选 Token／Fish TTS，分别显示本地单位；原聊天调试页按媒体类型显示 HTTP／尝试与本地量，不套用不存在的 LLM JSON／SSE 内容。

## 必要验证与剩余范围

- `DesktopFishMediaUsageTest`：合成 loopback 429→200，切账户仍冻结原 key／ID／payload；401、成功响应坏音频不重放；取消重试等待／已发无响应请求、清日志、拒绝 dispatch、数值 sink 故障；核原 callback drain 与本地重开。
- `DesktopMediaUsageCustodyTest`：旧记录字节、未知与实测 0、单位隔离、revision 去重、溢出和拒绝写入保全。
- `DesktopMediaUsagePanelTest`：420／1100 元数据日志、类型过滤、私密内容省略及本地单位云端实图。

这些测试尚未执行。本机不启动编译、测试或渲染；不访问真实 Fish 服务。NAI 生成／处理、独立试听／缓存，以及按会话／单请求／账户细分和超过 30 分组的完整 UI 仍是后续原范围，不把本增量写成完整 P15 或账单覆盖。成熟候选与本增量分开交付。
