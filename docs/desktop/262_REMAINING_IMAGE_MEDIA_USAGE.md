# 262 原朋友圈配图与图片处理用量

当前源码补齐原P15剩余媒体链，尚待本源云端验证；不混入冻结1233/2ba或由CI113代验。

- 原MomentGenerationCore增加末尾可选imageGeneration流适配器，默认仍是原imageService.generate。Desktop定时/调试两个factory及手动补图复用原观察流；共享Prompt、seed、retry、checkpoint、Final处理和保存顺序不改。无效raster或Error不能因外层结果正常返回成为网络成功；有效Final的网络结果在原本地materialize之前收尾。
- DesktopMomentGeneration在实际入口原子冻结当前NovelAI账户身份/凭据，已有同owner frozen call保持；中途切换不改变已启动请求。手动补图沿原launchOwnedWork UUID附post/session/card，定时继续原task ID，不另建业务Job。moments:IMAGE只是原请求日志的显示分组，新增动态配图tab；数值真session另存。
- Enhance/Upscale仍调用原shared服务和原validation/cancellation/retry限制，分别为NAI_ENHANCE/NAI_UPSCALE，验证后的图片数/字节独立计量。Upscale接口没报告模型，不从来源图猜模型。
- Vibe只观察原cache miss物理Call；原共享core在有效编码返回后、原cache写之前仅回调数字byteLength。缓存/已编码payload复用不新增行；cache写失败不抹除已返回网络结果，也不自动再请求。原V5不支持、缓存key和原子写保持。NAI_VIBE_ENCODING只用请求编码次数/有效编码字节，不计图片/音频/Token。
- 本地schema2新增nullable编码字段以NEVER默认省略，旧Token1/音频2原文件不改；严格单位、不可变requestedEncodings和原revision去重/溢出检查保留。新增类型筛选、分组和原debug元数据展示，不保存Prompt/头/图片/编码正文；账单仍UNREPORTED。

必要验证包括DesktopMomentImageUsageTest（三例：完成checkpoint真实core到本地失败、原manual worker/受控A→B切换、无效raster和取消），DesktopImageProcessingUsageTest（三例：真实Enhance/Upscale及429不额外重试、Vibe miss/两种复用与custody、缓存写失败/401/取消），原Panel新增窄宽Vibe单位/筛选回归。全部人工合成/loopback，不发真实付费请求，不在本机编译或渲染。

公开版本/签名不在此源码动作中改变，真人参数/声音/真实合并验收不由这些回归替代。
