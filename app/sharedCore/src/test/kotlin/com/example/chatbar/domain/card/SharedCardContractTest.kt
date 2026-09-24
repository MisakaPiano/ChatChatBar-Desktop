package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.domain.image.NovelAiImageModel
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedCardContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `character schemas three through eight retain legacy defaults`() {
        (3..8).forEach { schema ->
            val decoded = json.decodeFromString(
                CharacterCardPackage.serializer(),
                """{"schemaVersion":$schema,"card":{"name":"Legacy"}}"""
            )

            decoded.validateForImport()

            assertEquals(schema, decoded.schemaVersion)
            assertEquals("", decoded.card.botName)
            assertEquals(CharacterEditMode.STRUCTURED, decoded.card.editMode)
            assertNull(decoded.card.defaultNovelAiImageModel)
            assertNull(decoded.defaultFormatCard)
        }
    }

    @Test
    fun `character schema nine round trips current fields and embedded default format`() {
        val packageData = CharacterCardPackage(
            exportedAt = 42L,
            card = PackagedCharacterCard(
                name = "Current",
                botName = "Speaker",
                defaultNovelAiImageModel = NovelAiImageModel.V5_FULL,
                characters = listOf(
                    PackagedCharacter(
                        name = "Alice",
                        fishAudioVoice = FishAudioVoiceBinding("voice", "Voice")
                    )
                )
            ),
            defaultFormatCard = FormatCardPackage(
                exportedAt = 43L,
                name = "Default format",
                content = "Requirements",
                userTools = listOf(FormatCardUserToolConfig.randomNumber())
            )
        )

        val encoded = json.encodeToString(CharacterCardPackage.serializer(), packageData)
        val decoded = json.decodeFromString(CharacterCardPackage.serializer(), encoded)

        decoded.validateForImport()
        assertEquals(9, decoded.schemaVersion)
        assertEquals(packageData, decoded)
        assertTrue(encoded.contains("\"defaultFormatCard\""))
    }

    @Test
    fun `character package rejects missing resources and filters only empty placeholders`() {
        val missingResource = CharacterCardPackage(
            card = PackagedCharacterCard(name = "Card", avatarResourceId = "missing")
        )
        assertFailsWith<IllegalArgumentException> { missingResource.validateForImport() }

        val normalized = CharacterCardPackage(
            card = PackagedCharacterCard(
                name = "Card",
                characters = listOf(
                    PackagedCharacter(name = ""),
                    PackagedCharacter(name = "", profile = "Must remain"),
                    PackagedCharacter(name = "Alice")
                )
            )
        ).withoutEmptyCharacterPlaceholders()

        assertEquals(2, normalized.card.characters.size)
        assertEquals("Must remain", normalized.card.characters.first().profile)
        assertFailsWith<IllegalArgumentException> { normalized.validateForImport() }
    }

    @Test
    fun `format schemas one and two preserve defaults and ordered tools`() {
        val legacy = json.decodeFromString(
            FormatCardPackage.serializer(),
            """{"schemaVersion":1,"name":"Legacy","content":"Rules"}"""
        )
        legacy.validateForImport()
        assertEquals(emptyList(), legacy.userTools)

        val current = FormatCardPackage(
            exportedAt = 7L,
            name = "Current",
            content = "Rules",
            userTools = listOf(
                FormatCardUserToolConfig.strongPromptSuffix().copy(text = "First"),
                FormatCardUserToolConfig.randomNumber().copy(minimum = "-2", maximum = "2")
            )
        )
        val decoded = json.decodeFromString(
            FormatCardPackage.serializer(),
            json.encodeToString(FormatCardPackage.serializer(), current)
        )
        decoded.validateForImport()

        assertEquals(2, decoded.schemaVersion)
        assertEquals(current.userTools, decoded.userTools)
        assertEquals(
            listOf(FormatCardUserToolType.STRONG_PROMPT_SUFFIX, FormatCardUserToolType.RANDOM_NUMBER),
            decoded.userTools.map(FormatCardUserToolConfig::type)
        )
    }

    @Test
    fun `format validation is authoritative for invalid random bounds and blank strong suffix`() {
        assertFailsWith<IllegalArgumentException> {
            FormatCardPackage(
                name = "Invalid",
                content = "Rules",
                userTools = listOf(FormatCardUserToolConfig.randomNumber().copy(minimum = "10", maximum = "2"))
            ).validateForImport()
        }
        assertFailsWith<IllegalArgumentException> {
            FormatCardPackage(
                name = "Invalid",
                content = "Rules",
                userTools = listOf(FormatCardUserToolConfig.strongPromptSuffix())
            ).validateForImport()
        }
    }

    @Test
    fun `world book schema one and entity shape round trip`() {
        val packageData = WorldBookPackage(
            exportedAt = 9L,
            book = WorldBook(
                id = "book",
                name = "Lore",
                scanDepth = 5,
                recursiveScanning = true,
                entries = listOf(
                    WorldBookEntry(
                        id = "entry",
                        name = "Gate",
                        keys = listOf("door"),
                        content = "Open",
                        position = WorldBookPosition.AFTER_CHAR,
                        probability = 75,
                        useRegex = true,
                        extensions = "{\"future\":true}"
                    )
                )
            )
        )
        val encoded = json.encodeToString(WorldBookPackage.serializer(), packageData)
        val decoded = json.decodeFromString(WorldBookPackage.serializer(), encoded)

        decoded.validateForImport()
        assertEquals(1, decoded.schemaVersion)
        assertEquals(packageData, decoded)
        assertTrue(encoded.contains("\"probability\":75"))
        assertTrue(encoded.contains("\"useRegex\":true"))
    }

    @Test
    fun `png text metadata round trips package JSON semantics`() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        )
        val packageData = CharacterCardPackage(
            exportedAt = 1L,
            card = PackagedCharacterCard(name = "PNG", characters = listOf(PackagedCharacter(name = "Alice")))
        )
        val packageJson = json.encodeToString(CharacterCardPackage.serializer(), packageData)
        val payload = Base64.getEncoder().encodeToString(packageJson.toByteArray(Charsets.UTF_8))

        val withMetadata = PngTextChunks.insertTextChunk(
            png,
            PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
            payload
        )
        val extracted = requireNotNull(
            PngTextChunks.extractTextChunk(withMetadata, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)
        )
        val decoded = json.decodeFromString(
            CharacterCardPackage.serializer(),
            String(Base64.getDecoder().decode(extracted), Charsets.UTF_8)
        )

        assertTrue(PngTextChunks.isPng(withMetadata))
        assertFalse(withMetadata.contentEquals(png))
        assertEquals(packageData, decoded)
    }
}
