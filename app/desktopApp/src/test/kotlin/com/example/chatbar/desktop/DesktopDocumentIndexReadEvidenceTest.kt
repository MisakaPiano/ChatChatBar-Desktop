package com.example.chatbar.desktop

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopDocumentIndexReadEvidenceTest {
    @Test fun `native read binds bytes identity and link count to its own handle`() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("ccb-g2-read-")
        val file = root.resolve("synthetic.json")
        val bytes = "{\"synthetic\":true}".toByteArray()
        Files.write(file, bytes)
        var closed = 0
        val reader = DesktopDocumentIndexReadEvidence({}) { if (it == DesktopDocumentIndexReadEvidence.Step.CLOSED) closed++ }
        val before = reader.observe(file, false)
        assertEquals(1L, before.links)
        val actual = ArrayList<Byte>()
        val read = reader.read(file, before) { buffer, count -> for (i in 0 until count) actual += buffer[i] }
        assertEquals(bytes.toList(), actual)
        assertEquals(JFileEvidence(bytes.size.toLong(), jSha(bytes)), read.file)
        assertEquals(before, read.stamp)
        assertEquals(2, closed)
        assertContentEquals(bytes, Files.readAllBytes(file))
        // Synthetic fixture is retained for this validation's zero-write inspection.
    }
}
