@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopCurrentImagePresentationTest {
    private val awt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(6) { render().close(); yield() }; delay(40); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.matches(label) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        val center = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, center, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, center, button = PointerButton.Primary); frames()
    }
    private fun ImageComposeScene.shot(name: String) = render().use { image ->
        val output = Path.of("build/phase7-current-upstream-evidence/$name.png"); Files.createDirectories(output.parent)
        image.encodeToData()?.use { Files.write(output, it.bytes) }; Unit
    }
    private fun ImageComposeScene.previewHash(): Int {
        val bounds = nodes().first { it.matches("Studio 内联图片预览") }.boundsInRoot
        val raster = render().use { image -> image.encodeToData()?.use { ImageIO.read(ByteArrayInputStream(it.bytes)) } }
            ?: error("Preview frame unavailable")
        var hash = 1
        for (y in bounds.top.toInt() + 8 until bounds.bottom.toInt() - 8 step 8)
            for (x in bounds.left.toInt() + 8 until bounds.right.toInt() - 8 step 8)
                hash = 31 * hash + raster.getRGB(x, y)
        return hash
    }
    @Test fun `role disable collapse expand and edit remain independent from generation`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        var draft by mutableStateOf(NovelAiStudioDraft(basePrompt = "landscape", characters = listOf(NovelAiCharacterPromptDraft(id = "role", prompt = "adult traveler", negativePrompt = "blur"))))
        val scene = ImageComposeScene(760, 650) { Column(Modifier.fillMaxSize()) {
            DesktopStudioCharacters(draft, f.container.novelAiInfrastructure, false) { draft = it(draft) }
        } }
        try {
            scene.frames(); scene.click("✓")
            assertFalse(draft.characters.single().enabled); assertTrue(draft.activeCharacters.isEmpty())
            assertTrue(scene.nodes().any { it.matches("adult traveler") })
            scene.click("⌄"); assertTrue(scene.nodes().any { it.matches("角色正向") })
            assertFalse(draft.characters.single().enabled)
            scene.shot("role-disabled-expanded")
            scene.click("×"); assertTrue(draft.characters.single().enabled)
            assertEquals("adult traveler", draft.toPromptPlan().characterCaptions.single().prompt)
        } finally { scene.close(); f.close() }
    }
    @Test fun `AI design is read only conversation with structured modules and anchored composer`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); assertTrue(f.container.novelAiStudioController.design("Local scene", newConversation = true)); f.idle()
            val c = f.container.novelAiStudioController
            val draft = assertNotNull(c.draft.value); val auth = c.designAuthentication()
            val scene = ImageComposeScene(950, 740) { Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)) {
                DesktopDesignConversation(c, draft, false, auth, {}, {}, {}, {})
            } }
            try {
                scene.frames()
                assertTrue(scene.nodes().any { it.matches("编辑并分支") })
                assertTrue(scene.nodes().any { it.matches("复制 基础 Prompt") })
                val composer = scene.nodes().first { it.matches("画面需求") && it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(composer.boundsInRoot.top > 500)
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsActions.SetText) != null })
                scene.shot("design-conversation")
            } finally { scene.close() }
        } finally { f.close() }
    }
    @Test fun `studio wide and narrow keep result visible and share history authority`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            for (width in listOf(1360, 760)) {
                val scene = ImageComposeScene(width, 900) { DesktopNovelAiStudioPanel(f.container.novelAiStudioController) }
                try {
                    scene.frames(); repeat(10) { delay(25); scene.frames() }
                    val preview = scene.nodes().first { it.matches("打开预览") }
                    assertTrue(preview.boundsInRoot.top < 280)
                    assertTrue(preview.boundsInRoot.right <= width)
                    if (width > 900) assertTrue(preview.boundsInRoot.left > width / 2)
                    scene.shot("studio-$width")
                    if (width > 900) {
                        scene.click("预览 · 展开预览 ▾"); scene.click("仅缩略图")
                        scene.shot("studio-filmstrip")
                        scene.click("预览 · 仅缩略图 ▾"); scene.click("预览专注")
                        assertFalse(scene.nodes().any { it.matches("粘贴覆盖") })
                        scene.shot("studio-preview-focus")
                    }
                } finally { scene.close() }
            }
        } finally { f.close() }
    }
    @Test fun `zoom preserves cursor anchor and reset clears pan`() {
        val anchor = Offset(100f, 30f)
        val zoomed = DesktopViewerTransform().zoomAt(2f, anchor)
        assertEquals(anchor, anchor * zoomed.zoom + zoomed.pan)
        assertEquals(DesktopViewerTransform(), zoomed.zoomAt(1f, anchor))
    }
    @Test fun `wide Studio uses balanced resizable panes and preserves width across modes`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            for (width in listOf(1280, 1600)) {
                val scene = ImageComposeScene(width, 900) { DesktopNovelAiStudioPanel(f.container.novelAiStudioController) }
                try {
                    repeat(8) { scene.frames(); delay(20) }
                    val divider = scene.nodes().first { it.matches("调整预览宽度") }.boundsInRoot
                    assertTrue(divider.left in width * .44f..width * .56f)
                    assertEquals(12f, divider.width, 1f)
                    scene.sendPointerEvent(PointerEventType.Press, divider.center, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Move, divider.center - Offset(20f, 0f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Move, divider.center - Offset(100f, 0f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, divider.center - Offset(100f, 0f), button = PointerButton.Primary)
                    scene.frames()
                    val moved = scene.nodes().first { it.matches("调整预览宽度") }.boundsInRoot
                    assertTrue(moved.left < divider.left - 20f)
                    scene.click("预览 · 展开预览 ▾"); scene.click("仅缩略图")
                    assertTrue(scene.nodes().any { it.matches("调整预览宽度") })
                    scene.click("预览 · 仅缩略图 ▾"); scene.click("预览专注")
                    assertFalse(scene.nodes().any { it.matches("调整预览宽度") })
                    scene.click("预览 · 预览专注 ▾"); scene.click("展开预览")
                    val restored = scene.nodes().first { it.matches("调整预览宽度") }.boundsInRoot
                    assertEquals(moved.left, restored.left, 2f)
                    assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
                } finally { scene.close() }
            }
            assertEquals(.32f, desktopStudioResizeFraction(.49f, 9999f, 1f, 1280f))
            assertEquals(.68f, desktopStudioResizeFraction(.49f, -9999f, 1f, 1280f))
            assertEquals(.44f, desktopStudioResizeFraction(.49f, 128f, 2f, 1280f), .001f)
        } finally { f.close() }
    }
    @Test fun `narrow Studio keeps compact current image actions above Prompt`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            val draft = f.container.novelAiStudioController.draft.value
            val scene = ImageComposeScene(700, 700) { DesktopNovelAiStudioPanel(f.container.novelAiStudioController) }
            try {
                repeat(8) { scene.frames(); delay(20) }
                val open = scene.nodes().first { it.matches("打开预览") }.boundsInRoot
                val prompt = scene.nodes().first { it.matches("Prompt") }.boundsInRoot
                assertTrue(open.top < 250)
                assertTrue(prompt.top < 330)
                assertEquals(1, scene.nodes().count { it.matches("图像操作 / 用作") })
                scene.click("展开预览")
                assertTrue(scene.nodes().any { it.matches("收起预览") })
                assertTrue(scene.nodes().any { it.matches("Studio 内联图片预览") })
                assertTrue(scene.nodes().any { it.matches("适应 / 重置") })
                scene.click("收起预览")
                assertEquals(draft, f.container.novelAiStudioController.draft.value)
                assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
    @Test fun `Studio inline zoom drag reset and image switch preserve draft`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            val c = f.container.novelAiStudioController
            val draft = c.draft.value
            val scene = ImageComposeScene(1280, 900) { DesktopNovelAiStudioPanel(c) }
            try {
                repeat(12) { scene.frames(); delay(20) }
                val area = scene.nodes().first { it.matches("Studio 内联图片预览") }.boundsInRoot
                val center = area.center
                val fit = scene.previewHash()
                scene.sendPointerEvent(PointerEventType.Scroll, center, scrollDelta = Offset(0f, -8f))
                scene.frames()
                val zoomed = scene.previewHash()
                assertNotEquals(fit, zoomed)
                scene.sendPointerEvent(PointerEventType.Press, center, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, center + Offset(20f, 10f), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, center + Offset(100f, 45f), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, center + Offset(100f, 45f), button = PointerButton.Primary)
                scene.frames()
                assertNotEquals(zoomed, scene.previewHash())
                scene.click("适应 / 重置")
                scene.frames()
                assertEquals(fit, scene.previewHash())
                scene.sendPointerEvent(PointerEventType.Scroll, center, scrollDelta = Offset(0f, -8f))
                scene.frames()
                scene.click("选择结果缩略图 2")
                repeat(8) { scene.frames(); delay(20) }
                scene.click("选择结果缩略图 1")
                repeat(8) { scene.frames(); delay(20) }
                assertEquals(fit, scene.previewHash())
                assertEquals(draft, c.draft.value)
                assertTrue(c.taskEntries.value.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
    @Test fun `only new generation overrides explicit older result selection`() {
        assertEquals("newest", desktopSelectNewResult(null, emptyList(), listOf("first", "newest")))
        assertEquals("older", desktopSelectNewResult("older", listOf("first", "newest"), listOf("newest")))
        assertEquals("generated", desktopSelectNewResult("older", listOf("newest"), listOf("newest", "generated")))
    }
    @Test fun `chat composer cluster and assistant image actions stay compact on the right`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            val c = f.container
            c.characterRepository.save(com.example.chatbar.data.local.entity.CharacterCard.create("Fixture").copy(id = "card"))
            c.chatRepository.createSession(com.example.chatbar.data.local.entity.ChatSession(id = "session", characterCardId = "card", title = "Fixture", createdAt = 1, updatedAt = 1))
            val controller = c.primaryChatController
            controller.refresh(); controller.selectSession("session")
            val scene = ImageComposeScene(1360, 900) { DesktopPrimaryChatPanel(controller, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() }) }
            try {
                repeat(8) { scene.frames(); delay(20) }
                val expand = scene.nodes().first { it.matches("全屏编辑") }.boundsInRoot
                val send = scene.nodes().first { it.matches("发送") }.boundsInRoot
                assertTrue(expand.left > 1000 && send.left > expand.left)
                assertTrue(kotlin.math.abs(expand.bottom - send.bottom) < 2)
                assertTrue(send.top > 650)
                scene.shot("chat-composer")
            } finally { scene.close() }
            val message = com.example.chatbar.data.local.entity.ChatMessage("reply", "session", com.example.chatbar.data.local.entity.MessageRole.ASSISTANT, "Local fixture", createdAt = 1, updatedAt = 1)
            val actions = ImageComposeScene(900, 120) { DesktopAssistantImageActions(message, controller.state.value.copy(messages = listOf(message)), controller, true) }
            try {
                actions.frames()
                val generate = actions.nodes().first { it.matches("生成图片") }.boundsInRoot
                val settings = actions.nodes().first { it.matches("生图要求") }.boundsInRoot
                assertTrue(generate.left > 800 && settings.left > generate.left)
                assertTrue(generate.width <= 50 && settings.width <= 50)
                actions.shot("assistant-image-actions")
            } finally { actions.close() }
            assertTrue(c.taskRuntime.tasks.value.isEmpty())
        } finally { f.close() }
    }
    @Test fun `V5 unknown is not zero and known account renders a progress bar`() = runBlocking(awt) {
        var account by mutableStateOf(com.example.chatbar.ui.imageprompt.NovelAiAccountUiState(loading = false))
        val scene = ImageComposeScene(600, 160) { StudioAccountCluster(NovelAiImageModel.V5_FULL, account) }
        try {
            scene.frames(); assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null })
            account = account.reconcile(NovelAiAccountUsage(100, 3, true, 83.0, false)); scene.frames()
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == .83f })
            scene.shot("v5-battery")
        } finally { scene.close() }
    }

    @Test fun `history grid shift click selects visible inclusive range and refresh clears selection`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            val c = f.container.novelAiStudioController
            c.repository.saveHistoryFoldPreference(NovelAiHistoryFoldPreference(0, false, NovelAiHistoryFoldType.FULL))
            val scene = ImageComposeScene(950, 800) { DesktopStudioHistory(c, { _, _, _ -> }, { _, _ -> }, { _, _ -> }) }
            try {
                repeat(10) { scene.frames(); delay(20) }
                val images = scene.nodes().filter { it.matches("图片") }
                assertTrue(images.map { it.boundsInRoot.left }.distinct().size >= 3)
                scene.click("选择")
                assertTrue(scene.nodes().any { it.matches("范围起点") })
                scene.click("取消选择")
                assertTrue(c.state.value.selected.isEmpty())
                assertFalse(scene.nodes().any { it.matches("范围起点") })
                scene.click("选择")
                val target = scene.nodes().filter { it.matches("图片") }[2].boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, target, keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, target, keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true), button = PointerButton.Primary)
                scene.frames(); assertEquals(3, c.state.value.selected.size)
                assertFalse(scene.nodes().any { it.matches("范围起点") })
                scene.shot("history-range")
                val entry = c.history.first().first()
                c.repository.saveHistory(entry.copy(createdAt = entry.createdAt + 1)); scene.frames()
                assertTrue(c.state.value.selected.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `history grid grows with available tool window height`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            val heights = listOf(800, 1100).map { height ->
                val scene = ImageComposeScene(950, height) { DesktopStudioHistory(f.container.novelAiStudioController, { _, _, _ -> }, { _, _ -> }, { _, _ -> }) }
                try {
                    repeat(6) { scene.frames(); delay(20) }
                    val bounds = scene.nodes().first { it.matches("历史图片网格") }.boundsInRoot
                    val footer = scene.nodes().first { it.matches("Shift + 点击缩略图：从上次选择到当前相册选择可见范围；筛选或刷新后重置。") }.boundsInRoot
                    assertTrue(footer.bottom >= height - 2)
                    assertTrue(footer.top - bounds.bottom in 0f..10f)
                    scene.shot("r1-history-$height")
                    bounds.height
                } finally { scene.close() }
            }
            assertEquals(300f, heights[1] - heights[0], .5f)
        } finally { f.close() }
    }

    @Test fun `design settings history and back retain unsent composer without draft writes`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize()
            val c = f.container.novelAiStudioController
            val auth = c.designAuthentication(); val draft = c.repository.loadDraft()
            val path = f.container.appDataRoot.resolve("entities/novelai_studio_draft.json")
            val before = Files.readAllBytes(path)
            var surface by mutableStateOf("conversation")
            val scene = ImageComposeScene(950, 740) { Column(Modifier.fillMaxSize()) {
                if (surface == "conversation") DesktopDesignConversation(c, draft, false, auth,
                    { surface = "settings" }, { surface = "history" }, {}, {})
                else { StatusText(surface); BootstrapButton("返回设计") { surface = "conversation" } }
            } }
            try {
                scene.frames()
                val input = scene.nodes().first { it.matches("画面需求") && it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("未发送中文草稿")))
                scene.frames()
                for (label in listOf("设计设置", "设计历史")) {
                    scene.click(label); scene.click("返回设计")
                    assertEquals("未发送中文草稿", c.designComposer.value.input)
                    assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "未发送中文草稿" })
                }
                scene.click("新对话")
                scene.click("设计历史"); scene.click("返回设计")
                assertEquals("", c.designComposer.value.input)
                assertEquals(draft, c.repository.loadDraft()); assertContentEquals(before, Files.readAllBytes(path))
                assertTrue(f.requests.isEmpty()); assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `tool window exposes resize maximize and close without duplicating content title`() = runBlocking(awt) {
        var open by mutableStateOf(true)
        val title = "CCB isolated tool-window test"
        val scene = ImageComposeScene(1, 1) {
            if (open) DesktopImageToolWindow(title, { open = false }) { StatusText("本地工具内容") }
        }
        try {
            repeat(6) { scene.frames(); delay(25) }
            val window = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().single { it.title == title && it.isDisplayable }
            assertTrue(window.isResizable)
            assertTrue(window.width >= 800 && window.height >= 600)
            window.extendedState = java.awt.Frame.MAXIMIZED_BOTH
            delay(100); assertEquals(java.awt.Frame.MAXIMIZED_BOTH, window.extendedState)
            window.extendedState = java.awt.Frame.NORMAL
            window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING))
            scene.frames(); assertFalse(open)
        } finally { scene.close() }
    }
}
