package com.example.chatbar.desktop.backup

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test
import kotlin.test.*

class LargeArchiveAdversarialTest {
    @Test fun `central local filename discrepancy cannot publish`() {
        val bytes = CensusWireFixtures.bytes(prefs = "{}", files = emptyMap())
        val central = bytes.indices.first { it + 4 <= bytes.size && bytes[it] == 0x50.toByte() &&
            bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[central + 46] = 'X'.code.toByte()
        val out = Path.of("build/p11-lr2/synthetic")
        Files.createDirectories(out); Files.write(out.resolve("central-name-mismatch.cbbackup"), bytes)
        assertFails { StreamingContainerProbe.consume(bytes.inputStream(), null) }
    }
}
