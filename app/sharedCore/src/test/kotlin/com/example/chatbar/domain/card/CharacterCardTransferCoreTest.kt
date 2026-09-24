package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.operation.AppDataOperationGate
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CharacterCardTransferCoreTest {
    @Test
    fun `import materializes resources and preserves normal entity semantics`() = runTest {
        val fixture = fixture()
        val packageData = completePackage()

        val imported = fixture.core.importNew(
            packageData = packageData,
            requestedName = "  New Card  ",
            presetKey = "preset-key",
            presetVersion = 7,
        )

        assertEquals("New Card", imported.name)
        assertEquals("images/resource-1.png", imported.avatar)
        assertEquals("images/resource-2.jpg", imported.chatBackground)
        assertEquals("images/resource-3.webp", imported.characters.single().appearanceImage)
        assertEquals("documents/id-5_notes.txt", imported.customDocuments.single().filePath)
        assertEquals("default-negative", imported.defaultImageNegativePrompt)
        assertEquals("preset-key", imported.sourcePresetKey)
        assertEquals(7, imported.sourcePresetVersion)
        assertEquals(RagIndexStatus.NOT_INDEXED.name, imported.ragIndexStatus)
        assertEquals(1, imported.ragIndexTotal)
        assertEquals("参考文档待建立索引", imported.ragIndexMessage)
        assertNull(imported.characterBook)
        assertNull(imported.boundWorldBookId)
        assertEquals(imported, fixture.characters.cards.getValue(imported.id))
        assertEquals(1, fixture.worldBooks.books.size)
        assertEquals(1, fixture.formatCards.cards.size)
        assertContentEquals("avatar".toByteArray(), fixture.resources.readBytes(imported.avatar!!))
        assertEquals("notes", fixture.resources.readText(imported.customDocuments.single().filePath))

        val exported = fixture.core.decode(fixture.core.exportJson(imported.id))
        assertEquals("New Card", exported.card.name)
        assertEquals("default-negative", exported.card.defaultImageNegativePrompt)
        assertEquals("notes", exported.documents.single().content)
        assertContentEquals(
            "avatar".toByteArray(),
            Base64.getDecoder().decode(exported.images.getValue("avatar").data),
        )
    }

    @Test
    fun `duplicate gets fresh identity while overwrite preserves local identity name and createdAt`() = runTest {
        val fixture = fixture()
        val source = fixture.core.importNew(completePackage(), requestedName = "Source")

        val duplicate = fixture.core.duplicate(source.id)
        assertNotEquals(source.id, duplicate.id)
        assertEquals("Source (2)", duplicate.name)
        assertNotEquals(source.avatar, duplicate.avatar)

        val replacement = fixture.core.overwrite(
            source.id,
            completePackage().copy(card = completePackage().card.copy(name = "Ignored", greeting = "new greeting")),
            presetKey = "replacement",
            presetVersion = 9,
        )
        assertEquals(source.id, replacement.id)
        assertEquals(source.name, replacement.name)
        assertEquals(source.createdAt, replacement.createdAt)
        assertEquals("new greeting", replacement.greeting)
        assertEquals("replacement", replacement.sourcePresetKey)
        assertEquals(9, replacement.sourcePresetVersion)
        assertEquals(replacement, fixture.characters.cards.getValue(source.id))
    }

    @Test
    fun `world book and format reuse only reuse authoritative matches`() = runTest {
        val fixture = fixture()
        val reusableBook = worldBook("existing-world", "Lore", "same")
        fixture.worldBooks.books[reusableBook.id] = reusableBook
        val reusableFormat = FormatCard(
            id = "existing-format",
            name = "Format",
            content = "format content",
            createdAt = 1L,
        )
        fixture.formatCards.cards[reusableFormat.id] = reusableFormat

        val reused = fixture.core.importNew(completePackage(), requestedName = "Reuse")
        assertEquals(listOf(reusableBook.id), reused.worldBookIds)
        assertEquals(reusableFormat.id, reused.defaultFormatCardId)
        assertEquals(setOf(reusableBook.id), fixture.worldBooks.books.keys)
        assertEquals(setOf(reusableFormat.id), fixture.formatCards.cards.keys)

        val conflicts = completePackage().copy(
            card = completePackage().card.copy(name = "Conflict"),
            worldBooks = listOf(worldBook("incoming-2", "Different", "new")),
            defaultFormatCard = completePackage().defaultFormatCard!!.copy(content = "different"),
        )
        val imported = fixture.core.importNew(conflicts)
        assertNotEquals(reusableBook.id, imported.worldBookIds.single())
        assertNotEquals(reusableFormat.id, imported.defaultFormatCardId)
        assertEquals(2, fixture.worldBooks.books.size)
        assertEquals(2, fixture.formatCards.cards.size)
        assertFalse(fixture.formatCards.cards.getValue(imported.defaultFormatCardId!!).isDefault)
    }

    @Test
    fun `image and document failures remove resources created by the attempt`() = runTest {
        val imageFixture = fixture().also { it.resources.failImageAt = 2 }
        assertFailsWith<IllegalStateException> { imageFixture.core.importNew(completePackage()) }
        assertTrue(imageFixture.resources.entries.isEmpty())
        assertTrue(imageFixture.characters.cards.isEmpty())

        val documentFixture = fixture().also { it.resources.failDocumentAt = 1 }
        assertFailsWith<IllegalStateException> { documentFixture.core.importNew(completePackage()) }
        assertTrue(documentFixture.resources.entries.isEmpty())
        assertTrue(documentFixture.characters.cards.isEmpty())
    }

    @Test
    fun `new related entities rollback when a later materialization step fails`() = runTest {
        val fixture = fixture()
        fixture.formatCards.failImport = true

        assertFailsWith<IllegalStateException> { fixture.core.importNew(completePackage()) }

        assertTrue(fixture.characters.cards.isEmpty())
        assertTrue(fixture.worldBooks.books.isEmpty())
        assertTrue(fixture.formatCards.cards.isEmpty())
        assertTrue(fixture.resources.entries.isEmpty())
    }

    @Test
    fun `character save failure rolls back new side effects but preserves reused entities`() = runTest {
        val newSideEffects = fixture()
        newSideEffects.characters.failSaveBeforeCommit = true

        assertFailsWith<IllegalStateException> { newSideEffects.core.importNew(completePackage()) }

        assertTrue(newSideEffects.characters.cards.isEmpty())
        assertTrue(newSideEffects.worldBooks.books.isEmpty())
        assertTrue(newSideEffects.formatCards.cards.isEmpty())
        assertTrue(newSideEffects.resources.entries.isEmpty())

        val fixture = fixture()
        val reusableBook = worldBook("existing-world", "Lore", "same")
        val reusableFormat = FormatCard("existing-format", "Format", "format content", createdAt = 1L)
        fixture.worldBooks.books[reusableBook.id] = reusableBook
        fixture.formatCards.cards[reusableFormat.id] = reusableFormat
        fixture.characters.failSaveBeforeCommit = true

        assertFailsWith<IllegalStateException> { fixture.core.importNew(completePackage()) }

        assertTrue(fixture.characters.cards.isEmpty())
        assertEquals(setOf(reusableBook.id), fixture.worldBooks.books.keys)
        assertEquals(setOf(reusableFormat.id), fixture.formatCards.cards.keys)
        assertTrue(fixture.resources.entries.isEmpty())
    }

    @Test
    fun `rollback failures stay suppressed on the primary operation failure`() = runTest {
        val fixture = fixture()
        fixture.characters.failSaveBeforeCommit = true
        fixture.worldBooks.failDelete = true
        fixture.formatCards.failDelete = true

        val failure = assertFailsWith<IllegalStateException> {
            fixture.core.importNew(completePackage())
        }

        assertEquals("save before commit", failure.message)
        assertEquals(
            setOf("format rollback failed", "world rollback failed"),
            failure.suppressed.mapNotNull(Throwable::message).toSet(),
        )
    }

    @Test
    fun `overwrite precommit failure preserves old card and resources`() = runTest {
        val fixture = fixture()
        val old = existingCard("old", avatar = "images/old.png", document = "documents/old.txt")
        fixture.characters.cards[old.id] = old
        fixture.resources.entries[old.avatar!!] = "old-avatar".toByteArray()
        fixture.resources.entries[old.customDocuments.single().filePath] = "old-document".toByteArray()
        fixture.characters.failSaveBeforeCommit = true

        assertFailsWith<IllegalStateException> { fixture.core.overwrite(old.id, completePackage()) }

        assertEquals(old, fixture.characters.cards.getValue(old.id))
        assertTrue(fixture.resources.entries.containsKey(old.avatar))
        assertTrue(fixture.resources.entries.containsKey(old.customDocuments.single().filePath))
        assertEquals(setOf(old.avatar, old.customDocuments.single().filePath), fixture.resources.entries.keys)
    }

    @Test
    fun `overwrite postcommit cleanup failure keeps replacement authoritative`() = runTest {
        val fixture = fixture()
        val old = existingCard("old", avatar = "images/old.png")
        fixture.characters.cards[old.id] = old
        fixture.resources.entries[old.avatar!!] = "old".toByteArray()
        fixture.resources.failDeleteReferences += old.avatar

        val failure = assertFailsWith<CharacterTransferPostCommitException> {
            fixture.core.overwrite(old.id, completePackage())
        }

        assertEquals(CharacterTransferPostCommitOperation.OVERWRITE, failure.operation)
        val replacement = fixture.characters.cards.getValue(old.id)
        assertNotEquals(old.avatar, replacement.avatar)
        assertEquals("Card", replacement.name)
        assertTrue(fixture.resources.entries.containsKey(old.avatar))
        assertTrue(fixture.resources.entries.containsKey(replacement.avatar))
    }

    @Test
    fun `delete repository failure leaves card and resources intact`() = runTest {
        val fixture = fixture()
        val card = existingCard("delete", avatar = "images/delete.png", document = "documents/delete.txt")
        fixture.characters.cards[card.id] = card
        card.ownedTestReferences().forEach { fixture.resources.entries[it] = it.toByteArray() }
        fixture.characters.failDeleteBeforeCommit = true

        assertFailsWith<IllegalStateException> { fixture.core.deleteCard(card.id) }

        assertEquals(card, fixture.characters.cards.getValue(card.id))
        assertEquals(card.ownedTestReferences(), fixture.resources.entries.keys)
        assertTrue(fixture.rag.deleted.isEmpty())
    }

    @Test
    fun `delete commits entity before cleanup and never deletes unrelated resources`() = runTest {
        val fixture = fixture()
        val card = existingCard("delete", avatar = "images/delete.png", document = "documents/delete.txt")
        fixture.characters.cards[card.id] = card
        card.ownedTestReferences().forEach { fixture.resources.entries[it] = it.toByteArray() }
        fixture.resources.entries["images/unrelated.png"] = "keep".toByteArray()

        fixture.core.deleteCard(card.id)

        assertFalse(fixture.characters.cards.containsKey(card.id))
        assertFalse(fixture.resources.entries.keys.any(card.ownedTestReferences()::contains))
        assertTrue(fixture.resources.entries.containsKey("images/unrelated.png"))
        assertEquals(listOf(card.id), fixture.rag.deleted)
    }

    @Test
    fun `delete cleanup failure reports postcommit and cannot recreate card`() = runTest {
        val fixture = fixture()
        val card = existingCard("delete", avatar = "images/delete.png")
        fixture.characters.cards[card.id] = card
        fixture.resources.entries[card.avatar!!] = "old".toByteArray()
        fixture.resources.failDeleteReferences += card.avatar

        val failure = assertFailsWith<CharacterTransferPostCommitException> {
            fixture.core.deleteCard(card.id)
        }

        assertEquals(CharacterTransferPostCommitOperation.DELETE, failure.operation)
        assertFalse(fixture.characters.cards.containsKey(card.id))
        assertTrue(fixture.resources.entries.containsKey(card.avatar))
    }

    @Test
    fun `whole transfer enters operation gate exactly once`() = runTest {
        val gate = CountingGate()
        val fixture = fixture(gate)

        fixture.core.importNew(completePackage())

        assertEquals(1, gate.entries)
        assertEquals(0, gate.depth)
    }

    @Test
    fun `packaged image decoder keeps whitespace base64 compatibility and asset identity`() {
        val bytes = assertIs<CharacterPackagedImageContent.Bytes>(
            decodeCharacterPackagedImage("aGVs\r\nbG8="),
        )
        assertContentEquals("hello".toByteArray(), bytes.value)
        assertEquals(
            "cards/avatar.png",
            assertIs<CharacterPackagedImageContent.Asset>(
                decodeCharacterPackagedImage("asset:cards/avatar.png"),
            ).logicalPath,
        )
    }

    private fun fixture(gate: AppDataOperationGate = CountingGate()): Fixture {
        val characters = FakeCharacterStore()
        val worldBooks = FakeWorldBookStore()
        val formatCards = FakeFormatCardStore()
        val resources = FakeResourceStore()
        val rag = FakeRagCleanup()
        var sequence = 0
        return Fixture(
            characters = characters,
            worldBooks = worldBooks,
            formatCards = formatCards,
            resources = resources,
            rag = rag,
            core = CharacterCardTransferCore(
                characters = characters,
                worldBooks = worldBooks,
                formatCards = formatCards,
                resources = resources,
                promptPolicy = TestPromptPolicy,
                ragCleanup = rag,
                json = json,
                operationGate = gate,
                ioDispatcher = Dispatchers.Unconfined,
                nowMillis = { 100L },
                newId = { "id-${++sequence}" },
            ),
        )
    }

    private data class Fixture(
        val core: CharacterCardTransferCore,
        val characters: FakeCharacterStore,
        val worldBooks: FakeWorldBookStore,
        val formatCards: FakeFormatCardStore,
        val resources: FakeResourceStore,
        val rag: FakeRagCleanup,
    )

    private class CountingGate : AppDataOperationGate {
        var entries = 0
        var depth = 0

        override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
            entries += 1
            depth += 1
            return try {
                operation()
            } finally {
                depth -= 1
            }
        }
    }

    private class FakeCharacterStore : CharacterTransferCharacterStore {
        val cards = linkedMapOf<String, CharacterCard>()
        var failSaveBeforeCommit = false
        var failSaveAfterCommit = false
        var failDeleteBeforeCommit = false
        var failDeleteAfterCommit = false

        override suspend fun getAll(): List<CharacterCard> = cards.values.toList()
        override suspend fun getById(id: String): CharacterCard? = cards[id]

        override suspend fun save(card: CharacterCard, onCommitted: () -> Unit) {
            if (failSaveBeforeCommit) error("save before commit")
            cards[card.id] = card
            onCommitted()
            if (failSaveAfterCommit) error("save after commit")
        }

        override suspend fun delete(id: String, onCommitted: () -> Unit) {
            if (failDeleteBeforeCommit) error("delete before commit")
            cards.remove(id)
            onCommitted()
            if (failDeleteAfterCommit) error("delete after commit")
        }
    }

    private class FakeWorldBookStore : CharacterTransferWorldBookStore {
        val books = linkedMapOf<String, WorldBook>()
        var failDelete = false

        override suspend fun getAll(): List<WorldBook> = books.values.toList()
        override suspend fun getById(id: String): WorldBook? = books[id]
        override suspend fun save(book: WorldBook) {
            books[book.id] = book
        }
        override suspend fun delete(id: String) {
            if (failDelete) error("world rollback failed")
            books.remove(id)
        }
    }

    private class FakeFormatCardStore : CharacterTransferFormatCardStore {
        val cards = linkedMapOf<String, FormatCard>()
        var failImport = false
        var failDelete = false
        private var sequence = 0

        override suspend fun getById(id: String): FormatCard? = cards[id]

        override suspend fun importCharacterDefault(
            packageData: FormatCardPackage,
            onCreating: (String) -> Unit,
        ): FormatCard {
            cards.values.firstOrNull {
                NamePolicy.isSame(it.name, packageData.name) &&
                    it.content == packageData.content && it.userTools == packageData.userTools
            }?.let { return it }
            val id = "format-${++sequence}"
            onCreating(id)
            if (failImport) error("format import failed")
            return FormatCard(
                id = id,
                name = NamePolicy.nextCopyName(
                    packageData.name,
                    cards.values.map(FormatCard::name),
                ).takeIf { cards.values.any { card -> NamePolicy.isSame(card.name, packageData.name) } }
                    ?: NamePolicy.normalize(packageData.name),
                content = packageData.content,
                userTools = packageData.userTools,
                isDefault = false,
                sourcePresetKey = packageData.sourcePresetKey,
                sourcePresetVersion = packageData.sourcePresetVersion,
                createdAt = 100L,
            ).also { cards[it.id] = it }
        }

        override suspend fun delete(id: String) {
            if (failDelete) error("format rollback failed")
            cards.remove(id)
        }
    }

    private class FakeResourceStore : CharacterResourceStore {
        val entries = linkedMapOf<String, ByteArray>()
        val failDeleteReferences = mutableSetOf<String>()
        var failImageAt: Int? = null
        var failDocumentAt: Int? = null
        private var imageCount = 0
        private var documentCount = 0

        override fun readText(reference: String): String = String(entries.getValue(reference), Charsets.UTF_8)
        override fun readBytes(reference: String): ByteArray = entries.getValue(reference)
        override fun fileName(reference: String): String = reference.substringAfterLast('/')

        override fun materializeDocument(
            document: PackagedDocument,
            timestamp: Long,
            resourceId: String,
        ): String {
            documentCount += 1
            if (failDocumentAt == documentCount) error("document materialization failed")
            val reference = "documents/${resourceId}_${document.fileName}"
            entries[reference] = document.content.toByteArray()
            return reference
        }

        override fun materializeImage(
            image: PackagedImage,
            timestamp: Long,
            resourceId: String,
        ): String {
            imageCount += 1
            if (failImageAt == imageCount) error("image materialization failed")
            val extension = CharacterResourceNaming.imageExtension(image.fileName)
            val reference = "images/resource-$imageCount.$extension"
            entries[reference] = assertIs<CharacterPackagedImageContent.Bytes>(
                decodeCharacterPackagedImage(image.data),
            ).value
            return reference
        }

        override fun deleteOwned(reference: String) {
            if (reference in failDeleteReferences) error("resource cleanup failed")
            entries.remove(reference)
        }
    }

    private class FakeRagCleanup : CharacterDocumentRagCleanup {
        val deleted = mutableListOf<String>()
        var failure: Throwable? = null

        override suspend fun deleteDocumentChunks(characterId: String) {
            failure?.let { throw it }
            deleted += characterId
        }
    }

    private object TestPromptPolicy : CharacterTransferPromptPolicy {
        override fun defaultCharacterNaiNegativePrompt(): String = "default-negative"
        override fun effectiveCharacterNaiNegativePrompt(value: String): String =
            value.takeIf(String::isNotBlank) ?: defaultCharacterNaiNegativePrompt()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun completePackage(): CharacterCardPackage {
            val avatar = Base64.getEncoder().encodeToString("avatar".toByteArray())
            val background = Base64.getEncoder().encodeToString("background".toByteArray())
            val appearance = Base64.getEncoder().encodeToString("appearance".toByteArray())
            return CharacterCardPackage(
                card = PackagedCharacterCard(
                    name = "Card",
                    avatarResourceId = "avatar",
                    chatBackgroundResourceId = "background",
                    characters = listOf(
                        PackagedCharacter(
                            name = "Alice",
                            appearanceImageResourceId = "appearance",
                        ),
                    ),
                    greeting = "hello",
                ),
                images = linkedMapOf(
                    "avatar" to PackagedImage("avatar.png", avatar),
                    "background" to PackagedImage("background.jpg", background),
                    "appearance" to PackagedImage("appearance.webp", appearance),
                ),
                documents = listOf(PackagedDocument("notes.txt", "txt", "notes")),
                worldBooks = listOf(worldBook("incoming-world", "Lore", "same")),
                defaultFormatCard = FormatCardPackage(name = "Format", content = "format content"),
            )
        }

        fun worldBook(id: String, name: String, content: String): WorldBook = WorldBook(
            id = id,
            name = name,
            entries = listOf(WorldBookEntry(id = "$id-entry", keys = listOf("key"), content = content)),
            createdAt = 1L,
            updatedAt = 1L,
        )

        fun existingCard(id: String, avatar: String? = null, document: String? = null): CharacterCard =
            CharacterCard(
                id = id,
                name = "Card",
                avatar = avatar,
                customDocuments = document?.let {
                    listOf(DocumentInfo("doc", "doc.txt", it, "txt", 1L))
                }.orEmpty(),
                createdAt = 1L,
                updatedAt = 1L,
            )

        fun CharacterCard.ownedTestReferences(): Set<String> = buildSet {
            avatar?.let(::add)
            customDocuments.mapTo(this) { it.filePath }
        }
    }
}
