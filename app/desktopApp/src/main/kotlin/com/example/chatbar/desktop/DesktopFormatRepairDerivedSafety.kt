package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ConditionalMessageUpdateResult
import com.sun.jna.platform.win32.Kernel32
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** First production subset: absence of mutable derived state, never cleanup or source repair.
 * This owner uses the container's one storage/coordinator and TaskRuntime admission. A preflight
 * Boolean is not a lease: submit rescans under a top-level exclusive and invokes CAS inside it.
 */
internal class DesktopFormatRepairDerivedSafety(
    private val root: Path,
    private val storage: JsonFileStorage,
    private val coordinator: DesktopDataOperationCoordinator,
    private val tasks: DesktopTaskRuntime,
) : DesktopRepairSafetyGate {
    override suspend fun check(session: ChatSession, message: ChatMessage): DesktopRepairSafety =
        try { coordinator.withNormalOperation { inspect(session, message) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DesktopRepairSafety.UNKNOWN }

    override suspend fun submit(session: ChatSession, message: ChatMessage,
        write: suspend () -> ConditionalMessageUpdateResult): ConditionalMessageUpdateResult? {
        var writing = false
        var received: ConditionalMessageUpdateResult? = null
        return try {
            coordinator.withExclusiveMaintenance {
                if (inspect(session, message) != DesktopRepairSafety.VERIFIED_SAFE) null
                else {
                    writing = true
                    write().also { received = it }
                }
            }
        } catch (cancelled: CancellationException) {
            // Context exit can cancel delivery after Repository already returned durable evidence.
            // Preserve that receipt; without one, cancellation still discards uncommitted preview.
            received ?: throw cancelled
        }
        catch (error: Exception) { if (writing) throw error else null }
    }

    internal suspend fun inspect(session: ChatSession, message: ChatMessage): DesktopRepairSafety {
        if (!DesktopDataRootOwnership.isHeld(root) ||
            !tasks.hasRepairDerivedAdmission(session.id, message.id)) return DesktopRepairSafety.UNKNOWN
        inspectNamespaces()
        require(message.role == MessageRole.ASSISTANT && message.sessionId == session.id)
        require(!message.sourceTurnId.isNullOrBlank() && (message.sourceTurnOrder ?: 0) > 0)
        require(!message.currentAlternativeVersionId.isNullOrBlank())
        if (message.alternatives.isNotEmpty()) {
            require(message.currentAlternativeIndex in message.alternatives.indices &&
                message.alternativeVersionIds.size == message.alternatives.size &&
                message.alternativeVersionIds.none(String::isBlank) &&
                message.alternativeVersionIds.distinct().size == message.alternativeVersionIds.size &&
                message.currentAlternativeVersionId == message.alternativeVersionIds[message.currentAlternativeIndex])
        } else require(message.currentAlternativeIndex == 0 && message.alternativeVersionIds.isEmpty())

        // Only compact session identities are retained, with an explicit budget. Never retain chunks.
        val sessions = mutableMapOf<String, String>()
        var targetSession = false
        scan("chat_sessions", ChatSession.serializer()) { id, value ->
            require(id == value.id && id.length in 1..256 && value.characterCardId.length in 1..256 && sessions.size < 10_000 && id !in sessions)
            sessions[id] = value.characterCardId
            if (id == session.id) {
                require(value == session)
                targetSession = true
            }
            // Legacy mirrors lack sufficient source evidence to prove cross-session independence.
            // As with existing Memory graphs, this first subset conservatively refuses all of them.
            require(value.longTermMemory.isBlank() && value.longTermMemoryUpdatedThroughMessageId == null &&
                    value.memoryStateRevision == 0L && value.memoryHeadCommitId == null &&
                    value.sourceTurnTombstones.isEmpty() && value.timelineTombstones.isEmpty() &&
                    value.memoryUpdateStatus == MemoryUpdateStatus.IDLE && value.memoryArchiveStatus == MemoryUpdateStatus.IDLE &&
                    value.memoryHeadStatus == MemoryUpdateStatus.IDLE && value.memoryUpdateError == null &&
                    value.memoryArchiveError == null && value.memoryHeadError == null)
        }
        require(targetSession)
        val cards = mutableSetOf<String>()
        val documents = mutableMapOf<Pair<String, String>, String>()
        scan("character_cards", CharacterCard.serializer()) { id, card ->
            require(id == card.id && id.isNotBlank() && id.length <= 256 && cards.size < 10_000 && cards.add(id))
            require(card.pendingSpeakerRenameTasks.isEmpty())
            for (doc in card.customDocuments) {
                require(doc.id.isNotBlank() && doc.id.length <= 256 && doc.filePath.length in 1..1024 &&
                    documents.size < 10_000 && (id to doc.id) !in documents)
                documents[id to doc.id] = doc.filePath
            }
        }
        require(session.characterCardId in cards)
        var index: ChatMessageIndex? = null
        scan("chat_message_indexes", ChatMessageIndex.serializer()) { id, value ->
            require(id == value.sessionId && id in sessions && value.entries.map { it.messageId }.distinct().size == value.entries.size)
            if (id == session.id) index = value
        }
        val targetIndex = requireNotNull(index)
        val targetEntries = targetIndex.entries.associateBy { it.messageId }
        require(targetEntries.size == targetIndex.entries.size && targetEntries.keys.all { it.length in 1..256 })
        var indexedCount = 0
        var targetMessage = false
        scan("chat_messages", ChatMessage.serializer()) { id, value ->
            require(value.id.isNotBlank() && value.sessionId in sessions && id == "${value.sessionId}_${value.id}")
            if (value.sessionId == session.id) {
                require(targetEntries[value.id] == value.toIndexEntry())
                indexedCount++
            }
            if (value.sessionId == session.id && value.id == message.id) {
                require(value == message)
                targetMessage = true
            }
        }
        require(targetMessage && indexedCount == targetEntries.size)
        scan("vector_chunks", VectorChunk.serializer()) { id, chunk ->
            require(id == chunk.id && id.isNotBlank() && chunk.sourceId.isNotBlank())
            // First subset accepts only explicitly documented independent DOCUMENT blocks.
            require(chunk.sourceType == ChunkSourceType.DOCUMENT && chunk.messageId == null &&
                chunk.sourceId in cards && chunk.sourceId != session.id && chunk.embedding.all(Float::isFinite))
            require(chunk.metadata.keys.all { it in setOf("originalDocId", "documentId", "sourceLabel", "fileName", "contentHash", "embeddingKey") })
            require(!chunk.metadata["originalDocId"].isNullOrBlank() && !chunk.metadata["contentHash"].isNullOrBlank() &&
                !chunk.metadata["embeddingKey"].isNullOrBlank())
            require((chunk.sourceId to chunk.metadata.getValue("originalDocId")) in documents)
            require(chunk.metadata["documentId"] == null || chunk.metadata["documentId"] == chunk.metadata["originalDocId"])
            val documentPath = Path.of(documents.getValue(chunk.sourceId to chunk.metadata.getValue("originalDocId")))
            require(documentPath.isAbsolute && !documentPath.toString().startsWith("\\\\"))
            val normalized = documentPath.normalize()
            require(!normalized.startsWith(root.toAbsolutePath().normalize().resolve("entities")))
            validateDocumentFile(normalized)
            require(!Files.isSameFile(normalized, root.resolve("entities/chat_messages/${session.id}_${message.id}.json")))
        }
        // Any existing Memory graph is outside this first subset, including unrelated/orphan/legacy
        // graphs whose nested lineage has not been proven. Decode strictly before refusing it.
        absent("memory_states", MemorySessionState.serializer())
        absent("memory_nodes", MemoryNode.serializer())
        absent("memory_tier_revisions", MemoryTierRevision.serializer())
        absent("memory_compression_transactions", MemoryCompressionTransaction.serializer())
        absent("memory_commits", MemoryCommit.serializer())
        absent("memory_commit_journals", MemoryCommitJournal.serializer())
        absent("generated_voice_messages", GeneratedVoiceMessage.serializer())
        absent("voice_anchor_states", VoiceAnchorState.serializer())

        val posts = mutableMapOf<String, Pair<String, String>>()
        scan("moment_posts", JsonElement.serializer()) { id, value ->
            val post = historicalRecord(id, value, POST_STRINGS, POST_NULLABLE, POST_NUMBERS, POST_BOOLEANS,
                setOf("generatedImageMetadata"), setOf("id", "characterCardId", "sessionId", "senderName", "text", "scheduledAt", "generatedAt"))
            require(sessions[post.string("sessionId")] == post.string("characterCardId"))
            require(post["isPlaceholder"]?.jsonPrimitive?.booleanOrNull != true &&
                post["failureReason"] in setOf(null, JsonNull) && post.optionalString("generationCheckpoint").isBlank())
            post["generatedImageMetadata"]?.takeIf { it != JsonNull }?.let {
                STRICT_JSON.decodeFromJsonElement(GeneratedImageMetadata.serializer(), it)
            }
            require(posts.size < 10_000 && id !in posts)
            posts[id] = post.string("sessionId") to post.string("characterCardId")
        }
        scan("moment_tasks", JsonElement.serializer()) { id, value ->
            val task = historicalRecord(id, value, TASK_STRINGS, setOf("postId", "failureReason"), TASK_NUMBERS,
                emptySet(), emptySet(), setOf("id", "characterCardId", "sessionId", "scheduledAt"))
            require(sessions[task.string("sessionId")] == task.string("characterCardId"))
            when (task.optionalString("status")) {
                "COMPLETED" -> require(posts[task.optionalString("postId")] ==
                    (task.string("sessionId") to task.string("characterCardId")) && task["failureReason"] in setOf(null, JsonNull))
                "SKIPPED" -> require(task["postId"] in setOf(null, JsonNull))
                else -> throw IOException("Resumable derived task")
            }
        }
        // No shared SaveSlot decoder/runtime exists here yet: preserve, but refuse nonempty slots.
        absent("save_slots", JsonElement.serializer())
        require(DesktopDataRootOwnership.isHeld(root) && tasks.hasRepairDerivedAdmission(session.id, message.id))
        return DesktopRepairSafety.VERIFIED_SAFE
    }

    private suspend fun <T : Any> scan(type: String, serializer: KSerializer<T>, visit: suspend (String, T) -> Unit) =
        storage.visitEntitiesStrict(type, serializer, validatePath = ::validateWindowsPath, visit = visit)

    private suspend fun <T : Any> absent(type: String, serializer: KSerializer<T>) {
        scan(type, serializer) { _, _ -> throw IOException("Derived state is outside the proven subset") }
    }

    private fun inspectNamespaces() {
        val directory = root.resolve("entities")
        var ancestor = directory.toAbsolutePath().normalize().root
        for (part in directory.toAbsolutePath().normalize()) {
            ancestor = ancestor.resolve(part)
            val attr = Files.readAttributes(ancestor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attr.isDirectory && !attr.isSymbolicLink && !attr.isOther)
            validateWindowsPath(ancestor)
        }
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                val attr = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                validateWindowsPath(entry)
                require(!attr.isSymbolicLink && !attr.isOther)
                require((name in DIRECTORIES && attr.isDirectory) || (name in SINGLETONS && attr.isRegularFile))
            }
        }
    }

    /** Metadata only; no document bytes are read. Missing, redirected or live entity sources refuse. */
    private fun validateDocumentFile(path: Path) {
        var ancestor = path.root
        for (part in path) {
            ancestor = ancestor.resolve(part)
            val attr = Files.readAttributes(ancestor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!attr.isSymbolicLink && !attr.isOther && if (ancestor == path) attr.isRegularFile else attr.isDirectory)
            validateWindowsPath(ancestor)
        }
    }

    /** Android-only Moment serializers are not duplicated. This read-only historical projection
     * recognizes their exact known field/type contract and rejects every unrecognized shape.
     */
    private fun historicalRecord(id: String, value: JsonElement, strings: Set<String>, nullable: Set<String>,
        numbers: Set<String>, booleans: Set<String>, objects: Set<String>, required: Set<String>): JsonObject {
        val record = value as? JsonObject ?: throw IOException("Invalid historical record")
        require(record.keys.all { it in strings + nullable + numbers + booleans + objects } && record.keys.containsAll(required))
        require(record.string("id") == id && id.length in 1..256)
        record.forEach { (key, field) ->
            when (key) {
                in strings -> require(field is JsonPrimitive && field.isString)
                in nullable -> require(field == JsonNull || (field is JsonPrimitive && field.isString))
                in numbers -> require(field is JsonPrimitive && !field.isString && field.longOrNull != null)
                in booleans -> require(field is JsonPrimitive && !field.isString && field.booleanOrNull != null)
                in objects -> require(field == JsonNull || field is JsonObject)
            }
        }
        return record
    }

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IOException("Missing historical identity")
    private fun JsonObject.optionalString(key: String): String = get(key)?.takeIf { it != JsonNull }?.jsonPrimitive?.content.orEmpty()

    private fun validateWindowsPath(path: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString())
            if (attributes == -1 || attributes and 0x400 != 0) throw IOException("Unverifiable or reparse inventory path")
        }
    }

    companion object {
        private val DIRECTORIES = setOf("chat_sessions", "chat_messages", "chat_message_indexes", "chat_message_order_backups",
            "chat_drafts", "chat_scroll_positions", "character_cards", "format_cards", "world_books", "model_configs",
            "embedding_configs", "embedding_model_config", "retrieval_model_config", "edit_drafts", "vector_chunks", "memory_states", "memory_nodes", "memory_tier_revisions",
            "memory_compression_transactions", "memory_commits", "memory_commit_journals", "moment_posts", "moment_tasks",
            "generated_voice_messages", "voice_anchor_states", "save_slots")
        private val SINGLETONS = setOf("app_settings.json", "player_setting.json", "embedding_model_config.json")
        private val STRICT_JSON = Json { ignoreUnknownKeys = false }
        private val POST_STRINGS = setOf("id", "characterCardId", "sessionId", "senderName", "text", "imagePrompt", "imageBrief", "generationCheckpoint", "generationReason")
        private val POST_NULLABLE = setOf("senderCharacterId", "senderAvatar", "imagePath", "failureReason")
        private val POST_NUMBERS = setOf("baseLikeCount", "scheduledAt", "generatedAt", "createdAt", "updatedAt")
        private val POST_BOOLEANS = setOf("isPrivate", "userLiked", "isPlaceholder")
        private val TASK_STRINGS = setOf("id", "characterCardId", "sessionId", "status")
        private val TASK_NUMBERS = setOf("scheduledAt", "createdAt", "updatedAt")
    }
}
