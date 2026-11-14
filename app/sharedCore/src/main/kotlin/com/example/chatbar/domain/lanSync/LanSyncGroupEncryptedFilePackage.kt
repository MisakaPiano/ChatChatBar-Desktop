package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.Serializable

/**
 * A sequential ciphertext container, not a backup of runtime directories or an enrollment receipt.
 * The caller supplies TargetPublication's authenticated complete plan. This codec authenticates its
 * exact framing/inventory with an existing epoch key; root, causal and native admission stay with
 * their existing owners. No code, private key, credential, path or plaintext resource enters it.
 */
object LanSyncGroupEncryptedFilePackage {
    private val MAGIC = "CCB-GROUP-ENCRYPTED-FILE\n".toByteArray(Charsets.US_ASCII)
    private val INVENTORY = "CCB-GROUP-FILE-INVENTORY/1\n".toByteArray(Charsets.US_ASCII)
    private const val FORMAT = 1
    private const val OBJECT = 1
    private const val SEAL = 2
    private const val FOOTER = 3
    private const val HEADER_BYTES = 32 * 1024
    private const val BUFFER = 64 * 1024
    private const val ITEM_COST = 2048L

    /** All fields remain untrusted hints until verifyInventory AND the normal recovery owners. */
    @Serializable data class UnverifiedHeader(val format: Int, val packageId: SyncStoreId,
        val trust: SyncGroupTrust, val checkpoint: SyncVersionRef, val epoch: SyncStoreId,
        val commitment: String, val sealId: SyncStoreId, val objectCount: Long, val ciphertextBytes: Long)

    /** An authenticated container inventory grants no root/daily membership or native READY state. */
    class VerifiedInventory internal constructor(val plan: LanSyncGroupTargetPlan, val trust: SyncGroupTrust)

    class Prepared internal constructor(private val file: PrivateSyncFile,
        val descriptor: CiphertextDescriptor, private val checkActive: () -> Unit) : AutoCloseable {
        @Volatile private var closed = false
        private fun active() { check(!closed) { "SYNC_GROUP_FILE_CLOSED" }; guard(checkActive); PrivateSyncFile.checked(file.path) }
        /** Borrowed destination; a failed sink is propagated once and is never retried. */
        fun writeTo(destination: OutputStream): CiphertextDescriptor {
            active()
            Files.newInputStream(file.path, NOFOLLOW_LINKS).use { verifyReadback(it, descriptor, ::active) }
            Files.newInputStream(file.path, NOFOLLOW_LINKS).use { input ->
                require(transfer(input, destination, descriptor.length, ::active) == descriptor.sha256.hex) { "SYNC_GROUP_FILE_CHANGED" }
                require(input.read() == -1) { "SYNC_GROUP_FILE_CHANGED" }
            }
            active(); return descriptor
        }
        override fun close() { closed = true; file.close() }
        override fun toString() = "PreparedEncryptedGroupFile(ciphertext)"
    }

    class Staged internal constructor(private val directory: Path, private val parent: Path,
        private val directoryIdentity: Any?, private val store: FileCiphertextStore, private val seal: PrivateSyncFile,
        val header: UnverifiedHeader, val descriptor: CiphertextDescriptor,
        private val headerHash: String, private val prefixHash: String,
        private val sealDescriptor: CiphertextDescriptor, private val items: List<LanSyncGroupTargetObject>,
        private val metadataBudget: LanSyncMetadataBudget, private val checkActive: () -> Unit) : AutoCloseable {
        @Volatile private var closed = false
        private val byKey = items.associateBy { it.key }
        private fun active() {
            check(!closed) { "SYNC_GROUP_FILE_CLOSED" }; guard(checkActive); PrivateSyncFile.checked(directory, true)
            require(directoryFileKey(directory) == directoryIdentity) { "SYNC_GROUP_FILE_STAGE_CHANGED" }
        }

        /** Raw descriptor-checked candidates only. No writable store, plaintext path or READY flag. */
        val quarantine: CiphertextStore = object : CiphertextStore {
            override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED,
                untrustedCandidateVariants = true)
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult =
                throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)
            override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
                active(); val item = byKey[key] ?: return null
                val expected = CiphertextObjectMetadata(key, item.descriptor)
                require(store.metadata(key) == expected) { "SYNC_GROUP_FILE_STAGE_CHANGED" }; active(); return expected
            }
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                active(); val item = byKey[key]?.takeIf { expected == null || it.descriptor == expected } ?: return null
                val actual = store.readTo(key, guardedOutput(destination, ::active), item.descriptor)
                require(actual == CiphertextObjectMetadata(key, item.descriptor)) { "SYNC_GROUP_FILE_STAGE_CHANGED" }
                active(); return actual
            }
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                active(); val page = store.listCommits(cursor, pageSize)
                require(page.entries.all { byKey[it.key]?.descriptor == it.descriptor }) { "SYNC_GROUP_FILE_STAGE_CHANGED" }
                active(); return page
            }
            override fun toString() = "EncryptedGroupFileQuarantine(READ_ONLY)"
        }

        /** Requires independently admitted original trust/checkpoint/key, never the header as proof. */
        fun verifyInventory(cipher: SyncStreamingCipher, trust: SyncGroupTrust,
            checkpoint: SyncVersionRef): VerifiedInventory {
            active()
            require(header.trust == trust.copy(checkpoint = checkpoint) && header.checkpoint == checkpoint &&
                cipher.group == trust.group && cipher.epoch == header.epoch && cipher.keyCommitment() == header.commitment) {
                "SYNC_GROUP_FILE_CONTEXT"
            }
            Files.newInputStream(seal.path, NOFOLLOW_LINKS).use { verifyReadback(it, sealDescriptor, ::active) }
            val context = SyncEncryptionContext(trust.group, header.epoch, CiphertextObjectKey(CiphertextObjectKind.BLOB, header.sealId))
            // Existing AEAD owns and erases this invocation-only machine-metadata preparation.
            // User content remains encrypted in quarantine throughout this method.
            val plan = Files.newInputStream(seal.path, NOFOLLOW_LINKS).use { input ->
                cipher.decrypt(guardedInput(input, ::active), context, directory).use { plain ->
                    require(plain.length <= metadataBudget.maximumDecodedCost) { "SYNC_GROUP_FILE_METADATA_BUDGET" }
                    Files.newInputStream(plain.path, NOFOLLOW_LINKS).use { raw ->
                        val data = DataInputStream(guardedInput(raw, ::active))
                        fixed(data, INVENTORY)
                        require(text(data, 32) == header.packageId.value && text(data, 64) == headerHash &&
                            text(data, 64) == prefixHash && data.readLong() == header.objectCount &&
                            data.readLong() == header.ciphertextBytes) { "SYNC_GROUP_FILE_INVENTORY_CHANGED" }
                        val group = SyncStoreId.parse(text(data, 32)); val at = ref(data)
                        val epoch = SyncStoreId.parse(text(data, 32)); val commitment = text(data, 64)
                        val targetCode = data.readUnsignedByte()
                        val kind = SyncDeliveryKind.entries.getOrNull(targetCode) ?: error("SYNC_GROUP_FILE_TARGET")
                        val target = SyncDeliveryTarget(kind, SyncStoreId.parse(text(data, 32)))
                        val versionCount = data.readLong()
                        working(lanSyncAddLength(versionCount, items.size.toLong()), metadataBudget)
                        val versions = linkedSetOf<SyncVersionRef>()
                        repeat(versionCount.toInt()) { require(versions.add(ref(data))) { "SYNC_GROUP_FILE_DUPLICATE_VERSION" } }
                        require(versions.map { it.id }.toSet().size == versions.size && data.readLong() == items.size.toLong()) {
                            "SYNC_GROUP_FILE_INVENTORY_CHANGED"
                        }
                        for (item in items) { active(); require(readItem(data) == item) { "SYNC_GROUP_FILE_INVENTORY_CHANGED" } }
                        require(data.read() == -1) { "SYNC_GROUP_FILE_INVENTORY_TRAILING" }
                        LanSyncGroupTargetPlan(group, at, epoch, commitment, target,
                            Collections.unmodifiableSet(versions), items)
                    }
                }
            }
            validatePlan(plan, trust, cipher, metadataBudget)
            require(plan.checkpoint == checkpoint) { "SYNC_GROUP_FILE_CONTEXT" }
            // Recheck retained raw objects, even if this instance verified its inventory earlier.
            for (item in items) { active(); require(quarantine.metadata(item.key)?.descriptor == item.descriptor) { "SYNC_GROUP_FILE_STAGE_CHANGED" } }
            active(); return VerifiedInventory(plan, trust.copy(checkpoint = checkpoint))
        }

        override fun close() { closed = true; removeOwned(directory, parent, directoryIdentity) }
        override fun toString() = "StagedEncryptedGroupFile(UNVERIFIED_QUARANTINE)"
    }

    fun prepare(plan: LanSyncGroupTargetPlan, source: CiphertextStore, trust: SyncGroupTrust,
        cipher: SyncStreamingCipher, work: Path, checkActive: () -> Unit = {},
        metadataBudget: LanSyncMetadataBudget = LanSyncMetadataBudget()): Prepared {
        PrivateSyncFile.checked(work, true); guard(checkActive)
        val frozen = plan.copy(versions = Collections.unmodifiableSet(plan.versions.toSet()),
            objects = Collections.unmodifiableList(plan.objects.toList()))
        validatePlan(frozen, trust, cipher, metadataBudget)
        val total = frozen.objects.fold(0L) { sum, item -> lanSyncAddLength(sum, item.descriptor.length) }
        LanSyncResourceBudget().requireSpace(work, total, copies = 2)
        val header = UnverifiedHeader(FORMAT, SyncStoreId.random(), trust.copy(checkpoint = frozen.checkpoint),
            frozen.checkpoint, frozen.epoch, frozen.commitment, SyncStoreId.random(), frozen.objects.size.toLong(), total)
        val headerBytes = lanSyncJson.encodeToString(UnverifiedHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        require(headerBytes.size in 1..HEADER_BYTES) { "SYNC_GROUP_FILE_HEADER" }
        val file = PrivateSyncFile.create(work)
        try {
            val whole = MessageDigest.getInstance("SHA-256")
            var length = 0L
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val all = object : OutputStream() {
                    private val destination = Channels.newOutputStream(channel)
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        guard(checkActive); length = lanSyncAddLength(length, count.toLong())
                        destination.write(bytes, offset, count); whole.update(bytes, offset, count)
                    }
                }
                val framing = MessageDigest.getInstance("SHA-256")
                val framed = DigestOutputStream(all, framing)
                val prefix = MessageDigest.getInstance("SHA-256")
                val out = DataOutputStream(DigestOutputStream(framed, prefix))
                out.write(MAGIC); out.writeByte(FORMAT); out.writeInt(headerBytes.size); out.write(headerBytes)
                for (item in frozen.objects) {
                    guard(checkActive); out.writeByte(OBJECT); writeItem(out, item)
                    var count = 0L; val digest = MessageDigest.getInstance("SHA-256")
                    val sink = object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, size: Int) {
                            guard(checkActive); require(size >= 0 && count <= item.descriptor.length - size) { "SYNC_GROUP_FILE_OBJECT_CHANGED" }
                            out.write(bytes, offset, size); digest.update(bytes, offset, size); count += size
                        }
                    }
                    require(source.readTo(item.key, sink, item.descriptor) == CiphertextObjectMetadata(item.key, item.descriptor) &&
                        count == item.descriptor.length && hex(digest.digest()) == item.descriptor.sha256.hex) { "SYNC_GROUP_FILE_OBJECT_CHANGED" }
                }
                val prefixHash = hex(prefix.digest())
                val context = SyncEncryptionContext(trust.group, cipher.epoch, CiphertextObjectKey(CiphertextObjectKind.BLOB, header.sealId))
                fragments(inventory(header, hash(headerBytes), prefixHash, frozen)).use { input ->
                    cipher.encrypt(guardedInput(input, checkActive), context, work).use { sealed ->
                        val tail = DataOutputStream(framed)
                        tail.writeByte(SEAL); descriptor(tail, sealed.descriptor)
                        Files.newInputStream(sealed.path, NOFOLLOW_LINKS).use {
                            require(transfer(it, tail, sealed.descriptor.length, checkActive) == sealed.descriptor.sha256.hex && it.read() == -1) {
                                "SYNC_GROUP_FILE_SEAL_CHANGED"
                            }
                        }
                    }
                }
                val footer = DataOutputStream(all)
                footer.writeByte(FOOTER); footer.writeLong(header.objectCount); footer.writeLong(total)
                footer.write(hex(framing.digest()).toByteArray(Charsets.US_ASCII)); footer.flush(); channel.force(true)
            }
            guard(checkActive)
            return Prepared(file, CiphertextDescriptor(length, CiphertextSha256.parse(hex(whole.digest()))), checkActive)
        } catch (failure: Throwable) {
            try { file.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** A complete raw stage remains quarantine; no plaintext/native source or trust is installed. */
    fun stage(source: InputStream, work: Path, checkActive: () -> Unit = {},
        metadataBudget: LanSyncMetadataBudget = LanSyncMetadataBudget()): Staged {
        PrivateSyncFile.checked(work, true); guard(checkActive)
        val directory = Files.createTempDirectory(work, ".group-file-").toRealPath()
        val directoryIdentity = directoryFileKey(directory)
        try {
            val store = FileCiphertextStore(directory.resolve("ciphertext"))
            val whole = MessageDigest.getInstance("SHA-256"); var length = 0L
            val all = object : InputStream() {
                override fun read(): Int { guard(checkActive); val value = source.read(); if (value >= 0) { length = lanSyncAddLength(length, 1); whole.update(value.toByte()) }; return value }
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    guard(checkActive); val size = source.read(bytes, offset, count)
                    require(count == 0 || size != 0) { "SYNC_GROUP_FILE_STREAM_STALLED" }
                    if (size > 0) { length = lanSyncAddLength(length, size.toLong()); whole.update(bytes, offset, size) }; return size
                }
            }
            val framing = MessageDigest.getInstance("SHA-256"); val framed = DigestInputStream(all, framing)
            val prefix = MessageDigest.getInstance("SHA-256"); val data = DataInputStream(DigestInputStream(framed, prefix))
            fixed(data, MAGIC); require(data.readUnsignedByte() == FORMAT) { "SYNC_GROUP_FILE_FORMAT" }
            val headerSize = data.readInt(); require(headerSize in 1..HEADER_BYTES) { "SYNC_GROUP_FILE_HEADER" }
            val bytes = ByteArray(headerSize).also(data::readFully)
            val header = lanSyncJson.decodeFromString(UnverifiedHeader.serializer(), lanSyncUtf8(bytes))
            require(header.format == FORMAT && header.trust.checkpoint == header.checkpoint &&
                header.commitment.matches(Regex("[a-f0-9]{64}")) && header.trust.ownerCertificate.length in 1..8192 &&
                header.objectCount > 0 && header.ciphertextBytes > 0) { "SYNC_GROUP_FILE_HEADER" }
            working(header.objectCount, metadataBudget)
            LanSyncResourceBudget().requireSpace(work, header.ciphertextBytes, copies = 2)
            val items = ArrayList<LanSyncGroupTargetObject>()
            val keys = hashSetOf<CiphertextObjectKey>(); val operations = hashSetOf<SyncStoreId>(); var total = 0L
            repeat(header.objectCount.toInt()) {
                guard(checkActive); require(data.readUnsignedByte() == OBJECT) { "SYNC_GROUP_FILE_FRAME" }
                val item = readItem(data)
                require(keys.add(item.key) && operations.add(item.operation)) { "SYNC_GROUP_FILE_DUPLICATE_OBJECT" }
                total = lanSyncAddLength(total, item.descriptor.length)
                require(total <= header.ciphertextBytes) { "SYNC_GROUP_FILE_LENGTH" }
                val result = store.put(CiphertextPutRequest(item.key, item.operation, item.descriptor), limited(data, item.descriptor.length))
                require(result.metadata == CiphertextObjectMetadata(item.key, item.descriptor)) { "SYNC_GROUP_FILE_OBJECT_CHANGED" }
                items += item
            }
            require(total == header.ciphertextBytes) { "SYNC_GROUP_FILE_LENGTH" }
            val prefixHash = hex(prefix.digest()); val tail = DataInputStream(framed)
            require(tail.readUnsignedByte() == SEAL) { "SYNC_GROUP_FILE_SEAL_MISSING" }
            val sealDescriptor = descriptor(tail)
            require(sealDescriptor.length <= lanSyncAddLength(metadataBudget.maximumDecodedCost,
                lanSyncAddLength(metadataBudget.maximumDecodedCost / 1024, 8192))) { "SYNC_GROUP_FILE_METADATA_BUDGET" }
            LanSyncResourceBudget().requireSpace(work, sealDescriptor.length, copies = 2)
            val seal = PrivateSyncFile.create(directory)
            FileChannel.open(seal.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                require(transfer(tail, Channels.newOutputStream(channel), sealDescriptor.length, checkActive) == sealDescriptor.sha256.hex) {
                    "SYNC_GROUP_FILE_SEAL_CHANGED"
                }; channel.force(true)
            }
            Files.newInputStream(seal.path, NOFOLLOW_LINKS).use {
                val hint = SyncCiphertextHeader.peek(it)
                require(hint.context == SyncEncryptionContext(header.trust.group, header.epoch,
                    CiphertextObjectKey(CiphertextObjectKind.BLOB, header.sealId)) && hint.keyCommitment == header.commitment) { "SYNC_GROUP_FILE_SEAL_CONTEXT" }
            }
            val framingHash = hex(framing.digest()); val footer = DataInputStream(all)
            require(footer.readUnsignedByte() == FOOTER && footer.readLong() == header.objectCount && footer.readLong() == total &&
                text(footer, 64) == framingHash && footer.read() == -1) { "SYNC_GROUP_FILE_FOOTER" }
            guard(checkActive)
            return Staged(directory, work, directoryIdentity, store, seal, header,
                CiphertextDescriptor(length, CiphertextSha256.parse(hex(whole.digest()))), hash(bytes), prefixHash,
                sealDescriptor, Collections.unmodifiableList(items), metadataBudget, checkActive)
        } catch (failure: Throwable) {
            try { removeOwned(directory, work, directoryIdentity) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Actual external-file readback, not a target receipt or a claim based on a private stage. */
    fun verifyReadback(source: InputStream, expected: CiphertextDescriptor, checkActive: () -> Unit = {}) {
        val discard = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        require(transfer(source, discard, expected.length, checkActive) == expected.sha256.hex && source.read() == -1) {
            "SYNC_GROUP_FILE_READBACK_CHANGED"
        }
        guard(checkActive)
    }

    private fun validatePlan(plan: LanSyncGroupTargetPlan, trust: SyncGroupTrust, cipher: SyncStreamingCipher, budget: LanSyncMetadataBudget) {
        working(lanSyncAddLength(plan.objects.size.toLong(), plan.versions.size.toLong()), budget)
        require(plan.group == trust.group && plan.group == cipher.group && plan.epoch == cipher.epoch &&
            plan.commitment == cipher.keyCommitment() && plan.target != SyncDeliveryTarget.LEGACY &&
            plan.objects.isNotEmpty() && plan.objects.map { it.key }.toSet().size == plan.objects.size &&
            plan.objects.map { it.operation }.toSet().size == plan.objects.size &&
            plan.versions.map { it.id }.toSet().size == plan.versions.size) { "SYNC_GROUP_FILE_PLAN" }
        plan.objects.forEach(::validateItem)
        require(plan.objects.count { it.role == LanSyncGroupTargetObjectRole.VAULT && it.slot == "vault" && it.descriptor.length <= 272L * 1024 } == 1 &&
            plan.objects.count { it.role == LanSyncGroupTargetObjectRole.VAULT } == 1 &&
            plan.objects.count { it.role == LanSyncGroupTargetObjectRole.LEDGER && it.slot == "ledger" } == 1 &&
            plan.objects.count { it.role == LanSyncGroupTargetObjectRole.LEDGER } == 1 &&
            plan.objects.filter { it.role == LanSyncGroupTargetObjectRole.VERSION }.map { it.slot }.toSet() == plan.versions.map { it.id.value }.toSet() &&
            plan.objects.count { it.role == LanSyncGroupTargetObjectRole.VERSION } == plan.versions.size) { "SYNC_GROUP_FILE_PLAN_INCOMPLETE" }
    }
    private fun validateItem(item: LanSyncGroupTargetObject) {
        val slot = when (item.role) {
            LanSyncGroupTargetObjectRole.VAULT -> item.slot == "vault"
            LanSyncGroupTargetObjectRole.LEDGER -> item.slot == "ledger"
            LanSyncGroupTargetObjectRole.RESOURCE -> item.slot == item.key.id.value
            LanSyncGroupTargetObjectRole.VERSION -> item.slot.matches(Regex("[a-f0-9]{32}"))
            LanSyncGroupTargetObjectRole.CAPSULE -> item.slot.matches(Regex("[a-f0-9]{32}:[a-f0-9]{64}"))
            LanSyncGroupTargetObjectRole.NOTICE -> item.slot.matches(Regex("[a-f0-9]{64}"))
        }
        require(slot && item.descriptor.length > 0 &&
            item.key.kind == when (item.role) {
                LanSyncGroupTargetObjectRole.VAULT, LanSyncGroupTargetObjectRole.RESOURCE -> CiphertextObjectKind.BLOB
                else -> CiphertextObjectKind.COMMIT
            }) { "SYNC_GROUP_FILE_OBJECT" }
    }
    private fun inventory(header: UnverifiedHeader, headerHash: String, prefixHash: String, plan: LanSyncGroupTargetPlan): Sequence<ByteArray> = sequence {
        yield(small { write(INVENTORY); ascii(header.packageId.value); ascii(headerHash); ascii(prefixHash)
            writeLong(header.objectCount); writeLong(header.ciphertextBytes); ascii(plan.group.value); ref(this, plan.checkpoint)
            ascii(plan.epoch.value); ascii(plan.commitment); writeByte(plan.target.kind.ordinal); ascii(plan.target.id.value)
            writeLong(plan.versions.size.toLong()) })
        for (value in plan.versions.sortedBy { it.id.value }) yield(small { ref(this, value) })
        yield(small { writeLong(plan.objects.size.toLong()) })
        for (item in plan.objects) yield(small { writeItem(this, item) })
    }
    private fun fragments(chunks: Sequence<ByteArray>): InputStream = object : InputStream() {
        private val iterator = chunks.iterator(); private var bytes = ByteArray(0); private var index = 0
        override fun read(): Int { while (index == bytes.size) { if (!iterator.hasNext()) return -1; bytes = iterator.next(); index = 0 }; return bytes[index++].toInt() and 255 }
        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (index == bytes.size) { if (!iterator.hasNext()) return -1; bytes = iterator.next(); index = 0 }
            val size = minOf(length, bytes.size - index); bytes.copyInto(destination, offset, index, index + size); index += size; return size
        }
    }
    private fun small(work: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).work() }.toByteArray()
    private fun DataOutputStream.ascii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
    private fun ref(out: DataOutputStream, value: SyncVersionRef) { out.ascii(value.id.value); out.ascii(value.bodyHash) }
    private fun ref(input: DataInputStream) = SyncVersionRef(SyncStoreId.parse(text(input, 32)), text(input, 64))
    private fun descriptor(out: DataOutputStream, value: CiphertextDescriptor) { out.writeLong(value.length); out.ascii(value.sha256.hex) }
    private fun descriptor(input: DataInputStream): CiphertextDescriptor {
        val length = input.readLong(); require(length > 0) { "SYNC_GROUP_FILE_LENGTH" }
        return CiphertextDescriptor(length, CiphertextSha256.parse(text(input, 64)))
    }
    private fun writeItem(out: DataOutputStream, item: LanSyncGroupTargetObject) {
        out.writeByte(item.role.ordinal); out.writeShort(item.slot.length); out.ascii(item.slot)
        out.writeByte(if (item.key.kind == CiphertextObjectKind.BLOB) 1 else 2); out.ascii(item.key.id.value)
        descriptor(out, item.descriptor); out.ascii(item.operation.value)
    }
    private fun readItem(input: DataInputStream): LanSyncGroupTargetObject {
        val role = LanSyncGroupTargetObjectRole.entries.getOrNull(input.readUnsignedByte()) ?: error("SYNC_GROUP_FILE_ROLE")
        val size = input.readUnsignedShort(); require(size in 1..256) { "SYNC_GROUP_FILE_SLOT" }
        val slot = text(input, size)
        val kind = when (input.readUnsignedByte()) { 1 -> CiphertextObjectKind.BLOB; 2 -> CiphertextObjectKind.COMMIT; else -> error("SYNC_GROUP_FILE_KIND") }
        val key = CiphertextObjectKey(kind, SyncStoreId.parse(text(input, 32)))
        return LanSyncGroupTargetObject(role, slot, key, descriptor(input), SyncStoreId.parse(text(input, 32))).also(::validateItem)
    }
    private fun text(input: DataInputStream, length: Int): String = ByteArray(length).also(input::readFully).let {
        require(it.all { byte -> byte.toInt() in 33..126 }) { "SYNC_GROUP_FILE_ASCII" }; String(it, Charsets.US_ASCII)
    }
    private fun fixed(input: DataInputStream, expected: ByteArray) { require(ByteArray(expected.size).also(input::readFully).contentEquals(expected)) { "SYNC_GROUP_FILE_FORMAT" } }
    private fun working(count: Long, budget: LanSyncMetadataBudget) {
        require(count >= 0 && count <= Int.MAX_VALUE && count <= budget.maximumDecodedCost / ITEM_COST) { "SYNC_GROUP_FILE_METADATA_BUDGET" }
    }
    private fun limited(input: InputStream, length: Long): InputStream = object : InputStream() {
        private var remaining = length
        override fun read(): Int { if (remaining == 0L) return -1; val value = input.read(); require(value >= 0) { "SYNC_GROUP_FILE_TRUNCATED" }; remaining--; return value }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0; if (remaining == 0L) return -1
            val size = input.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
            require(size > 0) { "SYNC_GROUP_FILE_TRUNCATED" }; remaining -= size; return size
        }
    }
    private fun transfer(input: InputStream, output: OutputStream, length: Long, active: () -> Unit): String {
        require(length >= 0) { "SYNC_GROUP_FILE_LENGTH" }; val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER); var remaining = length
        while (remaining > 0) {
            guard(active); val size = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(size > 0) { "SYNC_GROUP_FILE_TRUNCATED" }; output.write(buffer, 0, size); digest.update(buffer, 0, size); remaining -= size
        }
        guard(active); return hex(digest.digest())
    }
    private fun guardedOutput(output: OutputStream, active: () -> Unit) = object : OutputStream() {
        override fun write(value: Int) { guard(active); output.write(value); guard(active) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { guard(active); output.write(bytes, offset, length); guard(active) }
    }
    private fun guardedInput(input: InputStream, active: () -> Unit) = object : InputStream() {
        override fun read(): Int { guard(active); return input.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { guard(active); return input.read(bytes, offset, length) }
    }
    private fun guard(active: () -> Unit) { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_GROUP_FILE_INTERRUPTED"); active() }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun directoryFileKey(directory: Path) = Files.readAttributes(directory,
        java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    private fun removeOwned(directory: Path, parent: Path, identity: Any?) {
        require(directory.parent == parent && directory.fileName.toString().startsWith(".group-file-")) { "SYNC_GROUP_FILE_STAGE_PATH" }
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        PrivateSyncFile.checked(directory, true)
        require(directoryFileKey(directory) == identity) { "SYNC_GROUP_FILE_STAGE_CHANGED" }
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
            require(it.startsWith(directory)); PrivateSyncFile.checked(it, Files.isDirectory(it, NOFOLLOW_LINKS)); Files.delete(it)
        } }
    }
}
