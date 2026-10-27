package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Immutable original-byte custody outside live images, entities and Task/Scheduler consumers. */
internal object DesktopPreservationStore {
    const val ROOT = "desktop_preservation"
    private const val INDEX = "index.json"
    private const val MAX_FILES = 40000
    private val hashPattern = Regex("[a-f0-9]{64}")

    @Serializable data class FileProof(val path: String, val size: Long, val sha256: String)
    @Serializable data class Record(val sourcePath: String, val storedPath: String, val id: String, val type: String)
    @Serializable data class Edge(val sourceRecord: String, val field: String, val sourceReference: String,
        val storedPath: String? = null, val state: EdgeState)
    @Serializable enum class EdgeState { OWNED_COPY_VERIFIED, SOURCE_MEDIA_NOT_SELECTED, HISTORICAL_AVATAR_ABSENT }
    @Serializable data class Index(val version: Int = 1, val kind: String = "dormant-official-moments",
        val sourceSha256: String, val sourceCode: Int, val sourceFormatVersion: Int,
        val sourceScope: InspectionScope? = null,
        val files: List<FileProof>, val records: List<Record>, val edges: List<Edge>)
    class Result(val records: Int, val mediaCopies: Int, val unavailableSourceMedia: Int) {
        override fun toString() = "PreservedMoments(records=$records,media=$mediaCopies,unavailable=$unavailableSourceMedia)"
    }

    suspend fun preserveMoments(input: DesktopFullBackupStaging, targetRoot: Path,
        beforePublish: suspend () -> Unit = {}): Result = withContext(Dispatchers.IO) {
        check(DesktopDataRootOwnership.isHeld(targetRoot)) { "PRESERVATION_ROOT_NOT_OWNED" }
        input.verifyUnchanged()
        val sourceHash = input.source.sourceHash.also { check(hashPattern.matches(it)) }
        val selected = input.entities.filterKeys { DesktopBackupPolicy.entityType(it) in setOf("moment_posts", "moment_tasks") }
        if (selected.isEmpty()) return@withContext Result(0, 0, 0)
        DesktopArchiveFiles.safe(targetRoot, true)
        val parent = targetRoot.resolve(ROOT)
        Files.createDirectories(parent); DesktopArchiveFiles.safe(parent, true)
        val home = parent.resolve(sourceHash)
        // Repeated imports can reuse a verified identical seal; no overwrite or silent orphan sweep.
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            val existing = validateSeal(home)
            check(existing.sourceSha256 == sourceHash && existing.records.map { it.sourcePath }.toSet() == selected.keys)
            return@withContext Result(existing.records.size, existing.files.count { it.path.startsWith("media/") }, existing.edges.count { it.storedPath == null })
        }
        Files.createDirectory(home)
        Files.createDirectory(home.resolve("records")); Files.createDirectory(home.resolve("media"))
        val files = linkedMapOf<String, FileProof>()
        val records = mutableListOf<Record>(); val edges = mutableListOf<Edge>()
        val logicalIds = hashSetOf<Pair<String, String>>()
        val postIds = selected.filterKeys { DesktopBackupPolicy.entityType(it) == "moment_posts" }.values.mapTo(hashSetOf()) { it.getValue("id").jsonPrimitive.content }
        suspend fun copy(sourcePath: String, relative: String) {
            if (relative in files) return
            val original = requireNotNull(input.files[sourcePath]) { "PRESERVATION_SOURCE_MISSING" }
            val expected = input.source.inventory.getValue(sourcePath)
            check(DesktopArchiveFiles.copyNew(original, home.resolve(relative), DesktopFullBackupSource.MAX_FILE) == expected.sha256)
            files[relative] = FileProof(relative, expected.size, expected.sha256)
        }
        for ((path, value) in selected.toSortedMap()) {
            currentCoroutineContext().ensureActive()
            val type = DesktopBackupPolicy.entityType(path)
            val id = value.getValue("id").jsonPrimitive.also { check(it.isString && it.content.isNotBlank()) }.content
            check(logicalIds.add(type to id)) { "PRESERVATION_DUPLICATE_ID" }
            if (type == "moment_tasks") {
                val task = backupJson.decodeFromJsonElement<MomentTaskSnapshot>(value)
                // No Task is ever registered; a dangling non-null post cannot be called a complete graph.
                check(task.postId == null || task.postId in postIds) { "PRESERVATION_TASK_POST_MISSING" }
            }
            val rawHash = input.source.inventory.getValue(path).sha256
            val stored = "records/$rawHash.json"
            copy(path, stored)
            records += Record(path, stored, id, type)
            if (type != "moment_posts") continue
            val post = backupJson.decodeFromJsonElement<MomentPostSnapshot>(value)
            val references = listOf("senderAvatar" to post.senderAvatar, "imagePath" to post.imagePath,
                "generatedImageMetadata.imagePath" to post.generatedImageMetadata?.imagePath)
            for ((field, raw) in references) {
                if (raw.isNullOrBlank()) continue
                check(raw.startsWith(DesktopBackupPolicy.PREFIX)) { "PRESERVATION_RESOURCE_NOT_IN_ARCHIVE" }
                val relative = DesktopBackupPolicy.path(raw.removePrefix(DesktopBackupPolicy.PREFIX))
                check(relative.startsWith("images/")) { "PRESERVATION_RESOURCE_FAMILY" }
                val proof = input.source.inventory[relative]
                if (proof == null) {
                    val state = when {
                        field == "senderAvatar" && input.source.manifest.appVersionCode in 85..86 -> EdgeState.HISTORICAL_AVATAR_ABSENT
                        field != "senderAvatar" && input.source.manifest.scope?.momentsImages == false -> EdgeState.SOURCE_MEDIA_NOT_SELECTED
                        else -> error("PRESERVATION_MEDIA_MISSING")
                    }
                    edges += Edge(path, field, raw, state = state)
                } else {
                    val storedMedia = "media/${proof.sha256}.bin"
                    copy(relative, storedMedia)
                    edges += Edge(path, field, raw, storedMedia, EdgeState.OWNED_COPY_VERIFIED)
                }
            }
        }
        val index = Index(sourceSha256 = sourceHash, sourceCode = input.source.manifest.appVersionCode,
            sourceFormatVersion = input.source.manifest.formatVersion, sourceScope = input.source.manifest.scope,
            files = files.values.toList(), records = records, edges = edges)
        val bytes = backupJson.encodeToString(Index.serializer(), index).toByteArray(Charsets.UTF_8)
        check(bytes.size <= DesktopBackupPolicy.MAX_JSON)
        val partial = home.resolve("index.part")
        FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true)
        }
        beforePublish()
        currentCoroutineContext().ensureActive()
        Files.move(partial, home.resolve(INDEX), StandardCopyOption.ATOMIC_MOVE)
        validateSeal(home)
        Result(records.size, files.keys.count { it.startsWith("media/") }, edges.count { it.storedPath == null })
    }

    suspend fun validate(root: Path) {
        val directory = root.resolve(ROOT)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        DesktopArchiveFiles.safe(directory, true)
        Files.newDirectoryStream(directory).use { seals ->
            var count = 0
            for (seal in seals) {
                currentCoroutineContext().ensureActive()
                check(++count <= 1000 && hashPattern.matches(seal.fileName.toString())) { "PRESERVATION_UNKNOWN_SEAL" }
                validateSeal(seal)
            }
        }
    }

    private suspend fun validateSeal(home: Path): Index {
        DesktopArchiveFiles.safe(home, true)
        val file = home.resolve(INDEX)
        DesktopArchiveFiles.safe(file, false); check(Files.size(file) <= DesktopBackupPolicy.MAX_JSON)
        val raw = Files.readAllBytes(file)
        val text = java.io.StringWriter()
        BackupJsonStream(raw.inputStream().strictUtf8Reader(), text) { value, _ -> value }.copy()
        val index = backupJson.decodeFromString(Index.serializer(), text.toString())
        check(index.version == 1 && index.kind == "dormant-official-moments" && index.sourceCode in 84..86)
        check(index.sourceFormatVersion in 1..2 && (index.sourceFormatVersion == 2) == (index.sourceScope != null))
        check(index.sourceFormatVersion != 2 || index.sourceCode >= 85)
        check(index.sourceSha256 == home.fileName.toString() && hashPattern.matches(index.sourceSha256))
        check(index.files.size <= MAX_FILES && index.records.size <= MAX_FILES && index.edges.size <= MAX_FILES * 3)
        val expected = linkedSetOf(INDEX)
        val proofs = index.files.associateBy { it.path }; check(proofs.size == index.files.size)
        for (proof in index.files) {
            check(proof.path.matches(Regex("(records/[a-f0-9]{64}\\.json|media/[a-f0-9]{64}\\.bin)")))
            check(hashPattern.matches(proof.sha256) && proof.size in 0..DesktopFullBackupSource.MAX_FILE)
            val resource = home.resolve(proof.path)
            check(Files.size(resource) == proof.size && DesktopArchiveFiles.hash(resource, DesktopFullBackupSource.MAX_FILE) == proof.sha256) { "PRESERVATION_BYTES_CHANGED" }
            expected += proof.path
        }
        check(index.records.map { it.type to it.id }.distinct().size == index.records.size)
        val posts = linkedMapOf<String, MomentPostSnapshot>()
        val tasks = mutableListOf<MomentTaskSnapshot>()
        index.records.forEach { record ->
            check(record.type in setOf("moment_posts", "moment_tasks") && record.storedPath in proofs && record.sourcePath.startsWith("entities/${record.type}/"))
            check(record.storedPath.startsWith("records/") && record.id.isNotBlank())
            val path = home.resolve(record.storedPath); check(Files.size(path) <= DesktopBackupPolicy.MAX_JSON)
            val writer = java.io.StringWriter()
            Files.newInputStream(path).strictUtf8Reader().use { reader -> BackupJsonStream(reader, writer) { v, _ -> v }.copy() }
            if (record.type == "moment_posts") {
                val post = backupJson.decodeFromString(MomentPostSnapshot.serializer(), writer.toString())
                check(post.id == record.id); posts[record.sourcePath] = post
            } else {
                val task = backupJson.decodeFromString(MomentTaskSnapshot.serializer(), writer.toString())
                check(task.id == record.id); tasks += task
            }
        }
        val postIds = posts.values.mapTo(hashSetOf()) { it.id }
        check(tasks.all { it.postId == null || it.postId in postIds }) { "PRESERVATION_TASK_POST_MISSING" }
        val expectedEdges = posts.flatMap { (path, post) -> listOf("senderAvatar" to post.senderAvatar,
            "imagePath" to post.imagePath, "generatedImageMetadata.imagePath" to post.generatedImageMetadata?.imagePath)
            .filter { !it.second.isNullOrBlank() }.map { Triple(path, it.first, it.second!!) } }.toSet()
        check(index.edges.map { Triple(it.sourceRecord, it.field, it.sourceReference) }.toSet() == expectedEdges && index.edges.size == expectedEdges.size)
        index.edges.forEach { edge ->
            check(edge.sourceReference.startsWith(DesktopBackupPolicy.PREFIX))
            check(DesktopBackupPolicy.path(edge.sourceReference.removePrefix(DesktopBackupPolicy.PREFIX)).startsWith("images/"))
            check((edge.state == EdgeState.OWNED_COPY_VERIFIED) == (edge.storedPath != null))
            edge.storedPath?.let { check(it.startsWith("media/") && it in proofs) }
            when (edge.state) {
                EdgeState.SOURCE_MEDIA_NOT_SELECTED -> check(index.sourceScope?.momentsImages == false && edge.field != "senderAvatar")
                EdgeState.HISTORICAL_AVATAR_ABSENT -> check(index.sourceCode in 85..86 && edge.field == "senderAvatar")
                EdgeState.OWNED_COPY_VERIFIED -> Unit
            }
        }
        check(proofs.keys == (index.records.map { it.storedPath } + index.edges.mapNotNull { it.storedPath }).toSet())
        val observed = hashSetOf<String>()
        Files.walk(home).use { paths -> paths.forEach { child ->
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(child, true)
            else { DesktopArchiveFiles.safe(child, false); check(observed.size <= MAX_FILES + 1); observed += home.relativize(child).joinToString("/") }
        } }
        check(observed == expected) { "PRESERVATION_FILE_SET_CHANGED" }
        return index
    }
}
