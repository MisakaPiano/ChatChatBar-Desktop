# 260 NovelAI 固定操作路由与临时聊天生成器归属

冻结1233／2ba39c中的原 `secureNovelAiClient()` 只准许HTTPS、image.novelai.net:443及subscription／generate-image-stream。`DesktopNovelAiGuidance`却给原VibeCore使用同一客户端，原Upscale也走这个默认工厂，分别在encode-vibe／upscale路径被本机拒绝。已有编码复用不触发encode，因此不能用其测试替代新参考图首次编码；V5不支持Vibe是另一模型限制。1233最终说明、草稿body和local signed15-release-r02已明确列出，包字节未改，旧r01记录保留。

## 最小修复与保留行为

- 工厂增加固定枚举用途，GENERATION仍只允许原两路径，VIBE_ENCODING只允许`/ai/encode-vibe`，POST_PROCESS只允许generate-image-stream／`/ai/upscale`。没有任意URL／路径输入，不扩大scheme／host／port，不允许自动redirect或连接重放。
- Guidance默认使用编码用途；PostProcessor默认使用处理用途。原共享请求、验证、缓存、V4.5／V5规则、成本和取消不改。没有真实供应商请求或重新启用旧Phase7计费探针。
- 259的生成器本体已接观测，但后补发现聊天手动生成／重新生成、自动生成创建临时runtime，未传diagnostics；这几条调用补原owner。自动路径用惰性supplier，在实际生成时读取，保留原lazy依赖图与任务parent。不能把b6首次通用runtime测试说成临时调用链已覆盖。

## 必要验证（新源待云端）

`DesktopNovelAiEndpointPolicyTest`三项：实际production guard对三个用途的固定路径／外host／HTTP／port拒绝；原Guidance→VibeCore新图首次编码通过guard再由末端stub响应，二次缓存与已有encoding不重复调用；原PostProcessor→Upscale通过guard成功及429仍仅一次。默认generation guard对encode／upscale仍拒绝，原用途不被悄悄放宽。

原`DesktopAssistantImageActionTest`补实际临时聊天runtime→loopback→真实任务／持久图片／会话日志与数值owner，保留原消息和归属。原Enhance参数／取消、Vibe inline／外置编码／坏hash／缓存和Studio回归继续；这不是实际服务器出图、付费行为或所有媒体计量完成证明。111原失败按其实际报告分列，不让本修正掩盖该首因。

111六张新增图片日志／统计实图已核，图片统计行误用“非Token均是Fish TTS”的旧二分标签；后补按media.kind显示对应类型，并在原420／1100 Scene检查图片标签及无Fish误标。原图片／音频／Token单位与未知计费边界保留。

## 113实际验证与异常类型后补

113/ac522主331suite/2519=2499PASS+19SKIP+1FAIL，唯一guard矩阵把HTTP分支也预期IllegalStateException，但更早ProxyAwareClient明文检查实际抛IOException且未到末端stub。按原实际顺序仅HTTP精确预期IOException，wrong host/port/path继续IllegalStateException；reached不增加、TLS/redirect/retry原断言保持，生产代码不改。原V4.5首次编码+cache/retained复用、Upscale、临时chat实际HTTP统计与图片标签均实际PASS，导入旧加密preview和新两交接/stop例也PASS；proxy5独立通过。新媒体9746不由113代验，修正待下一精确源。
