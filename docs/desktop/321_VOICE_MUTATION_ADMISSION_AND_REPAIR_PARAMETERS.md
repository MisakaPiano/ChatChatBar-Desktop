# 语音变更准入与格式修复模型参数

2026-11-07，固定141→12e实际语义追齐的后继补修。未改变公开1244/db7/签26、A1真人FAIL、已有315 Prompt未采纳状态；没有新包/公发、日常程序代装、本机JVM/GUI或真实资料操作。320的冷模型40/Studio33原范围保留。

## 格式修复：保护消息封装与继承模型参数分别处理

8c的官方MessageFormatRepairService仅移除disableThinking=true；其streamText默认不启用isolatedTaskParameters。Desktop在271阶段只对齐thinking/effort，同时保留旧参数strip。271是当时的实现记录，未找到用户明确要求该模型参数差异的依据。旧FormatRepairAuxiliaryTransport原20PASS证明当时确实删除temperature/stop/输出cap/预算等字段，不能据此宣称与官方参数语义一致。

c364仅新增实际 `OpenAiStreamingTransport.streamFormatRepair`→loopback HTTP反例。R01原一个方法FAIL：第一份legacy-thinking配置实际请求没有max_tokens；失败点后的字段与第二配置尚未执行，不据此推断它们已验。53dec仅在format caller传isolatedTaskParameters=false，原disableThinking=false和protectEnvelope保持；其他helper默认未变。八消息、已有Prompt/golden、完整目标、600秒socket/inactivity、TLS/代理、单次请求及取消/拒答/截断/terminal处理均保留。

原HTTP测试改为继承所选参数，并保留model对象不可变、golden、auth和custom envelope保护断言；原跨alias自定义88改为所选alias的自定义88，用来验证这份合法配置中typed77优先且没有双alias，不据此声称会自动排除任意用户冲突alias。新独立方法完整覆盖legacy/max_tokens与reasoning-only/max_completion_tokens两种配置、两个请求，保留temperature/stop/thinking预算，并拒绝custom messages/model/stream替换任务封装。没有改通用serializer或已有Prompt。

R02原四套34PASS、0FAIL/ERROR/SKIP：FormatRepairAuxiliaryTransport20、SelectedModelParameters1、ExperimentalToolTransport9、SelectedAuxiliaryModelParameters4。新方法两个配置的全部断言均执行，原20套中其余TLS/超时/terminal等原逻辑继续通过；不推广为付费服务或全仓/真人验收。

| 检查 | 普通源 / control / run | artifact / 字节 / SHA256 |
| --- | --- | --- |
| Format R01 | c364ce56 / 4e7ca2bb / 42330516434 | 13556654676 / 12935 / 75a34811f354ba642fc85a494184a312259948952b1731cc08507391f1092525 |
| Format R02 | 53dec959 / e5da5034 / 42330608810 | 13557311686 / 20058 / 0343e831a096991e1e4c9c30b17235807529830e88594e50ee0036069eeeda4b |

两个ZIP均已核长度/SHA256/CRC/source.json/原XML；旧失败保留。

## 语音：只调整具体normal-only入口

实际generate/play准备先normal准入再取voice mutation；驻场完成回调此前在mutation内进入normal，单条录音删除也在mutation内保存/释放。短exclusive维护不全部经TaskRuntime.maintenance；不能靠任务中心标志排除等待风险。

b09只加实际RuntimeFixture两个有界检查：暂停真实generate已经取得backing normal但尚未取mutation的位置，排exclusive，再调用真实DesktopCharacterResidency.completed或deleteVoice。旧generate的expected故意过期，若能取得锁应安全拒绝且不再发网络。exclusive期间只读已知合成session原bytes/原音频，释放后核驻场重新启用/notice或录音metadata与owned文件删除；取消/join、runtime/gate drain和本夹具根清理保持。它不是用户故障报告或界面点按复现。

b3只把automatic participation和deleteVoice的normal准入前置到mutation。随后确认manual participation唯一生产caller为Primary.setCharacterEnabled，其task admission/stateLock/读取、策略变更、保存及refreshSelection均为normal-only；d569同批补该入口，保留busy-voice拒绝，并加第三个manual facade检查。第三例不冒称实际Primary UI交互，实际Primary原整套另在后继检查内。暂停夹具以CoroutineName只匹配该次真实old generate，避免背景normal抢先消费标志。

通用mutateMessage、session maintenance/deletion与speaker-history回调不概括套normal：它们可以调独立exclusive清理或已有外层normal owner。cancel/join、原录音版本保护、无引用后删除和播放停止保持原顺序。已有生成job在自身normal lease内执行block及finally，具体deleteVoice父lease不会让未准入子job取消后的清理等一个新准入；待新原件验证再记结论。

Voice R01（b09/control012436aa/run42330233799）原两方法均在5秒等待界限FAIL、0ERROR/SKIP；R02（b3/control75046481/run42330308910）原八套81PASS、0FAIL/ERROR/SKIP：新Admission2、Runtime9、Coordinator15、Primary42、Persistence5、Safety5、Background2、TextDrain1。它们说明原两入口的实际循环反例与修正，并保留取消/音频持久化/后台/原Primary行为范围。d569后继R03（control4e9740c1/run42330869870）原三套54PASS、0FAIL/ERROR/SKIP：命名暂停的Admission3、Runtime9、Primary42。manual facade和原busy拒绝/Primary切换范围按这一新源成立；前两轮两方法与后继三方法夹具源分开，manual旧生产没有新增反例运行，重叠方法不累加成新135方法。

| 检查 | 普通源 / control / run | artifact / 字节 / SHA256 |
| --- | --- | --- |
| Voice R01 | b09d4272 / 012436aa / 42330233799 | 13557461473 / 10914 / 499f39674caaf0733d7041163612255b836a6ab0bcfb48935a43300f6e52cd50 |
| Voice R02 | b3b79af1 / 75046481 / 42330308910 | 13557586601 / 32561 / f9ca02b53f16e8cd6e5deca8317e15b502afa62c02110e7190e06a898f0c3f9e |
| Voice R03 | d5694bc1 / 4e9740c1 / 42330869870 | 13557875299 / 20015 / 625e276e150a09daeb88d153ee080f23f2ac9776920d16cbb0322f3c4ce92840 |

三个原件均已核长度/SHA256/CRC/source.json/全部实际XML，旧FAIL保留，不把分轮方法简单相加为一次新回归。

## 后继：界面录音条目读取与仓库锁

`rememberDesktopVoicePlacements`的produceState直接调用repository.placementsForMessage，无外层normal；它经ensureAnchors在repo mutex内初始化/保存新回复锚点。实际playExisting已normal准入后也直接调用placementsForMessage并需要同一repo mutex。此处不是纯文件AudioStore互斥，也不能用runtime入口已包装来排除独立UI reader。

0f4只增实际RuntimeFixture反例：先生成旧合成录音、添加尚无锚点的新回复，再命名暂停旧有效录音的真实play normal边界；exclusive排队后，late调用与UI相同的placementsForMessage(newReply)。暖repo的新anchor写等待准入会占住原mutex。984仅把VoiceMessageRepository原持久化mutex块置于storage normal之后，保留strictReads、缓存/初始化、锚点版本迁移/reconcile、restore和删除原body，shared内部调用internal入口，不扩底层API可见性。原纯文件DesktopVoiceAudioStore mutex不改。

正例检查仅包括播放准备返回Job、维护排空、late等待、释放后的新anchor版本、原消息/录音bytes和零新增TTS；stopForMaintenance/join单独完成，不宣称声卡或冷初始化复现。R01/control6fc8b16d/run42331431763仅317B source、零XML；R02/controlb7110d2f/run42331520806先执行原shared VoiceRepositoryPersistence1PASS，随后同样在Desktop测试编译失败：新用例两处snapshotAnchorsForMessages把List传给要求Set的接口（38/61行）。两轮都没有Desktop业务结论，不借此前54/81代验。

9e4只把两处调用修正为setOf。旧生产后继af12以0f4为父，仅复制修正后的同一测试blob；R03/control71f26aad/run42332169956原一个方法在5秒播放准备返回界限FAIL、0ERROR/SKIP。当前生产R04/control ec1b3350/run42332177084原九套Desktop55PASS、0FAIL/ERROR/SKIP：repo新1、mutation3、Runtime9、Persistence5、Safety5、HistoricalRetention4、LegacyVoiceIdentity3、SaveSlotService24、TextDrain1。已匹配984的shared1原PASS单独复用，不当作新同轮56。原两轮编译失败保留；本次actual等待反例来自修正后同一测试blob。

| 原编译失败 | artifact / 字节 / SHA256 |
| --- | --- |
| Repo R01 | 13557865955 / 317 / 0272180f633da2b138d30fce342d527810285f94bd2eee9a1aa25eeff2b18541 |
| Repo R02 | 13558096429 / 9338 / 01956cb09f1c4c99e87eca31a2d9e7140139bbcdb1ed76cc9f94a8da271377f0 |
| Repo R03（合法夹具旧生产） | 13558297488 / 10624 / 9c3130f75a889587042c9f059e9ae4892c49bdc3067bf00a83167a238af7f780 |
| Repo R04（新生产） | 13558322547 / 31639 / 6e8d47cdacef07bfbf2532cc0241a1d2ba498511adb2d9b062952bbbd2b6b861 |

R03/R04两个原ZIP也已核长度/SHA256/CRC/source.json与实际XML。完整通过范围包括播放准备/维护排空和late等待、释放后anchor/version与原音频bytes、零新增TTS、历史/存档语音保全与原取消排水；不把fake output当作物理声卡。

## 具体normal-only回复候选切换

另核Primary.selectAssistantAlternative原回调仅读取、eligible范围及selected-version保存，memory.sourceChanged发生在voice mutation退出后。4ffc新增 `mutateNormalMessage` 并只让该回调走专用normal helper，复用原停止播放/取消精确消息语音的owner，memory handoff仍在外；通用helper不变。新增第四个facade有界检查核切换保留兄弟候选和版本IDs。Mutation R04/control8752ba1a/run42332426826原三套55PASS、0FAIL/ERROR/SKIP：Admission4、Runtime9、实际Primary42；新入口按该后继成立，不从此前54或repo55代验，方法重叠不相加。

该原件artifact13558397194、20098B、SHA256 `fabf56084c8a8ea9da353a4c2bc4f32b62edd5a570090e0d1096a41dc5522e9a`，长度/CRC/source.json/原XML已核。

whole/segment编辑、带图修改/删除等回调会把normal持久化与后续exclusive资源清理组合，仍作为具体源码候选保留，不能由上述normal-only入口推广全局锁序闭合。这里没有对通用mutateMessage概括增加outer normal lease。

## 8c参数delta的精确映射

`OWNER_REPAIR_AND_AUXILIARY_PARAMETER_REVIEW.json`仅映射四条参数delta：CharacterAutoFill/Rewrite的固定thinking预算、MessageFormatRepair的强制关闭thinking、FishTag移除输出上限。实际shared候选generator及Desktop container把所选ModelConfig送到GENERATE/REPAIR producer；当前五个选定owner/caller blob与53dec已测源相同，34原方法中的SelectedAux4已跑角色两stage和真实Fish translation/tag HTTP。只说明这四条参数变化，不推广角色卡完整工作流或已有Prompt变更；默认关闭实验协议的原范围保留，314仍complete:false。
