package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopVibePayloadRuntimeTest {
    private class WatchingSecrets : DesktopSecretStore by InMemoryDesktopSecretStore() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        override fun load(key: DesktopCredentialKey): String? { reads.incrementAndGet(); return null }
    }
    private fun draft(value: String) = NovelAiStudioDraft(selectedModel = NovelAiImageModel.V4_5_FULL,
        followDefaultNovelAiImageModel = false, imageGuidance = NovelAiImageGuidanceDraft(
            referenceMode = NovelAiReferenceMode.VIBE, vibes = listOf(NovelAiVibeReferenceDraft(encodedVibe = value))))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `authenticated imported file reference resolves into actual request without another encode call`() = runBlocking {
        val base = Files.createTempDirectory("vibe-import-wire-")
        try {
            val value = "AQID".repeat(2048); val bytes = value.toByteArray(); val digest = hash(bytes)
            val reference = NovelAiInlinePayloadStore.PREFIX + digest
            val originalDraft = backupJson.encodeToString(NovelAiStudioDraft.serializer(), draft(reference)).toByteArray()
            val archive = CensusWireFixtures.bytes(prefs = "{}", files = mapOf(
                "entities/novelai_studio_draft.json" to originalDraft, "novelai-vibe-payloads/$digest" to bytes), encrypted = true,
                version = 2, code = 86, scope = OfficialWireScope(emptySet(), 0, 0, 0, 0, 0,
                    studioSettings = true, studioHistory = false, studioHistoryImages = false, moments = false))
            CensusWireFixtures.input(archive, "vibe-import-wire") { source ->
                val stagingRoot = Files.createDirectory(base.resolve("staging"))
                DesktopFullBackupStaging.prepare(source, CensusWireFixtures.PASSWORD.toCharArray(), stagingRoot).use { staged ->
                    val runtime = Files.createDirectory(base.resolve("runtime")); val work = Files.createDirectory(base.resolve("work"))
                    DesktopFullBackupMapping(runtime).restoreMerged(staged.files, work, BackupPreferences()).use { mapped ->
                        mapped.files.forEach { (relative, file) ->
                            val target = runtime.resolve(relative); Files.createDirectories(target.parent); Files.copy(file, target)
                        }
                        val restored = backupJson.decodeFromString(NovelAiStudioDraft.serializer(),
                            Files.readString(runtime.resolve("entities/novelai_studio_draft.json")))
                        assertEquals(reference, restored.imageGuidance.vibes.single().encodedVibe)
                        val guidance = DesktopNovelAiGuidance(runtime, DesktopCharacterResourceStore(runtime),
                            InMemoryDesktopSecretStore(), DesktopDataOperationCoordinator())
                        assertEquals(0, guidance.vibeCacheMisses(restored))
                        // No NovelAI credential is configured: an accidental encode attempt cannot succeed.
                        val prepared = guidance.prepare(restored)
                        assertEquals(value, prepared.guidance.vibes.single().encoding)
                        assertEquals(reference, prepared.retainedGuidance.vibes.single().encodedVibe)
                        MockWebServer().use { server ->
                            server.enqueue(MockResponse().setResponseCode(400).setBody("synthetic final request rejection"))
                            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                            }.build()
                            NovelAiImageService(client, safeErrorsOnly = true).generate("synthetic-key", NovelAiPromptPlan("fixture", emptyList()),
                                prepared.requestSize, restored.activeSettings, prepared.guidance, maxRateLimitRetries = 0).toList()
                            val sent = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                            val wire = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                            val encoded = wire.getValue("parameters").jsonObject.getValue("reference_image_multiple").jsonArray.single().jsonPrimitive.content
                            assertEquals(value, encoded); assertFalse(encoded.startsWith(NovelAiInlinePayloadStore.PREFIX))
                            assertEquals(1, server.requestCount)
                            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                        }
                        assertContentEquals(bytes, Files.readAllBytes(runtime.resolve("novelai-vibe-payloads/$digest")))
                        assertContentEquals(originalDraft, Files.readAllBytes(staged.files.getValue("entities/novelai_studio_draft.json")))
                    }
                }
            }
        } finally { base.toFile().deleteRecursively() }
    }

    @Test fun `legacy inline and explicit bad file references never silently request paid encoding`() = runBlocking {
        val root = Files.createTempDirectory("vibe-prepare-")
        try {
            val secrets = WatchingSecrets()
            val owner = DesktopNovelAiGuidance(root, DesktopCharacterResourceStore(root), secrets, DesktopDataOperationCoordinator())
            val prepared = owner.prepare(draft("AQID"))
            assertEquals("AQID", prepared.guidance.vibes.single().encoding)
            assertEquals("AQID", prepared.retainedGuidance.vibes.single().encodedVibe)
            for (ref in listOf("chatbar-vibe-file:../outside", "chatbar-vibe-file:" + "0".repeat(64))) {
                assertFails { owner.prepare(draft(ref)) }
            }
            assertFalse(Files.exists(root.resolve("novelai-vibe-payloads")))
            val longValue = "AQID".repeat(1024)
            val retained = owner.prepare(draft(longValue))
            assertEquals(longValue, retained.guidance.vibes.single().encoding)
            val reference = assertNotNull(retained.retainedGuidance.vibes.single().encodedVibe)
            assertTrue(reference.startsWith(NovelAiInlinePayloadStore.PREFIX))
            val file = root.resolve("novelai-vibe-payloads").resolve(reference.substringAfter(':'))
            Files.writeString(file, "altered")
            assertFails { owner.prepare(draft(reference)) }
            assertEquals("altered", Files.readString(file))
            assertEquals(0, secrets.reads.get(), "Existing or invalid encoded payloads must not request an encode credential")
        } finally { root.toFile().deleteRecursively() }
    }
}
