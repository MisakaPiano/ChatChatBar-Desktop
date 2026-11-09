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
    @Test fun `selected base saves separately and full PNG reimports its raw base while layout title stays display only`(): Unit = runBlocking {
        val f = Fixture()
        try {
            val card = f.card()
            f.controller.beginCoverExport(card.id)
            val draft = assertNotNull(f.controller.state.value.coverExport)
            assertTrue(draft.assets.any { it.label.contains("卡片头像") })
            val base = DesktopImageEditing.png(BufferedImage(23, 41, BufferedImage.TYPE_INT_RGB).also { it.setRGB(4, 9, 0x00aacc) })
            val basePath = f.parent.resolve("reusable-base.png"); f.picker.target = basePath
            f.controller.saveCoverBase(draft, base)
            assertNull(f.controller.state.value.error)
            assertSame(draft, f.controller.state.value.coverExport)
            assertContentEquals(base, Files.readAllBytes(basePath))
            val output = f.parent.resolve("layout.png"); f.picker.target = output
            f.controller.finishCoverExport(draft, CharacterCardPngExportOptions(preserveOriginalCover = false,
                displayTitle = "Display title only", gradientStrength = .5f), base, CharacterCardExportCompatibility.COMPLETE_V11)
            assertNull(f.controller.state.value.error)
            val pack = assertNotNull(f.app.characterTransfers.decodePng(Files.readAllBytes(output)))
            val embedded = pack.images.getValue(assertNotNull(pack.card.coverImageResourceId))
            assertContentEquals(base, Base64.getDecoder().decode(embedded.data))
            assertEquals(card.name, pack.card.name)
            assertEquals(card, f.app.characterRepository.getById(card.id))
            assertContentEquals(base, Files.readAllBytes(basePath))
            val other = DesktopCharacterCardPngRenderer().render(card, CharacterCardPngExportOptions(displayTitle = "Another title"), base)
            assertFalse(other.contentEquals(Files.readAllBytes(output)))
            val compatible = desktopCoverExportPackage(draft.packageData, base, CharacterCardExportCompatibility.OFFICIAL_V9)
            assertNull(compatible.card.coverImageResourceId)
            f.official(f.app.transferJson.encodeToString(CharacterCardPackage.serializer(), compatible))
        } finally { f.close() }
    }

    @Test fun `cover candidates include only the selected cards actual package and background library assets`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card()
            val library = DesktopCharacterBackgrounds(f.app.jsonFileStorage, f.app.characterResourceStore,
                f.app.dataOperationCoordinator, {})
            library.import(card.id, f.picture)
            library.import("unrelated-card", DesktopImageEditing.png(BufferedImage(3, 5, BufferedImage.TYPE_INT_RGB)))
            f.controller.beginCoverExport(card.id)
            val draft = assertNotNull(f.controller.state.value.coverExport)
            assertEquals(1, draft.assets.count { it.label.startsWith("背景图库") })
            val owned = f.app.characterResourceStore.readBytes(library.load(card.id).images.single())
            assertContentEquals(owned, draft.assets.single { it.label.startsWith("背景图库") }.bytes)
            assertTrue(draft.assets.none { DesktopImageEditing.decode(it.bytes).width == 3 })
            assertEquals(card, f.app.characterRepository.getById(card.id))
        } finally { f.close() }
    }

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
            app.characterRepository.update(initial.copy(name = "Edited legacy", greeting = "Edited text"))
            assertNotNull(app.characterRepository.getById(initial.id))
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
            val core = f.app.characterTransfers.prepareExport(card.id, CharacterCardExportCompatibility.OFFICIAL_V9).packageData
            assertEquals(9, core.schemaVersion); assertNull(core.card.coverImageResourceId)
            assertEquals(1, core.images.size)
            f.official(f.app.characterTransfers.exportJson(card.id, CharacterCardExportCompatibility.OFFICIAL_V9))
            for (extension in listOf("json", "png")) {
                val target = f.parent.resolve("default.$extension"); f.picker.target = target
                if (extension == "json") f.controller.exportCharacterJson(card.id, CharacterCardExportCompatibility.OFFICIAL_V9) else f.controller.exportCharacterPng(card.id, compatibility = CharacterCardExportCompatibility.OFFICIAL_V9)
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
                    f.controller.finishCharacterJsonExport(assertNotNull(f.controller.state.value.characterJsonExport), CharacterCardExportCompatibility.COMPLETE_V11)
                } else {
                    f.controller.beginCoverExport(card.id)
                    f.controller.finishCoverExport(assertNotNull(f.controller.state.value.coverExport), CharacterCardPngExportOptions(), null,
                        CharacterCardExportCompatibility.COMPLETE_V11)
                }
                assertNull(f.controller.state.value.error)
                val raw = f.wire(target)
                val official = f.app.transferJson.decodeFromString(Official1411Package.serializer(), raw)
                assertFailsWith<IllegalArgumentException> { official.validateOfficial1411() }
                val full = f.app.characterTransfers.decode(raw)
                assertEquals(11, full.schemaVersion)
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
            assertEquals(9, draft.packageData.schemaVersion) // Lightweight compatible baseline; visible choice defaults to full11.
            f.picker.target = f.parent.resolve("dialog.json")
            f.controller.finishCharacterJsonExport(draft, CharacterCardExportCompatibility.OFFICIAL_V9)
            f.official(f.wire(f.picker.target!!)); assertNull(f.controller.state.value.characterJsonExport)
            f.controller.beginCoverExport(card.id)
            f.picker.target = f.parent.resolve("dialog.png")
            f.controller.finishCoverExport(assertNotNull(f.controller.state.value.coverExport), CharacterCardPngExportOptions(), null, CharacterCardExportCompatibility.OFFICIAL_V9)
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
            f.controller.finishCharacterJsonExport(old, CharacterCardExportCompatibility.COMPLETE_V11)
            assertNotNull(f.controller.state.value.error); assertEquals(0, f.picker.saves)
            f.controller.cancelCharacterJsonExport(); f.controller.beginCharacterJsonExport(card.id)
            val current = assertNotNull(f.controller.state.value.characterJsonExport)
            Files.write(f.app.appDataRoot.resolve(assertNotNull(card.avatar)), byteArrayOf(1, 2, 3))
            f.controller.finishCharacterJsonExport(current, CharacterCardExportCompatibility.COMPLETE_V11)
            assertNotNull(f.controller.state.value.error); assertEquals(0, f.picker.saves)
        } finally { f.close() }
    }

    @Test fun `missing unused cover does not prevent official JSON and stored cover reference remains`() = runBlocking {
        val f = Fixture()
        try {
            val card = f.card(); Files.delete(f.app.appDataRoot.resolve(assertNotNull(card.coverImage)))
            Files.delete(f.app.appDataRoot.resolve(card.coverImage!!).parent)
            val raw = f.app.characterTransfers.exportJson(card.id, CharacterCardExportCompatibility.OFFICIAL_V9); f.official(raw)
            f.controller.beginCharacterJsonExport(card.id)
            val draft = assertNotNull(f.controller.state.value.characterJsonExport)
            f.picker.target = f.parent.resolve("missing-cover.json")
            f.controller.finishCharacterJsonExport(draft, CharacterCardExportCompatibility.OFFICIAL_V9)
            assertNull(f.controller.state.value.error); f.official(f.wire(f.picker.target!!))
            val source = f.app.appDataRoot.resolve(card.coverImage!!)
            Files.createDirectories(source.parent)
            f.picker.target = source
            f.controller.beginCharacterJsonExport(card.id)
            f.controller.finishCharacterJsonExport(assertNotNull(f.controller.state.value.characterJsonExport), CharacterCardExportCompatibility.OFFICIAL_V9)
            assertNotNull(f.controller.state.value.error); assertFalse(Files.exists(source))
            f.controller.cancelCharacterJsonExport()
            f.controller.beginCharacterJsonExport(card.id)
            f.controller.finishCharacterJsonExport(assertNotNull(f.controller.state.value.characterJsonExport), CharacterCardExportCompatibility.COMPLETE_V11)
            assertNotNull(f.controller.state.value.error)
            assertEquals(card.coverImage, f.app.characterRepository.getById(card.id)?.coverImage)
        } finally { f.close() }
    }
}
