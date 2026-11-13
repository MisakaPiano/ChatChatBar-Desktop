package com.example.chatbar.domain.lanSync

import java.nio.file.Files
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
}
