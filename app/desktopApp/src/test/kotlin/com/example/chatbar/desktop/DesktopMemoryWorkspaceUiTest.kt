@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.memory.MemorySourceFingerprint
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopMemoryWorkspaceUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `five memory pages retain explicit head and tier edits history source navigation and a read-only injection preview`(): Unit = runBlocking(edt) {
        val parent = Files.createTempDirectory("memory-workspace-ui-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try {
            val session = app.chatRepository.createSession(ChatSession.create("card", "Synthetic memory").copy(longTermMemoryEnabled = true))
            repeat(3) { index -> listOf(MessageRole.USER, MessageRole.ASSISTANT).forEachIndexed { offset, role ->
                val time = index * 2L + offset + 1
                app.chatRepository.addMessage(ChatMessage("message-$time", session.id, role, "Synthetic source $index $role",
                    createdAt = time, updatedAt = time, sourceTurnId = "turn-$index", sourceTurnOrder = index.toLong()))
            } }
            val controller = app.memoryController
            controller.service.ensureMigrated(session.id)
            val repository = MemoryRepository(app.jsonFileStorage, strictReads = true, dataGate = app.dataOperationCoordinator)
            val messages = app.chatRepository.getMessages(session.id)
            val hashes = (0..1).associate { "turn-$it" to MemorySourceFingerprint.semantic("turn-$it", messages, session) }
            val node = MemoryNode("episode", session.id, MemoryTier.EPISODE, sourceTurnIds = listOf("turn-0"),
                content = "Synthetic original episode", sourceFingerprints = hashes.filterKeys { it == "turn-0" })
            repository.saveNodes(listOf(node))
            val initial = assertNotNull(repository.getState(session.id))
            repository.saveState(initial.copy(episodePage = initial.episodePage.copy(activeNodeIds = listOf(node.id)),
                head = MemoryHead(throughSourceTurnId = "turn-1", location = "Synthetic location", sourceFingerprints = hashes)))
            controller.open(session.id, inSessionSettings = true)
            var jumped: Pair<String, String>? = null
            val scene = ImageComposeScene(1050, 880, coroutineContext = edt) {
                DesktopMemoryPanel(controller, embedded = true, onJumpToSource = { id, message -> jumped = id to message })
            }
            try {
                scene.until { controller.state.value.memory != null && !controller.state.value.operationBusy && !controller.state.value.busy }
                listOf("当前状态", "近期流程", "事件总结", "故事进程", "注入预览").forEach { assertTrue(scene.has(it)) }
                assertFalse(scene.has("当前分页还没有记忆内容。"), "HEAD must not be mixed with an empty Episode page")
                scene.shot("memory-current-state-wide.png")
                scene.click("编辑当前状态")
                scene.setText("位置", "Edited synthetic location")
                assertTrue(controller.state.value.dirty)
                scene.click("近期流程")
                scene.until { controller.state.value.leavePrompt }
                assertEquals(DesktopMemoryPage.HEAD, controller.state.value.page)
                scene.click("继续编辑")
                assertEquals("Edited synthetic location", controller.state.value.headDraft?.location)
                scene.click("保存当前状态")
                scene.until { !controller.state.value.operationBusy && controller.state.value.headDraft == null }
                assertEquals("Edited synthetic location", repository.getState(session.id)!!.head.location)
                scene.click("近期流程"); scene.until { controller.state.value.page == DesktopMemoryPage.EPISODE && scene.has("跳到来源") }
                scene.shot("memory-episode-current-wide.png")
                scene.click("跳到来源"); scene.until { jumped != null }
                assertEquals(session.id to "message-1", jumped)
                scene.click("编辑")
                scene.until { controller.state.value.section == DesktopMemorySection.EDIT }
                scene.click("编辑", last = true)
                scene.until { scene.has("正文") }
                scene.setText("正文", "Edited synthetic episode")
                scene.click("保存")
                scene.until { !controller.state.value.operationBusy && controller.state.value.editingNode == null }
                assertEquals("Edited synthetic episode", controller.state.value.nodes.single().body)
                scene.click("历史")
                scene.until { controller.state.value.history.isNotEmpty() && scene.has("恢复此版本…") }
                scene.shot("memory-episode-history-wide.png")
                val before = snapshot(app.appDataRoot)
                scene.click("注入预览")
                scene.until { controller.state.value.page == DesktopMemoryPage.PREVIEW && scene.contains("Edited synthetic episode") }
                assertTrue(scene.contains("Edited synthetic location"))
                assertEquals(before, snapshot(app.appDataRoot), "Switching to preview must not save a cache or a migration")
                scene.shot("memory-injection-preview-wide.png")
                val committed = assertNotNull(repository.getState(session.id))
                val journal = MemoryCommitJournal("pending-preview", session.id, committed.revision,
                    nextState = committed.copy(revision = committed.revision + 1))
                app.jsonFileStorage.saveEntity("memory_commit_journals", journal.id, journal, MemoryCommitJournal.serializer())
                val refusedSnapshot = snapshot(app.appDataRoot)
                controller.page(DesktopMemoryPage.PREVIEW)
                scene.until { controller.state.value.inspectionError != null && !controller.state.value.promptCurrent }
                assertNull(controller.state.value.prompt)
                assertFalse(scene.contains("Edited synthetic episode"), "A rejected new inspection must not display the old successful preview")
                assertTrue(scene.contains("本次注入预览不可用"))
                assertTrue(scene.contains("—/2000 字"), "An unavailable inspection must not report the old count or pretend the Archive is empty")
                assertEquals(refusedSnapshot, snapshot(app.appDataRoot), "Inspection must neither recover nor delete the pending journal")
                scene.shot("memory-injection-preview-refused-wide.png")
            } finally { scene.close() }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
    @Test fun `real primary navigation asks before leaving a memory draft and never opens one session with another sessions memory`(): Unit = runBlocking(edt) {
        val parent = Files.createTempDirectory("memory-primary-navigation-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try {
            val card = CharacterCard.create("Synthetic card"); app.characterRepository.save(card)
            val a = app.chatRepository.createSession(ChatSession.create(card.id, "A").copy(longTermMemoryEnabled = true))
            val b = app.chatRepository.createSession(ChatSession.create(card.id, "B").copy(longTermMemoryEnabled = true))
            val primary = app.primaryChatController; val memory = app.memoryController
            primary.refresh(); primary.selectSession(a.id); memory.open(a.id, inSessionSettings = true)
            withTimeout(15_000) { while (memory.state.value.memory == null || memory.state.value.busy || memory.state.value.operationBusy) delay(20) }
            memory.beginHeadEdit(); memory.headDraft(assertNotNull(memory.state.value.headDraft).copy(location = "Unsaved A"))
            primary.selectSession(b.id)
            assertEquals(a.id, primary.state.value.selectedSession?.id)
            assertEquals(a.id, memory.state.value.sessionId); assertTrue(memory.state.value.leavePrompt)
            memory.resolveLeave(false); assertTrue(memory.state.value.dirty)
            val count = app.chatRepository.getAllSessions().size
            primary.createSession(card.id)
            assertTrue(memory.state.value.leavePrompt); assertEquals(count, app.chatRepository.getAllSessions().size)
            memory.resolveLeave(false)
            var leftChat = false
            primary.requestSessionSettingsLeave { leftChat = true }
            assertFalse(leftChat); assertTrue(memory.state.value.leavePrompt)
            memory.resolveLeave(false)
            primary.selectSession(b.id); memory.resolveLeave(true)
            withTimeout(15_000) { while (primary.state.value.selectedSession?.id != b.id) delay(20) }
            assertFalse(memory.state.value.open); assertNull(memory.state.value.sessionId)
            memory.open(b.id, inSessionSettings = true)
            withTimeout(15_000) { while (memory.state.value.memory == null || memory.state.value.operationBusy || memory.state.value.busy) delay(20) }
            assertEquals(b.id, memory.state.value.sessionId); assertFalse(memory.state.value.dirty)
            assertFalse(memory.state.value.memory!!.head.render().contains("Unsaved A"))
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
    private fun snapshot(root: Path): Map<String, String> = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(it))
    } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || children.any { it.matches(label) }
    private fun ImageComposeScene.has(label: String) = nodes().any { it.matches(label) }
    private fun ImageComposeScene.contains(text: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> text in value.text } == true }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(15_000) {
        while (!condition()) { render(System.nanoTime()).close(); delay(20) }
        repeat(3) { render(System.nanoTime()).close(); delay(20) }
    }
    private suspend fun ImageComposeScene.click(label: String, last: Boolean = false) {
        val matches = nodes().filter { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
        val node = if (last) matches.last() else matches.first()
        assertFalse(node.config.contains(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(3) { render(System.nanoTime()).close(); delay(20) }
    }
    private suspend fun ImageComposeScene.setText(label: String, value: String) {
        val node = nodes().first { it.matches(label) && it.config.contains(SemanticsActions.SetText) }
        assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        repeat(3) { render(System.nanoTime()).close(); delay(20) }
    }
    private fun ImageComposeScene.shot(name: String) {
        val directory = System.getenv("CCB_FORMAT_AI_REVIEW_DIR") ?: return
        val path = Path.of(directory).resolve(name); Files.createDirectories(path.parent)
        render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
    }
}
