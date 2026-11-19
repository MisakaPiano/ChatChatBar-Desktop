package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.domain.syncStore.SyncPlaintextIdentity
import com.example.chatbar.domain.syncStore.SyncStoreId
import com.example.chatbar.domain.syncStore.SyncUnitChange
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanSyncRetainedDescriptorBudgetTest {
    private val readBudget = LanSyncMetadataBudget(maximumDecodedCost = 640L * 1024, maximumIndexCost = 320L * 1024)

    private fun records(): List<LanSyncRecord> {
        val session = ChatSession("session", "card", "Synthetic conversation", createdAt = 1, updatedAt = 1)
        val sessionBody = LanSyncScope.normalize("chat_sessions",
            lanSyncJson.encodeToJsonElement(ChatSession.serializer(), session).jsonObject)
        val commits = (1..168).map { index ->
            val id = "memory-commit-" + index.toString().padStart(4, '0')
            val body = buildJsonObject {
                put("id", id)
                put("sessionId", "session")
                put("createdAt", 1)
            }
            LanSyncRecord("memory_commits", id, body)
        }
        return listOf(LanSyncRecord("chat_sessions", "session", sessionBody)) + commits
    }

    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("retained-lan-metadata-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun aRetainedCompactOldConversationReopensWithExactUnitAndMembership() = withRoot { root ->
        val unit = "conversation/session"
        val coverage = LanSyncScope.schemas.keys.toSortedSet()
        val records = records()
        val snapshot = LanSyncSnapshot(deviceId = "synthetic", records = records, coveredTypes = coverage)
        val file = root.resolve("retained-unit.json")
        val payload = LanSyncGroupUnitPayload(1, unit, coverage, records, emptyMap())
        LanSyncJsonFiles.write(file, LanSyncGroupUnitPayload.serializer(), payload)
        val identity = SyncPlaintextIdentity(Files.size(file), LanSyncResourceStore.hashFile(file, Files.size(file)))
        val oldDocumentCost = readBudget.validate(file)
        assertTrue(oldDocumentCost in 518_785L..readBudget.maximumDecodedCost)
        val valueHash = snapshot.hashes().getValue(unit)
        val membership = LanSyncGroupMembership.identity(records)
        val codec = LanSyncGroupUnitCodec(Files.createDirectory(root.resolve("units")), coverage, readBudget)
        if (System.getenv("CCB_RETAINED_LEGACY_WRITER") == "1") {
            codec.prepare(LanSyncCapture(snapshot, emptyMap(), emptyMap()), unit).use { written ->
                assertEquals(identity, written.decoded.plaintext)
                assertEquals(valueHash, written.decoded.valueHash)
                assertEquals(membership, written.decoded.membership)
            }
        }
        val retained = LanSyncResourceStore(root.resolve("retained"))
        val retainedFile = retained.retain(file, identity.sha256, identity.length)
        val decoded = codec.readCached(retained, unit, SyncUnitChange(valueHash, identity, emptySet(), membership))
        assertEquals(169, decoded.payload.records.size)
        assertEquals(records, decoded.payload.records)
        assertEquals(identity, decoded.plaintext)
        assertEquals(valueHash, decoded.valueHash)
        assertEquals(membership, decoded.membership)
        assertEquals(identity.sha256, LanSyncResourceStore.hashFile(retainedFile, identity.length))
    }

    @Test fun aRetainedCompactSourceCheckpointKeepsEveryOriginalRecordAndFile() = withRoot { root ->
        val data = Files.createDirectory(root.resolve("data"))
        val state = Files.createDirectory(root.resolve("state"))
        val expectedRows = records().associateBy { it.key }
        expectedRows.values.forEach { record ->
            val file = data.resolve(LanSyncScope.relativePath(record))
            Files.createDirectories(file.parent)
            Files.writeString(file, record.body.toString())
        }
        val workspace = LanSyncWorkspace(data, state, { Path.of(it) })
        val transaction = UUID.randomUUID().toString()
        val entry = workspace.capture("synthetic").use { capture ->
            requireNotNull(workspace.recovery.checkpointSource(data, capture, SyncStoreId.random(), transaction))
        }
        val recovery = state.resolve("recovery")
        val entryDirectory = recovery.resolve("entries").resolve(transaction)
        val originalSnapshot = Files.readAllBytes(entryDirectory.resolve("snapshot.json"))
        val originalEntry = Files.readAllBytes(entryDirectory.resolve("entry.json"))
        val cold = LanSyncRecoveryVault(recovery, objectStore = workspace.resourceStore, metadataBudget = readBudget)
        val retainedEntry = requireNotNull(cold.read(transaction))
        assertEquals(entry, retainedEntry)
        val snapshot = cold.snapshot(retainedEntry)
        assertEquals(expectedRows, snapshot.records.associateBy { it.key })
        cold.verify(retainedEntry)
        assertContentEquals(originalSnapshot, Files.readAllBytes(entryDirectory.resolve("snapshot.json")))
        assertContentEquals(originalEntry, Files.readAllBytes(entryDirectory.resolve("entry.json")))
        expectedRows.values.forEach { record ->
            assertEquals(record.body.toString(), Files.readString(data.resolve(LanSyncScope.relativePath(record))))
        }
    }
}
