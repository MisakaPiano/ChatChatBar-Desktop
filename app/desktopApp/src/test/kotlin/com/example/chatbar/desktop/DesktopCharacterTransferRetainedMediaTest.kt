package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterTransferRetainedMediaTest {
    private class Fixture {
        val parent = Files.createTempDirectory("ccb-card-retained-media-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        fun resource(path: String): String {
            val file = root.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, "synthetic-original-$path"); return path
        }
        suspend fun card(): CharacterCard {
            val source = CharacterCard("source", "Synthetic source", greeting = "Synthetic greeting",
                avatar = resource("images/cover.png"), chatBackground = resource("images/shared.png"),
                characters = listOf(CharacterInfo("person", "Original author", appearanceImage = resource("images/person.png")),
                    CharacterInfo("unused", "Unused person", appearanceImage = resource("images/unused.png"))),
                customDocuments = listOf(DocumentInfo("doc", "source.txt", resource("documents/source.txt"), "text/plain", 1)),
                createdAt = 1, updatedAt = 1)
            app.characterRepository.save(source)
            app.characterRepository.save(CharacterCard("other", "Other", avatar = source.chatBackground, greeting = "Other greeting", createdAt = 1, updatedAt = 1))
            val session = ChatSession("session", source.id, "Synthetic session", createdAt = 1, updatedAt = 1)
            app.chatRepository.saveSession(session)
            val store = DesktopMomentStore(app.jsonFileStorage, app.dataOperationCoordinator)
            val attempt = store.begin(MomentTask("task", source.id, session.id, 1, createdAt = 1), source, session)
            store.complete(attempt, attempt.placeholder.copy(text = "Historical text", imagePath = source.avatar, isPlaceholder = false))
            return source
        }
        suspend fun close() { app.close(); check(parent.toFile().deleteRecursively()) }
    }

    @Test fun `actual container card delete and overwrite preserve historical moment author and shared image while retiring only unreferenced resources`() = runBlocking {
        for (overwrite in listOf(false, true)) {
            val f = Fixture()
            try {
                val source = f.card()
                val store = DesktopMomentStore(f.app.jsonFileStorage, f.app.dataOperationCoordinator)
                val before = store.posts()
                assertEquals(source.characters.first().appearanceImage, before.single().senderAvatar)
                if (overwrite) f.app.characterTransfers.overwrite(source.id,
                    CharacterCardPackage(card = PackagedCharacterCard(name = "Replacement", greeting = "Replacement greeting")))
                else f.app.characterTransfers.deleteCard(source.id)
                assertTrue(Files.exists(f.root.resolve(source.characters.first().appearanceImage!!)), "historical author avatar must remain")
                assertTrue(Files.exists(f.root.resolve(source.avatar!!)), "historical post image must remain")
                assertTrue(Files.exists(f.root.resolve(source.chatBackground!!)), "other card reference must remain")
                assertFalse(Files.exists(f.root.resolve(source.characters[1].appearanceImage!!)), "unreferenced person image must retire")
                assertFalse(Files.exists(f.root.resolve(source.customDocuments.single().filePath)))
                assertEquals(before, store.posts())
                assertEquals("synthetic-original-${source.avatar}", Files.readString(f.root.resolve(source.avatar!!)))
                if (overwrite) assertNotNull(f.app.characterRepository.getById(source.id)) else assertNull(f.app.characterRepository.getById(source.id))
            } finally { f.close() }
        }
    }
}
