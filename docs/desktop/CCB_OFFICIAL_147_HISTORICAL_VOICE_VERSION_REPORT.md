# CCB 1.4.7 历史录音版本引用问题反馈

状态：源码路径已定位，共享 JVM 逻辑的合成复现已完成；这是可交给 CCB 开发者的本地反馈材料，尚未对外发送。本文不包含用户原始记录、身份、存档名、凭据或私有摘要。

固定源码：`SaltyFishOTL/ChatChatBar @ b4fa75d296680f777be095f3121ad0c4db979f17`，code86 / 1.4.7。后续版本是否已修复，本次未核对，不将本结论扩大到最新版。

## 1. 版本被裁剪时，历史录音可以继续保留并进入存档

`domain/chat/MessageAlternativeVersionPolicy.kt:73–102` 的 `append` 默认 `maxVersions=5`，对版本执行 `takeLast(maxVersions)`。`ui/chat/ChatViewModel.kt:3465–3475` 的追加替代回复调用没有覆盖该默认值。

与此同时，`ChatViewModel.kt:4040` 创建存档时通过 `voiceMessageRepository.listForSession(sessionId)` 收集会话录音；`domain/chat/SaveSlotPackageStorage.kt` 写入 `voices.jsonl` 时复制录音并改写音频资源路径，没有同步保留已被裁掉的消息版本。其 Reader 验证会核对录音 ID 和音频资源，但不证明每个非空 `messageVersionId` 仍在消息版本集合内。

因此，现有机制允许产生“音频和 sourceText 都在、版本 ID 却不在”的历史记录。不能仅据此把完整音频认定为损坏，也不能宣称每一份实际档案都必然由上述裁剪路径产生；具体历史仍需原应用日志或更早快照证明。

## 2. 恢复时把未知非空版本当作 legacy 推断，可能错误绑定

`data/repository/VoiceMessageRepository.kt:248–299` 的带 messages 参数的 `restoreForSession`：

```kotlin
voice.messageVersionId
    ?.takeIf { id -> MessageAlternativeVersionPolicy.versions(message).any { it.id == id } }
    ?: VoiceMessageVersionPolicy.inferLegacyVersionId(message, voice, null)
```

这条逻辑没有区分：

- 没有版本身份的历史格式录音（null，可讨论 legacy 推断）；
- 明确携带旧 ID、但旧版本已不存在的录音（不能认定为 legacy）。

`domain/voice/VoiceMessageVersionPolicy.kt` 的 `inferLegacyVersionId` 在没有文本匹配时最终退回 activeVersionId；随后恢复代码会把该 ID 写回录音。这可能使旧录音在当前版本下可见，即使录音 sourceText 不属于当前文本。

`ChatViewModel.kt:4192` 的正式存档加载调用会走入这个带 messages 的重载。Desktop 共享仓库也存在等价逻辑，但目前严格存档检查会先拒绝该未知引用；本反馈不以删除 Desktop 检查来掩盖问题。

## 合成复现步骤与期望

1. 建立只有 `old-version` 的消息，为该版本保存一条录音，录音文本只存在于旧版本。
2. 使用默认 `append` 连续添加五个新版本，确认版本集合只有五个新 ID，旧录音仍携带 old-version。
3. 创建含该消息、旧录音和音频的 v8 包：现有共享 Reader 的格式/媒体检查接受此包；Desktop 严格版本图检查将其分类为未知非空历史版本。
4. 对隔离合成根调用共享仓库的带 messages 恢复方法，观察未知非空 ID 是否被改成当前版本、旧文本录音是否因此可见。

期望：未知非空版本身份不得静默改成其他版本。可以保留为不可激活的历史录音，或显式报告无法安全恢复；不能自动删音频，也不能以文本片段相同替代版本身份。

### 实际合成执行结果

`tools/desktop/interop/lr4/src/HistoricalVoiceUpstreamReproductionTest.kt` 的五项 JVM 复现/对照全部执行通过。它们证实：默认追加五个版本后旧版本被裁出；共享 Package Reader 仍接受媒体完整的该包；现有共享仓库实际将明确的 `old` 改成 `new-5`、持久写入隔离合成根，并使完全不同文本的旧录音出现在当前版本的可见列表。已知版本与 null legacy 的对照也执行了。

“复现通过”表示确实观察到不正确行为，不是安全验收 PASS。执行对象为当前共享 Kotlin/JVM 的等价路径；固定官方 1.4.7 的对应代码已逐段核对，没有运行 Android APK，也没有在用户真实数据上执行恢复。官方新版本是否已修复仍未知。

## 修复建议（交 CCB 决定产品行为）

- 将“缺失版本身份的旧格式”与“已知身份指向不存在版本”分开处理。
- 已携带非空版本 ID 的记录保留原 ID，不走 legacy 推断或 active-version 兜底。
- 明确版本裁剪后的录音生命周期：独立历史保全或保留必要版本快照；不要默认删除用户音频。
- 存档验证与恢复必须采用相同语义；历史记录可保全不等于允许加入当前消息的播放队列。
- 回归覆盖：默认五版本裁剪、未知非空 ID、null legacy、文本碰巧相同、完全不同文本、音频完整但版本缺失。

本次不修改官方 Android、生产恢复链或用户档案，不自动修复真实版本 ID。
