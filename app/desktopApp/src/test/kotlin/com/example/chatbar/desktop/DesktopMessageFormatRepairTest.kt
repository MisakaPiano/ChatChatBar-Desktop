package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.FormatRepairPromptAuthority
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class DesktopMessageFormatRepairTest {
    private class Gate : AppDataOperationGate {
        val calls = AtomicInteger()
        var hook: suspend (Int, Boolean) -> Unit = { _, _ -> }
        override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
            val n = calls.incrementAndGet()
            hook(n, false)
            return operation().also { hook(n, true) }
        }
    }

    /** Default integration topology is exactly one container/root/repository for chat and repair.
     * Separate storage is opt-in and exclusively for operation-gate fault injection. */
    private class Fixture(val faultInjection: Boolean = false) {
        val root = Files.createTempDirectory("desktop-format-repair-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("container"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path ->
                check(path == "presets/manifest.json")
                """{"entries":[]}""".toByteArray()
            })
        val gate = Gate()
        val dataRoot = if (faultInjection) root.resolve("fault-injection") else container.appDataRoot
        val storage = if (faultInjection) JsonFileStorage(dataRoot, gate) else container.jsonFileStorage
        val chats = if (faultInjection) ChatRepository(storage) else container.chatRepository
        val runtime get() = container.taskRuntime
        val requests = AtomicInteger()
        var response: (DesktopRepairRequest) -> Flow<StreamEvent> = { flowOf(StreamEvent.Delta("fixed"), StreamEvent.Done) }
        var safety: suspend (ChatSession, ChatMessage) -> DesktopRepairSafety = { _, _ -> DesktopRepairSafety.VERIFIED_SAFE }
        val controller by lazy { DesktopMessageFormatRepairController(chats, container.settingsRepository,
            container.characterRepository, container.formatCardRepository, container.effectiveModelResolver,
            runtime, DesktopRepairSafetyGate { s, m -> safety(s, m) }, { requests.incrementAndGet(); response(it) }) }
        lateinit var original: ChatMessage
        suspend fun prepare() {
            val card = CharacterCard.create("Bot")
            container.characterRepository.save(card)
            container.formatCardRepository.save(FormatCard("format", "synthetic", "Rule for \$botname and \$username", createdAt = 1))
            container.modelRepository.saveModel(ModelConfig("model", "Synthetic", "https://invalid.example/v1", "fake-key", "fake-model", createdAt = 1))
            container.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "model", defaultFormatCardId = "format",
                assistantSegmentedBubblesEnabled = false))
            chats.createSession(ChatSession("s", card.id, "Synthetic", playerName = "SessionPlayer", createdAt = 1, updatedAt = 1))
            val image = root.resolve("owned.png"); Files.write(image, byteArrayOf(1, 2, 3))
            original = chats.addMessage(ChatMessage("m", "s", MessageRole.ASSISTANT, "original😀body",
                alternatives = listOf("first", "original😀body", "last"), alternativeVersionIds = listOf("a", "b", "c"),
                currentAlternativeIndex = 1, currentAlternativeVersionId = "b", reasoningContent = "separate reasoning",
                images = listOf(image.toString()), sourceTurnId = "turn", sourceTurnOrder = 1, createdAt = 2, updatedAt = 2))
        }
        suspend fun saved() = assertNotNull(chats.getMessage("m", "s"))
        suspend fun reopened() = assertNotNull(ChatRepository(JsonFileStorage(dataRoot)).getMessage("m", "s"))
        suspend fun run(restore: Boolean = false): DesktopRepairState = await(if (restore) controller.restore("s", "m") else controller.repair("s", "m"))
        suspend fun await(id: String, owner: DesktopMessageFormatRepairController = controller): DesktopRepairState = withTimeout(8_000) {
            runtime.repairTasks.first { it[id] != null && it[id] != DesktopRepairStatus.RUNNING }
            assertNotNull(owner.states.value[id])
        }
        suspend fun replace(message: ChatMessage) { chats.updateMessage(message) }
        suspend fun settings(change: (AppSettings) -> AppSettings) {
            container.settingsRepository.saveAppSettings(change(container.settingsRepository.getAppSettings()))
        }
        suspend fun close() { container.close(); root.toFile().deleteRecursively() }
    }
    private suspend fun fixture(faultInjection: Boolean = false, block: suspend (Fixture) -> Unit) {
        val f = Fixture(faultInjection)
        try { f.prepare(); block(f) } finally { f.close() }
    }

    @Test fun `applied and restore persist across reopen preserving non-owned fields`() = runBlocking { fixture { f ->
        assertEquals(DesktopRepairStatus.APPLIED, f.run().status)
        val fixed = f.reopened()
        assertEquals(f.original.copy(content = "fixed", alternatives = listOf("first", "fixed", "last"),
            formatRepairNotice = MessageFormatRepairPolicy.completedRepairNotice(f.original.displayContent, "fixed"),
            updatedAt = fixed.updatedAt), fixed)
        assertEquals(DesktopRepairStatus.RESTORED, f.run(restore = true).status)
        assertEquals(f.original.copy(updatedAt = f.reopened().updatedAt), f.reopened())
        assertEquals(1, f.requests.get())
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(f.root.resolve("owned.png")))
    } }

    @Test fun `unchanged marker and identical body retain applied and stopped recovery points`() = runBlocking {
        for (kind in listOf(MessageFormatRepairNoticeKind.APPLIED, MessageFormatRepairNoticeKind.STOPPED)) fixture { f ->
            val notice = MessageFormatRepairNotice(kind, f.original.displayContent, "older")
            f.replace(f.original.copy(content = "distinct base", formatRepairNotice = notice))
            val before = f.saved()
            for (output in listOf(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER, before.displayContent)) {
                f.response = { flowOf(StreamEvent.Delta(output), StreamEvent.Done) }
                assertEquals(DesktopRepairStatus.UNCHANGED, f.run().status)
                assertEquals(before, f.reopened())
            }
        }
    }

    @Test fun `error notice only never normalizes base content or commits prefix`() = runBlocking { fixture { f ->
        f.replace(f.original.copy(content = "distinct base"))
        val before = f.saved()
        f.response = { flowOf(StreamEvent.Delta("unsafe prefix"), StreamEvent.Error("private provider payload")) }
        val result = f.run()
        assertEquals(DesktopRepairStatus.ERROR, result.status)
        val saved = f.reopened()
        assertEquals(before.copy(updatedAt = saved.updatedAt, formatRepairNotice = saved.formatRepairNotice), saved)
        assertEquals(MessageFormatRepairNoticeKind.ERROR, saved.formatRepairNotice?.kind)
        assertEquals("STREAM_ERROR", saved.formatRepairNotice?.errorMessage)
        assertEquals(1, f.requests.get())
        assertTrue(f.runtime.tasks.value.none { it.contentPreview.contains("prefix") })
    } }

    @Test fun `D039 repeated failure preserves an applicable recovery point byte for byte`() = runBlocking {
        for (kind in listOf(MessageFormatRepairNoticeKind.APPLIED, MessageFormatRepairNoticeKind.STOPPED)) fixture { f ->
            f.replace(f.original.copy(formatRepairNotice = MessageFormatRepairNotice(kind, f.original.displayContent, "recover me")))
            val before = f.saved()
            f.response = { flowOf(StreamEvent.Delta("discard"), StreamEvent.Error("secret")) }
            repeat(2) { assertEquals(DesktopRepairStatus.ERROR, f.run().status); assertEquals(before, f.reopened()) }
            assertEquals(2, f.requests.get())
        }
    }

    @Test fun `explicit stop persists Unicode overlay for empty short long emoji and marker prefixes`() = runBlocking {
        for (prefix in listOf("", "x", "longer than the entire original body", "🦊", "[[CHATBAR_FORMAT_")) fixture { f ->
            val received = CompletableDeferred<Unit>()
            f.response = { flow { emit(StreamEvent.Delta(prefix)); received.complete(Unit); awaitCancellation() } }
            val id = f.controller.repair("s", "m")
            withTimeout(8000) { received.await() }
            assertTrue(f.runtime.requestUserStop(id))
            assertFalse(f.runtime.requestUserStop(id))
            assertEquals(DesktopRepairStatus.STOPPED, f.await(id).status)
            val saved = f.reopened()
            assertEquals(MessageFormatRepairPolicy.progressiveOverlay(f.original.displayContent, prefix), saved.displayContent)
            assertEquals(MessageFormatRepairNoticeKind.STOPPED, saved.formatRepairNotice?.kind)
            assertEquals(f.original.displayContent, saved.formatRepairNotice?.originalContent)
        }
    }

    @Test fun `done and error stop collection without waiting for post-terminal flow code`() = runBlocking {
        for (error in listOf(false, true)) fixture { f ->
            val cleaned = CompletableDeferred<Unit>()
            var postTerminalReached = false
            f.response = { flow {
                try {
                    emit(StreamEvent.Delta("fixed"))
                    emit(if (error) StreamEvent.Error("private", com.example.chatbar.domain.prompt.AiTaskFailureKind.NETWORK,
                        AuxiliaryProtocolException(AuxiliaryProtocolFailure.EARLY_EOF)) else StreamEvent.Done)
                    postTerminalReached = true
                    awaitCancellation()
                } finally { cleaned.complete(Unit) }
            } }
            val id = f.controller.repair("s", "m")
            val result = f.await(id)
            assertEquals(if (error) DesktopRepairStatus.ERROR else DesktopRepairStatus.APPLIED, result.status)
            assertEquals(if (error) "NETWORK:EARLY_EOF" else null, result.code)
            assertTrue(cleaned.isCompleted)
            assertFalse(postTerminalReached)
            assertFalse(f.runtime.requestUserStop(id))
            assertEquals(if (error) f.original.displayContent else "fixed", f.reopened().displayContent)
            assertEquals(1, f.requests.get())
        }
    }

    @Test fun `observed exception EOF and ordinary cancellation are sealed before late stop`() = runBlocking {
        for (mode in 0..2) fixture { f ->
            val beforeCommit = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val taskIdentity = CompletableDeferred<String>(); val stopAfterChildCompletion = CompletableDeferred<Boolean>()
            var checks = 0
            f.safety = { _, _ ->
                if (++checks == 2) { beforeCommit.complete(Unit); release.await() }
                DesktopRepairSafety.VERIFIED_SAFE
            }
            f.response = { flow {
                val task = taskIdentity.await()
                currentCoroutineContext().job.invokeOnCompletion {
                    stopAfterChildCompletion.complete(f.runtime.requestUserStop(task))
                }
                emit(StreamEvent.Delta("discard"))
                when (mode) {
                    0 -> throw AuxiliaryProtocolException(AuxiliaryProtocolFailure.MALFORMED_SSE)
                    1 -> Unit // Unconfirmed natural EOF.
                    2 -> throw CancellationException("synthetic ordinary cancellation")
                }
            } }
            val id = f.controller.repair("s", "m")
            taskIdentity.complete(id)
            // The child has already observed exception/EOF/cancellation, independent of when the parent resumes.
            assertFalse(withTimeout(8000) { stopAfterChildCompletion.await() })
            if (mode < 2) {
                withTimeout(8000) { beforeCommit.await() }
                assertEquals(DesktopRepairStatus.RUNNING, f.runtime.repairTasks.value[id])
                assertFalse(f.runtime.requestUserStop(id))
                release.complete(Unit)
                val result = f.await(id)
                assertEquals(DesktopRepairStatus.ERROR, result.status)
                assertEquals(if (mode == 0) "MALFORMED_SSE" else "UNCONFIRMED_TERMINAL", result.code)
            } else {
                assertEquals(DesktopRepairStatus.CANCELLED, f.await(id).status)
                assertFalse(f.runtime.requestUserStop(id))
                assertEquals(f.original, f.reopened())
            }
            assertEquals(f.original.displayContent, f.reopened().displayContent)
        }
    }

    @Test fun `stop preceding a network cleanup failure retains the stop decision`() = runBlocking { fixture { f ->
        val entered = CompletableDeferred<Unit>()
        f.response = { flow {
            emit(StreamEvent.Delta("🦊")); entered.complete(Unit)
            try { awaitCancellation() } finally { throw IOException("synthetic disconnect after Stop") }
        } }
        val id = f.controller.repair("s", "m")
        withTimeout(8000) { entered.await() }
        assertTrue(f.runtime.requestUserStop(id))
        assertEquals(DesktopRepairStatus.STOPPED, f.await(id).status)
        assertEquals(MessageFormatRepairPolicy.progressiveOverlay(f.original.displayContent, "🦊"), f.reopened().displayContent)
        assertEquals(1, f.requests.get())
    } }

    @Test fun `trusted Done or Error is not replaced by a later flow cleanup cancellation`() = runBlocking {
        for (error in listOf(false, true)) fixture { f ->
            f.response = { flow {
                try {
                    emit(StreamEvent.Delta("fixed"))
                    emit(if (error) StreamEvent.Error("private", cause = AuxiliaryProtocolException(AuxiliaryProtocolFailure.MALFORMED_SSE)) else StreamEvent.Done)
                } finally { throw CancellationException("synthetic post-terminal cleanup cancellation") }
            } }
            val result = f.run()
            assertEquals(if (error) DesktopRepairStatus.ERROR else DesktopRepairStatus.APPLIED, result.status)
            assertEquals(if (error) "MALFORMED_SSE" else null, result.code)
            assertEquals(if (error) f.original.displayContent else "fixed", f.reopened().displayContent)
        }
    }

    @Test fun `first terminal wins even before the parent continuation resumes`() {
        for (kind in listOf(DesktopRepairTerminalKind.DONE, DesktopRepairTerminalKind.ERROR, DesktopRepairTerminalKind.CANCELLED)) {
            val control = DesktopRepairControl()
            val first = control.complete(kind, "fixed-category")
            assertFalse(control.requestStop())
            assertEquals(first, control.complete(DesktopRepairTerminalKind.ERROR, "late-category"))
            var lateDelta = false
            control.acceptDelta { lateDelta = true }
            assertFalse(lateDelta)
        }
        val stopped = DesktopRepairControl()
        assertTrue(stopped.requestStop())
        assertEquals(DesktopRepairTerminalKind.STOP, stopped.complete(DesktopRepairTerminalKind.ERROR, "disconnect").kind)
    }

    @Test fun `shutdown after explicit stop but before controlled commit discards preview`() = runBlocking { fixture { f ->
        val streamEntered = CompletableDeferred<Unit>(); val commitEntered = CompletableDeferred<Unit>()
        var checks = 0
        f.safety = { _, _ ->
            if (++checks == 2) { commitEntered.complete(Unit); awaitCancellation() }
            DesktopRepairSafety.VERIFIED_SAFE
        }
        f.response = { flow { emit(StreamEvent.Delta("preview")); streamEntered.complete(Unit); awaitCancellation() } }
        val id = f.controller.repair("s", "m")
        withTimeout(8000) { streamEntered.await() }
        assertTrue(f.runtime.requestUserStop(id))
        withTimeout(8000) { commitEntered.await() }
        f.runtime.closeAndDrain()
        assertEquals(DesktopRepairStatus.CANCELLED, f.await(id).status)
        assertEquals(f.original, f.reopened())
    } }

    @Test fun `shutdown discards preview and drains without STOPPED notice`() = runBlocking { fixture { f ->
        val received = CompletableDeferred<Unit>()
        f.response = { flow { emit(StreamEvent.Delta("preview")); received.complete(Unit); awaitCancellation() } }
        val id = f.controller.repair("s", "m")
        withTimeout(8000) { received.await() }
        f.runtime.closeAndDrain()
        assertEquals(DesktopRepairStatus.CANCELLED, f.await(id).status)
        assertEquals(f.original, f.reopened())
        assertFailsWith<DesktopTaskAdmissionException> { f.controller.repair("s", "m") }
    } }

    @Test fun `empty reasoning only EOF and typed errors discard prefix with single request`() = runBlocking {
        val errors = com.example.chatbar.domain.prompt.AiTaskFailureKind.entries
        val responses = listOf(flowOf(StreamEvent.Done), flowOf(StreamEvent.ReasoningDelta("hidden"), StreamEvent.Done),
            flowOf(StreamEvent.Delta("prefix"))) + errors.map { flowOf(StreamEvent.Delta("prefix"), StreamEvent.Error("private", it)) }
        for (response in responses) fixture { f ->
            f.response = { response }
            assertEquals(DesktopRepairStatus.ERROR, f.run().status)
            assertEquals(f.original.displayContent, f.reopened().displayContent)
            assertEquals(1, f.requests.get())
        }
    }

    @Test fun `edits candidate changes regeneration deletion and session deletion reject late results`() = runBlocking {
        for (mutation in 0..4) fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.response = { flow { entered.complete(Unit); release.await(); emit(StreamEvent.Delta("late")); emit(StreamEvent.Done) } }
            val id = f.controller.repair("s", "m"); withTimeout(8000) { entered.await() }
            when (mutation) {
                0 -> f.replace(f.original.copy(content = "edited", alternatives = listOf("first", "edited", "last")))
                1 -> f.replace(f.original.copy(currentAlternativeIndex = 0, currentAlternativeVersionId = "a"))
                2 -> f.replace(f.original.copy(alternatives = f.original.alternatives + "generated", alternativeVersionIds = listOf("a", "b", "c", "d")))
                3 -> f.chats.deleteMessage("m", "s")
                4 -> f.chats.deleteSession("s")
            }
            val before = f.chats.getMessage("m", "s")
            release.complete(Unit)
            assertEquals(if (mutation >= 3) DesktopRepairStatus.MISSING else DesktopRepairStatus.CONFLICT, f.await(id).status)
            assertEquals(before, f.chats.getMessage("m", "s"))
        }
    }

    @Test fun `repair admission rejects duplicate chat and source image tasks until terminal`() = runBlocking { fixture { f ->
        val entered = CompletableDeferred<Unit>()
        f.response = { flow { entered.complete(Unit); awaitCancellation() } }
        val id = f.controller.repair("s", "m"); withTimeout(8000) { entered.await() }
        assertFailsWith<DesktopTaskAdmissionException> { f.controller.repair("s", "m") }
        assertFailsWith<DesktopTaskAdmissionException> { f.runtime.launchChat("s", "not sent") }
        assertFailsWith<DesktopTaskAdmissionException> { f.runtime.launchNovelAi("not started", "s", "m") {} }
        assertTrue(f.runtime.requestUserStop(id)); f.await(id)
        val imageEntered = CompletableDeferred<Unit>()
        val image = f.runtime.launchNovelAi("synthetic", "s", "m") { imageEntered.complete(Unit); awaitCancellation() }
        withTimeout(8000) { imageEntered.await() }
        assertFailsWith<DesktopTaskAdmissionException> { f.controller.repair("s", "m") }
        assertTrue(f.runtime.requestUserStop(image))
    } }

    @Test fun `unknown unsafe and changed derived proof reject without mutation`() = runBlocking {
        for (mode in 0..2) fixture { f ->
            var checks = 0
            f.safety = { _, _ -> checks++; if (mode == 2 && checks == 1) DesktopRepairSafety.VERIFIED_SAFE
                else if (mode == 1) DesktopRepairSafety.UNSAFE else DesktopRepairSafety.UNKNOWN }
            assertEquals(DesktopRepairStatus.REJECTED, f.run().status)
            assertEquals(if (mode == 2) 1 else 0, f.requests.get())
            assertEquals(f.original, f.reopened())
        }
    }

    @Test fun `no rules and stale explicit format skip without request or error notice`() = runBlocking {
        for (mode in 0..1) fixture { f ->
            when (mode) {
                0 -> f.settings { it.copy(defaultFormatCardId = null) }
                1 -> f.chats.updateSession(assertNotNull(f.chats.getSession("s")).copy(formatCardId = "stale"))
            }
            assertEquals(DesktopRepairStatus.REJECTED, f.run().status)
            assertEquals(0, f.requests.get()); assertEquals(f.original, f.reopened())
        }
    }

    @Test fun `model configuration errors preserve old recovery or conditionally store notice only`() = runBlocking {
        for (mode in 0..3) for (kind in listOf(null, MessageFormatRepairNoticeKind.APPLIED, MessageFormatRepairNoticeKind.STOPPED)) fixture { f ->
            val notice = kind?.let { MessageFormatRepairNotice(it, f.original.displayContent, "recover me") }
            f.replace(f.original.copy(content = "different base", formatRepairNotice = notice))
            val before = f.saved()
            when (mode) {
                0 -> f.settings { it.copy(formatRepairModelId = "missing") }
                1 -> {
                    f.container.modelRepository.deleteModel("model")
                    f.settings { it.copy(defaultModelId = null) }
                    assertTrue(f.container.effectiveModelResolver.availableChatModels().isEmpty())
                    assertTrue(f.container.effectiveModelResolver.availableAuxiliaryTextModels().isEmpty())
                }
                2, 3 -> {
                    f.settings { it.copy(allowCleartextModelApi = mode == 3) }
                    f.container.modelRepository.saveModel(ModelConfig("model", "Synthetic",
                        if (mode == 3) "http://127.0.0.1:1/v1" else "https://invalid.example/v1", "", "fake-model", createdAt = 1))
                }
            }
            repeat(2) {
                val result = f.run()
                assertEquals(DesktopRepairStatus.ERROR, result.status)
                assertEquals(if (mode < 2) "INVALID_MODEL" else "AUTHENTICATION_REQUIRED", result.code)
                assertIs<ConditionalMessageUpdateResult.Success>(result.commit)
                val saved = f.reopened()
                if (kind != null) assertEquals(before, saved)
                else {
                    assertEquals(before.copy(formatRepairNotice = saved.formatRepairNotice, updatedAt = saved.updatedAt), saved)
                    assertEquals(MessageFormatRepairNotice(MessageFormatRepairNoticeKind.ERROR, before.displayContent,
                        errorMessage = result.code), saved.formatRepairNotice)
                }
            }
            assertEquals(0, f.requests.get())
        }
    }

    @Test fun `unset default still follows existing first available model semantics`() = runBlocking { fixture { f ->
        f.settings { it.copy(defaultModelId = null) }
        assertEquals("model", f.container.effectiveModelResolver.resolveFormatRepairModel(null)?.id)
        assertEquals(DesktopRepairStatus.APPLIED, f.run().status)
        assertEquals(1, f.requests.get())
    } }

    @Test fun `configuration error conditional commit keeps conflict storage failure and uncertain distinct`() = runBlocking {
        for (mode in 0..2) fixture(faultInjection = true) { f ->
            f.settings { it.copy(formatRepairModelId = "missing") }
            val failure = IOException("synthetic notice commit failure")
            var checks = 0
            f.safety = { _, _ ->
                if (++checks == 2) {
                    if (mode == 0) f.replace(f.original.copy(reasoningContent = "concurrent metadata edit"))
                    else {
                        f.gate.calls.set(0)
                        f.gate.hook = { n, after -> if (n == 3 && after == (mode == 2)) throw failure }
                    }
                }
                DesktopRepairSafety.VERIFIED_SAFE
            }
            val result = f.run()
            assertEquals(listOf(DesktopRepairStatus.CONFLICT, DesktopRepairStatus.STORAGE_FAILURE, DesktopRepairStatus.COMMIT_UNCERTAIN)[mode], result.status)
            when (mode) {
                0 -> assertIs<ConditionalMessageUpdateResult.Conflict>(result.commit)
                1 -> assertIs<ConditionalMessageUpdateResult.StorageFailure>(result.commit)
                2 -> {
                    val uncertain = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(result.commit)
                    assertEquals(MessageUpdateStage.WRITE_MESSAGE, uncertain.stage)
                    assertEquals(failure.message, uncertain.cause.message)
                    assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(uncertain.durableMessage)
                }
            }
            assertEquals(0, f.requests.get())
            f.gate.hook = { _, _ -> }
            assertEquals(f.original.displayContent, f.reopened().displayContent)
        }
    }

    @Test fun `production default unknown safety gate rejects on the real container repository`() = runBlocking { fixture { f ->
        assertSame(f.container.chatRepository, f.chats)
        assertSame(f.container.jsonFileStorage, f.storage)
        val production = f.container.messageFormatRepairController
        val id = production.repair("s", "m")
        val result = f.await(id, production)
        assertEquals(DesktopRepairStatus.REJECTED, result.status)
        assertEquals("DERIVED_DATA_UNPROVEN", result.code)
        assertEquals(f.original, f.reopened())
        assertEquals(0, f.requests.get())
        assertEquals(DesktopRepairStatus.APPLIED, f.run().status) // Explicit synthetic proof only.
    } }

    @Test fun `same container REAL CHAT and repair deny each other without a second request`() = runBlocking { fixture { f ->
        assertSame(f.container.chatRepository, f.chats)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    return MockResponse().addHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"synthetic chat\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                }
            }
            f.container.modelRepository.saveModel(ModelConfig("model", "Synthetic", server.url("/v1").toString(),
                "fake-local-key", "fake-model", createdAt = 1))
            f.settings { it.copy(allowCleartextModelApi = true) }
            try {
                val chat = f.runtime.launchChat("s", "synthetic user input")
                withTimeout(8000) { entered.await() }
                assertFailsWith<DesktopTaskAdmissionException> { f.controller.repair("s", "m") }
                assertEquals(0, f.requests.get())
                release.countDown()
                val finished = withTimeout(8000) { f.runtime.tasks.first { tasks -> tasks.any { it.taskId == chat && it.status != DesktopTaskStatus.RUNNING } } }
                assertEquals(DesktopTaskStatus.COMPLETED, finished.single { it.taskId == chat }.status)
                val repairEntered = CompletableDeferred<Unit>()
                f.response = { flow { repairEntered.complete(Unit); awaitCancellation() } }
                val repair = f.controller.repair("s", "m")
                withTimeout(8000) { repairEntered.await() }
                assertFailsWith<DesktopTaskAdmissionException> { f.runtime.launchChat("s", "must not send") }
                assertEquals(1, server.requestCount)
                assertTrue(f.runtime.requestUserStop(repair))
                assertEquals(DesktopRepairStatus.STOPPED, f.await(repair).status)
            } finally { release.countDown() }
        }
    } }

    @Test fun `selecting B during A repair cannot redirect persistence or cancel A`() = runBlocking { fixture { f ->
        val a = assertNotNull(f.chats.getSession("s"))
        f.chats.createSession(a.copy(id = "b", title = "B"))
        val b = f.chats.addMessage(ChatMessage("b-message", "b", MessageRole.ASSISTANT, "B unchanged", createdAt = 3, updatedAt = 3))
        val selection = f.container.primaryChatController
        selection.selectSession("s")
        assertEquals("s", selection.state.value.selectedSession?.id)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.response = { flow { entered.complete(Unit); release.await(); emit(StreamEvent.Delta("A fixed")); emit(StreamEvent.Done) } }
        val id = f.controller.repair("s", "m")
        withTimeout(8000) { entered.await() }
        selection.selectSession("b")
        assertEquals("b", selection.state.value.selectedSession?.id)
        assertEquals(DesktopRepairStatus.RUNNING, f.runtime.repairTasks.value[id])
        release.complete(Unit)
        assertEquals(DesktopRepairStatus.APPLIED, f.await(id).status)
        assertEquals("A fixed", f.reopened().displayContent)
        assertEquals(b, ChatRepository(JsonFileStorage(f.dataRoot)).getMessage("b-message", "b"))
        assertEquals("b", selection.state.value.selectedSession?.id)
    } }

    @Test fun `nonassistant invalid candidate missing and corrupt target never request`() = runBlocking {
        for (mode in 0..4) fixture { f ->
            when (mode) {
                0 -> f.replace(f.original.copy(role = MessageRole.USER))
                1 -> f.replace(f.original.copy(role = MessageRole.SYSTEM))
                2 -> f.replace(f.original.copy(alternativeVersionIds = listOf("duplicate", "duplicate", "last")))
                3 -> f.chats.deleteMessage("m", "s")
                4 -> Files.writeString(f.dataRoot.resolve("entities/chat_messages/s_m.json"), "{broken")
            }
            val result = f.run()
            assertTrue(result.status in setOf(DesktopRepairStatus.REJECTED, DesktopRepairStatus.CONFLICT, DesktopRepairStatus.MISSING, DesktopRepairStatus.CORRUPT))
            assertEquals(0, f.requests.get())
        }
    }

    @Test fun `request preparation renders placeholders and only adds global segmented rules`() = runBlocking { fixture { f ->
        f.response = { request ->
            assertEquals("Rule for Bot and SessionPlayer", request.formatCard)
            assertNull(request.segmentedFormat)
            assertEquals(f.original.displayContent, request.original)
            flowOf(StreamEvent.Delta(request.original), StreamEvent.Done)
        }
        assertEquals(DesktopRepairStatus.UNCHANGED, f.run().status)
        f.settings { it.copy(assistantSegmentedBubblesEnabled = true) }
        f.response = { request -> assertFalse(request.segmentedFormat.isNullOrBlank()); flowOf(StreamEvent.Delta(request.original), StreamEvent.Done) }
        assertEquals(DesktopRepairStatus.UNCHANGED, f.run().status)
    } }

    @Test fun `fault fixture commit uncertain retains attempted original cause and durable evidence without retry`() = runBlocking { fixture(faultInjection = true) { f ->
        val failure = IOException("synthetic post-write failure")
        f.response = { flow {
            emit(StreamEvent.Delta("fixed"))
            f.gate.calls.set(0)
            // Commit reads message + session, then saves message (index already cached by addMessage).
            f.gate.hook = { n, after -> if (n == 3 && after) throw failure }
            emit(StreamEvent.Done)
        } }
        val result = f.run()
        assertEquals(DesktopRepairStatus.COMMIT_UNCERTAIN, result.status)
        val evidence = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(result.commit)
        assertEquals("fixed", evidence.attempted.displayContent)
        assertEquals(MessageUpdateStage.WRITE_MESSAGE, evidence.stage)
        assertSame(failure, evidence.cause)
        assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(evidence.durableMessage)
        assertEquals(1, f.requests.get())
        f.gate.hook = { _, _ -> }
        assertEquals("fixed", f.reopened().displayContent)
    } }

    @Test fun `fault fixture cancellation at message or index write preserves actual repository outcome`() = runBlocking {
        for ((call, after, expectedStage) in listOf(
            Triple(3, false, MessageUpdateStage.WRITE_MESSAGE),
            Triple(3, true, MessageUpdateStage.WRITE_MESSAGE),
            Triple(4, false, MessageUpdateStage.WRITE_INDEX),
            Triple(4, true, MessageUpdateStage.WRITE_INDEX),
        )) fixture(faultInjection = true) { f ->
            val entered = CompletableDeferred<Unit>()
            f.response = { flow {
                emit(StreamEvent.Delta("fixed"))
                f.gate.calls.set(0)
                f.gate.hook = { n, finished ->
                    if (n == call && finished == after) { entered.complete(Unit); awaitCancellation() }
                }
                emit(StreamEvent.Done)
            } }
            val id = f.controller.repair("s", "m")
            withTimeout(8000) { entered.await() }
            assertFalse(f.runtime.requestUserStop(id)) // Commit already owns the terminal.
            f.runtime.closeAndDrain()
            val state = f.await(id)
            if (call == 3 && !after) {
                assertEquals(DesktopRepairStatus.STORAGE_FAILURE, state.status)
                val result = assertIs<ConditionalMessageUpdateResult.StorageFailure>(state.commit)
                assertEquals(expectedStage, result.stage)
                assertIs<CancellationException>(result.cause)
            } else {
                assertEquals(DesktopRepairStatus.COMMIT_UNCERTAIN, state.status)
                val result = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(state.commit)
                assertEquals(expectedStage, result.stage)
                assertIs<CancellationException>(result.cause)
                assertEquals("fixed", result.attempted.displayContent)
                assertEquals(result.attempted, assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(result.durableMessage).value)
            }
            assertEquals(state.status, f.runtime.repairTasks.value[id])
            assertEquals(call + 1, f.gate.calls.get()) // One readback; no retry/second write.
            f.gate.hook = { _, _ -> }
            assertEquals(if (call == 3 && !after) f.original.displayContent else "fixed", f.reopened().displayContent)
            assertEquals(1, f.requests.get())
        }
    }

    @Test fun `fault fixture shutdown during failure readback retains original error and durable evidence`() = runBlocking { fixture(faultInjection = true) { f ->
        val reading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val failure = IOException("synthetic original write failure")
        f.response = { flow {
            emit(StreamEvent.Delta("fixed"))
            f.gate.calls.set(0)
            f.gate.hook = { n, after ->
                if (n == 3 && after) throw failure
                if (n == 4 && !after) { reading.complete(Unit); release.await() }
            }
            emit(StreamEvent.Done)
        } }
        val id = f.controller.repair("s", "m")
        withTimeout(8000) { reading.await() }
        val closing = async(start = CoroutineStart.UNDISPATCHED) { f.runtime.closeAndDrain() }
        assertFalse(closing.isCompleted)
        assertFalse(f.runtime.requestUserStop(id))
        release.complete(Unit)
        closing.await()
        val state = f.await(id)
        assertEquals(DesktopRepairStatus.COMMIT_UNCERTAIN, state.status)
        val result = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(state.commit)
        assertEquals(MessageUpdateStage.WRITE_MESSAGE, result.stage)
        assertEquals(failure.message, result.cause.message)
        assertTrue(generateSequence<Throwable>(result.cause) { it.cause }.take(8).any { it === failure })
        assertEquals(result.attempted, assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(result.durableMessage).value)
        assertEquals(4, f.gate.calls.get())
        assertEquals(1, f.requests.get())
        f.gate.hook = { _, _ -> }
        assertEquals("fixed", f.reopened().displayContent)
    } }
}
