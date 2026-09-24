package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterDocumentRagCleanup
import com.example.chatbar.domain.card.CharacterPackagedImageContent
import com.example.chatbar.domain.card.CharacterResourceStore
import com.example.chatbar.domain.card.CharacterTransferPromptPolicy
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.PackagedDocument
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.card.decodeCharacterPackagedImage
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCharacterTransferGateTest {
    @Test
    fun `maintenance waits for whole character transfer admission`() = runTest {
        val root = Files.createTempDirectory("desktop-character-transfer-gate-")
        try {
            val coordinator = DesktopDataOperationCoordinator()
            val storage = JsonFileStorage(root, coordinator)
            val resources = BlockingResourceStore()
            val core = CharacterCardTransferCore(
                characterRepository = CharacterRepository(storage),
                worldBookRepository = WorldBookRepository(storage),
                formatCardRepository = FormatCardRepository(storage),
                resources = resources,
                promptPolicy = TestPromptPolicy,
                ragCleanup = CharacterDocumentRagCleanup {},
                json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
                operationGate = coordinator,
                ioDispatcher = Dispatchers.IO,
            )

            val transfer = async(Dispatchers.Default) {
                core.importNew(
                    CharacterCardPackage(
                        card = PackagedCharacterCard(name = "Card", avatarResourceId = "avatar"),
                        images = mapOf(
                            "avatar" to PackagedImage(
                                "avatar.png",
                                Base64.getEncoder().encodeToString("avatar".toByteArray()),
                            ),
                        ),
                    ),
                )
            }
            assertTrue(resources.entered.await(5, TimeUnit.SECONDS))

            val exclusiveEntered = CompletableDeferred<Unit>()
            val maintenance = launch {
                coordinator.withExclusiveMaintenance { exclusiveEntered.complete(Unit) }
            }
            withTimeout(5_000) {
                while (coordinator.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) yield()
            }
            assertFalse(exclusiveEntered.isCompleted)

            resources.release.countDown()
            val imported = transfer.await()
            maintenance.join()

            assertTrue(exclusiveEntered.isCompleted)
            assertEquals(imported, CharacterRepository(storage).getById(imported.id))
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class BlockingResourceStore : CharacterResourceStore {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val entries = mutableMapOf<String, ByteArray>()

        override fun readText(reference: String): String = String(entries.getValue(reference))
        override fun readBytes(reference: String): ByteArray = entries.getValue(reference)
        override fun fileName(reference: String): String = reference.substringAfterLast('/')

        override fun materializeDocument(
            document: PackagedDocument,
            timestamp: Long,
            resourceId: String,
        ): String = error("document not expected")

        override fun materializeImage(
            image: PackagedImage,
            timestamp: Long,
            resourceId: String,
        ): String {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release materialization" }
            val reference = "images/$resourceId.png"
            entries[reference] = (decodeCharacterPackagedImage(image.data) as CharacterPackagedImageContent.Bytes).value
            return reference
        }

        override fun deleteOwned(reference: String) {
            entries.remove(reference)
        }
    }

    private object TestPromptPolicy : CharacterTransferPromptPolicy {
        override fun defaultCharacterNaiNegativePrompt(): String = "test"
        override fun effectiveCharacterNaiNegativePrompt(value: String): String = value.ifBlank { "test" }
    }
}
