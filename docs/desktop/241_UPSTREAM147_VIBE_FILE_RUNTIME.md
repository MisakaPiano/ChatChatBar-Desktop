# 正式1.4.7适配：外置Vibe编码运行链

2026-11-02补验：8b2ffdfa／CI80完整SUCCESS，Windows2380PASS＋19SKIP、shared1109／Android582通过；本页共享payload存储2项及真实认证导入→mapping→guidance→HTTP wire两项实际通过。不是导出回导回环或真实付费调用。后续旧inline流式投影已进入独立工作树，见[242](242_UPSTREAM147_STUDIO_STREAMING_JSON.md)，尚待其精确源云端，不混入CI80或公开1229。

2026-11-01 JST。后续源码独立于公开1229／4fefe7ca；正式baseline尚未提升，Prompt未改。此问题来自官方147数据格式的运行时适配，不是邀请用户的账户代理超时原因。

现有备份流、资源注册、选择统计与合并已识别`novelai-vibe-payloads/<hash>`并保留`chatbar-vibe-file:<hash>`。不重做传输。旧DesktopGuidance把任意非空encodedVibe直接放入prepared encoding，ImageService随后把它写到实际reference_image_multiple；合法外置引用可能变成最终wire中的标记。

新增共享NovelAiInlinePayloadStore，复用官方147目录／标记与4096字符阈值。resolve严格验证64位小写hex、真实根身份、普通文件、UTF8、内容SHA和非空编码；未知／损坏原件保留，不尝试自动重新编码。retain对大编码做内容寻址的派生文件，保留已有正确文件、拒绝未知碰撞；小inline仍原样可用。构造和失败resolve不创建目录。DesktopGuidance通过真实AppDataOperationGate进行准备，wire只用解析后的值，retainedGuidance用于草稿／recipe／历史的值改为文件引用。

新增共享存储回归及真实合成加密v2/code86备份验证→staging→既有merge mapping→实际guidance→捕获HTTP请求测试，检查wire包含原编码而非标记；没有配置NAI token的guidance仍可处理已有编码，排除隐式encode／计费。另覆盖legacy inline、缺失／非法／篡改文件、并发相同编码保留与源字节不变。当前新源待云端验证，不冒认CI79覆盖。

在8b2／CI80范围内，官方openCompactJson／旧大inline单例解码的流式压缩尚未实现；后续独立源码和验证见242。不能把本页resolve／retain当成大型旧JSON峰值内存已解决，更不能据此宣称完整147兼容或断电事务。
