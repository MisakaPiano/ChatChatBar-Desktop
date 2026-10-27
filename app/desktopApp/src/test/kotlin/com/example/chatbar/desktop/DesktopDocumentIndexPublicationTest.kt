package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopDocumentIndexPublicationTest {
    private class Fixture {
        val base = Files.createTempDirectory("document-index-publish-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(base.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val root get() = container.appDataRoot
        val storage get() = container.jsonFileStorage
        val card = CharacterCard.create("Synthetic").copy(id = "card", customDocuments = listOf(
            DocumentInfo("doc", "doc.txt", "documents/doc.txt", "txt", 1)))
        val config = EmbeddingConfig("default", "Synthetic", "https://invalid.example/v1", "", "test", 2)
        lateinit var owner: DesktopDataRootOwnership
        suspend fun seed() {
            container.characterRepository.save(card)
            storage.saveEntity("embedding_model_config", "default", config, EmbeddingConfig.serializer())
            Files.createDirectories(root.resolve("documents")); Files.writeString(root.resolve("documents/doc.txt"), "# A\nSynthetic alpha.\n# B\nSynthetic beta.")
            owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(container.resolvedRoot)).ownership
        }
        fun service(gateway: DesktopIndexEmbeddingGateway = DesktopIndexEmbeddingGateway { it.pieces.map { listOf(1f, 0f) } },
            journal: DesktopIndexCommitJournal = DesktopIndexCommitJournal(root)) = DesktopDocumentIndexService(
            root, storage, container.characterRepository, container.dataOperationCoordinator, container.taskRuntime, gateway, journal = journal)
        suspend fun close() { container.close(); owner.close(); base.toFile().deleteRecursively() }
    }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) { val f = Fixture(); f.seed(); try { test(f) } finally { f.close() } }

    @Test fun `publication preserves unrelated vectors and official retrieval accepts new source`() = runBlocking { fixture { f ->
        val unrelated = VectorChunk("other", ChunkSourceType.DOCUMENT, "other-card", content = "untouched", embedding = listOf(1f, 0f), createdAt = 1)
        f.storage.saveEntity("vector_chunks", unrelated.id, unrelated, VectorChunk.serializer())
        val result = f.service().index("card", "doc")
        assertEquals(2, result.chunks)
        val card = assertNotNull(f.container.characterRepository.getById("card"))
        assertEquals("INDEXED", card.customDocuments.single().ragStatus)
        assertEquals("COMPLETE", card.ragIndexStatus)
        val matches = assertIs<DesktopDocumentRagResult.Available>(f.container.documentRagReader.retrieve(card,
            listOf(1f, 0f), DesktopRagEmbeddingIdentity.from(f.config)))
        assertEquals(2, matches.matches.size)
        assertEquals(unrelated, f.storage.loadEntity("vector_chunks", "other", VectorChunk.serializer()))
        DesktopIndexCommitJournal(f.root).recover()
        assertEquals(card, f.container.characterRepository.getById("card"))
    } }

    @Test fun `failed embedding and changed source never replace an existing index`() = runBlocking { fixture { f ->
        f.service().index("card", "doc")
        val before = Files.readAllBytes(f.root.resolve("entities/character_cards/card.json"))
        assertFailsWith<IllegalStateException> { f.service(DesktopIndexEmbeddingGateway { error("synthetic transport failure") }).index("card", "doc") }
        assertContentEquals(before, Files.readAllBytes(f.root.resolve("entities/character_cards/card.json")))
        assertFailsWith<IllegalStateException> { f.service(DesktopIndexEmbeddingGateway { batch ->
            Files.writeString(f.root.resolve("documents/doc.txt"), "changed source")
            batch.pieces.map { listOf(1f, 0f) }
        }).index("card", "doc") }
        assertContentEquals(before, Files.readAllBytes(f.root.resolve("entities/character_cards/card.json")))
    } }

    @Test fun `publication failure restores every old file and retains unrelated records`() = runBlocking { fixture { f ->
        f.service().index("card", "doc")
        fun inventory() = Files.walk(f.root.resolve("entities")).use { paths -> paths.filter { Files.isRegularFile(it) }.toList()
            .associate { f.root.relativize(it).toString() to Files.readAllBytes(it).toList() } }
        val before = inventory()
        assertFailsWith<IllegalStateException> { f.service(journal = DesktopIndexCommitJournal(f.root) { index ->
            if (index == 1) error("synthetic failure")
        }).index("card", "doc") }
        assertEquals(before, inventory())
        assertFalse(DesktopIndexCommitJournal(f.root).hasPending())
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.container.dataOperationCoordinator.state)
    } }

    @Test fun `cancellation during embedding leaves no journal and no normal lease`() = runBlocking { fixture { f ->
        val entered = CompletableDeferred<Unit>()
        val job = launch { f.service(DesktopIndexEmbeddingGateway { entered.complete(Unit); awaitCancellation() }).index("card", "doc") }
        entered.await(); job.cancelAndJoin()
        f.container.dataOperationCoordinator.withExclusiveMaintenance { }
        assertFalse(DesktopIndexCommitJournal(f.root).hasPending())
        assertEquals(f.card, f.container.characterRepository.getById("card"))
    } }

    @Test fun `startup replays incomplete undo before business and rejects foreign changes`() = runBlocking { fixture { f ->
        val file = f.root.resolve("entities/character_cards/card.json")
        val before = Files.readAllBytes(file)
        var captured: Path? = null
        val journal = DesktopIndexCommitJournal(f.root) {
            val home = f.root.resolve("backups/.restore-document-index.tmp")
            val active = Files.newDirectoryStream(home, "active-*").use { it.single() }
            val copy = Files.createTempDirectory(f.base, "crash-copy-")
            Files.list(active).use { files -> files.forEach { Files.copy(it, copy.resolve(it.fileName)) } }
            captured = copy
            error("crash boundary capture")
        }
        val next = f.storage.json.encodeToString(CharacterCard.serializer(), f.card.copy(name = "next")).toByteArray()
        assertFailsWith<IllegalStateException> { journal.commit(mapOf("entities/character_cards/card.json" to next)) }
        val active = f.root.resolve("backups/.restore-document-index.tmp/active-${java.util.UUID.randomUUID()}")
        Files.move(captured!!, active)
        Files.write(file, next)
        DesktopIndexCommitJournal(f.root).recover()
        assertContentEquals(before, Files.readAllBytes(file))
        assertFalse(DesktopIndexCommitJournal(f.root).hasPending())
        val nextActive = f.root.resolve("backups/.restore-document-index.tmp/active-${java.util.UUID.randomUUID()}")
        val done = Files.newDirectoryStream(f.root.resolve("backups/.restore-document-index.tmp"), "done-*").use { it.first() }
        Files.createDirectory(nextActive)
        Files.list(done).use { files -> files.forEach { Files.copy(it, nextActive.resolve(it.fileName)) } }
        Files.writeString(file, "foreign bytes")
        assertFailsWith<IllegalStateException> { DesktopIndexCommitJournal(f.root).recover() }
        assertEquals("foreign bytes", Files.readString(file))
    } }
}
