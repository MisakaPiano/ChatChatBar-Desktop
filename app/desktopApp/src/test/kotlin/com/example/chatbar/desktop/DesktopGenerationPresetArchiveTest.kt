package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopGenerationPresetArchiveTest {
    private class Fixture(format: String = "png") {
        val parent = Files.createTempDirectory("generation-preset-")
        val source = Files.createDirectory(parent.resolve("source"))
        val target = Files.createDirectory(parent.resolve("target"))
        val gate = DesktopDataOperationCoordinator()
        val sourceStore = DesktopCharacterResourceStore(source)
        val targetStore = DesktopCharacterResourceStore(target)
        val exporter = DesktopGenerationPresetArchive(source, sourceStore, gate)
        val importer = DesktopGenerationPresetArchive(target, targetStore, gate)
        val file = parent.resolve("synthetic.ccbpreset")
        val image = java.io.ByteArrayOutputStream().use { output ->
            check(javax.imageio.ImageIO.write(BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB), format, output)); output.toByteArray()
        }
        val encoded = "AQID".repeat(4096)
        fun draft(): NovelAiStudioDraft {
            Files.createDirectories(source.resolve("images")); Files.write(source.resolve("images/reference.png"), image)
            val hash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
            val asset = NovelAiStudioAssetRef(id = "private-id", path = "images/reference.png", sha256 = hash, width = 1024, height = 1024)
            return NovelAiStudioDraft(stylePrompt = "style", basePrompt = "base\n【角色 1】", extraPrompt = "extra", negativePrompt = "negative",
                importedCharacterCardId = "private-card", aiDesignModelId = "private-model", naturalLanguageMode = true,
                characters = listOf(NovelAiCharacterPromptDraft(prompt = "person", negativePrompt = "person-negative", center = DesignedCharacterCenter(0.2f, 0.8f)),
                    NovelAiCharacterPromptDraft(prompt = "disabled", enabled = false)),
                v45Settings = NovelAiGenerationSettings(seedMode = NovelAiSeedMode.FIXED, seed = 123, customWidth = 832, customHeight = 1216),
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 15),
                imageGuidance = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.INPAINT, baseImage = asset, maskImage = asset,
                    focusedInpaintRegion = NovelAiFocusedInpaintRegion(0.1f, 0.1f, 0.8f, 0.8f), referenceMode = NovelAiReferenceMode.PRECISE,
                    preciseReference = NovelAiPreciseReferenceDraft(asset),
                    vibes = listOf(NovelAiVibeReferenceDraft(asset = asset, encodedVibe = NovelAiInlinePayloadStore(source.toFile()).retain(encoded)))))
        }
        suspend fun close() { gate.closeAndDrain(); check(parent.toFile().deleteRecursively()) }
    }
    private fun entries(file: Path): Map<String, ByteArray> = ZipFile(file.toFile()).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
    }
    private fun write(file: Path, entries: Map<String, ByteArray>) = ZipOutputStream(Files.newOutputStream(file)).use { zip ->
        entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    }

    @Test fun `complete preset roundtrip transports deduplicated original references and streamed encoding into an independent root`() = runBlocking {
        val f = Fixture()
        try {
            val draft = f.draft(); f.exporter.export(draft, f.file)
            val archive = entries(f.file)
            assertEquals(3, archive.size)
            val manifest = archive.getValue("preset.json").toString(Charsets.UTF_8)
            assertFalse(manifest.contains("private-card")); assertFalse(manifest.contains("private-model")); assertFalse(manifest.contains("images/reference.png"))
            f.importer.inspect(f.file).use { prepared ->
                val created = mutableListOf<String>()
                val guidance = f.importer.materialize(prepared, created::add)
                assertEquals(1, created.size)
                val restored = prepared.preset.applyTo(NovelAiStudioDraft(aiDesignModelId = "target-model",
                    continuousModeEnabled = true, continuousTargetCount = 100), guidance)
                assertEquals(draft.v45Settings, restored.v45Settings); assertEquals(draft.v5Settings, restored.v5Settings)
                assertEquals(draft.negativePrompt, restored.negativePrompt); assertEquals(draft.naturalLanguageMode, restored.naturalLanguageMode)
                assertEquals(draft.characters.map { it.center }, restored.characters.map { it.center })
                assertEquals(draft.characters.map { it.negativePrompt }, restored.characters.map { it.negativePrompt })
                assertEquals(listOf(true, false), restored.characters.map { it.enabled })
                assertEquals(NovelAiGenerationAction.INPAINT, restored.imageGuidance.action)
                assertEquals("target-model", restored.aiDesignModelId); assertNull(restored.importedCharacterCardId)
                assertFalse(restored.continuousModeEnabled); assertEquals(draft.continuousTargetCount, restored.continuousTargetCount)
                assertTrue(restored.imageGuidance.presetAssets().all { it.path.startsWith("images/") && it.path != "images/reference.png" })
                assertContentEquals(f.image, f.targetStore.readBytes(restored.imageGuidance.baseImage!!.path))
                assertEquals(f.encoded, NovelAiInlinePayloadStore(f.target.toFile()).resolve(restored.imageGuidance.vibes.single().encodedVibe!!))
            }
            assertContentEquals(f.image, Files.readAllBytes(f.source.resolve("images/reference.png")))
            assertEquals(0L, Files.list(f.target.resolve("backups/.studio-preset-import")).use { it.count() })
        } finally { f.close() }
    }

    @Test fun `future missing extra traversal and corrupted resources refuse inspection without replacing originals`() = runBlocking {
        val f = Fixture()
        try {
            f.exporter.export(f.draft(), f.file); val valid = entries(f.file)
            val originalArchive = Files.readAllBytes(f.file)
            val canary = Files.writeString(f.target.resolve("canary"), "original")
            val resource = valid.keys.first { it.endsWith(".image") }
            val manifest = Json.parseToJsonElement(valid.getValue("preset.json").toString(Charsets.UTF_8)).jsonObject
            val futurePreset = JsonObject(manifest.getValue("preset").jsonObject + ("version" to JsonPrimitive(2)))
            val future = JsonObject(manifest + ("preset" to futurePreset)).toString().toByteArray()
            val overflowResources = JsonArray(manifest.getValue("resources").jsonArray.map { proof ->
                val fields = proof.jsonObject
                if (fields.getValue("name").jsonPrimitive.content.endsWith(".vibe"))
                    JsonObject(fields + ("bytes" to JsonPrimitive(Long.MAX_VALUE))) else proof
            })
            val overflow = JsonObject(manifest + ("resources" to overflowResources)).toString().toByteArray()
            val variants = listOf(valid - resource, valid + ("../outside" to byteArrayOf(1)),
                valid + (resource to byteArrayOf(1, 2, 3)), valid + ("preset.json" to future),
                (valid - "preset.json") + ("preset.json" to valid.getValue("preset.json")),
                valid + ("preset.json" to overflow))
            for ((index, variant) in variants.withIndex()) {
                val file = f.parent.resolve("bad-$index.ccbpreset"); write(file, variant)
                assertFails { f.importer.inspect(file).close() }
                assertEquals("original", Files.readString(canary)); assertFalse(Files.exists(f.parent.resolve("outside")))
            }
            assertContentEquals(originalArchive, Files.readAllBytes(f.file))
            assertFails { f.exporter.export(f.draft(), f.file) }
            assertContentEquals(originalArchive, Files.readAllBytes(f.file))
        } finally { f.close() }
    }

    @Test fun `cancelled inspection and altered staged image cannot materialize or change effective input`() = runBlocking {
        val f = Fixture()
        try {
            val draft = f.draft(); f.exporter.export(draft, f.file)
            assertFailsWith<CancellationException> { withContext(Job().apply { cancel() }) { f.importer.inspect(f.file) } }
            f.importer.inspect(f.file).use { prepared ->
                val file = prepared.files.values.first { it.fileName.toString().endsWith(".image") }
                Files.write(file, byteArrayOf(1, 2, 3))
                val created = mutableListOf<String>()
                assertFails { f.importer.materialize(prepared, created::add) }
                assertTrue(created.isEmpty()); assertFalse(Files.exists(f.target.resolve("images")))
                assertEquals("base\n【角色 1】", draft.basePrompt)
            }
        } finally { f.close() }
    }

    @Test fun `original jpeg bytes retain their content type instead of receiving a png file suffix`() = runBlocking {
        val f = Fixture("jpg")
        try {
            f.exporter.export(f.draft(), f.file)
            f.importer.inspect(f.file).use { prepared ->
                val guidance = f.importer.materialize(prepared, register = {})
                val path = assertNotNull(guidance.baseImage).path
                assertTrue(path.endsWith(".jpg")); assertContentEquals(f.image, f.targetStore.readBytes(path))
            }
        } finally { f.close() }
    }
}
