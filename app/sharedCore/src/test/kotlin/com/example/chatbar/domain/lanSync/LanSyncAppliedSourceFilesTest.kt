package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncAppliedSourceFilesTest {
    @Test fun authorizedWalDefinesExactPostRawFilesAndDetectsLaterModelOnlyEditsAfterPayloadRetirement() {
        val root = Files.createTempDirectory("synthetic-post-raw-manifest-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("data")); val state = Files.createDirectory(root.resolve("state"))
            val workspace = LanSyncWorkspace(data, state, { Path.of(it) })
            val card = data.resolve("entities/character_cards/card.json"); Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "synthetic", createdAt = 1, updatedAt = 1))
            val session = data.resolve("entities/chat_sessions/session.json"); Files.createDirectories(session.parent)
            LanSyncJsonFiles.write(session, ChatSession.serializer(), ChatSession("session", "card", "before", modelId = "synthetic-local-one", createdAt = 1, updatedAt = 1))
            workspace.capture("synthetic").use { before ->
                val tx = UUID.randomUUID().toString()
                val target = before.snapshot.copy(records = before.snapshot.records.map { row ->
                    if (row.type == "chat_sessions") row.copy(body = JsonObject(row.body + ("title" to JsonPrimitive("after")))) else row
                })
                assertNull(workspace.appliedSourceFiles(tx, before.sourceFiles))
                workspace.apply(LanSyncPlan(tx, target, emptyMap()), before.snapshot.hashes(), "synthetic", emptyMap(), expectedSourceFiles = before.sourceFiles)
                val post = requireNotNull(workspace.appliedSourceFiles(tx, before.sourceFiles))
                workspace.capture("synthetic").use { assertEquals(it.sourceFiles, post); assertEquals(target.hashes(), it.snapshot.hashes()) }
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
}
