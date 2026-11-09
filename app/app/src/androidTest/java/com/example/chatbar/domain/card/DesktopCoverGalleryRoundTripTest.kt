package com.example.chatbar.domain.card

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Only exact, hash-bound synthetic output from the real Desktop production reader/exporter. */
class DesktopCoverGalleryRoundTripTest {
    private lateinit var app: ChatBarApp
    private val cards = mutableListOf<String>()
    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
    }
    @After fun cleanup() = runBlocking<Unit> { cards.forEach { app.characterCardTransferService.deleteCard(it) } }

    @Test fun desktopActualJsonAndPngKeepAllResourcesDefaultAndPersonGatesThroughAndroidProduction() = runBlocking<Unit> {
        val folder = "desktop-cover-gallery"
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val origin = assets.open("$folder/origin.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val entries = origin.getValue("files").jsonArray
        require(entries.isNotEmpty()) { "Desktop production fixtures are required" }
        val origins = entries.map { it.jsonObject.getValue("originCase").jsonPrimitive.content }.toSet()
        assertEquals(setOf("android-origin", "desktop-origin"), origins)
        for (originCase in origins) {
            val extensions = entries.filter { it.jsonObject.getValue("originCase").jsonPrimitive.content == originCase }
                .map { it.jsonObject.getValue("file").jsonPrimitive.content.substringAfterLast('.') }.toSet()
            assertEquals(setOf("json", "png"), extensions)
        }
        val out = File(app.getExternalFilesDir(null), "desktop-cover-gallery-evidence").apply { mkdirs() }
        val produced = mutableListOf<File>()
        for (entry in entries) {
            val item = entry.jsonObject
            val name = item.getValue("file").jsonPrimitive.content
            val bytes = assets.open("$folder/$name").use { it.readBytes() }
            assertEquals(item.getValue("bytes").jsonPrimitive.int, bytes.size)
            assertEquals(item.getValue("sha256").jsonPrimitive.content,
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val source = if (name.endsWith(".json")) app.characterCardTransferService.decode(bytes.toString(Charsets.UTF_8))
                else requireNotNull(app.characterCardTransferService.decodePng(bytes))
            assertEquals(11, source.schemaVersion)
            val card = app.characterCardTransferService.importNew(source).also { cards += it.id }
            verify(card, source)
            val json = app.characterCardTransferService.exportJson(card.id)
            val png = app.characterCardTransferService.exportPng(card.id)
            val prefix = name.substringBeforeLast('.')
            File(out, "android-after-$prefix-from-${name.substringAfterLast('.')}.json").also { it.writeText(json); produced += it }
            File(out, "android-after-$prefix-from-${name.substringAfterLast('.')}.png").also { it.writeBytes(png); produced += it }
            // Each leg is a fresh destination: normal duplicate-import naming is a separate policy.
            app.characterCardTransferService.deleteCard(card.id)
            cards.remove(card.id)
            for (roundTrip in listOf(app.characterCardTransferService.decode(json),
                requireNotNull(app.characterCardTransferService.decodePng(png)))) {
                assertEquals(11, roundTrip.schemaVersion)
                val imported = app.characterCardTransferService.importNew(roundTrip).also { cards += it.id }
                verify(imported, source)
                app.characterCardTransferService.deleteCard(imported.id)
                cards.remove(imported.id)
            }
        }
        val manifest = buildJsonObject {
            put("syntheticOnly", true)
            put("desktopProducerSource", origin.getValue("desktopProducerSource"))
            put("desktopProducerRun", origin.getValue("desktopProducerRun"))
            put("inputs", entries)
            put("files", JsonArray(produced.map { file -> buildJsonObject {
                put("file", file.name); put("bytes", file.length())
                put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            } }))
        }
        File(out, "android-return-origin.json").writeText(manifest.toString())
    }

    private fun verify(card: CharacterCard, source: CharacterCardPackage) {
        fun resource(id: String) = Base64.getDecoder().decode(source.images.getValue(id).data)
        fun image(id: String?, path: String?) {
            assertEquals(id == null, path == null)
            if (id != null) assertArrayEquals(resource(id), File(requireNotNull(path)).readBytes())
        }
        assertEquals(source.card.name, card.name)
        assertEquals(source.card.characters.map { it.name }, card.characters.map { it.name })
        assertEquals(source.card.characters.map { it.enabled }, card.characters.map { it.enabled })
        image(source.card.coverImageResourceId, card.coverImage)
        image(source.card.avatarResourceId, card.avatar)
        image(source.card.chatBackgroundResourceId, card.chatBackground)
        val refs = source.card.chatBackgroundImageResourceIds.distinct()
        assertEquals(refs.size, card.chatBackgroundImages.size)
        refs.zip(card.chatBackgroundImages).forEach { (id, path) -> image(id, path) }
        assertEquals(refs.indexOf(source.card.chatBackgroundResourceId), card.chatBackgroundImages.indexOf(card.chatBackground))
        source.card.characters.zip(card.characters).forEach { (before, after) -> image(before.appearanceImageResourceId, after.appearanceImage) }
    }
}
