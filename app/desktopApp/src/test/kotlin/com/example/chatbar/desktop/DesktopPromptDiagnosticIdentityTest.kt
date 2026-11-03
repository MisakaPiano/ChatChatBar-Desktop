package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import kotlin.test.*

class DesktopPromptDiagnosticIdentityTest {
    @Test fun `template source identity separates stages and source revisions without incorporating user input`() {
        val generate = DesktopPromptDiagnosticContext("IMAGE_DESIGN", "GENERATE")
        val repair = generate.copy(stage = "REPAIR")
        val a = DesktopPromptDiagnosticIdentity.identify(generate, "a".repeat(64))
        assertEquals(a, DesktopPromptDiagnosticIdentity.identify(generate, "a".repeat(64)))
        assertTrue(assertNotNull(a.templateVersion).matches(Regex("[0-9a-f]{16}")))
        assertNotEquals(a.templateVersion, DesktopPromptDiagnosticIdentity.identify(repair, "a".repeat(64)).templateVersion)
        assertNotEquals(a.templateVersion, DesktopPromptDiagnosticIdentity.identify(generate, "b".repeat(64)).templateVersion)
        assertNull(DesktopPromptDiagnosticIdentity.identify(generate, null).templateVersion)
        assertNull(DesktopPromptDiagnosticIdentity.identify(generate, "not-a-template-hash").templateVersion)
        assertFailsWith<IllegalArgumentException> { DesktopPromptDiagnosticContext("input with secrets", "GENERATE") }
    }

    @Test fun `packaged template source metadata is readable and actual repair history remains filterable after clearing`() {
        assertNotNull(DesktopPromptDiagnosticIdentity.sourceSha256, "processResources must fingerprint the actual Prompt source inputs")
        val logs = DesktopTransportDiagnosticsOwner()
        val model = ModelConfig("synthetic", "Synthetic", "https://synthetic.invalid/v1", "synthetic-secret", "fixture", createdAt = 1)
        val first = DesktopPromptDiagnosticContext("MEMORY_HEAD", "SUMMARIZE")
        val repair = first.copy(stage = "REPAIR")
        val recorder = logs.begin("one", "memory", model, promptContext = first)
        recorder.recordPromptStage(first)
        recorder.recordPromptStage(repair)
        recorder.finish(DesktopTaskStatus.COMPLETED, null, null)
        val entry = logs.entries.value.single()
        assertEquals(listOf("SUMMARIZE", "REPAIR"), entry.promptStages.map { it.stage })
        assertEquals(2, entry.promptStages.map { it.templateVersion }.distinct().size)
        assertEquals(entry.promptStages.last().templateVersion, entry.templateVersion)
        assertEquals("记忆", desktopDiagnosticScenario(entry))
        entry.promptStages.forEach { stage ->
            assertEquals(listOf(entry), desktopFilteredDiagnostics(logs.entries.value, "记忆", assertNotNull(stage.templateVersion), "COMPLETED"))
        }
        assertFalse(entry.toString().contains("synthetic-secret"))
        logs.clearCompleted(setOf("one")); assertTrue(logs.entries.value.isEmpty())
    }
}
