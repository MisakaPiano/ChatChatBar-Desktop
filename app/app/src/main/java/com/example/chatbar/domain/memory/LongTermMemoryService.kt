package com.example.chatbar.domain.memory
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.StreamingChatService
/** Preserve the Android construction API without a second memory state machine. */
fun LongTermMemoryService(chatRepository: ChatRepository, memoryRepository: MemoryRepository,
    settingsRepository: SettingsRepository, streamingChatService: StreamingChatService,
    contextWindowManager: ContextWindowManager): LongTermMemoryService = LongTermMemoryService(
        chatRepository, memoryRepository, settingsRepository, MemoryAiGateway(streamingChatService), contextWindowManager)
