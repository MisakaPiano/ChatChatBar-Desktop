package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiDesignConversationRepository
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopImageClosureR1DesignTest {
    private suspend fun fixture(block: suspend (FinalProductDesignFixture, DesktopNovelAiStudioController) -> Unit) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(initializeComposer = false)
            val c = f.container.novelAiStudioController
            c.edit { it.copy(imageDescription = "legacy compatibility input") }
            block(f, c)
        } finally { f.close() }
    }

    private fun draftBytes(f: FinalProductDesignFixture): ByteArray = Files.readAllBytes(
        f.container.appDataRoot.resolve("entities/novelai_studio_draft.json"))

    @Test fun `typing new conversation and remount keep draft bytes revisions and pointer unchanged`() = runBlocking { fixture { f, c ->
        c.initializeDesignComposer()
        assertEquals("legacy compatibility input", c.designComposer.value.input)
        val before = c.repository.loadDraft(); val bytes = draftBytes(f)
        c.editDesignInput("unsent edit")
        c.initializeDesignComposer() // Re-entering the tool must not re-seed legacy text.
        assertEquals("unsent edit", c.designComposer.value.input)
        c.newDesignConversation()
        assertEquals("", c.designComposer.value.input)
        c.initializeDesignComposer()
        assertEquals("", c.designComposer.value.input)
        assertNull(c.designRepository.currentConversationId.value)
        assertEquals(before, c.repository.loadDraft())
        assertContentEquals(bytes, draftBytes(f))
        assertTrue(f.container.taskRuntime.tasks.value.isEmpty()); assertTrue(f.requests.isEmpty())
    } }

    @Test fun `existing conversation does not resurrect legacy and unsent new leaves durable pointer intact`() = runBlocking { fixture { f, c ->
        val (previous, _) = c.designRepository.createCurrentConversation("existing turn", "local-design", NovelAiImageModel.V4_5_FULL)
        c.initializeDesignComposer()
        assertFalse(c.designComposer.value.composingNew); assertEquals("", c.designComposer.value.input)
        val bytes = draftBytes(f)
        c.newDesignConversation(); c.editDesignInput("unsent new")
        c.initializeDesignComposer()
        assertTrue(c.designComposer.value.composingNew); assertEquals("unsent new", c.designComposer.value.input)
        val reopened = NovelAiDesignConversationRepository(JsonFileStorage(f.container.appDataRoot))
        reopened.initialize(); assertEquals(previous.id, reopened.currentConversationId.value)
        assertContentEquals(bytes, draftBytes(f))
    } }

    @Test fun `first durable turn consumes explicit input and clears only legacy even if provider then fails`() = runBlocking { fixture { f, c ->
        c.edit { it.copy(characters = listOf(NovelAiCharacterPromptDraft(prompt = "active adult"),
            NovelAiCharacterPromptDraft(prompt = "disabled adult", enabled = false))) }
        c.initializeDesignComposer(); c.editDesignInput("explicit transient input")
        val before = c.repository.loadDraft()
        // A provider failure is later than durable creation, so it must not undo the migration.
        f.failure = DesktopDesignFailure.NETWORK
        assertTrue(c.design(c.designComposer.value.input, attach = true)); f.idle()
        val reopened = NovelAiDesignConversationRepository(JsonFileStorage(f.container.appDataRoot))
        reopened.initialize()
        val turn = assertNotNull(reopened.currentConversation()).turns.single()
        assertEquals("explicit transient input", turn.userText)
        assertEquals(listOf("active adult"), assertNotNull(turn.attachedStudioPrompt).characterPrompts)
        assertNotEquals(NovelAiDesignTurnStatus.PENDING, turn.status)
        val after = c.repository.loadDraft()
        assertEquals(before.copy(imageDescription = "", contentRevision = after.contentRevision, updatedAt = after.updatedAt), after)
        assertEquals(before.promptContentRevision, after.promptContentRevision)
        assertEquals("", c.designComposer.value.input); assertFalse(c.designComposer.value.composingNew)
        assertTrue(f.requests.isNotEmpty())
    } }

    @Test fun `successful first send migrates legacy and later input never comes from Studio draft`() = runBlocking { fixture { f, c ->
        c.initializeDesignComposer(); c.editDesignInput("first transient")
        assertTrue(c.design(c.designComposer.value.input)); f.idle()
        assertEquals("", c.repository.loadDraft().imageDescription)
        c.edit { it.copy(imageDescription = "unrelated compatibility value") }
        val bytes = draftBytes(f)
        c.editDesignInput("second transient")
        assertTrue(c.design(c.designComposer.value.input)); f.idle()
        assertEquals(listOf("first transient", "second transient"), c.designRepository.currentConversation()!!.turns.map { it.userText })
        assertContentEquals(bytes, draftBytes(f))
    } }

    @Test fun `credential and blank input preflight preserve legacy without tasks or provider calls`() = runBlocking { fixture { f, c ->
        c.initializeDesignComposer()
        val bytes = draftBytes(f)
        assertFalse(c.design("   "))
        val model = assertNotNull(f.container.modelRepository.getModel("local-design"))
        f.container.modelRepository.saveModel(model.copy(apiKey = ""))
        assertFalse(c.design(c.designComposer.value.input))
        assertContentEquals(bytes, draftBytes(f)); assertEquals("legacy compatibility input", c.designComposer.value.input)
        assertNull(c.designRepository.currentConversation()); assertTrue(f.requests.isEmpty())
        assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
    } }

    @Test fun `failure before durable current pointer keeps transient input and legacy bytes`() = runBlocking { fixture { f, c ->
        c.initializeDesignComposer(); c.editDesignInput("must survive failed write")
        val bytes = draftBytes(f)
        // Corrupt only this isolated fixture's current pointer; shared transactional creation must fail.
        Files.writeString(f.container.appDataRoot.resolve("entities/novelai_design_current.json"), "{broken")
        assertTrue(c.design(c.designComposer.value.input)); f.idle()
        assertNull(c.designRepository.currentConversation())
        assertContentEquals(bytes, draftBytes(f)); assertEquals("must survive failed write", c.designComposer.value.input)
        assertTrue(c.designComposer.value.composingNew); assertTrue(f.requests.isEmpty())
    } }
}
