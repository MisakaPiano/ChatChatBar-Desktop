package com.example.chatbar.domain.syncStore

import java.nio.file.Files
import kotlin.test.*

/** Synthetic protection only; platform tests separately exercise their real wrapping owners. */
class SyncDeviceKeyCustodyTest {
    private val device = "a".repeat(64)
    @Test fun exactRecipientKeySurvivesFreshCustodyOwnersAndLostReopenReplies() {
        val root = Files.createTempDirectory("synthetic-recipient-custody").toRealPath()
        fun custody() = SyncDeviceKeyCustody(root, device, { it.reversedArray() }, { it.reversedArray() })
        val first = custody().createIfAbsent()
        val retained = Files.readAllBytes(root.resolve("recipient.protected"))
        assertEquals(first.publicKey.encoded, custody().createIfAbsent().publicKey.encoded)
        assertEquals(first.publicKey.encoded, custody().open(first.publicKey.encoded).publicKey.encoded)
        val context = "synthetic recipient reopen".toByteArray(); val secret = ByteArray(321) { (it % 127).toByte() }
        assertContentEquals(secret, custody().open(first.publicKey.encoded).decrypt(first.publicKey.encrypt(secret, context), context))
        assertContentEquals(retained, Files.readAllBytes(root.resolve("recipient.protected")))
    }

    @Test fun damagedMissingWrongDeviceOrWrongPinnedKeyNeverRegeneratesExistingCustody() {
        val root = Files.createTempDirectory("synthetic-recipient-damage").toRealPath()
        val custody = SyncDeviceKeyCustody(root, device, { it.reversedArray() }, { it.reversedArray() })
        val first = custody.createIfAbsent(); val path = root.resolve("recipient.protected")
        assertEquals("SYNC_HPKE_CUSTODY_PIN", assertFailsWith<IllegalArgumentException> {
            custody.open(SyncDeviceEncryptionKey.create(device).publicKey.encoded)
        }.message)
        assertEquals("SYNC_HPKE_DEVICE", assertFailsWith<IllegalArgumentException> {
            SyncDeviceKeyCustody(root, "b".repeat(64), { it.reversedArray() }, { it.reversedArray() }).createIfAbsent()
        }.message)
        Files.write(path, byteArrayOf(1, 2, 3))
        assertFails { custody.createIfAbsent() }; assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(path))
        Files.delete(path)
        assertEquals("SYNC_HPKE_CUSTODY_MISSING", assertFailsWith<IllegalArgumentException> { custody.open(first.publicKey.encoded) }.message)
        assertFalse(Files.exists(path))
    }
}
