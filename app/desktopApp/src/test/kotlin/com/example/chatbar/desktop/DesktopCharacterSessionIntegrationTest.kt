package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MessageRole
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DesktopCharacterSessionIntegrationTest {
    @Test
    fun `container shared session service persists session and opening greeting`() = runTest {
        val parent = Files.createTempDirectory("desktop-character-session-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = parent.resolve("bootstrap.json"),
            )
        )
        try {
            val character = CharacterCard.create("Desktop Character", greeting = "Desktop greeting")
            container.characterRepository.save(character)

            val sessionId = container.characterSessionService.createSessionForCharacter(character.id)

            val session = requireNotNull(container.chatRepository.getSession(sessionId))
            assertEquals(character.id, session.characterCardId)
            assertEquals("Desktop Character", session.title)
            val greeting = container.chatRepository.getMessages(sessionId).single()
            assertEquals(MessageRole.ASSISTANT, greeting.role)
            assertEquals("Desktop greeting", greeting.content)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
