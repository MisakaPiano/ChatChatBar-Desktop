package com.example.chatbar.domain.card

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterCardPngExportOptionsTest {
    @Test
    fun normalized_clampsEachExportControlIndependently() {
        val normalized = CharacterCardPngExportOptions(
            sizePx = -1,
            gradientHeight = -1f,
            gradientStrength = 2f,
            logoScale = -1f,
            titleScale = 2f,
            cropCenterX = -1f,
            cropCenterY = 2f,
            cropZoom = 20f
        ).normalized()

        assertEquals(1024, normalized.sizePx)
        assertEquals(0.25f, normalized.gradientHeight)
        assertEquals(0.9f, normalized.gradientStrength)
        assertEquals(0.07f, normalized.logoScale)
        assertEquals(0.08f, normalized.titleScale)
        assertEquals(0f, normalized.cropCenterX)
        assertEquals(1f, normalized.cropCenterY)
        assertEquals(6f, normalized.cropZoom)
    }

    @Test
    fun `shared codec attaches exact ChatBar keyword and decodable package`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val packageData = CharacterCardPackage(card = PackagedCharacterCard(name = "Codec"))
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
        )

        val attached = CharacterCardPngPackageCodec.attach(png, packageData, json)
        val payload = PngTextChunks.extractTextChunk(attached, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)
        val decodedJson = String(Base64.getDecoder().decode(requireNotNull(payload)), Charsets.UTF_8)

        assertEquals(packageData.card.name, json.decodeFromString(CharacterCardPackage.serializer(), decodedJson).card.name)
    }
}
