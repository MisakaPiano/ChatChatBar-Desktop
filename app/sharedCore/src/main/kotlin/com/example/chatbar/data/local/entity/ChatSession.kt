package com.example.chatbar.data.local.entity

import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageModelResolution
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class MemoryUpdateStatus {
    IDLE,
    UPDATING,
    WAITING_FOR_NETWORK,
    ERROR,
    LIMIT_DECISION_REQUIRED,
    PAUSED
}

@Serializable
data class SourceTurnTombstone(
    val sourceTurnId: String,
    val sourceOrder: Long
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SessionCharacterParticipation(val cardId: String, val disabledPersonIds: Set<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val automaticallyPausedPersonIds: Set<String> = emptySet()) {
    init {
        require(cardId.isNotBlank() && disabledPersonIds.all(String::isNotBlank))
        require(automaticallyPausedPersonIds.all(String::isNotBlank))
    }
}

/**
 * 聊天会话
 */
@Serializable
data class SessionPlayerRole(val cardId: String, val personId: String,
    val followName: Boolean = true, val followAvatar: Boolean = true) {
    init { require(cardId.isNotBlank() && personId.isNotBlank()) }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ChatSession(
    val id: String,
    val characterCardId: String,
    val title: String,
    val displayTitleOverride: String? = null,
    val modelId: String? = null,
    val imageModelId: String? = null,
    /** null 表示跟随角色卡与全局 NovelAI 生图模型。 */
    val novelAiImageModel: NovelAiImageModel? = null,
    val novelAiNaturalLanguageMode: Boolean = false,
    val formatCardId: String? = null,
    @Serializable(with = ReplyLengthSerializer::class)
    val replyLength: Int = DEFAULT_REPLY_LENGTH_CHARS, // 正文目标字数
    val replyLanguage: String? = null,       // 回复语言
    val roleplayStyle: String? = null,       // 会话扮演风格覆盖
    val supplementarySetting: String? = null, // 补充设定
    val playerName: String? = null,           // 玩家名称覆盖
    val playerSetting: String? = null,        // 个人设定覆盖
    /** Explicit player role only; equal names never establish this association. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val desktopPlayerRole: SessionPlayerRole? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val desktopPlayerAvatar: String? = null,
    val chatBackground: String? = null,
    val imagePromptPreference: String = "",
    val automaticImageGenerationEnabled: Boolean = false,
    /** null 表示跟随全局听书模式设置。 */
    val audiobookModeEnabled: Boolean? = null,
    /** null 表示语音直接使用气泡原文。 */
    val voiceLanguage: String? = null,
    /** Desktop-only per-card participation; absent legacy data enables every person. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val desktopCharacterParticipation: List<SessionCharacterParticipation> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val desktopAutoPauseAbsentPeople: Boolean = false,
    val longTermMemoryEnabled: Boolean = true,
    val longTermMemory: String = "",
    val longTermMemoryUpdatedThroughMessageId: String? = null,
    val nextSourceTurnOrder: Long = 1,
    val sourceTurnTombstones: List<SourceTurnTombstone> = emptyList(),
    /** v2草稿兼容字段。 */
    val nextTimelineTurn: Long = 1,
    val timelineTombstones: Set<Long> = emptySet(),
    val memoryLimitChars: Int = DEFAULT_MEMORY_LIMIT_CHARS,
    val memoryStateRevision: Long = 0,
    /** v2草稿兼容指针；v4迁移成功后清空。 */
    val memoryHeadCommitId: String? = null,
    val memoryUpdateStatus: MemoryUpdateStatus = MemoryUpdateStatus.IDLE,
    val memoryUpdateError: String? = null,
    val memoryArchiveStatus: MemoryUpdateStatus = MemoryUpdateStatus.IDLE,
    val memoryArchiveError: String? = null,
    val memoryHeadStatus: MemoryUpdateStatus = MemoryUpdateStatus.IDLE,
    val memoryHeadError: String? = null,
    val longTermMemorySchemaVersion: Int = 1,
    val contextWindowSize: Int = 20,
    val extraWorldBookIds: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageTime: Long? = null,
    val lastMessageRole: MessageRole? = null,
    val timedWorldInfo: Map<String, TimedEffectState> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long
) {
    init { require(desktopCharacterParticipation.map { it.cardId }.distinct().size == desktopCharacterParticipation.size) }
    fun resolvedNovelAiImageModel(
        characterDefault: NovelAiImageModel?,
        globalDefault: NovelAiImageModel
    ): NovelAiImageModel = NovelAiImageModelResolution.resolve(
        explicitOverride = novelAiImageModel,
        characterDefault = characterDefault,
        globalDefault = globalDefault
    )

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            characterCardId: String,
            title: String,
            modelId: String? = null,
            formatCardId: String? = null
        ): ChatSession {
            val now = System.currentTimeMillis()
            return ChatSession(
                id = Uuid.random().toString(),
                characterCardId = characterCardId,
                title = title,
                modelId = modelId,
                formatCardId = formatCardId,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
