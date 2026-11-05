package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopSessionDeletionControllerTest {
    @Test fun `confirmed deletion refuses active writers then drains draft and rejects stale viewport callbacks`() = runBlocking {
        val parent = Files.createTempDirectory("session-delete-controller-")
        val resolution = DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        Files.createDirectory(resolution.appDataRoot)
        val ownership = DesktopDataRootOwnership.acquire(resolution)
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(ownership, ownership.toString()).ownership
        val app = DesktopAppContainer(resolution, secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            app.characterRepository.save(CharacterCard("card", "合成卡", createdAt = 1, updatedAt = 1))
            app.chatRepository.createSession(ChatSession("session", "card", "合成会话", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1))
            val controller = app.primaryChatController
            controller.refresh(); controller.editComposer("retained until confirmation")
            val item = controller.state.value.sessions.single()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val writer = launch { app.taskRuntime.withMessageMutation("session") { entered.complete(Unit); release.await() } }
            entered.await()
            assertFalse(controller.deleteSession(item)); assertNotNull(app.chatRepository.getSession("session"))
            assertEquals("retained until confirmation", app.chatRepository.getSessionDraft("session"))
            release.complete(Unit); writer.join()
            assertTrue(controller.deleteSession(item)); assertTrue(controller.state.value.sessions.isEmpty())
            controller.editComposer("stale input callback")
            assertNull(controller.submitReadingPosition(ChatScrollPosition("session", capturedAt = 2)))
            controller.flushComposerForSnapshot()
            assertNull(app.chatRepository.getSession("session")); assertEquals("", app.chatRepository.getSessionDraft("session"))
            assertNull(app.chatRepository.getScrollPosition("session"))
        } finally { app.close(); owner.close(); parent.toFile().deleteRecursively() }
    }
}
