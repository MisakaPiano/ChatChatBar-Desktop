package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncStudioHistoryTest {
    private fun root() = Files.createTempDirectory("lan-studio-history-").toRealPath()
    private fun workspace(root: Path, state: Path) = LanSyncWorkspace(root, state, { raw ->
        Path.of(raw).let { if (it.isAbsolute) it else root.resolve(it) }
    }, validateResource = { kind, path, _ -> if (kind == "images") {
        val image = javax.imageio.ImageIO.read(path.toFile()); require(image != null); image.flush()
    } })
    private fun write(root: Path, entry: NovelAiGenerationHistoryEntry): Path {
        val path = root.resolve("entities/novelai_generation_history/${entry.id}.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, lanSyncJson.encodeToJsonElement(NovelAiGenerationHistoryEntry.serializer(), entry).toString())
        return path
    }
    @Test fun historyRecipeAllImagesGuidanceAndSmallOrReferencedVibesRoundTripWithoutChangingPromptOrDormantSettings() = runBlocking {
        val a = root(); val b = root(); val state = root()
        val source = NovelAiStudioStateRepository(JsonFileStorage(a)); val target = NovelAiStudioStateRepository(JsonFileStorage(b))
        val payloads = listOf(LanSyncPreviewSample.image(), LanSyncPreviewSample.image(warm = true))
        val paths = payloads.mapIndexed { index, bytes -> a.resolve("images/image-$index.png").also {
            Files.createDirectories(it.parent); Files.write(it, bytes)
        }.toString() }
        val assets = paths.mapIndexed { index, path -> NovelAiStudioAssetRef("asset-$index", path, lanSyncHash(payloads[index]), 32, 32) }
        val small = com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX + "synthetic-opaque-vibe"
        val large = "V".repeat(NovelAiInlinePayloadStore.INLINE_LIMIT + 321)
        val largeRef = NovelAiInlinePayloadStore(a.toFile()).retain(large)
        assertTrue(largeRef.startsWith(NovelAiInlinePayloadStore.PREFIX))
        val recipe = NovelAiGenerationRecipe(basePrompt = "原文\n{\"path\":\"不要改写正文\"}", negativePrompt = "合成负面",
            characters = listOf(NovelAiCharacterPromptDraft("scoped-role", "合成人物标签", "合成人物负面")),
            settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, sampler = NovelAiSampler.DDIM,
                effort = NovelAiEffort.HIGH, transparentBackground = true, varietyPlus = true, varietyPlusSigma = 0.75,
                seedMode = NovelAiSeedMode.FIXED, seed = 42),
            imageGuidance = NovelAiImageGuidanceDraft(baseImage = assets.first(), maskImage = assets.last(),
                preciseReference = NovelAiPreciseReferenceDraft(assets.first()), referenceMode = NovelAiReferenceMode.VIBE,
                vibes = listOf(NovelAiVibeReferenceDraft("vibe-small", assets.last(), small), NovelAiVibeReferenceDraft("vibe-large", encodedVibe = largeRef))))
        val entry = NovelAiGenerationHistoryEntry("history", paths.mapIndexed { index, path -> NovelAiGenerationHistoryImage(path, 42L + index) }, recipe, createdAt = 1)
        source.saveHistory(entry)
        val unrelated = b.resolve("entities/local_unrelated_feature.json"); Files.createDirectories(unrelated.parent)
        Files.writeString(unrelated, "synthetic local state must remain")
        val left = workspace(a, root()); val right = workspace(b, state)
        left.capture("computer").use { capture -> right.capture("phone").use { before ->
            assertEquals((payloads.map(::lanSyncHash) + listOf(lanSyncHash(small.toByteArray()), lanSyncHash(large.toByteArray()))).toSet(), capture.snapshot.resources.keys)
            assertFalse(capture.snapshot.records.single().body.toString().contains(NovelAiInlinePayloadStore.PREFIX))
            val plan = LanSyncPlan(UUID.randomUUID().toString(), capture.snapshot, emptyMap())
            right.apply(plan, before.snapshot.hashes(), "phone", capture.blobs)
            target.initialize()
            val imported = target.history.first().single()
            assertEquals(entry.recipe.basePrompt, imported.recipe.basePrompt)
            assertEquals(entry.recipe.negativePrompt, imported.recipe.negativePrompt)
            assertEquals(entry.recipe.characters, imported.recipe.characters)
            assertEquals(entry.recipe.settings, imported.recipe.settings)
            assertEquals(listOf(42L, 43L), imported.images.map { it.seed })
            imported.images.forEachIndexed { index, image -> assertContentEquals(payloads[index], Files.readAllBytes(Path.of(image.path))) }
            assertEquals(imported.images.first().path, imported.recipe.imageGuidance.baseImage?.path)
            assertEquals(imported.images.last().path, imported.recipe.imageGuidance.maskImage?.path)
            assertEquals(imported.images.first().path, imported.recipe.imageGuidance.preciseReference.asset?.path)
            val retained = NovelAiInlinePayloadStore(b.toFile())
            assertEquals(small, retained.resolve(requireNotNull(imported.recipe.imageGuidance.vibes[0].encodedVibe)))
            assertEquals(large, retained.resolve(requireNotNull(imported.recipe.imageGuidance.vibes[1].encodedVibe)))
            val raw = b.resolve("entities/novelai_generation_history/${entry.id}.json")
            val bytes = Files.readAllBytes(raw); val fileKey = Files.readAttributes(raw, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
            val reopened = workspace(b, state); reopened.recover()
            reopened.capture("phone").use { next ->
                assertEquals(capture.snapshot.hashes(), next.snapshot.hashes())
                reopened.apply(plan.copy(transaction = UUID.randomUUID().toString()), next.snapshot.hashes(), "phone", next.blobs)
            }
            assertContentEquals(bytes, Files.readAllBytes(raw))
            assertEquals(fileKey, Files.readAttributes(raw, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            assertEquals("synthetic local state must remain", Files.readString(unrelated))
        } }
    }

    @Test fun missingOrMalformedUtf8VibeEvidenceRefusesWithoutChangingSourceOrAuthorizingPublication() {
        for (missing in listOf(true, false)) {
            val data = root(); val state = root(); val invalid = byteArrayOf(0xc3.toByte(), 0x28)
            val hash = if (missing) "f".repeat(64) else lanSyncHash(invalid)
            val entry = NovelAiGenerationHistoryEntry("history", recipe = NovelAiGenerationRecipe(negativePrompt = "合成",
                imageGuidance = NovelAiImageGuidanceDraft(vibes = listOf(NovelAiVibeReferenceDraft("vibe", encodedVibe = NovelAiInlinePayloadStore.PREFIX + hash)))), createdAt = 1)
            val file = write(data, entry); val original = Files.readAllBytes(file)
            val payload = data.resolve("novelai-vibe-payloads/$hash")
            if (!missing) { Files.createDirectories(payload.parent); Files.write(payload, invalid) }
            assertEquals("SYNC_VIBE_PAYLOAD_INVALID", assertFailsWith<IllegalArgumentException> { workspace(data, state).capture("synthetic") }.message)
            assertContentEquals(original, Files.readAllBytes(file))
            if (!missing) assertContentEquals(invalid, Files.readAllBytes(payload))
        }
        val data = root(); val state = root(); val ws = workspace(data, state)
        val nested = (NovelAiInlinePayloadStore.PREFIX + "f".repeat(64)).toByteArray()
        val blob = state.resolve("nested"); Files.write(blob, nested); val hash = lanSyncHash(nested)
        val entry = NovelAiGenerationHistoryEntry("history", recipe = NovelAiGenerationRecipe(negativePrompt = "合成",
            imageGuidance = NovelAiImageGuidanceDraft(vibes = listOf(NovelAiVibeReferenceDraft("vibe",
                encodedVibe = "ccb-lan-v1/payloads/${UUID.randomUUID()}/$hash/utf8")))), createdAt = 1)
        val record = LanSyncRecord("novelai_generation_history", entry.id, lanSyncJson.encodeToJsonElement(NovelAiGenerationHistoryEntry.serializer(), entry).jsonObject)
        val plan = LanSyncPlan(UUID.randomUUID().toString(), LanSyncSnapshot(deviceId = "source", records = listOf(record), resources = mapOf(hash to nested.size.toLong())), emptyMap())
        ws.capture("destination").use { before -> assertEquals("SYNC_VIBE_PAYLOAD_INVALID", assertFailsWith<IllegalArgumentException> {
            ws.apply(plan, before.snapshot.hashes(), "destination", mapOf(hash to blob))
        }.message) }
        assertFalse(Files.exists(data.resolve("entities/novelai_generation_history/history.json")))
        assertFalse(Files.exists(state.resolve("transaction-${plan.transaction}/authorized")))
    }
}
