package com.example.chatbar.domain.lanSync

import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncCompareTest {
    private fun record(id: String, text: String, type: String = "character_cards", session: String? = null) =
        LanSyncRecord(type, id, buildJsonObject { put("id", id); put("text", text); session?.let { put("sessionId", it) } })
    private fun snapshot(vararg records: LanSyncRecord) = LanSyncSnapshot(deviceId = "synthetic", records = records.toList())
    private fun baseline(snapshot: LanSyncSnapshot) = LanSyncBaseline("synthetic", "previous", snapshot.hashes(), ownerId = "synthetic")

    @Test fun initialIndependentAddsAndRepeatAreAutomatic() {
        val a = snapshot(record("a", "A")); val b = snapshot(record("b", "B"))
        assertTrue(LanSyncCompare.compare(a, b, LanSyncBaseline("synthetic")).conflicts.isEmpty())
        assertTrue(LanSyncCompare.compare(a, a, baseline(a)).conflicts.isEmpty())
    }
    @Test fun oneSidedEditAndDeletionUseCommonAncestor() {
        val old = snapshot(record("a", "old")); val edited = snapshot(record("a", "new")); val empty = snapshot()
        assertEquals(true, LanSyncCompare.compare(edited, old, baseline(old)).automatic["character_cards/a"])
        assertEquals(false, LanSyncCompare.compare(old, empty, baseline(old)).automatic["character_cards/a"])
    }
    @Test fun editAgainstDeleteIsSpecificConflict() {
        val old = snapshot(record("a", "old"))
        val result = LanSyncCompare.compare(snapshot(record("a", "new")), snapshot(), baseline(old))
        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts.single().phoneDeleted)
    }
    @Test fun concurrentConversationEditsAreOneGraphConflict() {
        val session = record("s", "session", "chat_sessions")
        val old = snapshot(session, record("m", "old", "chat_messages", "s"))
        val a = snapshot(session, record("m", "computer", "chat_messages", "s"))
        val b = snapshot(session, record("m", "phone", "chat_messages", "s"))
        assertEquals("conversation/s", LanSyncCompare.compare(a, b, baseline(old)).conflicts.single().unit)
    }
    @Test fun jsonObjectOrderDoesNotInventAnEdit() {
        val a = LanSyncRecord("character_cards", "a", buildJsonObject { put("id", "a"); put("name", "名称") })
        val b = a.copy(body = buildJsonObject { put("name", "名称"); put("id", "a") })
        assertEquals(snapshot(a).hashes(), snapshot(b).hashes())
    }
}
