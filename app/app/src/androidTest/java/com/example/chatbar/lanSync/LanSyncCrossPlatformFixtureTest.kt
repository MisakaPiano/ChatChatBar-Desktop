package com.example.chatbar.lanSync

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.BuildConfig
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.lanSync.fixture.LanCrossPlatformFixture as F
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The middle cloud-only artifact leg. No sockets, provider calls, identities or credential transport. */
class LanSyncCrossPlatformFixtureTest {
    @Test fun importEditAndReturnWindowsSyntheticData(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("lanSyncCrossPlatformProof") == "true")
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("lanPreview", BuildConfig.BUILD_TYPE)
        assertTrue(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        val source = requireNotNull(args.getString("lanSyncChainSourceSha")); require(source.matches(Regex("[a-f0-9]{40}")))
        val exchange = app.filesDir.canonicalFile.toPath().resolve("lan-cross-platform-exchange")
        val input = exchange.resolve("input.zip"); require(Files.isRegularFile(input))
        require(!Files.exists(exchange.resolve("return.zip"))) { "Prior exchange evidence must not be overwritten" }
        val root = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "lan-cross-platform-data-").toRealPath()
        val state = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "lan-cross-platform-state-").toRealPath()
        val packet = F.read(input, Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "lan-cross-platform-blobs-"), source, "windows")
        val facts = packet.contract.getValue("facts").jsonObject
        val gate = LanSyncOperationGate(); val storage = JsonFileStorage(root, gate)
        val characters = CharacterRepository(storage); characters.initialize()
        val chats = ChatRepository(storage); chats.getAllSessions()
        val moments = MomentRepository(storage); moments.initialize()
        val settings = SettingsRepository(storage); settings.refreshPlayerSettingFromStorage()
        val context = object : android.content.ContextWrapper(app) { override fun getFilesDir() = root.toFile() }
        val studio = NovelAiStudioRepository(storage, NovelAiImageStorage(context))
        studio.initialize(); studio.loadDraft()
        val workspace = LanSyncWorkspace(root, state, { reference -> androidLanSyncResourcePath(root, root, reference) },
            validateResource = ::validateAndroidLanSyncResource)
        gate.exclusive {
            studio.prepareForLanSync()
            workspace.capture("android").use { before ->
                assertTrue(before.snapshot.records.isEmpty())
                workspace.apply(LanSyncPlan(UUID.randomUUID().toString(), packet.snapshot, emptyMap()), before.snapshot.hashes(), "android", packet.blobs)
            }
            characters.refreshFromStorage(); chats.reloadAfterLanSync(); moments.refreshFromStorage()
            settings.refreshPlayerSettingFromStorage(); studio.initialize(); studio.reloadAfterLanSync()
        }
        assertEquals("电脑合成角色", characters.getAll().single().name)
        assertEquals(F.SESSION, chats.sessions.first().single().id)
        assertEquals(F.POST, moments.getAllPosts().single().id)
        assertEquals("电脑合成玩家", settings.playerSetting.first().playerName)
        assertEquals(F.HISTORY, studio.history.first().single().id)
        assertEquals(F.STUDIO_CURRENT_PROMPT, studio.draft.value?.basePrompt)
        assertNull(studio.draft.value?.aiDesignModelId)
        gate.exclusive { workspace.capture("android").use { imported ->
            assertEquals(packet.snapshot.hashes(), imported.snapshot.hashes())
        } }
        fun decode(paths: Map<java.nio.file.Path, String>) {
            val firstHash = facts.getValue("firstHash").jsonPrimitive.content
            paths.forEach { (path, hash) ->
                val bitmap = requireNotNull(BitmapFactory.decodeFile(path.toString()))
                try {
                    assertEquals(if (hash == firstHash) 8 else 16, bitmap.width)
                    assertEquals(if (hash == firstHash) 6 else 12, bitmap.height)
                    assertEquals(if (hash == firstHash) 0xff3366cc.toInt() else 0xffdd8844.toInt(), bitmap.getPixel(0, 0))
                } finally { bitmap.recycle() }
            }
        }
        decode(F.verify(root, facts, modified = false, absolutePaths = true, model = null))
        F.editOnAndroid(root, studio)
        val currentFile = root.resolve("entities/${F.STUDIO_DRAFT}.json")
        val beforeStaging = Files.readAllBytes(currentFile)
        studio.stageDraft { it.copy(extraPrompt = F.STUDIO_ANDROID_EDIT, aiDesignModelId = F.ANDROID_MODEL) }
        assertArrayEquals(beforeStaging, Files.readAllBytes(currentFile))
        gate.exclusive { studio.prepareForLanSync() }
        val reopened = NovelAiStudioRepository(JsonFileStorage(root, gate), NovelAiImageStorage(context))
        assertEquals(F.STUDIO_ANDROID_EDIT, reopened.loadDraft().extraPrompt)
        assertEquals(F.ANDROID_MODEL, reopened.draft.value?.aiDesignModelId)
        assertEquals(4, reopened.loadEditorTimelineForLanSync()?.entries?.size)
        decode(F.verify(root, facts, modified = true, absolutePaths = true, model = F.ANDROID_MODEL))
        val output = exchange.resolve("return.zip")
        gate.exclusive { workspace.capture("android").use { capture ->
            assertFalse(capture.snapshot.records.single { it.type == "chat_sessions" }.body.containsKey("modelId"))
            F.write(output, source, "android", capture, facts, F.fileHash(input))
            val report = buildJsonObject {
                put("sourceSha", source); put("platform", "Android"); put("package", app.packageName)
                put("originArchiveSha256", F.fileHash(input)); put("returnArchiveSha256", F.fileHash(output))
                put("coveredCount", capture.snapshot.coveredTypes.size); put("recordCount", capture.snapshot.records.size)
                put("populatedTypes", JsonArray(F.populatedTypes.sorted().map(::JsonPrimitive)))
                put("emptyTypes", JsonArray((capture.snapshot.coveredTypes - F.populatedTypes).sorted().map(::JsonPrimitive)))
                put("applicationPrivateAbsoluteReferences", "passed"); put("nativeBitmapDecode", "passed")
                put("actualRepositoryReloadAndMutation", "passed"); put("largeVibeCustody", "passed")
                put("actualAndroidStudioSubclassLoadReloadStagePrepareReopen", "passed")
                put("selectedStudioTimelineRecords", 4)
                put("postProcess", "retention only; no Android post-process viewer claim")
            }
            Files.write(exchange.resolve("android-report.json"), report.toString().toByteArray(Charsets.UTF_8))
        } }
        println("LAN_CHAIN_ANDROID_RETURNED source=$source records=11 archive=${F.fileHash(output)}")
    }
}
