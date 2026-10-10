@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopAssistantImagePresentationTest {
    @Test fun `one source process card shows latest task and dismiss reveals retained older terminal`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val runtime = f.c.taskRuntime
            val old = runtime.launchNovelAi("fixture", "session", f.source.id, retryable = true) {
                it.designSnapshot("【研究】\nold actual output"); error("fake failure")
            }
            f.terminal(old)
            val latest = runtime.launchNovelAi("fixture", "session", f.source.id) { it.designSnapshot("【研究】\nlatest actual output") }
            f.terminal(latest)
            val state = DesktopPrimaryChatState(selectedSession = f.c.chatRepository.getSession("session"), messages = listOf(f.source))
            val scene = ImageComposeScene(700, 600) {
                DesktopAssistantImageActions(f.source, state, f.c.primaryChatController, true)
            }
            try {
                scene.frames()
                fun cards() = scene.nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("image-process-card-") == true }
                assertEquals(1, cards().size)
                assertEquals("image-process-card-$latest", cards().single().config[SemanticsProperties.TestTag])
                assertTrue(old in runtime.imageProgress.states.value)
                scene.click("关闭图片任务"); scene.frames()
                assertEquals(1, cards().size)
                assertEquals("image-process-card-$old", cards().single().config[SemanticsProperties.TestTag])
                assertTrue(scene.nodes().any { it.matches("重试此图片任务") })
                assertEquals(f.source, f.c.chatRepository.getMessage(f.source.id, "session"))
            } finally { scene.close() }
        } finally { f.close() }
    }

    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render().close(); yield() }; delay(50); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    private fun ImageComposeScene.click(label: String) = nodes().first { it.matches(label) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        .config[SemanticsActions.OnClick].action!!.invoke()

    @Test fun `actual assistant bubble puts compact actions and successful fake task below body after user`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        f.initialize()
        val c = f.c
        val controller = DesktopPrimaryChatController(c.characterRepository, c.chatRepository, c.settingsRepository,
            f.resolver, c.formatCardRepository, c.worldBookRepository, c.characterSessionService, c.characterResourceStore,
            taskRuntime = c.taskRuntime, imageRegeneration = f.service)
        try {
            val messages = c.chatRepository.getMessages("session")
            val state = DesktopPrimaryChatState(selectedSession = c.chatRepository.getSession("session"), messages = messages,
                selectedCharacter = c.characterRepository.getById("card"), assistantSegmentedBubblesEnabled = false)
            var normal by mutableStateOf(true)
            val scene = ImageComposeScene(900, 700) {
                val clipboard = DesktopSafeClipboard(LocalClipboard.current)
                Column(Modifier.fillMaxSize()) { messages.forEach { message ->
                    PrimaryMessageBubble(message, state, controller, clipboard,
                        actions = if (normal) listOf(DesktopMessageAction.COPY) else emptyList())
                } }
            }
            try {
                scene.frames()
                val user = scene.nodes().first { it.matches("A reading scene") }.boundsInRoot
                val body = scene.nodes().first { it.matches(f.source.displayContent) }.boundsInRoot
                val icon = scene.nodes().single { it.matches("生成图片") }.boundsInRoot
                val requirements = scene.nodes().single { it.matches("生图要求") }.boundsInRoot
                assertTrue(user.bottom < body.top)
                assertTrue(icon.top >= body.bottom && requirements.top >= body.bottom)
                assertTrue(icon.left > 750 && requirements.left > icon.left)
                assertTrue(icon.width <= 50 && requirements.width <= 50)
                scene.click("生成图片"); scene.frames()
                withTimeout(10000) { while (c.taskRuntime.tasks.value.isEmpty()) delay(10) }
                val task = f.terminal(c.taskRuntime.tasks.value.single().taskId)
                assertEquals(DesktopTaskStatus.COMPLETED, task.status, task.message)
                scene.frames()
                assertTrue(scene.nodes().first { it.matches("已完成 · ${task.message}") }.boundsInRoot.top >= body.bottom)
                assertEquals(f.source.id, c.chatRepository.getMessages("session").single { it.images.isNotEmpty() }.generatedFromMessageId)
                assertEquals(1, f.requests.size)
                normal = false; scene.frames()
                assertTrue(scene.nodes().none { it.matches("生成图片") || it.matches("生图要求") })
            } finally { scene.close() }
        } finally { controller.closeDraftPersistence(); f.close() }
    }
    @Test fun `clicking unconfigured source displays safe preflight reason below body without task`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); f.c.desktopSecretStore.delete(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken)
            val c = f.c; val state = DesktopPrimaryChatState(selectedSession = c.chatRepository.getSession("session"), messages = listOf(f.source))
            val scene = ImageComposeScene(900, 600) {
                PrimaryMessageBubble(f.source, state, c.primaryChatController, DesktopSafeClipboard(LocalClipboard.current), listOf(DesktopMessageAction.COPY))
            }
            try {
                scene.frames(); scene.click("生成图片")
                repeat(12) { scene.frames() }
                val body = scene.nodes().first { it.matches(f.source.displayContent) }.boundsInRoot
                assertTrue(scene.nodes().first { it.matches("未配置 NovelAI Token") }.boundsInRoot.top >= body.bottom)
                assertTrue(c.taskRuntime.tasks.value.isEmpty()); assertTrue(f.design.requests.isEmpty()); assertTrue(f.requests.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
    @Test fun `requirements form edits are local and Cancel never saves or launches`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); val before = f.c.chatRepository.getSession("session")!!
            var hint by mutableStateOf(""); var preference by mutableStateOf(before.imagePromptPreference)
            var cancelled = false; var generated = false
            val scene = ImageComposeScene(700, 650) {
                DesktopChatImageRequirementsForm(hint, preference, { hint = it }, { preference = it }, false, "",
                    { cancelled = true }, { generated = true })
            }
            try {
                scene.frames()
                val fields = scene.nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertEquals(2, fields.size)
                fields[0].config[SemanticsActions.SetText].action!!(AnnotatedString("local-only hint"))
                fields[1].config[SemanticsActions.SetText].action!!(AnnotatedString("local-only preference"))
                scene.frames(); scene.click("取消"); scene.frames()
                assertTrue(cancelled); assertFalse(generated)
                assertEquals("local-only hint", hint); assertEquals("local-only preference", preference)
                assertEquals(before, f.c.chatRepository.getSession("session"))
                assertTrue(f.c.taskRuntime.tasks.value.isEmpty()); assertTrue(f.design.requests.isEmpty()); assertTrue(f.requests.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
}
