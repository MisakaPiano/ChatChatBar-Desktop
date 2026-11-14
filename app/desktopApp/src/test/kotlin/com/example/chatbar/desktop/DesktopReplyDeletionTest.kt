@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.runtime.remember
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopReplyDeletionTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual chat panel whole deletion removes only target reply after second confirmation and cold read`() = runBlocking(edt) {
        val root = Files.createTempDirectory("panel-reply-deletion-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            val card = CharacterCard.create("Synthetic deletion panel", greeting = "Greeting")
            app.characterRepository.save(card)
            app.chatRepository.createSession(ChatSession("session", card.id, "Synthetic session", createdAt = 1, updatedAt = 1))
            val user = app.chatRepository.addMessage(ChatMessage.create("session", MessageRole.USER, "Retained user"))
            val target = app.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "Target visible")
                .copy(alternatives = listOf("Other target candidate", "Target visible"),
                    alternativeVersionIds = listOf("a", "b"), currentAlternativeIndex = 1, currentAlternativeVersionId = "b"))
            val separate = app.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "Separate AI reply retained"))
            app.primaryChatController.refresh(); app.primaryChatController.selectSession("session")
            val scene = ImageComposeScene(1200, 900, coroutineContext = edt) {
                DesktopPrimaryChatPanel(app.primaryChatController, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() })
            }
            try {
                suspend fun tap(label: String, button: PointerButton = PointerButton.Primary) {
                    scene.until { scene.nodes().any { it.label(label) && it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 } }
                    val node = scene.nodes().first { it.label(label) && it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
                    scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center, button = button)
                    scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center, button = button)
                    repeat(3) { scene.render().close(); yield(); delay(20) }
                }
                tap("Target visible", PointerButton.Secondary)
                tap("整条 · 删除")
                scene.until { scene.nodes().any { it.label("删除当前备选") } }
                assertEquals(target, app.chatRepository.getMessage(target.id, "session"))
                tap("删除全部备选")
                scene.until { scene.nodes().any { it.label(DesktopUiText.DELETE_MESSAGE_WARNING.zhCn) } }
                assertEquals(target, app.chatRepository.getMessage(target.id, "session"))
                tap(DesktopUiText.DELETE.zhCn)
                scene.until { app.primaryChatController.state.value.messages.none { it.id == target.id } }
                val cold = ChatRepository(JsonFileStorage(app.appDataRoot))
                assertNull(cold.getMessage(target.id, "session"))
                assertEquals(user, cold.getMessage(user.id, "session"))
                assertEquals(separate, cold.getMessage(separate.id, "session"))
                assertEquals(setOf(user.id, separate.id), cold.getMessages("session").map { it.id }.toSet())
            } finally { scene.close() }
        } finally { app.close(); root.toFile().deleteRecursively() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
        return semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(value: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
    private suspend fun ImageComposeScene.until(condition: suspend () -> Boolean) = withTimeout(15_000) {
        while (!condition()) { render().close(); yield(); delay(20) }
    }
    @Test fun `whole alternative deletion route remains full width and opens only through click`() = runBlocking(edt) {
        for (width in listOf(240, 680)) {
            var opened = 0
            val scene = ImageComposeScene(width, 150, coroutineContext = edt) {
                Box(Modifier.fillMaxSize()) { DesktopDeleteAllAlternativesAction { opened++ } }
            }
            try {
                repeat(3) { scene.render().close(); delay(20) }
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                val nodes = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
                val action = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "delete-all-alternatives" }
                assertEquals(0, opened)
                assertTrue(action.boundsInRoot.width >= width - 1)
                assertTrue(action.boundsInRoot.height >= 44)
                assertEquals(Role.Button, action.config[SemanticsProperties.Role])
                assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(1, opened)
            } finally { scene.close() }
        }
    }
}
