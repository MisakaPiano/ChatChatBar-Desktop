package com.example.chatbar.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class SlotIsolatedRestoreProbeTest {
    @Test fun `synthetic archive commits only in a new isolated root and reopens`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("isolated-probe-synthetic-")
        val source = SyntheticSaveSlotCorpus.archive(parent.resolve("synthetic.cbsave"))
        val result = SlotIsolatedRestoreProbe.run(source, parent)
        assertEquals("COMMITTED", result["ISOLATED_RESTORE"])
        assertEquals("COUNTS_VERIFIED", result["REOPEN_REPOSITORIES"])
        assertEquals("UNCHANGED", result["SOURCE_BYTES"])
        assertEquals("1", result["MESSAGES"])
        assertEquals("NOT_USED", result["PROVIDER_SECRETSTORE_GUI"])
        // Evidence intentionally retained; no recursive cleanup and no reuse of an existing root.
    }
}
