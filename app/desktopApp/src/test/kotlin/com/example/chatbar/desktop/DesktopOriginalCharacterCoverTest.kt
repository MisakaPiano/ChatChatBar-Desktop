package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopOriginalCharacterCoverTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private object Prompts : CharacterTransferPromptPolicy {
        override fun defaultCharacterNaiNegativePrompt() = "synthetic-negative"
        override fun effectiveCharacterNaiNegativePrompt(value: String) = value
    }
    private class Fixture {
        val root = Files.createTempDirectory("desktop-original-cover-")
        val storage = JsonFileStorage(root)
        val cards = CharacterRepository(storage)
        val resources = DesktopCharacterResourceStore(root)
        val core = CharacterCardTransferCore(cards, WorldBookRepository(storage), FormatCardRepository(storage),
            resources, Prompts, CharacterDocumentRagCleanup {}, Json { encodeDefaults = true; ignoreUnknownKeys = true })
        fun close() { root.toFile().deleteRecursively() }
    }
    private fun image(width: Int = 3, height: Int = 5, format: String = "png"): ByteArray {
        val raster = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) raster.setRGB(x, y, (x * 31 shl 16) or (y * 23 shl 8) or 73)
        return ByteArrayOutputStream().use { check(ImageIO.write(raster, format, it)); it.toByteArray() }
    }
    private fun packaged(bytes: ByteArray, name: String = "original.png") = PackagedImage(name, Base64.getEncoder().encodeToString(bytes))

    @Test fun `legacy native import text edits repeated PNG export and import retain encoded original without nested growth`() = runBlocking {
        val f = Fixture()
        try {
            val original = image()
            val legacy = CharacterCardPngPackageCodec.attach(original, CharacterCardPackage(card = PackagedCharacterCard("Original")), json)
            var card = f.core.importNew(assertNotNull(f.core.decodePng(legacy)))
            val firstReference = assertNotNull(card.coverImage)
            assertTrue(firstReference.startsWith("images/character-covers/"))
            val retained = f.resources.readBytes(firstReference)
            assertContentEquals(original, retained)
            var maximumSize = 0
            repeat(5) { index ->
                card = card.copy(name = "Edited-$index", greeting = "New text $index")
                f.cards.update(card)
                val export = f.core.prepareExport(card.id, CharacterCardExportCompatibility.DESKTOP_COVER_V10)
                assertEquals(10, export.packageData.schemaVersion)
                assertEquals(1, export.packageData.images.size)
                val cover = Base64.getDecoder().decode(export.packageData.images.getValue(assertNotNull(export.packageData.card.coverImageResourceId)).data)
                val picture = DesktopCharacterCardPngRenderer { error("Original mode must not load brand typography") }
                    .renderExportImage(export.card, CharacterCardPngExportOptions(sizePx = 2048, cropZoom = 6f), cover, null)
                val png = CharacterCardPngPackageCodec.attach(picture, export.packageData, json)
                assertContentEquals(original, OriginalCharacterCover.imageOnlyPng(png))
                val next = assertNotNull(f.core.decodePng(png))
                assertEquals(card.greeting, next.card.greeting)
                if (index == 0) maximumSize = png.size + 2000
                assertTrue(png.size <= maximumSize)
                card = f.core.importNew(next)
            }
            assertContentEquals(retained, f.resources.readBytes(firstReference))
        } finally { f.close() }
    }

    @Test fun `embedded independent cover wins over outer composition and duplication copies its resource`() = runBlocking {
        val f = Fixture()
        try {
            val original = image(4, 7)
            val source = CharacterCardPackage(schemaVersion = 10,
                card = PackagedCharacterCard("Embedded", avatarResourceId = "cover", coverImageResourceId = "cover"), images = mapOf("cover" to packaged(original)))
            val outer = CharacterCardPngPackageCodec.attach(image(11, 13), source, json)
            val card = f.core.importNew(assertNotNull(f.core.decodePng(outer)))
            assertNotNull(card.avatar)
            assertNotEquals(card.avatar, card.coverImage)
            val duplicate = f.core.duplicate(card.id)
            assertNotEquals(card.coverImage, duplicate.coverImage)
            assertContentEquals(original, f.resources.readBytes(assertNotNull(card.coverImage)))
            assertContentEquals(original, f.resources.readBytes(assertNotNull(duplicate.coverImage)))
            f.core.deleteCard(duplicate.id)
            assertContentEquals(original, f.resources.readBytes(card.coverImage!!))
        } finally { f.close() }
    }

    @Test fun `SillyTavern shared PNG importer retains cover while avatar and portable resources contain no old card chunks`() = runBlocking {
        val f = Fixture()
        try {
            val original = image()
            val payload = Base64.getEncoder().encodeToString("""{"spec":"chara_card_v2","data":{"name":"ST fixture","description":"New story"}}""".toByteArray())
            val png = PngTextChunks.insertTextChunk(original, "Chara", payload)
            val classifier = SharedImportClassifierCore<Nothing>(SillyTavernCardMapper(Prompts), json = json)
            val inspected = assertIs<SharedImportCoreInspection.Character>(classifier.inspect(png))
            val card = f.core.importNew(inspected.request.packageData)
            assertContentEquals(original, f.resources.readBytes(assertNotNull(card.avatar)))
            assertNotEquals(card.coverImage, card.avatar)
            assertContentEquals(original, f.resources.readBytes(assertNotNull(card.coverImage)))
            val exported = f.core.prepareExport(card.id).packageData
            for (resource in exported.images.values) {
                val bytes = Base64.getDecoder().decode(resource.data)
                assertContentEquals(bytes, OriginalCharacterCover.imageOnlyPng(bytes))
                assertNull(PngTextChunks.extractTextChunk(bytes, "chara", ignoreCase = true))
            }
            assertContentEquals(original, OriginalCharacterCover.imageOnlyPng(png))
        } finally { f.close() }
    }

    @Test fun `all nested packaged PNG roles drop old card text on imported and exported copies without rewriting sources`() = runBlocking {
        val f = Fixture()
        try {
            val original = image()
            val payload = Base64.getEncoder().encodeToString("old nested character body".toByteArray())
            val nested = PngTextChunks.insertTextChunk(original, "Chara", payload)
            val retainedSource = nested.copyOf()
            val source = CharacterCardPackage(schemaVersion = 10,
                card = PackagedCharacterCard("Nested", avatarResourceId = "avatar", chatBackgroundResourceId = "background",
                    characters = listOf(PackagedCharacter("Person", appearanceImageResourceId = "appearance")), coverImageResourceId = "cover"),
                images = listOf("avatar", "background", "appearance", "cover").associateWith { packaged(nested) })
            val imported = f.core.importNew(source)
            val references = listOf(imported.avatar, imported.chatBackground, imported.characters.single().appearanceImage, imported.coverImage)
            assertEquals(4, references.filterNotNull().toSet().size)
            references.forEach { assertContentEquals(original, f.resources.readBytes(assertNotNull(it))) }
            f.cards.update(imported.copy(name = "Current text"))
            val exported = f.core.prepareExport(imported.id, CharacterCardExportCompatibility.DESKTOP_COVER_V10).packageData
            assertEquals("Current text", exported.card.name)
            assertEquals(4, exported.images.size)
            exported.images.values.forEach { assertContentEquals(original, Base64.getDecoder().decode(it.data)) }
            assertContentEquals(retainedSource, nested)
            source.images.values.forEach { assertContentEquals(retainedSource, Base64.getDecoder().decode(it.data)) }
        } finally { f.close() }
    }

    @Test fun `nonPNG originals remain byte exact while original and explicit layout outputs stay separate`() = runBlocking {
        val f = Fixture()
        try {
            val jpeg = image(9, 13, "jpg")
            val card = f.core.importNew(CharacterCardPackage(schemaVersion = 10,
                card = PackagedCharacterCard("JPEG", coverImageResourceId = "cover"), images = mapOf("cover" to packaged(jpeg, "source.jpg"))))
            val reference = assertNotNull(card.coverImage)
            assertContentEquals(jpeg, f.resources.readBytes(reference))
            val renderer = DesktopCharacterCardPngRenderer { image() }
            val originalOutput = renderer.renderExportImage(card, CharacterCardPngExportOptions(), jpeg, null)
            val decoded = ImageIO.read(originalOutput.inputStream())
            assertEquals(9, decoded.width); assertEquals(13, decoded.height)
            val rendered = renderer.renderExportImage(card, CharacterCardPngExportOptions(sizePx = 1024, preserveOriginalCover = false), jpeg, null)
            assertEquals(1024, ImageIO.read(rendered.inputStream()).width)
            assertContentEquals(jpeg, f.resources.readBytes(reference))
        } finally { f.close() }
    }

    @Test fun `explicit avatar copy drafts materialize and clear without changing avatar or rollback sources`() = runBlocking {
        val f = Fixture()
        val gate = DesktopDataOperationCoordinator()
        try {
            val original = image()
            val avatar = f.resources.materializeImage(packaged(original), 1, "avatar")
            val drafts = DesktopCharacterDraftResources(f.root, f.resources)
            val staged = drafts.copyAsCover("cover-session", avatar)
            val pending = CharacterCard("draft", "Draft", avatar = avatar, createdAt = 1, updatedAt = 1, coverImage = staged)
            val (saved, created) = drafts.materialize(pending)
            val cover = assertNotNull(saved.coverImage)
            assertTrue(cover.startsWith("images/character-covers/")); assertNotEquals(avatar, cover)
            assertEquals(listOf(cover), created)
            assertTrue(staged in desktopCharacterResourceReferences(pending))
            f.cards.save(saved)
            drafts.discardSession("cover-session")
            assertContentEquals(original, f.resources.readBytes(cover))
            f.cards.update(saved.copy(coverImage = null))
            DesktopOwnedImageCleanup(f.root, f.resources, gate).deleteUnreferenced(listOf(cover))
            assertFalse(Files.exists(f.root.resolve(cover)))
            assertContentEquals(original, f.resources.readBytes(avatar))
            val (retry, allocated) = drafts.materialize(saved.copy(coverImage = avatar))
            drafts.rollback(allocated, IOExceptionForTest())
            assertFalse(Files.exists(f.root.resolve(retry.coverImage!!)))
            assertContentEquals(original, f.resources.readBytes(avatar))
        } finally { gate.closeAndDrain(); f.close() }
    }

    @Test fun `production transfer output guard refuses overwriting original with JSON PNG and edited export`() = runBlocking {
        val parent = Files.createTempDirectory("original-cover-output-guard-")
        class Picker : DesktopFilePicker {
            var target: Path? = null
            override fun pickOpenFile(type: DesktopFileType): Path? = null
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = target
        }
        val picker = Picker()
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker)
        try {
            val png = image()
            val card = c.characterTransfers.importNew(CharacterCardPackage(schemaVersion = 10,
                card = PackagedCharacterCard("Output guard", coverImageResourceId = "cover"), images = mapOf("cover" to packaged(png))))
            val original = c.appDataRoot.resolve(card.coverImage!!)
            picker.target = original
            val controller = c.createTypedTransferController()
            controller.exportCharacterPng(card.id)
            assertNotNull(controller.state.value.error)
            controller.exportCharacterJson(card.id)
            assertNotNull(controller.state.value.error)
            controller.beginCoverExport(card.id)
            controller.finishCoverExport(assertNotNull(controller.state.value.coverExport), CharacterCardPngExportOptions(), null)
            assertNotNull(controller.state.value.error)
            assertContentEquals(png, Files.readAllBytes(original))
            picker.target = parent.resolve("safe-export.png")
            controller.exportCharacterPng(card.id)
            assertNull(controller.state.value.error)
            assertContentEquals(png, OriginalCharacterCover.imageOnlyPng(Files.readAllBytes(picker.target!!)))
        } finally { c.close(); parent.toFile().deleteRecursively() }
    }
    private class IOExceptionForTest : java.io.IOException("synthetic rollback")
}
