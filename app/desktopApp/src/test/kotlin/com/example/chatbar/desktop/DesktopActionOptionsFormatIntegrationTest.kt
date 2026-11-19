@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.FormatRepairPromptAuthority
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopActionOptionsFormatIntegrationTest {
    private val rule = ActionOptionsFormat("<cyoa>", "</cyoa>", "Next", ActionOptionLabels.LETTERS)
    private val menu = "<cyoa>\nNext\nA) Check the map\nB) Ask the guide\n</cyoa>"

    @Test fun `actual repair wire preserves inline closing literals and indented continuations before cold commit`() = runBlocking {
        val numericRule = ActionOptionsFormat("[ACTIONS]", "[END-ACTIONS]", "", ActionOptionLabels.NUMBERS)
        for (tail in listOf("2. 核对日志标记 [END-ACTIONS]", "2. 核对日志标记\n    [END-ACTIONS]"))
            for (changed in listOf(false, true)) {
            val f = DesktopMessageFormatRepairTest.Fixture()
            try { MockWebServer().use { server ->
                f.prepare()
                f.container.modelRepository.saveModel(ModelConfig("model", "Synthetic local repair", server.url("/v1").toString().trimEnd('/'),
                    "synthetic-key", "synthetic-model", createdAt = 1))
                f.settings { it.copy(allowCleartextModelApi = true) }
                val missing = "本轮叙述。\n[ACTIONS]\n1. 检查地图\n$tail"
                val completed = "$missing\n[END-ACTIONS]"
                f.replace(f.saved().copy(content = missing, alternatives = listOf("first", missing, "last"),
                    actionOptionsFormat = numericRule, alternativeActionOptionsFormats = listOf(null, numericRule, null)))
                val before = f.saved()
                val reply = if (changed) "仅保留叙述。" else FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER
                server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":${JsonPrimitive(reply)}}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                f.response = { request -> OpenAiStreamingTransport(allowCleartextHttp = { request.allowCleartext })
                    .streamFormatRepair(request.formatCard, request.segmentedFormat, request.original, request.model) }
                val result = f.run()
                val wire = Json.parseToJsonElement(assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)).body.readUtf8()).jsonObject
                    .getValue("messages").jsonArray.joinToString("\n") { it.jsonObject.getValue("content").jsonPrimitive.content }
                assertTrue(completed in wire)
                assertTrue(FormatRepairPromptAuthority.actionOptionsRepairRequirement(numericRule) in wire)
                val cold = f.reopened()
                if (changed) {
                    assertEquals(DesktopRepairStatus.ERROR, result.status); assertEquals("ACTION_OPTIONS_CHANGED", result.code)
                    assertEquals(before.displayContent, cold.displayContent); assertEquals(before.alternatives, cold.alternatives)
                } else {
                    assertEquals(DesktopRepairStatus.APPLIED, result.status); assertEquals(completed, cold.displayContent)
                    assertEquals(2, RoleplayActionOptionsPolicy.blocks(cold.displayContent, numericRule).single().options.size)
                }
                assertEquals(before.alternativeVersionIds, cold.alternativeVersionIds)
                assertEquals(before.images, cold.images)
            } } finally { f.close() }
        }
    }

    @Test fun `quoted list code examples do not create action repairs or append a closing line`() = runBlocking {
        val numericRule = ActionOptionsFormat("[ACTIONS]", "[END-ACTIONS]", "", ActionOptionLabels.NUMBERS)
        for (example in listOf(
            "> ~~~text\n> [ACTIONS]\n> 1. 仅示例甲\n> 2. 仅示例乙\n> ~~~",
            "- 示例\n  ~~~text\n  [ACTIONS]\n  1. 仅示例甲\n  2. 仅示例乙\n  ~~~"
        )) {
            val f = DesktopMessageFormatRepairTest.Fixture()
            try {
                f.prepare()
                f.replace(f.saved().copy(content = example, alternatives = listOf("first", example, "last"),
                    actionOptionsFormat = numericRule, alternativeActionOptionsFormats = listOf(null, numericRule, null)))
                f.response = { request ->
                    assertEquals(example, request.original)
                    assertFalse(request.formatCard.orEmpty().contains("仅整理原回复已经列出的行动选项"))
                    flowOf(StreamEvent.Delta(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER), StreamEvent.Done)
                }
                assertEquals(DesktopRepairStatus.UNCHANGED, f.run().status)
                assertEquals(example, f.reopened().displayContent)
            } finally { f.close() }
        }
    }

    @Test fun `long closing fence still has one real disclosure and inserts its choice`() = runBlocking { fixture { app ->
        for (segmented in listOf(false, true)) {
            val raw = "```行动选项\n1. 查地图\n2. 找向导\n````"
            val message = ChatMessage.create("synthetic", MessageRole.ASSISTANT, raw)
            val state = DesktopPrimaryChatState(messages = listOf(message), assistantSegmentedBubblesEnabled = segmented)
            val clipboard = DesktopSafeClipboard(object : Clipboard {
                override val nativeClipboard: Any get() = error("Synthetic clipboard only")
                override suspend fun getClipEntry(): ClipEntry? = null
                override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
            })
            val picked = mutableListOf<String>()
            val scene = ImageComposeScene(800, 900) {
                PrimaryMessageBubble(message, state, app.primaryChatController, clipboard,
                    onActionChoice = { picked += it.text })
            }
            suspend fun frames() { repeat(8) { scene.render(System.nanoTime()).close(); delay(20) } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            }
            fun labels(value: String) = nodes().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
            }
            fun click(label: String) {
                var node = labels(label).first()
                while (!node.config.contains(SemanticsActions.OnClick)) node = requireNotNull(node.parent)
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            }
            try {
                frames()
                assertEquals(1, labels("行动选项").size, "A recognized block must have one rendered disclosure")
                click("行动选项"); frames(); click("1. 查地图"); frames()
                assertEquals(listOf("查地图"), picked)
                assertEquals(raw, message.content)
                assertEquals(desktopPresentMessage(message, null, null, segmented).copyText,
                    desktopPresentMessage(message, null, null, segmented, inlineActionChoices = true).copyText)
            } finally { scene.close() }
        }
    } }

    @Test fun `actual declaration switch label choice and preview stay in the current editor`() = runBlocking { fixture { app ->
        val editor = app.formatCardEditorController
        editor.openNew()
        val scene = ImageComposeScene(width = 800, height = 900) {
            val state by editor.state.collectAsState()
            Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp)) {
                DesktopFormatActionOptions(requireNotNull(state.card), editor)
            }
        }
        suspend fun frames() { repeat(8) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun click(label: String) {
            var node = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            while (!node.config.contains(SemanticsActions.OnClick)) node = requireNotNull(node.parent)
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            frames(); assertNull(editor.state.value.card?.actionOptionsFormat)
            click("自定义行动选项格式"); frames()
            assertNotNull(editor.state.value.card?.actionOptionsFormat)
            click("编号：数字"); frames()
            assertEquals(ActionOptionLabels.LETTERS, editor.state.value.card?.actionOptionsFormat?.labels)
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                "A) 查看地图" in text.text && "B) 询问向导" in text.text
            } == true })
            System.getenv("CCB_ACTION_RULE_REVIEW_DIR")?.let { folder ->
                val target = Files.createDirectories(Path.of(folder)).resolve("format-action-options-letters.png")
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } }
            }
            click("自定义行动选项格式"); frames()
            assertNull(editor.state.value.card?.actionOptionsFormat)
        } finally { scene.close() }
    } }

    @Test fun `format editor formal save and actual export import retain declared rule`() = runBlocking { fixture { app ->
        val editor = app.formatCardEditorController
        editor.openNew()
        editor.edit { it.copy(name = "Synthetic format", content = "Synthetic existing format instruction", actionOptionsFormat = rule) }
        assertTrue(editor.save())
        val id = editor.state.value.card!!.id
        val cold = FormatCardRepository(JsonFileStorage(app.appDataRoot))
        assertEquals(rule, cold.getById(id)?.actionOptionsFormat)
        val transfer = FormatCardTransferService(cold, app.jsonFileStorage.json)
        val decoded = transfer.decode(transfer.exportJson(id))
        assertEquals(rule, decoded.actionOptionsFormat)
        assertEquals(rule, transfer.importNew(decoded).actionOptionsFormat)
        val character = CharacterCard.create("Synthetic linked format").copy(defaultFormatCardId = id)
        app.characterRepository.save(character)
        val packageData = app.characterTransfers.decode(app.characterTransfers.exportJson(character.id))
        assertEquals(rule, packageData.defaultFormatCard?.actionOptionsFormat)
        val linked = app.characterTransfers.importNew(packageData)
        assertEquals(rule, app.formatCardRepository.getById(requireNotNull(linked.defaultFormatCardId))?.actionOptionsFormat)
        editor.closeClean(); editor.openExisting(id)
        assertFalse(editor.state.value.dirty)
        assertEquals(rule, editor.state.value.card?.actionOptionsFormat)
    } }

    @Test fun `request time rule stays frozen when card changes and regeneration captures its own rule`() = runBlocking { fixture { app ->
        MockWebServer().use { server ->
            val format = FormatCard.create("Synthetic format", "Synthetic formatting").copy(actionOptionsFormat = rule)
            val second = rule.copy(opening = "<next>", closing = "</next>")
            val laterMenu = menu.replace("<cyoa>", "<next>").replace("</cyoa>", "</next>")
            app.formatCardRepository.save(format)
            val card = CharacterCard.create("Synthetic", greeting = "Synthetic opening")
            app.characterRepository.save(card)
            val id = app.characterSessionService.createSessionForCharacter(card.id)
            val model = ModelConfig("synthetic", "Synthetic", server.url("/v1").toString().trimEnd('/'),
                "synthetic-key", "synthetic-model", createdAt = 1)
            app.modelRepository.saveModel(model)
            app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id, defaultFormatCardId = format.id,
                allowCleartextModelApi = true, ragInjectionMode = "OFF"))
            val calls = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val first = calls.getAndIncrement() == 0
                    if (first) runBlocking { app.formatCardRepository.save(format.copy(actionOptionsFormat = second)) }
                    val text = if (first) menu else laterMenu
                    return MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":${JsonPrimitive(text)}},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                }
            }
            val runtime = app.createRealChatRuntime()
            val first = runtime.sendText(id, "Synthetic input").assistant
            assertEquals(rule, first.actionOptionsFormat)
            val current = runtime.regenerate(id, first.id).assistant
            assertEquals(listOf(rule, second), current.alternativeActionOptionsFormats)
            assertEquals(second, current.displayActionOptionsFormat)
            val old = MessageAlternativeVersionPolicy.select(current, 0)
            assertEquals(rule, old.displayActionOptionsFormat)
            assertEquals(listOf("Check the map", "Ask the guide"), desktopActionChoices(old).map { it.text })
            assertEquals(2, calls.get())
        }
    } }

    @Test fun `custom choices have one button source while raw copy and original segment identities survive`() {
        val message = ChatMessage.create("synthetic", MessageRole.ASSISTANT, "Before.\n$menu\nAfter.").copy(actionOptionsFormat = rule)
        val plain = desktopPresentMessage(message, null, null, true)
        val live = desktopPresentMessage(message, null, null, true, inlineActionChoices = true)
        assertEquals(plain.copyText, live.copyText)
        assertTrue("Ask the guide" in live.copyText)
        assertFalse(live.segments.any { "Ask the guide" in it.markdownSource })
        assertEquals(plain.segments.map { it.source }, live.segments.map { it.source })
        assertEquals(plain.segments.map { it.index }, live.segments.map { it.index })
        assertEquals(listOf("A", "B"), desktopActionChoices(message).map { it.label })
        assertEquals("Before.\n$menu\nAfter.", message.content)
    }

    @Test fun `repair closes only a reliable existing block and retains local restore redo`() = runBlocking {
        val f = DesktopMessageFormatRepairTest.Fixture()
        try {
            f.prepare()
            val missing = menu.removeSuffix("\n</cyoa>")
            f.replace(f.saved().copy(content = missing, alternatives = listOf("first", missing, "last"),
                actionOptionsFormat = rule, alternativeActionOptionsFormats = listOf(null, rule, null)))
            f.response = { request ->
                assertEquals(menu, request.original)
                flowOf(StreamEvent.Delta(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER), StreamEvent.Done)
            }
            assertEquals(DesktopRepairStatus.APPLIED, f.run().status)
            assertEquals(menu, f.reopened().displayContent)
            assertEquals(listOf("first", menu, "last"), f.reopened().alternatives)
            assertEquals(DesktopRepairStatus.RESTORED, f.run(restore = true).status)
            assertEquals(missing, f.reopened().displayContent)
            val calls = f.requests.get()
            assertEquals(DesktopRepairStatus.APPLIED, f.await(f.controller.redo("s", "m")).status)
            assertEquals(calls, f.requests.get())
            assertEquals(rule, f.reopened().displayActionOptionsFormat)
        } finally { f.close() }
    }

    @Test fun `repair refuses changed action wording order and count without replacing source`() = runBlocking {
        for (changed in listOf(menu.replace("Check the map", "Buy a ticket"),
            menu.replace("A) Check the map\nB) Ask the guide", "A) Ask the guide\nB) Check the map"),
            menu.replace("\n</cyoa>", "\nC) Buy a ticket\n</cyoa>"))) {
            val f = DesktopMessageFormatRepairTest.Fixture()
            try {
                f.prepare()
                f.replace(f.saved().copy(content = menu, alternatives = listOf("first", menu, "last"),
                    actionOptionsFormat = rule, alternativeActionOptionsFormats = listOf(null, rule, null)))
                val before = f.saved()
                f.response = { flowOf(StreamEvent.Delta(changed), StreamEvent.Done) }
                val result = f.run()
                assertEquals(DesktopRepairStatus.ERROR, result.status)
                assertEquals("ACTION_OPTIONS_CHANGED", result.code)
                val retained = f.reopened()
                assertEquals(before.displayContent, retained.displayContent)
                assertEquals(before.alternatives, retained.alternatives)
                assertEquals(before.alternativeVersionIds, retained.alternativeVersionIds)
                assertEquals(before.images, retained.images)
            } finally { f.close() }
        }
    }

    private suspend fun fixture(block: suspend (DesktopAppContainer) -> Unit) {
        val root = Files.createTempDirectory("desktop-action-rule-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        try { block(app) } finally { app.close(); check(root.fileName.toString().startsWith("desktop-action-rule-")); check(root.toFile().deleteRecursively()) }
    }
}
