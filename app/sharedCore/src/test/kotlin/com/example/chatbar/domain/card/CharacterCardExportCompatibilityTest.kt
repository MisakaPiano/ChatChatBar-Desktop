package com.example.chatbar.domain.card

import kotlinx.serialization.json.Json
import kotlin.test.*

class CharacterCardExportCompatibilityTest {
    private val source = CharacterCardPackage(schemaVersion = 10, exportedAt = 42,
        card = PackagedCharacterCard("Synthetic", avatarResourceId = "avatar", coverImageResourceId = "cover",
            chatBackgroundResourceId = "background", characters = listOf(PackagedCharacter("Person", appearanceImageResourceId = "person")),
            systemPrompt = "synthetic-system", postHistoryInstructions = "synthetic-history", freeformCharacterText = "synthetic-text"),
        documents = listOf(PackagedDocument("notes.txt", "txt", "synthetic-document")),
        images = listOf("avatar", "cover", "background", "person", "extra").associateWith { PackagedImage("$it.png", "synthetic-$it") },
        defaultFormatCard = FormatCardPackage(name = "Synthetic format", content = "synthetic-format"))

    @Test fun `official projection omits only independent cover data and leaves source untouched`() {
        val result = source.forExportCompatibility(CharacterCardExportCompatibility.OFFICIAL_V9)
        assertEquals(source.copy(schemaVersion = 9, card = source.card.copy(coverImageResourceId = null), images = source.images - "cover"), result)
        val wire = Json { encodeDefaults = true }.encodeToString(CharacterCardPackage.serializer(), result)
        assertFalse(wire.contains("coverImageResourceId"))
        result.validateForImport()
        assertEquals("cover", source.card.coverImageResourceId)
        assertTrue(source.images.containsKey("cover"))
    }

    @Test fun `cover aliases retained avatar background or person image are not removed`() {
        for (alias in listOf("avatar", "background", "person")) {
            val aliased = source.copy(card = source.card.copy(coverImageResourceId = alias))
            val result = aliased.forExportCompatibility(CharacterCardExportCompatibility.OFFICIAL_V9)
            assertEquals(aliased.images, result.images)
            result.validateForImport()
        }
    }

    @Test fun `explicit full projection preserves every field and independent image`() {
        assertEquals(source.copy(schemaVersion = 11), source.forExportCompatibility(CharacterCardExportCompatibility.COMPLETE_V11))
        source.validateForImport()
    }

    @Test fun `complete gallery accepts ordered duplicate refs and an independent default while compatible projection retains aliases`() {
        val full = source.copy(schemaVersion = 11, card = source.card.copy(
            chatBackgroundImageResourceIds = listOf("extra", "avatar", "extra"),
            characters = source.card.characters.map { it.copy(desktopEnabled = false) }))
        full.validateForImport()
        val old = full.forExportCompatibility(CharacterCardExportCompatibility.OFFICIAL_V9)
        old.validateForImport()
        assertEquals(listOf("extra", "avatar", "extra"), full.card.chatBackgroundImageResourceIds)
        assertTrue(old.card.chatBackgroundImageResourceIds.isEmpty())
        assertTrue(old.card.characters.all { it.desktopEnabled })
        assertEquals("background", old.card.chatBackgroundResourceId)
        assertTrue("avatar" in old.images); assertFalse("extra" in old.images); assertFalse("cover" in old.images)
        assertFails { full.copy(schemaVersion = 9).validateForImport() }
        assertFails { full.copy(card = full.card.copy(chatBackgroundImageResourceIds = listOf("missing"))).validateForImport() }
        full.copy(schemaVersion = 10).validateForImport() // Android gallery10 and Desktop cover10 are field-based reads.
    }
}
