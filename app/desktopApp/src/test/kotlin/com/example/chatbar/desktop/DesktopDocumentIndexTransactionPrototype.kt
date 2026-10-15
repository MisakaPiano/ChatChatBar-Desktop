package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.domain.rag.ChunkingEngine
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** TEST ONLY. No production entry point, schema registration, recovery hook or HTTP gateway. */
internal enum class IndexTxnCut {
    BEFORE_STAGE, AFTER_STAGE, BEFORE_JOURNAL, INTENT_WITHOUT_SEAL, AFTER_JOURNAL,
    FIRST_OLD_MOVED, FIRST_NEW_INSTALLED, ALL_NEW_INSTALLED, BEFORE_CARD, AFTER_CARD,
    BEFORE_MARKER, AFTER_MARKER, DURING_CLEANUP, AFTER_CLEANUP,
    RECOVERY_FIRST_NEW_REMOVED, RECOVERY_FIRST_OLD_RESTORED,
}
internal enum class IndexTxnOutcome { NOT_COMMITTED, COMMITTED, COMMIT_UNCERTAIN }
internal data class IndexTxnReceipt(val outcome: IndexTxnOutcome, val businessOpen: Boolean)

internal class IndexTxnIntent(val id: String, val oldCard: String, val newCard: String,
    val sourceHash: String, val configHash: String, val old: Map<String, String>,
    val next: Map<String, String>, val other: Map<String, String>)

/** Deliberately small, synthetic-only journal experiment, not a production transaction service.
 * Metadata never embeds card/vector/document bodies; staged payload is confined to test temp roots.
 * force(true) + ATOMIC_MOVE is exercised, without claiming directory fsync or power-loss durability. */
internal class DesktopDocumentIndexTransactionPrototype(
    val root: Path, val storage: JsonFileStorage, val coordinator: DesktopDataOperationCoordinator,
    private val cut: suspend (IndexTxnCut) -> Unit = {},
) {
    val characters = CharacterRepository(storage)
    private val source = DesktopDocumentRagSource(root)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    val work: Path = root.resolve("entities/prototype_index_transactions")
    private val cardPath = root.resolve("entities/character_cards/card.json")
    private val configPath = root.resolve("entities/embedding_configs/embedding.json")
    private fun vectorPath(id: String) = root.resolve("entities/vector_chunks/$id.json")
    private class Snapshot(val card: CharacterCard, val config: EmbeddingConfig, val doc: DocumentInfo,
        val text: String, val hashes: Map<String, String>, val old: Map<String, String>, val other: Map<String, String>,
        val cardHash: String, val documentHash: String, val configHash: String)

    init { requireSyntheticRoot() }

    private fun requireSyntheticRoot() {
        require(root.fileName.toString().startsWith("p9-s2b-synthetic-"))
        require(Files.readString(root.resolve(".synthetic-only")) == "P9-S2B TEST ONLY\n")
        require(DesktopDataRootOwnership.isHeld(root))
        source.safeWindowsPath(root)
    }

    suspend fun publish(): IndexTxnReceipt {
        requireSyntheticRoot()
        check(businessOpen()) { "Unresolved prototype evidence" }
        val before = coordinator.withExclusiveMaintenance { snapshot() }
        val id = UUID.randomUUID().toString()
        source.checkPlanBudget(before.text, before.doc.fileName)
        val plan = ChunkingEngine().chunkDocument(before.text, before.doc.id, before.doc.fileName)
        check(plan.size in 1..32)
        val vectors = plan.mapIndexed { index, (text, meta) -> VectorChunk("txn_${id.replace("-", "")}_$index",
            ChunkSourceType.DOCUMENT, "card", content = text, embedding = listOf(.5f, .5f),
            metadata = meta + mapOf("fileName" to before.doc.fileName, "originalDocId" to "doc",
                "contentHash" to before.documentHash, "embeddingKey" to DesktopRagEmbeddingIdentity.from(before.config).key), createdAt = 2) }
        val card = before.card.copy(updatedAt = 2, customDocuments = before.card.customDocuments.map {
            if (it.id == "doc") it.copy(contentHash = before.documentHash, indexedHash = before.documentHash,
                ragStatus = "INDEXED", ragChunkCount = vectors.size, ragIndexedAt = 2, ragError = null) else it
        })
        val newBytes = vectors.associate { it.id to json.encodeToString(VectorChunk.serializer(), it).toByteArray() }
        check(newBytes.keys.none { it in before.hashes })
        val cardBytes = json.encodeToString(CharacterCard.serializer(), card).toByteArray()
        val intent = IndexTxnIntent(id, before.cardHash, digest(cardBytes), before.documentHash, before.configHash,
            before.old, newBytes.mapValues { digest(it.value) }, before.other)
        cut(IndexTxnCut.BEFORE_STAGE)
        val tx = work.resolve("pending/$id")
        coordinator.withNormalOperation {
            requireSyntheticRoot()
            for ((key, bytes) in newBytes) atomic(tx.resolve("stage/vectors/$key.json"), bytes)
            atomic(tx.resolve("stage/card.json"), cardBytes)
        }
        cut(IndexTxnCut.AFTER_STAGE)
        return coordinator.withExclusiveMaintenance {
            val latest = snapshot()
            check(latest.card == before.card && latest.doc == before.doc && latest.config == before.config &&
                latest.documentHash == before.documentHash && latest.hashes == before.hashes && latest.cardHash == before.cardHash && latest.configHash == before.configHash)
            for (old in before.old.keys) atomic(tx.resolve("backup/vectors/$old.json"), bytes(vectorPath(old)))
            atomic(tx.resolve("backup/card.json"), bytes(cardPath))
            cut(IndexTxnCut.BEFORE_JOURNAL)
            val raw = encodeIntent(intent)
            atomic(tx.resolve("intent.json"), raw)
            cut(IndexTxnCut.INTENT_WITHOUT_SEAL)
            atomic(tx.resolve("intent.sha256"), digest(raw).toByteArray())
            cut(IndexTxnCut.AFTER_JOURNAL)
            validateEvidence(tx, intent, committed = false)
            for ((index, old) in intent.old.keys.withIndex()) {
                val target = tx.resolve("moved/$old.json"); Files.createDirectories(target.parent)
                Files.move(vectorPath(old), target, StandardCopyOption.ATOMIC_MOVE)
                if (index == 0) cut(IndexTxnCut.FIRST_OLD_MOVED)
            }
            for ((index, next) in intent.next.keys.withIndex()) {
                atomic(vectorPath(next), bytes(tx.resolve("stage/vectors/$next.json")))
                if (index == 0) cut(IndexTxnCut.FIRST_NEW_INSTALLED)
            }
            cut(IndexTxnCut.ALL_NEW_INSTALLED); cut(IndexTxnCut.BEFORE_CARD)
            atomic(cardPath, bytes(tx.resolve("stage/card.json")))
            cut(IndexTxnCut.AFTER_CARD)
            verifyPayload(intent, next = true)
            cut(IndexTxnCut.BEFORE_MARKER)
            atomic(tx.resolve("commit.marker"), marker(intent, digest(raw)))
            cut(IndexTxnCut.AFTER_MARKER)
            finish(tx, intent, IndexTxnOutcome.COMMITTED)
        }
    }

    /** Fail-closed startup gate used ONLY by these tests/child, never patched into Main or Reader. */
    fun businessOpen(): Boolean = runCatching {
        if (!Files.exists(work)) return@runCatching true
        check(Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)); source.safeWindowsPath(work)
        Files.newDirectoryStream(work).use { dirs -> for (dir in dirs) check(dir.fileName.toString() in setOf("pending", "completed")) }
        check(children(work.resolve("pending")).isEmpty())
        children(work.resolve("completed")).forEach { completedReceipt(it) }
        true
    }.getOrDefault(false)

    suspend fun recover(): IndexTxnReceipt = coordinator.withExclusiveMaintenance {
        requireSyntheticRoot()
        try {
            val pending = children(work.resolve("pending"))
            if (pending.isEmpty()) {
                check(businessOpen())
                val archives = children(work.resolve("completed"))
                return@withExclusiveMaintenance IndexTxnReceipt(if (archives.isEmpty()) IndexTxnOutcome.NOT_COMMITTED
                    else completedReceipt(archives.single()), true)
            }
            val tx = pending.single()
            val intent = readIntent(tx)
            val committed = Files.exists(tx.resolve("commit.marker"), LinkOption.NOFOLLOW_LINKS)
            if (committed) check(bytes(tx.resolve("commit.marker")).contentEquals(marker(intent, hash(tx.resolve("intent.json")))))
            validateEvidence(tx, intent, committed)
            // Source/config and unrelated data must still match; recovery is not permission to
            // overwrite a later editor or an unknown file, even if an old backup is available.
            check(hash(root.resolve("documents/doc.txt")) == intent.sourceHash && hash(configPath) == intent.configHash)
            val current = vectorHashes()
            check(current.filterKeys { it !in intent.old && it !in intent.next } == intent.other)
            for ((key, value) in current) check(value == (intent.old[key] ?: intent.next[key] ?: intent.other[key]))
            check(hash(cardPath) in setOf(intent.oldCard, intent.newCard))
            if (!committed) {
                for ((index, key) in intent.next.keys.withIndex()) {
                    val path = vectorPath(key)
                    if (Files.exists(path)) { check(hash(path) == intent.next.getValue(key)); Files.delete(path) }
                    if (index == 0) cut(IndexTxnCut.RECOVERY_FIRST_NEW_REMOVED)
                }
                for ((index, key) in intent.old.keys.withIndex()) {
                    if (!Files.exists(vectorPath(key)) || hash(vectorPath(key)) != intent.old.getValue(key))
                        atomic(vectorPath(key), bytes(tx.resolve("backup/vectors/$key.json")))
                    if (index == 0) cut(IndexTxnCut.RECOVERY_FIRST_OLD_RESTORED)
                }
                if (hash(cardPath) != intent.oldCard) atomic(cardPath, bytes(tx.resolve("backup/card.json")))
                verifyPayload(intent, next = false)
            } else verifyPayload(intent, next = true)
            finish(tx, intent, if (committed) IndexTxnOutcome.COMMITTED else IndexTxnOutcome.NOT_COMMITTED)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { IndexTxnReceipt(IndexTxnOutcome.COMMIT_UNCERTAIN, false) }
    }

    private suspend fun finish(tx: Path, intent: IndexTxnIntent, outcome: IndexTxnOutcome): IndexTxnReceipt {
        if (outcome == IndexTxnOutcome.COMMITTED) {
            for ((index, key) in intent.old.keys.withIndex()) {
                for (path in listOf(tx.resolve("backup/vectors/$key.json"), tx.resolve("moved/$key.json"))) {
                    if (Files.exists(path)) { check(hash(path) == intent.old.getValue(key)); Files.delete(path) }
                }
                if (index == 0) cut(IndexTxnCut.DURING_CLEANUP)
            }
            cut(IndexTxnCut.AFTER_CLEANUP)
        }
        verifyPayload(intent, outcome == IndexTxnOutcome.COMMITTED)
        // Strict verification above, not loadAll, is the authority. Rebuild real cache views only
        // while the test startup gate is still closed. Production Flow/reader pausing is NOT wired.
        storage.loadAll("vector_chunks", VectorChunk.serializer())
        characters.refreshFromStorage()
        val receipt = buildJsonObject { put("id", intent.id); put("intentHash", hash(tx.resolve("intent.json"))); put("outcome", outcome.name) }
        atomic(tx.resolve("finish.json"), receipt.toString().toByteArray())
        val archive = work.resolve("completed/${intent.id}"); Files.createDirectories(archive.parent)
        Files.move(tx, archive, StandardCopyOption.ATOMIC_MOVE)
        return IndexTxnReceipt(outcome, businessOpen())
    }

    private suspend fun verifyPayload(intent: IndexTxnIntent, next: Boolean) {
        check(vectorHashes() == intent.other + if (next) intent.next else intent.old)
        check(hash(cardPath) == if (next) intent.newCard else intent.oldCard)
        // Also strict-read the card namespace before any cache rebuild.
        storage.visitEntitiesStrict("character_cards", CharacterCard.serializer(), validatePath = source::safeWindowsPath) { id, card -> source.requireIdentity(id, card.id) }
    }

    private fun validateEvidence(tx: Path, intent: IndexTxnIntent, committed: Boolean) {
        val files = mutableMapOf("intent.json" to hash(tx.resolve("intent.json")), "intent.sha256" to hash(tx.resolve("intent.sha256")),
            "stage/card.json" to intent.newCard, "backup/card.json" to intent.oldCard)
        for ((key, value) in intent.next) files["stage/vectors/$key.json"] = value
        for ((key, value) in intent.old) { files["backup/vectors/$key.json"] = value; files["moved/$key.json"] = value }
        if (Files.exists(tx.resolve("commit.marker"))) files["commit.marker"] = hash(tx.resolve("commit.marker"))
        if (Files.exists(tx.resolve("finish.json"))) files["finish.json"] = hash(tx.resolve("finish.json"))
        val directories = setOf("", "stage", "stage/vectors", "backup", "backup/vectors", "moved")
        Files.walk(tx).use { all -> for (path in all.toList()) {
            source.safeWindowsPath(path); check(!Files.isSymbolicLink(path))
            val relative = tx.relativize(path).toString().replace('\\', '/')
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) check(relative in directories)
            else check(hash(path) == files[relative])
        } }
        for (path in listOf("stage/card.json", "backup/card.json") + intent.next.keys.map { "stage/vectors/$it.json" })
            check(hash(tx.resolve(path)) == files.getValue(path))
        if (!committed) for (key in intent.old.keys) check(hash(tx.resolve("backup/vectors/$key.json")) == intent.old.getValue(key))
    }

    private suspend fun snapshot(): Snapshot {
        requireSyntheticRoot()
        var card: CharacterCard? = null; val cards = mutableMapOf<String, CharacterCard>()
        storage.visitEntitiesStrict("character_cards", CharacterCard.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id); check(cards.size < 8); cards[id] = value; if (id == "card") card = value
        }
        val actual = checkNotNull(card); val doc = actual.customDocuments.single { it.id == "doc" }
        var config: EmbeddingConfig? = null
        storage.visitEntitiesStrict("embedding_configs", EmbeddingConfig.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id); if (id == "embedding") config = value
        }
        val model = checkNotNull(config); check(model.dimensions == 2)
        val text = source.readDocument(doc.filePath); source.checkPlanBudget(text, doc.fileName)
        val hashes = linkedMapOf<String, String>(); val target = linkedMapOf<String, String>()
        storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id); check(hashes.size < 64 && source.validVector(value.embedding) && value.embedding.size == 2)
            if (value.sourceType == ChunkSourceType.DOCUMENT) {
                val owner = checkNotNull(cards[value.sourceId]); val info = owner.customDocuments.single { it.id == value.metadata["originalDocId"] }
                check(value.metadata.keys == setOf("documentId", "sourceLabel", "fileName", "originalDocId", "contentHash", "embeddingKey"))
                check(value.messageId == null && value.metadata["documentId"] == info.id &&
                    value.metadata["fileName"] == info.fileName && value.metadata["contentHash"] == info.indexedHash)
            } else check(value.sourceType == ChunkSourceType.CHAT_MEMORY && value.sourceId.isNotBlank() && !value.messageId.isNullOrBlank())
            hashes[id] = hash(vectorPath(id))
            if (value.sourceType == ChunkSourceType.DOCUMENT && value.sourceId == "card" && value.metadata["originalDocId"] == "doc") target[id] = hashes.getValue(id)
        }
        check(doc.ragStatus in setOf("INDEXED", "PENDING") && (doc.ragStatus != "INDEXED" || doc.ragChunkCount == target.size))
        return Snapshot(actual, model, doc, text, hashes, target, hashes.filterKeys { it !in target }, hash(cardPath),
            digest(text.toByteArray()), hash(configPath))
    }

    private suspend fun vectorHashes(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id); check(result.size < 64); result[id] = hash(vectorPath(id))
        }
        return result
    }

    private fun encodeIntent(i: IndexTxnIntent): ByteArray = buildJsonObject {
        put("protocol", 1); put("id", i.id); put("cardId", "card"); put("documentId", "doc"); put("ownedPath", "documents/doc.txt")
        put("oldCard", i.oldCard); put("newCard", i.newCard); put("sourceHash", i.sourceHash); put("configHash", i.configHash)
        put("old", jsonMap(i.old)); put("next", jsonMap(i.next)); put("other", jsonMap(i.other))
    }.toString().toByteArray()
    private fun readIntent(tx: Path): IndexTxnIntent {
        check(tx.fileName.toString().matches(Regex("[a-f0-9-]{36}")))
        val raw = bytes(tx.resolve("intent.json")); check(String(bytes(tx.resolve("intent.sha256"))) == digest(raw))
        val o = json.parseToJsonElement(String(raw)).jsonObject
        check(o.keys == setOf("protocol", "id", "cardId", "documentId", "ownedPath", "oldCard", "newCard", "sourceHash", "configHash", "old", "next", "other"))
        check(o.getValue("protocol").jsonPrimitive.int == 1 && o.string("id") == tx.fileName.toString())
        check(o.string("cardId") == "card" && o.string("documentId") == "doc" && o.string("ownedPath") == "documents/doc.txt")
        val old = parseMap(o.getValue("old").jsonObject); val next = parseMap(o.getValue("next").jsonObject); val other = parseMap(o.getValue("other").jsonObject)
        check(next.isNotEmpty() && (old.keys intersect next.keys).isEmpty() && ((old.keys + next.keys) intersect other.keys).isEmpty())
        fun h(key: String) = o.string(key).also { check(it.matches(HASH)) }
        return IndexTxnIntent(o.string("id"), h("oldCard"), h("newCard"), h("sourceHash"), h("configHash"), old, next, other)
    }
    private fun completedReceipt(tx: Path): IndexTxnOutcome {
        val intent = readIntent(tx); val finish = json.parseToJsonElement(String(bytes(tx.resolve("finish.json")))).jsonObject
        check(finish.keys == setOf("id", "intentHash", "outcome") && finish.string("id") == intent.id && finish.string("intentHash") == hash(tx.resolve("intent.json")))
        val outcome = IndexTxnOutcome.valueOf(finish.string("outcome")); check(outcome != IndexTxnOutcome.COMMIT_UNCERTAIN)
        if (outcome == IndexTxnOutcome.COMMITTED) check(bytes(tx.resolve("commit.marker")).contentEquals(marker(intent, hash(tx.resolve("intent.json")))))
        else check(!Files.exists(tx.resolve("commit.marker")))
        validateEvidence(tx, intent, outcome == IndexTxnOutcome.COMMITTED)
        return outcome
    }
    private fun marker(intent: IndexTxnIntent, hash: String) = buildJsonObject {
        put("id", intent.id); put("intentHash", hash); put("newCard", intent.newCard); put("newSet", digest(jsonMap(intent.next).toString().toByteArray()))
    }.toString().toByteArray()
    private fun parseMap(o: JsonObject): Map<String, String> {
        check(o.size <= 64)
        return o.mapValues { (key, value) -> source.requireIdentity(key, key); value.jsonPrimitive.content.also { check(it.matches(HASH)) } }
    }
    private fun jsonMap(map: Map<String, String>) = buildJsonObject { for ((key, value) in map.toSortedMap()) put(key, value) }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.also { check(it.isString) }.content
    private fun children(path: Path): List<Path> {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        source.safeWindowsPath(path); check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        return Files.newDirectoryStream(path).use { it.toList().also { paths -> check(paths.size <= 8) }.sortedBy { it.fileName.toString() } }
    }
    private fun bytes(path: Path): ByteArray {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)); source.safeWindowsPath(path)
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(512 * 1024 + 1).also { bytes -> check(bytes.size <= 512 * 1024) } }
    }
    private fun hash(path: Path) = digest(bytes(path))
    private fun atomic(path: Path, content: ByteArray) {
        requireSyntheticRoot(); check(path.normalize().startsWith(root) && content.size <= 512 * 1024)
        Files.createDirectories(path.parent)
        var ancestor = path.parent
        while (ancestor.startsWith(root)) { source.safeWindowsPath(ancestor); check(!Files.isSymbolicLink(ancestor)); ancestor = ancestor.parent }
        val temporary = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.tmp")
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        private val HASH = Regex("[a-f0-9]{64}")
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal object SyntheticIndexTxnData {
    suspend fun seed(root: Path) {
        check(root.fileName.toString().startsWith("p9-s2b-synthetic-"))
        Files.writeString(root.resolve(".synthetic-only"), "P9-S2B TEST ONLY\n")
        val storage = JsonFileStorage(root, DesktopDataOperationCoordinator())
        val text = "# One\nSynthetic first body.\n# Two\nSynthetic second body."
        Files.createDirectories(root.resolve("documents")); Files.writeString(root.resolve("documents/doc.txt"), text)
        val hash = DesktopDocumentIndexTransactionPrototype.digest(text.toByteArray())
        val doc = DocumentInfo("doc", "doc.txt", "documents/doc.txt", "txt", 1, hash, hash, "INDEXED", 2, 1)
        storage.saveEntity("character_cards", "card", CharacterCard.create("Synthetic").copy(id = "card", customDocuments = listOf(doc)), CharacterCard.serializer())
        storage.saveEntity("character_cards", "other", CharacterCard.create("Other synthetic").copy(id = "other", customDocuments = listOf(doc.copy(id = "otherDoc"))), CharacterCard.serializer())
        val config = EmbeddingConfig("embedding", "Synthetic", "https://invalid.example", "", "synthetic", 2)
        storage.saveEntity("embedding_configs", config.id, config, EmbeddingConfig.serializer())
        val chunks = ChunkingEngine().chunkDocument(text, "doc", "doc.txt")
        for ((index, piece) in chunks.withIndex()) {
            val vector = VectorChunk("old_$index", ChunkSourceType.DOCUMENT, "card", content = piece.first, embedding = listOf(1f, 0f),
                metadata = piece.second + mapOf("fileName" to "doc.txt", "originalDocId" to "doc", "contentHash" to hash,
                    "embeddingKey" to DesktopRagEmbeddingIdentity.from(config).key), createdAt = 1)
            storage.saveEntity("vector_chunks", vector.id, vector, VectorChunk.serializer())
            if (index == 0) {
                val other = vector.copy(id = "other", sourceId = "other", metadata = vector.metadata + mapOf("originalDocId" to "otherDoc", "documentId" to "otherDoc"))
                storage.saveEntity("vector_chunks", other.id, other, VectorChunk.serializer())
            }
        }
        val memory = VectorChunk("chat", ChunkSourceType.CHAT_MEMORY, "synthetic-session", messageId = "message", content = "Synthetic history", embedding = listOf(0f, 1f), createdAt = 1)
        storage.saveEntity("vector_chunks", memory.id, memory, VectorChunk.serializer())
        Files.createDirectories(root.resolve("images")); Files.write(root.resolve("images/history.bin"), byteArrayOf(1, 2, 3))
    }
    fun resolved(root: Path) = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("unused-bootstrap"))
    fun acquire(root: Path) = (DesktopDataRootOwnership.acquire(resolved(root)) as DesktopDataRootOwnershipResult.Acquired).ownership
    fun payloadHashes(root: Path): Map<String, String> = Files.walk(root).use { paths -> paths.filter {
        Files.isRegularFile(it) && it.fileName.toString() != ".ccb-desktop.lock" && !it.startsWith(root.resolve("entities/prototype_index_transactions"))
    }.toList().associate { root.relativize(it).toString().replace('\\', '/') to DesktopDocumentIndexTransactionPrototype.digest(Files.readAllBytes(it)) } }
    fun protectedHashes(root: Path) = payloadHashes(root).filterKeys { it != "entities/character_cards/card.json" && !it.startsWith("entities/vector_chunks/old_") && !it.contains("entities/vector_chunks/txn_") }
}
