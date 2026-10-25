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
        assertEquals("REBOUND_TO_TARGET", result["MESSAGE_IDENTITY"])
        // Evidence intentionally retained; no recursive cleanup and no reuse of an existing root.
    }

    @Test fun `foreign messages preserve versions turns and generated-from links under independent target identities`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("isolated-probe-linked-synthetic-")
        val user = SyntheticSaveSlotCorpus.message("a", "Synthetic source user").copy(
            role = com.example.chatbar.data.local.entity.MessageRole.USER)
        val reply = SyntheticSaveSlotCorpus.message("b", "Synthetic source reply").copy(
            generatedFromMessageId = user.id, createdAt = 2, updatedAt = 2,
            alternatives = listOf("older synthetic reply", "Synthetic source reply"),
            alternativeVersionIds = listOf("old-version", "v"), currentAlternativeIndex = 1, currentAlternativeVersionId = "v")
        val result = SlotIsolatedRestoreProbe.run(SyntheticSaveSlotCorpus.archive(parent.resolve("linked.cbsave"),
            messages = listOf(user, reply)), parent)
        assertEquals("COMMITTED", result["ISOLATED_RESTORE"])
        assertEquals("REBOUND_TO_TARGET", result["MESSAGE_IDENTITY"])
        assertEquals("PRESERVED", result["MESSAGE_TEXT_VERSIONS_TURNS"])
        assertEquals("COUNTS_VERIFIED", result["REOPEN_REPOSITORIES"])
        assertEquals("2", result["MESSAGES"])
        assertEquals("UNCHANGED", result["SOURCE_BYTES"])
    }
}
