package com.example.chatbar.domain.lanSync

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
)

@Serializable
data class LanSyncRecoveryOriginal(val relative: String, val hash: String, val length: Long)

/** Independent, immutable recovery custody. Ordinary transaction retirement has no authority here. */
class LanSyncRecoveryVault(
    private val root: Path,
    private val budget: LanSyncResourceBudget = LanSyncResourceBudget(),
    private val observe: (String) -> Unit = {},
) {
    private val objects = LanSyncResourceStore(root.resolve("objects"), budget)
    private val entries = root.resolve("entries")
    init {
        lanSyncSafe(root, true)
        if (!Files.exists(entries, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(entries)
        lanSyncSafe(entries, true)
    }

    @Serializable private data class Preparation(val transaction: String, val source: String, val target: String)
    private fun directory(transaction: String): Path {
        require(transaction.matches(Regex("[a-f0-9-]{36}"))) { "SYNC_RECOVERY_ID" }
        return entries.resolve(transaction)
    }
    private fun binding(snapshot: LanSyncSnapshot) = lanSyncHash(JsonObject(snapshot.hashes().mapValues { JsonPrimitive(it.value) }))

    @OptIn(ExperimentalSerializationApi::class)
    fun protect(dataRoot: Path, current: LanSyncCapture, plan: LanSyncPlan): LanSyncRecoveryEntry? = withEntry(plan.transaction) {
        protectOwned(dataRoot, current, plan)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun protectOwned(dataRoot: Path, current: LanSyncCapture, plan: LanSyncPlan): LanSyncRecoveryEntry? {
        val changes = LanSyncImpactReview.changes(current.snapshot.records, plan.snapshot.records)
        if (changes.none { it.needsProtection }) return null
        require(current.sourceFiles.size == current.snapshot.records.size) { "SYNC_RECOVERY_SOURCE" }
        val beforeUnits = current.snapshot.units()
        val affectedKeys = changes.filter { it.needsProtection }.map { it.key }.toSet()
        val affected = beforeUnits.filterValues { rows -> rows.any { it.key in affectedKeys } }.keys.sorted()
        require(affected.isNotEmpty()) { "SYNC_RECOVERY_SOURCE" }
        val preparation = Preparation(plan.transaction, binding(current.snapshot), binding(plan.snapshot))
        val directory = directory(plan.transaction)
        read(plan.transaction)?.let { existing ->
            require(existing.sourceBinding == preparation.source && existing.targetBinding == preparation.target) { "SYNC_RECOVERY_PLAN_CHANGED" }
            verify(existing)
            return existing
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
        lanSyncSafe(directory, true)
        val preparationFile = directory.resolve("preparing.json")
        if (Files.exists(preparationFile, LinkOption.NOFOLLOW_LINKS)) {
            require(lanSyncJson.decodeFromString(Preparation.serializer(), lanSyncUtf8(lanSyncRead(preparationFile, 4096))) == preparation) { "SYNC_RECOVERY_PLAN_CHANGED" }
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
                for ((relative, hash) in current.sourceFiles.toSortedMap()) {
                    val source = dataRoot.resolve(relative).normalize()
                    require(source.startsWith(dataRoot)) { "SYNC_RECOVERY_SOURCE" }
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
        observe("METADATA_PROTECTED")
        val entry = LanSyncRecoveryEntry(plan.transaction, current.snapshot.deviceId, System.currentTimeMillis(),
            preparation.source, preparation.target, LanSyncResourceStore.hashFile(snapshotFile, Files.size(snapshotFile)), Files.size(snapshotFile),
            LanSyncResourceStore.hashFile(originalsFile, Files.size(originalsFile)), Files.size(originalsFile),
            LanSyncResourceStore.hashFile(affectedFile, Files.size(affectedFile)), Files.size(affectedFile), affected.size.toLong(),
            current.snapshot.records.size.toLong(), current.snapshot.records.count { it.type == "chat_messages" }.toLong())
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
        val directory = directory(entry.transaction)
        for ((name, hash, length) in listOf(Triple("snapshot.json", entry.snapshotHash, entry.snapshotBytes),
            Triple("originals.jsonl", entry.originalsHash, entry.originalsBytes), Triple("affected.jsonl", entry.affectedHash, entry.affectedBytes))) {
            require(LanSyncResourceStore.hashFile(directory.resolve(name), length) == hash) { "SYNC_RECOVERY_DAMAGED" }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun snapshot(entry: LanSyncRecoveryEntry): LanSyncSnapshot {
        verifyFiles(entry)
        return Files.newInputStream(directory(entry.transaction).resolve("snapshot.json"), LinkOption.NOFOLLOW_LINKS).buffered().use {
            lanSyncJson.decodeFromStream(LanSyncSnapshot.serializer(), it)
        }.also { it.validate(); LanSyncScope.validateGraph(it); require(binding(it) == entry.sourceBinding) { "SYNC_RECOVERY_DAMAGED" } }
    }

    fun originals(entry: LanSyncRecoveryEntry, visit: (LanSyncRecoveryOriginal, Path) -> Unit) {
        verifyFiles(entry)
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
        require(seen.size.toLong() == entry.recordCount) { "SYNC_RECOVERY_INDEX" }
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
