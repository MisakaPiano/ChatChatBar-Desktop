package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** V1 is an explicit data scope. Accounts, device preferences and running jobs never cross it. */
object LanSyncScope {
    val schemas: Map<String, KSerializer<*>> = mapOf(
        "character_cards" to CharacterCard.serializer(), "format_cards" to FormatCard.serializer(),
        "world_books" to WorldBook.serializer(), "chat_sessions" to ChatSession.serializer(),
        "chat_messages" to ChatMessage.serializer(), "vector_chunks" to VectorChunk.serializer(),
        "memory_nodes" to MemoryNode.serializer(), "memory_states" to MemorySessionState.serializer(),
        "memory_tier_revisions" to MemoryTierRevision.serializer(),
        "memory_compression_transactions" to MemoryCompressionTransaction.serializer(),
        "memory_commits" to MemoryCommit.serializer(), "generated_voice_messages" to GeneratedVoiceMessage.serializer(),
        "save_slots" to SaveSlot.serializer(),
    )
    val description = "同步角色卡、格式卡、世界书、会话、消息及其版本、记忆、RAG、图片、文档和已生成语音。" +
        "同时保留旧式及 v8 会话存档。不含账户和密钥、设备设置、输入草稿、朋友圈、生图工作室和历史。模型绑定保留各设备现有选择，新会话需在本机选模型。两端需打开同步页面，首版一次连接一台可信设备、单次上限 1 GiB。"
    internal val localFields = setOf("modelId", "imageModelId", "defaultModelId", "defaultImageModelId")
    internal val resources = setOf("avatar", "coverImage", "desktopAvatar", "desktopPlayerAvatar", "appearanceImage", "chatBackground",
        "chatBackgroundImages", "senderAvatar", "imagePath", "audioPath", "filePath", "filePaths", "images", "path")

    fun normalize(type: String, body: JsonObject): JsonObject = if (type == "chat_sessions") JsonObject(body.filterKeys { it !in localFields }) else body

    fun identity(type: String, body: JsonObject): String? =
        (body[if (type == "memory_states") "sessionId" else "id"] as? JsonPrimitive)?.contentOrNull

    fun validateRecord(record: LanSyncRecord) {
        val serializer = requireNotNull(schemas[record.type]) { "SYNC_UNSUPPORTED_TYPE" }
        lanSyncJson.decodeFromJsonElement(serializer, record.body)
        require(identity(record.type, record.body) == record.id) { "SYNC_BODY_ID" }
        // Pending state-machine journals have their own owner; do not transport an active transaction.
        if (record.type == "chat_sessions") {
            listOf("memoryUpdateStatus", "memoryArchiveStatus", "memoryHeadStatus").forEach {
                require((record.body[it] as? JsonPrimitive)?.contentOrNull !in setOf("UPDATING", "WAITING_FOR_NETWORK")) { "SYNC_BUSY_MEMORY" }
            }
        }
    }

    fun validateGraph(snapshot: LanSyncSnapshot) {
        snapshot.records.forEach(::validateRecord)
        val ids = snapshot.records.groupBy { it.type }.mapValues { (_, rows) -> rows.map { it.id }.toSet() }
        val messages = snapshot.records.filter { it.type == "chat_messages" }.associateBy { it.id }
        snapshot.records.forEach { record ->
            val session = record.text("sessionId")
            if (session != null) require(session in ids["chat_sessions"].orEmpty()) { "SYNC_ORPHAN_SESSION" }
            if (record.type == "chat_sessions") {
                require(record.text("characterCardId") in ids["character_cards"].orEmpty()) { "SYNC_MISSING_CHARACTER" }
                record.text("formatCardId")?.let { require(it in ids["format_cards"].orEmpty()) { "SYNC_MISSING_FORMAT" } }
            }
            if (record.type == "generated_voice_messages") {
                val message = requireNotNull(messages[record.text("messageId")]) { "SYNC_ORPHAN_VOICE" }
                require(message.text("sessionId") == session) { "SYNC_VOICE_OWNER" }
            }
        }
        // Reuses the established shared memory ancestry checks without rewriting text.
        LanSyncMemoryProofs(snapshot.records.filter { it.type != "save_slots" }, LanSyncIdentity())
        snapshot.records.filter { it.type == "save_slots" }.forEach { LanSyncMemoryProofs(listOf(it), LanSyncIdentity()) }
    }
}
