package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopChatReadingLifecycleTest {
    @Test fun `Next within real window updates anchor before animation without reloading page`() = runBlocking {
        fixture { f ->
            f.selectAt(80)
            val before = f.controller.state.value
            val map = desktopChatTimelineMapping(before, stream(f.id))
            var target: DesktopScrollTarget? = null
            val viewport = DesktopChatViewportState(f.id, map, observe = {
                DesktopViewportObservation(listOf(DesktopVisibleTimelineItem(map.keys.indexOf("m80"), "m80", -13, 100)),
                    map.keys.size, 0, 100, false)
            }, scroll = { requested, animated ->
                assertTrue(animated)
                assertEquals("m81", f.controller.state.value.messageWindowAnchorId)
                target = requested
            })
            viewport.ready = true
            viewport.navigate(f.controller, DesktopChatJump.NEXT)
            assertEquals(DesktopScrollTarget(map.keys.indexOf("m81")), target)
            assertSame(before.messages, f.controller.state.value.messages, "Within-window Next must not reload a page")
            assertFalse(viewport.restoring)
        }
    }

    @Test fun `Next across newer boundary uses one contiguous bounded page and immediate successor`() = runBlocking {
        fixture { f ->
            f.selectAt(80)
            val before = f.controller.state.value
            assertTrue(before.hasNewerMessages)
            val anchor = before.messages.last().id
            val successor = f.messages[f.messages.indexOfFirst { it.id == anchor } + 1].id
            lateinit var viewport: DesktopChatViewportState
            var target: DesktopScrollTarget? = null
            viewport = DesktopChatViewportState(f.id, desktopChatTimelineMapping(before, stream(f.id)), observe = {
                val map = viewport.mapping
                DesktopViewportObservation(listOf(DesktopVisibleTimelineItem(map.keys.indexOf(anchor), anchor, -10, 100)),
                    map.keys.size, 0, 100, false)
            }, scroll = { requested, animated ->
                assertTrue(animated)
                assertEquals(successor, f.controller.state.value.messageWindowAnchorId)
                target = requested
            })
            viewport.ready = true
            val next = async { viewport.navigate(f.controller, DesktopChatJump.NEXT) }
            f.controller.state.first { it.messages != before.messages }
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                viewport.mapping = desktopChatTimelineMapping(f.controller.state.value, stream(f.id))
            }
            next.await()
            assertEquals(DesktopScrollTarget(viewport.mapping.keys.indexOf(successor)), target)
            val targetKey = viewport.mapping.keys[assertNotNull(target).index]
            assertTrue(viewport.mapping.renderedMessageIds.contains(targetKey))
            assertNotEquals("newer", targetKey)
            assertNotEquals("stream:task", targetKey)
            assertEquals(successor, f.controller.state.value.messageWindowAnchorId)
            assertTrue(f.controller.state.value.hasNewerMessages, "Next is not Jump Latest")
            f.assertWindow()
            assertFalse(viewport.restoring)
            f.controller.refresh()
            assertEquals(successor, f.controller.state.value.messageWindowAnchorId)
            f.assertWindow()
        }
    }

    @Test fun `Next at latest last real or stream-only is unavailable and never scrolls or reloads`() = runBlocking {
        fixture { f ->
            f.controller.selectSession(f.id)
            val before = f.controller.state.value
            val map = desktopChatTimelineMapping(before, stream(f.id))
            for (visible in listOf(before.messages.last().id, "stream:task")) {
                val viewport = DesktopChatViewportState(f.id, map, observe = {
                    DesktopViewportObservation(listOf(DesktopVisibleTimelineItem(map.keys.indexOf(visible), visible, 0, 500)),
                        map.keys.size, 0, 100, false)
                }, scroll = { _, _ -> fail("Next must not target stream") })
                viewport.ready = true
                assertFalse(viewport.canLater(before.messageWindowAnchorId))
                viewport.navigate(f.controller, DesktopChatJump.NEXT)
                assertSame(before.messages, f.controller.state.value.messages)
                assertEquals(before.messageWindowAnchorId, f.controller.state.value.messageWindowAnchorId)
                assertFalse(viewport.restoring)
            }
        }
    }

    @Test fun `refresh retries obsolete window revision and preserves newer durable position`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = false
        fixture(afterRead = { if (pause) { pause = false; entered.complete(Unit); release.await() } }) { f ->
            f.selectAt(200)
            pause = true
            val refresh = async { f.controller.refresh() }
            entered.await()
            f.controller.persistReadingPosition(f.position(210, 33))
            val newer = f.controller.state.value.readingPosition
            release.complete(Unit)
            refresh.await()
            val state = f.controller.state.value
            assertEquals(newer, state.readingPosition)
            assertEquals("m210", state.messageWindowAnchorId)
            f.assertWindow()
            assertEquals(f.container.chatRepository.getInitialMessagePage(f.id, "m210").messages, state.messages)
        }
    }

    @Test fun `refresh cannot clear process-only write error but current success can`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = false
        var fail = false
        fixture(beforeWrite = { if (fail) error("inline write fault") },
            afterRead = { if (pause) { pause = false; entered.complete(Unit); release.await() } }) { f ->
            f.selectAt(200)
            pause = true
            val refresh = async { f.controller.refresh() }
            entered.await()
            fail = true
            f.controller.persistReadingPosition(f.position(210))
            assertTrue(f.controller.state.value.readingPositionError)
            release.complete(Unit)
            refresh.await()
            assertTrue(f.controller.state.value.readingPositionError)
            f.controller.refreshAfterTerminalTask(f.id)
            assertTrue(f.controller.state.value.readingPositionError)
            val b = f.newSession()
            f.controller.selectSession(b)
            assertFalse(f.controller.state.value.readingPositionError)
            f.controller.selectSession(f.id)
            assertTrue(f.controller.state.value.readingPositionError)
            fail = false
            f.controller.persistReadingPosition(f.position(211))
            assertFalse(f.controller.state.value.readingPositionError)
        }
    }

    @Test fun `bottom hands latest anchor to refresh before any durable save`() = runBlocking {
        fixture { f ->
            f.selectAt(80)
            f.controller.loadLatestMessageWindow(f.id)
            assertEquals("m319", f.controller.state.value.messageWindowAnchorId)
            f.controller.refreshAfterTerminalTask(f.id)
            assertEquals("m319", f.controller.state.value.messages.last().id)
            assertFalse(f.controller.state.value.hasNewerMessages)
            f.assertWindow()
        }
    }

    @Test fun `stream-only real Previous navigate stays in loaded window and targets last real row`() = runBlocking {
        fixture { f ->
            f.controller.selectSession(f.id)
            val before = f.controller.state.value
            assertTrue(before.hasOlderMessages)
            val map = desktopChatTimelineMapping(before, stream(f.id))
            var target: DesktopScrollTarget? = null
            val viewport = DesktopChatViewportState(f.id, map,
                observe = { DesktopViewportObservation(listOf(DesktopVisibleTimelineItem(map.keys.lastIndex, "stream:task", 0, 100)),
                    map.keys.size, 0, 100, false) },
                scroll = { requested, _ -> target = requested })
            viewport.ready = true
            viewport.navigate(f.controller, DesktopChatJump.PREVIOUS)
            assertEquals(map.keys.indexOf(before.messages.last().id), target?.index)
            assertEquals(before.messages, f.controller.state.value.messages, "Previous must not invoke Load Older")
            assertEquals(before.messages.last().id, f.controller.state.value.messageWindowAnchorId)
        }
    }

    @Test fun `historical window renders stream without granting bottom-follow ownership`() = runBlocking {
        fixture { f ->
            f.selectAt(80)
            val state = f.controller.state.value
            assertTrue(state.hasNewerMessages)
            val map = desktopChatTimelineMapping(state, stream(f.id))
            assertEquals(listOf("newer", "stream:task"), map.keys.takeLast(2))
            assertFalse(desktopShouldFollowBottom(true, false, false, false, true))
            assertTrue(desktopShouldFollowBottom(true, false, true, false, false))
            assertNull(map.captureStable(f.id, listOf(DesktopVisibleTimelineItem(map.keys.lastIndex, "stream:task", 0, 100)),
                0, true, false, false))
        }
    }

    @Test fun `dispose submits final real viewport even while prior persistence is blocked`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(beforeWrite = { if (it.anchorMessageId == "m200") { entered.complete(Unit); release.await() } }) { f ->
            f.selectAt(200)
            f.controller.submitReadingPosition(f.position(200))
            entered.await()
            val map = desktopChatTimelineMapping(f.controller.state.value, stream(f.id))
            val lazy = map.messageToLazy(map.messageIds.indexOf("m210"))!!
            val viewport = DesktopChatViewportState(f.id, map, observe = {
                DesktopViewportObservation(listOf(DesktopVisibleTimelineItem(lazy, "m210", -29, 100)), map.keys.size, 0, 100, true)
            })
            viewport.ready = true
            assertNull(viewport.capture(), "ordinary capture is idle-only")
            viewport.submitFinalViewport { f.controller.submitReadingPosition(it) }
            assertEquals("m210", f.controller.state.value.messageWindowAnchorId)
            val b = f.newSession()
            f.controller.selectSession(b)
            release.complete(Unit)
            f.controller.closeDraftPersistence()
            assertEquals(b, f.controller.state.value.selectedSession?.id)
            assertNotEquals(f.id, f.controller.state.value.readingPosition?.sessionId)
            assertEquals("m210", f.container.chatRepository.getScrollPosition(f.id)?.anchorMessageId)
            assertEquals(29, f.container.chatRepository.getScrollPosition(f.id)?.scrollOffset)
        }
    }

    @Test fun `pending A B A positions remain isolated and latest accepted survives restart`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(beforeWrite = { if (it.anchorMessageId == "m200") { entered.complete(Unit); release.await() } }) { f ->
            f.selectAt(200)
            f.controller.submitReadingPosition(f.position(200))
            entered.await()
            f.controller.submitReadingPosition(f.position(205, 47))
            val b = f.newSession()
            val bMessage = f.container.chatRepository.addMessage(ChatMessage.create(b, MessageRole.USER, "inline B"))
            f.controller.selectSession(b)
            f.controller.submitReadingPosition(ChatScrollPosition(b, bMessage.id, 0, 5, 0))
            f.controller.selectSession(f.id)
            assertEquals("m205", f.controller.state.value.messageWindowAnchorId)
            assertEquals(47, f.controller.state.value.viewportRestorePosition?.scrollOffset)
            assertNotEquals(47, f.controller.state.value.readingPosition?.scrollOffset, "Pending is not yet durable")
            release.complete(Unit)
            f.controller.closeDraftPersistence()
            assertEquals("m205", f.container.chatRepository.getScrollPosition(f.id)?.anchorMessageId)
            assertEquals(bMessage.id, f.container.chatRepository.getScrollPosition(b)?.anchorMessageId)
            f.container.close()
            val reopened = DesktopAppContainer(f.root, secretStoreFactory = { InMemoryDesktopSecretStore() })
            try {
                reopened.primaryChatController.selectSession(f.id)
                assertEquals("m205", reopened.primaryChatController.state.value.messageWindowAnchorId)
                assertEquals(47, reopened.primaryChatController.state.value.readingPosition?.scrollOffset)
            } finally { reopened.close() }
        }
    }

    @Test fun `Load Older keeps visible boundary anchor in one bounded page`() = runBlocking {
        fixture { f ->
            f.selectAt(200)
            val visible = f.controller.state.value.messages.first().id
            f.controller.updateMessageWindowAnchor(f.id, visible)
            f.controller.loadOlder()
            assertEquals(visible, f.controller.state.value.messageWindowAnchorId)
            f.assertWindow()
            f.controller.refresh()
            assertEquals(visible, f.controller.state.value.messageWindowAnchorId)
            f.assertWindow()
        }
    }

    @Test fun `Load Newer keeps visible boundary anchor in one bounded page`() = runBlocking {
        fixture { f ->
            f.selectAt(80)
            val visible = f.controller.state.value.messages.last().id
            f.controller.updateMessageWindowAnchor(f.id, visible)
            f.controller.loadNewer()
            assertEquals(visible, f.controller.state.value.messageWindowAnchorId)
            f.assertWindow()
            f.controller.refreshAfterTerminalTask(f.id)
            assertEquals(visible, f.controller.state.value.messageWindowAnchorId)
            f.assertWindow()
        }
    }

    @Test fun `manual page navigation restores real keyed viewport and offset after bounded replacement`() = runBlocking {
        for (older in listOf(true, false)) fixture { f ->
            f.selectAt(150)
            val before = f.controller.state.value
            val visible = (if (older) before.messages.first() else before.messages.last()).id
            lateinit var viewport: DesktopChatViewportState
            var restored: DesktopScrollTarget? = null
            viewport = DesktopChatViewportState(f.id, desktopChatTimelineMapping(before, null), observe = {
                val mapping = viewport.mapping
                DesktopViewportObservation(listOf(DesktopVisibleTimelineItem(mapping.keys.indexOf(visible), visible, -13, 100)),
                    mapping.keys.size, 0, 100, false)
            }, scroll = { target, _ -> restored = target })
            viewport.ready = true
            val paging = async { viewport.loadAdjacent(f.controller, older) }
            f.controller.state.first { it.messages != before.messages }
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                viewport.mapping = desktopChatTimelineMapping(f.controller.state.value, null)
            }
            paging.await()
            assertEquals(DesktopScrollTarget(viewport.mapping.keys.indexOf(visible), 13), restored)
            assertEquals(visible, viewport.capture()?.anchorMessageId)
            assertEquals(visible, f.controller.state.value.messageWindowAnchorId)
            f.assertWindow()
        }
    }

    @Test fun `blocked reading close times out without releasing coordinator or root then retries`() = runBlocking {
        val directory = Files.createTempDirectory("reading-ownership-")
        val root = DesktopDataRootResolution.Resolved(directory, DesktopDataRootProvenance.CLI_OVERRIDE, directory.resolve("bootstrap.json"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = DesktopChatReadingPositionWriter({ null }, { entered.complete(Unit); release.await() })
        lateinit var ownership: DesktopDataRootOwnership
        val closed = mutableListOf<String>()
        writer.submit(ChatScrollPosition("inline", "message", capturedAt = 0))
        entered.await()
        try {
            assertFailsWith<DesktopReadingPositionDrainTimeoutException> {
                runDesktopApplicationWithDataRootOwnership(root,
                    acquireOwnership = { DesktopDataRootOwnership.acquire(it).also { result ->
                        ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership
                    } }, closeOwnership = { closed += "ownership"; it.close() }) {
                    runBlocking {
                        closeDesktopDataRuntimes(draftRuntimeClose = { writer.closeAndDrain(50) },
                            runtimeClose = { closed += "runtime" }, coordinatorClose = { closed += "coordinator" },
                            migrationServiceClose = { closed += "migration" })
                    }
                }
            }
            assertTrue(closed.isEmpty())
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
            release.complete(Unit)
            closeDesktopDataRuntimes(draftRuntimeClose = { writer.closeAndDrain() },
                runtimeClose = { closed += "runtime" }, coordinatorClose = { closed += "coordinator" },
                migrationServiceClose = { closed += "migration" })
            assertEquals(listOf("runtime", "coordinator", "migration"), closed)
        } finally { release.complete(Unit); writer.closeAndDrain(); ownership.close(); directory.toFile().deleteRecursively() }
    }

    private fun stream(id: String) = DesktopTaskEntry("task", DesktopTaskKind.REAL_CHAT, id, 0, contentPreview = "inline stream")
    private class Fixture(val container: DesktopAppContainer, val controller: DesktopPrimaryChatController,
        val id: String, val root: DesktopDataRootResolution.Resolved, val messages: List<ChatMessage>) {
        fun position(index: Int, offset: Int = 0) = ChatScrollPosition(id, "m$index", index, offset, 0)
        suspend fun selectAt(index: Int) {
            container.chatRepository.updateScrollPosition(position(index).copy(capturedAt = 1))
            controller.selectSession(id)
        }
        suspend fun newSession() = container.characterSessionService.createSessionForCharacter(container.characterRepository.getAll().first().id)
        fun assertWindow() {
            val state = controller.state.value
            val start = messages.indexOfFirst { it.id == state.messages.first().id }
            assertEquals(messages.drop(start).take(state.messages.size), state.messages)
            assertTrue(state.messages.size <= 120)
            assertEquals(start > 0, state.hasOlderMessages)
            assertEquals(start + state.messages.size < messages.size, state.hasNewerMessages)
        }
    }
    private suspend fun fixture(beforeWrite: suspend (ChatScrollPosition) -> Unit = {}, afterRead: suspend () -> Unit = {},
        block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("reading-lifecycle-")
        val root = DesktopDataRootResolution.Resolved(directory.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, directory.resolve("bootstrap.json"))
        val container = DesktopAppContainer(root, secretStoreFactory = { InMemoryDesktopSecretStore() })
        val controller = DesktopPrimaryChatController(container.characterRepository, container.chatRepository,
            container.settingsRepository, container.effectiveModelResolver, container.formatCardRepository,
            container.worldBookRepository, container.characterSessionService, container.characterResourceStore,
            taskRuntime = container.taskRuntime, readingWriter = { beforeWrite(it); container.chatRepository.updateScrollPosition(it) },
            afterWindowRead = afterRead)
        try {
            val card = CharacterCard.create("Inline R1")
            container.characterRepository.save(card)
            val id = container.characterSessionService.createSessionForCharacter(card.id)
            val messages = (0 until 320).map { i -> ChatMessage.create(id, MessageRole.USER, "inline $i").copy(
                id = "m$i", createdAt = i + 1L, orderKey = i + 1L, sourceTurnId = "turn$i", sourceTurnOrder = i + 1L) }
            container.chatRepository.replaceMessagesForSession(id, messages)
            block(Fixture(container, controller, id, root, messages))
        } finally { controller.closeDraftPersistence(); container.close(); directory.toFile().deleteRecursively() }
    }
}
