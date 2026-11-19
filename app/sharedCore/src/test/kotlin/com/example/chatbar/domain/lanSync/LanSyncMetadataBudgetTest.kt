package com.example.chatbar.domain.lanSync

import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

class LanSyncMetadataBudgetTest {
    private fun check(value: String, budget: LanSyncMetadataBudget) {
        val file = Files.createTempFile("lan-metadata-working-", ".json")
        try { Files.write(file, value.toByteArray(Charsets.UTF_8)); budget.validate(file) }
        finally { Files.delete(file) }
    }
    @Test fun rejectsDeepContainerAndTinyTokenAmplificationBeforeTheTypedDecoder() {
        assertEquals("SYNC_METADATA_DEPTH", assertFailsWith<IllegalArgumentException> {
            check("[".repeat(9) + "0" + "]".repeat(9), LanSyncMetadataBudget(maximumDepth = 8))
        }.message)
        assertEquals("SYNC_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> {
            check("[" + List(500) { "[]" }.joinToString(",") + "]", LanSyncMetadataBudget(maximumDecodedCost = 4096))
        }.message)
    }
    @Test fun stringEscapesCannotHideAWorkingBudgetOrDefeatDepthAccounting() {
        check("""{"quoted":"\\\"[[[[[","rows":[null,true,4]}""", LanSyncMetadataBudget(maximumDepth = 2))
        assertEquals("SYNC_METADATA_STRING_BUDGET", assertFailsWith<IllegalArgumentException> {
            check("\"" + "\\u0061".repeat(100) + "\"", LanSyncMetadataBudget(maximumStringCharacters = 30))
        }.message)
        check("[1,2,3]", LanSyncMetadataBudget(maximumDecodedCost = 4096))
    }

    @Test fun overBudgetEncodedSnapshotCannotReplaceRetainedMetadataOrPublishANewFile() {
        val root = Files.createTempDirectory("lan-metadata-publish-").toRealPath()
        try {
            val current = root.resolve("current.json")
            val pending = root.resolve("pending.json")
            val budget = LanSyncMetadataBudget(maximumDecodedCost = 2048, maximumIndexCost = 64 * 1024)
            val recordPath = listOf("records")
            val before = LanSyncSnapshot(deviceId = "synthetic", records = emptyList())
            val body = buildJsonObject {
                put("id", JsonPrimitive("synthetic-card"))
                put("name", JsonPrimitive("s".repeat(4096)))
            }
            val oversized = before.copy(records = listOf(LanSyncRecord("character_cards", "synthetic-card", body)))
            LanSyncJsonFiles.writeRecords(current, LanSyncSnapshot.serializer(), before, budget, recordPath)
            val retained = Files.readAllBytes(current)
            val emptyIndexCost = Files.newBufferedReader(current).use {
                LanSyncRecordPreflight(it, budget, recordPath).validate().indexCost
            }
            val smallBody = buildJsonObject { put("id", JsonPrimitive("synthetic-card")) }
            val indexed = before.copy(records = listOf(LanSyncRecord("character_cards", "synthetic-card", smallBody)))
            fun assertRefused(snapshot: LanSyncSnapshot, writeBudget: LanSyncMetadataBudget, code: String) {
                listOf(current, pending).forEach { target ->
                    assertEquals(code, assertFailsWith<IllegalArgumentException> {
                        LanSyncJsonFiles.writeRecords(target, LanSyncSnapshot.serializer(), snapshot, writeBudget, recordPath)
                    }.message)
                }
                assertContentEquals(retained, Files.readAllBytes(current))
                assertFalse(Files.exists(pending))
            }
            assertRefused(oversized, budget, "SYNC_METADATA_WORKING_BUDGET")
            assertRefused(indexed, budget.copy(maximumIndexCost = emptyIndexCost), "SYNC_METADATA_INDEX_BUDGET")
            assertEquals(before, LanSyncJsonFiles.read(current, LanSyncSnapshot.serializer()))
            Files.list(root).use { entries -> assertEquals(listOf(current), entries.toList()) }
        } finally {
            check(root.toFile().deleteRecursively())
        }
    }
}
