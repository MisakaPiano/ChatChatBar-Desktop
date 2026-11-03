@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterEditorOrderTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun app(parent: Path) = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
        DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
            check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
        })

    @Test fun `actual long card sections follow official order and remain reachable in both modes and compact heights`() = runBlocking(edt) {
        for (mode in CharacterEditMode.entries) for ((width, height) in listOf(420 to 560, 1100 to 740)) {
            val parent = Files.createTempDirectory("card-order-")
            val app = app(parent)
            try {
                val original = CharacterCard("synthetic-order", "合成布局卡", greeting = "Synthetic greeting", editMode = mode,
                    characters = (1..18).map { CharacterInfo("p$it", "Synthetic person $it", profile = "Profile\n".repeat(70)) },
                    freeformCharacterText = "Synthetic freeform\n".repeat(180), alternateGreetings = listOf("Alt exact  "),
                    basicSetting = "Common exact  ", defaultImagePrompt = "Style exact  ", defaultImageNegativePrompt = "Negative exact  ",
                    systemPrompt = "System exact  ", postHistoryInstructions = "Post exact  ", mesExample = "Example exact  ",
                    creatorNotes = "Notes exact  ", createdAt = 1, updatedAt = 1)
                app.characterRepository.save(original)
                val editor = app.characterEditorController; editor.openExisting(original.id)
                val activity = DesktopModalActivity()
                val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                    val state by editor.state.collectAsState()
                    CompositionLocalProvider(LocalDesktopModalActivity provides activity) {
                        DesktopCharacterEditorOverlay(editor, state)
                        DesktopCharacterLeavePrompt(editor)
                    }
                }
                try {
                    scene.frames()
                    val sections = listOf("card", "greeting", "background", "moments", "common", "style", "image-advanced",
                        "negative", "people", "advanced", "documents", "format", "worldbooks").map { "character-editor-$it" }
                    assertEquals(sections, scene.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.filter { it in sections })
                    // Sweep the real scroll owner. A clipped zero-sized semantic node is not visible evidence.
                    for (tag in sections) scene.reveal(tag, height)
                    scene.click("画风与生图参数")
                    scene.until { nodes().any { it.text("内置画风图集") && it.visible(height) } }
                    scene.click("▸ 生图高级设置")
                    scene.reveal("character-editor-image-advanced", height)
                    assertTrue(scene.nodes().any { it.text("使用角色专属生图参数") })
                    assertNull(editor.state.value.card!!.defaultImageGenerationSettings)
                    assertFalse(editor.state.value.dirty)
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val file = Path.of(directory).resolve("card-order-${mode.name}-$width.png")
                        Files.createDirectories(file.parent)
                        scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    }
                    assertEquals(original, app.characterRepository.getById(original.id))
                    assertEquals(original, editor.state.value.card)
                } finally { scene.close() }
            } finally { app.close(); check(parent.toFile().deleteRecursively()) }
        }
    }

    @Test fun `reordered actual fields retain draft recovery save cancel and all other original content`() = runBlocking(edt) {
        for (mode in CharacterEditMode.entries) {
            val parent = Files.createTempDirectory("card-order-lifecycle-")
            var app = app(parent)
            try {
                val original = CharacterCard("lifecycle", "Synthetic", editMode = mode, greeting = "Hello", basicSetting = "Common",
                    characters = listOf(CharacterInfo("p", "Person", profile = "Original profile")),
                    freeformCharacterText = "Original freeform", systemPrompt = "Original system", postHistoryInstructions = "Original post",
                    mesExample = "Original example", creatorNotes = "Original note", alternateGreetings = listOf("Alt"),
                    defaultImagePrompt = "Original style", defaultImageNegativePrompt = "Original negative", createdAt = 1, updatedAt = 1)
                app.characterRepository.save(original)
                suspend fun openScene(block: suspend (ImageComposeScene, DesktopCharacterEditorController) -> Unit) {
                    val editor = app.characterEditorController; editor.openExisting(original.id)
                    val activity = DesktopModalActivity()
                    val scene = ImageComposeScene(420, 560, coroutineContext = edt) {
                        val state by editor.state.collectAsState()
                        CompositionLocalProvider(LocalDesktopModalActivity provides activity) {
                            DesktopCharacterEditorOverlay(editor, state); DesktopCharacterLeavePrompt(editor)
                        }
                    }
                    try { scene.frames(); block(scene, editor) } finally { scene.close() }
                }
                openScene { scene, editor ->
                    scene.reveal("character-editor-common", 560)
                    scene.setText("基本设定", "Unsaved common exact  ")
                    assertEquals(original, app.characterRepository.getById(original.id))
                    editor.flushDraft()
                    scene.click("取消"); scene.until { editor.state.value.leavePrompt }
                    scene.click("继续编辑"); scene.until { !editor.state.value.leavePrompt }
                    assertEquals("Unsaved common exact  ", editor.state.value.card!!.basicSetting)
                }
                app.close(); app = app(parent)
                openScene { scene, editor ->
                    assertEquals("Unsaved common exact  ", editor.state.value.card!!.basicSetting)
                    assertEquals(original, app.characterRepository.getById(original.id))
                    scene.click("保存"); scene.until { !editor.state.value.dirty }
                    val saved = app.characterRepository.getById(original.id)!!
                    assertEquals(original.copy(basicSetting = saved.basicSetting, updatedAt = saved.updatedAt), saved)
                    scene.reveal("character-editor-common", 560)
                    scene.setText("基本设定", "Discard me")
                    scene.click("取消"); scene.until { editor.state.value.leavePrompt }
                    scene.click("放弃修改"); scene.until { editor.state.value.card == null }
                    assertEquals(saved, app.characterRepository.getById(original.id))
                }
            } finally { app.close(); check(parent.toFile().deleteRecursively()) }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.text(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.text(label) }
    private fun SemanticsNode.visible(height: Int) = boundsInRoot.let { it.width > 0f && it.height > 0f && it.top >= 0f && it.bottom <= height.toFloat() }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); delay(15) } }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(5_000) {
        while (!condition()) { render().close(); delay(15) }; frames()
    }
    private suspend fun ImageComposeScene.click(label: String) {
        until { nodes().any { it.text(label) && it.config.contains(SemanticsActions.OnClick) } }
        val n = nodes().first { it.text(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(n.config.contains(SemanticsProperties.Disabled))
        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
    private suspend fun ImageComposeScene.setText(label: String, value: String) {
        fun field() = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.SetText) }
        val scroll = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-editor-scroll" }
        withTimeout(5_000) {
            while (!field().visible(560)) { scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 60f); frames() }
        }
        val n = field()
        assertTrue(n.visible(560)); assertTrue(n.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frames()
    }
    private suspend fun ImageComposeScene.reveal(tag: String, height: Int) {
        val scroll = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-editor-scroll" }
        val action = scroll.config[SemanticsActions.ScrollBy].action!!
        assertTrue(action(0f, -1_000_000f)); frames()
        withTimeout(6_000) {
            while (true) {
                val target = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
                // Each section may exceed the viewport. Its heading must have actual visible bounds.
                fun visibleChild(n: SemanticsNode): Boolean = n.config.contains(SemanticsProperties.Text) && n.visible(height) || n.children.any(::visibleChild)
                if (visibleChild(target)) break
                assertTrue(action(0f, 120f)); frames()
            }
        }
    }
}
