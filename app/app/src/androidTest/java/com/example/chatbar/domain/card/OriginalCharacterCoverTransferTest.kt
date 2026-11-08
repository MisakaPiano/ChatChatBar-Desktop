package com.example.chatbar.domain.card

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.draft.EditorDraftAssetService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalCharacterCoverTransferTest {
    private lateinit var app: ChatBarApp
    private val cards = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Before fun setup() {
        runBlocking {
            app = ApplicationProvider.getApplicationContext()
            withTimeout(30_000) { app.dependenciesReady.first { it } }
        }
    }

    @After fun cleanup() {
        runBlocking {
            cards.forEach { app.characterCardTransferService.deleteCard(it) }
            files.forEach { it.delete() }
        }
    }

    @Test fun textAndBackgroundEditsExportLatestDataWithoutChangingOriginalFile() = runBlocking<Unit> {
        val original = image(Color.BLUE)
        val card = importLegacy(original)
        val source = File(requireNotNull(card.coverImage))
        assertEquals("character-covers", source.parentFile!!.name)
        assertArrayEquals(original, source.readBytes())
        val background = localImage(Color.RED)
        app.characterRepository.save(card.copy(name = "更新后的合成卡", greeting = "更新后的开场", chatBackground = background.absolutePath))
        val exported = app.characterCardTransferService.exportPng(card.id)
        assertArrayEquals(original, OriginalCharacterCover.imageOnlyPng(exported))
        assertArrayEquals(original, source.readBytes())
        val decoded = requireNotNull(app.characterCardTransferService.decodePng(exported))
        assertEquals("更新后的合成卡", decoded.card.name)
        assertEquals("更新后的开场", decoded.card.greeting)
        assertNotEquals(decoded.card.coverImageResourceId, decoded.card.chatBackgroundResourceId)
    }

    @Test fun renderedExportReimportRetainsIndependentOriginalRatherThanOuterComposite() = runBlocking<Unit> {
        val original = image(Color.BLUE)
        val card = importLegacy(original)
        val background = localImage(Color.RED)
        app.characterRepository.save(card.copy(chatBackground = background.absolutePath))
        val composite = app.characterCardTransferService.exportPng(card.id,
            CharacterCardPngExportOptions(preserveOriginalCover = false, sizePx = 1024))
        assertFalse(original.contentEquals(OriginalCharacterCover.imageOnlyPng(composite)))
        val rendered = requireNotNull(BitmapFactory.decodeByteArray(composite, 0, composite.size))
        try { assertEquals(Color.BLUE, rendered.getPixel(512, 100)) } finally { rendered.recycle() }
        val decoded = requireNotNull(app.characterCardTransferService.decodePng(composite))
        val imported = app.characterCardTransferService.importNew(decoded).also { cards += it.id }
        assertArrayEquals(original, File(requireNotNull(imported.coverImage)).readBytes())
        assertArrayEquals(original, OriginalCharacterCover.imageOnlyPng(app.characterCardTransferService.exportPng(imported.id)))
        assertArrayEquals(original, File(requireNotNull(card.coverImage)).readBytes())
    }

    @Test fun jsonTransferAndDuplicateOwnSeparateOriginalCopies() = runBlocking<Unit> {
        val original = image(Color.BLUE)
        val card = importLegacy(original)
        val transferred = app.characterCardTransferService.importNew(
            app.characterCardTransferService.decode(app.characterCardTransferService.exportJson(card.id)))
            .also { cards += it.id }
        val duplicate = app.characterCardTransferService.duplicate(card.id).also { cards += it.id }
        assertEquals(3, listOf(card.coverImage, transferred.coverImage, duplicate.coverImage).toSet().size)
        for (item in listOf(card, transferred, duplicate)) {
            assertArrayEquals(original, File(requireNotNull(item.coverImage)).readBytes())
        }
        app.characterCardTransferService.deleteCard(card.id)
        cards.remove(card.id)
        assertArrayEquals(original, File(requireNotNull(duplicate.coverImage)).readBytes())
        assertArrayEquals(original, File(requireNotNull(transferred.coverImage)).readBytes())
    }

    @Test fun draftMaterializationCopiesOriginalIntoDedicatedDirectoryWithoutEditingDraftSource() = runBlocking<Unit> {
        val assets = EditorDraftAssetService(app)
        val session = UUID.randomUUID().toString()
        try {
            val staged = assets.newDraftFile(session, "synthetic-original.png")
            val bytes = image(Color.BLUE)
            staged.writeBytes(bytes)
            val materialized = assets.materializeCharacterAssets(CharacterCard.create("synthetic").copy(coverImage = staged.absolutePath))
            val persisted = File(requireNotNull(materialized.coverImage)).also { files += it }
            assertEquals("character-covers", persisted.parentFile!!.name)
            assertNotEquals(staged.absolutePath, persisted.absolutePath)
            assertArrayEquals(bytes, staged.readBytes())
            assertArrayEquals(bytes, persisted.readBytes())
            assertTrue(persisted.absolutePath in assets.ownedAssetPaths(materialized))
        } finally { assets.deleteDraft(session) }
    }

    private suspend fun importLegacy(original: ByteArray): CharacterCard {
        val raw = json.encodeToString(CharacterCardPackage.serializer(),
            CharacterCardPackage(schemaVersion = 9, card = PackagedCharacterCard("合成原封面卡")))
        val png = PngTextChunks.insertTextChunk(original, PngTextChunks.CHATBAR_CHARACTER_KEYWORD, raw)
        return app.characterCardTransferService.importNew(
            requireNotNull(app.characterCardTransferService.decodePng(png)))
            .also { cards += it.id }
    }

    private fun localImage(color: Int) = File(app.cacheDir, "cover-fixture-${UUID.randomUUID()}.png")
        .also { it.writeBytes(image(color)); files += it }

    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
