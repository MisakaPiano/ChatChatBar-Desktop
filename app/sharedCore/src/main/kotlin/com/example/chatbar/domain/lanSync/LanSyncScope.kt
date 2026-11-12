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
        "voice_anchor_states" to VoiceAnchorState.serializer(),
    )
    val description = "同步角色卡、格式卡、世界书、会话、消息及其版本、记忆、RAG、图片、文档和已生成语音。" +
        "同时保留旧式及 v8 会话存档。不含账户和密钥、设备设置、输入草稿、朋友圈、生图工作室和历史。模型绑定保留各设备现有选择，新会话需在本机选模型。两端需打开同步页面，首版一次连接一台可信设备、单次上限 1 GiB。"
    internal val localFields = setOf("modelId", "imageModelId", "defaultModelId", "defaultImageModelId")
    internal val resources = setOf("avatar", "coverImage", "desktopAvatar", "desktopPlayerAvatar", "appearanceImage", "chatBackground",
        "chatBackgroundImages", "senderAvatar", "imagePath", "audioPath", "filePath", "filePaths", "images", "path")

    fun normalize(type: String, body: JsonObject): JsonObject = if (type == "chat_sessions") JsonObject(body.filterKeys { it !in localFields }) else body

    fun identity(type: String, body: JsonObject): String? {
        if (type == "voice_anchor_states") {
            val message = (body["messageId"] as? JsonPrimitive)?.contentOrNull ?: return null
            val version = (body["messageVersionId"] as? JsonPrimitive)?.contentOrNull ?: return message
            return java.util.UUID.nameUUIDFromBytes("$message\u0000$version".toByteArray(Charsets.UTF_8)).toString()
        }
        return (body[if (type == "memory_states") "sessionId" else "id"] as? JsonPrimitive)?.contentOrNull
    }
    fun storageId(record: LanSyncRecord): String = if (record.type == "chat_messages")
        "${requireNotNull(record.text("sessionId"))}_${record.id}" else record.id

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
        val referenced = linkedSetOf<String>()
        snapshot.records.forEach { record -> LanSyncWorkspace.mapResources(record.body) { reference, field ->
            when {
                reference.isBlank() || reference.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) -> Unit
                record.type == "save_slots" && (reference.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_IMAGE_PREFIX) ||
                    reference.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_AUDIO_PREFIX)) -> Unit
                else -> {
                    val parts = reference.split('/')
                    require(parts.size == 4 && parts[0] == "ccb-lan-v1" && parts[1] in setOf("images", "documents", "audio", "packages") &&
                        parts[2].matches(Regex("[a-f0-9]{64}")) && parts[3].matches(Regex("[a-z0-9]{1,8}"))) { "SYNC_EXTERNAL_WIRE_REFERENCE" }
                    require((field == "packageFile") == (parts[1] == "packages") && (field == "audioPath") == (parts[1] == "audio")) { "SYNC_RESOURCE_KIND" }
                    require(parts[2] in snapshot.resources) { "SYNC_RESOURCE_MANIFEST" }
                    referenced += parts[2]
                }
            }
            reference
        } }
        require(referenced == snapshot.resources.keys) { "SYNC_UNREFERENCED_RESOURCE" }
        val ids = snapshot.records.groupBy { it.type }.mapValues { (_, rows) -> rows.map { it.id }.toSet() }
        val messages = snapshot.records.filter { it.type == "chat_messages" }.associateBy { it.id }
        snapshot.records.forEach { record ->
            val session = record.text("sessionId")
            if (session != null) require(session in ids["chat_sessions"].orEmpty()) { "SYNC_ORPHAN_SESSION" }
            if (record.type == "chat_sessions") {
                // Existing archived conversations deliberately retain a missing card identity.
                require(!record.text("characterCardId").isNullOrBlank()) { "SYNC_MISSING_CHARACTER_ID" }
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

    fun referencedResources(records: List<LanSyncRecord>): Set<String> = buildSet {
        records.forEach { record -> LanSyncWorkspace.mapResources(record.body) { value, _ ->
            if (value.startsWith("ccb-lan-v1/")) value.split('/').getOrNull(2)?.let(::add)
            value
        } }
    }
}
