package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking

/** Opt-in package-gate companion on the test classpath. Never included in the distribution. */
internal object DesktopShutdownFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2 && args[0] in setOf("seed", "verify"))
        val root = Path.of(args[1]).toAbsolutePath().normalize()
        require(root.toString().contains("phase8-s0d-gate")) { "Only disposable S0D gate profiles are permitted" }
        Files.createDirectories(root)
        val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolveSibling("unused.json"))
        val ownership = (DesktopDataRootOwnership.acquire(resolution) as DesktopDataRootOwnershipResult.Acquired).ownership
        ownership.use {
            val container = DesktopAppContainer(resolution, secretStoreFactory = { InMemoryDesktopSecretStore() })
            try {
                val media = root.resolve("images/shutdown-fixture.png")
                val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/foAAAAASUVORK5CYII=")
                if (args[0] == "seed") {
                    check(container.characterRepository.getAll().isEmpty())
                    container.characterRepository.save(CharacterCard.create("S0D synthetic fixture").copy(id = "s0d-character"))
                    container.chatRepository.createSession(ChatSession("s0d-session", "s0d-character", "S0D synthetic session", createdAt = 1, updatedAt = 1))
                    Files.createDirectories(media.parent)
                    Files.write(media, bytes)
                    container.chatRepository.addMessage(ChatMessage("s0d-message", "s0d-session", MessageRole.USER,
                        "S0D persisted synthetic message", images = listOf(media.toString()), createdAt = 1, updatedAt = 1))
                    container.chatRepository.updateSessionDraft("s0d-session", "S0D persisted draft")
                }
                check(container.characterRepository.getById("s0d-character")?.name == "S0D synthetic fixture")
                check(container.chatRepository.getSession("s0d-session")?.characterCardId == "s0d-character")
                val messages = container.chatRepository.getMessages("s0d-session")
                check(messages.size == 1 && messages.single().id == "s0d-message")
                check(messages.single().content == "S0D persisted synthetic message")
                check(container.chatRepository.getSessionDraft("s0d-session") == "S0D persisted draft")
                check(Files.readAllBytes(media).contentEquals(bytes))
                println("S0D_SYNTHETIC_DATA_AND_LOCK_PASS")
            } finally { container.close() }
        }
    }
}
