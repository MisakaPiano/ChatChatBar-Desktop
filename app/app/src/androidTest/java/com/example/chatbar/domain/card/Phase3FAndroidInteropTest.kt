package com.example.chatbar.domain.card

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.interop.Phase3FInteropFixture
import com.example.chatbar.ui.manage.ManageViewModel
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase3FAndroidInteropTest {
    private val app: ChatBarApp = ApplicationProvider.getApplicationContext()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun androidProductionPathsProduceConsumeAndRejectAtomically() = runBlocking {
        cleanupFixtureEntities()
        val output = requireNotNull(app.getExternalFilesDir(null)).resolve("phase3f/android")
        output.deleteRecursively()
        check(output.mkdirs()) { "Unable to create Android interoperability output: $output" }

        produceCharacter(Phase3FInteropFixture.structuredCharacter, "structured", output)
        produceCharacter(Phase3FInteropFixture.freeformCharacter, "freeform", output)

        val format = app.formatCardTransferService.importNew(Phase3FInteropFixture.standaloneFormat)
        output.resolve("format.json").writeText(app.formatCardTransferService.exportJson(format.id))

        val world = app.worldBookTransferService.importNew(Phase3FInteropFixture.standaloneWorldBook)
        output.resolve("worldbook-chatbar.json").writeText(app.worldBookTransferService.exportJson(world.id))
        output.resolve("worldbook-sillytavern.json").writeText(app.worldBookTransferService.exportSillyTavernJson(world.id))

        cleanupFixtureEntities()
        consumeCharacterAsset("desktop/structured.json", false, Phase3FInteropFixture.structuredCharacter)
        consumeCharacterAsset("desktop/structured.png", true, Phase3FInteropFixture.structuredCharacter)
        consumeCharacterAsset("desktop/freeform.json", false, Phase3FInteropFixture.freeformCharacter)
        consumeCharacterAsset("desktop/freeform.png", true, Phase3FInteropFixture.freeformCharacter)
        consumeFormatAsset("desktop/format.json")
        consumeWorldBookAssets("desktop/worldbook-chatbar.json", "desktop/worldbook-sillytavern.json")

        verifyContentResolverIngress()
        verifyInvalidArtifactsAreAtomic()
        cleanupFixtureEntities()
    }

    private suspend fun produceCharacter(packageData: CharacterCardPackage, stem: String, output: File) {
        val imported = app.characterCardTransferService.importNew(packageData)
        val json = app.characterCardTransferService.exportJson(imported.id)
        output.resolve("$stem.json").writeText(json)
        output.resolve("$stem.png").writeBytes(
            app.characterCardTransferService.exportPng(
                imported.id,
                CharacterCardPngExportOptions(sizePx = 512),
            ),
        )
        assertCharacterSemantics(packageData, app.characterCardTransferService.decode(json))
        assertAndroidReferences(imported)
    }

    private suspend fun consumeCharacterAsset(
        assetName: String,
        isPng: Boolean,
        expected: CharacterCardPackage,
    ) {
        val bytes = testAssets.open(assetName).use { it.readBytes() }
        val decoded = if (isPng) {
            requireNotNull(app.characterCardTransferService.decodePng(bytes))
        } else {
            app.characterCardTransferService.decode(bytes.toString(Charsets.UTF_8))
        }
        assertCharacterSemantics(expected, decoded)
        val imported = app.characterCardTransferService.importNew(decoded)
        assertAndroidReferences(imported)
        val reexported = app.characterCardTransferService.decode(
            app.characterCardTransferService.exportJson(imported.id),
        )
        assertCharacterSemantics(expected, reexported)
        app.characterCardTransferService.deleteCard(imported.id)
    }

    private suspend fun consumeFormatAsset(assetName: String) {
        val raw = testAssets.open(assetName).bufferedReader().use { it.readText() }
        val decoded = app.formatCardTransferService.decode(raw)
        assertEquals(
            Phase3FInteropFixture.normalizeFormat(Phase3FInteropFixture.standaloneFormat),
            Phase3FInteropFixture.normalizeFormat(decoded),
        )
        val imported = app.formatCardTransferService.importNew(decoded)
        val reexported = app.formatCardTransferService.decode(app.formatCardTransferService.exportJson(imported.id))
        assertEquals(
            Phase3FInteropFixture.normalizeFormat(Phase3FInteropFixture.standaloneFormat),
            Phase3FInteropFixture.normalizeFormat(reexported),
        )
        app.formatCardRepository.delete(imported.id)
    }

    private suspend fun consumeWorldBookAssets(chatBarAsset: String, sillyTavernAsset: String) {
        val raw = testAssets.open(chatBarAsset).bufferedReader().use { it.readText() }
        val decoded = app.worldBookTransferService.decode(raw)
        assertEquals(
            Phase3FInteropFixture.normalizeWorldBook(Phase3FInteropFixture.standaloneWorldBook),
            Phase3FInteropFixture.normalizeWorldBook(decoded),
        )
        val imported = app.worldBookTransferService.importNew(decoded)
        val reexported = app.worldBookTransferService.decode(app.worldBookTransferService.exportJson(imported.id))
        assertEquals(
            Phase3FInteropFixture.normalizeWorldBook(Phase3FInteropFixture.standaloneWorldBook),
            Phase3FInteropFixture.normalizeWorldBook(reexported),
        )
        app.worldBookRepository.delete(imported.id)

        val stRaw = testAssets.open(sillyTavernAsset).bufferedReader().use { it.readText() }
        val stDecoded = app.worldBookTransferService.decode(stRaw)
        assertEquals(Phase3FInteropFixture.WORLD_BOOK_NAME, stDecoded.book.name)
        assertEquals(2, stDecoded.book.entries.size)
        assertEquals(listOf("Alpha", "/beta/i"), stDecoded.book.entries.first().keys)
    }

    private suspend fun verifyContentResolverIngress() {
        cleanupFixtureEntities()
        val providerDirectory = app.filesDir.resolve("images/phase3f-provider").apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to create provider fixture directory" }
        }
        val viewModel = ManageViewModel()
        try {
            val jsonFile = providerDirectory.resolve("structured.json").apply {
                writeBytes(testAssets.open("desktop/structured.json").use { it.readBytes() })
            }
            val jsonUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", jsonFile)
            val jsonRequest = viewModel.decodeCharacterImport(jsonUri, app)
            val jsonImported = app.characterCardTransferService.importNew(jsonRequest.packageData)
            assertCharacterSemantics(Phase3FInteropFixture.structuredCharacter, jsonRequest.packageData)
            app.characterCardTransferService.deleteCard(jsonImported.id)

            val pngFile = providerDirectory.resolve("freeform.png").apply {
                writeBytes(testAssets.open("desktop/freeform.png").use { it.readBytes() })
            }
            val pngUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", pngFile)
            val pngRequest = viewModel.decodeCharacterImport(pngUri, app)
            val pngImported = app.characterCardTransferService.importNew(pngRequest.packageData)
            assertCharacterSemantics(Phase3FInteropFixture.freeformCharacter, pngRequest.packageData)
            app.characterCardTransferService.deleteCard(pngImported.id)
        } finally {
            providerDirectory.deleteRecursively()
        }
    }

    private suspend fun verifyInvalidArtifactsAreAtomic() {
        cleanupFixtureEntities()
        val initialCharacters = app.characterRepository.getAll().size
        val initialFormats = app.formatCardRepository.getAll().size
        val initialWorldBooks = app.worldBookRepository.getAll().size
        val initialOwnedFiles = ownedFiles()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                app.characterCardTransferService.importNew(
                    Phase3FInteropFixture.structuredCharacter.copy(schemaVersion = 10),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                app.characterCardTransferService.importNew(
                    Phase3FInteropFixture.structuredCharacter.copy(
                        card = Phase3FInteropFixture.structuredCharacter.card.copy(
                            avatarResourceId = "missing-image",
                        ),
                    ),
                )
            }
        }
        assertThrows(Exception::class.java) {
            app.characterCardTransferService.decode("{malformed")
        }
        assertNull(app.characterCardTransferService.decodePng(minimalPng()))
        val corruptPng = PngTextChunks.insertTextChunk(
            minimalPng(),
            PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
            "not-valid-base64@@@",
        )
        assertThrows(Exception::class.java) {
            app.characterCardTransferService.decodePng(corruptPng)
        }

        assertEquals(initialCharacters, app.characterRepository.getAll().size)
        assertEquals(initialFormats, app.formatCardRepository.getAll().size)
        assertEquals(initialWorldBooks, app.worldBookRepository.getAll().size)
        assertEquals(initialOwnedFiles, ownedFiles())
    }

    private suspend fun cleanupFixtureEntities() {
        app.characterRepository.getAll()
            .filter { it.name == Phase3FInteropFixture.STRUCTURED_NAME || it.name == Phase3FInteropFixture.FREEFORM_NAME }
            .forEach { app.characterCardTransferService.deleteCard(it.id) }
        app.formatCardRepository.getAll()
            .filter { it.name == Phase3FInteropFixture.FORMAT_NAME || it.name == Phase3FInteropFixture.defaultFormat.name }
            .forEach { app.formatCardRepository.delete(it.id) }
        app.worldBookRepository.getAll()
            .filter { it.name == Phase3FInteropFixture.WORLD_BOOK_NAME || it.name == Phase3FInteropFixture.embeddedWorldBook.name }
            .forEach { app.worldBookRepository.delete(it.id) }
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

    private fun assertAndroidReferences(card: CharacterCard) {
        val references = buildList {
            card.avatar?.let(::add)
            card.chatBackground?.let(::add)
            card.characters.mapNotNullTo(this) { it.appearanceImage }
            card.customDocuments.mapTo(this) { it.filePath }
        }
        assertTrue(references.isNotEmpty())
        assertTrue(references.all { File(it).isAbsolute })
        assertTrue(references.all { File(it).isFile })
        assertFalse(references.any { it.contains("appDataRoot") })
    }

    private fun ownedFiles(): Set<String> = listOf(
        app.filesDir.resolve("images"),
        app.filesDir.resolve("documents"),
    ).flatMap { directory ->
        directory.walkTopDown().filter(File::isFile).map(File::getCanonicalPath).toList()
    }.toSet()

    private fun minimalPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    )

}
