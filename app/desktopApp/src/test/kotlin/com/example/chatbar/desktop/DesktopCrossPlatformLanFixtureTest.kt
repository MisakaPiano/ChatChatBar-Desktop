package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.lanSync.fixture.LanCrossPlatformFixture as F
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Two explicit cloud legs. This class is intentionally outside the ordinary DesktopLanSync filter. */
class DesktopCrossPlatformLanFixtureTest {
    private fun io(phase: String): Pair<Path, String> {
        assumeTrue(System.getenv("CCB_LAN_CHAIN_PHASE") == phase)
        check(System.getenv("GITHUB_ACTIONS") == "true" && System.getProperty("os.name").startsWith("Windows"))
        val path = Path.of(requireNotNull(System.getenv("CCB_LAN_CHAIN_ROOT"))).toAbsolutePath().normalize()
        val parents = listOfNotNull(System.getenv("RUNNER_TEMP"), System.getenv("TEMP")).map { Path.of(it).toRealPath() }
        Files.createDirectories(path); require(path.toRealPath() == path && parents.any { path != it && path.startsWith(it) })
        val source = requireNotNull(System.getenv("CCB_LAN_CHAIN_SOURCE_SHA")); require(source.matches(Regex("[a-f0-9]{40}")))
        return path to source
    }
    private fun workspace(root: Path, state: Path): LanSyncWorkspace {
        val resources = DesktopCharacterResourceStore(root)
        return LanSyncWorkspace(root, state, resources::resolveOwnedReference,
            validateResource = ::validateDesktopLanSyncResource,
            resourceReference = { _, path -> desktopLanSyncResourceReference(root, path) })
    }
    private fun raster(width: Int, height: Int, color: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return try {
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, color)
            DesktopImageEditing.png(image)
        } finally { image.flush() }
    }
    private fun decode(paths: Map<Path, String>, facts: JsonObject) {
        val first = facts.getValue("firstHash").jsonPrimitive.content
        paths.forEach { (path, hash) ->
            val image = requireNotNull(ImageIO.read(path.toFile()))
            try {
                assertEquals(if (hash == first) 8 else 16, image.width); assertEquals(if (hash == first) 6 else 12, image.height)
                assertEquals(if (hash == first) 0xff3366cc.toInt() else 0xffdd8844.toInt(), image.getRGB(0, 0))
            } finally { image.flush() }
        }
    }

    @Test fun exportSyntheticWindowsSnapshot(): Unit = runBlocking {
        val (io, source) = io("EXPORT")
        val root = Files.createTempDirectory(io, "windows-source-").toRealPath()
        val state = Files.createTempDirectory(io, "windows-state-").toRealPath()
        val resources = DesktopCharacterResourceStore(root)
        val firstBytes = raster(8, 6, 0xff3366cc.toInt()); val secondBytes = raster(16, 12, 0xffdd8844.toInt())
        fun retain(bytes: ByteArray, name: String) = resources.materializeImage(PackagedImage("$name.png", Base64.getEncoder().encodeToString(bytes)), 1, name)
        val first = retain(firstBytes, "cross_a"); val second = retain(secondBytes, "cross_b")
        val processed = DesktopPostProcessResults(JsonFileStorage(root), resources, DesktopDataOperationCoordinator())
            .persist(firstBytes, secondBytes, NovelAiPostProcessTab.UPSCALE, NovelAiEnhanceOptions())
        val facts = F.seed(root, first, second, processed)
        val studioStorage = JsonFileStorage(root)
        val studio = DesktopStudioHistoryRepository(studioStorage)
        studio.loadDraft(); studio.initializeHistory()
        val encoded = NovelAiInlinePayloadStore(root.toFile()).retain(F.vibe)
        val recipe = F.fixtureRecipe(first, second, facts.getValue("firstHash").jsonPrimitive.content,
            facts.getValue("secondHash").jsonPrimitive.content, encoded)
        val previous = studio.updateDraft(resetPromptEditors = true) { it.copy(basePrompt = "电脑工作室上一步",
            negativePrompt = "合成工作室负面原文", characters = recipe.characters,
            selectedModel = NovelAiImageModel.V5_FULL, followDefaultNovelAiImageModel = false,
            v5Settings = recipe.settings, imageGuidance = recipe.imageGuidance, aiDesignModelId = F.WINDOWS_MODEL) }
        studio.updateDraft(resetPromptEditors = true) { it.copy(basePrompt = F.STUDIO_CURRENT_PROMPT) }
        studio.saveUndoDraft(previous); studio.saveGuidanceCheckpoint(recipe.imageGuidance)
        studio.prepareForLanSync()
        assertTrue(studio.historyAvailability.value.undo)
        decode(F.verify(root, facts, modified = false, absolutePaths = false, model = F.WINDOWS_MODEL), facts)
        val output = io.resolve("windows-origin.zip")
        workspace(root, state).capture("windows").use { capture ->
            assertFalse(capture.snapshot.records.single { it.type == "chat_sessions" }.body.containsKey("modelId"))
            F.write(output, source, "windows", capture, facts)
            Files.writeString(io.resolve("windows-origin-report.json"), buildJsonObject {
                put("sourceSha", source); put("platform", "Windows"); put("archiveSha256", F.fileHash(output))
                put("recordCount", capture.snapshot.records.size); put("coveredCount", capture.snapshot.coveredTypes.size)
                put("populatedTypes", JsonArray(F.populatedTypes.sorted().map(::JsonPrimitive)))
                put("emptyTypes", JsonArray((capture.snapshot.coveredTypes - F.populatedTypes).sorted().map(::JsonPrimitive)))
                put("resourceCount", capture.snapshot.resources.size); put("facts", facts)
            }.toString())
        }
        println("LAN_CHAIN_WINDOWS_EXPORTED source=$source records=11 archive=${F.fileHash(output)}")
    }

    @Test fun verifyAndroidReturnWithRealDesktopOwners(): Unit = runBlocking {
        val (io, source) = io("VERIFY")
        val originPath = Path.of(requireNotNull(System.getenv("CCB_LAN_CHAIN_ORIGIN")))
        val returnPath = Path.of(requireNotNull(System.getenv("CCB_LAN_CHAIN_RETURN")))
        val origin = F.read(originPath, Files.createTempDirectory(io, "origin-blobs-"), source, "windows")
        val returned = F.read(returnPath, Files.createTempDirectory(io, "returned-blobs-"), source, "android")
        assertEquals(F.fileHash(originPath), returned.contract.getValue("parentArchiveSha256").jsonPrimitive.content)
        val facts = origin.contract.getValue("facts").jsonObject
        assertEquals(facts, returned.contract.getValue("facts"))
        val originalResult = F.json.decodeFromJsonElement(NovelAiPostProcessResultRecord.serializer(), facts.getValue("processed"))
        for (fresh in listOf(false, true)) {
            val root = Files.createTempDirectory(io, if (fresh) "windows-fresh-" else "windows-existing-").toRealPath()
            val state = Files.createTempDirectory(io, "return-state-").toRealPath(); val workspace = workspace(root, state)
            val studioStorage = JsonFileStorage(root); val studio = DesktopStudioHistoryRepository(studioStorage)
            if (!fresh) {
                workspace.apply(LanSyncPlan(UUID.randomUUID().toString(), origin.snapshot, emptyMap()), emptyMap(), "windows", origin.blobs)
                val chats = ChatRepository(JsonFileStorage(root))
                chats.updateSession(requireNotNull(chats.getSession(F.SESSION)).copy(modelId = F.WINDOWS_MODEL))
                studio.loadDraft(); studio.initializeHistory()
                studio.updateDraft { it.copy(aiDesignModelId = F.WINDOWS_MODEL) }
            } else {
                studio.loadDraft() // The existing cache must refresh without materializing a default file.
            }
            studio.prepareForLanSync()
            workspace.capture("windows").use { before ->
                val plan = if (fresh) LanSyncPlan(UUID.randomUUID().toString(), returned.snapshot, emptyMap()) else {
                    assertEquals(origin.snapshot.hashes(), before.snapshot.hashes())
                    val components = origin.snapshot.units().filterValues { rows -> rows.any { it.type in setOf("moment_posts", "moment_tasks") } }
                        .mapValues { (_, rows) -> rows.map { it.key }.toSet() }
                    LanSyncPlanner.plan(before.snapshot, returned.snapshot, LanSyncBaseline("android", "synthetic-source-completed",
                        origin.snapshot.hashes(), ownerId = "windows", coveredTypes = origin.snapshot.coveredTypes, componentRecords = components),
                        emptyMap(), UUID.randomUUID().toString())
                }
                workspace.apply(plan, before.snapshot.hashes(), "windows", origin.blobs + returned.blobs)
                studio.reloadAfterLanSync()
            }
            assertEquals(F.STUDIO_ANDROID_EDIT, studio.draft.value?.extraPrompt)
            assertEquals(if (fresh) null else F.WINDOWS_MODEL, studio.draft.value?.aiDesignModelId)
            assertTrue(studio.replay(false)); assertEquals("", studio.loadDraft().extraPrompt)
            assertTrue(studio.replay(true)); assertEquals(F.STUDIO_ANDROID_EDIT, studio.loadDraft().extraPrompt)
            val paths = F.verify(root, facts, modified = true, absolutePaths = false, model = if (fresh) null else F.WINDOWS_MODEL)
            decode(paths, facts)
            val owner = DesktopPostProcessResults(JsonFileStorage(root), DesktopCharacterResourceStore(root), DesktopDataOperationCoordinator())
            owner.load(); val paid = owner.records.value.single()
            assertEquals(originalResult.id, paid.id); paid.validateForImport()
            assertEquals(facts.getValue("secondHash").jsonPrimitive.content, lanSyncHash(owner.read(paid)))
            assertTrue(paid.imagePath.startsWith("images/"))
            workspace.capture("windows").use { after -> assertEquals(returned.snapshot.hashes(), after.snapshot.hashes()) }
        }
        Files.writeString(io.resolve("windows-return-report.json"), buildJsonObject {
            put("sourceSha", source); put("originArchiveSha256", F.fileHash(originPath)); put("returnArchiveSha256", F.fileHash(returnPath))
            put("existingReceiver", "passed"); put("freshReceiver", "passed"); put("postProcessOwnerRead", "passed")
            put("desktopStudioOwnerReloadUndoRedo", "passed"); put("localStudioModelRetained", "passed")
            put("recordCount", returned.snapshot.records.size); put("coveredCount", returned.snapshot.coveredTypes.size)
            put("populatedTypes", JsonArray(F.populatedTypes.sorted().map(::JsonPrimitive)))
            put("emptyTypes", JsonArray((returned.snapshot.coveredTypes - F.populatedTypes).sorted().map(::JsonPrimitive)))
        }.toString())
        println("LAN_CHAIN_WINDOWS_RETURN_VERIFIED source=$source receivers=2 records=11")
    }
}
