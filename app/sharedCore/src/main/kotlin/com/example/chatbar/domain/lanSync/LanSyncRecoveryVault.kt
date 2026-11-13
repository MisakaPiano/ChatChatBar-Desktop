package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncStoreId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

@Serializable
data class LanSyncRecoveryEntry(
    val transaction: String,
    val sourceDevice: String,
    val createdAt: Long,
    val sourceBinding: String,
    val targetBinding: String,
    val snapshotHash: String,
    val snapshotBytes: Long,
    val originalsHash: String,
    val originalsBytes: Long,
    val affectedHash: String,
    val affectedBytes: Long,
    val affectedUnits: Long,
    val recordCount: Long,
    val messageCount: Long,
    val projectionHash: String? = null,
    val projectionBytes: Long = 0,
    val custodyDevice: String? = null,
    val hasLocalOriginals: Boolean = true,
    val originalCount: Long = recordCount,
    val publicationTransaction: String? = null,
    val rawSourceBinding: String? = null,
    val sourceCheckpointGroup: String? = null,
)

@Serializable
data class LanSyncRecoveryOriginal(val relative: String, val hash: String, val length: Long)

/** Independent, immutable recovery custody. Ordinary transaction retirement has no authority here. */
class LanSyncRecoveryVault(
    private val root: Path,
    private val budget: LanSyncResourceBudget = LanSyncResourceBudget(),
    private val observe: (String) -> Unit = {},
    objectStore: LanSyncResourceStore? = null,
) {
    private val objects = objectStore ?: LanSyncResourceStore(root.resolve("objects"), budget)
    private val entries = root.resolve("entries")
    init {
        LanSyncResourceStore.createCheckedRoot(root)
        lanSyncSafe(root, true)
        if (!Files.exists(entries, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(entries)
        lanSyncSafe(entries, true)
    }

    @Serializable private data class Preparation(val transaction: String, val source: String, val target: String,
        val projectionHash: String? = null, val custodyDevice: String? = null, val hasLocalOriginals: Boolean = true,
        val rawSourceBinding: String? = null, val sourceCheckpointGroup: String? = null)
    private fun directory(transaction: String): Path {
        require(transaction.matches(Regex("[a-f0-9-]{36}"))) { "SYNC_RECOVERY_ID" }
        return entries.resolve(transaction)
    }
    private fun binding(snapshot: LanSyncSnapshot) = lanSyncHash(JsonObject(snapshot.hashes().mapValues { JsonPrimitive(it.value) }))
    private fun rawBinding(files: Map<String, String>) = lanSyncHashSequence(files.toSortedMap().asSequence().map { (path, hash) ->
        buildJsonObject { put("path", path); put("hash", hash) }
    })
    private fun rawBinding(snapshot: LanSyncSnapshot) = lanSyncHashSequence(snapshot.records.sortedBy { it.key }.asSequence().map {
        buildJsonObject { put("type", it.type); put("id", it.id); put("body", it.body) }
    })

    @OptIn(ExperimentalSerializationApi::class)
    fun protect(dataRoot: Path, current: LanSyncCapture, plan: LanSyncPlan,
        projection: LanSyncProjection = LanSyncProjection()): LanSyncRecoveryEntry? = withEntry(plan.transaction) {
        projection.validate()
        protectOwned(dataRoot, current, plan, projection)
    }

    /** Prepublication source custody, including raw local choices; not a publication/approval receipt. */
    fun checkpointSource(dataRoot: Path, current: LanSyncCapture, group: SyncStoreId, revision: String,
        projection: LanSyncProjection = LanSyncProjection()): LanSyncRecoveryEntry? = withEntry(revision) {
        current.snapshot.validate(); LanSyncScope.validateGraph(current.snapshot); projection.validate()
        if (current.snapshot.records.isEmpty()) {
            require(!Files.exists(directory(revision), LinkOption.NOFOLLOW_LINKS)) { "SYNC_RECOVERY_PLAN_CHANGED" }
            return@withEntry null
        }
        protectOwned(dataRoot, current, LanSyncPlan(revision, current.snapshot, emptyMap()), projection, sourceCheckpointGroup = group.value)
    }

    fun protectPeer(ownerDevice: String, peer: LanSyncCapture, peerTarget: LanSyncPlan,
        projection: LanSyncProjection = LanSyncProjection()): LanSyncRecoveryEntry? {
        require(ownerDevice.isNotBlank() && peer.snapshot.deviceId != ownerDevice && peer.sourceFiles.isEmpty()) { "SYNC_RECOVERY_DEVICE" }
        peer.snapshot.validate(); LanSyncScope.validateGraph(peer.snapshot); projection.validate()
        peerTarget.snapshot.validate(); LanSyncScope.validateGraph(peerTarget.snapshot)
        require(peerTarget.snapshot.deviceId == peer.snapshot.deviceId) { "SYNC_RECOVERY_DEVICE" }
        val entryId = java.util.UUID.nameUUIDFromBytes("ccb-lan-peer-custody/$ownerDevice/${peer.snapshot.deviceId}/${peerTarget.transaction}"
            .toByteArray(Charsets.UTF_8)).toString()
        return withEntry(entryId) {
            protectOwned(null, peer, peerTarget.copy(transaction = entryId), projection,
                ownerDevice, false, peerTarget.transaction)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun protectOwned(dataRoot: Path?, current: LanSyncCapture, plan: LanSyncPlan, projection: LanSyncProjection,
        custodyDevice: String = current.snapshot.deviceId, hasLocalOriginals: Boolean = true,
        publicationTransaction: String? = null, sourceCheckpointGroup: String? = null): LanSyncRecoveryEntry? {
        val changes = LanSyncImpactReview.changes(current.snapshot.records, plan.snapshot.records)
        if (sourceCheckpointGroup == null && changes.none { it.needsProtection } || sourceCheckpointGroup != null && current.snapshot.records.isEmpty()) return null
        require(sourceCheckpointGroup == null || sourceCheckpointGroup.matches(Regex("[a-f0-9]{32}")) &&
            hasLocalOriginals && custodyDevice == current.snapshot.deviceId && plan.snapshot == current.snapshot) { "SYNC_RECOVERY_SOURCE_CHECKPOINT" }
        require(if (hasLocalOriginals) dataRoot != null && current.sourceFiles.size == current.snapshot.records.size
            else dataRoot == null && current.sourceFiles.isEmpty()) { "SYNC_RECOVERY_SOURCE" }
        val beforeUnits = current.snapshot.units()
        val affectedKeys = if (sourceCheckpointGroup != null) current.snapshot.records.map { it.key }.toSet()
            else changes.filter { it.needsProtection }.map { it.key }.toSet()
        val affected = beforeUnits.filterValues { rows -> rows.any { it.key in affectedKeys } }.keys.sorted()
        require(affected.isNotEmpty()) { "SYNC_RECOVERY_SOURCE" }
        val raw = if (hasLocalOriginals) rawBinding(current.sourceFiles) else rawBinding(current.snapshot)
        val preparation = Preparation(plan.transaction, binding(current.snapshot), binding(plan.snapshot), projection.digest(), custodyDevice, hasLocalOriginals, raw, sourceCheckpointGroup)
        fun variant(): LanSyncRecoveryEntry? {
            val publication = publicationTransaction ?: plan.transaction
            val domain = if (sourceCheckpointGroup == null) "ccb-lan-custody-raw" else "ccb-group-source-custody/$sourceCheckpointGroup"
            val id = java.util.UUID.nameUUIDFromBytes("$domain/$publication/$custodyDevice/${current.snapshot.deviceId}/$hasLocalOriginals/$raw"
                .toByteArray(Charsets.UTF_8)).toString()
            require(id != plan.transaction) { "SYNC_RECOVERY_PLAN_CHANGED" }
            return withEntry(id) { protectOwned(dataRoot, current, plan.copy(transaction = id), projection,
                custodyDevice, hasLocalOriginals, publication, sourceCheckpointGroup) }
        }
        val directory = directory(plan.transaction)
        read(plan.transaction)?.let { existing ->
            require(existing.sourceBinding == preparation.source && existing.targetBinding == preparation.target) { "SYNC_RECOVERY_PLAN_CHANGED" }
            require(existing.sourceCheckpointGroup == sourceCheckpointGroup) { "SYNC_RECOVERY_SOURCE_CHECKPOINT" }
            require((existing.custodyDevice ?: existing.sourceDevice) == custodyDevice && existing.hasLocalOriginals == hasLocalOriginals) { "SYNC_RECOVERY_DEVICE" }
            val originalProjection = sourceProjection(existing)
            require(originalProjection == projection || originalProjection == null && projection.bindings.isEmpty()) { "SYNC_RECOVERY_PROJECTION" }
            val previousRaw = existing.rawSourceBinding ?: if (hasLocalOriginals) {
                val originalHashes = linkedMapOf<String, String>()
                originals(existing) { original, _ -> originalHashes[original.relative] = original.hash }
                rawBinding(originalHashes)
            } else rawBinding(snapshot(existing))
            if (previousRaw != raw) return variant()
            verify(existing)
            return existing
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
        lanSyncSafe(directory, true)
        val preparationFile = directory.resolve("preparing.json")
        if (Files.exists(preparationFile, LinkOption.NOFOLLOW_LINKS)) {
            val before = lanSyncJson.decodeFromString(Preparation.serializer(), lanSyncUtf8(lanSyncRead(preparationFile, 4096)))
            if (before != preparation) {
                require(before.rawSourceBinding != raw && before.copy(rawSourceBinding = raw,
                    custodyDevice = before.custodyDevice ?: current.snapshot.deviceId) == preparation) { "SYNC_RECOVERY_PLAN_CHANGED" }
                return variant()
            }
        } else {
            require(Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }) { "SYNC_RECOVERY_UNKNOWN_FILES" }
            lanSyncAtomicWrite(preparationFile, lanSyncJson.encodeToString(Preparation.serializer(), preparation).toByteArray(Charsets.UTF_8))
        }
        // Reuse only completed immutable objects. Space accounts for all content still missing.
        val missing = current.snapshot.resources.filter { (hash, size) -> objects.completed(hash, size) == null }
        budget.requireSpace(root, budget.total(missing.values))
        for ((hash, size) in current.snapshot.resources) objects.retain(requireNotNull(current.blobs[hash]), hash, size)
        observe("RESOURCES_PROTECTED")

        fun freshFile(name: String, write: (Path) -> Unit): Path {
            val file = directory.resolve(name)
            // Files in an unsealed preparation are owned by that exact source/target binding.
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { lanSyncSafe(file); Files.delete(file) }
            write(file)
            java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
            return file
        }
        val originalsFile = freshFile("originals.jsonl") { file ->
            Files.newBufferedWriter(file, Charsets.UTF_8, StandardOpenOption.CREATE_NEW).use { output ->
                if (!hasLocalOriginals) output.write("[]\n")
                for ((relative, hash) in current.sourceFiles.toSortedMap()) {
                    val originalRoot = requireNotNull(dataRoot)
                    val source = originalRoot.resolve(relative).normalize()
                    require(source.startsWith(originalRoot)) { "SYNC_RECOVERY_SOURCE" }
                    validateOriginalPath(relative)
                    lanSyncSafe(source)
                    val length = Files.size(source)
                    require(LanSyncResourceStore.hashFile(source, length) == hash) { "SYNC_RECOVERY_SOURCE_CHANGED" }
                    objects.retain(source, hash, length)
                    output.write(lanSyncJson.encodeToString(LanSyncRecoveryOriginal.serializer(), LanSyncRecoveryOriginal(relative, hash, length)))
                    output.newLine()
                }
            }
        }
        val affectedFile = freshFile("affected.jsonl") { file ->
            Files.newBufferedWriter(file, Charsets.UTF_8, StandardOpenOption.CREATE_NEW).use { output ->
                for (unit in affected) { output.write(JsonPrimitive(unit).toString()); output.newLine() }
            }
        }
        val snapshotFile = freshFile("snapshot.json") { file ->
            Files.newOutputStream(file, StandardOpenOption.CREATE_NEW).buffered().use {
                lanSyncJson.encodeToStream(LanSyncSnapshot.serializer(), current.snapshot, it)
            }
        }
        val projectionFile = freshFile("projection.json") { file -> LanSyncJsonFiles.write(file, LanSyncProjection.serializer(), projection) }
        observe("METADATA_PROTECTED")
        val entry = LanSyncRecoveryEntry(plan.transaction, current.snapshot.deviceId, System.currentTimeMillis(),
            preparation.source, preparation.target, LanSyncResourceStore.hashFile(snapshotFile, Files.size(snapshotFile)), Files.size(snapshotFile),
            LanSyncResourceStore.hashFile(originalsFile, Files.size(originalsFile)), Files.size(originalsFile),
            LanSyncResourceStore.hashFile(affectedFile, Files.size(affectedFile)), Files.size(affectedFile), affected.size.toLong(),
            current.snapshot.records.size.toLong(), current.snapshot.records.count { it.type == "chat_messages" }.toLong(),
            LanSyncResourceStore.hashFile(projectionFile, Files.size(projectionFile)), Files.size(projectionFile),
            custodyDevice, hasLocalOriginals, current.sourceFiles.size.toLong(), publicationTransaction, raw, sourceCheckpointGroup)
        verifyFiles(entry)
        observe("BEFORE_SEAL")
        lanSyncAtomicWrite(directory.resolve("entry.json"), lanSyncJson.encodeToString(LanSyncRecoveryEntry.serializer(), entry).toByteArray(Charsets.UTF_8))
        return entry
    }

    fun read(transaction: String): LanSyncRecoveryEntry? {
        val file = directory(transaction).resolve("entry.json")
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        return lanSyncJson.decodeFromString(LanSyncRecoveryEntry.serializer(), lanSyncUtf8(lanSyncRead(file, 8192))).also {
            require(it.transaction == transaction) { "SYNC_RECOVERY_ID" }
        }
    }

    fun list(): List<LanSyncRecoveryEntry> = Files.newDirectoryStream(entries).use { directories ->
        directories.mapNotNull { path ->
            lanSyncSafe(path, true)
            read(path.fileName.toString())
        }.sortedByDescending { it.createdAt }
    }

    private fun verifyFiles(entry: LanSyncRecoveryEntry) {
        require(entry.sourceDevice.isNotBlank() && (entry.custodyDevice == null || entry.custodyDevice.isNotBlank()) &&
            entry.recordCount > 0 && entry.messageCount in 0..entry.recordCount && entry.affectedUnits > 0 &&
            (entry.rawSourceBinding == null || entry.rawSourceBinding.matches(Regex("[a-f0-9]{64}"))) &&
            (entry.sourceCheckpointGroup == null || entry.sourceCheckpointGroup.matches(Regex("[a-f0-9]{32}")) &&
                entry.hasLocalOriginals && (entry.custodyDevice ?: entry.sourceDevice) == entry.sourceDevice && entry.sourceBinding == entry.targetBinding) &&
            (entry.hasLocalOriginals || entry.custodyDevice != null && entry.custodyDevice != entry.sourceDevice)) { "SYNC_RECOVERY_DAMAGED" }
        val directory = directory(entry.transaction)
        for ((name, hash, length) in listOf(Triple("snapshot.json", entry.snapshotHash, entry.snapshotBytes),
            Triple("originals.jsonl", entry.originalsHash, entry.originalsBytes), Triple("affected.jsonl", entry.affectedHash, entry.affectedBytes)) +
            listOfNotNull(entry.projectionHash?.let { Triple("projection.json", it, entry.projectionBytes) })) {
            require(LanSyncResourceStore.hashFile(directory.resolve(name), length) == hash) { "SYNC_RECOVERY_DAMAGED" }
        }
        require(entry.projectionHash != null || entry.projectionBytes == 0L) { "SYNC_RECOVERY_DAMAGED" }
    }

    fun sourceProjection(entry: LanSyncRecoveryEntry): LanSyncProjection? {
        val hash = entry.projectionHash ?: return null
        require(entry.projectionBytes > 0) { "SYNC_RECOVERY_DAMAGED" }
        val file = directory(entry.transaction).resolve("projection.json")
        require(Files.size(file) == entry.projectionBytes) { "SYNC_RECOVERY_DAMAGED" }
        return requireNotNull(LanSyncJsonFiles.read(file, LanSyncProjection.serializer(), hash)).also { it.validate() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun snapshot(entry: LanSyncRecoveryEntry): LanSyncSnapshot {
        verifyFiles(entry)
        val stored = requireNotNull(LanSyncJsonFiles.read(directory(entry.transaction).resolve("snapshot.json"),
            LanSyncSnapshot.serializer(), entry.snapshotHash))
        val compatible = if (stored.protocol == LAN_SYNC_PROTOCOL) stored else {
            LanSyncScope.validateLegacyPending(stored)
            stored.copy(protocol = LAN_SYNC_PROTOCOL, records = stored.records.map { it.copy(body = LanSyncScope.normalize(it.type, it.body)) })
        }
        return compatible.also {
            it.validate(); LanSyncScope.validateGraph(it)
            require(it.deviceId == entry.sourceDevice && binding(it) == entry.sourceBinding &&
                it.records.size.toLong() == entry.recordCount &&
                it.records.count { row -> row.type == "chat_messages" }.toLong() == entry.messageCount) { "SYNC_RECOVERY_DAMAGED" }
        }
    }

    fun affected(entry: LanSyncRecoveryEntry): Set<String> {
        verifyFiles(entry)
        val result = linkedSetOf<String>()
        Files.newInputStream(directory(entry.transaction).resolve("affected.jsonl"), LinkOption.NOFOLLOW_LINKS).buffered().use { input ->
            while (true) {
                val line = java.io.ByteArrayOutputStream()
                var ended = false
                while (true) {
                    val value = input.read()
                    if (value < 0) { require(line.size() == 0) { "SYNC_RECOVERY_INDEX" }; ended = true; break }
                    if (value == 10) break
                    require(line.size() < 4096) { "SYNC_RECOVERY_INDEX" }; line.write(value)
                }
                if (ended) break
                require(line.size() > 0) { "SYNC_RECOVERY_INDEX" }
                val unit = lanSyncJson.parseToJsonElement(lanSyncUtf8(line.toByteArray())).jsonPrimitive.content
                require(unit.length in 1..600 && result.add(unit)) { "SYNC_RECOVERY_INDEX" }
            }
        }
        require(result.size.toLong() == entry.affectedUnits) { "SYNC_RECOVERY_INDEX" }
        return result
    }

    fun restoredLocalFields(entry: LanSyncRecoveryEntry, keys: Set<String>, projection: LanSyncProjection): Map<String, JsonObject> = buildMap {
        originals(entry) { original, file ->
            val type = original.relative.split('/')[1].removeSuffix(".json")
            val fields = if (type == "format_cards") emptySet() else if (type == LanSyncStudioCurrent.DRAFT) setOf("aiDesignModelId", "selectedModel", "followDefaultNovelAiImageModel")
                else LanSyncScope.retainedLocalFields(type)
            if (fields.isNotEmpty()) {
                val body = lanSyncJson.parseToJsonElement(lanSyncUtf8(lanSyncRead(file, budget.maximumRecordBytes))).jsonObject
                val record = LanSyncRecord(type, requireNotNull(LanSyncScope.identity(type, body)) { "SYNC_RECOVERY_SOURCE" }, body)
                LanSyncScope.validateRecord(record)
                val wireKey = projection.rewrite(listOf(record)).single().key
                if (wireKey in keys) put(wireKey, JsonObject(body.filterKeys(fields::contains)))
            }
        }
    }

    fun originals(entry: LanSyncRecoveryEntry, visit: (LanSyncRecoveryOriginal, Path) -> Unit) {
        verifyFiles(entry)
        if (!entry.hasLocalOriginals) {
            require(entry.originalCount == 0L && lanSyncUtf8(lanSyncRead(directory(entry.transaction).resolve("originals.jsonl"), 16)) == "[]\n") { "SYNC_RECOVERY_INDEX" }
            return
        }
        val seen = hashSetOf<String>()
        Files.newInputStream(directory(entry.transaction).resolve("originals.jsonl"), LinkOption.NOFOLLOW_LINKS).buffered().use { input ->
            while (true) {
                val bytes = java.io.ByteArrayOutputStream()
                var ended = false
                while (true) {
                    val value = input.read(); if (value < 0) break
                    if (value == 10) { ended = true; break }
                    require(bytes.size() < 4096) { "SYNC_RECOVERY_INDEX" }; bytes.write(value)
                }
                if (!ended) { require(bytes.size() == 0) { "SYNC_RECOVERY_INDEX" }; break }
                val line = lanSyncUtf8(bytes.toByteArray())
                val original = lanSyncJson.decodeFromString(LanSyncRecoveryOriginal.serializer(), line)
                validateOriginalPath(original.relative)
                require(seen.add(original.relative)) { "SYNC_RECOVERY_INDEX" }
                val path = requireNotNull(objects.completed(original.hash, original.length)) { "SYNC_RECOVERY_RESOURCE_MISSING" }
                visit(original, path)
            }
        }
        require(seen.size.toLong() == entry.originalCount && entry.originalCount == entry.recordCount) { "SYNC_RECOVERY_INDEX" }
    }

    private fun validateOriginalPath(relative: String) {
        val parts = relative.split('/')
        require(parts.size in 2..3 && parts[0] == "entities" &&
            parts.all { it.isNotEmpty() && it !in setOf(".", "..") && it.none { char -> char == '\\' || char == ':' || char.code < 32 } }) { "SYNC_RECOVERY_SOURCE" }
        val type = if (parts.size == 2) parts[1].removeSuffix(".json") else parts[1]
        require(type in LanSyncScope.schemas && if (parts.size == 2) LanSyncScope.isSingleton(type) && parts[1] == "$type.json"
            else !LanSyncScope.isSingleton(type) && parts[2].endsWith(".json")) { "SYNC_RECOVERY_SOURCE" }
    }

    fun verify(entry: LanSyncRecoveryEntry) {
        val snapshot = snapshot(entry)
        snapshot.resources.forEach { (hash, size) -> requireNotNull(objects.completed(hash, size)) { "SYNC_RECOVERY_RESOURCE_MISSING" } }
        originals(entry) { _, _ -> }
    }

    fun blobs(entry: LanSyncRecoveryEntry): Map<String, Path> = snapshot(entry).resources.mapValues { (hash, size) ->
        requireNotNull(objects.completed(hash, size)) { "SYNC_RECOVERY_RESOURCE_MISSING" }
    }

    private fun <T> withEntry(transaction: String, operation: () -> T): T {
        val key = directory(transaction).toAbsolutePath().normalize().toString()
        val lease = requireNotNull(locks.compute(key) { _, previous -> (previous ?: EntryLock()).also { it.users++ } })
        fun unregister() { locks.compute(key) { _, current ->
            check(current === lease); lease.users--; if (lease.users == 0) null else lease
        } }
        if (lease.lock.isHeldByCurrentThread) { unregister(); error("SYNC_RECOVERY_ALREADY_OPEN") }
        try { observe("ENTRY_WAITING") }
        catch (failure: Throwable) { unregister(); throw failure }
        lease.lock.lock()
        try { return operation() }
        finally { lease.lock.unlock(); unregister() }
    }

    companion object {
        private class EntryLock(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)
        private val locks = ConcurrentHashMap<String, EntryLock>()
    }
}
