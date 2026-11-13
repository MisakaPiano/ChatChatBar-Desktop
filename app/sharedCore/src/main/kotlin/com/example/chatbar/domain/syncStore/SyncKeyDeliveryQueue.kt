package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.lanSyncJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Bounded public wrapper; only the root signature and recipient HPKE can authenticate its claims. */
object SyncKeyDeliveryCodec {
    private val magic = "CCB-SYNC-KEY-CAPSULE/1\n".toByteArray(Charsets.US_ASCII)
    const val MAX_BYTES = 128 * 1024
    internal fun hasPrefix(bytes: ByteArray) = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    fun encode(delivery: SyncGroupKeyDelivery): ByteArray {
        val json = lanSyncJson.encodeToString(SyncGroupKeyDelivery.serializer(), delivery).toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_BYTES - magic.size) { "SYNC_KEY_DELIVERY_BUDGET" }
        return magic + json
    }

    /** Discovery hint only; never constructs an admitted key or persists the decoded secret. */
    fun decode(bytes: ByteArray): SyncGroupKeyDelivery {
        require(bytes.size in magic.size + 1..MAX_BYTES && bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "SYNC_KEY_DELIVERY_FORMAT" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes, magic.size, bytes.size - magic.size)).toString()
        // The key wrapper is small machine metadata. Bound nesting before the typed decoder.
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { char ->
            if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
            else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "SYNC_KEY_DELIVERY_DEPTH" } }
                '}', ']' -> { depth--; require(depth >= 0) { "SYNC_KEY_DELIVERY_DEPTH" } }
            }
        }
        require(!quoted && depth == 0) { "SYNC_KEY_DELIVERY_FORMAT" }
        return lanSyncJson.decodeFromString(SyncGroupKeyDelivery.serializer(), text)
    }

    internal fun read(input: InputStream): ByteArray {
        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) return bytes.toByteArray()
            require(bytes.size() <= MAX_BYTES - count) { "SYNC_KEY_DELIVERY_BUDGET" }; bytes.write(buffer, 0, count)
        }
    }

    fun read(backend: CiphertextStore, metadata: CiphertextObjectMetadata, trust: SyncGroupTrust,
        recipient: SyncDeviceEncryptionKey): SyncTentativeGroupKey {
        require(metadata.key.kind == CiphertextObjectKind.COMMIT && metadata.descriptor.length in 1..MAX_BYTES.toLong()) { "SYNC_KEY_DELIVERY_BUDGET" }
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) { require(bytes.size() < MAX_BYTES) { "SYNC_KEY_DELIVERY_BUDGET" }; bytes.write(value) }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                require(length >= 0 && bytes.size() <= MAX_BYTES - length) { "SYNC_KEY_DELIVERY_BUDGET" }; bytes.write(value, offset, length)
            }
        }
        require(backend.readTo(metadata.key, bounded, metadata.descriptor) == metadata) { "SYNC_KEY_DELIVERY_OBJECT_MISSING" }
        val retained = bytes.toByteArray()
        require(descriptor(retained) == metadata.descriptor) { "SYNC_KEY_DELIVERY_OBJECT_CHANGED" }
        return decode(retained).openTentative(trust, recipient, metadata.key)
    }

    internal fun descriptor(bytes: ByteArray) = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
}

data class SyncKeyDeliveryEntry(val delivery: SyncGroupKeyDelivery, val request: CiphertextPutRequest, val uploaded: Boolean)

/** Exact encrypted capsule is forced locally before any remote I/O; no cleartext group key is stored. */
class SyncKeyDeliveryQueue(private val root: Path, private val trust: SyncGroupTrust, private val localDevice: String,
    private val ledger: () -> SyncDeviceLedger, private val observe: (String) -> Unit = {}) {
    private val lock: ReentrantLock
    init {
        PrivateSyncFile.checked(root, true)
        require(localDevice.matches(Regex("[a-f0-9]{64}"))) { "SYNC_KEY_QUEUE_DEVICE" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }

    fun prepare(operation: SyncStoreId, cipher: SyncStreamingCipher, recipient: String,
        sign: (ByteArray) -> ByteArray): SyncKeyDeliveryEntry = guarded {
        val state = checkedLedger(); val current = state.current()
        require(cipher.group == trust.group && state.hasKey(cipher.epoch, cipher.keyCommitment())) { "SYNC_KEY_DELIVERY_KEY_NOT_ADMITTED" }
        val member = requireNotNull(current.members[recipient]?.takeIf { it.active }) { "SYNC_KEY_DELIVERY_RECIPIENT_REVOKED" }
        val expected = SyncKeyDeliveryContext(1, operation, trust.group, cipher.epoch, cipher.keyCommitment(), current.reference(),
            recipient, SyncRecipientPublicKey.parse(member.recipientKey).hash)
        read(operation)?.let {
            require(it.delivery.context == expected) { "SYNC_KEY_QUEUE_OPERATION_CHANGED" }; return@guarded it
        }
        val delivery = SyncGroupKeyDelivery.create(state, cipher, recipient, operation, sign)
        require(delivery.context == expected) { "SYNC_KEY_QUEUE_AUTHORITY_CHANGED" }
        publish(file(operation), SyncKeyDeliveryCodec.encode(delivery))
        observe("KEY_CAPSULE_RETAINED")
        requireNotNull(read(operation))
    }

    /** Inspection survives revocation and does not grant upload/key custody authority. */
    fun entry(operation: SyncStoreId): SyncKeyDeliveryEntry? = guarded { read(operation) }

    fun upload(operation: SyncStoreId, backend: CiphertextStore): CiphertextPutResult = guarded {
        val entry = requireNotNull(read(operation)) { "SYNC_KEY_QUEUE_MISSING" }
        observe("KEY_CAPSULE_UPLOAD_STARTED")
        val state = checkedLedger(); val current = state.current(); val context = entry.delivery.context
        require(context.authorizingLedger == current.reference() && state.hasKey(context.epoch, context.keyCommitment)) { "SYNC_KEY_QUEUE_AUTHORITY_CHANGED" }
        require(current.members[context.recipient]?.let { it.active && SyncRecipientPublicKey.parse(it.recipientKey).hash == context.recipientKeyHash } == true) {
            "SYNC_KEY_DELIVERY_RECIPIENT_REVOKED"
        }
        val result = Files.newInputStream(file(operation), NOFOLLOW_LINKS).use { backend.put(entry.request, it) }
        require(result.metadata == CiphertextObjectMetadata(entry.request.key, entry.request.descriptor)) { "SYNC_KEY_QUEUE_RECEIPT" }
        observe("KEY_CAPSULE_BACKEND_RETURNED")
        if (!entry.uploaded) publish(receipt(operation), entry.request.descriptor.sha256.hex.toByteArray(Charsets.US_ASCII))
        observe("KEY_CAPSULE_UPLOAD_RECORDED")
        result
    }

    private fun checkedLedger(): SyncDeviceLedger = ledger().also { state ->
        require(state.trustAnchor.group == trust.group && state.trustAnchor.genesis == trust.genesis &&
            state.trustAnchor.ownerCertificate == trust.ownerCertificate) { "SYNC_KEY_DELIVERY_TRUST_CHANGED" }
        require(state.current().members[localDevice]?.active == true) { "SYNC_KEY_QUEUE_LOCAL_REVOKED" }
    }
    private fun read(operation: SyncStoreId): SyncKeyDeliveryEntry? {
        val file = file(operation)
        if (!Files.exists(file, NOFOLLOW_LINKS)) {
            require(!Files.exists(receipt(operation), NOFOLLOW_LINKS)) { "SYNC_KEY_QUEUE_RECEIPT" }; return null
        }
        PrivateSyncFile.checked(file)
        require(Files.size(file) in 1..SyncKeyDeliveryCodec.MAX_BYTES.toLong()) { "SYNC_KEY_DELIVERY_BUDGET" }
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use(SyncKeyDeliveryCodec::read)
        val delivery = SyncKeyDeliveryCodec.decode(bytes)
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, operation)
        delivery.verifyOwner(trust, key)
        val descriptor = SyncKeyDeliveryCodec.descriptor(bytes)
        val uploaded = Files.exists(receipt(operation), NOFOLLOW_LINKS)
        if (uploaded) {
            PrivateSyncFile.checked(receipt(operation))
            require(Files.size(receipt(operation)) == 64L && Files.newInputStream(receipt(operation), NOFOLLOW_LINKS).use {
                String(SyncKeyDeliveryCodec.read(it), Charsets.US_ASCII) == descriptor.sha256.hex
            }) { "SYNC_KEY_QUEUE_RECEIPT" }
        }
        return SyncKeyDeliveryEntry(delivery, CiphertextPutRequest(key, operation, descriptor), uploaded)
    }
    private fun file(operation: SyncStoreId) = root.resolve("key-${operation.value}.capsule")
    private fun receipt(operation: SyncStoreId) = root.resolve("key-${operation.value}.uploaded")
    private fun publish(path: Path, bytes: ByteArray) {
        require(!Files.exists(path, NOFOLLOW_LINKS)) { "SYNC_KEY_QUEUE_IMMUTABLE" }
        PrivateSyncFile.create(root).use { preparation ->
            FileChannel.open(preparation.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
            }
            Files.move(preparation.path, path, ATOMIC_MOVE)
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_KEY_QUEUE_REENTRY" }
        lock.lockInterruptibly()
        try {
            PrivateSyncFile.checked(root, true)
            val lockPath = root.resolve("key-queue.lock")
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                PrivateSyncFile.checked(lockPath); channel.lock().use { return work() }
            }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
