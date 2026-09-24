package com.example.chatbar.domain.card

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import com.example.chatbar.data.repository.WorldBookRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldBookTransferServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("world-book-transfer-")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `native package schema one exports and decodes repository entity`() = runTest {
        val repository = repository()
        val transfer = WorldBookTransferService(repository, json)
        repository.save(sampleBook(id = "native", name = "Native"))
        val persisted = requireNotNull(repository.getById("native"))

        val decoded = transfer.decode(transfer.exportJson("native"))

        assertEquals(1, decoded.schemaVersion)
        assertEquals(persisted, decoded.book)
    }

    @Test
    fun `silly tavern object entries decode all supported fields and preserve raw json`() {
        val decoded = decodeOnly().decode(
            """
            {
              "name": "ST World",
              "description": "Description",
              "scanDepth": 8,
              "tokenBudget": 4096,
              "recursiveScanning": true,
              "caseSensitive": true,
              "matchWholeWords": true,
              "sourcePresetKey": "preset",
              "sourcePresetVersion": 3,
              "entries": {
                "7": {
                  "comment": "Gate",
                  "key": ["door", "/gate/i"],
                  "keysecondary": ["silver"],
                  "content": "Unlocked lore",
                  "order": 42,
                  "priority": 9,
                  "position": "outlet",
                  "disable": false,
                  "constant": true,
                  "selective": true,
                  "selectiveLogic": 3,
                  "caseSensitive": false,
                  "matchWholeWords": false,
                  "depth": 4,
                  "role": "system",
                  "ignoreBudget": true,
                  "excludeRecursion": true,
                  "preventRecursion": true,
                  "delayUntilRecursion": true,
                  "recursionLevel": 2,
                  "matchCharacterDescription": true,
                  "matchCharacterPersonality": true,
                  "matchScenario": true,
                  "matchCreatorNotes": true,
                  "matchPersonaDescription": true,
                  "probability": 75,
                  "group": "locks",
                  "groupWeight": 80,
                  "sticky": 2,
                  "cooldown": 3,
                  "delay": 4,
                  "outletName": "knowledge",
                  "characterFilter": {"names":["Alice"],"isExclude":true},
                  "extensions": {"future":"kept"}
                }
              }
            }
            """.trimIndent()
        )

        val book = decoded.book
        val entry = book.entries.single()
        assertEquals("ST World", book.name)
        assertEquals("Description", book.description)
        assertEquals(8, book.scanDepth)
        assertEquals(4096, book.tokenBudget)
        assertTrue(book.recursiveScanning)
        assertTrue(book.caseSensitive)
        assertTrue(book.matchWholeWords)
        assertEquals("preset", book.sourcePresetKey)
        assertEquals(3, book.sourcePresetVersion)
        assertEquals(listOf("door", "/gate/i"), entry.keys)
        assertEquals(listOf("silver"), entry.secondaryKeys)
        assertEquals(42, entry.insertionOrder)
        assertEquals(9, entry.priority)
        assertEquals(WorldBookPosition.OUTLET, entry.position)
        assertEquals("outlet", entry.originalPosition)
        assertTrue(entry.enabled)
        assertTrue(entry.constant)
        assertTrue(entry.selective)
        assertEquals(WorldBookSelectiveLogic.AND_ALL.value, entry.selectiveLogic)
        assertEquals(false, entry.caseSensitive)
        assertEquals(false, entry.matchWholeWords)
        assertEquals(4, entry.scanDepth)
        assertEquals("system", entry.role)
        assertTrue(entry.ignoreBudget)
        assertTrue(entry.excludeRecursion)
        assertTrue(entry.preventRecursion)
        assertTrue(entry.delayUntilRecursion)
        assertEquals(2, entry.recursionLevel)
        assertTrue(entry.matchCharacterDescription)
        assertTrue(entry.matchCharacterPersonality)
        assertTrue(entry.matchScenario)
        assertTrue(entry.matchCreatorNotes)
        assertTrue(entry.matchPersonaDescription)
        assertEquals(75, entry.probability)
        assertEquals("locks", entry.group)
        assertEquals(80, entry.groupWeight)
        assertEquals(2, entry.sticky)
        assertEquals(3, entry.cooldown)
        assertEquals(4, entry.delay)
        assertTrue(entry.useRegex)
        assertEquals("knowledge", entry.outletName)
        assertEquals(listOf("Alice"), entry.characterFilter)
        assertTrue(entry.characterFilterExclude)
        assertTrue(entry.extensions.contains("\"uid\":\"7\""))
        assertTrue(entry.extensions.contains("rawJson"))
        assertTrue(entry.extensions.contains("future"))
    }

    @Test
    fun `silly tavern object uses fallback name and default scan depth`() {
        val decoded = decodeOnly().decode("""{"entries":{}}""", fallbackName = "Fallback")

        assertEquals("Fallback", decoded.book.name)
        assertEquals(10, decoded.book.scanDepth)
    }

    @Test
    fun `character book array entries decode embedded fields`() {
        val book = decodeOnly().decodeCharacterBook(
            """
            {
              "name": "Character Book",
              "scan_depth": 6,
              "token_budget": 1200,
              "recursive_scanning": true,
              "case_sensitive": true,
              "match_whole_words": true,
              "entries": [
                {
                  "name": "Moon",
                  "keys": ["moon"],
                  "secondary_keys": ["night"],
                  "content": "Moon lore",
                  "insertion_order": 9,
                  "position": "before_char",
                  "enabled": false
                }
              ]
            }
            """.trimIndent(),
            fallbackName = "Fallback"
        )

        val entry = book.entries.single()
        assertEquals("Character Book", book.name)
        assertEquals(6, book.scanDepth)
        assertEquals(1200, book.tokenBudget)
        assertTrue(book.recursiveScanning)
        assertTrue(book.caseSensitive)
        assertTrue(book.matchWholeWords)
        assertEquals(listOf("moon"), entry.keys)
        assertEquals(listOf("night"), entry.secondaryKeys)
        assertEquals(9, entry.insertionOrder)
        assertEquals(WorldBookPosition.BEFORE_CHAR, entry.position)
        assertFalse(entry.enabled)
    }

    @Test
    fun `silly tavern export uses object entries and preserves mapped fields`() = runTest {
        val repository = repository()
        val transfer = WorldBookTransferService(repository, json)
        repository.save(
            sampleBook(
                id = "export",
                name = "Export",
                entries = listOf(
                    sampleEntry(
                        id = "entry",
                        position = WorldBookPosition.AFTER_CHAR,
                        originalPosition = null,
                        characterFilter = listOf("Alice"),
                        characterFilterExclude = true
                    )
                )
            )
        )

        val exported = json.parseToJsonElement(transfer.exportSillyTavernJson("export")).jsonObject
        val entry = exported.getValue("entries").jsonObject.getValue("0").jsonObject

        assertEquals("Export", exported.getValue("name").jsonPrimitive.content)
        assertEquals(5, exported.getValue("scanDepth").jsonPrimitive.int)
        assertTrue(exported.getValue("recursiveScanning").jsonPrimitive.boolean)
        assertEquals("after_char", entry.getValue("position").jsonPrimitive.content)
        assertEquals(listOf("key"), entry.getValue("key").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(75, entry.getValue("probability").jsonPrimitive.int)
        assertEquals("group", entry.getValue("group").jsonPrimitive.content)
        assertTrue(entry.getValue("useRegex").jsonPrimitive.boolean)
        val filter = entry.getValue("characterFilter").jsonObject
        assertEquals(listOf("Alice"), filter.getValue("names").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(filter.getValue("isExclude").jsonPrimitive.boolean)
    }

    @Test
    fun `unsupported lorebook shapes retain explicit error`() {
        listOf(
            """{"kind":"novelai","entries":{}}""",
            """{"lorebookVersion":2,"entries":{}}""",
            """{"items":[]}"""
        ).forEach { raw ->
            val error = assertFailsWith<IllegalStateException> { decodeOnly().decode(raw) }
            assertTrue(error.message.orEmpty().contains("暂不支持 NovelAI / Agnai / Risu"))
        }
    }

    @Test
    fun `duplicate creates fresh book and entry identities and clears preset provenance`() = runTest {
        val repository = repository()
        val transfer = WorldBookTransferService(repository, json)
        val source = sampleBook(
            id = "source",
            name = "Lore",
            sourcePresetKey = "preset",
            sourcePresetVersion = 2,
            createdAt = 1L,
            updatedAt = 1L
        )
        repository.save(source)
        repository.save(sampleBook(id = "copy-2", name = "Lore (2)"))

        val duplicate = transfer.duplicate(source.id)

        assertNotEquals(source.id, duplicate.id)
        assertEquals("Lore (3)", duplicate.name)
        assertNotEquals(source.entries.single().id, duplicate.entries.single().id)
        assertNull(duplicate.sourcePresetKey)
        assertNull(duplicate.sourcePresetVersion)
        assertTrue(duplicate.createdAt > source.createdAt)
        assertTrue(duplicate.updatedAt >= duplicate.createdAt)
    }

    @Test
    fun `import new resolves conflict and creates fresh book and entry identities`() = runTest {
        val repository = repository()
        val transfer = WorldBookTransferService(repository, json)
        val existing = sampleBook(id = "existing", name = " Lore ")
        repository.save(existing)
        val persistedExisting = requireNotNull(repository.getById(existing.id))
        val incoming = sampleBook(
            id = "incoming",
            name = "Ignored by requested name",
            sourcePresetKey = "incoming-preset",
            sourcePresetVersion = 4,
            createdAt = 2L,
            updatedAt = 2L
        )

        val imported = transfer.importNew(WorldBookPackage(book = incoming), requestedName = "lore")

        assertNotEquals(incoming.id, imported.id)
        assertEquals("lore (2)", imported.name)
        assertNotEquals(incoming.entries.single().id, imported.entries.single().id)
        assertEquals("incoming-preset", imported.sourcePresetKey)
        assertEquals(4, imported.sourcePresetVersion)
        assertTrue(imported.createdAt > incoming.createdAt)
        assertEquals(persistedExisting, repository.getById(existing.id))
    }

    @Test
    fun `overwrite preserves local book identity name and creation time with upstream entry ids`() = runTest {
        val repository = repository()
        val transfer = WorldBookTransferService(repository, json)
        val existing = sampleBook(id = "existing", name = "Local name", createdAt = 123L, updatedAt = 123L)
        repository.save(existing)
        val incoming = sampleBook(
            id = "incoming",
            name = "Incoming name",
            description = "replacement",
            entries = listOf(sampleEntry(id = "incoming-entry", content = "replacement entry")),
            sourcePresetKey = "new-preset",
            sourcePresetVersion = 9,
            createdAt = 999L,
            updatedAt = 999L
        )

        val overwritten = transfer.overwrite(existing.id, WorldBookPackage(book = incoming))
        val persisted = requireNotNull(repository.getById(existing.id))

        assertEquals(existing.id, overwritten.id)
        assertEquals(existing.name, overwritten.name)
        assertEquals(existing.createdAt, overwritten.createdAt)
        assertEquals("replacement", overwritten.description)
        assertEquals("incoming-entry", overwritten.entries.single().id)
        assertEquals("replacement entry", overwritten.entries.single().content)
        assertEquals("new-preset", overwritten.sourcePresetKey)
        assertEquals(9, overwritten.sourcePresetVersion)
        assertEquals(overwritten.copy(updatedAt = persisted.updatedAt), persisted)
        assertTrue(persisted.updatedAt >= overwritten.updatedAt)
    }

    @Test
    fun `repository round trip remains valid after import overwrite and export`() = runTest {
        val repository = repository()
        val transfer = WorldBookTransferService(repository, json)
        val imported = transfer.importNew(WorldBookPackage(book = sampleBook(id = "package", name = "Round trip")))
        val replacement = sampleBook(
            id = "replacement",
            name = "ignored",
            description = "updated",
            entries = listOf(sampleEntry(id = "replacement-entry", content = "updated content"))
        )

        transfer.overwrite(imported.id, WorldBookPackage(book = replacement))
        val decoded = transfer.decode(transfer.exportJson(imported.id))
        val persisted = requireNotNull(repository.getById(imported.id))

        assertEquals(persisted, decoded.book)
        assertEquals(imported.id, decoded.book.id)
        assertEquals(imported.name, decoded.book.name)
        assertEquals("updated", decoded.book.description)
        assertEquals("replacement-entry", decoded.book.entries.single().id)
    }

    private fun repository(): WorldBookRepository = WorldBookRepository(JsonFileStorage(root))

    private fun decodeOnly(): WorldBookTransferService = WorldBookTransferService(json)

    private fun sampleBook(
        id: String,
        name: String,
        description: String = "description",
        entries: List<WorldBookEntry> = listOf(sampleEntry(id = "entry-$id")),
        sourcePresetKey: String? = null,
        sourcePresetVersion: Int? = null,
        createdAt: Long = 1L,
        updatedAt: Long = createdAt
    ): WorldBook = WorldBook(
        id = id,
        name = name,
        description = description,
        entries = entries,
        scanDepth = 5,
        tokenBudget = 2048,
        recursiveScanning = true,
        caseSensitive = true,
        matchWholeWords = true,
        sourcePresetKey = sourcePresetKey,
        sourcePresetVersion = sourcePresetVersion,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun sampleEntry(
        id: String,
        content: String = "content",
        position: WorldBookPosition = WorldBookPosition.OUTLET,
        originalPosition: String? = "outlet",
        characterFilter: List<String> = emptyList(),
        characterFilterExclude: Boolean = false
    ): WorldBookEntry = WorldBookEntry(
        id = id,
        name = "Entry",
        keys = listOf("key"),
        content = content,
        enabled = true,
        insertionOrder = 42,
        priority = 9,
        constant = true,
        position = position,
        caseSensitive = false,
        matchWholeWords = false,
        selective = true,
        secondaryKeys = listOf("secondary"),
        selectiveLogic = WorldBookSelectiveLogic.AND_ALL.value,
        comment = "Comment",
        scanDepth = 4,
        role = "system",
        ignoreBudget = true,
        excludeRecursion = true,
        preventRecursion = true,
        delayUntilRecursion = true,
        recursionLevel = 2,
        originalPosition = originalPosition,
        matchCharacterDescription = true,
        matchCharacterPersonality = true,
        matchScenario = true,
        matchCreatorNotes = true,
        matchPersonaDescription = true,
        probability = 75,
        group = "group",
        groupWeight = 80,
        sticky = 2,
        cooldown = 3,
        delay = 4,
        useRegex = true,
        outletName = "knowledge",
        characterFilter = characterFilter,
        characterFilterExclude = characterFilterExclude,
        extensions = "{\"future\":true}"
    )
}
