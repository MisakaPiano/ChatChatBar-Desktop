package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import com.example.chatbar.desktop.interop.official1411.CharacterCardPackage as Official1411Package
import com.example.chatbar.desktop.interop.official1411.validateForImport as validateOfficial1411
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopCharacterExportCompatibilityTest {
    private class Picker : DesktopFilePicker {
        var target: Path? = null
        var saves = 0
        override fun pickOpenFile(type: DesktopFileType): Path? = null
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? { saves++; return target }
    }
    private class Fixture {
        val parent = Files.createTempDirectory("official-character-export-")
        val picker = Picker()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker,
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        val controller = app.createTypedTransferController()
        val picture = ByteArrayOutputStream().use { out ->
            val image = BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB)
            image.setRGB(3, 4, 0x129a73); check(ImageIO.write(image, "png", out)); out.toByteArray()
        }
        val legacy = CharacterCardPackage(card = PackagedCharacterCard("Legacy", avatarResourceId = "avatar",
            characters = listOf(PackagedCharacter("Person", profile = "synthetic-profile")),
            systemPrompt = "synthetic-system", postHistoryInstructions = "synthetic-history"),
            images = mapOf("avatar" to PackagedImage("avatar.png", Base64.getEncoder().encodeToString(picture))),
            documents = listOf(PackagedDocument("synthetic.txt", "txt", "synthetic-document")))
        suspend fun card() = app.characterTransfers.importNew(assertNotNull(app.characterTransfers.decodePng(
            CharacterCardPngPackageCodec.attach(picture, legacy, app.transferJson)))).let { initial ->
            initial.copy(name = "Edited legacy", greeting = "Edited text").also { app.characterRepository.update(it) }
        }
        fun wire(path: Path): String {
            val bytes = Files.readAllBytes(path)
            return if (path.toString().endsWith(".png")) String(Base64.getDecoder().decode(assertNotNull(
                PngTextChunks.extractTextChunk(bytes, PngTextChunks.CHATBAR_CHARACTER_KEYWORD))), Charsets.UTF_8)
            else String(bytes, Charsets.UTF_8)
        }
        fun official(raw: String) = app.transferJson.decodeFromString(Official1411Package.serializer(), raw).also { it.validateOfficial1411() }
        suspend fun close() { app.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `legacy PNG text edit default core JSON and PNG exports remain official compatible with original appearance`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card(); val cover = assertNotNull(card.coverImage)
            val retained = Files.readAllBytes(f.app.appDataRoot.resolve(cover))
            val core = f.app.characterTransfers.prepareExport(card.id).packageData
            assertEquals(9, core.schemaVersion); assertNull(core.card.coverImageResourceId)
            assertEquals(1, core.images.size)
            f.official(f.app.characterTransfers.exportJson(card.id))
            for (extension in listOf("json", "png")) {
                val target = f.parent.resolve("default.$extension"); f.picker.target = target
                if (extension == "json") f.controller.exportCharacterJson(card.id) else f.controller.exportCharacterPng(card.id)
                assertNull(f.controller.state.value.error)
                val raw = f.wire(target); val official = f.official(raw)
                assertFalse(raw.contains("coverImageResourceId")); assertEquals(9, official.schemaVersion)
                assertEquals(card.name, official.card.name); assertEquals(card.greeting, official.card.greeting)
                assertEquals(card.systemPrompt, official.card.systemPrompt)
                assertEquals(card.postHistoryInstructions, official.card.postHistoryInstructions)
                assertEquals(1, official.images.size); assertEquals("synthetic-document", official.documents.single().content)
                if (extension == "png") assertContentEquals(f.picture, OriginalCharacterCover.imageOnlyPng(Files.readAllBytes(target)))
            }
            assertEquals(card, f.app.characterRepository.getById(card.id))
            assertContentEquals(retained, Files.readAllBytes(f.app.appDataRoot.resolve(cover)))
        } finally { f.close() }
    }

    @Test fun `both full export controller routes retain extension and reject current official reader while Desktop reimports`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card()
            for (extension in listOf("json", "png")) {
                val target = f.parent.resolve("full.$extension"); f.picker.target = target
                if (extension == "json") {
                    f.controller.beginCharacterJsonExport(card.id)
                    f.controller.finishCharacterJsonExport(assertNotNull(f.controller.state.value.characterJsonExport), CharacterCardExportCompatibility.DESKTOP_COVER_V10)
                } else {
                    f.controller.beginCoverExport(card.id)
                    f.controller.finishCoverExport(assertNotNull(f.controller.state.value.coverExport), CharacterCardPngExportOptions(), null,
                        CharacterCardExportCompatibility.DESKTOP_COVER_V10)
                }
                assertNull(f.controller.state.value.error)
                val raw = f.wire(target)
                val official = f.app.transferJson.decodeFromString(Official1411Package.serializer(), raw)
                assertFailsWith<IllegalArgumentException> { official.validateOfficial1411() }
                val full = f.app.characterTransfers.decode(raw)
                assertEquals(10, full.schemaVersion)
                val image = full.images.getValue(assertNotNull(full.card.coverImageResourceId))
                assertContentEquals(f.picture, Base64.getDecoder().decode(image.data))
                val reimported = f.app.characterTransfers.importNew(full)
                assertContentEquals(f.picture, Files.readAllBytes(f.app.appDataRoot.resolve(assertNotNull(reimported.coverImage))))
                if (extension == "png") assertContentEquals(f.picture, OriginalCharacterCover.imageOnlyPng(Files.readAllBytes(target)))
            }
        } finally { f.close() }
    }

    @Test fun `actual JSON dialog default choice and PNG finish emit official files without changing local cover`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card()
            f.controller.beginCharacterJsonExport(card.id)
            val draft = assertNotNull(f.controller.state.value.characterJsonExport)
            assertEquals(9, draft.packageData.schemaVersion)
            f.picker.target = f.parent.resolve("dialog.json")
            f.controller.finishCharacterJsonExport(draft, CharacterCardExportCompatibility.OFFICIAL_V9)
            f.official(f.wire(f.picker.target!!)); assertNull(f.controller.state.value.characterJsonExport)
            f.controller.beginCoverExport(card.id)
            f.picker.target = f.parent.resolve("dialog.png")
            f.controller.finishCoverExport(assertNotNull(f.controller.state.value.coverExport), CharacterCardPngExportOptions(), null)
            f.official(f.wire(f.picker.target!!))
            assertContentEquals(f.picture, OriginalCharacterCover.imageOnlyPng(Files.readAllBytes(f.picker.target!!)))
            assertEquals(card, f.app.characterRepository.getById(card.id))
        } finally { f.close() }
    }

    @Test fun `cancel reopen stale drafts and canceled chooser never publish obsolete or partial output`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card()
            f.controller.beginCharacterJsonExport(card.id)
            val old = assertNotNull(f.controller.state.value.characterJsonExport)
            f.controller.cancelCharacterJsonExport(); f.controller.beginCharacterJsonExport(card.id)
            val current = assertNotNull(f.controller.state.value.characterJsonExport)
            assertNotSame(old, current)
            f.controller.finishCharacterJsonExport(old, CharacterCardExportCompatibility.OFFICIAL_V9)
            assertNotNull(f.controller.state.value.error); assertEquals(0, f.picker.saves)
            f.controller.finishCharacterJsonExport(current, CharacterCardExportCompatibility.OFFICIAL_V9)
            assertSame(current, f.controller.state.value.characterJsonExport)
            assertEquals(1, f.picker.saves)
            f.controller.cancelCharacterJsonExport()
            f.controller.beginCoverExport(card.id); val png = assertNotNull(f.controller.state.value.coverExport)
            f.controller.cancelCoverExport(); f.controller.beginCoverExport(card.id)
            f.controller.finishCoverExport(png, CharacterCardPngExportOptions(), null)
            assertNotNull(f.controller.state.value.error); assertEquals(1, f.picker.saves)
            assertEquals(card, f.app.characterRepository.getById(card.id))
        } finally { f.close() }
    }

    @Test fun `full choice refuses changed card or linked image instead of mixing snapshots`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card()
            f.controller.beginCharacterJsonExport(card.id); val old = assertNotNull(f.controller.state.value.characterJsonExport)
            f.app.characterRepository.update(card.copy(greeting = "Changed while dialog open"))
            f.controller.finishCharacterJsonExport(old, CharacterCardExportCompatibility.DESKTOP_COVER_V10)
            assertNotNull(f.controller.state.value.error); assertEquals(0, f.picker.saves)
            f.controller.cancelCharacterJsonExport(); f.controller.beginCharacterJsonExport(card.id)
            val current = assertNotNull(f.controller.state.value.characterJsonExport)
            Files.write(f.app.appDataRoot.resolve(assertNotNull(card.avatar)), byteArrayOf(1, 2, 3))
            f.controller.finishCharacterJsonExport(current, CharacterCardExportCompatibility.DESKTOP_COVER_V10)
            assertNotNull(f.controller.state.value.error); assertEquals(0, f.picker.saves)
        } finally { f.close() }
    }

    @Test fun `missing unused cover does not prevent official JSON and stored cover reference remains`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card(); Files.delete(f.app.appDataRoot.resolve(assertNotNull(card.coverImage)))
            val raw = f.app.characterTransfers.exportJson(card.id); f.official(raw)
            f.controller.beginCharacterJsonExport(card.id)
            val draft = assertNotNull(f.controller.state.value.characterJsonExport)
            f.picker.target = f.parent.resolve("missing-cover.json")
            f.controller.finishCharacterJsonExport(draft, CharacterCardExportCompatibility.OFFICIAL_V9)
            assertNull(f.controller.state.value.error); f.official(f.wire(f.picker.target!!))
            f.controller.beginCharacterJsonExport(card.id)
            f.controller.finishCharacterJsonExport(assertNotNull(f.controller.state.value.characterJsonExport), CharacterCardExportCompatibility.DESKTOP_COVER_V10)
            assertNotNull(f.controller.state.value.error)
            assertEquals(card.coverImage, f.app.characterRepository.getById(card.id)?.coverImage)
        } finally { f.close() }
    }
}
