package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncAppliedSourceFilesTest {
    @Test fun anAuthorizedJournalAboveTheEntityWindowRecoversAndProvesEveryExactPostApplyFile() {
        val root = Files.createTempDirectory("synthetic-post-manifest-entity-window-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("data"))
            val state = Files.createDirectory(root.resolve("state"))
            val cards = Files.createDirectories(data.resolve("entities/character_cards"))
            val measuring = LanSyncMetadataBudget()
            val largest = (1..32).maxOf { index ->
                val id = "synthetic-card-$index"
                val file = cards.resolve("$id.json")
                LanSyncJsonFiles.write(file, CharacterCard.serializer(), CharacterCard(id, "原卡", createdAt = 1, updatedAt = 1))
                measuring.validate(file)
            }
            val budget = LanSyncMetadataBudget(maximumDecodedCost = largest + 1024)
            val owner = LanSyncWorkspace(data, state, { Path.of(it) }, metadataBudget = budget, observeJournal = { stage, _ ->
                if (stage == "AUTHORIZED") error("SYNTHETIC_WAL_AUTHORIZATION_INTERRUPTION")
            })
            val transaction = UUID.randomUUID().toString()
            val before = owner.capture("synthetic").use { it.snapshot to it.sourceFiles }
            val target = before.first.copy(records = before.first.records.map { row ->
                row.copy(body = JsonObject(row.body + ("name" to JsonPrimitive("新卡"))))
            })
            assertEquals("SYNTHETIC_WAL_AUTHORIZATION_INTERRUPTION", assertFailsWith<IllegalStateException> {
                owner.apply(LanSyncPlan(transaction, target, emptyMap()), before.first.hashes(), "synthetic", emptyMap())
            }.message)
            val directory = state.resolve("transaction-$transaction")
            val journal = directory.resolve("journal.json")
            val authorization = Files.readString(directory.resolve("authorized"))
            assertEquals(authorization, LanSyncResourceStore.hashFile(journal, Files.size(journal)))
            assertEquals("SYNC_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> { budget.validate(journal) }.message)
            assertFalse(owner.applied(transaction))
            assertNull(owner.appliedSourceFiles(transaction, before.second))

            val reopened = LanSyncWorkspace(data, state, { Path.of(it) }, metadataBudget = budget)
            reopened.recover()
            assertTrue(reopened.applied(transaction))
            val post = requireNotNull(reopened.appliedSourceFiles(transaction, before.second))
            reopened.capture("synthetic").use { actual ->
                assertEquals(32, post.size)
                assertEquals(post, actual.sourceFiles)
                assertEquals(target.hashes(), actual.snapshot.hashes())
                assertTrue(actual.snapshot.records.all { it.text("name") == "新卡" })
            }
            assertEquals(authorization, Files.readString(directory.resolve("authorized")))
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun authorizedWalSkipsKnownDerivedIndexDeletionsAndDetectsLaterModelOnlyEditsAfterPayloadRetirement() {
        val root = Files.createTempDirectory("synthetic-post-raw-manifest-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("data")); val state = Files.createDirectory(root.resolve("state"))
            val workspace = LanSyncWorkspace(data, state, { Path.of(it) })
            val card = data.resolve("entities/character_cards/card.json"); Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "synthetic", createdAt = 1, updatedAt = 1))
            val session = data.resolve("entities/chat_sessions/session.json"); Files.createDirectories(session.parent)
            LanSyncJsonFiles.write(session, ChatSession.serializer(), ChatSession("session", "card", "before", modelId = "synthetic-local-one", createdAt = 1, updatedAt = 1))
            val indexes = listOf("chat_message_indexes", "chat_message_order_backups").map { type ->
                data.resolve("entities/$type/session.json").also { path -> Files.createDirectories(path.parent); Files.writeString(path, "{\"synthetic\":true}") }
            }
            workspace.capture("synthetic").use { before ->
                assertTrue(indexes.none { data.relativize(it).toString().replace('\\', '/') in before.sourceFiles })
                val tx = UUID.randomUUID().toString()
                val target = before.snapshot.copy(records = before.snapshot.records.map { row ->
                    if (row.type == "chat_sessions") row.copy(body = JsonObject(row.body + ("title" to JsonPrimitive("after")))) else row
                })
                assertNull(workspace.appliedSourceFiles(tx, before.sourceFiles))
                workspace.apply(LanSyncPlan(tx, target, emptyMap()), before.snapshot.hashes(), "synthetic", emptyMap(), expectedSourceFiles = before.sourceFiles)
                val post = requireNotNull(workspace.appliedSourceFiles(tx, before.sourceFiles))
                assertTrue(indexes.none { Files.exists(it) })
                workspace.capture("synthetic").use { assertEquals(it.sourceFiles, post); assertEquals(target.hashes(), it.snapshot.hashes()) }
                val authorization = state.resolve("transaction-$tx/authorized"); val originalAuthorization = Files.readAllBytes(authorization)
                Files.write(authorization, "0".repeat(64).toByteArray())
                assertFails { workspace.appliedSourceFiles(tx, before.sourceFiles) }; Files.write(authorization, originalAuthorization)
                assertEquals("synthetic-local-one", LanSyncJsonFiles.read(session, ChatSession.serializer())?.modelId)
                assertEquals("SYNC_JOURNAL_SOURCE_CHANGED", assertFailsWith<IllegalArgumentException> {
                    workspace.appliedSourceFiles(tx, before.sourceFiles + ("entities/chat_sessions/session.json" to "0".repeat(64)))
                }.message)
                workspace.retire(tx); assertEquals(post, workspace.appliedSourceFiles(tx, before.sourceFiles))
                val current = requireNotNull(LanSyncJsonFiles.read(session, ChatSession.serializer()))
                LanSyncJsonFiles.write(session, ChatSession.serializer(), current.copy(modelId = "synthetic-local-two"))
                workspace.capture("synthetic").use { later ->
                    assertEquals(target.hashes(), later.snapshot.hashes()); assertNotEquals(post, later.sourceFiles)
                    assertEquals(post, workspace.appliedSourceFiles(tx, before.sourceFiles))
                }
                workspace.recover()
                assertEquals("synthetic-local-two", LanSyncJsonFiles.read(session, ChatSession.serializer())?.modelId)
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun aBareAppliedMarkerDoesNotGrantPostManifestAuthority() {
        val root = Files.createTempDirectory("synthetic-post-raw-refusal-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("data")); val state = Files.createDirectory(root.resolve("state"))
            val workspace = LanSyncWorkspace(data, state, { Path.of(it) }); val tx = UUID.randomUUID().toString()
            val directory = Files.createDirectory(state.resolve("transaction-$tx")); Files.write(directory.resolve("applied"), byteArrayOf(1))
            assertFails { workspace.appliedSourceFiles(tx, emptyMap()) }
            assertFalse(Files.exists(directory.resolve("authorized"))); assertFalse(Files.exists(directory.resolve("journal.json")))
        } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun anUnknownAuthorizedEntryIsNotSilentlyExcludedFromTheManifest() {
        val root = Files.createTempDirectory("synthetic-post-raw-unknown-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("data")); val state = Files.createDirectory(root.resolve("state"))
            val workspace = LanSyncWorkspace(data, state, { Path.of(it) }); val tx = UUID.randomUUID().toString()
            val directory = Files.createDirectory(state.resolve("transaction-$tx")); Files.write(directory.resolve("applied"), byteArrayOf(1))
            val journal = directory.resolve("journal.json")
            LanSyncJsonFiles.write(journal, LanSyncJournal.serializer(), LanSyncJournal(tx, listOf(
                LanSyncJournalEntry("entities/unrelated/ghost.json", null, "a".repeat(64), 0))))
            Files.writeString(directory.resolve("authorized"), LanSyncResourceStore.hashFile(journal, Files.size(journal)))
            assertEquals("SYNC_TARGET_PATH", assertFailsWith<IllegalArgumentException> { workspace.appliedSourceFiles(tx, emptyMap()) }.message)
            assertFalse(Files.exists(data.resolve("entities/unrelated/ghost.json")))
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
