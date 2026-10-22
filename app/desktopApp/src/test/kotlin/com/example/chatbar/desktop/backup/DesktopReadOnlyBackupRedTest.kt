package com.example.chatbar.desktop.backup

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

/** Baseline RED: the selected official v2 envelope is not accepted by the old reader. */
class DesktopReadOnlyBackupRedTest {
    @Test fun `official v2 envelope requires separate read only admission`() {
        val output = ByteArrayOutputStream()
        Official147FixtureCodec.encrypting(output, null, version = 2).use { it.write(byteArrayOf(1, 2, 3)) }
        assertFails { AppBackupCodec.decrypting(output.toByteArray().inputStream(), null).use { it.readBytes() } }
        assertContentEquals(byteArrayOf(1, 2, 3), AppBackupCodec.decryptingForInspection(output.toByteArray().inputStream(), null) { _, _ -> }.use { it.readBytes() })
    }
}
