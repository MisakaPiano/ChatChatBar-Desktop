@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterCopyRetryUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `changed writable source keeps the real copy button after a failed attachment fork`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("copy-retry-ui-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val recovery = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
            app.worldBookRepository, app.formatCardRepository, app.chatRepository,
            DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json)
        try {
            val card = CharacterCard.create("Synthetic conflict", greeting = "Hello")
            app.characterRepository.save(card)
            val editor = app.characterEditorController
            editor.openExisting(card.id)
            editor.addTextDocument("retry.txt", "COPY_RETRY_BYTES")
            assertEquals("retry.txt", editor.state.value.card!!.customDocuments.single().fileName)
            editor.flushDraft()
            val path = app.appDataRoot.resolve(editor.state.value.card!!.customDocuments.single().filePath)
            app.characterRepository.save(card.copy(creatorNotes = "External newer source"))
            recovery.openExisting(card.id)
            assertEquals(CharacterEditorProblem.SOURCE_CHANGED, recovery.state.value.problem)
            Files.delete(path)
            recovery.saveAsNew()
            assertEquals(CharacterEditorProblem.SOURCE_CHANGED, recovery.state.value.problem)
            val management = app.createManagementController(app.createTypedTransferController())
            var label = ""
            val scene = ImageComposeScene(1000, 1600, coroutineContext = edt) {
                label = LocalDesktopUiStrings.current(DesktopUiText.SAVE_AS_NEW)
                DesktopCharacterManagementPanel(recovery, management)
            }
            try {
                suspend fun frames() { repeat(6) { scene.render(System.nanoTime()).close(); delay(20) } }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                fun hasLabel(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || node.children.any(::hasLabel)
                frames()
                val button = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    .firstOrNull { it.config.contains(SemanticsActions.OnClick) && hasLabel(it) }
                assertNotNull(button, "Attachment failure hid the existing source-conflict copy action")
                Files.writeString(path, "COPY_RETRY_BYTES")
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                withTimeout(8_000) { while (recovery.state.value.targetId != null) frames() }
                assertNull(recovery.state.value.problem)
                recovery.flushDraft()
                val draft = assertNotNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertEquals("COPY_RETRY_BYTES", Files.readString(app.appDataRoot.resolve(draft.characterPayload!!.customDocuments.single().filePath)))
            } finally { scene.close() }
        } finally {
            recovery.closeAndDrain(); app.close(); parent.toFile().deleteRecursively()
        }
    }
}
