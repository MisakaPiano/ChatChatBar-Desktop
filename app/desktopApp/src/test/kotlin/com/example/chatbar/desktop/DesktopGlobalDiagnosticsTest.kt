package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import kotlin.test.*

class DesktopGlobalDiagnosticsTest {
    @Test fun `global log filters preserve missing version and completed-only clearing preserves running requests`() {
        val owner = DesktopTransportDiagnosticsOwner()
        val model = ModelConfig(id = "synthetic", displayName = "Synthetic", baseUrl = "https://example.test/v1",
            apiKey = "synthetic-secret", modelName = "synthetic-model", createdAt = 1)
        val a = owner.begin("a", "one", model, DesktopUsageContext(DesktopUsageSource.MOMENTS, taskId = "parent"))
        owner.begin("b", "two", model, DesktopUsageContext(DesktopUsageSource.CHAT, taskId = "other"))
        a.finish(DesktopTaskStatus.COMPLETED, null, null)
        assertEquals(listOf("a"), desktopFilteredDiagnostics(owner.entries.value, "朋友圈", "未记录版本", "COMPLETED").map { it.taskId })
        assertEquals(2, desktopFilteredDiagnostics(owner.entries.value, "全部场景", "全部版本", "全部结果").size)
        assertEquals("parent", owner.entries.value.first { it.taskId == "a" }.logicalTaskId)
        assertTrue(owner.entries.value.all { it.templateVersion == null })
        owner.clearCompleted(setOf("a", "b"))
        assertEquals(listOf("b"), owner.entries.value.map { it.taskId })
        owner.begin("c", "three", model).finish(DesktopTaskStatus.COMPLETED, null, null)
        owner.clearCompleted(setOf("a")) // Later completed requests were not in the user's snapshot.
        assertEquals(setOf("b", "c"), owner.entries.value.map { it.taskId }.toSet())
        assertFalse(owner.entries.value.toString().contains("synthetic-secret"))
    }
}
