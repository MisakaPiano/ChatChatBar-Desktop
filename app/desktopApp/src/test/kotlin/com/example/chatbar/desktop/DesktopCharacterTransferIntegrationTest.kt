package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterDocumentRagCleanup
import com.example.chatbar.domain.card.CharacterTransferPromptPolicy
import com.example.chatbar.domain.card.PackagedCharacter
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.PackagedDocument
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCharacterTransferIntegrationTest {
    @Test
    fun `container production transfer path uses authoritative prompt default`() = runTest {
        val parent = Files.createTempDirectory("desktop-character-prompt-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
        )
        try {
            val core = container.createCharacterTransferCore(CharacterDocumentRagCleanup {})
            val imported = core.importNew(
                CharacterCardPackage(
                    card = PackagedCharacterCard(
                        name = "Prompt authority",
                        defaultImageNegativePrompt = "  ",
                    ),
                ),
            )

            assertEquals(
                CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt(),
                requireNotNull(container.characterRepository.getById(imported.id)).defaultImageNegativePrompt,
            )
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persisted Character refs stay relative and relocate with app data root`() = runTest {
        val parent = Files.createTempDirectory("desktop-character-core-")
        try {
            val rootA = Files.createDirectory(parent.resolve("root-a"))
            val rootB = Files.createDirectory(parent.resolve("root-b"))
            val storageA = JsonFileStorage(rootA)
            val repositoryA = CharacterRepository(storageA)
            val resourcesA = DesktopCharacterResourceStore(rootA)
            val coreA = core(storageA, resourcesA)

            val imported = coreA.importNew(completePackage())
            val persistedA = requireNotNull(repositoryA.getById(imported.id))
            val references = buildList {
                add(requireNotNull(persistedA.avatar))
                add(requireNotNull(persistedA.chatBackground))
                add(requireNotNull(persistedA.characters.single().appearanceImage))
                add(persistedA.customDocuments.single().filePath)
            }

            assertTrue(references.all { !Path.of(it).isAbsolute })
            assertTrue(references.all { rootA.resolve(it).isRegularFile() })
            assertContentEquals("avatar".toByteArray(), resourcesA.readBytes(persistedA.avatar!!))
            assertEquals("notes", resourcesA.readText(persistedA.customDocuments.single().filePath))

            copyTree(rootA, rootB)
            val repositoryB = CharacterRepository(JsonFileStorage(rootB))
            val persistedB = requireNotNull(repositoryB.getById(imported.id))
            val resourcesB = DesktopCharacterResourceStore(rootB)
            val relocatedCore = core(JsonFileStorage(rootB), resourcesB)
            val exportedAfterRelocation = relocatedCore.decode(relocatedCore.exportJson(imported.id))

            assertEquals(persistedA.avatar, persistedB.avatar)
            assertEquals(persistedA.chatBackground, persistedB.chatBackground)
            assertEquals(persistedA.characters.single().appearanceImage, persistedB.characters.single().appearanceImage)
            assertEquals(persistedA.customDocuments.single().filePath, persistedB.customDocuments.single().filePath)
            assertTrue(references.all { resourcesB.resolveOwnedReference(it).startsWith(rootB) })
            assertTrue(references.none { resourcesB.resolveOwnedReference(it).startsWith(rootA) })
            assertContentEquals("avatar".toByteArray(), resourcesB.readBytes(persistedB.avatar!!))
            assertEquals("notes", resourcesB.readText(persistedB.customDocuments.single().filePath))
            assertEquals("notes", exportedAfterRelocation.documents.single().content)
            assertContentEquals(
                "avatar".toByteArray(),
                Base64.getDecoder().decode(exportedAfterRelocation.images.getValue("avatar").data),
            )
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun core(
        storage: JsonFileStorage,
        resources: DesktopCharacterResourceStore,
    ): CharacterCardTransferCore = CharacterCardTransferCore(
        characterRepository = CharacterRepository(storage),
        worldBookRepository = WorldBookRepository(storage),
        formatCardRepository = FormatCardRepository(storage),
        resources = resources,
        promptPolicy = TestPromptPolicy,
        ragCleanup = CharacterDocumentRagCleanup {},
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        ioDispatcher = Dispatchers.IO,
    )

    private fun completePackage(): CharacterCardPackage = CharacterCardPackage(
        card = PackagedCharacterCard(
            name = "Desktop Card",
            avatarResourceId = "avatar",
            chatBackgroundResourceId = "background",
            characters = listOf(
                PackagedCharacter(name = "Alice", appearanceImageResourceId = "appearance"),
            ),
        ),
        images = linkedMapOf(
            "avatar" to image("avatar.png", "avatar"),
            "background" to image("background.jpg", "background"),
            "appearance" to image("appearance.webp", "appearance"),
        ),
        documents = listOf(PackagedDocument("notes.txt", "txt", "notes")),
    )

    private fun image(name: String, content: String): PackagedImage = PackagedImage(
        fileName = name,
        data = Base64.getEncoder().encodeToString(content.toByteArray()),
    )

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.sorted().forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) {
                    if (!Files.exists(target)) Files.createDirectory(target)
                } else {
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private object TestPromptPolicy : CharacterTransferPromptPolicy {
        override fun defaultCharacterNaiNegativePrompt(): String = "test"
        override fun effectiveCharacterNaiNegativePrompt(value: String): String = value.ifBlank { "test" }
    }
}
