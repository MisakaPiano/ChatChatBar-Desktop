package com.example.chatbar.desktop

import java.nio.file.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Strict ownership inventory; damaged or unrecognized records never become deletion authority. */
internal class DesktopSessionInventory(private val root: Path) {
    suspend fun read(sessionId: String): Map<String, String> {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val owned = linkedMapOf<String, String>()
        // Only owner identities and hashes survive each record; total JSON is not retained.
        val recordBudget = com.example.chatbar.desktop.backup.DesktopBackupRecords.recordBudget
        var count = 0
        TYPES.forEach { type ->
            val directory = root.resolve("entities/$type")
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@forEach
            DesktopArchiveFiles.safe(directory, true)
            Files.newDirectoryStream(directory).use { entries ->
                for (file in entries) {
                    currentCoroutineContext().ensureActive()
                    check(++count <= 40000) { "会话数据清单超过校验预算" }
                    check(file.fileName.toString().matches(Regex("[A-Za-z0-9_-]{1,256}\\.json"))) { "存在未知会话文件；原文件保留" }
                    DesktopArchiveFiles.safe(file, false)
                    com.example.chatbar.desktop.backup.DesktopBackupRecords.requireSize(Files.size(file))
                    val before = DesktopArchiveFiles.hash(file, recordBudget)
                    val value = Json.parseToJsonElement(Files.readString(file)).jsonObject
                    val ownerKey = when (type) { "chat_sessions" -> "id"; "vector_chunks" -> "sourceId"; else -> "sessionId" }
                    val owner = value[ownerKey]?.jsonPrimitive?.contentOrNull
                    check(!owner.isNullOrBlank()) { "会话数据缺少所有权身份" }
                    fun field(name: String) = value[name]?.jsonPrimitive?.contentOrNull
                    val id = file.fileName.toString().removeSuffix(".json")
                    val expectedId = when (type) {
                        "chat_sessions", "chat_message_indexes", "chat_message_order_backups", "chat_drafts", "chat_scroll_positions", "memory_states" -> owner
                        "chat_messages" -> "${owner}_${requireNotNull(field("id"))}"
                        "voice_anchor_states" -> {
                            val message = requireNotNull(field("messageId"))
                            field("messageVersionId")?.let { version ->
                                java.util.UUID.nameUUIDFromBytes("$message\u0000$version".toByteArray(Charsets.UTF_8)).toString()
                            } ?: message
                        }
                        else -> requireNotNull(field("id"))
                    }
                    check(id == expectedId) { "会话文件与记录身份不一致；原数据保留" }
                    if (owner == sessionId && (type != "vector_chunks" || value["sourceType"]?.jsonPrimitive?.content == "CHAT_MEMORY")) {
                        check(DesktopArchiveFiles.hash(file, recordBudget) == before)
                        owned["entities/$type/${file.fileName}"] = before
                    }
                }
            }
        }
        return owned
    }

    companion object {
        val TYPES = listOf("chat_sessions", "chat_messages", "chat_message_indexes", "chat_message_order_backups",
            "chat_drafts", "chat_scroll_positions", "vector_chunks", "memory_states", "memory_nodes",
            "memory_tier_revisions", "memory_compression_transactions", "memory_commits", "memory_commit_journals",
            "generated_voice_messages", "voice_anchor_states")
    }
}
