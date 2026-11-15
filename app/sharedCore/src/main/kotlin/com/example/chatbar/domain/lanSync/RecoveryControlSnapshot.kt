package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.Serializable

/** Independent ciphertext candidates only. Reopening never admits trust, keys or native data. */
class LanSyncGroupRecoveryControlSnapshot private constructor() {
    @Serializable private data class Item(val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor,
        val operation: SyncStoreId) {
        fun metadata() = CiphertextObjectMetadata(key, descriptor)
    }
    @Serializable private data class Manifest(val format: Int, val anchor: LanSyncGroupRecoveryAnchor,
        val store: SyncStoreId, val inventoryHash: String, val items: List<Item>)
    @Serializable private data class Ready(val format: Int, val manifestHash: String,
        val store: SyncStoreId, val inventoryHash: String)

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val READY = "ready.json"
        private const val CIPHERTEXT = "ciphertext"
        private val order = compareBy<CiphertextObjectMetadata>({ it.key.kind.name }, { it.key.id.value })

        /** The issuer stays live only while copying; the returned source captures no issuer owner. */
        internal fun retain(root: Path, bootstrap: LanSyncGroupRecoveryBootstrap,
            checkActive: () -> Unit = {}): LanSyncGroupRecoveryBootstrap {
            checkActive()
            val anchor = frozen(bootstrap.anchor)
            if (Files.exists(root, NOFOLLOW_LINKS)) {
                val prior = open(root, checkActive)
                require(prior.anchor == anchor) { "SYNC_GROUP_RECOVERY_CONTROL_ANCHOR_CHANGED" }
                checkActive(); return prior
            }
            lanSyncSafe(requireNotNull(root.parent), true)
            Files.createDirectory(root); lanSyncSafe(root, true)
            val selected = inventory(bootstrap, checkActive)
            val store = FileCiphertextStore(root.resolve(CIPHERTEXT))
            val items = selected.map { metadata ->
                checkActive()
                val item = Item(metadata.key, metadata.descriptor, SyncStoreId.random())
                // Spool before entering the destination store: never call an issuer store from
                // inside this FileCiphertextStore's input callback or lock.
                PrivateSyncFile.create(root).use { file ->
                    LanSyncResourceBudget().requireSpace(root, metadata.descriptor.length, copies = 2)
                    FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                        val sink = Channels.newOutputStream(channel)
                        val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
                        val guarded = object : OutputStream() {
                            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                checkActive(); require(length >= 0 && count <= metadata.descriptor.length - length) {
                                    "SYNC_GROUP_RECOVERY_CONTROL_OBJECT_CHANGED"
                                }
                                sink.write(bytes, offset, length); hash.update(bytes, offset, length); count += length
                                checkActive()
                            }
                        }
                        val actual = bootstrap.source.readTo(metadata.key, guarded, metadata.descriptor)
                        checkActive()
                        require(actual == metadata && count == metadata.descriptor.length && digest(hash) == metadata.descriptor.sha256.hex) {
                            "SYNC_GROUP_RECOVERY_CONTROL_OBJECT_CHANGED"
                        }
                        channel.force(true)
                    }
                    Files.newInputStream(file.path, NOFOLLOW_LINKS).use { input ->
                        val guarded = object : java.io.FilterInputStream(input) {
                            override fun read(): Int { checkActive(); val value = `in`.read(); checkActive(); return value }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                checkActive(); val count = `in`.read(bytes, offset, length); checkActive(); return count
                            }
                        }
                        require(store.put(CiphertextPutRequest(item.key, item.operation, item.descriptor), guarded).metadata == metadata) {
                            "SYNC_GROUP_RECOVERY_CONTROL_OBJECT_CHANGED"
                        }
                    }
                }
                item
            }
            require(inventory(bootstrap, checkActive) == selected) { "SYNC_GROUP_RECOVERY_CONTROL_INVENTORY_CHANGED" }
            val storeId = storeId(root.resolve(CIPHERTEXT))
            val manifest = Manifest(1, anchor, storeId, inventoryHash(selected), items)
            checkActive(); LanSyncJsonFiles.write(root.resolve(MANIFEST), Manifest.serializer(), manifest)
            LanSyncMetadataBudget().validate(root.resolve(MANIFEST))
            val ready = Ready(1, hashFile(root.resolve(MANIFEST), checkActive), storeId, manifest.inventoryHash)
            checkActive(); LanSyncJsonFiles.write(root.resolve(READY), Ready.serializer(), ready)
            checkActive()
            return open(root, checkActive)
        }

        /** A missing/incomplete snapshot refuses before opening a store that could create origins. */
        fun open(root: Path, checkActive: () -> Unit = {}): LanSyncGroupRecoveryBootstrap {
            checkActive(); lanSyncSafe(root, true)
            require(children(root) == setOf(MANIFEST, READY, CIPHERTEXT)) { "SYNC_GROUP_RECOVERY_CONTROL_INCOMPLETE" }
            val readyPath = root.resolve(READY); val manifestPath = root.resolve(MANIFEST)
            LanSyncMetadataBudget().validate(readyPath)
            val ready = requireNotNull(LanSyncJsonFiles.read(readyPath, Ready.serializer()))
            require(ready.format == 1 && ready.manifestHash.matches(Regex("[a-f0-9]{64}")) &&
                ready.inventoryHash.matches(Regex("[a-f0-9]{64}")) && hashFile(manifestPath, checkActive) == ready.manifestHash) {
                "SYNC_GROUP_RECOVERY_CONTROL_MANIFEST_CHANGED"
            }
            LanSyncMetadataBudget().validate(manifestPath)
            val manifest = requireNotNull(LanSyncJsonFiles.read(manifestPath, Manifest.serializer(), ready.manifestHash))
            val anchor = frozen(manifest.anchor)
            val rows = manifest.items.map { it.metadata() }
            require(manifest.format == 1 && manifest.store == ready.store && manifest.inventoryHash == ready.inventoryHash &&
                manifest.inventoryHash == inventoryHash(rows) && rows == rows.sortedWith(order) &&
                rows.map { it.key }.toSet().size == rows.size && manifest.items.map { it.operation }.toSet().size == rows.size &&
                rows.singleOrNull { it.key == anchor.vault.key }?.descriptor == anchor.vaultDescriptor &&
                rows.all { it.key.kind == CiphertextObjectKind.COMMIT || it.key == anchor.vault.key }) {
                "SYNC_GROUP_RECOVERY_CONTROL_INVENTORY_CHANGED"
            }
            val path = root.resolve(CIPHERTEXT)
            // FileCiphertextStore creates missing directories/identity on ordinary construction.
            // A cold snapshot must have all of them already, with no abandoned writer to repair.
            require(children(path) == setOf("objects", "commits", "operations", "staging", "store.id", "store.lock")) {
                "SYNC_GROUP_RECOVERY_CONTROL_STORE_CHANGED"
            }
            require(storeId(path) == ready.store) { "SYNC_GROUP_RECOVERY_CONTROL_STORE_CHANGED" }
            lanSyncSafe(path.resolve("store.lock"))
            require(children(path.resolve("staging")).isEmpty() &&
                children(path.resolve("objects")) == rows.filter { it.key.kind == CiphertextObjectKind.BLOB }.map { "${it.key.id.value}.obj" }.toSet() &&
                children(path.resolve("commits")) == rows.filter { it.key.kind == CiphertextObjectKind.COMMIT }.map { "${it.key.id.value}.obj" }.toSet() &&
                children(path.resolve("operations")) == manifest.items.map { "${it.operation.value}.op" }.toSet()) {
                "SYNC_GROUP_RECOVERY_CONTROL_STORE_CHANGED"
            }
            checkActive()
            val store = FileCiphertextStore(path)
            for (row in rows) {
                checkActive()
                require(store.readTo(row.key, guardedOutput(discard, checkActive), row.descriptor) == row) {
                    "SYNC_GROUP_RECOVERY_CONTROL_OBJECT_CHANGED"
                }
                checkActive()
            }
            val byKey = Collections.unmodifiableMap(rows.associateBy { it.key })
            val commits = Collections.unmodifiableList(rows.filter { it.key.kind == CiphertextObjectKind.COMMIT })
            val prefix = "recovery-controls1:${ready.store.value}:${ready.manifestHash}:"
            val source = object : CiphertextStore {
                override val capabilities = store.capabilities
                override fun put(request: CiphertextPutRequest, source: java.io.InputStream): CiphertextPutResult =
                    throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)
                override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
                    checkActive(); val expected = byKey[key] ?: return null
                    require(store.readTo(key, guardedOutput(discard, checkActive), expected.descriptor) == expected) {
                        "SYNC_GROUP_RECOVERY_CONTROL_OBJECT_CHANGED"
                    }
                    checkActive(); return expected
                }
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream,
                    expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                    checkActive(); val selected = byKey[key] ?: return null
                    require(expected == null || expected == selected.descriptor) { "SYNC_GROUP_RECOVERY_CONTROL_DESCRIPTOR_CHANGED" }
                    val actual = store.readTo(key, guardedOutput(destination, checkActive), selected.descriptor)
                    checkActive(); require(actual == selected) { "SYNC_GROUP_RECOVERY_CONTROL_OBJECT_CHANGED" }; return actual
                }
                override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                    checkActive(); require(pageSize in 1..1000) { "SYNC_GROUP_RECOVERY_CONTROL_PAGE_SIZE" }
                    val after = cursor?.token?.let {
                        if (!it.startsWith(prefix) || !it.removePrefix(prefix).matches(Regex("[a-f0-9]{32}")))
                            throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
                        it.removePrefix(prefix)
                    }
                    val remaining = commits.filter { after == null || it.key.id.value > after }
                    val page = remaining.take(pageSize)
                    checkActive()
                    return CommitPage(page, if (remaining.size > page.size) CommitCursor(prefix + page.last().key.id.value) else null)
                }
                override fun toString() = "LanSyncGroupRecoveryControlSnapshotSource(REDACTED)"
            }
            checkActive(); return LanSyncGroupRecoveryBootstrap(anchor, source)
        }

        private fun frozen(anchor: LanSyncGroupRecoveryAnchor): LanSyncGroupRecoveryAnchor {
            anchor.validate()
            return anchor.copy(dataHeads = Collections.unmodifiableSet(anchor.dataHeads.sortedWith(compareBy({ it.id.value }, { it.bodyHash })).toCollection(linkedSetOf())))
        }
        private fun inventory(bootstrap: LanSyncGroupRecoveryBootstrap, active: () -> Unit): List<CiphertextObjectMetadata> {
            active(); val vault = requireNotNull(bootstrap.source.metadata(bootstrap.anchor.vault.key)) { "SYNC_GROUP_RECOVERY_CONTROL_VAULT_MISSING" }
            require(vault.descriptor == bootstrap.anchor.vaultDescriptor) { "SYNC_GROUP_RECOVERY_CONTROL_VAULT_CHANGED" }
            val selected = linkedMapOf(vault.key to vault); var cursor: CommitCursor? = null; val seen = hashSetOf<String>()
            do {
                active(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_RECOVERY_CONTROL_LISTING_CHANGED" } }
                val page = bootstrap.source.listCommits(cursor)
                for (row in page.entries) {
                    require(row.key.kind == CiphertextObjectKind.COMMIT && selected[row.key]?.let { it == row } != false) {
                        "SYNC_GROUP_RECOVERY_CONTROL_INVENTORY_CHANGED"
                    }
                    require((selected.size.toLong() + 1) * 1024 <= LanSyncMetadataBudget().maximumDecodedCost) { "SYNC_METADATA_WORKING_BUDGET" }
                    selected[row.key] = row
                }
                cursor = page.nextCursor
            } while (cursor != null)
            active(); return selected.values.sortedWith(order)
        }
        private fun children(path: Path): Set<String> {
            lanSyncSafe(path, true)
            return Files.newDirectoryStream(path).use { files -> files.map { child ->
                lanSyncSafe(child, Files.isDirectory(child, NOFOLLOW_LINKS)); child.fileName.toString()
            }.toSet() }
        }
        private fun storeId(path: Path): SyncStoreId {
            val file = path.resolve("store.id"); lanSyncSafe(file)
            require(Files.size(file) == 32L) { "SYNC_GROUP_RECOVERY_CONTROL_STORE_CHANGED" }
            return SyncStoreId.parse(lanSyncUtf8(lanSyncRead(file, 32)))
        }
        private fun inventoryHash(rows: List<CiphertextObjectMetadata>): String {
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update("CCB-GROUP-RECOVERY-CONTROL-INVENTORY/1\n".toByteArray(Charsets.US_ASCII))
            rows.forEach { hash.update("${it.key.kind.name}:${it.key.id.value}:${it.descriptor.length}:${it.descriptor.sha256.hex}\n".toByteArray(Charsets.US_ASCII)) }
            return digest(hash)
        }
        private fun hashFile(path: Path, active: () -> Unit): String {
            lanSyncSafe(path); val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { active(); val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            active(); return digest(hash)
        }
        private fun guardedOutput(destination: OutputStream, active: () -> Unit) = object : OutputStream() {
            override fun write(value: Int) { active(); destination.write(value); active() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { active(); destination.write(bytes, offset, length); active() }
        }
        private fun digest(hash: MessageDigest) = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        private val discard = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }
    }
}
