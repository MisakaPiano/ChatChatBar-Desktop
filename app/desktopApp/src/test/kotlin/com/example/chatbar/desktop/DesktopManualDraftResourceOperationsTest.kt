package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopManualDraftResourceOperationsTest {
    @Test fun `manual crop and text stage reject during maintenance and discard holds draft files until one admitted cleanup`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("ccb-manual-draft-operations-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val png = DesktopImageEditing.png(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB))
        var release = CompletableDeferred<Unit>(); var maintenance: Job? = null
        suspend fun hold() {
            release = CompletableDeferred()
            val entered = CompletableDeferred<Unit>()
            maintenance = launch(Dispatchers.Default) { app.dataOperationCoordinator.withExclusiveMaintenance { entered.complete(Unit); release.await() } }
            withTimeout(5000) { entered.await() }
        }
        try {
            Files.createDirectories(root.resolve("documents")); Files.writeString(root.resolve("documents/source.txt"), "original source")
            val card = CharacterCard("source", "Synthetic", greeting = "Greeting",
                characters = listOf(CharacterInfo("person", "Person")),
                customDocuments = listOf(DocumentInfo("doc", "source.txt", "documents/source.txt", "text/plain", 1)), createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            val editor = app.characterEditorController; editor.openExisting(card.id)
            val before = editor.state.value.card
            hold()
            val changes = listOf<() -> Unit>({ editor.cropAvatar(png) }, { editor.cropBackground(png) },
                { editor.cropAppearance("person", png) }, { editor.addTextDocument("new.txt", "new text") },
                { editor.editDocument("doc", "edited.txt", "edited text") })
            changes.forEach { change ->
                change(); assertEquals(before, editor.state.value.card)
                assertEquals(CharacterEditorProblem.RESOURCE_FAILED, editor.state.value.problem)
                assertFalse(Files.exists(root.resolve("draft_assets")))
            }
            release.complete(Unit); maintenance!!.join()
            changes.forEach { it() }
            editor.flushDraft()
            assertNull(editor.state.value.problem)
            val staged = assertNotNull(editor.state.value.card)
            val references = listOfNotNull(staged.avatar, staged.chatBackground, staged.characters.single().appearanceImage) + staged.customDocuments.map { it.filePath }
            assertTrue(references.all { it.startsWith("draft_assets/") && Files.isRegularFile(root.resolve(it)) })
            assertEquals("original source", Files.readString(root.resolve("documents/source.txt")))
            hold()
            val discard = async(start = CoroutineStart.UNDISPATCHED) { editor.discard() }
            assertFalse(discard.isCompleted)
            assertTrue(references.all { Files.isRegularFile(root.resolve(it)) })
            release.complete(Unit); maintenance!!.join(); withTimeout(5000) { discard.await() }
            assertNull(editor.state.value.card); assertTrue(references.none { Files.exists(root.resolve(it)) })
            assertEquals("original source", Files.readString(root.resolve("documents/source.txt")))
        } finally { release.complete(Unit); maintenance?.join(); app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
