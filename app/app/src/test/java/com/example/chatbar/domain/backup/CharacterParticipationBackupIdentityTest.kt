package com.example.chatbar.domain.backup

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CharacterParticipationBackupIdentityTest {
    private val identity = BackupMergeIdentity(copy = false).apply {
        bind("character_cards", "old-card", "new-card")
        bind("characters", "old-person", "new-person")
    }

    @Test fun streamingMergeKeepsDisabledPeopleBoundToTheirRemappedCardAndPerson() {
        val raw = """{"id":"session","characterCardId":"old-card","characterParticipation":[{"cardId":"old-card","disabledPersonIds":["old-person"],"automaticallyPausedPersonIds":["old-person"]}]}"""
        val result = rewrite(raw)
        assertEquals("new-card", result.getValue("characterCardId").jsonPrimitive.content)
        val state = result.getValue("characterParticipation").jsonArray.single().jsonObject
        assertEquals("new-card", state.getValue("cardId").jsonPrimitive.content)
        assertEquals("new-person", state.getValue("disabledPersonIds").jsonArray.single().jsonPrimitive.content)
        assertEquals("new-person", state.getValue("automaticallyPausedPersonIds").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun legacyParticipationMapsButUnrelatedAndExtensionFieldsRemainExact() {
        val raw = """{"cardId":"old-card","disabledPersonIds":["old-person"],"desktopCharacterParticipation":[{"cardId":"old-card","disabledPersonIds":["old-person"]}],"extensions":{"characterParticipation":[{"cardId":"old-card","disabledPersonIds":["old-person"]}]}}"""
        val result = rewrite(raw)
        assertEquals("old-card", result.getValue("cardId").jsonPrimitive.content)
        assertEquals("old-person", result.getValue("disabledPersonIds").jsonArray.single().jsonPrimitive.content)
        val legacy = result.getValue("desktopCharacterParticipation").jsonArray.single().jsonObject
        assertEquals("new-card", legacy.getValue("cardId").jsonPrimitive.content)
        val extension = result.getValue("extensions").jsonObject.getValue("characterParticipation").jsonArray.single().jsonObject
        assertEquals("old-card", extension.getValue("cardId").jsonPrimitive.content)
        assertEquals("old-person", extension.getValue("disabledPersonIds").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun explicitPlayerRoleRemapsCardAndPersonButNeverExtensionText() {
        val result = rewrite("""{"desktopPlayerRole":{"cardId":"old-card","personId":"old-person","followName":false},"extensions":{"desktopPlayerRole":{"cardId":"old-card","personId":"old-person"}}}""")
        val role = result.getValue("desktopPlayerRole").jsonObject
        assertEquals("new-card", role.getValue("cardId").jsonPrimitive.content)
        assertEquals("new-person", role.getValue("personId").jsonPrimitive.content)
        assertFalse(role.getValue("followName").jsonPrimitive.boolean)
        val extension = result.getValue("extensions").jsonObject.getValue("desktopPlayerRole").jsonObject
        assertEquals("old-card", extension.getValue("cardId").jsonPrimitive.content)
        assertEquals("old-person", extension.getValue("personId").jsonPrimitive.content)
        for (name in listOf("desktopAvatar", "desktopPlayerAvatar")) assertTrue(BackupDataRegistry.isResourceField(name, emptyList()))
    }

    private fun rewrite(raw: String): JsonObject {
        val record = BackupMergeRecord(File("synthetic-session.json"), "entities/chat_sessions/synthetic.json", "chat_sessions", emptyMap())
        val out = StringWriter()
        BackupJsonStream(StringReader(raw), out, mappedFields = BackupMergeIdentity.fields,
            mapString = { value, fields -> identity.fieldSpace(record, fields)?.let { identity.value(it, value) } ?: value },
            mapPath = { path, _ -> path }).copy()
        return Json.parseToJsonElement(out.toString()).jsonObject
    }
}
