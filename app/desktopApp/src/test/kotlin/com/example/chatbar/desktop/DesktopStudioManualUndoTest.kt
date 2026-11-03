package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopStudioManualUndoTest {
    @Test fun `manual prompt parameter and character removal use durable original undo without provider requests`() = runBlocking {
        val parent = Files.createTempDirectory("studio-manual-undo-")
        val root = Files.createDirectory(parent.resolve("data"))
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val c = app.novelAiStudioController
            c.load()
            assertFalse(c.repository.undoAvailable.value)
            val empty = c.draft.value
            assertFalse(c.undo())
            assertEquals(empty, c.draft.value)
            assertEquals("当前没有可撤销的修改", c.state.value.status)
            assertTrue(c.edit { it.copy(basePrompt = "synthetic initial", characters = listOf(NovelAiCharacterPromptDraft(prompt = "synthetic person"))) })
            for (change in listOf<(NovelAiStudioDraft) -> NovelAiStudioDraft>(
                { it.copy(basePrompt = "synthetic edit") },
                { it.withActiveSettings(it.activeSettings.copy(steps = 19)) },
                { it.copy(characters = emptyList()) },
            )) {
                val before = assertNotNull(c.draft.value)
                assertTrue(c.edit(change))
                assertTrue(c.repository.undoAvailable.value)
                val edited = assertNotNull(c.draft.value)
                assertTrue(c.edit { it }) // A no-op must not replace the existing undo checkpoint.
                assertTrue(c.undo())
                assertFalse(c.repository.undoAvailable.value)
                val undone = assertNotNull(c.draft.value)
                assertEquals(before.basePrompt, undone.basePrompt)
                assertEquals(before.activeSettings, undone.activeSettings)
                assertEquals(before.characters, undone.characters)
                assertTrue(c.replace { edited }) // Original redo uses this same normal replacement path.
                assertEquals(edited.basePrompt, c.draft.value!!.basePrompt)
                assertEquals(edited.activeSettings, c.draft.value!!.activeSettings)
                assertEquals(edited.characters, c.draft.value!!.characters)
            }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
