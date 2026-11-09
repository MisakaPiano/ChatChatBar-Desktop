package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardPngPackageCodec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** The twelve inputs are exact Android production outputs, not rewritten wire fixtures. */
class DesktopAndroidGalleryReturnTest {
    private fun resource(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/cover-gallery/android-return/$name")
    ).use { it.readBytes() }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private suspend fun <T> withApp(block: suspend (DesktopAppContainer) -> T): T {
        val parent = Files.createTempDirectory("android-gallery-return-")
        val app = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path ->
                check(path == "presets/manifest.json")
                """{"entries":[]}""".toByteArray()
            }
        )
        try { return block(app) } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }

    private fun state(pack: CharacterCardPackage): JsonObject {
        fun image(id: String?): JsonElement = id?.let {
            JsonPrimitive(digest(Base64.getDecoder().decode(pack.images.getValue(it).data)))
        } ?: JsonNull
        val card = pack.card
        return buildJsonObject {
            put("name", card.name)
            put("cover", image(card.coverImageResourceId))
            put("avatar", image(card.avatarResourceId))
            put("default", image(card.chatBackgroundResourceId))
            put("gallery", JsonArray(card.chatBackgroundImageResourceIds.map(::image)))
            put("galleryCount", card.chatBackgroundImageResourceIds.size)
            put("defaultIndex", card.chatBackgroundImageResourceIds.indexOf(card.chatBackgroundResourceId))
            put("peopleNames", JsonArray(card.characters.map { JsonPrimitive(it.name) }))
            put("peopleEnabled", JsonArray(card.characters.map { JsonPrimitive(it.desktopEnabled) }))
            put("peopleImages", JsonArray(card.characters.map { image(it.appearanceImageResourceId) }))
        }
    }

    @Test fun `twelve actual Android returns survive final Desktop production import reopen and both exports`(): Unit = runBlocking {
        val manifest = Json.parseToJsonElement(resource("origin.json").toString(Charsets.UTF_8)).jsonObject
        val expected = Json.parseToJsonElement(resource("expected.json").toString(Charsets.UTF_8)).jsonObject
        val records = manifest.getValue("files").jsonArray
        assertEquals(12, records.size)
        assertEquals(12, expected.size)
        val completed = mutableListOf<JsonElement>()
        for (entry in records) {
            val record = entry.jsonObject
            val name = record.getValue("file").jsonPrimitive.content
            val input = resource(name)
            assertEquals(record.getValue("bytes").jsonPrimitive.int, input.size, name)
            assertEquals(record.getValue("sha256").jsonPrimitive.content, digest(input), name)
            val wanted = expected.getValue(name).jsonObject
            val defaultIndex = if (name.contains("desktop-created-")) 3 else -1
            assertEquals(defaultIndex, wanted.getValue("defaultIndex").jsonPrimitive.int, name)
            withApp { app ->
                val decoded = if (name.endsWith(".json")) app.characterTransfers.decode(input.toString(Charsets.UTF_8))
                    else assertNotNull(app.characterTransfers.decodePng(input))
                assertEquals(wanted, state(decoded), name)
                val card = app.characterTransfers.importNew(decoded)
                assertEquals(wanted.getValue("name").jsonPrimitive.content, card.name)
                val reopened = CharacterRepository(JsonFileStorage(app.appDataRoot))
                assertEquals(card, reopened.getById(card.id), name)
                val full = app.characterTransfers.prepareExport(card.id).packageData
                assertEquals(11, full.schemaVersion)
                assertEquals(wanted, state(full), name)
                assertEquals(full.card.chatBackgroundImageResourceIds.size,
                    full.card.chatBackgroundImageResourceIds.distinct().size, name)
                val jsonBytes = app.transferJson.encodeToString(CharacterCardPackage.serializer(), full).toByteArray()
                val pngBytes = CharacterCardPngPackageCodec.attach(Base64.getDecoder().decode(
                    full.images.getValue(assertNotNull(full.card.coverImageResourceId)).data), full, app.transferJson)
                for ((format, output) in listOf("json" to jsonBytes, "png" to pngBytes)) withApp { next ->
                    val again = if (format == "json") next.characterTransfers.decode(output.toString(Charsets.UTF_8))
                        else assertNotNull(next.characterTransfers.decodePng(output))
                    assertEquals(wanted, state(again), "$name -> $format")
                    val imported = next.characterTransfers.importNew(again)
                    assertEquals(wanted, state(next.characterTransfers.prepareExport(imported.id).packageData),
                        "$name -> $format -> repository")
                }
                completed += buildJsonObject {
                    put("file", name); put("inputSha256", digest(input)); put("state", state(full))
                    put("reopened", true); put("jsonReimportPassed", true); put("pngReimportPassed", true)
                    put("exportedJsonSha256", digest(jsonBytes)); put("exportedPngSha256", digest(pngBytes))
                }
            }
        }
        System.getenv("CCB_COVER_GALLERY_REVIEW_DIR")?.let { directory ->
            Files.writeString(Files.createDirectories(Path.of(directory)).resolve("desktop-final-android-return.json"),
                buildJsonObject {
                    put("syntheticOnly", true); put("inputCount", completed.size)
                    put("androidProducer", "af23e4cb8a02c45ab2d5cb5791e66389e969547a")
                    put("cases", JsonArray(completed))
                }.toString())
        }
    }
}
