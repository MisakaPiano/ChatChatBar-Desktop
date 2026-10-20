package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.repository.ChatRepository
import java.nio.file.Path

/** Validate owned source files before the official source-turn normalizer may persist anything. */
internal class DesktopMemorySources(root: Path, private val storage: JsonFileStorage, private val chats: ChatRepository) {
    private val source = DesktopDocumentRagSource(root)
    suspend fun ensure(sessionId: String): List<ChatMessage> {
        read(sessionId)
        chats.ensureSourceTurns(sessionId)
        return read(sessionId)
    }
    suspend fun read(sessionId: String): List<ChatMessage> {
        source.requireIdentity(sessionId, sessionId)
        var sessionFound = false
        storage.visitEntitiesStrict("chat_sessions", ChatSession.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id)
            if (id == sessionId) sessionFound = true
        }
        check(sessionFound) { "记忆所属会话不存在" }
        var retained = 0L
        val messages = ArrayList<ChatMessage>()
        storage.visitEntitiesStrict("chat_messages", ChatMessage.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(value.id, value.id); source.requireIdentity(value.sessionId, value.sessionId)
            check(id == "${value.sessionId}_${value.id}") { "记忆来源文件身份不匹配" }
            if (value.sessionId == sessionId) {
                retained += storage.json.encodeToString(ChatMessage.serializer(), value).length * 2L
                check(messages.size < 20_000 && retained <= 32L * 1024 * 1024) { "记忆来源超过读取预算" }
                messages += value
            }
        }
        return messages.sortedWith(ChatMessage.TimelineComparator)
    }
}
