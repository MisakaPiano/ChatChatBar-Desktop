@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Exercises the merged Shell itself, with isolated repositories and no network requests. */
class DesktopB1CIntegrationTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render().close(); yield() }; delay(40); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(s: String) =
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s) == true ||
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true
    private fun ImageComposeScene.has(s: String) = nodes().any { it.matches(s) }
    private suspend fun ImageComposeScene.click(s: String) {
        val p = nodes().first { it.matches(s) && it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, p, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, p, button = PointerButton.Primary)
        frames()
    }
    private fun ImageComposeScene.shot(name: String) = render().use { image ->
        val file = Path.of("build/phase7-b1-c-integration-evidence/$name.png")
        Files.createDirectories(file.parent); image.encodeToData()!!.use { Files.write(file, it.bytes) }; Unit
    }
    @Composable private fun Shell(f: FinalProductDesignFixture, chrome: DesktopWindowChrome,
        navigation: DesktopPrimaryNavigationController) {
        val c = f.container
        val transfer = remember { c.createTypedTransferController() }
        val management = remember { c.createManagementController(transfer) }
        val root = remember { DesktopDataRootSwitchController(
            DesktopDataRootResolution.Resolved(c.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, f.root.resolve("bootstrap.json")),
            DesktopDirectoryPicker { null }, { false }, { error("Migration not requested") }) }
        val scope = rememberCoroutineScope()
        DesktopPrimaryShell(chrome, navigation, root, transfer,
            remember { c.createModelTemplateTransferController() }, management,
            remember { c.createPromptInspectorController() }, c.primaryChatController, c.modelSettingsController,
            remember { DesktopAutomaticBackupSettingsController(c.automaticBackupRuntime, scope) },
            c.characterEditorController, c.formatCardEditorController, c.worldBookEditorController,
            c.uiLanguageController, c.appearanceController, c.connectionTestController,
            c.novelAiSettingsController, c.novelAiStudioController, {})
    }
    private suspend fun seeded(): FinalProductDesignFixture {
        val f = FinalProductDesignFixture(); f.initialize(false)
        f.container.characterRepository.save(CharacterCard.create("Integration character").copy(id = "card"))
        f.container.chatRepository.createSession(ChatSession("room", "card", "Integration room", createdAt = 1, updatedAt = 1))
        f.container.primaryChatController.refresh()
        return f
    }

    @Test fun `compact actual Shell preserves explicit chat and browser across Tools and Manage`() = runBlocking(awt) {
        val f = seeded(); val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val idle = DesktopDataRootSwitchState.Idle(f.container.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, true)
        val scene = ImageComposeScene(700, 900) { Shell(f, chrome, navigation) }
        try {
            scene.frames(); assertTrue(scene.has("搜索对话"))
            scene.click("Integration room")
            withTimeout(5_000) { while (scene.has("搜索对话")) scene.frames() }
            f.container.primaryChatController.editComposer("kept draft"); scene.frames()
            val selected = f.container.primaryChatController.state.value.selectedSession!!.id
            for (route in listOf(DesktopPrimaryRoute.TOOLS, DesktopPrimaryRoute.MANAGE)) {
                navigation.navigate(route, idle); scene.frames()
                navigation.navigate(DesktopPrimaryRoute.CHAT, idle); scene.frames()
                assertFalse(scene.has("搜索对话")); assertTrue(scene.has("展开会话列表"))
                assertEquals(selected, f.container.primaryChatController.state.value.selectedSession?.id)
                assertEquals("kept draft", f.container.primaryChatController.state.value.composerDraft)
            }
            scene.click("展开会话列表")
            navigation.navigate(DesktopPrimaryRoute.TOOLS, idle); scene.frames()
            navigation.navigate(DesktopPrimaryRoute.CHAT, idle); scene.frames()
            assertTrue(scene.has("搜索对话")); scene.shot("compact-chat-return")
        } finally { scene.close(); f.close() }
    }

    @Test fun `actual Shell Tools uses full height preview and native geometry then restores chat chrome`() = runBlocking(awt) {
        val f = seeded(); f.seedLocalImages()
        val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val idle = DesktopDataRootSwitchState.Idle(f.container.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, true)
        val scene = ImageComposeScene(1240, 800) { Shell(f, chrome, navigation) }
        try {
            scene.frames(); assertTrue(chrome.layout.captions.keys.containsAll(listOf(ChromeHit.MINIMIZE, ChromeHit.MAXIMIZE, ChromeHit.CLOSE)))
            navigation.navigate(DesktopPrimaryRoute.TOOLS, idle); scene.frames()
            val bounds = scene.nodes().first { it.matches("Studio 全高预览面板") }.boundsInRoot
            assertEquals(0f, bounds.top, 1f); assertEquals(800f, bounds.bottom, 1f)
            assertTrue(chrome.layout.dragRegions.isNotEmpty())
            val drag = chrome.layout.dragRegions.single()
            assertEquals(ChromeHit.CAPTION, DesktopChromeHitTest.hit((drag.left + drag.right) / 2, 22f, 1240f, 800f, 8f, false, chrome.layout))
            assertTrue(chrome.layout.captions.keys.containsAll(listOf(ChromeHit.MINIMIZE, ChromeHit.MAXIMIZE, ChromeHit.CLOSE)))
            scene.shot("wide-studio-shell")
            navigation.navigate(DesktopPrimaryRoute.CHAT, idle); scene.frames()
            assertTrue(chrome.layout.dragRegions.isEmpty()); assertNotNull(chrome.layout.title)
            assertTrue(scene.has("收起会话列表"))
        } finally { scene.close(); f.close() }
    }

    @Test fun `Manage start character chat enters compact chat through merged Shell callback`() = runBlocking(awt) {
        val f = seeded(); val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val idle = DesktopDataRootSwitchState.Idle(f.container.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, true)
        val scene = ImageComposeScene(700, 900) { Shell(f, chrome, navigation) }
        try {
            scene.frames(); assertTrue(scene.has("搜索对话"))
            val before = f.container.primaryChatController.state.value.sessions.map { it.id }.toSet()
            navigation.navigate(DesktopPrimaryRoute.MANAGE, idle); scene.frames()
            scene.click("角色")
            withTimeout(5_000) { while (!scene.has("Integration character")) scene.frames() }
            scene.click("开始聊天")
            withTimeout(5_000) { while (navigation.selectedRoute.value != DesktopPrimaryRoute.CHAT) scene.frames() }
            scene.frames()
            assertFalse(scene.has("搜索对话")); assertTrue(scene.has("展开会话列表"))
            val selected = f.container.primaryChatController.state.value.selectedSession!!
            assertFalse(selected.id in before); assertEquals("card", selected.characterCardId)
        } finally { scene.close(); f.close() }
    }
}
