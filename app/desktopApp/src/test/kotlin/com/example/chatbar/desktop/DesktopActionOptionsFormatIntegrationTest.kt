package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.FormatRepairPromptAuthority
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopActionOptionsFormatIntegrationTest {
    private val rule = ActionOptionsFormat("<cyoa>", "</cyoa>", "Next", ActionOptionLabels.LETTERS)
    private val menu = "<cyoa>\nNext\nA) Check the map\nB) Ask the guide\n</cyoa>"

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
