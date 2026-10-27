package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.model.*
import com.example.chatbar.domain.voice.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.*
import java.time.LocalDate
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

class DesktopChatVoiceRuntimeTest {
    @Test fun `UI click intent cannot become a new autoplay intent after a leave and return`() = runBlocking {
        Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val voice = f.repo.listForSession(f.session.id).single()
            f.runtime.foreground(f.session.id).join()
            val intent = f.runtime.playbackIntent()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val hold = async(Dispatchers.IO) { f.runtime.mutateMessage(f.session.id, f.message.id) { entered.complete(Unit); release.await() } }
            entered.await()
            val play = async { f.runtime.play(voice, f.message, intent) }
            f.runtime.foreground(null).join(); f.runtime.foreground(f.session.id).join()
            release.complete(Unit); hold.await(); play.await()?.join()
            assertEquals(0, f.output.plays)
            f.enqueue(); f.done(f.runtime.generate(f.message, "alice", auxiliaryAuthorized = true, additional = voice, originGeneration = intent))
            assertEquals(2, f.repo.listForSession(f.session.id).size)
            assertEquals(0, f.output.plays)
        }
    }
    @Test fun `replacement releases an actively playing old artifact before deletion`() = runBlocking {
        Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val old = f.repo.listForSession(f.session.id).single()
            f.runtime.playback.playSequence(listOf(old))
            withTimeout(3000) { while (!f.runtime.playback.state.value.playing) delay(10) }
            f.enqueue(); f.done(f.runtime.generate(f.message, "alice", replace = old, editedTaggedText = "hello"))
            assertFalse(f.output.playing)
            assertFalse(Files.exists(Path.of(old.audioPath)))
            assertNotEquals(old.audioPath, f.repo.get(old.id)!!.audioPath)
        }
    }

    @Test fun `manual play blocked on admission cannot arrive after leaving its session`() = runBlocking {
        Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val voice = f.repo.listForSession(f.session.id).single()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val hold = async(Dispatchers.IO) { f.runtime.playback.withVoiceReleased("unrelated") { entered.complete(Unit); release.await() } }
            entered.await()
            f.runtime.coordinator.setForegroundSession(f.session.id)
            val pending = f.runtime.coordinator.playFrom(voice, listOf(voice))
            delay(50)
            val leaving = f.runtime.foreground(null)
            release.complete(Unit); hold.await(); pending.join(); leaving.join()
            assertEquals(0, f.output.plays); assertFalse(f.output.playing)
        }
    }
    internal class Fixture(
        gate: com.example.chatbar.data.operation.AppDataOperationGate = com.example.chatbar.data.operation.NoOpAppDataOperationGate,
        outputFactory: (PreviewOutputFake) -> DesktopAudioOutput = { it },
    ) : AutoCloseable {
        val root = Files.createTempDirectory("s5-chat-")
        val storage = JsonFileStorage(root, gate); val chats = ChatRepository(storage)
        val characters = CharacterRepository(storage); val settings = SettingsRepository(storage)
        val models = EffectiveModelResolver(ModelRepository(storage), settings, object : PresetModelCatalogSource {
            override val catalog = PresetModelCatalog(); override val modelCatalogVersion: Int? = null
        })
        val repo = VoiceMessageRepository(storage, strictReads = true); val audio = DesktopVoiceAudioStore(root)
        val output = PreviewOutputFake(); val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic") }
        val server = MockWebServer().also { it.start() }
        val target = root.resolve("exported.mp3")
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? = null
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path = target
        }
        val transport = DesktopFishVoiceTransport(credentials, audio, OkHttpClient(), server.url("/").toString().trimEnd('/'), { LocalDate.of(2026,10,19) })
        val runtime = DesktopChatVoiceRuntime(chats, characters, settings, models, repo, audio, credentials, outputFactory(output),
            picker = picker, gate = gate, transportOverride = transport, textGateway = FishVoiceTextGateway { _, _, _, _ -> error("Unapproved auxiliary request") })
        lateinit var session: ChatSession; lateinit var message: ChatMessage
        suspend fun seed() {
            characters.save(CharacterCard("card", "synthetic", characters = listOf(CharacterInfo("alice", "Alice",
                fishAudioVoice = FishAudioVoiceBinding("reference", "snapshot"))), createdAt = 1, updatedAt = 1))
            session = ChatSession.create("card", "synthetic").copy(audiobookModeEnabled = true)
            chats.createSession(session)
            message = chats.addMessage(ChatMessage("message", session.id, MessageRole.ASSISTANT, "<n=\"Alice\"/>[hello]()", createdAt = 1, updatedAt = 1))
        }
        fun enqueue() { server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))) }
        suspend fun done(id: String?) { assertNotNull(id); withTimeout(5000) {
            while (runtime.coordinator.batches.value.any { it.id == id && it.phase !in setOf(VoiceGenerationPhase.FAILED, VoiceGenerationPhase.CANCELLED) }) delay(10)
        } }
        override fun close() { runBlocking { runtime.closeAndDrain() }; server.shutdown() }
    }

    @Test fun `UI facade distinguishes another take replacement export and explicit deletion`() = runBlocking {
        Fixture().use { f ->
            f.seed(); f.repo.initialize(); assertEquals(0, f.server.requestCount)
            f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val first = f.repo.listForSession(f.session.id).single()
            f.enqueue(); f.done(f.runtime.generate(f.message, "alice", auxiliaryAuthorized = true, additional = first))
            assertEquals(2, f.repo.listForSession(f.session.id).size); assertTrue(Files.exists(Path.of(first.audioPath)))
            f.enqueue(); f.done(f.runtime.generate(f.message, "alice", replace = first, editedTaggedText = "hello"))
            val replaced = assertNotNull(f.repo.get(first.id)); assertNotEquals(first.audioPath, replaced.audioPath)
            assertEquals(2, f.repo.listForSession(f.session.id).size); assertFalse(Files.exists(Path.of(first.audioPath)))
            f.runtime.save(replaced, "synthetic"); assertContentEquals(Files.readAllBytes(Path.of(replaced.audioPath)), Files.readAllBytes(f.target))
            f.runtime.deleteVoice(replaced); assertNull(f.repo.get(first.id)); assertFalse(Files.exists(Path.of(replaced.audioPath)))
            assertTrue(Files.exists(f.target)); assertEquals(1, f.repo.listForSession(f.session.id).size)
        }
    }

    @Test fun `stale UI target and unapproved auxiliary model never send network`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            f.chats.updateMessage(f.message.copy(content = "changed"))
            assertNull(f.runtime.generate(f.message, "alice", auxiliaryAuthorized = true))
            f.chats.updateSession(f.session.copy(audiobookModeEnabled = false))
            val current = assertNotNull(f.chats.getMessage(f.message.id, f.session.id))
            assertNull(f.runtime.generate(current, "alice", auxiliaryAuthorized = false))
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun `message deletion drains voice work and only removes explicitly owned files`() = runBlocking {
        Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val old = f.repo.listForSession(f.session.id).single()
            val unknown = f.root.resolve("audio/generated/unknown.mp3"); Files.writeString(unknown, "keep")
            f.runtime.mutateMessage(f.session.id, f.message.id) {
                f.chats.deleteMessage(f.message.id, f.session.id)
                f.runtime.removeDeletedMessage(f.session.id, f.message.id)
            }
            assertTrue(f.repo.listForSession(f.session.id).isEmpty()); assertFalse(Files.exists(Path.of(old.audioPath)))
            assertTrue(Files.exists(unknown))
        }
    }
}
