@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.voice.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

internal fun dumpFishActionSemantics(nodes: List<SemanticsNode>) {
    nodes.filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { name ->
        name.contains("音色") || name.contains("免费预览")
    } == true || it.config.contains(SemanticsActions.OnClick) }.forEach { node ->
        println("FISH_SEMANTICS id=${node.id} parent=${node.parent?.id} keys=${node.config.map { it.key.name }} " +
            "description=${node.config.getOrNull(SemanticsProperties.ContentDescription)} text=${node.config.getOrNull(SemanticsProperties.Text)} " +
            "role=${node.config.getOrNull(SemanticsProperties.Role)} disabled=${node.config.contains(SemanticsProperties.Disabled)} " +
            "click=${node.config.contains(SemanticsActions.OnClick)} focus=${node.config.contains(SemanticsActions.RequestFocus)} bounds=${node.boundsInRoot}")
    }
}

class DesktopFishPickerLayoutTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Driver(val scene: ImageComposeScene) {
        private var clock = 0L
        suspend fun frame() { clock += 16_666_667; scene.render(clock).close(); yield(); delay(10) }
        suspend fun until(check: () -> Boolean) = withTimeout(5_000) { do { frame() } while (!check()) }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun has(label: String) = nodes().any { it.label(label) }
        fun node(label: String) = nodes().first { it.label(label) }
        suspend fun tap(label: String) {
            until { has(label) }
            val point = node(label).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
            repeat(3) { frame() }
        }
        suspend fun text(label: String, value: String) {
            val field = nodes().single { it.label(label) && it.config.contains(SemanticsActions.SetText) }
            assertTrue(field.config[SemanticsActions.SetText].action!!(AnnotatedString(value)))
            repeat(3) { frame() }
        }
    }
    private companion object {
        fun SemanticsNode.label(value: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true ||
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    }

    @Test fun `shared StudioAction defaults and explicit accessible names retain real button keyboard behavior`() = runBlocking(awt) {
        var calls = 0
        val scene = ImageComposeScene(500, 500) {
            Column {
                StudioActionStyle.entries.forEach { style ->
                    StudioAction("Default $style", style = style, selected = style == StudioActionStyle.CHIP) { calls++ }
                }
                StudioAction("Short", accessibilityLabel = "Complete synthetic action") { calls++ }
                StudioAction("Disabled", enabled = false, accessibilityLabel = "Complete disabled action") { calls++ }
            }
        }
        val ui = Driver(scene)
        try {
            ui.until { ui.has("Complete synthetic action") }
            StudioActionStyle.entries.forEach { style ->
                val button = ui.node("Default $style")
                assertEquals(listOf("Default $style"), button.config[SemanticsProperties.ContentDescription])
                assertEquals(Role.Button, button.config[SemanticsProperties.Role])
                assertFalse(button.config.contains(SemanticsProperties.Disabled))
                assertTrue(button.config.contains(SemanticsActions.OnClick))
                assertTrue(button.config.contains(SemanticsActions.RequestFocus))
            }
            assertTrue(ui.node("Default CHIP").config[SemanticsProperties.Selected])
            val custom = ui.node("Complete synthetic action")
            assertEquals(Role.Button, custom.config[SemanticsProperties.Role])
            assertEquals(listOf("Short"), custom.config[SemanticsProperties.Text].map { it.text })
            val disabled = ui.node("Complete disabled action")
            assertEquals(Role.Button, disabled.config[SemanticsProperties.Role])
            assertTrue(disabled.config.contains(SemanticsProperties.Disabled))
            ui.tap("Complete disabled action"); assertEquals(0, calls)
            assertTrue(custom.config[SemanticsActions.RequestFocus].action!!())
            repeat(3) { ui.frame() }
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            ui.until { calls == 1 }
        } finally { scene.close() }
    }

    @Test fun `wide sidebar compact viewport scroll themes and keyboard binding preserve query count`() = runBlocking(awt) {
        val scope = CoroutineScope(SupervisorJob() + awt)
        var requests = 0
        val voices = (0..29).map { FishAudioModel("voice-$it", "Voice $it") }
        val picker = DesktopFishPickerController(scope) { requests++; FishAudioModelPage(items = voices, total = 30) }
        val used = mutableListOf<FishAudioModel>()
        var dark by mutableStateOf(false)
        val scene = ImageComposeScene(1100, 760) {
            CompositionLocalProvider(LocalDesktopPalette provides desktopSemanticColors(
                if (dark) ThemeMode.DARK else ThemeMode.LIGHT, DefaultThemeColorHsv, false)) {
                DesktopFishPickerContent(picker, true, {}, onUse = used::add)
            }
        }
        val ui = Driver(scene)
        try {
            ui.until { ui.has("Fish 宽屏分栏") }
            assertEquals(0, requests)
            assertFalse(ui.has("标签（逗号分隔）"))
            ui.tap("查询 / 重试")
            ui.until { ui.has("使用音色 · Voice 0") }
            assertEquals(288f, ui.node("Fish 搜索设置").boundsInRoot.width, .1f)
            assertTrue(ui.node("Fish 音色结果").boundsInRoot.left >= 304f)
            ui.tap("预览目标 · Voice 0")
            assertTrue(used.isEmpty())
            scene.constraints = Constraints.fixed(360, 640)
            ui.until { ui.has("Fish 紧凑单列") }
            val viewport = ui.node("Fish 音色列表").boundsInRoot
            val button = ui.node("使用音色 · Voice 0").boundsInRoot
            assertTrue(viewport.height >= 250f)
            assertTrue(button.width > 0 && button.left >= 0 && button.right <= 360 &&
                button.top >= viewport.top && button.bottom <= viewport.bottom)
            assertTrue(ui.has("已设为预览目标；尚未绑定"))
            assertFalse(ui.has("生成 / 重播免费预览"))
            // The card contains two actions, with full accessible names and a keyboard target.
            val use = ui.node("使用音色 · Voice 0")
            dumpFishActionSemantics(ui.nodes())
            assertEquals(Role.Button, use.config.getOrNull(SemanticsProperties.Role))
            assertTrue(use.config[SemanticsActions.RequestFocus].action!!())
            repeat(3) { ui.frame() }
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            ui.until { used.size == 1 }
            assertEquals("voice-0", used.single().id)
            dark = true
            repeat(4) { ui.frame() }
            assertTrue(ui.has("Fish 紧凑单列")); assertEquals(1, requests)
            fun range() = ui.node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range().value()
            withTimeout(5_000) {
                while (range().value() <= before) {
                    scene.sendPointerEvent(PointerEventType.Scroll, viewport.center, scrollDelta = Offset(0f, 6f))
                    repeat(4) { ui.frame() }
                }
            }
            assertTrue(range().maxValue() > 0f && range().value() > before)
            assertEquals(1, requests); assertEquals(1, used.size)
        } finally { scene.close(); picker.close(); scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }

    @Test fun `fold resize and stale action callbacks retain text without implicit preview or binding`() = runBlocking(awt) {
        val scope = CoroutineScope(SupervisorJob() + awt)
        val server = MockWebServer().also { it.start() }
        val output = PreviewOutputFake()
        val credentials = DesktopFishAudioCredentials(com.example.chatbar.desktop.security.InMemoryDesktopSecretStore())
        val runtime = DesktopFishPreviewRuntime(
            DesktopFishPreviewTransport(credentials, OkHttpClient(), server.url("/").toString().trimEnd('/')), output,
            samples = DesktopFishSampleTransport(OkHttpClient()) { server.url(it.encodedPath) })
        val preview = runtime.openSession()
        var requests = 0
        val voice = FishAudioModel("target", "Target", samples = listOf(FishAudioSample(audio = syntheticOfficialSample)))
        val picker = DesktopFishPickerController(scope) { requests++; FishAudioModelPage(items = listOf(voice), total = 1) }
        val used = mutableListOf<FishAudioModel>()
        val scene = ImageComposeScene(1000, 800) {
            DesktopFishPickerContent(picker, true, { picker.close(); preview.requestClose() }, preview, used::add)
        }
        val ui = Driver(scene)
        try {
            ui.until { ui.has("免费文本生成") }
            assertFalse(ui.has("免费预览文本（1–120 字）")); assertFalse(ui.has("标签（逗号分隔）"))
            ui.tap("查询 / 重试"); ui.until { ui.has("预览目标 · Target") }
            ui.tap("预览目标 · Target"); ui.tap("免费文本生成")
            ui.until { ui.has("生成 / 重播免费预览") }
            dumpFishActionSemantics(ui.nodes())
            assertTrue(ui.node("生成 / 重播免费预览").config.contains(SemanticsProperties.Disabled))
            ui.text("免费预览文本（1–120 字）", "合成 preview text")
            assertFalse(ui.node("生成 / 重播免费预览").config.contains(SemanticsProperties.Disabled))
            scene.constraints = Constraints.fixed(360, 640)
            ui.until { ui.has("Fish 折叠设置内容") }
            val field = ui.nodes().single { it.label("免费预览文本（1–120 字）") && it.config.contains(SemanticsActions.SetText) }
            assertEquals("合成 preview text", field.config[SemanticsProperties.EditableText].text)
            assertTrue(ui.has("预览目标 · Target"))
            ui.tap("返回音色列表")
            scene.constraints = Constraints.fixed(1000, 800)
            ui.until { ui.has("Fish 宽屏分栏") }
            ui.tap("免费文本生成")
            ui.until { ui.has("生成 / 重播免费预览") }
            val generate = ui.node("生成 / 重播免费预览").config[SemanticsActions.OnClick].action!!
            val bind = ui.node("使用音色 · Target").config[SemanticsActions.OnClick].action!!
            val sample = ui.node("试听官方样本 · Target").config[SemanticsActions.OnClick].action!!
            ui.text("音色标题", "new query") // Invalidates the old page/target without sending.
            assertTrue(ui.node("生成 / 重播免费预览").config.contains(SemanticsProperties.Disabled))
            generate(); bind(); sample() // Previously captured UI callbacks must still revalidate.
            repeat(5) { ui.frame() }
            assertEquals(1, requests); assertEquals(0, server.requestCount)
            assertEquals(0, output.plays); assertTrue(used.isEmpty())
            picker.close()
            generate(); bind(); sample()
            repeat(3) { ui.frame() }
            assertEquals(0, server.requestCount); assertTrue(used.isEmpty())
        } finally {
            scene.close(); picker.close(); runtime.closeAndDrain(); scope.cancel()
            scope.coroutineContext[Job]!!.join(); server.close()
        }
    }

    @Test fun `error empty loading and late catalog completion stay separate after close`() = runBlocking(awt) {
        val scope = CoroutineScope(SupervisorJob() + awt)
        val late = CompletableDeferred<FishAudioModelPage>()
        var calls = 0
        var providerFinished = false
        val picker = DesktopFishPickerController(scope) {
            when (++calls) {
                1 -> throw IllegalStateException("injected-not-user-visible")
                2 -> FishAudioModelPage()
                else -> withContext(NonCancellable) { late.await().also { providerFinished = true } }
            }
        }
        var used = 0
        val scene = ImageComposeScene(360, 640) { DesktopFishPickerContent(picker, true, {}, onUse = { used++ }) }
        val ui = Driver(scene)
        try {
            ui.tap("查询 / 重试")
            ui.until { ui.has("音色查询失败，请检查网络后重试") }
            assertFalse(ui.has("暂无音色；请查询或调整筛选")); assertFalse(ui.has("injected-not-user-visible"))
            ui.tap("查询 / 重试")
            ui.until { ui.has("暂无音色；请查询或调整筛选") }
            assertFalse(ui.has("音色查询失败，请检查网络后重试"))
            ui.tap("查询 / 重试"); ui.until { ui.has("正在查询…") }
            assertFalse(ui.has("暂无音色；请查询或调整筛选"))
            picker.close(); late.complete(FishAudioModelPage(items = listOf(FishAudioModel("late", "Late"))))
            ui.until { providerFinished }
            assertNull(picker.selectable("late")); assertFalse(ui.has("使用音色 · Late")); assertEquals(0, used)
        } finally {
            late.complete(FishAudioModelPage()); scene.close(); picker.close(); scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }
}
