package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.MomentTask
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopLanSyncMomentsAdmissionTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    @Test fun `local legacy shape remains pinned compatible and dormant flag is disclosed as Fork extension`() {
        val task = MomentTask("synthetic", "card", "session", 1, createdAt = 1, updatedAt = 1)
        val legacy = json.encodeToJsonElement(MomentTask.serializer(), task).jsonObject
        assertFalse(DesktopMomentBackupExtension.TASK_APPROVAL_FIELD in legacy)
        val pinned = json.decodeFromJsonElement(MomentTaskSnapshot.serializer(), legacy)
        assertEquals(task.id, pinned.id)
        assertEquals(json.encodeToString(MomentTaskSnapshot.serializer(), pinned), json.encodeToString(MomentTask.serializer(), task))
        val imported = json.encodeToJsonElement(MomentTask.serializer(), task.copy(requiresLocalScheduleApproval = true)).jsonObject
        assertTrue(DesktopBackupPolicy.hasDesktopParticipation(imported))
        assertEquals(task.id, DesktopMomentBackupExtension.taskSnapshot(imported).id)
        assertTrue(json.decodeFromJsonElement(MomentTask.serializer(), imported).requiresLocalScheduleApproval)
        val transported = DesktopBackupPolicy.entity("entities/moment_tasks/${task.id}.json", imported.toString().toByteArray(), exporting = true, modern = true)
        assertEquals(JsonPrimitive(true), transported[DesktopMomentBackupExtension.TASK_APPROVAL_FIELD])
        assertFails { DesktopMomentBackupExtension.taskSnapshot(JsonObject(imported + (DesktopMomentBackupExtension.TASK_APPROVAL_FIELD to JsonPrimitive("true")))) }
        assertFails { DesktopMomentBackupExtension.taskSnapshot(JsonObject(imported + ("unknownField" to JsonPrimitive(1)))) }
    }
}
