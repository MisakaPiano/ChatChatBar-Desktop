package com.example.chatbar.domain.card

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OriginalCharacterCoverTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun stripsAllCardPayloadKindsAndKeepsEveryOtherChunkByteForByte() {
        val image = png(listOf(chunk("tEXt", "Author\u0000synthetic".toByteArray())))
        val oldCard = addBeforeEnd(image, listOf(
            chunk("tEXt", "ChatBarCharacter\u0000old-native".toByteArray()),
            chunk("zTXt", "ChArA\u0000\u0000old-compressed".toByteArray()),
            chunk("iTXt", "ccv3\u0000\u0000\u0000\u0000\u0000old-international".toByteArray())
        ))
        val before = oldCard.copyOf()
        assertArrayEquals(image, OriginalCharacterCover.imageOnlyPng(oldCard))
        assertArrayEquals(before, oldCard)
        assertEquals("synthetic", PngTextChunks.extractTextChunk(image, "Author"))
    }

    @Test fun repeatedTextExportsKeepOriginalImageAndOnlyCurrentPayload() {
        val image = png()
        val original = PngTextChunks.insertTextChunk(image, "chara", "old-card")
        var exported = original
        repeat(5) { index ->
            val text = "{\"name\":\"synthetic-$index\"}"
            exported = OriginalCharacterCover.exportPng(exported, text)
            assertArrayEquals(image, OriginalCharacterCover.imageOnlyPng(exported))
            assertNull(PngTextChunks.extractTextChunk(exported, "chara"))
            assertEquals(text, String(Base64.getDecoder().decode(
                PngTextChunks.extractTextChunk(exported, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)
            ), Charsets.UTF_8))
        }
        assertEquals("old-card", PngTextChunks.extractTextChunk(original, "chara"))
    }

    @Test fun importedLegacyPngCreatesIndependentCoverWithoutOverwritingResourceIds() {
        val source = CharacterCardPackage(schemaVersion = 9, card = PackagedCharacterCard("合成卡"),
            images = mapOf("cover-original" to PackagedImage("unrelated.png", "synthetic")))
        val original = png()
        val imported = OriginalCharacterCover.attachToImportedPackage(source,
            PngTextChunks.insertTextChunk(original, "ChatBarCharacter", "old-payload"))
        val id = requireNotNull(imported.card.coverImageResourceId)
        assertNotEquals("cover-original", id)
        assertEquals(source.images["cover-original"], imported.images["cover-original"])
        assertArrayEquals(original, Base64.getDecoder().decode(imported.images.getValue(id).data))
        assertNull(source.card.coverImageResourceId)
        imported.validateForImport()
    }

    @Test fun embeddedOriginalWinsOverOuterExportedCompositionWithoutRecursiveGrowth() {
        val original = png()
        val first = OriginalCharacterCover.attachToImportedPackage(
            CharacterCardPackage(card = PackagedCharacterCard("合成卡")), original)
        var current = first
        repeat(5) {
            val outer = OriginalCharacterCover.exportPng(original,
                json.encodeToString(CharacterCardPackage.serializer(), current))
            current = OriginalCharacterCover.attachToImportedPackage(current, outer)
        }
        assertSame(first, current)
        assertEquals(1, current.images.size)
        assertArrayEquals(original, Base64.getDecoder().decode(current.images.values.single().data))
    }

    @Test fun rejectsTruncationCrcDamageAndTrailingData() {
        val image = png()
        for (broken in listOf(image.copyOf(image.size - 1), image + byteArrayOf(1),
            image.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })) {
            assertThrows(IllegalArgumentException::class.java) { OriginalCharacterCover.imageOnlyPng(broken) }
        }
    }

    @Test fun oldPackagesRemainReadableAndNewCoverRequiresItsResource() {
        json.decodeFromString(CharacterCardPackage.serializer(),
            """{"schemaVersion":9,"card":{"name":"synthetic"}}""").also {
            it.validateForImport()
            assertNull(it.card.coverImageResourceId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardPackage(card = PackagedCharacterCard("synthetic", coverImageResourceId = "missing"))
                .validateForImport()
        }
    }

    @Test fun originalCoverIsAPortableBackupResource() {
        assertTrue(com.example.chatbar.domain.backup.BackupDataRegistry.isResourceField("coverImage", emptyList()))
        assertFalse(com.example.chatbar.domain.backup.BackupDataRegistry.isResourceField("coverImage", listOf("metadata")))
    }

    private fun png(extra: List<ByteArray> = emptyList()): ByteArray {
        val header = ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
            it.writeInt(1); it.writeInt(1); it.write(byteArrayOf(8, 6, 0, 0, 0))
        } }.toByteArray()
        val pixels = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(byteArrayOf(0, 12, 34, 56, -1)) }
        }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) +
            extra.fold(byteArrayOf()) { a, b -> a + b } + chunk("IDAT", pixels) + chunk("IEND", byteArrayOf())
    }

    private fun addBeforeEnd(png: ByteArray, chunks: List<ByteArray>) = png.copyOf(png.size - 12) +
        chunks.fold(byteArrayOf()) { a, b -> a + b } + png.copyOfRange(png.size - 12, png.size)

    private fun chunk(type: String, data: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { stream ->
            val name = type.toByteArray(Charsets.US_ASCII)
            stream.writeInt(data.size); stream.write(name); stream.write(data)
            stream.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        }
    }.toByteArray()
}
