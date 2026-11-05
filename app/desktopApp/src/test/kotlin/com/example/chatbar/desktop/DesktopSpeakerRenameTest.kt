package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SpeakerTagHistoryService
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSpeakerRenameTest {
    private fun app(root: Path) = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, root.parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
            require(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
        })
    private suspend fun seed(app: DesktopAppContainer) {
        app.characterRepository.save(CharacterCard("card", "Unchanged card title", greeting = "Synthetic greeting",
            characters = listOf(CharacterInfo("a", "A"), CharacterInfo("b", "B")), createdAt = 1, updatedAt = 1))
        app.chatRepository.createSession(ChatSession("session", "card", "Independent session title", createdAt = 1, updatedAt = 1))
        app.chatRepository.addMessage(ChatMessage("first", "session", MessageRole.ASSISTANT,
            "<n=\"A\"/>first plain A <n=\"NPC\"/>unchanged", alternatives = listOf("<n=\"B\"/>alternate"), createdAt = 1, updatedAt = 1))
        app.chatRepository.addMessage(ChatMessage("second", "session", MessageRole.ASSISTANT,
            "<n=\"B\"/>second", alternatives = listOf("<n=\"A\"/>other"), createdAt = 2, updatedAt = 2))
    }
    private fun editChain(editor: DesktopCharacterEditorController) {
        editor.updateCharacter("a") { it.copy(name = "B") }
        editor.updateCharacter("b") { it.copy(name = "C") }
    }
    private suspend fun verify(app: DesktopAppContainer) {
        assertEquals("<n=\"B\"/>first plain A <n=\"NPC\"/>unchanged", app.chatRepository.getMessage("first", "session")!!.content)
        assertEquals(listOf("<n=\"C\"/>alternate"), app.chatRepository.getMessage("first", "session")!!.alternatives)
        assertEquals("<n=\"C\"/>second", app.chatRepository.getMessage("second", "session")!!.content)
        assertEquals(listOf("<n=\"B\"/>other"), app.chatRepository.getMessage("second", "session")!!.alternatives)
        assertEquals("Independent session title", app.chatRepository.getSession("session")!!.title)
        assertEquals("Unchanged card title", app.characterRepository.getById("card")!!.name)
        assertTrue(app.characterRepository.getById("card")!!.pendingSpeakerRenameTasks.isEmpty())
    }

    @Test fun `actual controller save of stable person ids rewrites history content and alternatives without renaming the card`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-speaker-save-"); val app = app(parent.resolve("data"))
        try {
            seed(app); val editor = app.characterEditorController
            editor.openExisting("card"); editChain(editor)
            assertTrue(editor.save()); assertNull(editor.state.value.problem)
            verify(app)
            assertTrue(app.taskRuntime.tasks.value.none { it.status == DesktopTaskStatus.RUNNING })
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `controller partial durable rename failure keeps marker then ordinary app reopen completes the same chain exactly once`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-speaker-reopen-"); val root = parent.resolve("data")
        val first = app(root)
        var writes = 0
        val service = SpeakerTagHistoryService(first.characterRepository, first.chatRepository, rewrite = { task, persist ->
            first.chatRepository.rewriteSpeakerTagsForTaskObserved(task, persist) {
                if (++writes == 1) throw IOException("synthetic first message already durable")
            }
        })
        val editor = DesktopCharacterEditorController(first.characterRepository, first.editorDraftRepository,
            first.worldBookRepository, first.formatCardRepository, first.chatRepository,
            DesktopCharacterDraftResources(root, first.characterResourceStore), first.jsonFileStorage.json,
            dataGate = first.dataOperationCoordinator, speakerHistory = service)
        try {
            seed(first); editor.openExisting("card"); editChain(editor)
            assertTrue(editor.save())
            assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
            val card = first.characterRepository.getById("card")!!
            assertEquals(listOf("B", "C"), card.characters.map { it.name })
            assertEquals(2, card.pendingSpeakerRenameTasks.single().rewritePlan!!.size)
            assertNotNull(first.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, "card"))
        } finally { editor.closeAndDrain(); first.close() }
        val reopened = app(root)
        try {
            reopened.initializePersistentState()
            reopened.characterEditorController.openExisting("card")
            verify(reopened)
            assertNull(reopened.characterEditorController.state.value.problem)
            assertNull(reopened.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, "card"))
        } finally { reopened.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `existing session mutation admission defers history rewrite without losing committed names then retries`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-speaker-admission-"); val app = app(parent.resolve("data"))
        try {
            seed(app); val editor = app.characterEditorController
            editor.openExisting("card"); editChain(editor)
            val original = app.chatRepository.getMessage("first", "session")!!
            app.taskRuntime.withMessageMutation("session") {
                assertTrue(editor.save())
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
                assertEquals(original, app.chatRepository.getMessage("first", "session"))
                assertTrue(app.characterRepository.getById("card")!!.pendingSpeakerRenameTasks.isNotEmpty())
            }
            editor.retryCleanup()
            verify(app); assertNull(editor.state.value.problem)
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
}
