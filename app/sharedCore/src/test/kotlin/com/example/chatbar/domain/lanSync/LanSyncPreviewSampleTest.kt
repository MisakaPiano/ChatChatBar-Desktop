package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.MomentRepository
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncPreviewSampleTest {
    @Test fun actualPreviewSamplePreservesOldDataDecodesBothPalettesAndKeepsDormantPlanRecords() = runBlocking {
        withTimeout(20000) {
            val parent = Files.createTempDirectory("lan-sample-v2-")
            val data = Files.createDirectory(parent.resolve("data")); val state = Files.createDirectory(parent.resolve("state"))
            val old = data.resolve("entities/character_cards/ccb-lan-preview-sample-card.json")
            Files.createDirectories(old.parent)
            val oldBytes = lanSyncJson.encodeToJsonElement(CharacterCard.serializer(),
                CharacterCard("ccb-lan-preview-sample-card", "旧版样例资料", createdAt = 1, updatedAt = 1)).toString().toByteArray()
            Files.write(old, oldBytes)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val gate = LanSyncOperationGate(); val repository = MomentRepository(JsonFileStorage(data, gate))
            repository.initialize()
            val store = LanSyncStateStore(state, { it }, { it })
            val workspace = LanSyncWorkspace(data, state.resolve("workspace"), { Paths.get(it) })
            val runtime = LanSyncRuntime(store, workspace, object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) = gate.exclusive(work)
                override suspend fun reload(sessionIds: Set<String>) { repository.refreshFromStorage() }
            }, "synthetic-sample", scope = scope)
            try {
                runtime.createPreviewSample()
                while (repository.getAllPosts().isEmpty() || runtime.state.value.busy) delay(10)
                assertTrue(runtime.state.value.status.startsWith("已建立"))
                assertContentEquals(oldBytes, Files.readAllBytes(old))
                val post = repository.getAllPosts().single()
                assertEquals(2, post.desktopImageVersions.size)
                assertEquals(post.desktopImageVersions.last().imagePath, post.imagePath)
                val colours = post.desktopImageVersions.map { version ->
                    val bitmap = assertNotNull(ImageIO.read(Paths.get(version.imagePath).toFile()))
                    assertEquals(32, bitmap.width); assertEquals(32, bitmap.height)
                    assertEquals(version.imagePath, version.generatedImageMetadata?.imagePath)
                    bitmap.getRGB(0, 0)
                }
                assertNotEquals(colours[0], colours[1])
                val plans = repository.getAllTasks()
                assertEquals(setOf(MomentTaskStatus.PENDING, MomentTaskStatus.RUNNING), plans.map { it.status }.toSet())
                assertTrue(plans.all { it.requiresLocalScheduleApproval })
                assertTrue(plans.single { it.status == MomentTaskStatus.PENDING }.scheduledAt > 2_000_000_000_000L)
                val before = workspace.capture(store.identity().fingerprint).use { it.snapshot }
                before.validate(); LanSyncScope.validateGraph(before)
                assertEquals(2, before.resources.size)
                assertTrue(before.records.none { it.type.contains("account") || it.type.contains("model") })
                runtime.createPreviewSample()
                while (!runtime.state.value.status.startsWith("离线样例已存在") || runtime.state.value.busy) delay(10)
                val after = workspace.capture(store.identity().fingerprint).use { it.snapshot }
                assertEquals(before.hashes(), after.hashes())
                assertEquals(before.records.size, after.records.size)
                assertContentEquals(oldBytes, Files.readAllBytes(old))
                assertEquals(2, repository.getAllTasks().size)
            } finally {
                runtime.closeAndDrain(); scope.cancel(); scope.coroutineContext[Job]!!.join()
                parent.toFile().deleteRecursively()
            }
        }
    }
}
