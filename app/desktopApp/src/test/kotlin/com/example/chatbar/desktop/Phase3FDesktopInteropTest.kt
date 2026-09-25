package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.domain.card.CharacterCardPngPackageCodec
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.interop.Phase3FInteropFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase3FDesktopInteropTest {
    @Test
    fun `Desktop production paths produce and consume canonical artifacts`() = runTest {
        val configuredRoot = System.getenv("PHASE3F_ARTIFACT_ROOT")?.let(Path::of)
        val ownedRoot = configuredRoot == null
        val artifactRoot = configuredRoot ?: Files.createTempDirectory("phase3f-artifacts-")
        val desktopOutput = artifactRoot.resolve("desktop")
        desktopOutput.toFile().deleteRecursively()
        Files.createDirectories(desktopOutput)
        try {
            val produced = Files.createTempDirectory("phase3f-desktop-producer-")
            try {
                withContainer(produced) { container ->
                    produceCharacter(container, Phase3FInteropFixture.structuredCharacter, "structured", desktopOutput)
                    produceCharacter(container, Phase3FInteropFixture.freeformCharacter, "freeform", desktopOutput)

                    val format = container.formatTransfers.importNew(Phase3FInteropFixture.standaloneFormat)
                    writeText(desktopOutput.resolve("format.json"), container.formatTransfers.exportJson(format.id))

                    val world = container.worldBookTransfers.importNew(Phase3FInteropFixture.standaloneWorldBook)
                    writeText(desktopOutput.resolve("worldbook-chatbar.json"), container.worldBookTransfers.exportJson(world.id))
                    writeText(desktopOutput.resolve("worldbook-sillytavern.json"), container.worldBookTransfers.exportSillyTavernJson(world.id))
                }
            } finally {
                produced.toFile().deleteRecursively()
            }

            consumeCharacterArtifact(
                desktopOutput.resolve("structured.json"),
                isPng = false,
                expected = Phase3FInteropFixture.structuredCharacter,
            )
            consumeCharacterArtifact(
                desktopOutput.resolve("structured.png"),
                isPng = true,
                expected = Phase3FInteropFixture.structuredCharacter,
            )
            consumeCharacterArtifact(
                desktopOutput.resolve("freeform.json"),
                isPng = false,
                expected = Phase3FInteropFixture.freeformCharacter,
            )
            consumeCharacterArtifact(
                desktopOutput.resolve("freeform.png"),
                isPng = true,
                expected = Phase3FInteropFixture.freeformCharacter,
            )
            consumeFormatArtifact(desktopOutput.resolve("format.json"))
            consumeWorldBookArtifacts(
                desktopOutput.resolve("worldbook-chatbar.json"),
                desktopOutput.resolve("worldbook-sillytavern.json"),
            )
        } finally {
            if (ownedRoot) artifactRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Desktop consumes Android production artifacts`() = runTest {
        val androidRoot = System.getenv("PHASE3F_ANDROID_ARTIFACT_ROOT")?.let(Path::of)
        if (androidRoot == null) {
            check(System.getenv("PHASE3F_REQUIRE_ANDROID") != "true") {
                "PHASE3F_ANDROID_ARTIFACT_ROOT is required for the cross-platform gate"
            }
            return@runTest
        }
        consumeCharacterArtifact(androidRoot.resolve("structured.json"), false, Phase3FInteropFixture.structuredCharacter)
        consumeCharacterArtifact(androidRoot.resolve("structured.png"), true, Phase3FInteropFixture.structuredCharacter)
        consumeCharacterArtifact(androidRoot.resolve("freeform.json"), false, Phase3FInteropFixture.freeformCharacter)
        consumeCharacterArtifact(androidRoot.resolve("freeform.png"), true, Phase3FInteropFixture.freeformCharacter)
        consumeFormatArtifact(androidRoot.resolve("format.json"))
        consumeWorldBookArtifacts(
            androidRoot.resolve("worldbook-chatbar.json"),
            androidRoot.resolve("worldbook-sillytavern.json"),
        )
    }

    @Test
    fun `Desktop rejects invalid cross-platform character artifacts atomically`() = runTest {
        val parent = Files.createTempDirectory("phase3f-desktop-invalid-")
        try {
            withContainer(parent) { container ->
                val initialCharacters = container.characterRepository.getAll().size
                val initialFormats = container.formatCardRepository.getAll().size
                val initialWorldBooks = container.worldBookRepository.getAll().size

                assertFails {
                    container.characterTransfers.importNew(
                        Phase3FInteropFixture.structuredCharacter.copy(schemaVersion = 10),
                    )
                }
                val missing = Phase3FInteropFixture.structuredCharacter.copy(
                    card = Phase3FInteropFixture.structuredCharacter.card.copy(avatarResourceId = "missing-image"),
                )
                assertFails { container.characterTransfers.importNew(missing) }
                assertFails { container.characterTransfers.decode("{malformed") }
                assertNull(container.characterTransfers.decodePng(minimalPng()))
                val corruptPng = PngTextChunks.insertTextChunk(
                    minimalPng(),
                    PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
                    "not-valid-base64@@@",
                )
                assertFails { container.characterTransfers.decodePng(corruptPng) }

                assertEquals(initialCharacters, container.characterRepository.getAll().size)
                assertEquals(initialFormats, container.formatCardRepository.getAll().size)
                assertEquals(initialWorldBooks, container.worldBookRepository.getAll().size)
                assertFalse(Files.exists(container.appDataRoot.resolve("images")))
                assertFalse(Files.exists(container.appDataRoot.resolve("documents")))
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private suspend fun produceCharacter(
        container: DesktopAppContainer,
        fixture: CharacterCardPackage,
        stem: String,
        output: Path,
    ) {
        val imported = container.characterTransfers.importNew(fixture)
        val exportedJson = container.characterTransfers.exportJson(imported.id)
        writeText(output.resolve("$stem.json"), exportedJson)
        val export = container.characterTransfers.prepareExport(imported.id)
        val backgroundBytes = export.packageData.card.chatBackgroundResourceId
            ?.let(export.packageData.images::get)
            ?.let { Base64.getMimeDecoder().decode(it.data) }
        val rendered = container.characterPngRenderer.render(
            export.card,
            CharacterCardPngExportOptions(sizePx = 512),
            backgroundBytes,
        )
        Files.write(
            output.resolve("$stem.png"),
            CharacterCardPngPackageCodec.attach(rendered, export.packageData, container.transferJson),
        )
        assertCharacterSemantics(fixture, container.characterTransfers.decode(exportedJson))
        assertDesktopReferences(imported)
    }

    private suspend fun consumeCharacterArtifact(path: Path, isPng: Boolean, expected: CharacterCardPackage) {
        assertTrue(path.isRegularFile(), "Missing interoperability artifact: $path")
        val parent = Files.createTempDirectory("phase3f-desktop-consumer-")
        try {
            withContainer(parent) { container ->
                val decoded = if (isPng) {
                    assertNotNull(container.characterTransfers.decodePng(Files.readAllBytes(path)))
                } else {
                    container.characterTransfers.decode(Files.readString(path))
                }
                assertCharacterSemantics(expected, decoded)
                val imported = container.characterTransfers.importNew(decoded)
                assertDesktopReferences(imported)
                val reexported = container.characterTransfers.decode(container.characterTransfers.exportJson(imported.id))
                assertCharacterSemantics(expected, reexported)
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private suspend fun consumeFormatArtifact(path: Path) {
        assertTrue(path.isRegularFile(), "Missing interoperability artifact: $path")
        val parent = Files.createTempDirectory("phase3f-desktop-format-")
        try {
            withContainer(parent) { container ->
                val decoded = container.formatTransfers.decode(Files.readString(path))
                assertEquals(
                    Phase3FInteropFixture.normalizeFormat(Phase3FInteropFixture.standaloneFormat),
                    Phase3FInteropFixture.normalizeFormat(decoded),
                )
                val imported = container.formatTransfers.importNew(decoded)
                val reexported = container.formatTransfers.decode(container.formatTransfers.exportJson(imported.id))
                assertEquals(
                    Phase3FInteropFixture.normalizeFormat(Phase3FInteropFixture.standaloneFormat),
                    Phase3FInteropFixture.normalizeFormat(reexported),
                )
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private suspend fun consumeWorldBookArtifacts(chatBarPath: Path, sillyTavernPath: Path) {
        assertTrue(chatBarPath.isRegularFile(), "Missing interoperability artifact: $chatBarPath")
        assertTrue(sillyTavernPath.isRegularFile(), "Missing interoperability artifact: $sillyTavernPath")
        val parent = Files.createTempDirectory("phase3f-desktop-worldbook-")
        try {
            withContainer(parent) { container ->
                val decoded = container.worldBookTransfers.decode(Files.readString(chatBarPath))
                assertEquals(
                    Phase3FInteropFixture.normalizeWorldBook(Phase3FInteropFixture.standaloneWorldBook),
                    Phase3FInteropFixture.normalizeWorldBook(decoded),
                )
                val imported = container.worldBookTransfers.importNew(decoded)
                val reexported = container.worldBookTransfers.decode(container.worldBookTransfers.exportJson(imported.id))
                assertEquals(
                    Phase3FInteropFixture.normalizeWorldBook(Phase3FInteropFixture.standaloneWorldBook),
                    Phase3FInteropFixture.normalizeWorldBook(reexported),
                )
                val stDecoded = container.worldBookTransfers.decode(Files.readString(sillyTavernPath))
                assertEquals(Phase3FInteropFixture.WORLD_BOOK_NAME, stDecoded.book.name)
                assertEquals(2, stDecoded.book.entries.size)
                assertEquals(listOf("Alpha", "/beta/i"), stDecoded.book.entries.first().keys)
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun assertCharacterSemantics(expected: CharacterCardPackage, actual: CharacterCardPackage) {
        assertEquals(
            Phase3FInteropFixture.normalizeCharacter(expected),
            Phase3FInteropFixture.normalizeCharacter(actual),
        )
        assertEquals(
            Phase3FInteropFixture.imageRoleHashes(expected),
            Phase3FInteropFixture.imageRoleHashes(actual),
        )
    }

    private fun assertDesktopReferences(card: CharacterCard) {
        val references = buildList {
            card.avatar?.let(::add)
            card.chatBackground?.let(::add)
            card.characters.mapNotNullTo(this) { it.appearanceImage }
            card.customDocuments.mapTo(this) { it.filePath }
        }
        assertTrue(references.isNotEmpty())
        assertTrue(references.none { Path.of(it).isAbsolute }, "Desktop references must remain root-relative: $references")
    }

    private suspend fun withContainer(parent: Path, block: suspend (DesktopAppContainer) -> Unit) {
        val root = Files.createDirectories(parent.resolve("root"))
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
        )
        try {
            block(container)
        } finally {
            container.close()
        }
    }

    private fun writeText(path: Path, content: String) {
        Files.writeString(path, content)
    }

    private fun minimalPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    )
}
