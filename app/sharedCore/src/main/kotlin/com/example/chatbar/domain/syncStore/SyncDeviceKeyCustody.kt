package com.example.chatbar.domain.syncStore

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Separate private root; callers create only during explicit group enrollment, then open by pinned public key. */
class SyncDeviceKeyCustody(private val root: Path, private val device: String,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray) {
    private val lock: ReentrantLock
    private val file get() = root.resolve("recipient.protected")
    init {
        PrivateSyncFile.checked(root, true)
        require(device.matches(Regex("[a-f0-9]{64}"))) { "SYNC_HPKE_DEVICE" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }

    /** Existing damaged/inaccessible custody always refuses; it is never silently replaced. */
    fun createIfAbsent(): SyncDeviceEncryptionKey = guarded {
        if (Files.exists(file, NOFOLLOW_LINKS)) return@guarded read(null)
        val key = SyncDeviceEncryptionKey.create(device)
        val protected = key.protectedKeyset(protect)
        // Validate the actual protection boundary before an enrollment can advertise this public key.
        val reopened = SyncDeviceEncryptionKey.open(device, protected, unprotect)
        require(reopened.publicKey.encoded == key.publicKey.encoded) { "SYNC_HPKE_CUSTODY_CHANGED" }
        PrivateSyncFile.create(root).use { preparation ->
            FileChannel.open(preparation.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.wrap(protected); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true)
            }
            Files.move(preparation.path, file, ATOMIC_MOVE)
        }
        read(key.publicKey.encoded)
    }

    /** Missing custody after enrollment is a recovery condition, never permission to generate another key. */
    fun open(expectedPublicKey: String): SyncDeviceEncryptionKey = guarded {
        require(SyncRecipientPublicKey.parse(expectedPublicKey).encoded == expectedPublicKey) { "SYNC_HPKE_CUSTODY_PIN" }
        require(Files.exists(file, NOFOLLOW_LINKS)) { "SYNC_HPKE_CUSTODY_MISSING" }
        read(expectedPublicKey)
    }

    private fun read(expected: String?): SyncDeviceEncryptionKey {
        PrivateSyncFile.checked(file)
        require(Files.size(file) in 1..64 * 1024L) { "SYNC_HPKE_CUSTODY_BUDGET" }
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
            val retained = SyncKeyDeliveryCodec.read(input)
            require(retained.size in 1..64 * 1024) { "SYNC_HPKE_CUSTODY_BUDGET" }; retained
        }
        val key = SyncDeviceEncryptionKey.open(device, bytes, unprotect)
        require(expected == null || key.publicKey.encoded == expected) { "SYNC_HPKE_CUSTODY_PIN" }
        return key
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_HPKE_CUSTODY_REENTRY" }
        lock.lockInterruptibly()
        try {
            PrivateSyncFile.checked(root, true)
            val lockPath = root.resolve("recipient.lock")
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                PrivateSyncFile.checked(lockPath); channel.lock().use { return work() }
            }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
