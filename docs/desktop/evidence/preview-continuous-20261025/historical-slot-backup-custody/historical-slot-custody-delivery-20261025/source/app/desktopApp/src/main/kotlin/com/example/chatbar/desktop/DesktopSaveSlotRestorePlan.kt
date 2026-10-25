package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.memory.*
import java.nio.file.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*

/** Builds the complete replacement in an owned, isolated directory. Never writes the live root. */
internal class DesktopSaveSlotRestorePlan(private val liveRoot: Path) {
    data class Plan(val directory: Path, val session: ChatSession,
        val files: Map<String, Path>, val proof: Map<String, String>)

    suspend fun prepare(archive: DesktopSaveSlotArchive, prepared: DesktopSaveSlotArchive.Prepared,
        target: ChatSession, duplicate: Boolean = false): Plan = withContext(Dispatchers.IO) {
        archive.verify(prepared)
        val directory = Files.createDirectory(prepared.directory.resolve("restore-${UUID.randomUUID()}"))
        val storage = JsonFileStorage(directory) // Offline graph only; live commit uses the real coordinator.
        val chats = ChatRepository(storage)
        val voices = VoiceMessageRepository(storage, strictReads = true)
        val messageFiles = linkedMapOf<String, Path>()
        val ragFiles = mutableListOf<Path>()
        val records = Files.createDirectory(directory.resolve("records"))
        val rawVoices = mutableListOf<GeneratedVoiceMessage>()
        var bytes = 0L
        fun message(value: ChatMessage) {
            bytes += storage.json.encodeToString(ChatMessage.serializer(), value).toByteArray().size
            check(bytes <= MAX_GRAPH && messageFiles.size < MAX_RECORDS) { "存档正文超过单次恢复预算" }
            check(value.id !in messageFiles) { "存档消息身份重复" }
            val path = records.resolve("message-${messageFiles.size}.json")
            Files.writeString(path, storage.json.encodeToString(ChatMessage.serializer(), MessageAlternativeVersionPolicy.normalize(value)), StandardOpenOption.CREATE_NEW)
            messageFiles[value.id] = path
        }
        fun chunk(value: VectorChunk) {
            bytes += storage.json.encodeToString(VectorChunk.serializer(), value).toByteArray().size
            check(bytes <= MAX_GRAPH && ragFiles.size < MAX_RECORDS)
            val path = records.resolve("rag-${ragFiles.size}.json")
            Files.writeString(path, storage.json.encodeToString(VectorChunk.serializer(), value), StandardOpenOption.CREATE_NEW)
            ragFiles.add(path)
        }
        var background: String? = null
        var audioSource: (String) -> java.io.InputStream
        val handoff = DesktopVoiceArchiveHandoff(DesktopVoiceAudioStore(directory, extendedPaths = true), voices,
            retainHistoricalVersions = true)
        val codec = SaveSlotPackageStorage(directory.toFile(), { _, _ -> error("恢复不能压缩原图") },
            maximumImportBytes = DesktopSaveSlotArchive.MAX_ARCHIVE)
        val slot = prepared.slot
        val reader = if (prepared.format == SlotInspectFormat.ZIP_V8) {
            val imported = Files.newInputStream(prepared.file).use { codec.importPackage(it, target.id) { name -> name } }
            codec.openReader(imported.slot)
        } else null
        try {
            if (reader != null) {
                reader.validate()
                reader.streamMessages(target.id, ::message)
                reader.streamRag(target.id, ::chunk)
                reader.streamVoices { rawVoices += it }
                background = reader.materializeBackground()
                audioSource = reader::openAudioResource
            } else {
                val imagePaths = linkedMapOf<String, String>()
                slot.imageResources.forEach { (reference, resource) ->
                    currentCoroutineContext().ensureActive()
                    val extension = resource.fileName.substringAfterLast('.', "png").lowercase()
                    check(extension in setOf("png", "jpg", "jpeg", "webp", "gif"))
                    val home = directory.resolve("images/save_slots/${UUID.randomUUID()}")
                    Files.createDirectories(home)
                    val path = home.resolve("${UUID.randomUUID()}.$extension")
                    val data = Base64.getDecoder().decode(resource.data)
                    check(data.size <= DesktopImageEditing.MAX_BYTES)
                    Files.write(path, data, StandardOpenOption.CREATE_NEW)
                    imagePaths[reference] = path.toString()
                }
                fun image(reference: String): String = if (reference.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) reference
                    else requireNotNull(imagePaths[reference]) { "旧存档图片缺少内嵌资源；不读取外部路径" }
                slot.messages.forEach { message(it.copy(sessionId = target.id, images = it.images.map(::image),
                    generatedImageMetadata = it.generatedImageMetadata.map { meta -> meta.copy(imagePath = image(meta.imagePath)) })) }
                slot.vectorChunks.forEach { chunk(it.copy(id = UUID.randomUUID().toString(), sourceId = target.id)) }
                background = slot.chatBackground?.let(::image)
                val audioResources = linkedMapOf<String, SaveSlotAudioResource>()
                val legacyVoices = slot.voiceMessages.map { voice ->
                    val key = requireNotNull(DesktopSaveSlotInspector.legacyAudioResourceKey(slot, voice))
                    val resource = UUID.randomUUID().toString()
                    audioResources[resource] = slot.audioResources.getValue(key)
                    voice.copy(audioPath = SAVE_SLOT_AUDIO_PREFIX + resource)
                }
                rawVoices += legacyVoices
                audioSource = { resource -> Base64.getDecoder().wrap(audioResources.getValue(resource).data.byteInputStream(Charsets.US_ASCII)) }
            }
            val copy = SessionCopyPolicy(target.id, messageFiles.keys)
            val voiceGroups = rawVoices.groupBy { it.messageId }
            check(voiceGroups.keys.all { it in messageFiles }) { "录音缺少所属消息" }
            val stagedVoices = mutableListOf<GeneratedVoiceMessage>()
            val voiceIds = linkedMapOf<String, String>()
            var stagedAudioBytes = 0L
            val publishedImages = linkedMapOf<String, String>()
            suspend fun imageReference(raw: String): String {
                if (raw.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) return raw
                publishedImages[raw]?.let { return it }
                val path = Path.of(raw).toAbsolutePath().normalize()
                check(path.startsWith(directory.resolve("images/save_slots")))
                DesktopArchiveFiles.safe(path, false)
                check(Files.size(path) <= DesktopImageEditing.MAX_BYTES)
                DesktopImageEditing.decode(Files.readAllBytes(path), longestSide = 1).flush()
                val reference = "images/${UUID.randomUUID()}.${path.fileName.toString().substringAfterLast('.')}"
                DesktopArchiveFiles.copyNew(path, directory.resolve(reference), DesktopImageEditing.MAX_BYTES.toLong())
                publishedImages[raw] = reference
                return reference
            }
            var session = SaveSlotSessionPolicy.restore(target, slot, background?.let { imageReference(it) })
            if (duplicate) session = copy.session(session, target.id,
                target.displayTitleOverride ?: target.title, target.createdAt)
            chats.createSession(session)
            chats.replaceMessagesForSessionStreaming(target.id) { emit ->
                messageFiles.forEach { (sourceId, path) ->
                    currentCoroutineContext().ensureActive()
                    val original = storage.json.decodeFromString(ChatMessage.serializer(), Files.readString(path))
                    val mapped = if (duplicate) copy.message(original, target.id) else original.copy(sessionId = target.id)
                    val message = mapped.copy(images = mapped.images.map { imageReference(it) },
                        generatedImageMetadata = mapped.generatedImageMetadata.map { it.copy(imagePath = imageReference(it.imagePath)) })
                    val sourceVoices = voiceGroups[sourceId].orEmpty()
                    if (sourceVoices.isNotEmpty()) {
                        val batch = handoff.stage(sourceVoices, target.id, mapOf(sourceId to message), audioSource)
                        stagedAudioBytes += batch.voices.sumOf { it.byteLength }
                        check(stagedAudioBytes <= 64L * 1024 * 1024) { "存档音频超过恢复预算" }
                        stagedVoices += batch.voices
                        val versions = MessageAlternativeVersionPolicy.versions(original).map { it.id }.toSet()
                        sourceVoices.zip(batch.voices).forEach { (source, mapped) ->
                            if (source.messageVersionId != null && source.messageVersionId !in versions)
                                check(voiceIds.put(source.id, mapped.id) == null)
                        }
                    }
                    emit(message)
                }
            }
            ragFiles.forEach { path ->
                val original = storage.json.decodeFromString(VectorChunk.serializer(), Files.readString(path))
                val value = (if (duplicate) copy.chunk(original) else original).let { value ->
                    value.copy(metadata = value.metadata.mapValues { (key, text) -> if (key == "sessionId") target.id else text })
                }
                storage.saveEntity("vector_chunks", value.id, value, VectorChunk.serializer())
            }
            val rewrittenAudio = stagedVoices.map { voice ->
                val staged = Path.of(voice.audioPath)
                check(staged.startsWith(directory.resolve("audio/generated")))
                voice.copy(audioPath = liveRoot.resolve(directory.relativize(staged)).toString())
            }
            voices.restoreForSessionStreaming(target.id, rewrittenAudio) { emit -> chats.forEachMessage(target.id, action = emit) }
            val restoredVoices = voices.listForSession(target.id).associateBy { it.id }
            com.example.chatbar.desktop.backup.DesktopSlotHistoricalVoiceCustody.preserve(prepared, directory,
                voiceIds.mapValues { (_, id) -> restoredVoices.getValue(id) })
            val memory = MemoryRepository(storage, strictReads = true)
            LongTermMemoryService(chats, memory, SettingsRepository(storage), NoArchiveNetwork,
                ContextWindowManager()).loadSnapshot(target.id, slot.memorySnapshot)
            session = requireNotNull(chats.getSession(target.id))
            archive.verify(prepared)
            val files = linkedMapOf<String, Path>()
            val ownedEntities = DesktopSessionInventory(directory).read(target.id).keys
            Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                val relative = directory.relativize(path).joinToString("/")
                // The imported source package is an input, not a new library slot.
                if (relative in ownedEntities || relative in publishedImages.values || relative.startsWith("audio/") ||
                    relative.startsWith(com.example.chatbar.desktop.backup.DesktopHistoricalVoiceCustody.ROOT + "/")) files[relative] = path
            } }
            check(files.size <= MAX_RECORDS)
            var total = 0L
            val proof = files.mapValues { (_, path) ->
                total += Files.size(path); check(total <= 256L * 1024 * 1024) { "存档恢复及历史录音原包保全超过单次 256 MiB 预算；原存档及当前会话均已保留" }
                DesktopArchiveFiles.hash(path, DesktopSaveSlotArchive.MAX_ARCHIVE)
            }
            Plan(directory, session, files, proof)
        } finally { reader?.close() }
    }

    suspend fun verify(plan: Plan) {
        plan.files.forEach { (relative, path) ->
            check(path == plan.directory.resolve(relative))
            check(DesktopArchiveFiles.hash(path, DesktopSaveSlotArchive.MAX_ARCHIVE) == plan.proof.getValue(relative))
        }
    }

    private object NoArchiveNetwork : MemoryAiClient {
        override suspend fun episode(model: ModelConfig, renderedTurns: String, summaryPromptMaxChars: Int,
            onStreamingSummary: ((String) -> Unit)?, validate: (EpisodeResponse) -> Unit): EpisodeResponse = error("存档恢复禁止模型请求")
        override suspend fun compression(model: ModelConfig, kind: MemoryCompressionKind, forcedConsumedChildIds: List<String>,
            renderedChildren: String, onStreamingSummary: ((String) -> Unit)?, validate: (CompressionResponse) -> Unit): CompressionResponse = error("存档恢复禁止模型请求")
        override suspend fun head(model: ModelConfig, mode: MemoryHeadUpdateMode, throughT: Long, currentHead: String,
            archive: String, sourceTurns: String, validate: (HeadResponse) -> Unit): HeadResponse = error("存档恢复禁止模型请求")
    }
    companion object { private const val MAX_GRAPH = 64L * 1024 * 1024; private const val MAX_RECORDS = 20000 }
}
