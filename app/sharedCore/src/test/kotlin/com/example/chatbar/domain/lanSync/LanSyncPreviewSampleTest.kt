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
            val oldSession = ChatSession("ccb-lan-preview-sample-session", "ccb-lan-preview-sample-card", "旧版样例会话",
                longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1)
            val oldMessage = ChatMessage("ccb-lan-preview-sample-message", oldSession.id, MessageRole.ASSISTANT, "旧版本1",
                alternatives = listOf("旧版本0", "旧版本1"), alternativeVersionIds = listOf("sample-version-0", "sample-version-1"),
                currentAlternativeIndex = 1, currentAlternativeVersionId = "sample-version-1", createdAt = 1, updatedAt = 1,
                orderKey = 1_000_000)
            val oldSessionPath = data.resolve("entities/chat_sessions/${oldSession.id}.json")
            val oldMessagePath = data.resolve("entities/chat_messages/${oldSession.id}_${oldMessage.id}.json")
            Files.createDirectories(oldSessionPath.parent); Files.createDirectories(oldMessagePath.parent)
            val oldSessionBytes = lanSyncJson.encodeToJsonElement(ChatSession.serializer(), oldSession).toString().toByteArray()
            val oldMessageBytes = lanSyncJson.encodeToJsonElement(ChatMessage.serializer(), oldMessage).toString().toByteArray()
            Files.write(oldSessionPath, oldSessionBytes); Files.write(oldMessagePath, oldMessageBytes)
            val originalFiles = mapOf(old to oldBytes, oldSessionPath to oldSessionBytes, oldMessagePath to oldMessageBytes)
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
                originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
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
                val versionIds = before.records.filter { it.type == "chat_messages" }.flatMap { message ->
                    message.body.getValue("alternativeVersionIds").jsonArray.map { it.jsonPrimitive.content }
                }
                assertEquals(4, versionIds.size); assertEquals(versionIds.size, versionIds.distinct().size)
                assertTrue(before.records.none { it.type.contains("account") || it.type.contains("model") })
                runtime.createPreviewSample()
                while (!runtime.state.value.status.startsWith("离线样例已存在") || runtime.state.value.busy) delay(10)
                val after = workspace.capture(store.identity().fingerprint).use { it.snapshot }
                assertEquals(before.hashes(), after.hashes())
                assertEquals(before.records.size, after.records.size)
                originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
                assertEquals(2, repository.getAllTasks().size)
            } finally {
                runtime.closeAndDrain(); scope.cancel(); scope.coroutineContext[Job]!!.join()
                parent.toFile().deleteRecursively()
            }
        }
    }
}
