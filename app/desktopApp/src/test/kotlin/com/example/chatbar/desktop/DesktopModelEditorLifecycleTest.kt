package com.example.chatbar.desktop

import androidx.compose.ui.input.key.Key
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopModelEditorLifecycleTest {
    @Test
    fun `template entry is clean and field or credential intent makes dedicated editor dirty`() = runBlocking {
        withController { controller ->
            controller.startCreate(ModelTemplate.CLAUDE)
            assertEquals(ModelTemplate.CLAUDE, controller.state.value.editor?.templateType)
            assertFalse(controller.state.value.editorDirty)
            controller.editModel { it.copy(displayName = "Claude model") }
            assertTrue(controller.state.value.editorDirty)
            controller.closeEditor()
            assertTrue(controller.state.value.leavePrompt)
            assertTrue(controller.state.value.editor != null)
            controller.continueEditing()
            assertFalse(controller.state.value.leavePrompt)
            controller.replaceModelCredential("fake-new-key")
            assertTrue(controller.state.value.editorDirty)
            assertFalse(controller.state.value.toString().contains("fake-new-key"))
        }
    }

    @Test
    fun `discard and save leave paths are explicit and successful save resets baseline`() = runBlocking {
        withController { controller ->
            controller.startCreate(ModelTemplate.CUSTOM)
            controller.editModel { it.copy(displayName = "Discard me", modelName = "discard") }
            var left = false
            controller.requestLeave { left = true }
            assertFalse(left)
            controller.resolveLeave(save = false)
            assertTrue(left)
            assertNull(controller.state.value.editor)
            assertTrue(controller.state.value.models.isEmpty())

            controller.startCreate(ModelTemplate.CUSTOM)
            controller.editModel { it.copy(displayName = "Saved", baseUrl = "https://example.invalid/v1", modelName = "saved") }
            assertTrue(controller.state.value.editorDirty)
            controller.saveModel()
            assertFalse(controller.state.value.editorDirty)
            controller.editModel { it.copy(displayName = "Saved edit") }
            controller.startCreate(ModelTemplate.GEMINI)
            assertTrue(controller.state.value.leavePrompt)
            assertEquals("Saved edit", controller.state.value.editor?.displayName)
            controller.continueEditing()
            controller.requestLeave { left = true }
            controller.resolveLeave(save = true)
            assertFalse(controller.state.value.editorDirty)
            assertNull(controller.state.value.editor)
            assertEquals("Saved edit", controller.state.value.models.single().displayName)
        }
    }

    @Test
    fun `keyboard commands map Ctrl S to save and Escape to guarded close`() {
        assertEquals(DesktopModelEditorCommand.SAVE, desktopModelEditorCommand(Key.S, true))
        assertEquals(DesktopModelEditorCommand.CLOSE, desktopModelEditorCommand(Key.Escape, false))
        assertNull(desktopModelEditorCommand(Key.S, false))
    }

    private suspend fun withController(block: suspend (DesktopModelSettingsController) -> Unit) {
        val parent = Files.createTempDirectory("desktop-editor-lifecycle-")
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try { block(container.modelSettingsController) } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
