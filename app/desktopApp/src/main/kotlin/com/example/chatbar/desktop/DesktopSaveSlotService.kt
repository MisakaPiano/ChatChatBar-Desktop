package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import java.nio.file.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer

internal interface DesktopSessionReservation {
    suspend fun <T> run(sessionId: String, operation: suspend () -> T): T
}
internal data class DesktopSessionBindingChoices(val session: ChatSession, val characters: List<CharacterCard>, val books: List<WorldBook>)

/** One app-owned archive service. Preparation and native drain precede exclusive publication. */
internal class DesktopSaveSlotService(
    private val root: Path,
    private val gate: DesktopDataOperationCoordinator,
    private val audio: DesktopAudioMaintenance,
    private val reservation: DesktopSessionReservation,
    private val chats: ChatRepository,
    private val voices: VoiceMessageRepository,
    private val memorySnapshot: suspend (String) -> MemorySnapshot?,
    private val mediaSource: suspend (String) -> Path,
    private val journal: DesktopSessionCommitJournal = DesktopSessionCommitJournal(root),
) {
    private val mutex = Mutex()
    private val storage = JsonFileStorage(root, gate)
    private val inventory = DesktopSessionInventory(root)
    private val workspace = root.resolve("backups/.save-slot-work")
    private val archive get() = DesktopSaveSlotArchive(workspace)
    private val planner = DesktopSaveSlotRestorePlan(root) { target, ids -> gate.withNormalOperation {
        var foreign = false; var count = 0
        storage.visitEntitiesStrict("chat_messages", ChatMessage.serializer(), validatePath = {
            DesktopArchiveFiles.safe(it, Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS))
        }) { _, message ->
            check(++count <= 40000) { "会话身份清单超过校验预算；原数据保留" }
            if (message.sessionId != target && message.id in ids) foreign = true
        }
        foreign
    } }

    suspend fun list(sessionId: String): List<SaveSlotSummary> = gate.withNormalOperation {
        val home = root.resolve("entities/save_slots")
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return@withNormalOperation emptyList()
        DesktopArchiveFiles.safe(home, true)
        val values = mutableListOf<SaveSlotSummary>()
        var total = 0L
        Files.newDirectoryStream(home).use { files -> files.forEach { path ->
            currentCoroutineContext().ensureActive()
            check(values.size < 4096 && path.fileName.toString().endsWith(".json"))
            DesktopArchiveFiles.safe(path, false)
            val size = Files.size(path); total += size
            check(size <= 64L * 1024 * 1024 && total <= 256L * 1024 * 1024)
            val proof = DesktopArchiveFiles.hash(path, 64L * 1024 * 1024)
            val summary = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
                DesktopSaveSlotSummaryReader.read(path.fileName.toString().removeSuffix(".json"), it)
            }
            check(DesktopArchiveFiles.hash(path, 64L * 1024 * 1024) == proof)
            values += summary
        } }
        values.filter { it.sessionId == sessionId }.sortedByDescending { it.createdAt }
    }

    suspend fun bindingChoices(sessionId: String): DesktopSessionBindingChoices = gate.withNormalOperation {
        DesktopSessionBindingChoices(strictEntity("chat_sessions", sessionId, ChatSession.serializer()),
            strictEntities("character_cards", CharacterCard.serializer()), strictEntities("world_books", WorldBook.serializer()))
    }

    suspend fun create(sessionId: String, name: String, imagePolicy: SaveSlotImagePolicy,
        includeAudio: Boolean, progress: (String) -> Unit = {}): SaveSlot = mutex.withLock {
        require(name.isNotBlank() && name.length <= 120)
        reservation.run(sessionId) {
            val capture = capture(sessionId, name, imagePolicy, includeAudio, progress)
            val addition = libraryAddition(capture.prepared, sessionId)
            publish(capture.sourceId, capture.proof, null, emptyMap(), addition.second)
            addition.first
        }
    }

    /** Import stores an independently validated archive. It never loads it into the conversation. */
    suspend fun import(source: Path, sessionId: String): SaveSlot = mutex.withLock {
        reservation.run(sessionId) {
            val baseline = gate.withNormalOperation { ensureWorkspace(); inventory.read(sessionId) }
            check(baseline.containsKey("entities/chat_sessions/$sessionId.json"))
            val prepared = gate.withNormalOperation { archive.prepare(source) }
            val addition = libraryAddition(prepared, sessionId)
            publish(sessionId, baseline, null, emptyMap(), addition.second)
            addition.first
        }
    }

    suspend fun export(slotId: String, destination: Path) = mutex.withLock {
        val prepared = gate.withNormalOperation { prepareLibrary(slotId) }
        archive.export(prepared, destination)
    }

    /** Confirmed deletion affects a catalog record and, only after a complete reference proof, its package. */
    suspend fun delete(slotId: String, sessionId: String) = mutex.withLock {
        require(slotId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        reservation.run(sessionId) {
            val sourceProof = gate.withNormalOperation { inventory.read(sessionId) }
            val catalog = gate.withNormalOperation { libraryOwnership() }
            val selected = requireNotNull(catalog[slotId]) { "存档不存在" }
            check(selected.summary.sessionId == sessionId) { "存档不属于当前会话" }
            val removed = linkedMapOf("entities/save_slots/$slotId.json" to selected.hash)
            val name = selected.packageName
            if (selected.summary.schemaVersion == 8) check(name != null) { "存档包身份不完整" }
            if (name != null && catalog.none { (id, item) -> id != slotId && item.packageName == name }) {
                val relative = "save_slot_packages/$name"
                removed[relative] = gate.withNormalOperation { DesktopArchiveFiles.hash(root.resolve(relative), DesktopSaveSlotArchive.MAX_ARCHIVE) }
            }
            publish(sessionId, sourceProof, null, removed, emptyMap()) {
                check(libraryOwnership() == catalog) { "存档引用已变化，请重新确认删除" }
            }
        }
    }

    private data class LibraryOwner(val summary: SaveSlotSummary, val packageName: String?, val hash: String)
    private suspend fun libraryOwnership(): Map<String, LibraryOwner> {
        val home = root.resolve("entities/save_slots")
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        DesktopArchiveFiles.safe(home, true)
        val values = linkedMapOf<String, LibraryOwner>()
        var total = 0L
        Files.newDirectoryStream(home).use { paths -> for (path in paths) {
            currentCoroutineContext().ensureActive()
            check(values.size < 4096 && path.fileName.toString().matches(Regex("[A-Za-z0-9_-]{1,128}\\.json")))
            DesktopArchiveFiles.safe(path, false)
            total += Files.size(path); check(total <= 256L * 1024 * 1024)
            val hash = DesktopArchiveFiles.hash(path, 64L * 1024 * 1024)
            val id = path.fileName.toString().removeSuffix(".json")
            var name: String? = null
            val summary = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
                DesktopSaveSlotSummaryReader.read(id, it) { name = it }
            }
            check(summary.schemaVersion != 8 || name != null)
            check(DesktopArchiveFiles.hash(path, 64L * 1024 * 1024) == hash)
            values[id] = LibraryOwner(summary, name, hash)
        } }
        return values
    }

    /** Caller obtains explicit user confirmation before entering this destructive operation. */
    suspend fun restore(slotId: String, sessionId: String, progress: (String) -> Unit = {}): ChatSession = mutex.withLock {
        reservation.run(sessionId) {
            // This safety slot is retained together with the atomic replacement, including its old media.
            val safety = capture(sessionId, "恢复前自动保全", SaveSlotImagePolicy.ORIGINAL, true, progress)
            val before = libraryAddition(safety.prepared, sessionId)
            val prepared = gate.withNormalOperation { prepareLibrary(slotId) }
            val plan = planner.prepare(archive, prepared, safety.session)
            publish(sessionId, safety.proof, plan, safety.proof, before.second)
            plan.session
        }
    }

    suspend fun duplicate(sessionId: String, progress: (String) -> Unit = {}): ChatSession = mutex.withLock {
        reservation.run(sessionId) {
            val source = capture(sessionId, "复制会话", SaveSlotImagePolicy.ORIGINAL, true, progress)
            val target = UUID.randomUUID().toString().let { id ->
                val policy = SessionCopyPolicy(id, source.messageIds)
                policy.session(source.session, id, SessionCopyPolicy.copyName(source.session, chats.getAllSessions()), System.currentTimeMillis())
            }
            val plan = planner.prepare(archive, source.prepared, target, duplicate = true)
            // Drafts are local working text, not part of the portable SaveSlot format.
            val extras = linkedMapOf<String, Path>()
            if (source.draft.isNotEmpty()) {
                val path = source.prepared.directory.resolve("duplicate-draft.json")
                Files.writeString(path, storage.json.encodeToString(ChatDraft.serializer(), ChatDraft(target.id, source.draft)), StandardOpenOption.CREATE_NEW)
                extras["entities/chat_drafts/${target.id}.json"] = path
            }
            publish(sessionId, source.proof, plan, emptyMap(), extras)
            plan.session
        }
    }

    suspend fun rebind(sessionId: String, characterId: String, extraWorldBookIds: List<String>): ChatSession = mutex.withLock {
        require(extraWorldBookIds.distinct().size == extraWorldBookIds.size && extraWorldBookIds.size <= 128)
        reservation.run(sessionId) {
            val before = capture(sessionId, "资源重绑定前保全", SaveSlotImagePolicy.ORIGINAL, true) {}
            val resourceProof = linkedMapOf<String, String>()
            gate.withNormalOperation {
                val card = strictEntity("character_cards", characterId, CharacterCard.serializer())
                check(card.id == characterId)
                resourceProof["entities/character_cards/$characterId.json"] = DesktopArchiveFiles.hash(root.resolve("entities/character_cards/$characterId.json"), 8L * 1024 * 1024)
                (card.worldBookIds + listOfNotNull(card.boundWorldBookId) + extraWorldBookIds).distinct().forEach { id ->
                    check(strictEntity("world_books", id, WorldBook.serializer()).id == id)
                    val relative = "entities/world_books/$id.json"
                    resourceProof[relative] = DesktopArchiveFiles.hash(root.resolve(relative), 8L * 1024 * 1024)
                }
            }
            val rebound = before.session.copy(characterCardId = characterId, extraWorldBookIds = extraWorldBookIds,
                timedWorldInfo = emptyMap(), updatedAt = System.currentTimeMillis())
            val additions = libraryAddition(before.prepared, sessionId).second.toMutableMap()
            val path = before.prepared.directory.resolve("rebound-session.json")
            Files.writeString(path, storage.json.encodeToString(ChatSession.serializer(), rebound), StandardOpenOption.CREATE_NEW)
            val relative = "entities/chat_sessions/$sessionId.json"
            additions[relative] = path
            publish(sessionId, before.proof, null, mapOf(relative to before.proof.getValue(relative)), additions,
                refreshSessionId = sessionId) {
                resourceProof.forEach { (key, hash) -> check(DesktopArchiveFiles.hash(root.resolve(key), 8L * 1024 * 1024) == hash) }
            }
            rebound
        }
    }

    private data class Capture(val sourceId: String, val session: ChatSession, val draft: String, val messageIds: Set<String>,
        val proof: Map<String, String>, val prepared: DesktopSaveSlotArchive.Prepared)

    private suspend fun capture(sessionId: String, name: String, images: SaveSlotImagePolicy, includeAudio: Boolean,
        progress: (String) -> Unit): Capture = gate.withNormalOperation {
        ensureWorkspace()
        progress("正在保存会话与记忆")
        val memory = memorySnapshot(sessionId)
        val messageIds = chats.getMessageIds(sessionId)
        check(messageIds.size <= 20000)
        val proof = inventory.read(sessionId)
        val session = strictEntity("chat_sessions", sessionId, ChatSession.serializer())
        check(proof.keys.count { it.startsWith("entities/chat_messages/") } == messageIds.size)
        val chunkPaths = proof.keys.filter { it.startsWith("entities/vector_chunks/") }
        val recorded = if (includeAudio) proof.keys.filter { it.startsWith("entities/generated_voice_messages/") }.map {
            storage.json.decodeFromString(GeneratedVoiceMessage.serializer(), Files.readString(root.resolve(it)))
        } else emptyList()
        val sources = linkedMapOf<String, Path>()
        if (images != SaveSlotImagePolicy.NONE) {
            suspend fun include(reference: String) {
                if (reference.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX) || reference in sources) return
                check(sources.size < 20000)
                sources[reference] = mediaSource(reference)
            }
            chats.forEachMessage(sessionId) { message -> message.images.forEach { include(it) } }
            session.chatBackground?.let { include(it) }
            session.desktopPlayerAvatar?.let { include(it) }
        }
        recorded.forEach { sources[it.audioPath] = mediaSource(it.audioPath) }
        val mediaProof = sources.mapValues { DesktopArchiveFiles.hash(it.value, DesktopSaveSlotArchive.MAX_ARCHIVE) }
        val prepared = archive.create(SaveSlotSessionPolicy.create(session, name, null, memory, images, includeAudio),
            images, includeAudio, { emit -> chats.forEachMessage(sessionId, action = emit) }, { emit -> chunkPaths.forEach { path ->
                emit(storage.json.decodeFromString(VectorChunk.serializer(), Files.readString(root.resolve(path))))
            } }, recorded,
            { sources.getValue(it) }, progress)
        check(inventory.read(sessionId) == proof) { "会话在保存期间变化，请重试" }
        sources.forEach { (ref, path) -> check(DesktopArchiveFiles.hash(path, DesktopSaveSlotArchive.MAX_ARCHIVE) == mediaProof.getValue(ref)) }
        Capture(sessionId, session, chats.getSessionDraft(sessionId), messageIds, proof, prepared)
    }

    private suspend fun libraryAddition(prepared: DesktopSaveSlotArchive.Prepared, sessionId: String): Pair<SaveSlot, Map<String, Path>> {
        archive.verify(prepared)
        val work = Files.createDirectory(prepared.directory.resolve("library-${UUID.randomUUID()}"))
        val files = linkedMapOf<String, Path>()
        val slot = if (prepared.format == SlotInspectFormat.ZIP_V8) {
            val codec = SaveSlotPackageStorage(work.toFile(), { _, _ -> error("导入不重新编码") }, maximumImportBytes = DesktopSaveSlotArchive.MAX_ARCHIVE)
            val imported = Files.newInputStream(prepared.file).use { codec.importPackage(it, sessionId) { name -> name } }.slot
            val relative = "save_slot_packages/${requireNotNull(imported.packageRef).fileName}"
            files[relative] = work.resolve(relative)
            imported
        } else prepared.slot.copy(id = UUID.randomUUID().toString(), sessionId = sessionId,
            // Keep source graph identity together; local library ownership differs only at its envelope.
            messages = prepared.slot.messages.map { it.copy(sessionId = sessionId) },
            vectorChunks = prepared.slot.vectorChunks.map { it.copy(sourceId = sessionId,
                metadata = it.metadata.mapValues { (key, value) -> if (key == "sessionId") sessionId else value }) },
            voiceMessages = prepared.slot.voiceMessages.map { it.copy(sessionId = sessionId) }, createdAt = System.currentTimeMillis())
        val entity = work.resolve("slot.json")
        Files.writeString(entity, storage.json.encodeToString(SaveSlot.serializer(), slot), StandardOpenOption.CREATE_NEW)
        files["entities/save_slots/${slot.id}.json"] = entity
        return slot to files
    }

    private suspend fun prepareLibrary(slotId: String): DesktopSaveSlotArchive.Prepared {
        ensureWorkspace()
        val slot = strictEntity("save_slots", slotId, SaveSlot.serializer())
        val path = if (slot.schemaVersion == 8) {
            val name = requireNotNull(slot.packageRef).fileName
            check(name.matches(Regex("[A-Za-z0-9_-]{1,128}\\.cbsave")))
            root.resolve("save_slot_packages/$name")
        } else root.resolve("entities/save_slots/$slotId.json")
        return archive.prepare(path)
    }

    private suspend fun publish(sourceId: String, sourceProof: Map<String, String>, plan: DesktopSaveSlotRestorePlan.Plan?,
        replaced: Map<String, String>, additions: Map<String, Path>, refreshSessionId: String? = plan?.session?.id,
        validateResources: suspend () -> Unit = {}) {
        plan?.let { planner.verify(it) }
        val changes = linkedMapOf<String, Path?>().apply {
            replaced.keys.forEach { put(it, null) }
            plan?.files?.forEach { (key, value) -> put(key, value) }
            additions.forEach { (key, value) -> check(key !in this || this[key] == null); put(key, value) }
        }
        val expected = changes.mapValues { (key, _) -> replaced[key] }
        audio.withMaintenance(true, { gate.isRestartRequired }) {
            gate.withExclusiveMaintenance {
                check(inventory.read(sourceId) == sourceProof) { "会话已变化，请重新准备" }
                validateResources()
                if (plan != null && plan.session.id != sourceId) check(inventory.read(plan.session.id).isEmpty())
                plan?.let { planner.verify(it) }
                try {
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        journal.commit(changes, expected)
                        try { refreshSessionId?.let { chats.reloadAfterSessionCommit(it); voices.reload() } }
                        catch (error: Throwable) { requireRestart(); throw error }
                    }
                } catch (error: Throwable) {
                    if (journal.hasPending()) requireRestart()
                    throw error
                }
            }
        }
    }

    private fun ensureWorkspace() {
        var ancestor = workspace
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.parent
        DesktopArchiveFiles.safe(ancestor, true)
        Files.createDirectories(workspace); DesktopArchiveFiles.safe(workspace, true)
    }
    private suspend fun <T : Any> strictEntity(type: String, id: String, serializer: KSerializer<T>): T {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return when (val result = storage.readEntityStrict(type, id, serializer)) {
            is JsonFileStorage.EntityReadResult.Valid -> result.value
            else -> error("存档或会话无法安全读取，原数据已保留")
        }
    }
    private suspend fun <T : Any> strictEntities(type: String, serializer: KSerializer<T>): List<T> {
        val values = mutableListOf<T>()
        storage.visitEntitiesStrict(type, serializer, maximumFileBytes = 1024 * 1024) { _, value ->
            check(values.size < 4096) { "存档列表超过读取预算" }
            values += value
        }
        return values
    }
}
