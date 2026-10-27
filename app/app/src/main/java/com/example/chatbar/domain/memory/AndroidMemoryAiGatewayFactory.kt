package com.example.chatbar.domain.memory
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.AndroidMemoryRagTextClient
/** Android compatibility construction; shared engine owns the official validation and retry policy. */
fun MemoryAiGateway(chatService: StreamingChatService): MemoryAiGateway =
    MemoryAiGateway(AndroidMemoryRagTextClient(chatService))
