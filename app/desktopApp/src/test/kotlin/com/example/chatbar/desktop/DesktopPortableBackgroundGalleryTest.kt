package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Inputs are exact production Android synthetic exports, hash-bound in resources/cover-gallery/origin.json. */
class DesktopPortableBackgroundGalleryTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun fixture(name: String): ByteArray {
        val data = requireNotNull(javaClass.getResourceAsStream("/cover-gallery/$name")).use { it.readBytes() }
        val origin = requireNotNull(javaClass.getResourceAsStream("/cover-gallery/origin.json")).use {
            Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
        }
        val record = origin.getValue("files").jsonArray.single { it.jsonObject.getValue("file").jsonPrimitive.content == name }.jsonObject
        assertEquals(record.getValue("bytes").jsonPrimitive.int, data.size)
        assertEquals(record.getValue("sha256").jsonPrimitive.content,
            java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
        return data
    }
    private suspend fun <T> withApp(block: suspend (DesktopAppContainer, Path) -> T): T {
        val parent = Files.createTempDirectory("portable-gallery-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try { return block(app, parent) } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
    private fun bytes(pack: CharacterCardPackage, id: String) = Base64.getDecoder().decode(pack.images.getValue(id).data)
    private fun assertEquivalent(before: CharacterCardPackage, after: CharacterCardPackage) {
        assertEquals(11, after.schemaVersion)
        assertEquals(before.card.name, after.card.name)
        assertEquals(before.card.characters.map { it.desktopEnabled }, after.card.characters.map { it.desktopEnabled })
        assertContentEquals(bytes(before, assertNotNull(before.card.coverImageResourceId)), bytes(after, assertNotNull(after.card.coverImageResourceId)))
        assertContentEquals(bytes(before, assertNotNull(before.card.chatBackgroundResourceId)), bytes(after, assertNotNull(after.card.chatBackgroundResourceId)))
        val original = before.card.chatBackgroundImageResourceIds.distinct()
        assertEquals(original.size, after.card.chatBackgroundImageResourceIds.size)
        original.zip(after.card.chatBackgroundImageResourceIds).forEach { (a, b) -> assertContentEquals(bytes(before, a), bytes(after, b)) }
        assertFalse(after.card.chatBackgroundResourceId in after.card.chatBackgroundImageResourceIds)
    }

    @Test fun `actual Android JSON and PNG survive Desktop production import export reopen and second round without losing any backgrounds`() = runBlocking {
        for (extension in listOf("json", "png")) withApp { app, _ ->
            val input = fixture("android-complete11.$extension")
            val source = if (extension == "json") app.characterTransfers.decode(input.toString(Charsets.UTF_8))
                else assertNotNull(app.characterTransfers.decodePng(input))
            assertFalse(source.card.characters.single().desktopEnabled)
            var card = app.characterTransfers.importNew(source)
            repeat(2) { round ->
                val exported = app.characterTransfers.prepareExport(card.id).packageData
                assertEquivalent(source, exported)
                val wire = app.transferJson.encodeToString(CharacterCardPackage.serializer(), exported)
                val png = CharacterCardPngPackageCodec.attach(bytes(exported, assertNotNull(exported.card.coverImageResourceId)), exported, app.transferJson)
                assertEquivalent(source, assertNotNull(app.characterTransfers.decodePng(png)))
                if (round == 0) System.getenv("CCB_COVER_GALLERY_REVIEW_DIR")?.let { directory ->
                    val output = Files.createDirectories(Path.of(directory))
                    Files.writeString(output.resolve("desktop-from-android-$extension.json"), wire)
                    Files.write(output.resolve("desktop-from-android-$extension.png"), png)
                }
                card = app.characterTransfers.importNew(app.characterTransfers.decode(wire))
                val reopened = com.example.chatbar.data.repository.CharacterRepository(com.example.chatbar.data.local.JsonFileStorage(app.appDataRoot))
                assertEquals(card, reopened.getById(card.id))
            }
        }
    }

    @Test fun `legacy private library projects read only then explicit migration preserves default preferred source bytes and later removal`() = runBlocking {
        withApp { app, _ ->
            val source = app.characterTransfers.decode(fixture("android-complete11.json").toString(Charsets.UTF_8))
            val card = app.characterTransfers.importNew(source)
            val old = DesktopCharacterBackgrounds(app.jsonFileStorage, app.characterResourceStore, app.dataOperationCoordinator, {})
            val image = bytes(source, source.card.chatBackgroundImageResourceIds.first())
            old.import(card.id, image)
            val legacy = old.load(card.id).images.single(); old.prefer(card.id, legacy)
            val oldRecord = Files.readAllBytes(app.appDataRoot.resolve("entities/desktop_character_backgrounds/${card.id}.json"))
            val oldBytes = app.characterResourceStore.readBytes(legacy)
            val readOnly = app.characterTransfers.prepareExport(card.id)
            assertEquals(card.chatBackgroundImages.size + 1, readOnly.packageData.card.chatBackgroundImageResourceIds.size)
            assertEquals(card, app.characterRepository.getById(card.id))
            assertContentEquals(oldRecord, Files.readAllBytes(app.appDataRoot.resolve("entities/desktop_character_backgrounds/${card.id}.json")))
            app.characterBackgrounds.migrate(card.id)
            val migrated = assertNotNull(app.characterRepository.getById(card.id))
            assertEquals(card.chatBackgroundImages + legacy, migrated.chatBackgroundImages)
            assertEquals(card.chatBackground, migrated.chatBackground)
            assertEquals(legacy, app.characterBackgrounds.preferred(card.id))
            app.characterBackgrounds.remove(card.id, legacy)
            assertEquals(card.chatBackgroundImages, app.characterBackgrounds.catalog(card.id).images)
            assertContentEquals(oldBytes, app.characterResourceStore.readBytes(legacy))
            assertTrue(app.characterBackgrounds.load(card.id).migrated)
            assertEquals(card.chatBackgroundImages.size, app.characterTransfers.prepareExport(card.id).packageData.card.chatBackgroundImageResourceIds.size)
        }
    }

    @Test fun `new background bytes and explicit default survive export while missing gallery fails visibly without modifying source`() = runBlocking {
        withApp { app, parent ->
            val source = app.characterTransfers.decode(fixture("android-complete11.json").toString(Charsets.UTF_8))
            val card = app.characterTransfers.importNew(source)
            app.characterRepository.update(card.copy(name = "Desktop modified synthetic gallery"))
            val image = bytes(source, source.card.chatBackgroundImageResourceIds.last())
            app.characterBackgrounds.import(card.id, image)
            val added = app.characterBackgrounds.catalog(card.id).images.last()
            assertContentEquals(image, app.characterResourceStore.readBytes(added))
            app.characterBackgrounds.setDefault(card.id, added)
            val full = app.characterTransfers.prepareExport(card.id).packageData
            assertEquals(full.card.chatBackgroundResourceId, full.card.chatBackgroundImageResourceIds.last())
            assertEquals(card.chatBackgroundImages.size + 1, full.card.chatBackgroundImageResourceIds.size)
            assertNotEquals(full.card.chatBackgroundImageResourceIds.last(), full.card.chatBackgroundImageResourceIds[card.chatBackgroundImages.lastIndex])
            assertContentEquals(bytes(full, full.card.chatBackgroundImageResourceIds.last()), bytes(full, full.card.chatBackgroundImageResourceIds[card.chatBackgroundImages.lastIndex]))
            System.getenv("CCB_COVER_GALLERY_REVIEW_DIR")?.let { directory ->
                val output = Files.createDirectories(Path.of(directory))
                Files.writeString(output.resolve("desktop-created-complete11.json"), app.transferJson.encodeToString(CharacterCardPackage.serializer(), full))
                Files.write(output.resolve("desktop-created-complete11.png"), CharacterCardPngPackageCodec.attach(
                    bytes(full, assertNotNull(full.card.coverImageResourceId)), full, app.transferJson))
                fun digest(id: String) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes(full, id)).joinToString("") { "%02x".format(it) }
                val expected = buildJsonObject {
                    put("syntheticOnly", true); put("name", full.card.name)
                    put("cover", digest(assertNotNull(full.card.coverImageResourceId)))
                    put("default", digest(assertNotNull(full.card.chatBackgroundResourceId)))
                    put("defaultGalleryIndex", full.card.chatBackgroundImageResourceIds.indexOf(full.card.chatBackgroundResourceId))
                    put("gallery", JsonArray(full.card.chatBackgroundImageResourceIds.map { JsonPrimitive(digest(it)) }))
                    put("distinctReferences", full.card.chatBackgroundImageResourceIds.distinct().size)
                    put("peopleEnabled", JsonArray(full.card.characters.map { JsonPrimitive(it.desktopEnabled) }))
                }
                Files.writeString(output.resolve("desktop-created-expected.json"), expected.toString())
            }
            val before = assertNotNull(app.characterRepository.getById(card.id))
            Files.move(app.appDataRoot.resolve(added), parent.resolve("held.png"))
            assertFails { app.characterTransfers.prepareExport(card.id) }
            assertEquals(before, app.characterRepository.getById(card.id))
            // Its missing default remains a required retained image in the explicit compatibility projection too.
            assertFails { app.characterTransfers.prepareExport(card.id, CharacterCardExportCompatibility.OFFICIAL_V9) }
        }
    }

    @Test fun `new Desktop preference does not become a legacy gallery source after a portable card edit`() = runBlocking {
        withApp { app, _ ->
            val source = app.characterTransfers.decode(fixture("android-complete11.json").toString(Charsets.UTF_8))
            val card = app.characterTransfers.importNew(source)
            val chosen = card.chatBackgroundImages.first()
            app.characterBackgrounds.prefer(card.id, chosen)
            assertTrue(app.characterBackgrounds.load(card.id).migrated)
            assertEquals(card, app.characterRepository.getById(card.id))
            app.characterRepository.update(card.copy(chatBackgroundImages = card.chatBackgroundImages - chosen))
            val exported = app.characterTransfers.prepareExport(card.id).packageData
            assertEquals(card.chatBackgroundImages.size - 1, exported.card.chatBackgroundImageResourceIds.size)
            assertEquals(chosen, app.characterBackgrounds.preferred(card.id))
            assertContentEquals(app.characterResourceStore.readBytes(chosen), bytes(source, source.card.chatBackgroundImageResourceIds.first()))
        }
    }
}
