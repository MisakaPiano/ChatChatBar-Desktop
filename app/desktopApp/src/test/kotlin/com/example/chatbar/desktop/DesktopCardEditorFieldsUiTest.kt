@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.CharacterGreetingPolicy
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCardEditorFieldsUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `expanded example cancel preserves raw source confirm applies exact text and external edit closes stale window`() = runBlocking(edt) {
        var value by mutableStateOf("Before\n exact  ")
        val scene = ImageComposeScene(700, 620, coroutineContext = edt) {
            CompositionLocalProvider(LocalDesktopEditorAuxiliaryFrame provides { content ->
                Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) { content() }
            }) {
                Box(Modifier.fillMaxSize()) { DesktopCharacterTextField("Synthetic example", value, multiline = true, expandable = true) { value = it } }
            }
        }
        try {
            scene.frames(); scene.click("全屏编辑")
            scene.setExpanded("Discard\n me  "); assertEquals("Before\n exact  ", value)
            scene.click("取消"); assertEquals("Before\n exact  ", value)
            scene.click("全屏编辑"); scene.setExpanded("Accepted\n  exact  "); scene.click("确认")
            assertEquals("Accepted\n  exact  ", value)
            scene.click("全屏编辑"); scene.setExpanded("Old window text")
            value = "New externally supplied value"; scene.frames()
            assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "character-expanded-text-field" })
            assertEquals("New externally supplied value", value)
        } finally { scene.close() }
    }

    @Test fun `real greeting chooser shows exact selected text and visible cancel create controls at compact and wide sizes`() = runBlocking(edt) {
        for ((width, height) in listOf(360 to 520, 900 to 720)) {
            val card = CharacterCard("synthetic", "Synthetic card", greeting = "Main raw  ",
                alternateGreetings = listOf("", " Alternate\n exact  "), createdAt = 1, updatedAt = 1)
            val choices = CharacterGreetingPolicy.choices(card)
            var selection by mutableStateOf(DesktopSessionGreetingSelection(card.id, card.name, choices, choices.first()))
            var confirms = 0; var cancels = 0
            val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                DesktopSessionGreetingContent(selection, null, { selection = selection.copy(selected = it) }, { cancels++ }, { confirms++ })
            }
            try {
                scene.frames(); scene.click("备用开场 2")
                assertEquals(choices.last(), selection.selected)
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == choices.last().text } == true })
                for (label in listOf("取消", "创建会话")) {
                    val node = scene.button(label)
                    assertTrue(node.boundsInRoot.let { it.width > 0 && it.height > 0 && it.top >= 0 && it.bottom <= height }, "$label ${node.boundsInRoot}")
                }
                scene.saveImage("greeting-picker-$width.png")
                scene.click("取消"); assertEquals(1, cancels); assertEquals(0, confirms)
                scene.click("创建会话"); assertEquals(1, confirms)
            } finally { scene.close() }
        }
    }

    @Test fun `actual card editor exposes author version and card tags through the existing save owner`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("card-fields-ui-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            val card = CharacterCard.create("Synthetic metadata", "Main").copy(tags = listOf("Original"), extensions = "raw unknown  ")
            app.characterRepository.save(card); app.characterEditorController.openExisting(card.id)
            val scene = ImageComposeScene(1100, 900, coroutineContext = edt) {
                val state by app.characterEditorController.state.collectAsState()
                DesktopCharacterEditorOverlay(app.characterEditorController, state)
            }
            try {
                scene.frames()
                scene.setField("作者", "UI author"); scene.setField("内容版本", "UI version")
                scene.click("▸ 角色卡标签"); scene.setField("角色卡标签 1", "UI tag")
                assertEquals(card, app.characterRepository.getById(card.id))
                scene.click("保存")
                withTimeout(5_000) { while (app.characterEditorController.state.value.dirty) { scene.frames() } }
                val saved = assertNotNull(app.characterRepository.getById(card.id))
                assertEquals("UI author", saved.creator); assertEquals("UI version", saved.characterVersion)
                assertEquals(listOf("UI tag"), saved.tags); assertEquals(card.extensions, saved.extensions)
                scene.saveImage("card-metadata-editor.png")
            } finally { scene.close() }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.text(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.text(label) }
    private fun ImageComposeScene.button(label: String) = nodes().first { it.text(label) && it.config.contains(SemanticsActions.OnClick) }
    private suspend fun ImageComposeScene.frames() { repeat(6) { render(System.nanoTime()).close(); delay(15) } }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = button(label); assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
    private suspend fun ImageComposeScene.setExpanded(value: String) {
        val node = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-expanded-text-field" }
        assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frames()
    }
    private suspend fun ImageComposeScene.setField(label: String, value: String) {
        val node = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            it.config.contains(SemanticsActions.SetText) }
        assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frames()
    }
    private fun ImageComposeScene.saveImage(name: String) {
        System.getenv("CCB_CARD_EDITOR_REVIEW_DIR")?.let { path ->
            val file = Path.of(path).resolve(name); Files.createDirectories(file.parent)
            render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
        }
    }
}
