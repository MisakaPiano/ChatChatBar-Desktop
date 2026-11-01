package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.desktopMergeFailureDetails
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class DesktopBackupSinkFailureTest {
    private enum class Target { ENTITY, PREFERENCES, MEDIA }
    private class Sink(private val target: Target? = null, private val fail: () -> Unit = {}) : DesktopFullBackupSource.TentativeSink {
        override fun entity(path: String, original: JsonObject, raw: ByteArray) { if (target == Target.ENTITY) fail() }
        override fun preferences(original: JsonObject) { if (target == Target.PREFERENCES) fail() }
        override fun media(path: String, input: InputStream) { if (target == Target.MEDIA) fail() }
    }

    @Test fun localAccessDeniedRetainsItsCategoryAndFirstCodeLocationAcrossBothEnvelopeVersions() = runBlocking<Unit> {
        for (version in 1..2) for (encrypted in listOf(false, true)) {
            val bytes = CensusWireFixtures.bytes(version = version, code = if (version == 1) 84 else 86,
                encrypted = encrypted, files = CensusWireFixtures.files() + ("images/synthetic.bin" to byteArrayOf(1, 2, 3)))
            CensusWireFixtures.input(bytes, "sink-denied-$version-$encrypted") { source ->
                val password = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                try {
                    assertEquals(5, DesktopFullBackupSource.verify(source, password, Sink()).inventory.size)
                    for (target in Target.entries) {
                        val failure = assertFailsWith<DesktopFullBackupSource.Refusal> {
                            DesktopFullBackupSource.verify(source, password, Sink(target, ::denyLocalWrite))
                        }
                        assertEquals("ZIP_CONTENT_SINK_ACCESS_DENIED", failure.stage)
                        assertEquals(encrypted, failure.encrypted)
                        assertFalse(failure.passwordRequired)
                        assertEquals(AccessDeniedException::class.java.name, failure.failureLocations.first())
                        assertTrue(failure.failureLocations.any { it.contains("DesktopBackupSinkFailureTest.denyLocalWrite:") })
                        assertTrue(failure.failureLocations.size <= 9)
                        assertNull(failure.cause)
                        assertTrue(failure.suppressed.isEmpty())
                        val details = desktopMergeFailureDetails(failure)
                        assertTrue(details.contains("SINK_ACCESS_DENIED"))
                        assertTrue(failure.userMessage().contains("本机"))
                        assertFalse(failure.userMessage().contains("数据结构"))
                        assertNoPrivateData(failure.toString() + details + failure.userMessage(), source.toString())
                        assertContentEquals(bytes, Files.readAllBytes(source))
                    }
                } finally { password?.fill('\u0000') }
            }
        }
    }

    @Test fun localIoAndSecurityFailuresAreNotReportedAsSchemaOrPasswordFailures() = runBlocking<Unit> {
        CensusWireFixtures.input(CensusWireFixtures.bytes(), "sink-other-io") { source ->
            for ((category, throwFailure) in listOf(
                "IO" to { throw IOException(PRIVATE_ERROR) },
                "IO" to { throw java.nio.file.FileSystemException(PRIVATE_PATH, null, "No space left on device $PRIVATE_ERROR") },
                "SECURITY" to { throw SecurityException(PRIVATE_ERROR) })) {
                val failure = assertFailsWith<DesktopFullBackupSource.Refusal> {
                    DesktopFullBackupSource.verify(source, null, Sink(Target.ENTITY, throwFailure))
                }
                assertEquals("ZIP_CONTENT_SINK_$category", failure.stage)
                assertFalse(failure.userMessage().contains("数据结构"))
                assertFalse(failure.userMessage().contains("密码"))
                assertNoPrivateData(failure.toString() + desktopMergeFailureDetails(failure) + failure.userMessage(), source.toString())
            }
        }
    }

    @Test fun sourceStreamFailureWrappedByMediaConsumerIsNotMisreportedAsALocalSinkFailure() = runBlocking<Unit> {
        val files = mapOf("images/synthetic.bin" to ByteArray(256) { it.toByte() })
        val bytes = CensusWireFixtures.bytes(files = files)
        val headerLength = java.nio.ByteBuffer.wrap(bytes, 8, 4).int
        val localHeader = 12 + headerLength
        fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        val data = localHeader + 30 + u16(localHeader + 26) + u16(localHeader + 28)
        // Invalid DEFLATE block type in the first media entry, leaving the envelope intact.
        bytes[data] = ((bytes[data].toInt() and 0xf8) or 7).toByte()
        CensusWireFixtures.input(bytes, "sink-source-stream-failure") { source ->
            val sink = object : DesktopFullBackupSource.TentativeSink {
                override fun entity(path: String, original: JsonObject, raw: ByteArray) {}
                override fun preferences(original: JsonObject) {}
                override fun media(path: String, input: InputStream) {
                    try { input.readBytes() }
                    catch (failure: Exception) { throw IOException(PRIVATE_ERROR, failure) }
                    fail("The damaged source must fail while the consumer reads it")
                }
            }
            val failure = assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(source, null, sink) }
            assertEquals("ZIP_CONTENT_ENTRY", failure.stage)
            assertFalse(failure.stage.contains("SINK_"))
            assertNull(failure.cause)
            assertNoPrivateData(failure.toString() + desktopMergeFailureDetails(failure), source.toString())
        }
    }

    @Test fun sinkCancellationStillPropagatesAndRealSchemaRefusalsStaySchemaRefusals() = runBlocking<Unit> {
        CensusWireFixtures.input(CensusWireFixtures.bytes(), "sink-cancel") { source ->
            val cancelled = CancellationException(PRIVATE_ERROR)
            assertSame(cancelled, assertFailsWith<CancellationException> {
                DesktopFullBackupSource.verify(source, null, Sink(Target.ENTITY) { throw cancelled })
            })
        }
        val files = mapOf("entities/model_configs/m.json" to CensusWireFixtures.model(extra = ",\"unsupportedField\":true").toByteArray())
        CensusWireFixtures.input(CensusWireFixtures.bytes(files = files), "sink-real-schema") { source ->
            var invoked = false
            val failure = assertFailsWith<DesktopFullBackupSource.Refusal> {
                DesktopFullBackupSource.verify(source, null, Sink(Target.ENTITY) { invoked = true; denyLocalWrite() })
            }
            assertFalse(invoked)
            assertEquals("ZIP_CONTENT_ENTITY_SCHEMA", failure.stage)
            assertTrue(failure.userMessage().contains("数据结构"))
        }
    }

    private fun denyLocalWrite(): Nothing = throw AccessDeniedException(PRIVATE_PATH, null, PRIVATE_ERROR)
    private fun assertNoPrivateData(value: String, source: String) {
        for (secret in listOf(PRIVATE_PATH, PRIVATE_ERROR, source, CensusWireFixtures.SECRET, CensusWireFixtures.PASSWORD))
            assertFalse(value.contains(secret))
    }
    private companion object {
        const val PRIVATE_ERROR = "SYNTHETIC_PRIVATE_DISK_FAILURE"
        const val PRIVATE_PATH = "Z:/synthetic-private-location/secret-file"
    }
}
