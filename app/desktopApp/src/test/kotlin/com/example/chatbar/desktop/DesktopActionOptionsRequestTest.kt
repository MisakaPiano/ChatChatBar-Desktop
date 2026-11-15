package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.memory.MemoryPromptView
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import com.example.chatbar.domain.card.PackagedImage
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopActionOptionsRequestTest {
    private fun options(tag: String) = "————————\n【行动选项】\n1. ${tag}_UNTAKEN_LEFT\n2. ${tag}_UNTAKEN_RIGHT\n————————"
    private val ordinary = "我推开木门，走到窗边观察街上的雨。"
    private val imageCaption = "依照图示去做会更合适，请直接推进后续剧情。"
    private fun syntheticPng(): ByteArray = java.io.ByteArrayOutputStream().use {
        check(javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it))
        it.toByteArray()
    }
    private class Fixture {
        val root = Files.createTempDirectory("action-wire-")
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        val chats get() = c.chatRepository
        val server = MockWebServer().also { it.start() }
        lateinit var session: ChatSession
        val sources = mutableListOf<ChatMessage>()
        var lastWire = JsonArray(emptyList())
        suspend fun prepare(excludeStatus: Boolean, multimodal: Boolean) {
            c.modelRepository.saveModel(ModelConfig("model", "synthetic", server.url("/v1").toString().trimEnd('/'),
                "synthetic-key", "synthetic-model", createdAt = 1, isMultimodal = multimodal))
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "model", allowCleartextModelApi = true,
                defaultContextWindowSize = 20, ragInjectionMode = "OFF", excludeAssistantStatusFromHistory = excludeStatus))
            val card = CharacterCard.create("synthetic")
            c.characterRepository.save(card)
            session = chats.createSession(ChatSession("s", card.id, "synthetic", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1))
        }
        suspend fun add(role: MessageRole, text: String, turn: String? = null, images: List<String> = emptyList()): ChatMessage {
            val n = sources.size.toLong() + 1
            return chats.addMessage(ChatMessage("source-$n", "s", role, text, images = images, createdAt = n, updatedAt = n,
                sourceTurnId = turn, sourceTurnOrder = if (turn != null) n else null)).also { sources += it }
        }
        suspend fun wire(text: String, regenerate: ChatMessage? = null, runtime: DesktopRealChatRuntime = c.createRealChatRuntime(),
            attachments: List<DesktopPendingImage> = emptyList()): Pair<List<String>, DesktopRealChatResult> {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"synthetic model reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val result = withTimeout(10_000) { if (regenerate == null) runtime.sendText("s", text, attachments = attachments) else runtime.regenerate("s", regenerate.id) }
            assertNull(result.failureMessage)
            val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/chat/completions", request.path)
            val raw = request.body.readUtf8()
            System.getenv("CCB_ACTION_PROJECTION_REVIEW_DIR")?.let { directory ->
                val output = Files.createDirectories(java.nio.file.Path.of(directory).resolve(root.fileName.toString()))
                Files.writeString(output.resolve("actual-synthetic-request.json"), raw)
                Files.writeString(output.resolve("synthetic-source-messages.json"), Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), sources.toList() + listOf(result.currentUser).filter { user -> sources.none { it.id == user.id } }))
            }
            val messages = Json.parseToJsonElement(raw).jsonObject.getValue("messages").jsonArray
            lastWire = messages
            return messages.map { message -> when (val content = message.jsonObject.getValue("content")) {
                is JsonArray -> content.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("\n")
                else -> content.jsonPrimitive.content
            } } to result
        }
        fun imageUrls(): List<String> = lastWire.flatMap { message ->
            (message.jsonObject["content"] as? JsonArray).orEmpty().mapNotNull {
                it.jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
            }
        }
        suspend fun unchanged(except: String? = null) {
            val cold = ChatRepository(JsonFileStorage(c.appDataRoot))
            sources.filter { it.id != except }.forEach { assertEquals(it, cold.getMessage(it.id, "s")) }
        }
        suspend fun close() { try { c.close(); server.shutdown() } finally { root.toFile().deleteRecursively() } }
    }
    private suspend fun fixture(exclude: Boolean = false, multimodal: Boolean = false, block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.prepare(exclude, multimodal); withTimeout(25_000) { block(f) } } finally { f.close() }
    }

    @Test fun `current long caption with image retains earlier choices and exact user multimodal payload`() = runBlocking {
        for (exclude in listOf(false, true)) fixture(exclude, multimodal = true) { f ->
            f.add(MessageRole.USER, ordinary)
            f.add(MessageRole.ASSISTANT, "最近的剧情\n" + options("IMAGE_CURRENT"))
            val png = syntheticPng()
            val (wire, result) = f.wire(imageCaption, attachments = listOf(DesktopPendingImage(bytes = png)))
            assertEquals(1, wire.count { it == imageCaption })
            assertEquals(listOf("data:image/jpeg;base64," + f.c.chatImages.jpegBase64(png)), f.imageUrls())
            assertContentEquals(png, f.c.chatImages.read(result.currentUser.images.single()))
            assertEquals(imageCaption, result.currentUser.displayContent)
            assertTrue(wire.any { it.contains("IMAGE_CURRENT_UNTAKEN") })
            f.sources += result.currentUser; f.unchanged()
        }
    }

    @Test fun `historical image protects earlier choices but not later independent action blocks on actual wire`() = runBlocking { fixture(multimodal = true) { f ->
        f.add(MessageRole.USER, ordinary)
        f.add(MessageRole.ASSISTANT, "图片之前的剧情\n" + options("IMAGE_EARLIER"))
        val png = syntheticPng()
        val reference = f.c.characterResourceStore.materializeImage(PackagedImage("synthetic.png", java.util.Base64.getEncoder().encodeToString(png)), 1, "action-fixture")
        f.add(MessageRole.USER, imageCaption, images = listOf(reference))
        f.add(MessageRole.ASSISTANT, "图片之后新的剧情\n" + options("IMAGE_LATER"))
        val wire = f.wire(ordinary).first
        assertEquals(1, wire.count { it == imageCaption })
        assertEquals(listOf("data:image/jpeg;base64," + f.c.chatImages.jpegBase64(png)), f.imageUrls())
        assertContentEquals(png, f.c.chatImages.read(reference))
        assertEquals(listOf(true, false), listOf(wire.any { it.contains("IMAGE_EARLIER_UNTAKEN") }, wire.any { it.contains("IMAGE_LATER_UNTAKEN") }))
        f.unchanged()
    } }

    @Test fun `unresolved pronoun and same action user text keeps its reference on actual wire`() = runBlocking {
        for (reference in listOf("I will carry it out tomorrow.", "I shall proceed with them tomorrow.",
            "Tomorrow we will carry it out quietly.", "Tomorrow we shall proceed with them quietly.",
            "Tomorrow we shall follow the same plan quietly.", "Tomorrow we shall follow the aforementioned instruction quietly.",
            "I choose B and then wait for her.", "Tomorrow choose B and then wait quietly.", "那就选乙，然后再看看她接下来的反应。",
            "I shall follow the same plan tomorrow.", "I shall follow the aforementioned instruction tomorrow.",
            "我们照旧处理接下来的安排并等待结果。", "我们照常处理接下来的安排并等待结果。", "如上处理接下来的安排并等待结果。",
            "按照她所说的继续处理接下来的事情。", "按他说的做，然后安静等待事情结束。", "按你说的办，然后安静等待事情结束。",
            "照她的安排继续处理接下来的事情。", "照他的安排继续处理接下来的事情。", "照你的安排继续处理接下来的事情。", "照我的安排继续处理接下来的事情。")) fixture { f ->
            f.add(MessageRole.USER, ordinary)
            f.add(MessageRole.ASSISTANT, "等待选择的剧情\n" + options("UNRESOLVED_WORD"))
            val wire = f.wire(reference).first
            assertEquals(1, wire.count { it == reference })
            assertTrue(wire.any { it.contains("UNRESOLVED_WORD_UNTAKEN") }, reference)
            f.unchanged()
        }
    }

    @Test fun `actual ordinary chat wire removes earlier and previous action blocks independently of status switch`() = runBlocking {
        for (exclude in listOf(false, true)) fixture(exclude) { f ->
            f.add(MessageRole.USER, "用户自己的清单\n1. 地图\n2. 饮水\n" + options("USER"))
            f.add(MessageRole.ASSISTANT, "已发生的旁白，衣着青色大衣。\n${options("EARLY")}\n```status\nEARLY_STATUS\n```")
            f.add(MessageRole.USER, ordinary)
            val previous = f.add(MessageRole.ASSISTANT, "原有剧情旁白，衣着蓝色长裙。\n${options("PREVIOUS")}\n```status\nPREVIOUS_STATUS\n```")
            val slot = SaveSlot(schemaVersion = 4, id = "slot", sessionId = "s", name = "synthetic", messages = f.sources.toList(), createdAt = 1)
            f.c.jsonFileStorage.saveEntity("save_slots", slot.id, slot, SaveSlot.serializer())
            val slotPath = f.c.appDataRoot.resolve("entities/save_slots/slot.json"); val before = Files.readAllBytes(slotPath)
            val format = FormatCard("format", "synthetic", "手动输出要求保持原文\n" + options("MANUAL"), createdAt = 1)
            f.c.formatCardRepository.save(format)
            f.c.settingsRepository.updateAppSettings { it.copy(defaultFormatCardId = format.id) }
            val (wire, _) = f.wire(ordinary)
            val text = wire.joinToString("\n")
            assertFalse(text.contains("EARLY_UNTAKEN")); assertFalse(text.contains("PREVIOUS_UNTAKEN"))
            assertTrue(text.contains("USER_UNTAKEN")); assertTrue(text.contains("MANUAL_UNTAKEN"))
            assertTrue(text.contains("青色大衣")); assertTrue(text.contains("蓝色长裙"))
            assertEquals(!exclude, text.contains("EARLY_STATUS")); assertTrue(text.contains("PREVIOUS_STATUS"))
            assertEquals(1, wire.count { it.contains("原有剧情旁白") })
            assertEquals(previous, f.chats.getMessage(previous.id, "s"))
            assertContentEquals(before, Files.readAllBytes(slotPath)); f.unchanged()
        }
    }

    @Test fun `historical references retain remaining choices without overriding legacy status exclusion`() = runBlocking {
        for (reference in listOf("1", "选择第二项，然后观察她的反应。", "就照刚才的那个行动做。"))
            for (continuation in listOf(false, true)) for (excludeStatus in listOf(false, true)) fixture(excludeStatus) { f ->
            f.add(MessageRole.USER, ordinary)
            f.add(MessageRole.ASSISTANT, "旧剧情\n" + options("OLD_REFERENCE"))
            f.add(MessageRole.USER, reference)
            f.add(MessageRole.ASSISTANT, "选择后已经发生的剧情。")
            f.add(MessageRole.USER, ordinary)
            f.add(MessageRole.ASSISTANT, "新剧情\n" + options("NEW_UNSELECTED"))
            val text = f.wire(if (continuation) "" else ordinary).first.joinToString("\n")
            assertEquals(!excludeStatus, text.contains("OLD_REFERENCE_UNTAKEN"), reference)
            assertTrue(text.contains(reference)); assertFalse(text.contains("NEW_UNSELECTED_UNTAKEN"), reference)
            f.unchanged()
        }
    }

    @Test fun `current numeric and unknown short replies are not expanded and retain their action reference`() = runBlocking {
        for (reference in listOf("2", "１", "②", "⑴，然后看看她接下来的反应。", "按第二条行动继续。", "好", "继续",
            "请继续生成接下来发生的故事。", "Please continue the story for a while.")) fixture { f ->
            f.add(MessageRole.USER, ordinary)
            f.add(MessageRole.ASSISTANT, "最新剧情\n" + options("NEEDED"))
            val wire = f.wire(reference).first
            assertEquals(1, wire.count { it == reference })
            assertTrue(wire.any { it.contains("NEEDED_UNTAKEN") })
            f.unchanged()
        }
    }

    @Test fun `continuation and regeneration use real planner projection while preserving source and alternatives`() = runBlocking {
        for (regenerate in listOf(false, true)) fixture { f ->
            f.add(MessageRole.USER, ordinary)
            f.add(MessageRole.ASSISTANT, "先前已经发生的剧情\n" + options("EARLIER"))
            if (regenerate) f.add(MessageRole.USER, ordinary)
            val target = f.add(MessageRole.ASSISTANT, "待续剧情\n" + options("TARGET"))
            val userCount = f.chats.getMessagesReadOnly("s").count { it.role == MessageRole.USER }
            val (wire, result) = f.wire("", if (regenerate) target else null)
            assertFalse(wire.any { it.contains("EARLIER_UNTAKEN") || it.contains("TARGET_UNTAKEN") })
            assertEquals(userCount, f.chats.getMessagesReadOnly("s").count { it.role == MessageRole.USER })
            if (regenerate) {
                assertEquals(target.id, result.assistant.id)
                assertTrue(result.assistant.alternatives.contains(target.displayContent))
            } else assertTrue(wire.contains(MainChatPromptAuthority.continueGenerationUserPrompt()))
            f.unchanged(if (regenerate) target.id else null)
        }
    }

    @Test fun `code malformed fences and ordinary lists retain exact assistant body on final wire`() = runBlocking { fixture { f ->
        f.add(MessageRole.USER, ordinary)
        val content = "真实旁白与衣着。\n1. 地图\n2. 饮水\n```kotlin\n${options("CODE")}\n```\n" +
            "————————\n【行动选项】\n1. MALFORMED_LEFT\n3. MALFORMED_RIGHT\n————————"
        f.add(MessageRole.ASSISTANT, content)
        val wire = f.wire(ordinary).first
        assertEquals(MainChatPromptAuthority.narrativeModeHistoryReply(content, com.example.chatbar.data.local.entity.ChatNarrativeMode.FOCUS),
            wire.single { it.contains("MALFORMED_LEFT") }); f.unchanged()
    } }

    @Test fun `pinned old source with an omitted middle remains unfiltered on actual wire and read only planner agrees`() = runBlocking { fixture { f ->
        f.add(MessageRole.USER, ordinary, "old-turn")
        val old = f.add(MessageRole.ASSISTANT, "旧原文\n" + options("PINNED_UNKNOWN"), "old-turn")
        repeat(12) { n ->
            f.add(MessageRole.USER, if (n == 0) "1" else ordinary, "middle-$n")
            f.add(MessageRole.ASSISTANT, "中间剧情已经发生。", "middle-$n")
        }
        f.add(MessageRole.USER, ordinary, "recent")
        f.add(MessageRole.ASSISTANT, "最新剧情\n" + options("LATEST"), "recent")
        f.c.settingsRepository.updateAppSettings { it.copy(defaultContextWindowSize = 0) }
        val planner = DesktopChatRequestPlanner(f.c.characterRepository, f.chats, f.c.formatCardRepository,
            f.c.contextWindowManager, f.c.worldBookRequestPlanner, f.c.promptAssembler, f.c.mainChatRequestAssembler,
            memoryView = { _, _ -> MemoryPromptView("", "", "", null, 0, 2000, 0, pendingSourceTurnIds = setOf("old-turn")) })
        val runtime = DesktopRealChatRuntime(f.c.characterRepository, f.chats, f.c.settingsRepository,
            f.c.characterSessionService, f.c.effectiveModelResolver, planner)
        val (wire, result) = f.wire(ordinary, runtime = runtime)
        assertTrue(wire.any { it.contains("PINNED_UNKNOWN_UNTAKEN") })
        assertFalse(wire.any { it.contains("LATEST_UNTAKEN") })
        val inspection = planner.planPersistedUser("s", result.currentUser, DesktopFakeChatInputs(effectiveContextWindowSize = 0), true)
        assertTrue(inspection.assembly.messages.any { it.content.toString().contains("PINNED_UNKNOWN_UNTAKEN") })
        assertFalse(inspection.assembly.messages.any { it.content.toString().contains("LATEST_UNTAKEN") })
        assertEquals(old, f.chats.getMessage(old.id, "s")); f.unchanged()
    } }

    @Test fun `known UI regeneration without a user removes unselected prior actions and sends no synthetic user`() = runBlocking { fixture { f ->
        f.add(MessageRole.ASSISTANT, "最早的开场剧情\n" + options("OPENING"))
        val target = f.add(MessageRole.ASSISTANT, "随后出现的开场剧情\n" + options("REGENERATED"))
        val (wire, result) = f.wire("", regenerate = target)
        assertFalse(wire.any { it.contains("OPENING_UNTAKEN") })
        assertFalse(wire.any { it.contains("REGENERATED_UNTAKEN") })
        assertFalse(result.currentUserPersisted)
        assertFalse(wire.contains(MainChatPromptAuthority.continueGenerationUserPrompt()))
        assertTrue(result.assistant.alternatives.contains(target.displayContent))
        f.unchanged(target.id)
    } }

    @Test fun `regeneration retains numeric or ambiguous current user and its prior action source without reinserting the target`() = runBlocking {
        for (reference in listOf("2", "好", "请继续生成接下来发生的故事。")) fixture { f ->
            f.add(MessageRole.USER, ordinary)
            f.add(MessageRole.ASSISTANT, "选择前的剧情\n" + options("REGEN_REFERENCE"))
            val user = f.add(MessageRole.USER, reference)
            val target = f.add(MessageRole.ASSISTANT, "旧的待重生回复\n" + options("REGEN_TARGET"))
            val users = f.chats.getMessagesReadOnly("s").count { it.role == MessageRole.USER }
            val (wire, result) = f.wire("", regenerate = target)
            assertEquals(1, wire.count { it == reference })
            assertTrue(wire.any { it.contains("REGEN_REFERENCE_UNTAKEN") })
            assertFalse(wire.any { it.contains("REGEN_TARGET_UNTAKEN") })
            assertEquals(user.id, result.currentUser.id)
            assertTrue(result.assistant.alternatives.contains(target.displayContent))
            assertEquals(users, f.chats.getMessagesReadOnly("s").count { it.role == MessageRole.USER })
            f.unchanged(target.id)
        }
    }

    @Test fun `known no current user regeneration filters its complete opening turn even with older users outside the selected context`() = runBlocking { fixture { f ->
        f.add(MessageRole.USER, ordinary, "old")
        f.add(MessageRole.ASSISTANT, "更早的完整对话。", "old")
        f.add(MessageRole.ASSISTANT, "独立的后续开场\n" + options("KNOWN_OPENING"), "opening-a")
        val target = f.add(MessageRole.ASSISTANT, "待重生的独立开场\n" + options("KNOWN_TARGET"), "opening-b")
        f.c.settingsRepository.updateAppSettings { it.copy(defaultContextWindowSize = 0) }
        val userCount = f.chats.getMessagesReadOnly("s").count { it.role == MessageRole.USER }
        val (wire, result) = f.wire("", regenerate = target)
        assertFalse(result.currentUserPersisted)
        assertFalse(wire.any { it.contains("KNOWN_OPENING_UNTAKEN") || it.contains("KNOWN_TARGET_UNTAKEN") })
        assertEquals(userCount, f.chats.getMessagesReadOnly("s").count { it.role == MessageRole.USER })
        assertTrue(result.assistant.alternatives.contains(target.displayContent))
        f.unchanged(target.id)
    } }

    @Test fun `an explicitly written complete action stays in USER while its unselected sibling is not sent`() = runBlocking { fixture { f ->
        f.add(MessageRole.USER, "我走进广场，留心观察周围的建筑。")
        val body = "先前已经发生的剧情。\n————————\n【行动选项】\n1. $ordinary\n2. NEVER_SELECTED_SIBLING\n————————"
        f.add(MessageRole.ASSISTANT, body)
        val (wire, _) = f.wire(ordinary)
        assertEquals(1, wire.count { it == ordinary })
        assertFalse(wire.any { it.contains("NEVER_SELECTED_SIBLING") })
        assertTrue(wire.any { it.contains("先前已经发生的剧情") })
        f.unchanged()
    } }
}
