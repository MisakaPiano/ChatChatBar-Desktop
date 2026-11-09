@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
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
    @Test fun `person summary uses the whole master row below identity and actions without widening the master`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("person-summary-width-")
        val app = app(parent)
        try {
            val profile = "Synthetic profile with enough words to wrap across the available person row width."
            val card = CharacterCard("summary", "Synthetic", characters = listOf(CharacterInfo("person", "Person", profile = profile)),
                createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            val controller = app.characterEditorController; controller.openExisting(card.id)
            val scene = ImageComposeScene(320, 180, coroutineContext = edt) {
                CharacterPersonRow(DesktopPersonRowPresentation("person", "Person", null, profile), true, controller, {}, {})
            }
            try {
                scene.frames()
                val text = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == profile } == true }.boundsInRoot
                val name = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == "Person" } == true }.boundsInRoot
                assertTrue(text.width >= 290, "Profile must retain the master row width: $text")
                assertTrue(text.top >= name.bottom)
                assertTrue(text.right <= 320 && text.bottom <= 180)
                assertFalse(controller.state.value.dirty)
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val file = Path.of(directory).resolve("person-summary-width.png"); Files.createDirectories(file.parent)
                    scene.render(System.nanoTime()).use { it.encodeToData()!!.use { data -> Files.write(file, data.bytes) } }
                }
            } finally { scene.close() }
            assertEquals(card, app.characterRepository.getById(card.id))
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }

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
                val durable = assertNotNull(app.characterRepository.getById(original.id))
                assertEquals(original, durable)
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
                        scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    }
                    assertEquals(durable, app.characterRepository.getById(original.id))
                    assertEquals(durable, editor.state.value.card)
                } finally { scene.close() }
            } finally { app.close(); check(parent.toFile().deleteRecursively()) }
        }
    }

    @Test fun `five tool entries stay together above scrolling card content with original callbacks and fixed footer`() = runBlocking(edt) {
        for (mode in CharacterEditMode.entries) for ((width, height) in listOf(420 to 420, 1100 to 640)) {
            val parent = Files.createTempDirectory("card-toolbar-"); val app = app(parent)
            try {
                val original = CharacterCard("tools", "Synthetic tools", editMode = mode,
                    greeting = "Synthetic greeting\n".repeat(80), defaultImagePrompt = "Original style",
                    characters = listOf(CharacterInfo("p", "Person", profile = "Original profile")), createdAt = 1, updatedAt = 1)
                app.characterRepository.save(original)
                val durable = assertNotNull(app.characterRepository.getById(original.id))
                val editor = app.characterEditorController; editor.openExisting(original.id)
                val before = editor.state.value.card
                val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                    val state by editor.state.collectAsState()
                    CompositionLocalProvider(LocalDesktopModalActivity provides remember { DesktopModalActivity() }) {
                        DesktopCharacterEditorOverlay(editor, state)
                    }
                }
                try {
                    scene.frames()
                    val labels = listOf("画风与生图参数", "生成卡片封面候选", "字段 Token 估算") +
                        (if (mode == CharacterEditMode.STRUCTURED) listOf("AI 补全空白") else emptyList()) + "AI 定向改写"
                    fun button(label: String) = scene.nodes().first { it.text(label) && it.config.contains(SemanticsActions.OnClick) }
                    val toolbar = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-editor-tools" }.boundsInRoot
                    val positions = labels.associateWith { button(it).boundsInRoot }
                    positions.values.forEach { rect ->
                        assertTrue(rect.width > 0 && rect.height > 0 && rect.left >= toolbar.left && rect.right <= toolbar.right)
                        assertTrue(rect.top >= toolbar.top && rect.bottom <= toolbar.bottom)
                    }
                    if (width == 1100) assertEquals(1, positions.values.map { it.top }.distinct().size)
                    assertTrue(button("保存").visible(height)); assertTrue(button("取消").visible(height))
                    val scroll = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-editor-scroll" }.boundsInRoot
                    assertTrue(scroll.height > 0)
                    scene.sendPointerEvent(PointerEventType.Scroll, scroll.center, scrollDelta = Offset(0f, 20f)); scene.frames()
                    labels.forEach { assertEquals(positions[it], button(it).boundsInRoot) }
                    scene.click("字段 Token 估算"); scene.until { nodes().any { it.text("编辑字段 Token 估算") } }
                    scene.click("关闭 Token 估算")
                    assertFalse(scene.nodes().any { it.text("编辑字段 Token 估算") })
                    scene.click("生成卡片封面候选"); assertTrue(editor.cardImages!!.state.value.open); editor.cardImages!!.close(); scene.frames()
                    scene.click("AI 定向改写"); assertTrue(editor.cardAi!!.state.value.open)
                    assertEquals(com.example.chatbar.domain.card.CharacterCardAiOperation.REWRITE, editor.cardAi!!.state.value.operation)
                    editor.cardAi!!.close(); scene.frames()
                    if (mode == CharacterEditMode.STRUCTURED) {
                        scene.click("AI 补全空白"); assertEquals(com.example.chatbar.domain.card.CharacterCardAiOperation.FILL, editor.cardAi!!.state.value.operation)
                        editor.cardAi!!.close(); scene.frames()
                    }
                    scene.click("画风与生图参数")
                    scene.until {
                        val section = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-editor-style" }
                        val viewport = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-editor-scroll" }
                        val rawTop = section.positionInRoot.y
                        val top = viewport.positionInRoot.y
                        val value = viewport.config[SemanticsProperties.VerticalScrollAxisRange].value()
                        println("CARD_TOOL_JUMP mode=$mode width=$width rawTop=$rawTop viewportTop=$top scroll=$value")
                        rawTop >= top - 1f && rawTop < top + 48f
                    }
                    assertEquals(before, editor.state.value.card)
                    assertEquals(durable, app.characterRepository.getById(original.id))
                    assertTrue(app.taskRuntime.tasks.value.isEmpty())
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val file = Path.of(directory).resolve("card-tools-${mode.name}-$width.png"); Files.createDirectories(file.parent)
                        scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    }
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
                    defaultImagePrompt = "Original style", defaultImageNegativePrompt = "Original negative  ", createdAt = 1, updatedAt = 1)
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
    private suspend fun ImageComposeScene.frames() { repeat(5) { render(System.nanoTime()).close(); delay(15) } }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(5_000) {
        while (!condition()) { render(System.nanoTime()).close(); delay(15) }; frames()
    }
    private suspend fun ImageComposeScene.click(label: String) {
        fun SemanticsNode.named() = text(label) || config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
        until { nodes().any { it.named() && it.config.contains(SemanticsActions.OnClick) } }
        val n = nodes().first { it.named() && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(n.config.contains(SemanticsProperties.Disabled))
        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
    private suspend fun ImageComposeScene.setText(label: String, value: String) {
        fun field() = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.SetText) }
        revealNode("field:$label", 560, ::field)
        val n = field()
        assertTrue(n.visible(560)); assertTrue(n.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frames()
    }
    private suspend fun ImageComposeScene.reveal(tag: String, height: Int) {
        fun heading(): SemanticsNode {
            fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
            val target = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            return flatten(target).first { it.config.contains(SemanticsProperties.Text) }
        }
        revealNode(tag, height, ::heading)
    }
    private suspend fun ImageComposeScene.revealNode(label: String, height: Int, target: () -> SemanticsNode) {
        withTimeout(6_000) {
            while (true) {
                val node = target()
                val scroll = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "character-editor-scroll" }
                val viewport = scroll.boundsInRoot
                val top = node.positionInRoot.y; val bottom = top + node.size.height
                val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                println("CARD_REVEAL $label top=$top bottom=$bottom viewport=$viewport scroll=${range.value()} max=${range.maxValue()}")
                if (top >= viewport.top && bottom <= viewport.bottom) {
                    assertTrue(node.visible(height)); assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 1f)
                    break
                }
                val point = Offset(viewport.right - 10f, viewport.center.y)
                val distance = if (top < viewport.top) top - viewport.top else bottom - viewport.bottom
                val delta = if (distance < 0) (distance / 48f).coerceIn(-20f, -1f) else (distance / 48f).coerceIn(1f, 20f)
                sendPointerEvent(PointerEventType.Move, point)
                sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, delta))
                frames()
            }
        }
    }
}
