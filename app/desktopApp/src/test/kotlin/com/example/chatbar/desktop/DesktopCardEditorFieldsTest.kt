package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.card.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopCardEditorFieldsTest {
    private val extension = " {\n  \"unknown.vendor\": {\"raw\": [1, null, \"保留  空格\"]}\n}  "

    @Test fun `import edit save reopen and export retain metadata examples extensions and empty overrides in both modes`() = runBlocking {
        for (mode in CharacterEditMode.entries) withApp { app ->
            val imported = app.characterTransfers.importNew(CharacterCardPackage(card = PackagedCharacterCard(
                name = "Synthetic $mode", greeting = "Main", editMode = mode, freeformCharacterText = "Freeform",
                characters = listOf(PackagedCharacter("Person", profile = "Profile")),
                mesExample = "Before", alternateGreetings = listOf(" First  ", "", "Third\n"),
                tags = listOf("", " exact ", "duplicate", "duplicate"), creator = " Original ", characterVersion = "v1",
                extensions = extension, systemPrompt = "", postHistoryInstructions = "")))
            val editor = app.characterEditorController
            editor.openExisting(imported.id)
            assertEquals(imported.tags, editor.state.value.card!!.tags)
            editor.edit { it.copy(creator = " Changed author  ", characterVersion = "2.0-beta", mesExample = "New example\n  exact") }
            editor.setCardTag(1, "changed")
            editor.moveGreeting(2, -1)
            assertTrue(editor.save())
            editor.closeClean(); editor.openExisting(imported.id)
            val saved = app.characterRepository.getById(imported.id)!!
            assertEquals(" Changed author  ", saved.creator)
            assertEquals("2.0-beta", saved.characterVersion)
            assertEquals(listOf("", "changed", "duplicate", "duplicate"), saved.tags)
            assertEquals(listOf(" First  ", "Third\n", ""), saved.alternateGreetings)
            assertEquals("New example\n  exact", saved.mesExample)
            assertEquals(extension, saved.extensions)
            assertEquals("", saved.systemPrompt); assertEquals("", saved.postHistoryInstructions)
            assertEquals(imported.characters, saved.characters)
            val exported = app.characterTransfers.decode(app.characterTransfers.exportJson(saved.id)).card
            assertEquals(saved.creator, exported.creator); assertEquals(saved.characterVersion, exported.characterVersion)
            assertEquals(saved.tags, exported.tags); assertEquals(saved.alternateGreetings, exported.alternateGreetings)
            assertEquals(saved.mesExample, exported.mesExample); assertEquals(extension, exported.extensions)
            assertEquals("", exported.systemPrompt); assertEquals("", exported.postHistoryInstructions)
        }
    }

    @Test fun `untouched imported metadata raw arrays and unknown extensions survive unrelated edit and discard`() = runBlocking {
        withApp { app ->
            val original = CharacterCard.create("Synthetic", "Main").copy(tags = listOf("", " A ", "A", "A"),
                creator = " Author ", characterVersion = "", extensions = extension, alternateGreetings = listOf("", "  ", "Alt"))
            app.characterRepository.save(original)
            val editor = app.characterEditorController; editor.openExisting(original.id)
            editor.edit { it.copy(basicSetting = "Changed") }; assertTrue(editor.save())
            val saved = app.characterRepository.getById(original.id)!!
            assertEquals(original.tags, saved.tags); assertEquals(original.creator, saved.creator)
            assertEquals(original.characterVersion, saved.characterVersion); assertEquals(extension, saved.extensions)
            assertEquals(original.alternateGreetings, saved.alternateGreetings)
            editor.edit { it.copy(creator = "Discard me", characterVersion = "discard", mesExample = "Discard") }
            editor.flushDraft(); editor.discard()
            assertEquals(saved, app.characterRepository.getById(original.id))
        }
    }

    @Test fun `metadata and reordered blank greetings recover from durable draft after container reopen`() = runBlocking {
        val parent = Files.createTempDirectory("card-fields-draft-")
        var app = app(parent)
        try {
            val card = CharacterCard.create("Synthetic", "Main").copy(alternateGreetings = listOf("One", "", "Three"), extensions = extension)
            app.characterRepository.save(card); app.characterEditorController.openExisting(card.id)
            app.characterEditorController.edit { it.copy(creator = "Draft author", characterVersion = "Draft version", tags = listOf("Draft")) }
            app.characterEditorController.moveGreeting(2, -1); app.characterEditorController.flushDraft()
            app.close(); app = app(parent)
            app.characterEditorController.openExisting(card.id)
            val restored = app.characterEditorController.state.value.card!!
            assertEquals("Draft author", restored.creator); assertEquals("Draft version", restored.characterVersion)
            assertEquals(listOf("Draft"), restored.tags); assertEquals(listOf("One", "Three", ""), restored.alternateGreetings)
            assertEquals(extension, restored.extensions); assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(app.characterEditorController.save())
            assertEquals(restored.creator, app.characterRepository.getById(card.id)!!.creator)
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }

    @Test fun `explicit tag addition deduplicates new entries without normalizing imported list`() = runBlocking {
        withApp { app ->
            val card = CharacterCard.create("Synthetic", "Main").copy(tags = listOf("", " A ", "A"), alternateGreetings = listOf("", "B"))
            app.characterRepository.save(card); val editor = app.characterEditorController; editor.openExisting(card.id)
            editor.addCardTag("a"); editor.addCardTag("  ")
            assertEquals(card.tags, editor.state.value.card!!.tags)
            editor.addCardTag(" B "); assertEquals(card.tags + "B", editor.state.value.card!!.tags)
            editor.moveGreeting(0, -1); editor.moveGreeting(1, 1); editor.moveGreeting(-1, 1)
            assertEquals(card.alternateGreetings, editor.state.value.card!!.alternateGreetings)
            editor.moveGreeting(1, -1); assertEquals(listOf("B", ""), editor.state.value.card!!.alternateGreetings)
        }
    }

    private fun app(parent: Path) = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
        DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
            check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
        })

    private suspend fun withApp(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("card-fields-"); val app = app(parent)
        try { block(app) } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
