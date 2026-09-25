package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.domain.card.CharacterCardImportRequest
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardPngPackageCodec
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.card.WorldBookPackage
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTypedTransferControllerTest {
    @Test
    fun `Character decoder imports CCB JSON CCB PNG ST JSON and ST PNG`() = runTest {
        withFixture { fixture ->
            val ccb = packageData("CCB JSON").copy(
                defaultFormatCard = FormatCardPackage(name = "Embedded format", content = "format"),
                worldBooks = listOf(WorldBook(id = "embedded", name = "Embedded lore")),
            )
            fixture.controller.importCharacter(fixture.writeJson("ccb.json", fixture.json(ccb)))
            val ccbImported = requireNotNull(fixture.container.characterRepository.getAll().firstOrNull { it.name == "CCB JSON" })
            assertNotNull(ccbImported.defaultFormatCardId)
            assertEquals(1, ccbImported.worldBookIds.size)

            val ccbPng = CharacterCardPngPackageCodec.attach(minimalPng(), packageData("CCB PNG"), fixture.container.transferJson)
            fixture.controller.importCharacter(fixture.write("ccb.png", ccbPng))
            assertTrue(fixture.container.characterRepository.getAll().any { it.name == "CCB PNG" })

            fixture.controller.importCharacter(fixture.writeJson("st.json", stV2("ST JSON")))
            assertTrue(fixture.container.characterRepository.getAll().any { it.name == "ST JSON" })

            val stPayload = Base64.getEncoder().encodeToString(stV1("ST PNG").toByteArray())
            val stPng = PngTextChunks.insertTextChunk(minimalPng(), "Chara", stPayload)
            fixture.controller.importCharacter(fixture.write("st.png", stPng))
            val stPngImported = requireNotNull(fixture.container.characterRepository.getAll().firstOrNull { it.name == "ST PNG" })
            assertFalse(Path.of(requireNotNull(stPngImported.avatar)).isAbsolute)
            assertTrue(Files.exists(fixture.root.resolve(stPngImported.avatar)))

            fixture.controller.importCharacter(fixture.write("ordinary.png", minimalPng()))
            assertTrue(fixture.controller.state.value.error?.contains("metadata") == true)
            fixture.controller.importCharacter(fixture.writeJson("bad.json", "not-json"))
            assertNotNull(fixture.controller.state.value.error)
        }
    }

    @Test
    fun `Character conflict honors preset precedence import-new overwrite and community protection`() = runTest {
        withFixture { fixture ->
            val existing = fixture.container.characterTransfers.importNew(packageData("Existing"), presetKey = "preset")
            fixture.controller.importCharacter(CharacterCardImportRequest(packageData("Different name"), presetKey = "preset"))
            val presetConflict = assertIs<DesktopPendingTransferConflict.Character>(fixture.controller.state.value.pendingConflict)
            assertEquals(existing.id, presetConflict.existingId)

            fixture.controller.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(2, fixture.container.characterRepository.getAll().size)

            fixture.controller.importCharacter(CharacterCardImportRequest(packageData("Existing", greeting = "replacement")))
            fixture.controller.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals("replacement", fixture.container.characterRepository.getById(existing.id)?.greeting)

            fixture.container.characterRepository.save(
                requireNotNull(fixture.container.characterRepository.getById(existing.id)).copy(communityItemId = "community"),
            )
            fixture.controller.importCharacter(CharacterCardImportRequest(packageData("Existing", greeting = "blocked")))
            val communityConflict = assertIs<DesktopPendingTransferConflict.Character>(fixture.controller.state.value.pendingConflict)
            assertFalse(communityConflict.overwriteAllowed)
            fixture.controller.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals("replacement", fixture.container.characterRepository.getById(existing.id)?.greeting)
            assertNotNull(fixture.controller.state.value.error)
        }
    }

    @Test
    fun `Character export JSON and rendered CCB PNG round trip through authoritative decoder`() = runTest {
        withFixture { fixture ->
            val imported = fixture.container.characterTransfers.importNew(packageData("Export"))
            val jsonTarget = fixture.root.resolve("out.json")
            fixture.picker.saves.add(jsonTarget)
            fixture.controller.exportCharacterJson(imported.id)
            assertEquals("Export", fixture.container.characterTransfers.decode(Files.readString(jsonTarget)).card.name)

            val pngTarget = fixture.root.resolve("out.png")
            fixture.picker.saves.add(pngTarget)
            fixture.controller.exportCharacterPng(imported.id)
            val decoded = fixture.container.characterTransfers.decodePng(Files.readAllBytes(pngTarget))
            assertEquals("Export", requireNotNull(decoded).card.name)
            assertNotNull(PngTextChunks.extractTextChunk(Files.readAllBytes(pngTarget), PngTextChunks.CHATBAR_CHARACTER_KEYWORD))
        }
    }

    @Test
    fun `Format and WorldBook typed import conflict actions and exports use shared services`() = runTest {
        withFixture { fixture ->
            val formatPath = fixture.writeJson("format.json", fixture.container.transferJson.encodeToString(
                FormatCardPackage.serializer(),
                FormatCardPackage(name = "Format", content = "content"),
            ))
            fixture.controller.importFormat(formatPath)
            val originalFormat = fixture.container.formatCardRepository.getAll().single()
            fixture.controller.importFormat(formatPath)
            assertIs<DesktopPendingTransferConflict.Format>(fixture.controller.state.value.pendingConflict)
            fixture.controller.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(2, fixture.container.formatCardRepository.getAll().size)
            val changedFormatPath = fixture.writeJson("format-changed.json", fixture.container.transferJson.encodeToString(
                FormatCardPackage.serializer(),
                FormatCardPackage(name = "Format", content = "changed"),
            ))
            fixture.controller.importFormat(changedFormatPath)
            fixture.controller.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals("changed", fixture.container.formatCardRepository.getById(originalFormat.id)?.content)
            val formatExport = fixture.root.resolve("format-export.json")
            fixture.picker.saves.add(formatExport)
            fixture.controller.exportFormatJson(originalFormat.id)
            assertEquals("Format", fixture.container.formatTransfers.decode(Files.readString(formatExport)).name)

            val worldPackage = WorldBookPackage(book = WorldBook(id = "incoming", name = "Lore", description = "first"))
            val worldPath = fixture.writeJson("world.json", fixture.container.transferJson.encodeToString(WorldBookPackage.serializer(), worldPackage))
            fixture.controller.importWorldBook(worldPath)
            val originalWorld = fixture.container.worldBookRepository.getAll().single()
            fixture.controller.importWorldBook(worldPath)
            assertIs<DesktopPendingTransferConflict.WorldBookConflict>(fixture.controller.state.value.pendingConflict)
            fixture.controller.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals(originalWorld.id, fixture.container.worldBookRepository.getAll().single().id)
            fixture.controller.importWorldBook(worldPath)
            fixture.controller.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(2, fixture.container.worldBookRepository.getAll().size)

            val stPath = fixture.writeJson("st-world.json", """{"name":"ST Lore","entries":{"0":{"key":["x"],"content":"value"}}}""")
            fixture.controller.importWorldBook(stPath)
            assertTrue(fixture.container.worldBookRepository.getAll().any { it.name == "ST Lore" })

            val worldExport = fixture.root.resolve("world-export.json")
            fixture.picker.saves.add(worldExport)
            fixture.controller.exportWorldBookJson(originalWorld.id)
            assertEquals("Lore", fixture.container.worldBookTransfers.decode(Files.readString(worldExport)).book.name)
            val stExport = fixture.root.resolve("world-st.json")
            fixture.picker.saves.add(stExport)
            fixture.controller.exportWorldBookSillyTavern(originalWorld.id)
            assertEquals("Lore", fixture.container.worldBookTransfers.decode(Files.readString(stExport)).book.name)
        }
    }

    @Test
    fun `picker and conflict cancellation cause no mutation`() = runTest {
        withFixture { fixture ->
            fixture.controller.chooseAndImportCharacter()
            assertTrue(fixture.container.characterRepository.getAll().isEmpty())

            val existing = fixture.container.characterTransfers.importNew(packageData("Conflict"))
            fixture.controller.exportCharacterJson(existing.id)
            assertFalse(Files.exists(fixture.root.resolve("Conflict.json")))
            fixture.controller.importCharacter(CharacterCardImportRequest(packageData("Conflict")))
            fixture.controller.resolveConflict(DesktopTransferConflictAction.CANCEL)
            assertEquals(1, fixture.container.characterRepository.getAll().size)
            assertNull(fixture.controller.state.value.pendingConflict)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val parent = Files.createTempDirectory("desktop-typed-transfer-")
        val root = Files.createDirectory(parent.resolve("root"))
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
        )
        val picker = FakePicker()
        try {
            block(Fixture(root, container, picker, container.createTypedTransferController(picker)))
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private data class Fixture(
        val root: Path,
        val container: DesktopAppContainer,
        val picker: FakePicker,
        val controller: DesktopTypedTransferController,
    ) {
        fun write(name: String, bytes: ByteArray): Path = Files.write(root.resolve(name), bytes)
        fun writeJson(name: String, value: String): Path = Files.writeString(root.resolve(name), value)
        fun json(packageData: CharacterCardPackage): String = container.transferJson.encodeToString(CharacterCardPackage.serializer(), packageData)
    }

    private class FakePicker : DesktopFilePicker {
        val opens = ArrayDeque<Path?>()
        val saves = ArrayDeque<Path?>()
        override fun pickOpenFile(type: DesktopFileType): Path? = if (opens.isEmpty()) null else opens.removeFirst()
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = if (saves.isEmpty()) null else saves.removeFirst()
    }

    private fun packageData(name: String, greeting: String = "hello") = CharacterCardPackage(
        card = PackagedCharacterCard(name = name, greeting = greeting),
    )

    private fun stV1(name: String) = """{"name":"$name","description":"desc","first_mes":"hello"}"""
    private fun stV2(name: String) = """{"spec":"chara_card_v2","data":{"name":"$name","description":"desc","first_mes":"hello"}}"""
    private fun minimalPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    )
}
