# B2 角色补全／改写桌面基础链

CI58／ab整批FAIL保留：Windows2324项中2304通过、19跳过、唯一VoiceUx跨句／离页检查在line41断言输出为true失败；新角色AI控制器11项和Panel1均PASS。播放源码会先发布下一句ID、再打开输出；本批定向回归以可控第二次打开强制该窗口，等同一句playing确认后保留原跨页与无新增请求断言、同一5秒时限，不改生产播放行为。此回归与看图文字候选待新源云端；后续封面／头像代码仍在独立工作树，不借旧结果冒认完成。

看图文字候选源15263759已本地提交，待CI58结束后送云端，不能据静态审查当通过；另将原人物头像512方图构图策略及Prompt拼接原文移共享，Android测试／facade保留。此策略共享尚未接Desktop生图、裁剪、候选资源应用，封面和头像仍未完成。

研究源397／CI57现已完整SUCCESS；多图源ab080c1e单独推送CI58／41277789587，运行中。后续“图片补外貌／服装”源码又单独共享原Prompt块、模型路由和解析，Android保留task facade，Desktop接独立图片选择、候选对照、显式应用到同一人物草稿；不改头像／声线／启用状态，空字段保留原文。新增实际HTTP参数对照、截断／失效草稿／维护取消与窄宽UI回归待新源云端，不能算CI58覆盖。封面／人物头像生成尚待，不算完整B2。

研究源397c0960／CI57的Android与shared已通过：173 suite／1082 shared、580 Android，含研究引擎、HTTP取消及12次实际请求对照。Windows仍待，不能冒认全批通过。后续源码已接补全多参考图的批次读取／预览／排序／移除、直接多模态和精确关联视觉模型、最终重试复用及关闭清理；新候选同步清掉旧研究显示，避免失败后把历史brief误当本轮checkpoint。新图片与状态回归、420／1100 UI和多图实际请求对照待新源云端；未调用真实服务或用户图片，封面／头像和看图外貌继续排队。

最新检查点：21073fb0／CI56／41274659995已完整SUCCESS。Windows281 suite／2318项中2299通过、19跳过，新controller5项及420／1100 Panel1项通过；Android579／shared1078全过，含八次实际HTTP对照。代理视觉已看，不能当作真人验收、原生SF14或后续工作树通过。下方基础过程保留。

后续研究接线工作树已补百科／仅网址／组合／无网页模式、临时TXT／MD／JSON参考文档、RAG Top20和研究预览；沿用原规划／批次清洗／失败可见性／准备检查点，改模型不清除研究成果，半程plan也有继续入口。URL／文档正文不持久化，模式偏好按补全／改写分别保存，显式最终重生沿用资料。阶段固定类型补JSON修复截断提示，HTTP取消绑定实际Call并覆盖body消费、源client全部关闭排水。这些代码尚未云端验证；实际外网／嵌入模型／用户资料没有调用，不能因此宣布完整B2，多图／看图／封面／头像仍待。

2026-11-01。独立于已公开冻结1226／d94。共享策略828的CI55两job已成功；本页后续生成与桌面接线是新源码，不能由该结果代验。

本批把原八个角色卡Prompt常量及改写schema原文移入 `CharacterCardAiPromptAuthority`，Android `PromptTemplates`保留同名facade／目录，构建指纹纳入新物理源。I盘逐块回执确认原文字面量和schema相同，无文案修改。机器JSON payload与fill合并也改为共同入口，Android原研究、图片识别与checkpoint上下文继续负责准备输入。

`CharacterCardCandidateGenerator`共用最终生成、完整终态、原文checkpoint、至多一次JSON修复与候选策略。Android以原task context／512或256思考预算／600秒静默覆盖接入；Desktop独立gateway保留模型输出预算、custom参数与同一GENERAL envelope。终态Error／拒绝／截断／取消或没有Done的输出只保留预览，不作为checkpoint或修复输入；成功原文后修复失败，可以重试修复。此处没有自动重发整个请求的隐藏fallback。

Desktop角色编辑器新增AI补空／定向改写入口；自由模式只支持改写。模型列表和鉴权在生成时重新读取，指定模型丢失不回落其他模型。流式原文／思考／修复、日志、取消及重试可见。候选预览比较实际merge后的卡，而非原始patch；只显式应用到同一session、未改变的完整草稿，正常保存仍独立。人物ID／声线／头像／开关及STRUCTURED／FREEFORM边界由原共享策略保留，关闭／维护排水纳入现有TaskRuntime。

当前入口明确只有“当前卡片＋填写要求”。研究／URL／临时文档／多图准备及看图／封面／头像候选仍待下一步接入，没有提供然后丢弃这些选项，不能宣布完整B2或P12完成。新回归覆盖完整终态与修复checkpoint、Android／Desktop八个实际HTTP请求、模型刷新与失效草稿、普通保存、维护取消、自由模式、420／1100窄宽UI；尚未云端执行，不使用本机Gradle／渲染或用户真实资料。

后续在210／CI56运行期间继续把SearchModels／ResearchCleaner／MediaWiki／指定网页和临时文档RAG读取移到shared，并提取原CharacterResearchService业务为CharacterResearchEngine；原Android任务和Log入口留在facade，业务块除coroutine返回标签外逐字保持，原测试保留。跨模块测试所需的少数internal helper仅拓宽可见性；原DNS／redirect／内容预算保持。Planner／brief LLM适配和桌面研究输入还未接入，不据此算用户功能完成。另把修复阶段截断改为固定类型，Desktop显示JSON修复和输出上限，保留成功生成的原文checkpoint及只重试REPAIR的回归。此后续源码待云端验证，不冒认CI56覆盖。
