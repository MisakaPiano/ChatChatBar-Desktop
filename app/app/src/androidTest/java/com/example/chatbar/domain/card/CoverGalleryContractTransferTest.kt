package com.example.chatbar.domain.card

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Public synthetic fixtures shared with Desktop; no preset or user data. */
class CoverGalleryContractTransferTest {
    private lateinit var app: ChatBarApp
    private val cards = mutableListOf<String>()
    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
    }
    @After fun cleanup() = runBlocking<Unit> { cards.forEach { app.characterCardTransferService.deleteCard(it) } }

    @Test fun completeJsonAndPngKeepGalleryOrderOutsideDefaultAndIndependentCoverAcrossTwoCycles() = runBlocking<Unit> {
        val data = fixture("complete11.json")
        val imported = app.characterCardTransferService.importNew(data).also { cards += it.id }
        verify(imported, data)
        val output = File(app.getExternalFilesDir(null), "cover-gallery-evidence").apply { mkdirs() }
        for (png in listOf(false, true)) {
            var current = imported
            repeat(2) { cycle ->
                val text = if (!png) app.characterCardTransferService.exportJson(current.id) else null
                val bytes = if (png) app.characterCardTransferService.exportPng(current.id) else null
                if (cycle == 0) {
                    if (text != null) File(output, "android-complete11.json").writeText(text)
                    if (bytes != null) File(output, "android-complete11.png").writeBytes(bytes)
                }
                val next = if (text != null) app.characterCardTransferService.decode(text)
                    else requireNotNull(app.characterCardTransferService.decodePng(requireNotNull(bytes)))
                assertEquals(11, next.schemaVersion)
                current = app.characterCardTransferService.importNew(next).also { cards += it.id }
                verify(current, data)
            }
        }
    }

    @Test fun bothHistoricalTenVariantsImportWithoutInventingGalleryOrOriginal() = runBlocking<Unit> {
        for (name in listOf("android-gallery10.json", "desktop-cover10.json")) {
            val data = fixture(name)
            val card = app.characterCardTransferService.importNew(data).also { cards += it.id }
            assertEquals(data.card.coverImageResourceId != null, card.coverImage != null)
            assertEquals(data.card.chatBackgroundImageResourceIds.distinct().size, card.chatBackgroundImages.size)
            val exported = app.characterCardTransferService.decode(app.characterCardTransferService.exportJson(card.id))
            assertEquals(11, exported.schemaVersion)
            assertNotNull(exported.card.chatBackgroundResourceId)
        }
    }

    private fun fixture(name: String): CharacterCardPackage = InstrumentationRegistry.getInstrumentation().context.assets
        .open("cover-gallery-contract/$name").bufferedReader().use { app.characterCardTransferService.decode(it.readText()) }

    private fun verify(card: CharacterCard, source: CharacterCardPackage) {
        fun bytes(id: String) = Base64.getDecoder().decode(source.images.getValue(id).data)
        assertEquals(3, card.chatBackgroundImages.size)
        source.card.chatBackgroundImageResourceIds.distinct().zip(card.chatBackgroundImages).forEach { (id, path) ->
            assertArrayEquals(bytes(id), File(path).readBytes())
        }
        assertArrayEquals(bytes("default"), File(requireNotNull(card.chatBackground)).readBytes())
        assertFalse(card.chatBackground in card.chatBackgroundImages)
        assertArrayEquals(bytes("cover"), File(requireNotNull(card.coverImage)).readBytes())
        assertArrayEquals(bytes("avatar"), File(requireNotNull(card.avatar)).readBytes())
        assertArrayEquals(bytes("person"), File(requireNotNull(card.characters.single().appearanceImage)).readBytes())
        assertFalse(card.characters.single().enabled)
    }
}
