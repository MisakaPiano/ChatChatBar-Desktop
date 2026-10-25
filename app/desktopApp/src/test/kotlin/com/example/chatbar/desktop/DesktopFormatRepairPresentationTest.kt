@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.StreamEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Native-backed offscreen Compose; no Robot, formal app launch or paid Provider. */
class DesktopFormatRepairPresentationTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(text: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    private suspend fun ImageComposeScene.until(check: () -> Boolean) = withTimeout(8000) {
        do { render().close(); yield(); delay(10) } while (!check())
    }
    private suspend fun ImageComposeScene.tap(text: String, button: PointerButton = PointerButton.Primary) {
        until { nodes().any { it.label(text) } }
        val point = nodes().first { it.label(text) }.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, point, button = button)
        sendPointerEvent(PointerEventType.Release, point, button = button)
        render().close(); yield()
    }

    @Test fun `segment pointer menu launches full candidate repair and Stop restores through same repository`() = runBlocking(awt) {
        fishFixture { _, c ->
            val f = DesktopRepairInteractionFixture(c)
            try {
                f.prepare()
                val entered = CompletableDeferred<Unit>()
                f.response = { flow { emit(StreamEvent.Delta("🦊")); entered.complete(Unit); awaitCancellation() } }
                val scene = ImageComposeScene(800, 700) {
                    val state by f.primary.state.collectAsState()
                    val tasks by c.taskRuntime.repairTasks.collectAsState()
                    val scope = rememberCoroutineScope()
                    val clipboard = DesktopSafeClipboard(LocalClipboard.current)
                    val running = tasks.values.any { it == DesktopRepairStatus.RUNNING }
                    val message = state.messages.firstOrNull()
                    if (message != null) PrimaryMessageBubble(message, state.copy(assistantSegmentedBubblesEnabled = true),
                        f.primary, clipboard, desktopMessageActions(state.messages, message, null, true, running, running),
                        onAction = { action -> when (action) {
                            DesktopMessageAction.REPAIR_FORMAT -> scope.launch { f.primary.checkMessageFormat(message) }
                            DesktopMessageAction.RESTORE_FORMAT -> scope.launch { f.primary.restoreMessageFormat(message) }
                            DesktopMessageAction.STOP_FORMAT -> f.primary.stopMessageFormat(message)
                            else -> Unit
                        } })
                }
                try {
                    scene.tap("Middle", PointerButton.Secondary)
                    scene.until { scene.nodes().any { it.label("回复 · 修复格式") } }
                    assertFalse(scene.nodes().any { it.label("本段 · 修复格式") })
                    scene.tap("回复 · 修复格式")
                    scene.until { entered.isCompleted }
                    assertEquals(f.original.displayContent, f.requests.single().original)
                    assertEquals(f.original, f.saved())
                    scene.until { scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "format-repair-preview-${f.original.id}" } }
                    val id = assertNotNull(c.taskRuntime.runningRepair("A", f.original.id)).taskId
                    scene.tap("停止格式修复")
                    val stopped = f.await(id)
                    assertEquals(DesktopRepairStatus.STOPPED, stopped.status)
                    f.primary.refreshAfterTerminalTask("A")
                    scene.until { scene.nodes().any { it.label("用户停止修复") } }
                    scene.tap("恢复原文")
                    scene.until { f.repair.states.value.values.any { it.status == DesktopRepairStatus.RESTORED } }
                    assertEquals(f.original.displayContent, f.saved().displayContent)
                    assertNull(f.saved().formatRepairNotice)
                } finally { scene.close() }
            } finally { f.close() }
        }
    }

    @Test fun `persisted notice after restart is sanitized and only valid recovery shows button`() = runBlocking(awt) {
        val message = ChatMessage("m", "s", MessageRole.ASSISTANT, "fixed", createdAt = 1, updatedAt = 1)
        for (kind in MessageFormatRepairNoticeKind.entries) {
            val notice = MessageFormatRepairNotice(kind, "fixed", "original", "PRIVATE exception and credential")
            val scene = ImageComposeScene(500, 250) {
                DesktopMessageFormatRepairStatus(message.copy(formatRepairNotice = notice), null, false, {}, {})
            }
            try {
                scene.until { scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "format-repair-status-m" } }
                assertEquals(kind in setOf(MessageFormatRepairNoticeKind.APPLIED, MessageFormatRepairNoticeKind.STOPPED),
                    scene.nodes().any { it.label("恢复原文") })
                assertFalse(scene.nodes().any { it.label("PRIVATE exception and credential") })
            } finally { scene.close() }
        }
        val invalid = ImageComposeScene(500, 250) {
            DesktopMessageFormatRepairStatus(message.copy(formatRepairNotice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED,
                "other candidate", "original")), null, false, {}, {})
        }
        try { invalid.render().close(); assertTrue(invalid.nodes().none { it.label("恢复原文") }) } finally { invalid.close() }
    }

    @Test fun `uncertain commit warning is distinct and offers no automatic or recovery action`() = runBlocking(awt) {
        val message = ChatMessage("m", "s", MessageRole.ASSISTANT, "fixed", createdAt = 1, updatedAt = 1,
            formatRepairNotice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED, "fixed", "original"))
        var restored = 0; var stopped = 0
        val scene = ImageComposeScene(700, 250) {
            DesktopMessageFormatRepairStatus(message, DesktopRepairState(DesktopRepairStatus.COMMIT_UNCERTAIN), false,
                { stopped++ }, { restored++ })
        }
        try {
            val expected = DesktopUiStrings(DesktopUiLanguage.ZH_CN)(DesktopUiText.FORMAT_UNCERTAIN)
            scene.until { scene.nodes().any { it.label(expected) } }
            assertFalse(scene.nodes().any { it.label("恢复原文") || it.label("停止格式修复") })
            assertEquals(0, restored); assertEquals(0, stopped)
        } finally { scene.close() }
    }

    @Test fun `real uncertain second repair hides pointer menu actions until explicit verified read`() = runBlocking(awt) {
        val f = DesktopMessageFormatRepairTest.Fixture(faultInjection = true)
        val primary = f.primary()
        try {
            f.prepare(); f.appliedThenUncertain()
            primary.refresh(); primary.selectSession("s")
            val scene = ImageComposeScene(800, 700) {
                val state by primary.state.collectAsState()
                val repairs by f.controller.states.collectAsState()
                val scope = rememberCoroutineScope()
                val message = state.messages.firstOrNull()
                if (message != null) PrimaryMessageBubble(message, state, primary, DesktopSafeClipboard(LocalClipboard.current),
                    desktopMessageActions(state.messages, message, null, true, repairState = desktopRepairStateFor(message, repairs)),
                    onAction = { action -> when (action) {
                        DesktopMessageAction.REPAIR_FORMAT -> scope.launch { primary.checkMessageFormat(message) }
                        DesktopMessageAction.RESTORE_FORMAT -> scope.launch { primary.restoreMessageFormat(message) }
                        else -> Unit
                    } })
            }
            try {
                val warning = DesktopUiStrings(DesktopUiLanguage.ZH_CN)(DesktopUiText.FORMAT_UNCERTAIN)
                scene.until { scene.nodes().any { it.label(warning) } }
                assertFalse(scene.nodes().any { it.label("恢复原文") })
                scene.tap("second fixed", PointerButton.Secondary)
                scene.until { scene.nodes().any { it.label("整条 · 复制") } }
                assertTrue(scene.nodes().single { it.label("回复 · 修复格式") }.config.contains(SemanticsProperties.Disabled))
                scene.tap("整条 · 复制")
                scene.tap("重新加载并核对消息")
                scene.until { scene.nodes().any { it.label("恢复原文") } }
                scene.tap("second fixed", PointerButton.Secondary)
                scene.until { scene.nodes().any { it.label("回复 · 修复格式") } }
                assertFalse(scene.nodes().single { it.label("回复 · 修复格式") }.config.contains(SemanticsProperties.Disabled))
                assertEquals(2, f.requests.get())
            } finally { scene.close() }
        } finally { primary.closeDraftPersistence(); f.close() }
    }

    @Test fun `format model selector includes hidden auxiliary models and retains explicit unavailable ID`() = runBlocking(awt) {
        fishFixture { _, c ->
            val model = ModelConfig.create("Hidden repair fixture", "https://fixture.invalid", "synthetic-key", "fixture")
                .copy(selectableForChat = false)
            c.modelRepository.saveModel(model)
            c.settingsRepository.saveAppSettings(AppSettings(formatRepairModelId = "stale"))
            val editor = c.modelSettingsController; editor.loadSettings()
            val scene = ImageComposeScene(700, 400) {
                val state by editor.state.collectAsState()
                Column { DesktopFormatRepairModelSetting(state) { id -> editor.editSettings { it.copy(formatRepairModelId = id) } } }
            }
            try {
                scene.until { scene.nodes().any { it.label("格式修复模型 · 已选模型不可用 · stale ▾") } }
                assertEquals("stale", c.settingsRepository.getAppSettings().formatRepairModelId)
                assertTrue(editor.state.value.availableChatModels.none { it.id == model.id })
                scene.tap("格式修复模型 · 已选模型不可用 · stale ▾"); scene.tap("Hidden repair fixture")
                scene.until { editor.state.value.settings?.formatRepairModelId == model.id }
                assertEquals("stale", c.settingsRepository.getAppSettings().formatRepairModelId)
                editor.saveAppSettings(); assertFalse(editor.state.value.chatDefaultsDirty)
                assertEquals(model.id, c.settingsRepository.getAppSettings().formatRepairModelId)
                scene.tap("格式修复模型 · Hidden repair fixture ▾"); scene.tap("跟随默认对话模型")
                scene.until { editor.state.value.settings?.formatRepairModelId == null }
                editor.discardChatDefaults(); assertEquals(model.id, editor.state.value.settings?.formatRepairModelId)
                editor.loadSettings(); assertEquals(model.id, editor.state.value.settings?.formatRepairModelId)
                assertFalse(c.settingsRepository.getAppSettings().automaticFormatCheckEnabled)
            } finally { scene.close() }
        }
    }

    @Test fun `terminal states render distinct localized outcomes without retaining running preview`() = runBlocking(awt) {
        val message = ChatMessage("m", "s", MessageRole.ASSISTANT, "body", createdAt = 1, updatedAt = 1)
        for (language in DesktopUiLanguage.entries) {
            val strings = DesktopUiStrings(language)
            for (status in listOf(DesktopRepairStatus.APPLIED, DesktopRepairStatus.UNCHANGED, DesktopRepairStatus.STOPPED,
                DesktopRepairStatus.ERROR, DesktopRepairStatus.CONFLICT, DesktopRepairStatus.STORAGE_FAILURE, DesktopRepairStatus.REJECTED)) {
                val state = DesktopRepairState(status, preview = "discarded terminal preview", code = "DERIVED_DATA_UNPROVEN")
                val scene = ImageComposeScene(700, 180) {
                    CompositionLocalProvider(LocalDesktopUiStrings provides strings) {
                        DesktopMessageFormatRepairStatus(message, state, false, {}, {})
                    }
                }
                try {
                    scene.until { scene.nodes().any { it.label(strings(desktopRepairStatusText(state))) } }
                    assertFalse(scene.nodes().any { it.label("discarded terminal preview") })
                    assertFalse(scene.nodes().any { it.label(strings(DesktopUiText.FORMAT_STOP)) })
                } finally { scene.close() }
            }
        }
    }
}
