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

    @Test fun usingExistingAvatarCopiesItToDedicatedCoverStorage() = runBlocking<Unit> {
        val source = localImage(Color.BLUE)
        val original = source.readBytes()
        val assets = EditorDraftAssetService(app)
        val card = assets.materializeCharacterAssets(CharacterCard.create("synthetic").copy(
            avatar = source.absolutePath, coverImage = source.absolutePath, chatBackground = source.absolutePath))
        val cover = File(requireNotNull(card.coverImage)).also { files += it }
        assertNotEquals(source.absolutePath, cover.absolutePath)
        assertEquals("character-covers", cover.parentFile!!.name)
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(original, cover.readBytes())
        assertEquals(source.absolutePath, card.avatar)
        assertEquals(source.absolutePath, card.chatBackground)
    }

    @Test fun exportedImageResourcesCannotRetainStaleCharacterPayloads() = runBlocking<Unit> {
        val pixels = image(Color.BLUE)
        val original = PngTextChunks.insertTextChunk(pixels, "chara", "synthetic-old-card-text")
        val source = File(app.cacheDir, "synthetic-old-card-${UUID.randomUUID()}.png").also {
            it.writeBytes(original); files += it
        }
        val card = CharacterCard.create("synthetic-new-card").copy(avatar = source.absolutePath,
            chatBackground = source.absolutePath, chatBackgroundImages = listOf(source.absolutePath), coverImage = source.absolutePath)
        app.characterRepository.save(card); cards += card.id
        val exported = app.characterCardTransferService.exportPng(card.id)
        val decoded = requireNotNull(app.characterCardTransferService.decodePng(exported))
        for (resource in decoded.images.values) {
            val bytes = java.util.Base64.getDecoder().decode(resource.data)
            assertNull(PngTextChunks.extractTextChunk(bytes, "chara"))
            assertNull(PngTextChunks.extractTextChunk(bytes, PngTextChunks.CHATBAR_CHARACTER_KEYWORD))
            assertArrayEquals(pixels, bytes)
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun chosenBaseAndDisplayTextRoundTripWithoutChangingCardOrDefault() = runBlocking<Unit> {
        val original = image(Color.BLUE)
        val card = importLegacy(original)
        val selected = localImage(Color.GREEN)
        val background = localImage(Color.RED)
        val saved = card.copy(chatBackground = background.absolutePath,
            chatBackgroundImages = listOf(background.absolutePath, selected.absolutePath))
        app.characterRepository.save(saved)
        val beforeExport = app.characterRepository.getById(card.id)
        val options = CharacterCardPngExportOptions(coverImagePath = selected.absolutePath,
            preserveOriginalCover = false, displayText = "synthetic export title", sizePx = 1024)
        val exported = app.characterCardTransferService.exportPng(card.id, options)
        val decoded = requireNotNull(app.characterCardTransferService.decodePng(exported))
        assertEquals(card.name, decoded.card.name)
        val imported = app.characterCardTransferService.importNew(decoded).also { cards += it.id }
        assertArrayEquals(selected.readBytes(), File(requireNotNull(imported.coverImage)).readBytes())
        assertArrayEquals(background.readBytes(), File(requireNotNull(imported.chatBackground)).readBytes())
        assertEquals(2, imported.chatBackgroundImages.size)
        assertEquals(beforeExport, app.characterRepository.getById(card.id))
        assertArrayEquals(original, File(requireNotNull(card.coverImage)).readBytes())
        val base = app.characterCardTransferService.exportCoverBase(imported.id, CharacterCardPngExportOptions())
        assertEquals("image/png", base.mimeType)
        assertArrayEquals(selected.readBytes(), base.bytes)
    }

    @Test fun standaloneBaseStripsOnlyCardPayloadAndPreservesJpegBytes() = runBlocking<Unit> {
        val card = importLegacy(image(Color.BLUE))
        val pixels = image(Color.GREEN)
        val selected = localImage(Color.RED).also { it.writeBytes(PngTextChunks.insertTextChunk(pixels, "chara", "old synthetic")) }
        val png = app.characterCardTransferService.exportCoverBase(card.id,
            CharacterCardPngExportOptions(coverImagePath = selected.absolutePath))
        assertArrayEquals(pixels, png.bytes)
        assertNotNull(PngTextChunks.extractTextChunk(selected.readBytes(), "chara"))
        val bitmap = Bitmap.createBitmap(19, 11, Bitmap.Config.ARGB_8888)
        val jpeg = try { bitmap.eraseColor(Color.YELLOW); ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)); it.toByteArray()
        } } finally { bitmap.recycle() }
        selected.writeBytes(jpeg)
        val saved = app.characterCardTransferService.exportCoverBase(card.id,
            CharacterCardPngExportOptions(coverImagePath = selected.absolutePath))
        assertEquals("image/jpeg", saved.mimeType)
        assertEquals("jpg", saved.extension)
        assertArrayEquals(jpeg, saved.bytes)
        assertTrue(runCatching { app.characterCardTransferService.exportCoverBase(card.id,
            CharacterCardPngExportOptions(coverImagePath = selected.absolutePath + ".missing")) }.isFailure)
    }

    @Test fun compatibilityExportKeepsDefaultButExplicitlyOmitsUnsupportedResourcesAndGates() = runBlocking<Unit> {
        val card = importLegacy(image(Color.BLUE))
        val background = localImage(Color.RED)
        val gallery = localImage(Color.GREEN)
        app.characterRepository.save(card.copy(chatBackground = background.absolutePath,
            chatBackgroundImages = listOf(gallery.absolutePath),
            characters = listOf(com.example.chatbar.data.local.entity.CharacterInfo.create("synthetic").copy(enabled = false))))
        val png = app.characterCardTransferService.exportPng(card.id, CharacterCardPngExportOptions(legacyCompatibility = true))
        // Inspect the payload before PNG import deliberately captures the outer legacy picture as a new original.
        val raw = requireNotNull(PngTextChunks.extractTextChunk(png, PngTextChunks.CHATBAR_CHARACTER_KEYWORD))
        val decoded = app.characterCardTransferService.decode(if (raw.trimStart().startsWith("{")) raw
            else String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8))
        assertEquals(9, decoded.schemaVersion)
        assertNull(decoded.card.coverImageResourceId)
        assertTrue(decoded.card.chatBackgroundImageResourceIds.isEmpty())
        assertTrue(decoded.card.characters.single().enabled)
        assertArrayEquals(background.readBytes(), java.util.Base64.getDecoder().decode(
            decoded.images.getValue(requireNotNull(decoded.card.chatBackgroundResourceId)).data))
        assertFalse(decoded.images.values.any { java.util.Base64.getDecoder().decode(it.data).contentEquals(gallery.readBytes()) })
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
