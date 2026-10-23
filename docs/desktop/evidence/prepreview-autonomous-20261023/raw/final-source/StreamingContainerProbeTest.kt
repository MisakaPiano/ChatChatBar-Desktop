package com.example.chatbar.desktop.backup

import org.junit.Test
import kotlin.test.*

class StreamingContainerProbeTest {
    @Test fun `independent official plain and encrypted v1 v2 inventories`() {
        for (version in 1..2) for (encrypted in listOf(false, true)) {
            val bytes = CensusWireFixtures.bytes(files = mapOf("entities/app_settings.json" to "{}".toByteArray()),
                version = version, code = if (version == 1) 84 else 86, encrypted = encrypted)
            val r = StreamingContainerProbe.consume(bytes.inputStream(), if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null)
            assertEquals(1, r.fileCount); assertEquals(version, r.format)
            assertEquals(mapOf("app_settings" to 1), r.entityCounts)
            assertEquals(version == 2, r.selective)
            assertFalse(r.toString().contains(CensusWireFixtures.SECRET))
        }
    }
    @Test fun `bad inventory manifest and unknown fields cannot publish`() {
        for (bytes in listOf(CensusWireFixtures.bytes(badInventory = true), CensusWireFixtures.bytes(badManifest = true),
            CensusWireFixtures.bytes(manifestExtra = ",\"future\":1"), CensusWireFixtures.bytes(manifestExtra = ",\"fileCount\":1"))) {
            assertFails { StreamingContainerProbe.consume(bytes.inputStream(), null) }
        }
    }
    @Test fun `truncated final authentication and wrong password cannot publish`() {
        val bytes = CensusWireFixtures.bytes(encrypted = true)
        assertFails { StreamingContainerProbe.consume(bytes.copyOf(bytes.size - 1).inputStream(), CensusWireFixtures.PASSWORD.toCharArray()) }
        assertFails { StreamingContainerProbe.consume(bytes.inputStream(), "SYNTHETIC_WRONG_PASSWORD".toCharArray()) }
    }
    @Test fun `unknown entities remain explicitly unknown without private type names`() {
        val bytes = CensusWireFixtures.bytes(files = mapOf("entities/PRIVATE_SENTINEL/a.json" to "{}".toByteArray()))
        val r = StreamingContainerProbe.consume(bytes.inputStream(), null)
        assertEquals(1, r.unknownEntityFiles); assertTrue(r.entityCounts.isEmpty())
        assertFalse(r.toString().contains("PRIVATE_SENTINEL"))
    }
}
