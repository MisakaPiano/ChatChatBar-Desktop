package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.MomentRepository
import com.example.chatbar.domain.moment.MomentTaskExecutionPolicy
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class LanSyncMomentsAdmissionTest {
    @Test fun importedPendingDataSurvivesReopenWithoutBecomingAutomaticWork() = runBlocking {
        val root = Files.createTempDirectory("lan-moments-admission-").toRealPath()
        val repository = MomentRepository(JsonFileStorage(root))
        val local = MomentTask("local", "card", "session", 1, createdAt = 1, updatedAt = 1)
        repository.saveTask(local)
        repository.saveTask(local.copy(id = "imported", requiresLocalScheduleApproval = true))
        repository.saveTask(local.copy(id = "interrupted", status = MomentTaskStatus.RUNNING, requiresLocalScheduleApproval = true))
        val reopened = MomentRepository(JsonFileStorage(root))
        val due = reopened.pendingTasksDue(2)
        assertEquals(setOf("local", "imported"), due.map { it.id }.toSet())
        assertEquals(listOf("local"), due.filter(MomentTaskExecutionPolicy::mayAutomaticallyExecute).map { it.id })
        assertEquals(MomentTaskStatus.PENDING, reopened.getAllTasks().single { it.id == "imported" }.status)
        assertEquals(MomentTaskStatus.RUNNING, reopened.getAllTasks().single { it.id == "interrupted" }.status)
    }
}
