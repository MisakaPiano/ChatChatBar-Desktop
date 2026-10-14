package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.FormatRepairPromptAuthority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

/** One container, repository and root. VERIFIED_SAFE exists only in this synthetic test seam. */
internal class DesktopRepairInteractionFixture(val c: DesktopAppContainer) {
    val requests = mutableListOf<DesktopRepairRequest>()
    var response: () -> Flow<StreamEvent> = { flowOf(StreamEvent.Delta("fixed"), StreamEvent.Done) }
    val repair = DesktopMessageFormatRepairController(c.chatRepository, c.settingsRepository,
        c.characterRepository, c.formatCardRepository, c.effectiveModelResolver, c.taskRuntime,
        DesktopRepairSafetyGate { _, _ -> DesktopRepairSafety.VERIFIED_SAFE }, { requests += it; response() })
    val primary = DesktopPrimaryChatController(c.characterRepository, c.chatRepository, c.settingsRepository,
        c.effectiveModelResolver, c.formatCardRepository, c.worldBookRepository, c.characterSessionService,
        c.characterResourceStore, taskRuntime = c.taskRuntime, formatRepair = repair, imageStore = c.chatImages)
    lateinit var original: ChatMessage
    suspend fun prepare() {
        val card = CharacterCard.create("Synthetic")
        c.characterRepository.save(card)
        c.modelRepository.saveModel(ModelConfig("model", "Synthetic", "https://fixture.invalid", "synthetic-key", "fixture", createdAt = 1))
        c.formatCardRepository.save(FormatCard("format", "Synthetic", "Synthetic rule", createdAt = 1))
        c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "model", defaultFormatCardId = "format",
            assistantSegmentedBubblesEnabled = false))
        for (id in listOf("A", "B")) c.chatRepository.createSession(ChatSession(id, card.id, id, createdAt = 1, updatedAt = 1))
        original = c.chatRepository.addMessage(ChatMessage("message-A", "A", MessageRole.ASSISTANT, "First<n=\"Bot\"/>[Middle]()『Last』",
            alternatives = listOf("other", "First<n=\"Bot\"/>[Middle]()『Last』", "last candidate"),
            currentAlternativeIndex = 1, alternativeVersionIds = listOf("v1", "v2", "v3"), currentAlternativeVersionId = "v2",
            reasoningContent = "separate reasoning", sourceTurnId = "turn", sourceTurnOrder = 1, createdAt = 2, updatedAt = 2))
        c.chatRepository.addMessage(ChatMessage("message-B", "B", MessageRole.ASSISTANT, "B remains untouched", createdAt = 2, updatedAt = 2))
        primary.refresh(); primary.selectSession("A")
    }
    suspend fun saved() = assertNotNull(c.chatRepository.getMessage(original.id, "A"))
    suspend fun await(id: String): DesktopRepairState = withTimeout(8000) {
        c.taskRuntime.repairTasks.first { it[id] != null && it[id] != DesktopRepairStatus.RUNNING }
        assertNotNull(repair.states.value[id])
    }
    suspend fun close() { primary.closeDraftPersistence() }
}

class DesktopFormatRepairInteractionTest {
    private suspend fun fixture(block: suspend (DesktopRepairInteractionFixture) -> Unit) = fishFixture { _, c ->
        val f = DesktopRepairInteractionFixture(c)
        try { f.prepare(); block(f) } finally { f.close() }
    }

    @Test fun `segment context repair is whole assistant only and restore requires applicable recoverable notice`() {
        val raw = ChatMessage("m", "s", MessageRole.ASSISTANT, "body", createdAt = 1, updatedAt = 1)
        for (role in MessageRole.entries) {
            val actions = desktopMessageActions(listOf(raw), raw.copy(role = role), null, formatRepairEnabled = true)
            assertEquals(role == MessageRole.ASSISTANT, DesktopMessageAction.REPAIR_FORMAT in actions)
            val scoped = desktopMessageContextActions(actions, true).filter { it.action == DesktopMessageAction.REPAIR_FORMAT }
            assertTrue(scoped.all { it.scope == DesktopMessageActionScope.MESSAGE })
        }
        for (kind in MessageFormatRepairNoticeKind.entries) {
            val message = raw.copy(formatRepairNotice = MessageFormatRepairNotice(kind, "body", "original"))
            assertEquals(kind in setOf(MessageFormatRepairNoticeKind.APPLIED, MessageFormatRepairNoticeKind.STOPPED), desktopCanRestoreFormat(message))
            assertFalse(desktopCanRestoreFormat(message.copy(content = "changed")))
            assertFalse(desktopCanRestoreFormat(message.copy(formatRepairNotice = message.formatRepairNotice!!.copy(originalContent = null))))
        }
    }

    @Test fun `preview is target scoped and candidate changes hide old runtime results`() = runBlocking { fixture { f ->
        val entered = CompletableDeferred<Unit>()
        f.response = { flow { emit(StreamEvent.Delta("🦊 preview")); entered.complete(Unit); awaitCancellation() } }
        val id = assertNotNull(f.primary.checkMessageFormat(f.original))
        withTimeout(8000) { entered.await() }
        val state = assertNotNull(desktopRepairStateFor(f.original, f.repair.states.value))
        assertEquals(DesktopRepairTaskIdentity(id, "A", f.original.id), state.identity)
        assertNotNull(state.preview)
        assertEquals(f.original, f.saved())
        assertEquals(f.original.displayContent, desktopWholeMessageCopyText(f.saved(), null, null, ""))
        assertNull(desktopRepairStateFor(f.original.copy(sessionId = "B"), f.repair.states.value))
        assertNull(desktopRepairStateFor(MessageAlternativeVersionPolicy.select(f.original, 0), f.repair.states.value))
        assertTrue(f.primary.stopMessageFormat(f.original))
        assertEquals(DesktopRepairStatus.STOPPED, f.await(id).status)
    } }

    @Test fun `repair owns session admission for direct editing deletion alternatives and chat`() = runBlocking { fixture { f ->
        val entered = CompletableDeferred<Unit>()
        f.response = { flow { entered.complete(Unit); awaitCancellation() } }
        val id = assertNotNull(f.primary.checkMessageFormat(f.original)); withTimeout(8000) { entered.await() }
        assertTrue(f.primary.hasActiveTask("A")); assertFalse(f.primary.hasActiveTask("B"))
        assertNull(f.primary.checkMessageFormat(f.original))
        assertFalse(f.primary.editMessage(f.original.id, "unsafe"))
        assertFalse(f.primary.deleteMessage(f.original.id))
        assertFalse(f.primary.deleteMessageImage(f.original, "unknown"))
        val segment = parseRoleplayTextSegments(f.original.displayContent).first()
        assertFalse(f.primary.editMessageSegment(f.original.id, segment.start, segment.endExclusive, "unsafe"))
        f.primary.selectAssistantAlternative(f.original.id, -1)
        assertEquals(f.original, f.saved())
        assertFailsWith<DesktopTaskAdmissionException> { f.c.taskRuntime.launchChat("A", "not sent") }
        assertFailsWith<DesktopTaskAdmissionException> { f.c.taskRuntime.launchRegeneration("A", f.original.id) }
        val actions = desktopMessageActions(listOf(f.original), f.original, null, true, true, true)
        assertEquals(listOf(DesktopMessageAction.COPY, DesktopMessageAction.STOP_FORMAT), actions)
        assertTrue(f.primary.stopMessageFormat(f.original)); f.await(id)
        assertFalse(f.primary.hasActiveTask("A"))
    } }

    @Test fun `message mutation lease rejects repair and releases reliably after cancellation`() = runBlocking { fixture { f ->
        val entered = CompletableDeferred<Unit>()
        val mutation = launch { f.c.taskRuntime.withMessageMutation("A") { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        assertFailsWith<DesktopTaskAdmissionException> { f.repair.repair("A", f.original.id) }
        mutation.cancelAndJoin()
        assertEquals(DesktopRepairStatus.APPLIED, f.await(f.repair.repair("A", f.original.id)).status)
    } }

    @Test fun `stale Stop action cannot cancel a newer task for the same message`() = runBlocking { fixture { f ->
        val old = assertNotNull(f.primary.checkMessageFormat(f.original)); f.await(old)
        val entered = CompletableDeferred<Unit>()
        f.response = { flow { entered.complete(Unit); awaitCancellation() } }
        val current = f.saved()
        val fresh = assertNotNull(f.primary.checkMessageFormat(current))
        withTimeout(8000) { entered.await() }
        assertFalse(f.primary.stopMessageFormat(current, old))
        assertEquals(DesktopRepairStatus.RUNNING, f.c.taskRuntime.repairTasks.value[fresh])
        assertTrue(f.primary.stopMessageFormat(current, fresh))
        assertEquals(DesktopRepairStatus.STOPPED, f.await(fresh).status)
    } }

    @Test fun `switching A to B retains task and refreshing A completion never selects or mutates B`() = runBlocking { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.response = { flow { emit(StreamEvent.Delta("fixed")); entered.complete(Unit); release.await(); emit(StreamEvent.Done) } }
        val id = assertNotNull(f.primary.checkMessageFormat(f.original)); entered.await()
        f.primary.selectSession("B")
        val b = assertNotNull(f.c.chatRepository.getMessage("message-B", "B"))
        assertNull(desktopRepairStateFor(b, f.repair.states.value))
        assertEquals(id, f.c.taskRuntime.runningRepair("A", f.original.id)?.taskId)
        assertFalse(f.primary.stopMessageFormat(b))
        release.complete(Unit); assertEquals(DesktopRepairStatus.APPLIED, f.await(id).status)
        f.primary.refreshAfterTerminalTask("A")
        assertEquals("B", f.primary.state.value.selectedSession?.id)
        assertEquals(listOf(b), f.primary.state.value.messages)
        assertEquals(b, f.c.chatRepository.getMessage(b.id, "B"))
        f.primary.selectSession("A")
        assertEquals("fixed", f.primary.state.value.messages.single().displayContent)
        assertTrue(desktopCanRestoreFormat(f.primary.state.value.messages.single()))
        assertEquals(DesktopRepairStatus.RESTORED, f.await(assertNotNull(f.primary.restoreMessageFormat(f.saved()))).status)
        assertEquals(f.original.copy(updatedAt = f.saved().updatedAt), f.saved())
    } }

    @Test fun `production default UNKNOWN rejects request and restore without changing durable files`() = runBlocking { fixture { f ->
        val primary = f.c.primaryChatController
        primary.refresh(); primary.selectSession("A")
        val repair = assertNotNull(primary.formatRepair)
        val id = assertNotNull(primary.checkMessageFormat(f.original))
        withTimeout(8000) { f.c.taskRuntime.repairTasks.first { it[id] == DesktopRepairStatus.REJECTED } }
        assertEquals("DERIVED_DATA_UNPROVEN", repair.states.value[id]?.code)
        assertEquals(DesktopUiText.FORMAT_UNPROVEN, desktopRepairStatusText(repair.states.value[id]!!))
        assertEquals(f.original, f.saved()); assertTrue(f.requests.isEmpty())
        assertNull(primary.restoreMessageFormat(f.original))
    } }

    @Test fun `unchanged error applied and stopped have distinct sanitized status labels`() = runBlocking { fixture { f ->
        f.response = { flowOf(StreamEvent.Delta(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER), StreamEvent.Done) }
        assertEquals(DesktopUiText.FORMAT_UNCHANGED, desktopRepairStatusText(f.await(f.repair.repair("A", f.original.id))))
        f.response = { flowOf(StreamEvent.Error("synthetic PRIVATE provider body")) }
        assertEquals(DesktopUiText.FORMAT_FAILED, desktopRepairStatusText(f.await(f.repair.repair("A", f.original.id))))
        assertEquals(DesktopUiText.FORMAT_UNCERTAIN, desktopRepairStatusText(DesktopRepairState(DesktopRepairStatus.COMMIT_UNCERTAIN)))
        assertEquals(DesktopUiText.FORMAT_STORAGE_ERROR, desktopRepairStatusText(DesktopRepairState(DesktopRepairStatus.STORAGE_FAILURE)))
        assertEquals(DesktopUiText.FORMAT_CHANGED, desktopRepairStatusText(DesktopRepairState(DesktopRepairStatus.CONFLICT)))
    } }
}
