@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** The actual primary panel must scroll, not merely load a page containing the source. */
class DesktopSourceMessageNavigationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun frames(scene: ImageComposeScene) {
        repeat(24) { scene.render(System.nanoTime()).close(); delay(20) }
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_SOURCE_NAVIGATION_REVIEW_DIR")?.let { folder ->
            val target = Files.createDirectories(Path.of(folder)).resolve(name)
            scene.render(System.nanoTime()).use { image ->
                image.encodeToData()!!.use { Files.write(target, it.bytes) }
            }
        }
    }
    private fun visible(scene: ImageComposeScene, text: String): Boolean {
        val viewport = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "chat-timeline-content" }.boundsInRoot
        return nodes(scene).any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                node.boundsInRoot.height > 0 && node.boundsInRoot.width > 0 &&
                node.boundsInRoot.top < viewport.bottom && node.boundsInRoot.bottom > viewport.top
        }
    }
    private suspend fun fixture(block: suspend (DesktopAppContainer, Int) -> Unit) {
        val parent = Files.createTempDirectory("source-message-navigation-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            val card = CharacterCard.create("Synthetic source navigation")
            app.characterRepository.save(card)
            app.chatRepository.createSession(ChatSession("s", card.id, "Synthetic source navigation", createdAt = 1, updatedAt = 1))
            val count = (ChatRepository.MAX_WINDOW_TURNS + 30) * 2
            repeat(count) { index ->
                val message = ChatMessage("m$index", "s", if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                    "Synthetic source message $index", images = if (index == 1 || index == count - 21) listOf("same.png", "same.png") else emptyList(),
                    createdAt = index.toLong() + 1, updatedAt = index.toLong() + 1, orderKey = index.toLong() + 1)
                app.jsonFileStorage.saveEntity("chat_messages", "s_${message.id}", message, ChatMessage.serializer())
            }
            app.primaryChatController.refresh()
            app.primaryChatController.selectSession("s")
            app.primaryChatController.editComposer("Unsent source-navigation draft")
            app.primaryChatController.persistComposer()
            app.primaryChatController.flushComposerForSnapshot()
            block(app, count)
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }

    private suspend fun sourceThroughPanel(old: Boolean) = fixture { app, count ->
        val controller = app.primaryChatController
        val sourceId = if (old) "m1" else "m${count - 21}"
        val sourceText = if (old) "Synthetic source message 1" else "Synthetic source message ${count - 21}"
        assertEquals(!old, controller.state.value.messages.any { it.id == sourceId })
        val snapshot = controller.sessionImageGallery("s")
        val occurrenceIndex = snapshot.occurrences.indexOfFirst { it.messageId == sourceId && it.imageIndex == 1 }
        assertTrue(occurrenceIndex >= 0)
        var opening by mutableStateOf<DesktopSessionImageOpenRequest?>(null)
        var actionCompleted = false
        val scene = ImageComposeScene(1380, 900, coroutineContext = edt) {
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                DesktopPrimaryChatPanel(controller, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() })
                opening?.let { request -> Box(Modifier.align(Alignment.TopEnd)) {
                    DesktopSessionImageOpening(request) { state ->
                        DesktopViewerSourceAction(state.selection) { index ->
                            scope.launch {
                                val occurrence = request.snapshot.occurrences[index]
                                assertEquals(sourceId, occurrence.messageId)
                                assertEquals(1, occurrence.imageIndex)
                                assertTrue(controller.revealMessage("s", occurrence.messageId))
                                actionCompleted = true
                                opening = null
                            }
                        }
                    }
                } }
            }
        }
        try {
            frames(scene)
            assertFalse(visible(scene, sourceText), "Fixture source must start outside the actual viewport")
            opening = DesktopSessionImageOpenRequest(snapshot, occurrenceIndex)
            frames(scene)
            val action = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "回到来源消息" } == true }
            assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
            frames(scene)
            assertTrue(actionCompleted)
            assertTrue(controller.state.value.messages.any { it.id == sourceId })
            capture(scene, if (old) "source-old-unloaded.png" else "source-already-loaded.png")
            assertTrue(visible(scene, sourceText), "Gallery source message was loaded but was not visible: $sourceId")
            assertEquals("Unsent source-navigation draft", controller.state.value.composerDraft)

            // An explicit opening of the exact same snapshot/index remains a new navigation event.
            val timeline = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "chat-timeline-content" }
            assertTrue(timeline.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 20000f))
            frames(scene)
            assertFalse(visible(scene, sourceText))
            opening = DesktopSessionImageOpenRequest(snapshot, occurrenceIndex)
            frames(scene)
            nodes(scene).first { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "回到来源消息" } == true }
                .config[SemanticsActions.OnClick].action!!.invoke()
            frames(scene)
            assertTrue(visible(scene, sourceText), "Repeated source navigation must scroll again")
            assertEquals("Unsent source-navigation draft", app.chatRepository.getSessionDraft("s"))
        } finally { scene.close() }
    }

    @Test fun `gallery source action scrolls the real primary panel to an already loaded message`() = runBlocking(edt) {
        sourceThroughPanel(old = false)
    }
    @Test fun `gallery source action loads old history and scrolls the real primary panel`() = runBlocking(edt) {
        sourceThroughPanel(old = true)
    }

    @Test fun `pending source target rejects stale capture and stale completion without affecting another session`() = runBlocking(edt) {
        fixture { app, count ->
            val controller = app.primaryChatController
            assertTrue(controller.revealMessage("s", "m${count - 21}"))
            val first = assertNotNull(controller.state.value.messageRevealRequest)
            assertNull(controller.submitReadingPosition(ChatScrollPosition("s", "m${count - 1}", 0, 17, 0)))
            assertEquals(first.position, controller.state.value.viewportRestorePosition)
            assertTrue(controller.revealMessage("s", first.position.anchorMessageId!!))
            val second = assertNotNull(controller.state.value.messageRevealRequest)
            assertNotEquals(first.requestId, second.requestId)
            controller.completeMessageReveal(first.requestId)
            assertTrue(controller.state.value.messageRevealRequest!!.pending)
            controller.completeMessageReveal(second.requestId)
            assertFalse(controller.state.value.messageRevealRequest!!.pending)
            assertNotNull(controller.submitReadingPosition(ChatScrollPosition("s", "m${count - 1}", 0, 17, 0)))
            val cardId = controller.state.value.selectedSession!!.characterCardId
            app.chatRepository.createSession(ChatSession("other", cardId, "Synthetic other", createdAt = 2, updatedAt = 2))
            controller.selectSession("other")
            controller.completeMessageReveal(second.requestId)
            assertNull(controller.state.value.messageRevealRequest)
            assertEquals("other", controller.state.value.selectedSession!!.id)
            assertEquals("Unsent source-navigation draft", app.chatRepository.getSessionDraft("s"))
        }
    }
}
