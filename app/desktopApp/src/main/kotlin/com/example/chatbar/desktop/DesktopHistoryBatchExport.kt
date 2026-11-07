package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import com.sun.nio.file.ExtendedOpenOption
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString

/** Original-byte export. No history/draft writes, automatic overwrites or source deletion. */
internal class DesktopHistoryBatchExport(
    private val storage: JsonFileStorage,
    private val resources: DesktopCharacterResourceStore,
    private val gate: DesktopDataOperationCoordinator,
    private val directoryPicker: DesktopDirectoryPicker,
    private val afterPublished: suspend (Int) -> Unit = {},
) {
    enum class Decision { OVERWRITE, SKIP }
    internal data class Proof(val path: Path, val key: Any, val size: Long, val hash: String, val modified: Long)
    internal data class Source(val selected: NovelAiHistoryImageSelection, val entryHash: String, val file: Proof, val createdAt: Long)
    internal data class Item(val source: Source, val name: String, val existing: List<Proof>) {
        val key get() = source.selected
    }
    internal class Plan internal constructor(val directory: Path, internal val directoryKey: Any, val items: List<Item>) {
        val conflicts get() = items.filter { it.existing.isNotEmpty() }
    }
    data class Result(val saved: Int, val skipped: Int, val remainingDuplicates: Int, val recoveryDirectory: Path? = null)
    class Failure(val recoveryDirectory: Path, cause: Throwable) : IllegalStateException("HISTORY_EXPORT_ROLLBACK_UNCONFIRMED", cause)
    private class Changed : IllegalStateException("HISTORY_EXPORT_CHANGED")
    private data class Written(val path: Path, val key: Any, val staged: Proof)
    private data class Displaced(val destination: Path, val file: Path, var proof: Proof? = null)

    private data class WindowsIdentity(val volume: Long, val id: String, val created: Long)
    private fun DesktopDocumentIndexReadEvidence.Stamp.identity() = WindowsIdentity(volume, id, created)
    private val windows get() = System.getProperty("os.name").startsWith("Windows")
    private suspend fun identity(path: Path, directory: Boolean): Any {
        DesktopArchiveFiles.safe(path, directory)
        if (windows) return DesktopDocumentIndexReadEvidence({}, extendedPaths = true).observe(path, directory).identity()
        return requireNotNull(Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey())
    }
    private suspend fun directoryKey(path: Path): Any = identity(path, true)
    private suspend fun proof(path: Path): Proof {
        DesktopArchiveFiles.safe(path, false)
        if (windows) {
            val evidence = DesktopDocumentIndexReadEvidence({}, extendedPaths = true)
            val stamp = evidence.observe(path, false)
            val read = evidence.read(path, stamp) { _, _ -> }
            return Proof(path, stamp.identity(), stamp.size, read.file.sha256, stamp.modified)
        }
        val stamp = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val digest = DesktopArchiveFiles.hash(path, stamp.size())
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(stamp.fileKey() == after.fileKey() && stamp.size() == after.size() && stamp.lastModifiedTime() == after.lastModifiedTime())
        return Proof(path, requireNotNull(stamp.fileKey()), stamp.size(), digest, stamp.lastModifiedTime().toMillis())
    }
    private suspend fun same(expected: Proof) {
        if (proof(expected.path) != expected) throw Changed()
    }
    private suspend fun entry(id: String): NovelAiGenerationHistoryEntry = when (val read = storage.readEntityStrict(
        "novelai_generation_history", id, NovelAiGenerationHistoryEntry.serializer())) {
        is JsonFileStorage.EntityReadResult.Valid -> read.value.also { check(it.id == id) }
        else -> throw Changed()
    }
    private fun entryHash(value: NovelAiGenerationHistoryEntry): String = digest(storage.json.encodeToString(value).toByteArray())
    private suspend fun sources(selected: List<NovelAiHistoryImageSelection>): List<Source> {
        require(selected.isNotEmpty() && selected.toSet().size == selected.size) { "HISTORY_EXPORT_SELECTION" }
        val entries = selected.map { it.entryId }.distinct().associateWith { entry(it) }
        // Check the entire selection before source IO, chooser or any export publication.
        selected.forEach { requested ->
            val image = entries.getValue(requested.entryId).images.singleOrNull { it.path == requested.imagePath } ?: throw Changed()
            require(!image.originalOmitted) { "HISTORY_EXPORT_ORIGINAL_OMITTED" }
        }
        return selected.map { requested ->
            currentCoroutineContext().ensureActive()
            val record = entries.getValue(requested.entryId)
            Source(requested, entryHash(record), proof(resources.resolveOwnedReference(requested.imagePath)), record.createdAt)
        }
    }
    private suspend fun verifySources(sources: List<Source>) {
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            if (entryHash(entry(source.selected.entryId)) != source.entryHash) throw Changed()
            same(source.file)
        }
    }
    private suspend fun existing(directory: Path, name: String): List<Proof> {
        val found = mutableListOf<Proof>()
        Files.newDirectoryStream(directory).use { children ->
            for (path in children) {
                currentCoroutineContext().ensureActive()
                if (NovelAiHistoryExportNaming.sameName(path.fileName.toString(), name)) found += proof(path)
            }
        }
        return found.sortedWith(compareByDescending<Proof> { it.modified }.thenBy { it.path.fileName.toString() })
    }

    suspend fun prepare(selected: List<NovelAiHistoryImageSelection>, title: String = ""): Plan? = withContext(Dispatchers.IO) {
        val captured = gate.withNormalOperation { sources(selected) }
        val directory = directoryPicker.pickDirectory("选择批量保存图片的文件夹")?.toAbsolutePath()?.normalize() ?: return@withContext null
        gate.withNormalOperation {
            verifySources(captured)
            val key = directoryKey(directory)
            val items = captured.mapIndexed { index, source ->
                val name = NovelAiHistoryExportNaming.displayName(source.selected.imagePath, source.createdAt, index + 1, title)
                check(Path.of(name).fileName.toString() == name && name !in setOf(".", ".."))
                val matches = existing(directory, name)
                check(matches.none { target -> captured.any { it.file.path == target.path } }) { "HISTORY_EXPORT_SOURCE_TARGET" }
                Item(source, name, matches)
            }
            check(items.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet().size == items.size)
            check(directoryKey(directory) == key)
            Plan(directory, key, items)
        }
    }
    private suspend fun verify(plan: Plan) {
        if (directoryKey(plan.directory) != plan.directoryKey) throw Changed()
        verifySources(plan.items.map { it.source })
        for (item in plan.items) if (existing(plan.directory, item.name) != item.existing) throw Changed()
    }

    suspend fun execute(plan: Plan, decisions: Map<NovelAiHistoryImageSelection, Decision>, progress: (Int, Int) -> Unit = { _, _ -> }): Result =
        withContext(Dispatchers.IO) { gate.withNormalOperation {
            require(decisions.keys == plan.conflicts.map { it.key }.toSet()) { "HISTORY_EXPORT_EXPLICIT_DECISIONS" }
            verify(plan)
            val work = Files.createTempDirectory(plan.directory, ".ccb-history-export-")
            val workKey = directoryKey(work)
            val owned = linkedMapOf<Path, Proof>()
            val written = mutableListOf<Written>()
            val displaced = mutableListOf<Displaced>()
            val active = plan.items.filter { decisions[it.key] != Decision.SKIP }
            var saved = 0
            var keepWork = false
            var failed: Throwable? = null
            var unknownPublication = false
            suspend fun checkRoot() {
                if (directoryKey(plan.directory) != plan.directoryKey || directoryKey(work) != workKey) throw Changed()
            }
            try {
                val staged = active.mapIndexed { index, item ->
                    checkRoot()
                    val path = work.resolve("source-$index")
                    try {
                        check(DesktopArchiveFiles.copyNew(item.source.file.path, path, item.source.file.size) == item.source.file.hash)
                    } finally {
                        withContext(NonCancellable) {
                            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) owned[path] = proof(path)
                        }
                    }
                    owned.getValue(path)
                }
                // All images and explicit target choices are re-proved before the first publish.
                verify(plan)
                for ((index, item) in active.withIndex()) {
                    currentCoroutineContext().ensureActive(); checkRoot()
                    val old = item.existing.firstOrNull()
                    val destination = old?.path ?: plan.directory.resolve(item.name)
                    if (old != null) {
                        same(old)
                        val backup = Displaced(destination, work.resolve("original-$index"))
                        withContext(NonCancellable) {
                            Files.move(destination, backup.file)
                            displaced += backup
                            backup.proof = proof(backup.file).also { owned[backup.file] = it }
                        }
                        // A racing change is retained as the displaced original and rolled back.
                        check(backup.proof!!.let { it.key == old.key && it.hash == old.hash && it.size == old.size })
                    }
                    val options = mutableSetOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    if (windows) options += setOf(ExtendedOpenOption.NOSHARE_DELETE, ExtendedOpenOption.NOSHARE_WRITE)
                    FileChannel.open(destination, options).use { channel ->
                        unknownPublication = true
                        withContext(NonCancellable) {
                            // The Windows handle denies replacement until this new file ID is journaled.
                            written += Written(destination, identity(destination, false), staged[index])
                            unknownPublication = false
                        }
                        check(DesktopArchiveFiles.read(staged[index].path, staged[index].size) { bytes, count ->
                            val buffer = ByteBuffer.wrap(bytes, 0, count)
                            while (buffer.hasRemaining()) channel.write(buffer)
                        } == staged[index].hash)
                        channel.force(true)
                    }
                    check(proof(destination).let { it.key == written.last().key && it.hash == staged[index].hash })
                    saved++; progress(saved, active.size); afterPublished(saved)
                }
                verifySources(plan.items.map { it.source }); checkRoot()
                Result(saved, plan.items.size - saved, active.sumOf { (it.existing.size - 1).coerceAtLeast(0) })
            } catch (failure: Throwable) {
                failed = failure
                val rollbackFailures = withContext(NonCancellable) {
                    var failures = if (unknownPublication) 1 else 0
                    for (output in written.asReversed()) try {
                        checkRoot()
                        val actual = proof(output.path)
                        check(actual.key == output.key && actual.size <= output.staged.size)
                        val prefix = MessageDigest.getInstance("SHA-256"); var remaining = actual.size
                        DesktopArchiveFiles.read(output.staged.path, output.staged.size) { bytes, count ->
                            val take = minOf(count.toLong(), remaining).toInt(); prefix.update(bytes, 0, take); remaining -= take
                        }
                        check(actual.hash == prefix.digest().hex())
                        Files.delete(output.path)
                    } catch (_: Exception) { failures++ }
                    for (original in displaced.asReversed()) try {
                        checkRoot(); val originalProof = requireNotNull(original.proof); same(originalProof)
                        check(DesktopArchiveFiles.copyNew(original.file, original.destination, originalProof.size) == originalProof.hash)
                    } catch (_: Exception) { failures++ }
                    failures
                }
                if (rollbackFailures > 0) { keepWork = true; throw Failure(work, failure) }
                throw failure
            } finally {
                withContext(NonCancellable) {
                    if (!keepWork) {
                        for ((path, expected) in owned) try { checkRoot(); same(expected); Files.delete(path) }
                        catch (_: Exception) { keepWork = true }
                        if (!keepWork) try { checkRoot(); Files.delete(work) } catch (_: Exception) { keepWork = true }
                    }
                    if (keepWork && failed != null) throw Failure(work, requireNotNull(failed))
                }
            }.let { result -> if (keepWork) result.copy(recoveryDirectory = work) else result }
        } }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
