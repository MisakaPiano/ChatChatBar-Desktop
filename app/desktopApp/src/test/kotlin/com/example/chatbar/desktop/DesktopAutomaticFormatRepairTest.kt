package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.StreamEvent
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopAutomaticFormatRepairTest {
    private suspend fun fixture(block: suspend (DesktopMessageFormatRepairTest.Fixture) -> Unit) {
        val f = DesktopMessageFormatRepairTest.Fixture()
        var ownership: DesktopDataRootOwnership? = null
        try {
            f.prepare()
            ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(f.container.resolvedRoot)).ownership
            f.chats.updateSession(f.chats.getSession("s")!!.copy(longTermMemoryEnabled = false))
            withTimeout(30_000) { block(f) }
        } finally {
            try { f.container.close() } finally { ownership?.close(); f.root.toFile().deleteRecursively() }
        }
    }

    private fun success(text: String) = MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":${kotlinx.serialization.json.JsonPrimitive(text)}},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
    private suspend fun prepareHttp(f: DesktopMessageFormatRepairTest.Fixture, server: MockWebServer,
        enabled: Boolean = true, rules: Boolean = true) {
        val model = f.container.modelRepository.getAllModels().single()
        f.container.modelRepository.saveModel(model.copy(baseUrl = server.url("/v1").toString().trimEnd('/')))
        f.settings { it.copy(automaticFormatCheckEnabled = enabled, allowCleartextModelApi = true,
            defaultFormatCardId = if (rules) "format" else null, assistantSegmentedBubblesEnabled = false, ragInjectionMode = "OFF") }
    }
    private suspend fun awaitChat(f: DesktopMessageFormatRepairTest.Fixture, id: String): DesktopTaskEntry = withTimeout(20_000) {
        f.runtime.tasks.first { rows -> rows.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }.single { it.taskId == id }
    }

    @Test fun `real main and repair requests retain original and restore exact current candidate`() = runBlocking<Unit> { fixture { f ->
        MockWebServer().use { server ->
            prepareHttp(f, server)
            server.enqueue(success("unformatted reply")); server.enqueue(success("formatted reply"))
            val id = f.runtime.launchChat("s", "new turn")
            assertEquals(DesktopTaskStatus.COMPLETED, awaitChat(f, id).status)
            val saved = f.chats.getMessages("s").last()
            assertEquals("formatted reply", saved.displayContent)
            assertEquals("unformatted reply", saved.formatRepairNotice?.originalContent)
            assertEquals(2, server.requestCount)
            val main = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            val repair = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(main.contains("new turn")); assertFalse(repair.contains("new turn"))
            assertTrue(repair.contains("unformatted reply"))
            val state = f.await(f.container.messageFormatRepairController.restore("s", saved.id), f.container.messageFormatRepairController)
            assertEquals(DesktopRepairStatus.RESTORED, state.status)
            assertEquals("unformatted reply", f.chats.getMessage(saved.id, "s")!!.displayContent)
            assertEquals(saved.currentAlternativeVersionId, f.chats.getMessage(saved.id, "s")!!.currentAlternativeVersionId)
            assertEquals(2, server.requestCount)
        }
    } }

    @Test fun `disabled and ruleless automatic settings add no auxiliary request`() = runBlocking<Unit> {
        for ((enabled, rules) in listOf(false to true, true to false)) fixture { f ->
            MockWebServer().use { server ->
                prepareHttp(f, server, enabled, rules); server.enqueue(success("retained"))
                assertEquals(DesktopTaskStatus.COMPLETED, awaitChat(f, f.runtime.launchChat("s", "new turn")).status)
                assertEquals("retained", f.chats.getMessages("s").last().displayContent)
                assertEquals(1, server.requestCount)
                assertNull(f.chats.getMessages("s").last().formatRepairNotice)
            }
        }
    }

    @Test fun `explicitly allowed local model without an API key can check format without an authorization header`() = runBlocking<Unit> { fixture { f ->
        MockWebServer().use { server ->
            prepareHttp(f, server)
            f.container.modelRepository.saveModel(f.container.modelRepository.getAllModels().single().copy(apiKey = ""))
            server.enqueue(success("local raw")); server.enqueue(success("local formatted"))
            val task = awaitChat(f, f.runtime.launchChat("s", "Next"))
            assertEquals(DesktopTaskStatus.COMPLETED, task.status, task.message)
            assertEquals("local formatted", f.chats.getMessages("s").last().displayContent)
            assertEquals(2, server.requestCount)
            repeat(2) { assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")) }
        }
    } }

    @Test fun `resident activation reads final formatted reply instead of raw main response`() = runBlocking<Unit> { fixture { f ->
        MockWebServer().use { server ->
            val session = f.chats.getSession("s")!!
            val alice = CharacterInfo.create("Alice"); val bob = CharacterInfo.create("Bob")
            val card = f.container.characterRepository.getById(session.characterCardId)!!.copy(characters = listOf(alice, bob))
            f.container.characterRepository.save(card)
            var disabled = com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.change(session, card, alice.id, false)
            disabled = com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.change(disabled, card, bob.id, false)
            f.chats.updateSession(disabled)
            prepareHttp(f, server)
            server.enqueue(success("<n=\"Alice\"/>[Raw appearance]()"))
            server.enqueue(success("<n=\"Bob\"/>[Final appearance]()"))
            val task = awaitChat(f, f.runtime.launchChat("s", "Next"))
            assertEquals(DesktopTaskStatus.COMPLETED, task.status, task.message)
            val retained = f.chats.getSession("s")!!
            assertFalse(com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.isEnabled(retained, card, alice.id))
            assertTrue(com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.isEnabled(retained, card, bob.id))
            assertTrue(f.container.characterResidency.notices.value["s"]?.text?.contains("Bob") == true)
            assertEquals(2, server.requestCount)
        }
    } }

    @Test fun `format check recognizes an already returned disabled speaker without injecting profile or bypassing card gate`() = runBlocking<Unit> {
        for (cardDisabled in listOf(false, true)) fixture { f -> MockWebServer().use { server ->
            val session = f.chats.getSession("s")!!
            val person = CharacterInfo.create("ReturnedPerson").copy(profile = "NEVER_INJECT_DISABLED_PROFILE", desktopEnabled = !cardDisabled)
            val card = f.container.characterRepository.getById(session.characterCardId)!!.copy(characters = listOf(person))
            f.container.characterRepository.save(card)
            f.chats.updateSession(com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.change(session, card, person.id, false))
            prepareHttp(f, server)
            f.settings { it.copy(assistantSegmentedBubblesEnabled = true) }
            server.enqueue(success("<n=\"ReturnedPerson\"/>[I returned]()"))
            server.enqueue(success("[[CHATBAR_FORMAT_OK]]"))
            val task = awaitChat(f, f.runtime.launchChat("s", "Next"))
            assertEquals(DesktopTaskStatus.COMPLETED, task.status, task.message)
            val main = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            val repair = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            assertFalse(main.contains(person.profile)); assertFalse(repair.contains(person.profile))
            val envelope = kotlinx.serialization.json.Json.parseToJsonElement(repair) as kotlinx.serialization.json.JsonObject
            val messages = envelope["messages"] as kotlinx.serialization.json.JsonArray
            val target = "<n=\"ReturnedPerson\"/>[I returned]()"
            assertEquals(target, f.chats.getMessages("s").last().displayContent)
            val input = assertNotNull(messages.map { ((it as kotlinx.serialization.json.JsonObject)["content"] as kotlinx.serialization.json.JsonPrimitive).content }
                .singleOrNull { it.contains(target) })
            assertTrue(input.substringBefore(target).contains(person.name), "Name must occur before the target text, in the rules")
            val retained = f.chats.getSession("s")!!
            assertEquals(!cardDisabled, com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.isEnabled(retained, card, person.id))
            assertEquals(card, f.container.characterRepository.getById(card.id))
            assertEquals(2, server.requestCount)
        } }
    }

    @Test fun `unchanged marker and auxiliary error preserve the completed reply`() = runBlocking<Unit> {
        for (unchanged in listOf(true, false)) fixture { f ->
            MockWebServer().use { server ->
                prepareHttp(f, server); server.enqueue(success("retained"))
                server.enqueue(if (unchanged) success("[[CHATBAR_FORMAT_OK]]") else MockResponse().setResponseCode(401).setBody("synthetic auth failure"))
                assertEquals(DesktopTaskStatus.COMPLETED, awaitChat(f, f.runtime.launchChat("s", "new turn")).status)
                val saved = f.chats.getMessages("s").last()
                assertEquals("retained", saved.displayContent)
                assertEquals(if (unchanged) null else MessageFormatRepairNoticeKind.ERROR, saved.formatRepairNotice?.kind)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun `regeneration repairs only new candidate and keeps other candidates and source turn`() = runBlocking<Unit> { fixture { f ->
        MockWebServer().use { server ->
            val user = ChatMessage("user", "s", MessageRole.USER, "original user", sourceTurnId = "turn", sourceTurnOrder = 1, createdAt = 1, updatedAt = 1)
            f.chats.replaceMessagesForSession("s", listOf(user, f.original.copy(generatedFromMessageId = user.id)))
            f.original = f.saved()
            prepareHttp(f, server); server.enqueue(success("new raw")); server.enqueue(success("new formatted"))
            val task = awaitChat(f, f.runtime.launchRegeneration("s", "m"))
            assertEquals(DesktopTaskStatus.COMPLETED, task.status, task.message)
            val saved = f.reopened()
            assertEquals("new formatted", saved.displayContent)
            assertEquals(f.original.alternatives, saved.alternatives.take(f.original.alternatives.size))
            assertEquals(f.original.alternativeVersionIds, saved.alternativeVersionIds.take(f.original.alternativeVersionIds.size))
            assertEquals(f.original.sourceTurnId, saved.sourceTurnId)
            assertEquals("new raw", saved.formatRepairNotice?.originalContent)
            assertEquals(2, server.requestCount)
        }
    } }

    @Test fun `production source owner accepts existing memory rag recording and save snapshots without rewriting them`() = runBlocking<Unit> { fixture { f ->
        val slot = SaveSlot(id = "slot", sessionId = "s", name = "snapshot", createdAt = 1)
        f.storage.saveEntity("save_slots", slot.id, slot, SaveSlot.serializer())
        f.storage.saveEntity("memory_states", "s", MemorySessionState("s"), MemorySessionState.serializer())
        val chunk = VectorChunk("chunk", ChunkSourceType.CHAT_MEMORY, "s", content = "old source", embedding = listOf(.1f), createdAt = 1)
        f.storage.saveEntity("vector_chunks", chunk.id, chunk, VectorChunk.serializer())
        val recording = f.root.resolve("recording.mp3").also { Files.write(it, byteArrayOf(2, 4, 6)) }
        val voice = GeneratedVoiceMessage.create("s", "m", "b", null, 0, "NARRATION", "Bot", "original😀body",
            taggedText = "original😀body", characterId = f.chats.getSession("s")!!.characterCardId, characterName = "Bot",
            voice = FishAudioVoiceBinding("ref", "Synthetic"), fishModelId = "s1", audioPath = recording.toString(), durationMs = 1, byteLength = 3)
        f.storage.saveEntity("generated_voice_messages", voice.id, voice, GeneratedVoiceMessage.serializer())
        val paths = listOf(recording, f.dataRoot.resolve("entities/save_slots/slot.json"), f.dataRoot.resolve("entities/vector_chunks/chunk.json"),
            f.dataRoot.resolve("entities/generated_voice_messages/${voice.id}.json"))
        val bytes = paths.map(Files::readAllBytes)
        val c = f.container.createMessageFormatRepairController { flowOf(StreamEvent.Delta("fixed"), StreamEvent.Done) }
        assertEquals(DesktopRepairStatus.APPLIED, f.await(c.repair("s", "m"), c).status)
        assertEquals("fixed", f.reopened().displayContent)
        paths.forEachIndexed { i, path -> assertContentEquals(bytes[i], Files.readAllBytes(path)) }
        assertEquals(DesktopRepairStatus.RESTORED, f.await(c.restore("s", "m"), c).status)
        assertEquals(f.original.displayContent, f.reopened().displayContent)
    } }

    @Test fun `changed target during model request rejects final proof and keeps external edit`() = runBlocking<Unit> { fixture { f ->
        val c = f.container.createMessageFormatRepairController { flow {
            f.chats.updateMessage(f.saved().copy(content = "external edit", alternatives = listOf("first", "external edit", "last")))
            emit(StreamEvent.Delta("must not commit")); emit(StreamEvent.Done)
        } }
        assertEquals(DesktopRepairStatus.REJECTED, f.await(c.repair("s", "m"), c).status)
        assertEquals("external edit", f.reopened().displayContent)
    } }

    @Test fun `playing audio refuses before model request without stopping the owner`() = runBlocking<Unit> { fixture { f ->
        val focus = DesktopAudioFocus(object : DesktopAudioOutput {
            var playing = false
            override suspend fun play(path: java.nio.file.Path) { playing = true }
            override suspend fun stopAndDrain() { playing = false }
            override suspend fun isPlaying() = playing
            override suspend fun closeAndDrain() { playing = false }
        })
        val port = focus.port()
        port.play(f.root.resolve("synthetic.mp3"))
        try {
            val audio = DesktopAudioMaintenance({ focus }, { error("MUST_NOT_INTERRUPT") })
            val safety = DesktopFormatRepairSourceSafety(f.dataRoot, f.storage, f.container.dataOperationCoordinator,
                f.runtime, audio, { f.container.chatVoiceRuntime }, { })
            val c = DesktopMessageFormatRepairController(f.chats, f.container.settingsRepository, f.container.characterRepository,
                f.container.formatCardRepository, f.container.effectiveModelResolver, f.runtime, safety,
                { f.requests.incrementAndGet(); error("MUST_NOT_REQUEST") })
            val state = f.await(c.repair("s", "m"), c)
            assertEquals(DesktopRepairStatus.REJECTED, state.status); assertEquals("AUDIO_BUSY", state.code)
            assertTrue(port.hasFocus()); assertTrue(port.isPlaying())
            assertEquals(0, f.requests.get()); assertEquals(f.original, f.reopened())
        } finally { port.closeAndDrain(); focus.closeAndDrain() }
    } }

    @Test fun `pending data maintenance rejects final mutation immediately without mutex gate deadlock`() = runBlocking<Unit> { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val finishModel = CompletableDeferred<Unit>()
        val c = f.container.createMessageFormatRepairController { flow {
            entered.complete(Unit); finishModel.await(); emit(StreamEvent.Delta("must not commit")); emit(StreamEvent.Done)
        } }
        val repair = c.repair("s", "m"); entered.await()
        val holding = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coroutineScope {
            val reader = async { f.container.dataOperationCoordinator.withNormalOperation { holding.complete(Unit); release.await() } }
            holding.await()
            val maintenance = async { f.container.dataOperationCoordinator.withExclusiveMaintenance { } }
            try {
                withTimeout(5_000) { while (f.container.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) yield() }
                finishModel.complete(Unit)
                val state = f.await(repair, c)
                assertEquals(DesktopRepairStatus.REJECTED, state.status); assertEquals("DATA_MAINTENANCE_BUSY", state.code)
            } finally { release.complete(Unit) }
            reader.await(); maintenance.await()
        }
        assertEquals(f.original, f.reopened())
    } }

    @Test fun `unknown target index fields reject without model request or file repair`() = runBlocking<Unit> { fixture { f ->
        val index = f.dataRoot.resolve("entities/chat_message_indexes/s.json")
        val text = Files.readString(index)
        Files.writeString(index, text.replaceFirst("{", "{\"unknownFutureField\":true,"))
        val bytes = Files.readAllBytes(index)
        val c = f.container.createMessageFormatRepairController { f.requests.incrementAndGet(); error("MUST_NOT_REQUEST") }
        assertEquals(DesktopRepairStatus.REJECTED, f.await(c.repair("s", "m"), c).status)
        assertEquals(0, f.requests.get()); assertContentEquals(bytes, Files.readAllBytes(index))
        assertEquals(f.original, f.reopened())
    } }
}
