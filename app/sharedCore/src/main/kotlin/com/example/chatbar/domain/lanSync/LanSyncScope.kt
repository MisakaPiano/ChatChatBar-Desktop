package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** V1 is an explicit data scope. Accounts, device preferences and running jobs never cross it. */
object LanSyncScope {
    internal val legacyChatTypes = setOf("character_cards", "format_cards", "world_books", "chat_sessions", "chat_messages",
        "vector_chunks", "memory_nodes", "memory_states", "memory_tier_revisions", "memory_compression_transactions",
        "memory_commits", "generated_voice_messages", "save_slots", "voice_anchor_states")
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
        "moment_posts" to MomentPost.serializer(), "moment_tasks" to MomentTask.serializer(),
    )
    val description = "同步角色卡、格式卡、世界书、会话、消息及其版本、记忆、RAG、图片、文档和已生成语音。" +
        "同时保留旧式及 v8 会话存档、朋友圈及其图片版本和计划。同步计划需本机明确恢复后才参与自动生成，中断记录不自动重试。不含账户和密钥、设备设置、输入草稿、生图工作室和历史。模型绑定保留各设备现有选择，新会话需在本机选模型。两端需打开同步页面，首版一次连接一台可信设备、单次上限 1 GiB。"
    internal val localFields = setOf("modelId", "imageModelId", "defaultModelId", "defaultImageModelId")
    internal val resources = setOf("avatar", "coverImage", "desktopAvatar", "desktopPlayerAvatar", "appearanceImage", "chatBackground",
        "chatBackgroundImages", "senderAvatar", "imagePath", "audioPath", "filePath", "filePaths", "images", "path")

    fun normalize(type: String, body: JsonObject): JsonObject = when (type) {
        "chat_sessions" -> JsonObject(body.filterKeys { it !in localFields })
        "format_cards" -> JsonObject(body + ("isDefault" to JsonPrimitive(false)))
        "moment_tasks" -> JsonObject(body + ("requiresLocalScheduleApproval" to JsonPrimitive(true)))
        else -> body
    }
    internal fun retainedLocalFields(type: String) = when (type) {
        "chat_sessions" -> localFields
        "format_cards" -> setOf("isDefault")
        else -> emptySet()
    }

    /** Only previously supported bodies are reconsidered; the frozen legacy envelope is never rewritten. */
    internal fun validateLegacyPending(snapshot: LanSyncSnapshot) {
        try {
            require(snapshot.protocol in setOf(1, 2) && legacyChatTypes.containsAll(snapshot.coveredTypes))
            val compatible = snapshot.copy(protocol = LAN_SYNC_PROTOCOL,
                records = snapshot.records.map { it.copy(body = normalize(it.type, it.body)) })
            compatible.validate(); validateGraph(compatible)
            require(compatible.resources.values.sum() <= LanSyncWorkspace.MAX_TOTAL)
        } catch (failure: Exception) {
            throw IllegalArgumentException("SYNC_LEGACY_PLAN_UNSUPPORTED", failure)
        }
    }

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
        if (record.type in LanSyncMoments.types) LanSyncMoments.validate(record)
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
        require(snapshot.records.none { it.type == "format_cards" && it.body["isDefault"] != JsonPrimitive(false) }) { "SYNC_DEVICE_DEFAULT_ON_WIRE" }
        require(snapshot.records.none { it.type == "moment_tasks" && it.body["requiresLocalScheduleApproval"] != JsonPrimitive(true) }) { "SYNC_SCHEDULE_AUTHORITY_ON_WIRE" }
        val referenced = linkedSetOf<String>()
        val mediaIdentities = linkedMapOf<String, String>()
        snapshot.records.forEach { record -> LanSyncWorkspace.mapResources(record.body) { reference, field ->
            when {
                reference.isBlank() || reference.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) -> Unit
                record.type == "save_slots" && (reference.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_IMAGE_PREFIX) ||
                    reference.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_AUDIO_PREFIX)) -> Unit
                else -> {
                    val parts = reference.split('/')
                    require(parts.size == 5 && parts[0] == "ccb-lan-v1" && parts[1] in setOf("images", "documents", "audio", "packages") &&
                        parts[2].matches(Regex("[a-f0-9-]{36}")) && parts[3].matches(Regex("[a-f0-9]{64}")) && parts[4].matches(Regex("[a-z0-9]{1,8}"))) { "SYNC_EXTERNAL_WIRE_REFERENCE" }
                    require((field == "packageFile") == (parts[1] == "packages") && (field == "audioPath") == (parts[1] == "audio")) { "SYNC_RESOURCE_KIND" }
                    require(parts[3] in snapshot.resources) { "SYNC_RESOURCE_MANIFEST" }
                    val identity = "${parts[1]}/${parts[2]}"
                    require(mediaIdentities[identity]?.let { it == parts[3] } != false) { "SYNC_RESOURCE_ID_CHANGED" }
                    mediaIdentities[identity] = parts[3]
                    referenced += parts[3]
                }
            }
            reference
        } }
        require(referenced == snapshot.resources.keys) { "SYNC_UNREFERENCED_RESOURCE" }
        val ids = snapshot.records.groupBy { it.type }.mapValues { (_, rows) -> rows.map { it.id }.toSet() }
        val messages = snapshot.records.filter { it.type == "chat_messages" }.associateBy { it.id }
        snapshot.records.forEach { record ->
            val session = record.text("sessionId")
            if (session != null && record.type !in LanSyncMoments.types) require(session in ids["chat_sessions"].orEmpty()) { "SYNC_ORPHAN_SESSION" }
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
            if (value.startsWith("ccb-lan-v1/")) value.split('/').getOrNull(3)?.let(::add)
            value
        } }
    }
}
