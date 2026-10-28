package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*

/** Official settings plus namespaced Desktop participation. Card identity, title and live runtime stay outside archive settings. */
object SaveSlotSessionPolicy {
    fun create(session: ChatSession, name: String, description: String?, memorySnapshot: MemorySnapshot?,
        imagePolicy: SaveSlotImagePolicy, includeAudio: Boolean): SaveSlot = SaveSlot.create(
        sessionId = session.id,
        name = name.trim(),
        description = description?.trim()?.takeIf(String::isNotBlank)
    ).copy(
        schemaVersion = SaveSlotPackageStorage.SCHEMA_VERSION,
        playerSetting = session.playerSetting,
        playerName = session.playerName,
        supplementarySetting = session.supplementarySetting,
        modelId = session.modelId,
        imageModelId = session.imageModelId,
        novelAiImageModel = session.novelAiImageModel,
        novelAiNaturalLanguageMode = session.novelAiNaturalLanguageMode,
        automaticImageGenerationEnabled = session.automaticImageGenerationEnabled,
        formatCardId = session.formatCardId,
        replyLength = session.replyLength,
        replyLanguage = session.replyLanguage,
        roleplayStyle = session.roleplayStyle,
        chatBackground = session.chatBackground,
        audiobookModeEnabled = session.audiobookModeEnabled,
        voiceLanguage = session.voiceLanguage,
        desktopCharacterParticipation = session.desktopCharacterParticipation,
        desktopAutoPauseAbsentPeople = session.desktopAutoPauseAbsentPeople,
        longTermMemoryEnabled = session.longTermMemoryEnabled,
        longTermMemory = session.longTermMemory,
        longTermMemoryUpdatedThroughMessageId = session.longTermMemoryUpdatedThroughMessageId,
        nextSourceTurnOrder = session.nextSourceTurnOrder,
        sourceTurnTombstones = session.sourceTurnTombstones,
        nextTimelineTurn = session.nextTimelineTurn,
        timelineTombstones = session.timelineTombstones,
        memoryLimitChars = session.memoryLimitChars,
        memorySnapshot = memorySnapshot,
        contextWindowSize = session.contextWindowSize,
        extraWorldBookIds = session.extraWorldBookIds,
        timedWorldInfo = session.timedWorldInfo,
        imagePolicy = imagePolicy,
        includeAudio = includeAudio
    )

    fun restore(session: ChatSession, slot: SaveSlot, restoredBackground: String?): ChatSession =
    session.copy(
        playerSetting = slot.playerSetting,
        playerName = slot.playerName,
        supplementarySetting = slot.supplementarySetting,
        modelId = slot.modelId,
        imageModelId = slot.imageModelId,
        novelAiImageModel = slot.novelAiImageModel,
        novelAiNaturalLanguageMode = slot.novelAiNaturalLanguageMode,
        automaticImageGenerationEnabled = slot.automaticImageGenerationEnabled,
        formatCardId = slot.formatCardId,
        replyLength = slot.replyLength,
        replyLanguage = slot.replyLanguage,
        roleplayStyle = slot.roleplayStyle,
        chatBackground = restoredBackground,
        audiobookModeEnabled = slot.audiobookModeEnabled,
        voiceLanguage = slot.voiceLanguage,
        desktopCharacterParticipation = slot.desktopCharacterParticipation,
        desktopAutoPauseAbsentPeople = slot.desktopAutoPauseAbsentPeople,
        longTermMemoryEnabled = slot.longTermMemoryEnabled,
        longTermMemory = slot.longTermMemory,
        longTermMemoryUpdatedThroughMessageId = slot.longTermMemoryUpdatedThroughMessageId,
        nextSourceTurnOrder = slot.nextSourceTurnOrder,
        sourceTurnTombstones = slot.sourceTurnTombstones,
        nextTimelineTurn = slot.nextTimelineTurn,
        timelineTombstones = slot.timelineTombstones,
        memoryLimitChars = slot.memoryLimitChars,
        contextWindowSize = slot.contextWindowSize ?: session.contextWindowSize,
        extraWorldBookIds = slot.extraWorldBookIds,
        timedWorldInfo = slot.timedWorldInfo
    )
}
