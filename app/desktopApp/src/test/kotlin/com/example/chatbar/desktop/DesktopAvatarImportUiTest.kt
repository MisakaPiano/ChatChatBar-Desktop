@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatSession
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopAvatarImportUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual global avatar entry opens crop preview and cancel preserves settings while apply saves a distinct copy`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("player-avatar-ui-")
        val path = parent.resolve("source.png")
        val original = DesktopImageEditing.png(BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB))
        Files.write(path, original)
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType) = path
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No export")
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker,
            bundledAssets = { name -> check(name == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try {
            val controller = app.modelSettingsController
            controller.loadSettings()
            val before = app.settingsRepository.getPlayerSetting()
            val scene = ImageComposeScene(760, 860, coroutineContext = edt) {
                val state by controller.state.collectAsState()
                CompositionLocalProvider(LocalDesktopEditorAuxiliaryFrame provides { content -> content() }) {
                    DesktopPlayerAvatarSetting(controller, state)
                }
            }
            try {
                scene.until { has("选择玩家头像") }; scene.click("选择玩家头像")
                scene.until { has("应用") && enabled("应用") }
                assertEquals(before, app.settingsRepository.getPlayerSetting())
                scene.click("取消"); scene.until { !has("应用") }
                assertEquals(before, app.settingsRepository.getPlayerSetting())
                scene.click("选择玩家头像"); scene.until { has("应用") && enabled("应用") }
                val slider = scene.nodes().first { it.config.contains(SemanticsActions.SetProgress) }
                assertTrue(slider.config[SemanticsActions.SetProgress].action!!.invoke(.5f))
                scene.click("应用")
                scene.until { !has("应用") && controller.state.value.playerAvatar != null }
                val saved = app.settingsRepository.getPlayerSetting()
                assertEquals(before.playerName, saved.playerName)
                assertEquals(before.globalPersona, saved.globalPersona)
                val edited = app.characterResourceStore.readBytes(assertNotNull(saved.desktopAvatar))
                assertEquals(1024, DesktopImageEditing.decode(edited).width)
                assertEquals(1024, DesktopImageEditing.decode(edited).height)
                assertContentEquals(original, Files.readAllBytes(path))

                val card = CharacterCard("card", "Synthetic card", greeting = "Hello",
                    characters = listOf(CharacterInfo("person", "Synthetic person")), createdAt = 1, updatedAt = 1)
                app.characterRepository.save(card)
                val editor = app.characterEditorController
                editor.openExisting(card.id)
                val editorBefore = editor.state.value.card
                val candidate = assertNotNull(editor.selectImageForEditing("Synthetic image"))
                assertContentEquals(original, candidate)
                assertEquals(editorBefore, editor.state.value.card)
                assertFalse(editor.state.value.dirty)
                editor.cropAvatar(edited)
                editor.cropAppearance("person", edited)
                assertContentEquals(edited, editor.imageBytes(editor.state.value.card!!.avatar))
                assertContentEquals(edited, editor.imageBytes(editor.state.value.card!!.characters.single().appearanceImage))
                assertEquals(card, app.characterRepository.getById(card.id))
                editor.discard()
                assertEquals(card, app.characterRepository.getById(card.id))

                val first = app.chatRepository.createSession(ChatSession.create(card.id, "First"))
                val second = app.chatRepository.createSession(ChatSession.create(card.id, "Second"))
                val chat = app.primaryChatController
                chat.selectSession(first.id)
                val sessionCandidate = assertNotNull(chat.prepareSessionPlayerAvatar())
                assertEquals(first.id, sessionCandidate.first)
                assertNull(app.chatRepository.getSession(first.id)!!.desktopPlayerAvatar)
                chat.selectSession(second.id)
                chat.chooseSessionPlayerAvatar(editedBytes = edited, expectedSessionId = first.id)
                assertNotNull(chat.state.value.error)
                assertNull(app.chatRepository.getSession(first.id)!!.desktopPlayerAvatar)
                assertNull(app.chatRepository.getSession(second.id)!!.desktopPlayerAvatar)
                chat.selectSession(first.id)
                chat.chooseSessionPlayerAvatar(editedBytes = edited, expectedSessionId = first.id)
                assertNull(chat.state.value.error)
                assertContentEquals(edited, app.characterResourceStore.readBytes(assertNotNull(app.chatRepository.getSession(first.id)!!.desktopPlayerAvatar)))
                assertNull(app.chatRepository.getSession(second.id)!!.desktopPlayerAvatar)
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val target = Path.of(directory).resolve("avatar-import-applied.png")
                    Files.createDirectories(target.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } }
                }
            } finally { scene.close() }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    private fun ImageComposeScene.has(label: String) = nodes().any { it.matches(label) }
    private fun ImageComposeScene.enabled(label: String) = nodes().any { it.matches(label) && it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(15_000) {
        while (!condition()) { render(System.nanoTime()).close(); delay(20) }
        repeat(3) { render(System.nanoTime()).close(); delay(20) }
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(3) { render(System.nanoTime()).close(); delay(20) }
    }
}
