# 259 原生图网络尝试与独立图片单位

后续P15-06／07增量，尚待精确源云端验证；不在冻结2ba39c的1233候选包内。正式Fish和生成试听复用104／108已成立范围，不因此重做。

## 本片入口与真实边界

- 原 `DesktopNovelAiGenerationRuntime` 的Studio／聊天生图与 `DesktopCharacterImageGeneration` 的封面／头像候选，在原共享 `NovelAiImageService` 请求上附加只读observer。每次原newCall进入应用拦截器时记一次；原429重试产生后续尝试，流帧不是新请求。不改原凭据、序列化、超时、重试、解码、图片合成或原子保存。
- 原冻结 `DesktopCredentialCall` 提供实际账户ID；无冻结账户则保持未知，不额外读取／猜测选中账户。原 `DesktopUsageParent` 提供任务、聊天、卡片、消息或编辑器身份。角色图片明确传入真实卡／草稿／人物，原withCardDesign把其已注册UUID补为taskId，不把草稿当ChatSession；其他调用默认继承原上下文。
- 只累计原验证通过的final图片数和原返回字节；中间预览不计为结果。有效输出确认后结束网络记录，再走原ensureActive／NonCancellable保存；本地保存失败保留已完成网络记录和原任务失败，不重放。取消和无响应保持未知HTTP／输出，不能推断供应商没有受理。
- 日志沿原 `DesktopTransportDiagnosticsOwner` 和DebugOverlay，仅方法、去凭据URL、HTTP、次数／图片测量；不收集Prompt、headers、参考图、base64或图片正文。清空对应组不复活旧条目、不改数值统计或其他任务。
- Studio保留原高级／诊断PromptInspector，另有“生图日志”沿实际generationTaskId；其他操作改变taskId不会把旧生图日志入口指向别的任务。角色候选复用同一入口与实际编辑器组；聊天沿现有会话日志。打开／关闭／清空均不调用供应商或修改草稿／图片。

## 本地数值格式与界面

`DesktopUsageMediaKind.NAI_IMAGE_GENERATION` 使用已有本地schema2；新增可选requestedImages／outputImages／outputImageBytes，Fish／Token旧文件原字节保留。图片不允许混入音频／字符或Token字段，Fish不允许混入图片单位，单次记录的请求数量与身份不能被后续revision改写。新单位按尝试去重、checked sum，未知与0保持不同。管理统计可筛选“NovelAI生图”，明确网络输出不等于保存／应用、不等于Anlas账单。

## 受影响检查（未执行不算通过）

- `DesktopImageMediaUsageTest` 五项：真实loopback429→成功，固定凭据／账户／seed和真实父信息，原frame计数，401／503／坏frame／preflight与取消，日志清除后数值保留，本地保存失败不重放，角色图片及sink失败保全。
- 原角色controller用例检查实际TaskRuntime child、卡／草稿／人物parent；候选应用／持久保存原断言保留。
- `DesktopMediaUsageCustodyTest`补三类并存重开、旧文件字节不变、混单位及改身份拒绝。
- 原Panel Scene增加420／1100图片计量／日志和330／1100实际日志入口开关／清除组／原数值字节保留，捕获沿CCB_MEDIA_USAGE_REVIEW_DIR。

仍未覆盖Moments实际生图、Enhance／Upscale、Vibe encode miss、供应商账单和真实出图质量。没有真实付费请求、本机编译／渲染或日常H安装；不改变上游baseline，不把本片称完整P15。
