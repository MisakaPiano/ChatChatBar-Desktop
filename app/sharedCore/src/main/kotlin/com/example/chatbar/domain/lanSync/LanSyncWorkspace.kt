package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID

data class LanSyncCapture(val snapshot: LanSyncSnapshot, val blobs: Map<String, Path>,
    val sourceFiles: Map<String, String>, private val release: (() -> Unit)? = null) : java.io.Closeable {
    override fun close() { release?.invoke() }
}

/** Runs only inside the platform's exclusive data admission, after its writers have drained. */
class LanSyncWorkspace(val dataRoot: Path, private val stateRoot: Path,
    private val resolveResource: (String) -> Path,
    private val assetResource: (String) -> ByteArray? = { null },
    private val audioResource: (String, String, String, Path) -> String = { _, _, _, file -> file.toString() },
    private val observeJournal: (String, Int) -> Unit = { _, _ -> },
    private val validateResource: (String, Path) -> Unit = { _, _ -> },
    private val resourceReference: (String, Path) -> String = { _, path -> path.toString() },
) {
    init { Files.createDirectories(stateRoot); lanSyncSafe(stateRoot, true) }

    fun capture(deviceId: String): LanSyncCapture {
        lanSyncSafe(dataRoot, true)
        val scratch = LanSyncScratch(stateRoot, "capture"); val frozen = scratch.directory
        var returned = false
        try {
        val blobs = linkedMapOf<String, Path>(); val resources = linkedMapOf<String, Long>()
        val records = mutableListOf<LanSyncRecord>(); val sourceFiles = linkedMapOf<String, String>()
        val checkedResources = hashSetOf<Pair<String, String>>()
        val excluded = linkedSetOf<String>()
        val entities = dataRoot.resolve("entities")
        if (Files.exists(entities, LinkOption.NOFOLLOW_LINKS)) {
            lanSyncSafe(entities, true)
            Files.newDirectoryStream(entities).use { entries -> entries.forEach { directory ->
                val type = directory.fileName.toString()
                if (type in setOf("memory_commit_journals", "pending_deletions") && Files.isDirectory(directory)) {
                    require(Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }) { "SYNC_PENDING_REPOSITORY_WORK" }
                }
                if (type !in LanSyncScope.schemas || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    excluded += type.removeSuffix(".json"); return@forEach
                }
                lanSyncSafe(directory, true)
                Files.newDirectoryStream(directory).use { files -> files.sortedBy { it.fileName.toString() }.forEach { file ->
                    require(file.fileName.toString().endsWith(".json")) { "SYNC_UNFINISHED_ENTITY" }
                    val raw = lanSyncRead(file, MAX_RECORD)
                    val body = lanSyncJson.parseToJsonElement(lanSyncUtf8(raw)).jsonObject
                    val id = requireNotNull(LanSyncScope.identity(type, body)) { "SYNC_ENTITY_ID" }
                    val original = LanSyncRecord(type, id, body); LanSyncScope.validateRecord(original)
                    require(file.fileName.toString() == "${LanSyncScope.storageId(original)}.json") { "SYNC_STORAGE_ID" }
                    val normalized = LanSyncScope.normalize(type, mapResources(body) { reference, field ->
                        if (reference.isBlank() || reference.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) ||
                            type == "save_slots" && (reference.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_IMAGE_PREFIX) ||
                                reference.startsWith(com.example.chatbar.domain.chat.SAVE_SLOT_AUDIO_PREFIX))) reference else {
                            val kind = if (field == "packageFile") "packages" else if (field == "audioPath") "audio" else if (field in setOf("filePath", "filePaths", "path")) "documents" else "images"
                            val resource = if (reference.startsWith("asset:")) {
                                val bytes = requireNotNull(assetResource(reference)) { "SYNC_ASSET_UNAVAILABLE" }
                                frozen.resolve("asset-${UUID.randomUUID()}").also { lanSyncAtomicWrite(it, bytes); scratch.own(it) }
                            } else (if (field == "packageFile") dataRoot.resolve("save_slot_packages").resolve(reference) else resolveResource(reference)).also { source ->
                                require(source.toAbsolutePath().normalize().startsWith(dataRoot)) { "SYNC_EXTERNAL_RESOURCE" }
                            }
                            if (field == "packageFile") LanSyncSlotPackages.validate(resource, body)
                            val hash = freeze(resource, frozen, blobs, resources, scratch)
                            if (checkedResources.add(kind to hash)) validateResource(kind, requireNotNull(blobs[hash]))
                            val suffix = reference.substringAfterLast('.', "bin").lowercase().takeIf {
                                it.matches(Regex("[a-z0-9]{1,8}"))
                            } ?: "bin"
                            val retainedId = resource.fileName.toString().let { name ->
                                Regex("lan-([a-f0-9-]{36})-[a-f0-9]{64}\\.[a-z0-9]{1,8}").matchEntire(name)?.groupValues?.get(1)
                            }
                            val mediaId = retainedId ?: UUID.nameUUIDFromBytes((if (kind == "packages") "package/${original.id}"
                                else if (reference.startsWith("asset:")) reference else dataRoot.relativize(resource).toString().replace('\\', '/'))
                                .toByteArray(Charsets.UTF_8)).toString()
                            "ccb-lan-v1/$kind/$mediaId/$hash/$suffix"
                        }
                    })
                    val record = original.copy(body = normalized)
                    sourceFiles[dataRoot.relativize(file).toString().replace('\\', '/')] = lanSyncHash(raw)
                    records += record
                    require(records.size <= 100_000) { "SYNC_RECORD_LIMIT" }
                } }
            } }
        }
        val snapshot = LanSyncSnapshot(deviceId = deviceId, records = records, resources = resources, excludedTypes = excluded)
        snapshot.validate(); LanSyncScope.validateGraph(snapshot)
        require(lanSyncJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8).size <= LanSyncWire.MAX_MESSAGE) { "SYNC_SNAPSHOT_LIMIT" }
        returned = true
        return LanSyncCapture(snapshot, blobs, sourceFiles, scratch::close)
        } finally { if (!returned) scratch.close() }
    }

    private fun freeze(source: Path, frozen: Path, blobs: MutableMap<String, Path>, resources: MutableMap<String, Long>, scratch: LanSyncScratch): String {
        lanSyncSafe(source); val before = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes::class.java)
        require(before.size() in 1..LanSyncSnapshot.MAX_RESOURCE) { "SYNC_RESOURCE_LIMIT" }
        val part = frozen.resolve("blob-${UUID.randomUUID()}")
        val hash = copyHash(source, part, before.size(), scratch::own)
        val after = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes::class.java)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) { "SYNC_SOURCE_CHANGED" }
        if (hash !in blobs) { blobs[hash] = part; resources[hash] = before.size() }
        require(resources.values.sum() <= MAX_TOTAL) { "SYNC_TOTAL_LIMIT" }
        return hash
    }

    /** Prepares a durable local journal before changing any entity. Media are immutable additions. */
    fun apply(plan: LanSyncPlan, expected: Map<String, String>, deviceId: String, blobs: Map<String, Path>) {
        plan.snapshot.validate(); LanSyncScope.validateGraph(plan.snapshot)
        val current = capture(deviceId)
        try {
        require(current.snapshot.hashes() == expected) { "SYNC_LOCAL_CHANGED_REVIEW_AGAIN" }
        val transaction = stateRoot.resolve("transaction-${plan.transaction.also { require(it.matches(Regex("[a-f0-9-]{36}"))) }}")
        require(!Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) { "SYNC_TRANSACTION_ALREADY_PREPARED" }
        Files.createDirectory(transaction)
        val changes = linkedMapOf<String, ByteArray?>()
        val checkedResources = hashSetOf<Pair<String, String>>()
        val desired = plan.snapshot.records.associateBy { "entities/${it.type}/${LanSyncScope.storageId(it)}.json" }
        val currentRecords = current.snapshot.records.associateBy { it.key }
        desired.forEach { (relative, wire) ->
            val existing = currentRecords[wire.key]
            if (existing?.comparisonBody() == wire.comparisonBody()) return@forEach
            val materialized = mapResources(wire.body) { reference, field ->
                if (!reference.startsWith("ccb-lan-v1/")) reference else {
                    val parts = reference.split('/')
                    require(parts.size == 5 && parts[1] in setOf("images", "audio", "documents", "packages") && parts[2].matches(Regex("[a-f0-9-]{36}")) &&
                        parts[3].matches(Regex("[a-f0-9]{64}")) && parts[4].matches(Regex("[a-z0-9]{1,8}"))) { "SYNC_RESOURCE_REFERENCE" }
                    val source = requireNotNull(blobs[parts[3]]) { "SYNC_RESOURCE_MISSING" }
                    val size = requireNotNull(plan.snapshot.resources[parts[3]]) { "SYNC_RESOURCE_MANIFEST" }
                    require(hashFile(source, size) == parts[3]) { "SYNC_RESOURCE_DAMAGED" }
                    if (checkedResources.add(parts[1] to parts[3])) validateResource(parts[1], source)
                    if (parts[1] == "audio") {
                        audioResource(requireNotNull(wire.text("sessionId")), wire.id, parts[3], source)
                    } else {
                        val packageSource = if (parts[1] == "packages") {
                            val ref = requireNotNull(wire.body["packageRef"]).jsonObject
                            // Validate against the retained source envelope before choosing a new library filename.
                            val nested = java.util.zip.ZipFile(source.toFile()).use { zip ->
                                val entry = requireNotNull(zip.getEntry("manifest.json")); require(entry.size in 1..256 * 1024)
                                val raw = zip.getInputStream(entry).use { it.readBytes() }
                                lanSyncJson.parseToJsonElement(lanSyncUtf8(raw)).jsonObject
                            }
                            val sourceId = nested.getValue("id").jsonPrimitive.content
                            val filename = if (wire.id != sourceId) "${wire.id}.cbsave" else "lan-${parts[2]}-${parts[3]}.cbsave"
                            val library = JsonObject(wire.body + ("packageRef" to JsonObject(ref + ("fileName" to JsonPrimitive(filename)))))
                            LanSyncSlotPackages.validate(source, library)
                            filename
                        } else null
                        val target = if (packageSource != null) dataRoot.resolve("save_slot_packages").resolve(packageSource)
                            else dataRoot.resolve(if (field == "coverImage") "images/character-covers" else parts[1]).resolve("lan-${parts[2]}-${parts[3]}.${parts[4]}")
                        Files.createDirectories(target.parent); lanSyncSafe(target.parent, true)
                        LanSyncBlobs.publish(source, target, size, parts[3], stateRoot)
                        if (packageSource != null) target.fileName.toString() else resourceReference(parts[1], target)
                    }
                }
            }
            val localBody = existing?.takeIf { wire.type == "chat_sessions" }?.let {
                val original = lanSyncJson.parseToJsonElement(lanSyncUtf8(lanSyncRead(dataRoot.resolve(relative), MAX_RECORD))).jsonObject
                JsonObject(materialized + original.filterKeys { it in LanSyncScope.localFields })
            } ?: materialized
            LanSyncScope.validateRecord(wire.copy(body = localBody))
            changes[relative] = lanSyncJson.encodeToString(JsonObject.serializer(), localBody).toByteArray(Charsets.UTF_8)
        }
        current.sourceFiles.keys.filter { it !in desired }.forEach { changes[it] = null }
        // Derived indexes belong to the local repository. Invalidating them preserves message order keys.
        val changedSessions = (current.snapshot.units().keys + plan.snapshot.units().keys).filter {
            it.startsWith("conversation/") && current.snapshot.hashes()[it] != plan.snapshot.hashes()[it]
        }.map { it.removePrefix("conversation/") }
        changedSessions.forEach { id -> listOf("chat_message_indexes", "chat_message_order_backups").forEach { type ->
            val relative = "entities/$type/$id.json"
            if (Files.exists(target(relative), LinkOption.NOFOLLOW_LINKS)) changes[relative] = null
        } }
        val entries = changes.entries.mapIndexed { index, (relative, bytes) ->
            val path = target(relative)
            val old = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) lanSyncRead(path, MAX_RECORD) else null
            old?.let { lanSyncAtomicWrite(transaction.resolve("$index.old"), it) }
            bytes?.let { lanSyncAtomicWrite(transaction.resolve("$index.new"), it) }
            LanSyncJournalEntry(relative, old?.let(::lanSyncHash), bytes?.let(::lanSyncHash), index)
        }
        val journal = LanSyncJournal(plan.transaction, entries)
        lanSyncAtomicWrite(transaction.resolve("journal.json"), lanSyncJson.encodeToString(journal).toByteArray(Charsets.UTF_8))
        // This marker is the only recovery authority; earlier preparation is inert evidence.
        lanSyncAtomicWrite(transaction.resolve("authorized"), lanSyncHash(lanSyncRead(transaction.resolve("journal.json"), LanSyncWire.MAX_MESSAGE)).toByteArray())
        observeJournal("AUTHORIZED", -1)
        recoverTransaction(transaction)
        } finally { current.close() }
    }

    fun recover() {
        Files.newDirectoryStream(stateRoot, "transaction-*").use { dirs -> dirs.sorted().forEach { transaction ->
            lanSyncSafe(transaction, true)
            if (Files.exists(transaction.resolve("authorized"), LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(transaction.resolve("applied"), LinkOption.NOFOLLOW_LINKS)) recoverTransaction(transaction)
        } }
    }

    fun applied(transaction: String): Boolean = Files.exists(stateRoot.resolve("transaction-$transaction/applied"), LinkOption.NOFOLLOW_LINKS)

    /** Called only after a newer bilateral completion. Preserve the receipt; release old payloads. */
    fun retire(transactionId: String) {
        require(transactionId.matches(Regex("[a-f0-9-]{36}")))
        val transaction = stateRoot.resolve("transaction-$transactionId")
        if (!Files.exists(transaction.resolve("applied")) || Files.exists(transaction.resolve("retired"))) return
        val journal = lanSyncJson.decodeFromString<LanSyncJournal>(lanSyncUtf8(lanSyncRead(transaction.resolve("journal.json"), LanSyncWire.MAX_MESSAGE)))
        require(journal.transaction == transactionId)
        val payloads = journal.entries.flatMap { entry -> listOfNotNull(
            entry.oldHash?.let { transaction.resolve("${entry.index}.old") to it },
            entry.newHash?.let { transaction.resolve("${entry.index}.new") to it }) }
        payloads.forEach { (path, hash) -> require(lanSyncHash(lanSyncRead(path, MAX_RECORD)) == hash) { "SYNC_JOURNAL_DAMAGED" } }
        lanSyncAtomicWrite(transaction.resolve("retired"), transactionId.toByteArray())
        payloads.forEach { (path, _) -> Files.delete(path) }
    }

    private fun recoverTransaction(transaction: Path) {
        val bytes = lanSyncRead(transaction.resolve("journal.json"), LanSyncWire.MAX_MESSAGE)
        require(lanSyncUtf8(lanSyncRead(transaction.resolve("authorized"), 128)) == lanSyncHash(bytes)) { "SYNC_JOURNAL_DAMAGED" }
        val journal = lanSyncJson.decodeFromString<LanSyncJournal>(lanSyncUtf8(bytes))
        require(transaction.fileName.toString() == "transaction-${journal.transaction}" && journal.entries.map { it.path }.toSet().size == journal.entries.size)
        // Validate the entire recovery authority before the first entity changes.
        journal.entries.forEach { entry ->
            require(entry.index in journal.entries.indices)
            val path = target(entry.path)
            entry.oldHash?.let { require(lanSyncHash(lanSyncRead(transaction.resolve("${entry.index}.old"), MAX_RECORD)) == it) { "SYNC_JOURNAL_DAMAGED" } }
            entry.newHash?.let { require(lanSyncHash(lanSyncRead(transaction.resolve("${entry.index}.new"), MAX_RECORD)) == it) { "SYNC_JOURNAL_DAMAGED" } }
            val current = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) lanSyncHash(lanSyncRead(path, MAX_RECORD)) else null
            require(current == entry.oldHash || current == entry.newHash) { "SYNC_FOREIGN_CHANGE_RECOVERY_REFUSED" }
        }
        journal.entries.forEach { entry ->
            val path = target(entry.path)
            val old = entry.oldHash?.let { lanSyncRead(transaction.resolve("${entry.index}.old"), MAX_RECORD).also { raw -> require(lanSyncHash(raw) == it) } }
            val next = entry.newHash?.let { lanSyncRead(transaction.resolve("${entry.index}.new"), MAX_RECORD).also { raw -> require(lanSyncHash(raw) == it) } }
            val current = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) lanSyncHash(lanSyncRead(path, MAX_RECORD)) else null
            require(current == entry.oldHash || current == entry.newHash) { "SYNC_FOREIGN_CHANGE_RECOVERY_REFUSED" }
            if (current != entry.newHash) {
                if (next == null) Files.delete(path) else lanSyncAtomicWrite(path, next)
            }
            observeJournal("ENTRY", entry.index)
        }
        lanSyncAtomicWrite(transaction.resolve("applied"), journal.transaction.toByteArray(Charsets.UTF_8))
    }

    private fun target(relative: String): Path {
        val parts = relative.split('/')
        require(parts.size == 3 && parts[0] == "entities" && parts[1] in (LanSyncScope.schemas.keys + setOf("chat_message_indexes", "chat_message_order_backups")) &&
            parts[2].endsWith(".json") && parts[2].none { it == '\\' || it == ':' || it.code < 32 } && parts.none { it == "." || it == ".." }) { "SYNC_TARGET_PATH" }
        val path = dataRoot.resolve(relative).normalize(); require(path.startsWith(dataRoot))
        return path
    }

    companion object {
        const val MAX_RECORD = 8 * 1024 * 1024
        const val MAX_TOTAL = 1024L * 1024 * 1024
        /** Mirrors the existing backup registry's contextual path recognition. */
        fun mapResources(body: JsonObject, transform: (String, String) -> String): JsonObject {
            fun visit(value: JsonElement, fields: List<String>): JsonElement {
                require(fields.size <= 64) { "SYNC_JSON_DEPTH" }
                if (fields.any { it in setOf("extensions", "metadata", "customParams") }) return value
                return when (value) {
                    is JsonObject -> JsonObject(value.toSortedMap().mapValues { (key, child) -> visit(child, fields + key) })
                    is JsonArray -> JsonArray(value.map { visit(it, fields) })
                    is JsonPrimitive -> {
                        val field = fields.lastOrNull()
                        val packageFile = fields == listOf("packageRef", "fileName")
                        val serviceArtwork = field == "coverImage" && fields.dropLast(1).lastOrNull() in setOf("fishAudioVoice", "voice")
                        if (value.isString && (field in LanSyncScope.resources || packageFile) && !serviceArtwork)
                            JsonPrimitive(transform(value.content, if (packageFile) "packageFile" else field!!)) else value
                    }
                    else -> value
                }
            }
            return visit(body, emptyList()).jsonObject
        }
        fun hashFile(path: Path, length: Long): String {
            lanSyncSafe(path); require(Files.size(path) == length && length in 1..LanSyncSnapshot.MAX_RESOURCE)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024); var count = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= length); digest.update(buffer, 0, n) }
                require(count == length)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun copyHash(source: Path, target: Path, length: Long, onCreated: (Path) -> Unit = {}): String {
            lanSyncSafe(source); require(Files.size(source) == length)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
                java.nio.channels.FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    onCreated(target)
                    val buffer = ByteArray(64 * 1024); var count = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= length); digest.update(buffer, 0, n)
                        val block = java.nio.ByteBuffer.wrap(buffer, 0, n); while (block.hasRemaining()) channel.write(block) }
                    require(count == length); channel.force(true)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

@Serializable internal data class LanSyncJournal(val transaction: String, val entries: List<LanSyncJournalEntry>)
@Serializable internal data class LanSyncJournalEntry(val path: String, val oldHash: String?, val newHash: String?, val index: Int)
