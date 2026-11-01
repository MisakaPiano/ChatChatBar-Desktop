@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
        val file = System.getenv("CCB_SELECTED_FIX_REVIEW_DIR")?.let { Path.of(it).resolve("$name.png") }
            ?: Path.of("build/phase7-b1-c-integration-evidence/$name.png")
        Files.createDirectories(file.parent); image.encodeToData()!!.use { Files.write(file, it.bytes) }; Unit
    }
    @Composable private fun Shell(f: FinalProductDesignFixture, chrome: DesktopWindowChrome,
        navigation: DesktopPrimaryNavigationController, updateNotices: DesktopUpdateNotices? = null, enableTasks: Boolean = false,
        moments: DesktopMomentsController? = null) {
        val c = f.container
        val transfer = remember { c.createTypedTransferController() }
        val management = remember { c.createManagementController(transfer) }
        val root = remember { DesktopDataRootSwitchController(
            DesktopDataRootResolution.Resolved(c.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, f.root.resolve("bootstrap.json")),
            DesktopDirectoryPicker { null }, { false }, { error("Migration not requested") }) }
        val scope = rememberCoroutineScope()
        CompositionLocalProvider(LocalDesktopApiAccounts provides c.apiAccountController,
            LocalDesktopUpdateNotices provides updateNotices,
            LocalDesktopTaskCenter provides if (enableTasks) c.taskCenter else null) {
        DesktopPrimaryShell(chrome, navigation, root, transfer,
            remember { c.createModelTemplateTransferController() }, management,
            remember { c.createPromptInspectorController() }, c.primaryChatController, c.modelSettingsController,
            remember { DesktopAutomaticBackupSettingsController(c.automaticBackupRuntime, scope) },
            c.characterEditorController, c.formatCardEditorController, c.worldBookEditorController,
            c.uiLanguageController, c.appearanceController, c.connectionTestController,
            c.novelAiSettingsController, c.novelAiStudioController, c.fullBackupController, {},
            taskCenter = if (enableTasks) c.taskCenter else null, momentsController = moments)
        }
    }
    @Test fun `actual Shell opens tasks over its busy backup surface but keeps data navigation and original stop boundary guarded`() = runBlocking(awt) {
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? = null
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? {
                entered.complete(Unit); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)); return null
            }
        }
        val f = FinalProductDesignFixture(picker); f.initialize(false)
        val c = f.container; val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val activity = DesktopModalActivity()
        val scene = ImageComposeScene(1100, 800) {
            CompositionLocalProvider(LocalDesktopModalActivity provides activity, LocalDesktopTaskCenter provides c.taskCenter) {
                Box {
                    Shell(f, chrome, navigation, enableTasks = true)
                    val backup by c.fullBackupController.state.collectAsState()
                    DesktopBackupExportDialog(backup, {}, {}, { _, _ -> error("No export submission from this fixture surface") }, c.fullBackupController::stop)
                }
            }
        }
        try {
            scene.frames(); c.fullBackupController.export(null)
            withTimeout(5000) { entered.await() }; scene.frames()
            assertEquals(1, activity.backupCount)
            assertTrue(scene.nodes().first { it.matches(DesktopUiText.RECOVERY_SUPPORT.zhCn) && it.config.contains(SemanticsActions.OnClick) }
                .config.contains(SemanticsProperties.Disabled))
            assertTrue(scene.nodes().first { it.matches(DesktopUiText.CONTEXT_HELP.zhCn) && it.config.contains(SemanticsActions.OnClick) }
                .config.contains(SemanticsProperties.Disabled))
            scene.click("查看任务中心"); scene.frames()
            assertTrue(scene.has("任务中心 · 进行中 1"))
            val before = navigation.selectedRoute.value
            scene.click("前往页面")
            assertEquals(before, navigation.selectedRoute.value)
            assertTrue(scene.has("当前操作尚未结束，请稍后从任务中心前往。"))
            assertTrue(scene.has("任务中心 · 进行中 1"))
            scene.click("停止")
            withTimeout(5000) { c.taskCenter.state.first { it.rows.any { row -> row.stopRequested } } }
            withTimeout(5000) { c.fullBackupController.workRows.first { it.single().stopRequested } }
            assertTrue(c.fullBackupController.state.value.busy)
            release.countDown()
            withTimeout(5000) { c.fullBackupController.workRows.first { it.single().phase == DesktopWorkPhase.CANCELLED } }
            scene.frames(); scene.click("关闭任务中心")
            assertTrue(scene.has("选择导出内容")); assertEquals(1, activity.backupCount)
        } finally { release.countDown(); scene.close(); f.close() }
    }
    private suspend fun seeded(): FinalProductDesignFixture {
        val f = FinalProductDesignFixture(); f.initialize(false)
        f.container.characterRepository.save(CharacterCard.create("Integration character").copy(id = "card"))
        f.container.chatRepository.createSession(ChatSession("room", "card", "Integration room", createdAt = 1, updatedAt = 1))
        f.container.primaryChatController.refresh()
        return f
    }

    @Test fun `actual Shell context help closes and reopens without navigating saving drafts or starting work`(): Unit = runBlocking(awt) {
        val f = seeded(); val c = f.container
        val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val idle = DesktopDataRootSwitchState.Idle(c.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, true)
        c.primaryChatController.selectSession("room"); c.primaryChatController.editComposer("Unsaved help-test draft")
        c.characterEditorController.openExisting("card")
        c.characterEditorController.edit { it.copy(basicSetting = "Unsaved editor help-test draft") }
        val dirty = c.characterEditorController.state.value.card
        val pin = c.characterEditorController.state.value.draftSessionId
        val cardPath = c.appDataRoot.resolve("entities/character_cards/card.json")
        val bytes = Files.readAllBytes(cardPath)
        val activity = DesktopModalActivity()
        val scene = ImageComposeScene(1100, 800) {
            CompositionLocalProvider(LocalDesktopModalActivity provides activity) { Shell(f, chrome, navigation, enableTasks = true) }
        }
        try {
            for (route in listOf(DesktopPrimaryRoute.CHAT, DesktopPrimaryRoute.MANAGE, DesktopPrimaryRoute.TOOLS, DesktopPrimaryRoute.DATA)) {
                navigation.navigate(route, idle); scene.frames()
                val requests = f.requests.toList()
                val work = c.taskCenter.state.value.rows
                repeat(2) {
                    scene.click(DesktopUiText.CONTEXT_HELP.zhCn)
                    assertTrue(scene.has(DesktopUiText.CLOSE_CONTEXT_HELP.zhCn))
                    assertTrue(scene.has("${DesktopUiText.CONTEXT_HELP.zhCn} · ${desktopContextHelp(route).first.zhCn}"))
                    assertEquals(route, navigation.selectedRoute.value)
                    assertEquals(1, activity.otherCount)
                    scene.click(DesktopUiText.CLOSE_CONTEXT_HELP.zhCn)
                    assertFalse(scene.has(DesktopUiText.CLOSE_CONTEXT_HELP.zhCn))
                    assertEquals(0, activity.count)
                }
                assertEquals(requests, f.requests.toList())
                assertEquals(work, c.taskCenter.state.value.rows)
                assertEquals("Unsaved help-test draft", c.primaryChatController.state.value.composerDraft)
                assertEquals(dirty, c.characterEditorController.state.value.card)
                assertEquals(pin, c.characterEditorController.state.value.draftSessionId)
                assertContentEquals(bytes, Files.readAllBytes(cardPath))
            }
            // An already-open general modal retains priority, even for a stale semantics action.
            activity.opened(); scene.frames()
            val help = scene.nodes().first { it.matches(DesktopUiText.CONTEXT_HELP.zhCn) && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(help.config.contains(SemanticsProperties.Disabled))
            help.config[SemanticsActions.OnClick].action?.invoke(); scene.frames()
            assertFalse(scene.has(DesktopUiText.CLOSE_CONTEXT_HELP.zhCn)); assertEquals(1, activity.count)
            activity.closed(); scene.frames()
        } finally { scene.close(); f.close() }
    }

    @Test fun `actual Shell task navigation reveals only its matching character candidate without generation or losing draft`(): Unit = runBlocking(awt) {
        val f = seeded(); val c = f.container
        val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val persisted = assertNotNull(c.characterRepository.getById("card"))
        val persistedPath = c.appDataRoot.resolve("entities/character_cards/card.json")
        val persistedBytes = Files.readAllBytes(persistedPath)
        c.characterEditorController.openExisting("card")
        val editor = c.characterEditorController
        val draft = assertNotNull(editor.state.value.draftSessionId)
        assertNotNull(editor.state.value.card)
        editor.edit { it.copy(basicSetting = "Synthetic unsaved editor text") }
        val dirty = assertNotNull(editor.state.value.card)
        val ai = assertNotNull(editor.cardAi)
        ai.open(com.example.chatbar.domain.card.CharacterCardAiOperation.FILL)
        val work = async { c.taskRuntime.withCardDesign(draft, creativeKind = DesktopOwnedWorkKind.CHARACTER_AI) {
            entered.complete(Unit); release.await()
        } }
        val activity = DesktopModalActivity()
        val scene = ImageComposeScene(1100, 800) {
            CompositionLocalProvider(LocalDesktopModalActivity provides activity) { Shell(f, chrome, navigation, enableTasks = true) }
        }
        try {
            withTimeout(5000) { entered.await(); c.taskCenter.state.first { it.rows.any { row -> row.kind == DesktopWorkKind.CHARACTER_AI && row.active } } }
            scene.frames(); c.taskCenter.requestOpen(); scene.frames()
            scene.click("前往页面"); scene.frames()
            assertEquals(DesktopPrimaryRoute.MANAGE, navigation.selectedRoute.value)
            assertNull(c.taskCenter.state.value.error)
            assertTrue(ai.state.value.open); assertNull(ai.state.value.candidate)
            assertEquals(draft, editor.state.value.draftSessionId); assertEquals(dirty, editor.state.value.card)
            assertEquals(persisted, c.characterRepository.getById("card")); assertContentEquals(persistedBytes, Files.readAllBytes(persistedPath))
            assertTrue(f.requests.isEmpty())
            assertTrue(work.isActive)
        } finally { release.complete(Unit); work.cancelAndJoin(); scene.close(); f.close() }
    }

    @Test fun `actual Shell refuses disabled Moments task target and opens its enabled page without generation`(): Unit = runBlocking(awt) {
        val f = seeded(); val c = f.container
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val moments = DesktopMomentsController(DesktopMomentStore(c.jsonFileStorage, c.dataOperationCoordinator),
            c.settingsRepository, c.characterRepository, c.chatRepository, c.dataOperationCoordinator, c.taskRuntime,
            DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> calls.incrementAndGet(); error("NO_GENERATION_FROM_TASK_NAVIGATION") })
        val navigation = DesktopPrimaryNavigationController(); val chrome = DesktopWindowChrome {}
        c.taskCenter.watch("moment-home-fixture", kotlinx.coroutines.flow.flowOf(listOf(DesktopWorkRow("original", DesktopWorkKind.MOMENT,
            DesktopWorkPhase.RUNNING, 1, target = DesktopWorkTarget(DesktopPrimaryRoute.MOMENTS, sessionId = "room", detail = DesktopWorkDetail.MOMENTS_HOME)))))
        val scene = ImageComposeScene(1100, 800) { Shell(f, chrome, navigation, enableTasks = true, moments = moments) }
        try {
            withTimeout(5000) { c.taskCenter.state.first { it.rows.any { row -> row.kind == DesktopWorkKind.MOMENT } } }
            scene.frames(); c.taskCenter.requestOpen(); scene.frames(); scene.click("前往页面")
            assertEquals(DesktopPrimaryRoute.CHAT, navigation.selectedRoute.value)
            assertNotNull(c.taskCenter.state.value.error)
            c.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }; moments.refresh()
            scene.frames(); scene.click("关闭任务提示"); scene.click("前往页面"); scene.frames()
            assertEquals(DesktopPrimaryRoute.MOMENTS, navigation.selectedRoute.value)
            assertNull(c.taskCenter.state.value.error); assertEquals(0, calls.get()); assertTrue(f.requests.isEmpty())
            assertFalse(moments.state.value.scheduling)
        } finally { scene.close(); moments.closeAndDrain(); f.close() }
    }

    @Test fun `startup notice later retains data badge and its actual Shell entry opens the shared update page`(): Unit = runBlocking(awt) {
        val f = seeded(); val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val scope = CoroutineScope(SupervisorJob() + awt)
        var checks = 0
        val notices = DesktopUpdateNotices(scope, "0.1.0-preview.100", null, true) {
            checks++
            DesktopUpdateResult(DesktopRelease("0.1.0-preview.101", null, "合成更新说明",
                "https://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0.1.0-preview.101", true), true)
        }
        val otherModal = mutableStateOf(true)
        val modalActivity = DesktopModalActivity()
        val scene = ImageComposeScene(1240, 800) {
            CompositionLocalProvider(LocalDesktopModalActivity provides modalActivity) {
                Box {
                    Shell(f, chrome, navigation, notices)
                    if (otherModal.value) DesktopModalSurface { StatusText("合成已有错误") }
                }
            }
        }
        try {
            notices.checkOnStartup()!!.join(); scene.frames()
            assertTrue(scene.has("合成已有错误")); assertFalse(scene.has("发现新版本"))
            assertTrue(notices.state.value.showStartupNotice, "Another modal defers rather than consumes the notice")
            otherModal.value = false; scene.frames()
            assertTrue(scene.has("发现新版本")); assertTrue(scene.has("数据，有新版本"))
            val close = scene.nodes().single { it.matches("关闭更新提醒") }
            assertTrue(close.boundsInRoot.width <= 30f && close.boundsInRoot.height <= 30f)
            scene.shot("startup-update-notice")
            scene.click("稍后")
            assertFalse(scene.has("发现新版本")); assertTrue(scene.has("数据，有新版本"))
            scene.click("数据，有新版本"); scene.frames()
            assertEquals(DesktopPrimaryRoute.DATA, navigation.selectedRoute.value)
            assertTrue(scene.has("启动后自动检查一次；下载与安装仍由你确认。"))
            assertTrue(scene.has("合成更新说明")); assertEquals(1, checks)
            scene.shot("startup-update-page")
            scene.click("检查更新"); scene.frames()
            assertEquals(2, checks); assertFalse(notices.state.value.showStartupNotice)
        } finally { scene.close(); notices.closeAndDrain(); scope.cancel(); f.close() }
    }

    @Test fun `wide workspace navigation preserves all management data and tool destinations`() = runBlocking(awt) {
        val f = seeded(); val chrome = DesktopWindowChrome {}; val navigation = DesktopPrimaryNavigationController()
        val idle = DesktopDataRootSwitchState.Idle(f.container.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, true)
        val scene = ImageComposeScene(1240, 800) { Shell(f, chrome, navigation) }
        try {
            scene.frames()
            navigation.navigate(DesktopPrimaryRoute.MANAGE, idle); scene.frames()
            assertTrue(scene.has("导入导出")); assertTrue(scene.has("角色")); assertTrue(scene.has("模型")); assertTrue(scene.has("设置"))
            scene.shot("workspace-manage")
            scene.click("设置"); scene.frames(); scene.shot("workspace-settings")
            navigation.navigate(DesktopPrimaryRoute.DATA, idle); scene.frames()
            assertTrue(scene.has("备份与导入")); assertTrue(scene.has("版本与更新")); scene.shot("workspace-data")
            navigation.navigate(DesktopPrimaryRoute.TOOLS, idle); scene.frames()
            assertTrue(scene.has("高级 / 诊断")); scene.shot("workspace-tools")
        } finally { scene.close(); f.close() }
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
            val status = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "studio-global-status" }.boundsInRoot
            assertEquals(assertNotNull(chrome.layout.title).bottom, bounds.top, 1f)
            assertEquals(status.top, bounds.bottom, 1f)
            assertTrue(bounds.height > 600f); assertEquals(800f, status.bottom, 1f)
            assertTrue(chrome.layout.dragRegions.isEmpty())
            val title = assertNotNull(chrome.layout.title)
            // Fixed help/tasks can abut captions. Find real measured blank space between controls,
            // rather than treating their shared boundary as a draggable point.
            val occupied = (chrome.layout.interactive + chrome.layout.captions.values + listOfNotNull(chrome.layout.icon))
                .filter { it.right > it.left }.sortedBy { it.left }
            val gap = assertNotNull(occupied.zipWithNext().filter { (left, right) -> right.left - left.right > 16f }
                .maxByOrNull { (left, right) -> right.left - left.right })
            val captionGap = (gap.first.right + gap.second.left) / 2
            assertEquals(ChromeHit.CAPTION, DesktopChromeHitTest.hit(captionGap, (title.top + title.bottom) / 2, 1240f, 800f, 8f, false, chrome.layout))
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
