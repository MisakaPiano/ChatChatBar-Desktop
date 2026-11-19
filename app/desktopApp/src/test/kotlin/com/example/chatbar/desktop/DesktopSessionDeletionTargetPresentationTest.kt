@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Synthetic real copy/menu/confirmation path; no access to the reporter's data. */
class DesktopSessionDeletionTargetPresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    private fun SemanticsNode.label(text: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    private fun click(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).asReversed().filter { it.label(text) }.firstNotNullOf { candidate ->
            generateSequence(candidate) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) }
        }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(12) { scene.render(System.nanoTime()).close(); delay(20) } }
    private suspend fun duplicate(app: DesktopAppContainer, sourceId: String): ChatSession {
        val before = app.chatRepository.getAllSessions().map { it.id }.toSet()
        app.saveSlotController.openFileContext(sourceId)!!.join()
        app.saveSlotController.duplicate()
        withTimeout(15_000) {
            while (app.saveSlotController.state.value.busy || app.chatRepository.getAllSessions().none { it.id !in before }) delay(20)
        }
        assertNull(app.saveSlotController.state.value.error)
        return app.chatRepository.getAllSessions().single { it.id !in before }
    }

    @Test fun `copied session menu confirmation and committed id stay the same across selection changes`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("session-delete-target-")
        val resolution = DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val app = DesktopAppContainer(resolution, secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            app.characterRepository.save(CharacterCard("card", "Synthetic deletion card", createdAt = 1, updatedAt = 1))
            app.chatRepository.createSession(ChatSession("source", "card", "Synthetic source", longTermMemoryEnabled = false,
                createdAt = 1, updatedAt = 1))
            app.chatRepository.addMessage(ChatMessage.create("source", MessageRole.USER, "Synthetic retained message"))
            val controller = app.primaryChatController
            controller.refresh()
            controller.editComposer("Synthetic retained draft")
            controller.persistComposer(); controller.flushComposerForSnapshot()
            val first = duplicate(app, "source")
            val second = duplicate(app, "source")
            assertNotEquals(first.id, second.id); assertNotEquals(first.title, second.title)
            app.saveSlotController.closePanel()
            controller.selectSession(first.id)
            val scene = ImageComposeScene(1380, 900, coroutineContext = edt) {
                DesktopPrimaryChatPanel(controller, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() })
            }
            try {
                frames(scene)
                suspend fun openMenu(target: ChatSession) {
                    val title = nodes(scene).first { it.label(target.title) && it.boundsInRoot.left < 280 }
                    scene.sendPointerEvent(PointerEventType.Press, title.boundsInRoot.center, button = PointerButton.Secondary)
                    scene.sendPointerEvent(PointerEventType.Release, title.boundsInRoot.center, button = PointerButton.Secondary)
                    frames(scene)
                }
                fun assertConfirmation(target: ChatSession, other: ChatSession) {
                    val explanation = nodes(scene).single { it.label("删除此会话的消息、草稿、存档、记忆和录音；保留角色卡、世界书以及其他会话仍在使用的媒体。此操作不能从界面撤销。") }
                    val modal = generateSequence(explanation) { it.parent }.first { ancestor ->
                        descendants(ancestor).any { it.label("确认删除") }
                    }
                    val texts = descendants(modal).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                    assertTrue(target.title in texts, "Confirmation must name the exact menu target")
                    assertFalse(other.title in texts, "Another copy must not replace the confirmation target")
                }
                openMenu(first)
                controller.selectSession(second.id)
                frames(scene)
                click(scene, "删除会话…"); frames(scene)
                assertConfirmation(first, second)
                click(scene, "取消"); frames(scene)
                assertEquals(3, app.chatRepository.getAllSessions().size)

                openMenu(second)
                click(scene, "删除会话…"); frames(scene)
                controller.selectSession("source"); frames(scene)
                assertConfirmation(second, first)
                click(scene, "确认删除")
                withTimeout(15_000) {
                    while (app.chatRepository.getSession(second.id) != null) { frames(scene) }
                }
                assertNotNull(app.chatRepository.getSession(first.id))
                assertNotNull(app.chatRepository.getSession("source"))
                assertEquals("Synthetic retained draft", app.chatRepository.getSessionDraft(first.id))
                assertEquals("Synthetic retained draft", app.chatRepository.getSessionDraft("source"))
                assertEquals(1, app.chatRepository.getMessagesReadOnly(first.id).size)
                assertEquals(1, app.chatRepository.getMessagesReadOnly("source").size)
            } finally { scene.close() }
        } finally { app.close(); ownership.close(); parent.toFile().deleteRecursively() }
    }
}
