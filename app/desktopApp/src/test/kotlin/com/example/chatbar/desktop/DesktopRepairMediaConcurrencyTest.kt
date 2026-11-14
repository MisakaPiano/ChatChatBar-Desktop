package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.voice.FishVoiceTextGateway
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.*

/** Actual source-safety, repository CAS, native-focus reservation and formal Fish owners.
 * Only model/Fish transport and native output are synthetic; no permissive repair safety gate. */
class DesktopRepairMediaConcurrencyTest {
    private class Fixture(val beforePlayback: suspend () -> Unit = {}) {
        val f = DesktopMessageFormatRepairTest.Fixture()
        val tasks get() = f.runtime
        val c get() = f.container
        val chats get() = f.chats
        var ownership: DesktopDataRootOwnership? = null
        val output = PreviewOutputFake()
        val focus = DesktopAudioFocus(output)
        val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic") }
        val server = MockWebServer().also { it.start() }
        val transport by lazy { DesktopFishVoiceTransport(credentials, c.voiceAudioStore, OkHttpClient(),
            server.url("/").toString().trimEnd('/'), { LocalDate.of(2026, 10, 19) }) }
        val voices by lazy { DesktopChatVoiceRuntime(chats, c.characterRepository, c.settingsRepository,
            c.effectiveModelResolver, c.voiceRepository, c.voiceAudioStore, credentials, focus.port(formal = true),
            gate = c.dataOperationCoordinator, sourceBusy = tasks::hasActiveVoiceSourceMutation,
            transportOverride = transport, textGateway = FishVoiceTextGateway { _, _, _, _ -> error("No auxiliary request") },
            withPlaybackOperation = { work -> tasks.withRepairCompatiblePlayback { beforePlayback(); work() } }) }
        val maintenance = DesktopAudioMaintenance({ focus }, { voices.stopForMaintenance() })
        val changed = AtomicInteger()
        val requests = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val safety by lazy { DesktopFormatRepairSourceSafety(c.appDataRoot, c.jsonFileStorage, c.dataOperationCoordinator,
            tasks, maintenance, { voices }, { changed.incrementAndGet() }) }
        val repair by lazy { DesktopMessageFormatRepairController(chats, c.settingsRepository, c.characterRepository,
            c.formatCardRepository, c.effectiveModelResolver, tasks, safety) {
            requests.incrementAndGet()
            flow { entered.complete(Unit); release.await(); emit(StreamEvent.Delta("fixed")); emit(StreamEvent.Done) }
        } }
        lateinit var b: ChatMessage
        suspend fun prepare() {
            f.prepare()
            ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(c.resolvedRoot)).ownership
            val session = chats.getSession("s")!!
            val card = c.characterRepository.getById(session.characterCardId)!!
            c.characterRepository.save(card.copy(characters = listOf(CharacterInfo("alice", "Alice",
                fishAudioVoice = FishAudioVoiceBinding("reference", "fixture")))))
            chats.updateSession(session.copy(audiobookModeEnabled = true))
            b = chats.addMessage(ChatMessage("b", "s", MessageRole.ASSISTANT, "<n=\"Alice\"/>[hello]()", createdAt = 3, updatedAt = 3))
        }
        suspend fun startRepair(): String = repair.repair("s", "m").also { withTimeout(5_000) { entered.await() } }
        suspend fun repaired(id: String) = f.await(id, repair)
        suspend fun terminal(id: String) = withTimeout(8_000) {
            tasks.tasks.first { rows -> rows.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }.single { it.taskId == id }
        }
        fun image(retry: Boolean = false, work: suspend ((String) -> Unit) -> Unit): String =
            tasks.launchNovelAi("synthetic source generation", "s", "b", retryable = retry, queueIfBusy = true,
                deferSourceCommitForRepair = true, work = work)
        suspend fun persist(report: (String) -> Unit) = report.withSourceCommit {
            val png = DesktopImageEditing.png(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB))
            persistDesktopChatImages(chats, c.characterResourceStore, c.dataOperationCoordinator, b,
                NovelAiPromptPlan("fixture", emptyList()), NovelAiImageSize(4, 4, "fixture"), listOf(png),
                NovelAiGenerationRecipe(), { chats.getMessage("b", "s") == b }, chats.getSession("s")!!.characterCardId)
        }
        suspend fun generate(): String {
            voices.foreground("s").join()
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3())))
            return assertNotNull(voices.generate(b, "alice"))
        }
        suspend fun savedVoice() = withTimeout(8_000) {
            while (c.voiceRepository.listForSession("s").isEmpty()) delay(10)
            c.voiceRepository.listForSession("s").single()
        }
        suspend fun close() {
            try { voices.closeAndDrain(); transport.closeAndDrain(); focus.closeAndDrain(); c.close() }
            finally { ownership?.close(); server.shutdown(); f.root.toFile().deleteRecursively() }
        }
    }
    private suspend fun fixture(beforePlayback: suspend () -> Unit = {}, block: suspend (Fixture) -> Unit) {
        val f = Fixture(beforePlayback)
        try { f.prepare(); withTimeout(25_000) { block(f) } } finally { f.close() }
    }

    @Test fun `different image work overlaps real repair but shared publication follows its CAS in either start order`() = runBlocking {
        for (imageFirst in listOf(false, true)) fixture { f ->
            val generation = CompletableDeferred<Unit>(); val generated = CompletableDeferred<Unit>()
            suspend fun image() = f.image { report -> generation.complete(Unit); generated.await(); f.persist(report) }
            val imageId = if (imageFirst) image().also { generation.await() } else null
            val repairId = f.startRepair()
            val id = imageId ?: image().also { generation.await() }
            generated.complete(Unit)
            withTimeout(5_000) { f.tasks.tasks.first { rows -> rows.any {
                it.taskId == id && it.message == "核对来源并等待格式修复完成" } } }
            assertEquals(DesktopTaskStatus.RUNNING, f.tasks.tasks.value.single { it.taskId == id }.status)
            assertTrue(f.chats.getMessagesReadOnly("s").none { it.generatedFromMessageId == "b" })
            assertNull(f.voices.generate(f.f.original, "alice")); assertEquals(0, f.server.requestCount)
            assertFailsWith<DesktopTaskAdmissionException> {
                f.tasks.launchNovelAi("same source", "s", "m", queueIfBusy = true, deferSourceCommitForRepair = true) { error("Not admitted") }
            }
            f.release.complete(Unit)
            assertEquals(DesktopRepairStatus.APPLIED, f.repaired(repairId).status)
            assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(id).status)
            val cold = ChatRepository(JsonFileStorage(f.c.appDataRoot)).getMessagesReadOnly("s")
            assertEquals("fixed", cold.single { it.id == "m" }.displayContent)
            assertEquals(f.b, cold.single { it.id == "b" })
            val child = cold.single { it.generatedFromMessageId == "b" }
            assertTrue(Files.exists(f.c.appDataRoot.resolve(child.images.single())))
            assertEquals(1, f.changed.get()); assertEquals(1, f.requests.get())
        }
    }

    @Test fun `publication reservation blocks a new repair and uncertain retry never gains a fresh safe proof`() = runBlocking { fixture { f ->
        val publication = CompletableDeferred<Unit>(); val fail = CompletableDeferred<Unit>()
        val id = f.image(retry = true) { report -> report.withSourceCommit {
            publication.complete(Unit); fail.await(); error("synthetic uncertain publication")
        } }
        publication.await()
        assertFailsWith<DesktopTaskAdmissionException> { f.repair.repair("s", "m") }
        fail.complete(Unit); assertEquals(DesktopTaskStatus.FAILED, f.terminal(id).status)
        val refused = f.repair.repair("s", "m")
        assertEquals(DesktopRepairStatus.REJECTED, f.repaired(refused).status)
        assertEquals(0, f.requests.get())
        val retry = assertNotNull(f.tasks.retryImageTask(id))
        f.terminal(retry)
        assertEquals(DesktopRepairStatus.REJECTED, f.repaired(f.repair.repair("s", "m")).status)
        assertEquals(0, f.requests.get())
    } }

    @Test fun `stopping an image waiting for publication leaves repair and its saved source untouched`() = runBlocking { fixture { f ->
        val repair = f.startRepair(); val generated = CompletableDeferred<Unit>()
        val image = f.image(retry = true) { report -> generated.complete(Unit); f.persist(report) }
        generated.await(); assertTrue(f.tasks.requestUserStop(image))
        assertEquals(DesktopTaskStatus.USER_STOPPED, f.terminal(image).status)
        f.release.complete(Unit); assertEquals(DesktopRepairStatus.APPLIED, f.repaired(repair).status)
        assertEquals(f.b, f.chats.getMessage("b", "s"))
        assertTrue(f.chats.getMessagesReadOnly("s").none { it.generatedFromMessageId == "b" })
        assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(assertNotNull(f.tasks.retryImageTask(image))).status)
    } }

    @Test fun `unknown image writers remain conservative and maintenance cancels both source waits without late commit`() = runBlocking {
        fixture { f ->
            val started = CompletableDeferred<Unit>()
            val unknown = f.tasks.launchNovelAi("unclassified") { started.complete(Unit); awaitCancellation() }
            started.await(); assertFailsWith<DesktopTaskAdmissionException> { f.repair.repair("s", "m") }
            assertTrue(f.tasks.requestUserStop(unknown)); f.terminal(unknown)
            val repair = f.startRepair(); val image = f.image { f.persist(it) }
            f.tasks.withMaintenance { }
            assertEquals(DesktopRepairStatus.CANCELLED, f.repaired(repair).status)
            assertEquals(DesktopTaskStatus.CANCELLED, f.terminal(image).status)
            assertEquals(f.f.original, f.chats.getMessage("m", "s"))
            assertTrue(f.chats.getMessagesReadOnly("s").none { it.generatedFromMessageId == "b" })
        }
    }

    @Test fun `formal Fish request and durable recording overlap repair while actual native start waits and survives source commit`() = runBlocking { fixture { f ->
        val repair = f.startRepair(); f.generate()
        val voice = f.savedVoice()
        withTimeout(5_000) { f.voices.playback.state.first { it.currentVoiceId == voice.id } }
        assertEquals(1, f.server.requestCount); assertEquals(0, f.output.plays)
        assertTrue(Files.exists(Path.of(voice.audioPath)))
        f.release.complete(Unit); assertEquals(DesktopRepairStatus.APPLIED, f.repaired(repair).status)
        withTimeout(5_000) { f.voices.playback.state.first { it.playing } }
        assertEquals(1, f.output.plays); assertEquals(voice, f.c.voiceRepository.get(voice.id))
        assertTrue(Files.exists(Path.of(voice.audioPath)))
    } }

    @Test fun `reverse start reserves actual playback before native opening so repair cannot pass its idle check`() = runBlocking {
        val opening = CompletableDeferred<Unit>(); val open = CompletableDeferred<Unit>()
        fixture(beforePlayback = { opening.complete(Unit); open.await() }) { f ->
            f.generate(); val voice = f.savedVoice(); opening.await()
            assertEquals(0, f.output.plays)
            assertFailsWith<DesktopTaskAdmissionException> { f.repair.repair("s", "m") }
            assertEquals(0, f.requests.get())
            open.complete(Unit); withTimeout(5_000) { f.voices.playback.state.first { it.playing } }
            f.voices.stopForMaintenance()
            val repair = f.startRepair(); f.release.complete(Unit)
            assertEquals(DesktopRepairStatus.APPLIED, f.repaired(repair).status)
            assertEquals(voice, f.c.voiceRepository.get(voice.id))
        }
    }

    @Test fun `stopping queued playback cancels only local intent and a later recording can play after repair`() = runBlocking { fixture { f ->
        val repair = f.startRepair(); f.generate(); val old = f.savedVoice()
        withTimeout(5_000) { f.voices.playback.state.first { it.currentVoiceId == old.id } }
        f.voices.stopForMaintenance()
        assertEquals(0, f.output.plays); assertEquals(old, f.c.voiceRepository.get(old.id))
        f.release.complete(Unit); assertEquals(DesktopRepairStatus.APPLIED, f.repaired(repair).status)
        f.b = f.chats.addMessage(f.b.copy(id = "c", createdAt = 4, updatedAt = 4))
        f.generate()
        withTimeout(5_000) { f.voices.playback.state.first { it.playing } }
        assertEquals(1, f.output.plays); assertEquals(2, f.server.requestCount)
        assertTrue(Files.exists(Path.of(old.audioPath)))
    } }

    @Test fun `repair withdraws its selected waiting source without dropping another queued source`() = runBlocking { fixture { f ->
        f.voices.foreground(null).join()
        f.server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3())))
        assertNotNull(f.voices.generate(f.b, "alice"))
        val b = f.savedVoice()
        withTimeout(5_000) { while (f.voices.coordinator.batches.value.isNotEmpty()) delay(10) }
        val a = b.copy(id = "old-a", messageId = "m", messageVersionId = "b")
        f.c.voiceRepository.save(a)
        val repair = f.startRepair()
        assertTrue(f.voices.playback.enqueueSequence(listOf(a)))
        withTimeout(5_000) { f.voices.playback.state.first { it.currentVoiceId == a.id } }
        assertTrue(f.voices.playback.enqueueSequence(listOf(b)))
        assertEquals(0, f.output.plays)
        f.release.complete(Unit)
        assertEquals(DesktopRepairStatus.APPLIED, f.repaired(repair).status)
        withTimeout(5_000) { f.voices.playback.state.first { it.playing && it.currentVoiceId == b.id } }
        assertEquals(1, f.output.plays)
        assertEquals(a, f.c.voiceRepository.get(a.id)); assertEquals(b, f.c.voiceRepository.get(b.id))
        assertTrue(Files.exists(Path.of(b.audioPath)))
    } }
}
