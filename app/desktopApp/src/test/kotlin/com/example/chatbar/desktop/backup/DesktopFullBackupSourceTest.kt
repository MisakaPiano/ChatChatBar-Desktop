package com.example.chatbar.desktop.backup

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class DesktopFullBackupSourceTest {
    @Test fun encryptedBackupWithoutValidLengthPasswordHasTypedRetryableRequirement() = runBlocking<Unit> {
        CensusWireFixtures.input(CensusWireFixtures.bytes(version = 2, code = 86, encrypted = true), "password-required") { source ->
            for (password in listOf(null, "short".toCharArray())) {
                try {
                    val failure = assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(source, password, Sink()) }
                    assertTrue(failure.passwordRequired)
                    assertNull(failure.cause)
                    assertFalse(failure.toString().contains(CensusWireFixtures.SECRET))
                } finally { password?.fill('\u0000') }
            }
            val password = CensusWireFixtures.PASSWORD.toCharArray()
            try { assertEquals(4, DesktopFullBackupSource.verify(source, password, Sink()).inventory.size) }
            finally { password.fill('\u0000') }
        }
    }

    @Test fun outerReadFailureRetainsCodeLocationWithoutRetainingTheOriginalExceptionOrPayload() = runBlocking<Unit> {
        CensusWireFixtures.input("SYNTHETIC_PRIVATE_SOURCE_CONTENT".toByteArray(), "bad-envelope") { source ->
            val failure = assertFailsWith<DesktopFullBackupSource.Refusal> {
                DesktopFullBackupSource.verify(source, null, Sink())
            }
            assertEquals("AUTHENTICATION_ZIP", failure.stage)
            assertNull(failure.cause)
            assertTrue(failure.failureLocations.any { it == "java.lang.IllegalArgumentException" })
            assertTrue(failure.failureLocations.any { it.contains("AppBackupCodec.decrypting:") })
            val details = com.example.chatbar.desktop.desktopMergeFailureDetails(failure)
            assertTrue(details.contains("stage=AUTHENTICATION_ZIP"))
            assertFalse(details.contains("SYNTHETIC_PRIVATE_SOURCE_CONTENT"))
            assertFalse(details.contains(source.toString()))
        }
    }

    @Test fun archiveCannotSupplyLocalRetainedPreferenceInstructions() = runBlocking<Unit> {
        for (retained in listOf("novelai_credentials", "future_preferences")) {
            val bytes = CensusWireFixtures.bytes(version = 2, code = 86, files = emptyMap(),
                prefs = """{"retainedPreferences":["$retained"]}""")
            CensusWireFixtures.input(bytes, "stream-local-instruction-$retained") { source ->
                val sink = Sink()
                val error = assertFailsWith<DesktopFullBackupSource.Refusal> {
                    DesktopFullBackupSource.verify(source, null, sink)
                }
                // Strict ZIP deliberately sanitizes callback failures rather than exposing JSON.
                assertEquals("ZIP_ENTRY_CALLBACK_OR_STREAM", error.stage)
                assertEquals(0, sink.preferences)
            }
        }
    }

    private class Sink : DesktopFullBackupSource.TentativeSink {
        val entities = mutableListOf<String>()
        var preferences = 0
        var media = 0
        override fun entity(path: String, original: kotlinx.serialization.json.JsonObject, raw: ByteArray) { entities += path }
        override fun preferences(original: kotlinx.serialization.json.JsonObject) { preferences++ }
        override fun media(path: String, input: InputStream) { media++ /* Deliberately leave bytes unread. */ }
    }

    @Test fun authenticatesPlainAndPasswordV1V2WithExactSourceIdentity() = runBlocking<Unit> {
        for (version in 1..2) for (encrypted in listOf(false, true)) {
            val bytes = CensusWireFixtures.bytes(version = version, code = if (version == 1) 84 else 86, encrypted = encrypted)
            CensusWireFixtures.input(bytes, "stream-$version-$encrypted") { source ->
                val password = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                try {
                    val sink = Sink()
                    val verified = DesktopFullBackupSource.verify(source, password, sink)
                    assertEquals(CensusWireFixtures.digest(bytes), verified.sourceHash)
                    assertEquals(bytes.size.toLong(), verified.sourceBytes)
                    assertEquals(encrypted, verified.encrypted)
                    assertEquals(version, verified.manifest.formatVersion)
                    assertEquals(4, sink.entities.size)
                    assertEquals(1, sink.preferences)
                    assertEquals(4, verified.inventory.size)
                    assertFalse(verified.toString().contains(CensusWireFixtures.SECRET))
                } finally { password?.fill('\u0000') }
            }
        }
    }

    @Test fun unreadMediaStillParticipatesInHashInventoryAndCrc() = runBlocking<Unit> {
        val payload = ByteArray(90000) { (it * 37).toByte() }
        val files = CensusWireFixtures.files() + ("images/synthetic.bin" to payload)
        CensusWireFixtures.input(CensusWireFixtures.bytes(files = files), "stream-unread-media") { source ->
            val sink = Sink()
            val verified = DesktopFullBackupSource.verify(source, null, sink)
            assertEquals(1, sink.media)
            assertEquals(CensusWireFixtures.digest(payload), verified.inventory.getValue("images/synthetic.bin").sha256)
            assertEquals(payload.size.toLong(), verified.inventory.getValue("images/synthetic.bin").size)
        }
    }

    @Test fun lateManifestAndInventoryFailureNeverReturnsVerifiedDespiteTentativeCallbacks() = runBlocking<Unit> {
        for (bytes in listOf(CensusWireFixtures.bytes(badInventory = true), CensusWireFixtures.bytes(badManifest = true))) {
            CensusWireFixtures.input(bytes, "stream-late-${CensusWireFixtures.digest(bytes).take(8)}") { source ->
                val sink = Sink()
                val failure = assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(source, null, sink) }
                assertEquals("MANIFEST_INVENTORY", failure.stage)
                assertEquals(4, sink.entities.size) // Explicitly untrusted, never an activation callback.
                assertNull(failure.cause)
                assertFalse(failure.toString().contains(CensusWireFixtures.SECRET))
            }
        }
    }

    @Test fun unknownEntityFieldsFamiliesAndInvalidUtf8FailBeforeVerification() = runBlocking<Unit> {
        val variants = listOf(
            mapOf("entities/model_configs/m.json" to CensusWireFixtures.model(extra = ",\"future\":true").toByteArray()),
            mapOf("entities/future/m.json" to "{}".toByteArray()),
            mapOf("entities/app_settings.json" to byteArrayOf(0x7b, 0xff.toByte(), 0x7d)),
            mapOf("entities/app_settings.json" to "{\"language\":\"en\",\"language\":\"zh\"}".toByteArray())
        )
        variants.forEachIndexed { index, files ->
            CensusWireFixtures.input(CensusWireFixtures.bytes(files = files), "stream-schema-$index") { source ->
                assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(source, null, Sink()) }
            }
        }
    }

    @Test fun wrongPasswordTruncationAndAuthenticationDamageRefuseWithoutSecretsOrCause() = runBlocking<Unit> {
        val original = CensusWireFixtures.bytes(encrypted = true)
        val variants = listOf(original to "invented-wrong-password", original.copyOf(original.size - 20) to CensusWireFixtures.PASSWORD,
            original.copyOf().also { it[it.lastIndex - 3] = (it[it.lastIndex - 3].toInt() xor 1).toByte() } to CensusWireFixtures.PASSWORD)
        variants.forEachIndexed { index, (bytes, value) ->
            CensusWireFixtures.input(bytes, "stream-auth-$index") { source ->
                val password = value.toCharArray()
                try {
                    val error = assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(source, password, Sink()) }
                    assertNull(error.cause)
                    assertFalse(error.toString().contains(value))
                    assertFalse(error.toString().contains(CensusWireFixtures.SECRET))
                } finally { password.fill('\u0000') }
            }
        }
    }

    @Test fun cancellationIsNotConvertedToAnArchiveFailure() = runBlocking<Unit> {
        CensusWireFixtures.input(CensusWireFixtures.bytes(), "stream-cancel") { source ->
            var checks = 0
            assertFailsWith<CancellationException> {
                DesktopFullBackupSource.verify(source, null, Sink()) { if (++checks >= 50) throw CancellationException("synthetic-cancel") }
            }
        }
    }

    @Test fun sourceLeasePreventsConcurrentOverwriteAndNoDecryptedArchiveIsCreated() = runBlocking<Unit> {
        val bytes = CensusWireFixtures.bytes()
        CensusWireFixtures.input(bytes, "stream-lease") { source ->
            val sink = object : DesktopFullBackupSource.TentativeSink {
                override fun entity(path: String, original: kotlinx.serialization.json.JsonObject, raw: ByteArray) {
                    assertFailsWith<java.nio.file.FileSystemException> { Files.write(source, byteArrayOf(0)) }
                }
                override fun preferences(original: kotlinx.serialization.json.JsonObject) {}
                override fun media(path: String, input: InputStream) {}
            }
            DesktopFullBackupSource.verify(source, null, sink)
            Files.list(source.parent).use { assertEquals(listOf(source), it.toList()) }
            assertContentEquals(bytes, Files.readAllBytes(source))
        }
    }

    @Test fun sourceRefusesV1WithV2ManifestScopeAndFutureVersion() = runBlocking<Unit> {
        for (bytes in listOf(CensusWireFixtures.bytes(version = 2, code = 84),
            CensusWireFixtures.bytes(code = 87), CensusWireFixtures.bytes(manifestExtra = ",\"scope\":null"))) {
            CensusWireFixtures.input(bytes, "stream-version-${CensusWireFixtures.digest(bytes).take(8)}") { source ->
                assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(source, null, Sink()) }
            }
        }
    }
}
