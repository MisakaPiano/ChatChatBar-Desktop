package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncResourceBudget
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/**
 * Private untrusted ciphertext evidence. Same-key descriptors remain independent candidates;
 * storing or selecting an exact descriptor proves no AEAD, signature, key or group authority.
 */
class SyncCiphertextCandidates(private val root: Path, private val legacy: CiphertextStore? = null) {
    private val preparations = root.resolve("preparations")
    init { ensureDirectory(root); ensureDirectory(preparations) }

    /** Full source length/hash is verified before a complete single-object store is published. */
    fun retain(metadata: CiphertextObjectMetadata, source: CiphertextStore) {
        requireCommit(metadata)
        candidate(metadata)?.let { return }
        // Discovery pins this candidate descriptor. A generic metadata(key) lookup could name a
        // different untrusted candidate; readTo must return and fully match the exact descriptor.
        LanSyncResourceBudget().requireSpace(preparations, metadata.descriptor.length, copies = 2)
        PrivateSyncFile.create(preparations).use { temporary ->
            var count = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            val result = FileChannel.open(temporary.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length &&
                            count <= metadata.descriptor.length - length) { "SYNC_CANDIDATE_SOURCE_CHANGED" }
                        val buffer = ByteBuffer.wrap(bytes, offset, length)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        digest.update(bytes, offset, length); count += length
                    }
                }
                source.readTo(metadata.key, output, metadata.descriptor).also { channel.force(true) }
            }
            require(result == metadata && count == metadata.descriptor.length &&
                hex(digest.digest()) == metadata.descriptor.sha256.hex) { "SYNC_CANDIDATE_SOURCE_CHANGED" }
            val name = name(metadata)
            val staging = preparations.resolve("candidate-${UUID.randomUUID()}")
            val store = FileCiphertextStore(staging)
            Files.newInputStream(temporary.path, NOFOLLOW_LINKS).use { input ->
                store.put(CiphertextPutRequest(metadata.key, operation(metadata), metadata.descriptor), input)
            }
            require(single(staging) == metadata) { "SYNC_CANDIDATE_DAMAGED" }
            val target = root.resolve(name)
            try {
                // An incomplete preparation is never an enumerated candidate. No replacement path.
                if (!Files.exists(target, NOFOLLOW_LINKS)) Files.move(staging, target, ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                // A concurrent same-descriptor writer may have completed first.
            } catch (_: DirectoryNotEmptyException) {
                // Atomic directory publication cannot replace an already completed store.
            }
            require(candidate(metadata) != null) { "SYNC_CANDIDATE_DAMAGED" }
            // A failed/racing store preparation stays private for inspection; no sweep owns it.
        }
    }

    /** Distinct descriptors for one key are intentionally retained and returned. */
    fun entries(): List<CiphertextObjectMetadata> {
        PrivateSyncFile.checked(root, true)
        val result = linkedSetOf<CiphertextObjectMetadata>()
        Files.newDirectoryStream(root).use { paths -> paths.forEach { path ->
            if (path == preparations) PrivateSyncFile.checked(path, true)
            else {
                require(path.fileName.toString().matches(DIRECTORY)) { "SYNC_CANDIDATE_UNEXPECTED_PATH" }
                val metadata = single(path)
                require(path.fileName.toString() == name(metadata)) { "SYNC_CANDIDATE_DAMAGED" }
                result += metadata
            }
        } }
        legacy?.let { original ->
            var cursor: CommitCursor? = null
            val seen = hashSetOf<String>()
            do {
                val page = original.listCommits(cursor)
                page.entries.forEach { requireCommit(it); result += it }
                cursor = page.nextCursor
                cursor?.let { require(seen.add(it.token)) { "SYNC_CANDIDATE_CURSOR_REPEATED" } }
            } while (cursor != null)
        }
        return Collections.unmodifiableList(result.sortedWith(compareBy({ it.key.id.value },
            { it.descriptor.length }, { it.descriptor.sha256.hex })))
    }

    /** Read-only lens: a different descriptor under this key is never substituted. */
    fun exact(metadata: CiphertextObjectMetadata): CiphertextStore {
        requireCommit(metadata)
        return object : CiphertextStore {
            override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED)
            private fun source(): CiphertextStore? = candidate(metadata) ?: legacy?.takeIf { it.metadata(metadata.key) == metadata }
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult =
                throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)
            override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? =
                if (key != metadata.key || source() == null) null else metadata
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                if (key != metadata.key) return null
                require(expected == null || expected == metadata.descriptor) { "SYNC_CANDIDATE_DESCRIPTOR_CHANGED" }
                val store = source() ?: return null
                return store.readTo(key, destination, metadata.descriptor).also {
                    require(it == null || it == metadata) { "SYNC_CANDIDATE_SOURCE_CHANGED" }
                }
            }
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                require(pageSize in 1..1000) { "SYNC_CANDIDATE_PAGE_SIZE" }
                require(cursor == null) { "SYNC_CANDIDATE_CURSOR" }
                return CommitPage(if (source() == null) emptyList() else listOf(metadata), null)
            }
        }
    }

    private fun candidate(metadata: CiphertextObjectMetadata): FileCiphertextStore? {
        PrivateSyncFile.checked(root, true)
        val path = root.resolve(name(metadata))
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(single(path) == metadata) { "SYNC_CANDIDATE_DAMAGED" }
        return FileCiphertextStore(path)
    }
    private fun single(path: Path): CiphertextObjectMetadata {
        PrivateSyncFile.checked(path, true)
        val expected = setOf("store.id", "store.lock", "objects", "commits", "operations", "staging")
        require(Files.newDirectoryStream(path).use { children -> children.map { it.fileName.toString() }.toSet() } == expected) {
            "SYNC_CANDIDATE_UNEXPECTED_PATH"
        }
        for (name in listOf("store.id", "store.lock")) PrivateSyncFile.checked(path.resolve(name))
        for (name in listOf("objects", "commits", "operations", "staging")) PrivateSyncFile.checked(path.resolve(name), true)
        require(Files.newDirectoryStream(path.resolve("objects")).use { !it.iterator().hasNext() } &&
            Files.newDirectoryStream(path.resolve("staging")).use { !it.iterator().hasNext() }) { "SYNC_CANDIDATE_UNEXPECTED_PATH" }
        val store = FileCiphertextStore(path)
        val page = store.listCommits(null, 2)
        require(page.nextCursor == null && page.entries.size == 1) { "SYNC_CANDIDATE_DAMAGED" }
        val metadata = page.entries.single(); requireCommit(metadata)
        val operations = Files.newDirectoryStream(path.resolve("operations")).use { it.toList() }
        require(operations.size == 1 && operations.single().fileName.toString() == "${operation(metadata).value}.op") { "SYNC_CANDIDATE_DAMAGED" }
        PrivateSyncFile.checked(operations.single())
        return metadata
    }
    private fun requireCommit(metadata: CiphertextObjectMetadata) {
        require(metadata.key.kind == CiphertextObjectKind.COMMIT) { "SYNC_CANDIDATE_COMMIT_REQUIRED" }
    }
    private fun name(metadata: CiphertextObjectMetadata): String = hex(MessageDigest.getInstance("SHA-256").digest(
        ("CCB-SYNC-CIPHERTEXT-CANDIDATE/1\n${metadata.key.kind.name}\n${metadata.key.id.value}\n${metadata.descriptor.length}\n${metadata.descriptor.sha256.hex}\n")
            .toByteArray(Charsets.US_ASCII)))
    private fun operation(metadata: CiphertextObjectMetadata) = SyncStoreId.parse(name(metadata).take(32))
    private fun ensureDirectory(path: Path) {
        require(path.isAbsolute && path.normalize() == path) { "SYNC_CANDIDATE_PRIVATE_PATH" }
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            ensureDirectory(requireNotNull(path.parent))
            try { Files.createDirectory(path) } catch (_: FileAlreadyExistsException) { }
        }
        PrivateSyncFile.checked(path, true)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    companion object { private val DIRECTORY = Regex("[a-f0-9]{64}") }
}
