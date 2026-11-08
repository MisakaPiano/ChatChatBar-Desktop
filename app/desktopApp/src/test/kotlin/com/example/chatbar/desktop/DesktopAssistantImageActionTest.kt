package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.domain.model.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopAssistantImageActionTest {
    @Test fun `chat image design omits disabled person references without changing original card`() = runBlocking {
        val f = Fixture()
        try {
            f.initialize()
            val first = CharacterInfo.create("ActiveReference_X").copy(imagePrompt = "active_reference_tag_x")
            val second = CharacterInfo.create("InactiveReference_Y").copy(imagePrompt = "inactive_reference_tag_y")
            val card = f.c.characterRepository.getById("card")!!.copy(characters = listOf(first, second))
            f.c.characterRepository.save(card)
            val session = f.c.chatRepository.getSession("session")!!
            f.c.chatRepository.saveSessionSettingsDraft(session,
                com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.change(session, card, second.id, false))
            success(f)
            val contents = f.design.requests.flatten().joinToString("\n") { it.content.toString() }
            assertTrue("active_reference_tag_x" in contents)
            assertFalse("inactive_reference_tag_y" in contents)
            assertEquals(card, f.c.characterRepository.getById(card.id))
        } finally { f.close() }
    }

    internal class Fixture(imageClient: OkHttpClient? = null) {
        val design = FinalProductDesignFixture()
        val c = design.container
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val authorizations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val model = ModelConfig("image-design", "Fixture", "https://fixture.invalid", "fake-key", "fixture", createdAt = 1)
        val resolver = EffectiveModelResolver(c.modelRepository, c.settingsRepository, object : PresetModelCatalogSource {
            override val catalog = PresetModelCatalog()
            override val modelCatalogVersion: Int? = null
        })
        val client = imageClient ?: OkHttpClient.Builder().addInterceptor { chain ->
            authorizations += chain.request().header("Authorization").orEmpty()
            requests += okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val png = DesktopImageEditing.png(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB))
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(2); packer.packString("event_type"); packer.packString("final")
            packer.packString("image"); packer.packBinaryHeader(png.size); packer.writePayload(png)
            packer.close(); val payload = packer.toByteArray()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ByteBuffer.allocate(payload.size + 4).putInt(payload.size).put(payload).array().toResponseBody()).build()
        }.build()
        val service = DesktopChatImageRegeneration(c.chatRepository, c.characterRepository, c.settingsRepository,
            c.characterResourceStore, c.dataOperationCoordinator, c.desktopSecretStore, c.taskRuntime,
            c.novelAiInfrastructure, resolver, client)
        lateinit var source: ChatMessage
        suspend fun initialize() {
            c.modelRepository.saveModel(model)
            c.desktopSecretStore.save(DesktopCredentialKey.NovelAiToken, "synthetic-fixture-token")
            c.characterRepository.save(CharacterCard.create("Adult traveler").copy(id = "card"))
            c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "Fixture",
                createdAt = 1, updatedAt = 1, imageModelId = model.id, imagePromptPreference = "saved-preference-fixture"))
            c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.USER, "A reading scene"))
            source = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "The adult traveler reads by the window."))
        }
        // This checks resolution/persistence, not a ten-second performance contract.
        suspend fun terminal(id: String) = withTimeout(30_000) {
            c.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }.single { it.taskId == id }
        }
        suspend fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); design.close() }
    }
    private suspend fun fixture(work: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.initialize(); work(f) } finally { f.close() }
    }
    @Test fun `queued assistant design and old image regeneration freeze their own inputs credentials and result anchors`() = runBlocking {
        val f = Fixture(); val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        try {
            f.initialize()
            assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(f.service.generateFromAssistant(f.source)).status)
            val oldImage = f.c.chatRepository.getMessages("session").single { it.images.isNotEmpty() }
            val source = f.c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "Another original source"))
            val blocker = f.c.taskRuntime.launchNovelAi("held", "session", "foreign") { entered.complete(Unit); release.await() }
            withTimeout(5000) { entered.await() }
            val assistant = f.service.generateFromAssistant(source, DesktopChatImageRequirements("snapshot hint", "snapshot preference", false))
            val metadata = oldImage.generatedImageMetadata.single()
            val draft = metadata.toRegenerationDraft().copy(baseCaption = "frozen_old_image_prompt")
            val launch = NovelAiGenerationSettings(customWidth = draft.width, customHeight = draft.height,
                seedMode = NovelAiSeedMode.FIXED, seed = 1234, steps = 20)
            val regeneration = f.service.generate(oldImage, draft, launch)
            assertNull(f.c.taskRuntime.tasks.value.single { it.taskId == assistant }.startedAt)
            assertNull(f.c.taskRuntime.tasks.value.single { it.taskId == regeneration }.startedAt)
            f.c.desktopSecretStore.save(DesktopCredentialKey.NovelAiToken, "synthetic-later-token")
            val session = f.c.chatRepository.getSession("session")!!
            f.c.chatRepository.saveSessionSettingsDraft(session, session.copy(imagePromptPreference = "later preference"))
            assertEquals(1, f.requests.size)
            release.complete(Unit); f.terminal(blocker)
            assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(assistant).status)
            assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(regeneration).status)
            assertEquals(3, f.requests.size)
            assertTrue(f.authorizations.all { it == "Bearer synthetic-fixture-token" })
            assertTrue(f.design.requests.flatten().any { "snapshot hint" in it.content.toString() && "snapshot preference" in it.content.toString() })
            val reissued = f.requests.single { "frozen_old_image_prompt" in it }
            assertTrue("1234" in reissued)
            val saved = f.c.chatRepository.getMessages("session")
            assertEquals(1, saved.count { it.generatedFromMessageId == source.id })
            assertEquals(1, saved.count { it.generatedFromMessageId == oldImage.id })
            assertEquals(oldImage, saved.single { it.id == oldImage.id })
            assertEquals(source, saved.single { it.id == source.id })
        } finally { release.complete(Unit); f.close() }
    }
    @Test fun `actual temporary chat image runtime records original HTTP attempt in its real session and usage owner`() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val png = DesktopImageEditing.png(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB))
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(2); packer.packString("event_type"); packer.packString("final")
            packer.packString("image"); packer.packBinaryHeader(png.size); packer.writePayload(png); packer.close()
            val frame = packer.toByteArray()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(ByteBuffer.allocate(frame.size + 4).putInt(frame.size).put(frame).array())))
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val f = Fixture(client)
            try {
                f.initialize()
                val id = f.service.generateFromAssistant(f.source)
                assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(id).status)
                val log = f.c.taskRuntime.diagnostics.entries.value.single { it.image != null }
                assertEquals("session", log.sessionId); assertEquals(200, log.image!!.httpStatus)
                assertEquals(1L, log.image!!.outputImages); assertEquals(1, server.requestCount)
                withTimeout(5000) {
                    do { delay(20); f.c.providerUsage.refresh(DesktopUsageQuery(mediaKind = DesktopUsageMediaKind.NAI_IMAGE_GENERATION)) }
                    while (f.c.providerUsage.state.value.recent.none { it.outcome == DesktopUsageOutcome.COMPLETED })
                }
                val row = f.c.providerUsage.state.value.recent.single()
                assertEquals(id, row.taskId); assertEquals("session", row.sessionId); assertEquals(f.source.id, row.subjectId)
                assertEquals(DesktopUsageSource.CHAT, row.source); assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome)
                assertEquals(1L, row.media!!.outputImages)
                assertEquals(f.source, f.c.chatRepository.getMessage(f.source.id, "session"))
            } finally { f.close() }
        }
    }
    private suspend fun success(f: Fixture, requirements: DesktopChatImageRequirements? = null) {
        assertEquals(DesktopChatImagePreflight.READY, f.service.preflight(f.source))
        val task = f.terminal(f.service.generateFromAssistant(f.source, requirements))
        assertEquals(DesktopTaskStatus.COMPLETED, task.status, task.message)
        assertEquals(f.source.id, task.targetMessageId)
        assertEquals(1, f.requests.size)
        assertTrue(f.design.requests.isNotEmpty())
        val durable = ChatRepository(JsonFileStorage(f.c.appDataRoot)).getMessages("session")
        val generated = durable.single { it.generatedFromMessageId == f.source.id }
        assertEquals(MessageRole.ASSISTANT, generated.role)
        assertEquals(durable.indexOfFirst { it.id == f.source.id } + 1, durable.indexOf(generated))
        assertEquals(generated.images, generated.generatedImageMetadata.map { it.imagePath })
        assertTrue(Files.isRegularFile(f.c.appDataRoot.resolve(generated.images.single())))
        assertEquals(f.source, f.c.chatRepository.getMessage(f.source.id, "session"))
    }
    @Test fun `direct action completes real persistence with fake transports and existing preference`() = runBlocking { fixture { f ->
        success(f)
        assertTrue(f.design.requests.flatten().any { it.content.toString().contains("saved-preference-fixture") })
    } }
    @Test fun `requirements reaches shared designer and persists only preference`() = runBlocking { fixture { f ->
        success(f, DesktopChatImageRequirements("one-shot-hint-fixture", "edited-preference-fixture"))
        val messages = f.design.requests.flatten()
        assertTrue(messages.any { it.content.toString().contains("one-shot-hint-fixture") && it.content.toString().contains("edited-preference-fixture") })
        val session = f.c.chatRepository.getSession("session")!!
        assertEquals("edited-preference-fixture", session.imagePromptPreference)
        assertFalse(session.toString().contains("one-shot-hint-fixture"))
    } }
    @Test fun `per-run requirements can leave session preference unchanged`() = runBlocking { fixture { f ->
        success(f, DesktopChatImageRequirements("hint", "transient-preference-fixture", persistPreference = false))
        assertEquals("saved-preference-fixture", f.c.chatRepository.getSession("session")!!.imagePromptPreference)
        assertTrue(f.design.requests.flatten().any { it.content.toString().contains("transient-preference-fixture") })
    } }
    @Test fun `missing explicit model uses existing resolver fallback`() = runBlocking { fixture { f ->
        val session = f.c.chatRepository.getSession("session")!!
        f.c.chatRepository.saveSessionSettingsDraft(session, session.copy(imageModelId = "deleted-model"))
        f.c.settingsRepository.updateAppSettings { it.copy(defaultImageModelId = f.model.id) }
        success(f)
    } }
    @Test fun `deterministic preflight failures admit no task or transport and do not save preference`() = runBlocking {
        for (kind in listOf("token", "character", "model", "url", "auth", "ratio", "edited", "deleted", "session", "user", "blank")) fixture { f ->
            val expected = when (kind) {
                "token" -> { f.c.desktopSecretStore.delete(DesktopCredentialKey.NovelAiToken); DesktopChatImagePreflight.CREDENTIAL }
                "character" -> { f.c.characterRepository.delete("card"); DesktopChatImagePreflight.CHARACTER }
                "model" -> { f.c.modelRepository.deleteModel(f.model.id); DesktopChatImagePreflight.MODEL }
                "url" -> { f.c.modelRepository.saveModel(f.model.copy(baseUrl = "bad-url")); DesktopChatImagePreflight.MODEL }
                "auth" -> { f.c.modelRepository.saveModel(f.model.copy(apiKey = "")); DesktopChatImagePreflight.AUTH }
                "ratio" -> { f.c.settingsRepository.updateAppSettings { it.copy(novelAiImageAspectRatio = "invalid") }; DesktopChatImagePreflight.RATIO }
                "edited" -> { f.c.chatRepository.updateMessage(f.source.copy(content = "changed")); DesktopChatImagePreflight.SOURCE }
                "deleted" -> { f.c.chatRepository.deleteMessage(f.source.id, "session"); DesktopChatImagePreflight.SOURCE }
                "session" -> { f.c.chatRepository.deleteSessionRecord("session"); DesktopChatImagePreflight.SESSION }
                "user" -> { f.source = f.c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.USER, "user")); DesktopChatImagePreflight.SOURCE }
                else -> { f.source = f.c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, " ")); DesktopChatImagePreflight.SOURCE }
            }
            val before = f.c.chatRepository.getSession("session")
            assertEquals(expected, f.service.preflight(f.source), kind)
            val error = assertFailsWith<DesktopChatImagePreflightException>(kind) {
                f.service.generateFromAssistant(f.source, DesktopChatImageRequirements("hint", "must-not-save"))
            }
            assertEquals(expected, error.result, kind)
            assertEquals(before, f.c.chatRepository.getSession("session"), kind)
            assertTrue(f.c.taskRuntime.tasks.value.isEmpty(), kind)
            assertTrue(f.design.requests.isEmpty(), kind); assertTrue(f.requests.isEmpty(), kind)
        }
    }
    @Test fun `footer visibility excludes user system blank streaming archive and stale states`() = runBlocking { fixture { f ->
        val state = DesktopPrimaryChatState(selectedSession = f.c.chatRepository.getSession("session"), messages = listOf(f.source))
        assertTrue(desktopAssistantImageActionVisible(f.source, state, true))
        assertFalse(desktopAssistantImageActionVisible(f.source, state, false))
        assertFalse(desktopAssistantImageActionVisible(f.source, state.copy(selectedSession = null), true))
        assertFalse(desktopAssistantImageActionVisible(f.source, state.copy(messages = emptyList()), true))
        for (message in listOf(f.source.copy(role = MessageRole.USER), f.source.copy(role = MessageRole.SYSTEM), f.source.copy(content = ""))) {
            assertFalse(desktopAssistantImageActionVisible(message, state.copy(messages = listOf(message)), true))
        }
    } }
}
