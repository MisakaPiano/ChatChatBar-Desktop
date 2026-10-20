package com.example.chatbar.domain.rag
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.AndroidMemoryRagTextClient
fun RetrievalPlanner(chatService: StreamingChatService): RetrievalPlanner =
    RetrievalPlanner(AndroidMemoryRagTextClient(chatService))
