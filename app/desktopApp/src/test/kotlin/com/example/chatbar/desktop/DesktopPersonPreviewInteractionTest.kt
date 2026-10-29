@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import java.nio.file.*
import kotlin.test.*

class DesktopPersonPreviewInteractionTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(5) { scene.render().close(); yield(); delay(10) } }
    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
    }
    @Test fun `hover and click preview contains only state and gear then opens management for that person`(): Unit = runBlocking {
        val message = ChatMessage(id = "m", sessionId = "s", role = MessageRole.ASSISTANT, content = "state", createdAt = 1, updatedAt = 1)
        val person = DesktopChatPerson("c", CharacterInfo(id = "alice", name = "Alice"), true, DesktopPersonStatus("心情：calm", message))
        val preview = DesktopPeoplePreviewState()
        var managed: String? = null
        val actions = DesktopChatPeopleActions(listOf(person), { managed = it.character.id }, { _, _ -> }, preview = preview)
        val scene = ImageComposeScene(900, 600) {
            CompositionLocalProvider(LocalDesktopChatPeople provides actions) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(24.dp)) { DesktopChatPersonTarget(person) { BasicText("Avatar", Modifier.size(40.dp)) } }
                    DesktopPeoplePreview(actions)
                }
            }
        }
        try {
            frames(scene)
            val trigger = nodes(scene).single { "人物状态 · Alice" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            val point = trigger.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, point); frames(scene)
            assertEquals(person.character.id, preview.personId)
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "心情：calm" } == true })
            val texts = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertFalse(texts.any { it.contains("状态来源") || it.contains("参与当前会话") || it.contains("编辑人物") })
            click(scene, point); frames(scene)
            assertTrue(preview.held)
            System.getProperty("chatbar.test.screenshot.dir")?.let { dir ->
                Files.createDirectories(Path.of(dir)); scene.render().use { image -> image.encodeToData()?.let { Files.write(Path.of(dir, "native-avatar-preview.png"), it.bytes) } }
            }
            val gear = nodes(scene).single { "管理人物 · Alice" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertTrue(gear.boundsInRoot.right <= 900)
            assertTrue(gear.config[SemanticsActions.OnClick].action!!.invoke()); frames(scene)
            assertEquals("alice", managed); assertNull(preview.personId)
        } finally { scene.close() }
    }
}
