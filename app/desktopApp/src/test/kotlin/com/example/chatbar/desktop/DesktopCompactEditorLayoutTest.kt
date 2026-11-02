@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopCompactEditorLayoutTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Fixture {
        val parent = Files.createTempDirectory("compact-editors-")
        var picks = 0
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() },
            filePicker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? { picks++; return null }
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? { picks++; return null }
            })
        suspend fun close() { app.close(); check(parent.toFile().deleteRecursively()) }
    }
    private val sizes = listOf(330 to 420, 420 to 900, 1100 to 760)
    private val editorSizes = sizes.map { Triple(it.first, it.second, DesktopUiLanguage.ZH_CN) } +
        Triple(330, 420, DesktopUiLanguage.EN)

    @Test fun `long format editor keeps real leave and save actions visible without scrolling away from its body`() = runBlocking(edt) {
        val f = Fixture()
        val source = FormatCard.create("Synthetic format", "Original").copy(userTools = List(12) { FormatCardUserToolConfig.randomNumber() })
        try {
            f.app.formatCardRepository.save(source)
            val controller = f.app.formatCardEditorController
            for ((width, height, language) in editorSizes) {
                val strings = DesktopUiStrings(language)
                controller.openExisting(source.id)
                val content = "Synthetic editable format $width\n".repeat(120)
                controller.edit { it.copy(content = content) }
                val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                    CompositionLocalProvider(LocalDesktopUiStrings provides strings) {
                        Box(Modifier.fillMaxSize()) { DesktopFormatCardEditorOverlay(controller); DesktopFormatCardLeavePrompt(controller) }
                    }
                }
                try {
                    frames(scene); click(scene, strings(DesktopUiText.CANCEL), width, height)
                    until(scene) { controller.state.value.leavePrompt }
                    for (label in listOf(DesktopUiText.SAVE_AND_LEAVE, DesktopUiText.FORMAT_KEEP_DRAFT_AND_LEAVE,
                        DesktopUiText.DISCARD_CHANGES, DesktopUiText.CONTINUE_EDITING)) visible(action(scene, strings(label)), width, height)
                    click(scene, strings(DesktopUiText.CONTINUE_EDITING), width, height); frames(scene)
                    assertEquals(content, controller.state.value.card!!.content)
                    shot(scene, "format-editor-$language-$width-$height")
                    click(scene, strings(DesktopUiText.SAVE), width, height)
                    until(scene) { controller.state.value.card == null }
                    val saved = assertNotNull(f.app.formatCardRepository.getById(source.id))
                    assertEquals(content, saved.content); assertEquals(source.userTools, saved.userTools)
                } finally { scene.close() }
            }
            assertEquals(0, f.picks)
        } finally { f.close() }
    }

    @Test fun `long world book and entry editors keep original explicit entry and entity commits separate and reachable`() = runBlocking(edt) {
        val f = Fixture()
        val source = WorldBook("synthetic-book", "Synthetic book", entries = List(20) {
            WorldBookEntry("entry-$it", "Entry $it", listOf("key-$it"), "Original $it")
        })
        try {
            f.app.worldBookRepository.save(source)
            val controller = f.app.worldBookEditorController
            for ((width, height, language) in editorSizes) {
                val strings = DesktopUiStrings(language)
                controller.openExisting(source.id)
                val previous = assertNotNull(f.app.worldBookRepository.getById(source.id))
                val content = "Synthetic entry $width\n".repeat(120)
                controller.edit { it.copy(name = "Synthetic book $width") }
                val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                    CompositionLocalProvider(LocalDesktopUiStrings provides strings) {
                        Box(Modifier.fillMaxSize()) { DesktopWorldBookEditorOverlay(controller); DesktopWorldBookLeavePrompt(controller) }
                    }
                }
                try {
                    frames(scene); visible(action(scene, strings(DesktopUiText.SAVE)), width, height)
                    val help = action(scene, strings(DesktopUiText.WORLD_HELP))
                    val close = action(scene, strings(DesktopUiText.CLOSE))
                    val title = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == strings(DesktopUiText.WORLD_EDIT) } == true }
                    assertTrue(help.boundsInRoot.left - title.boundsInRoot.right >= 7.5f)
                    assertTrue(close.boundsInRoot.left - help.boundsInRoot.right >= 7.5f)
                    visible(help, width, height); visible(close, width, height)
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Synthetic book $width" })
                    controller.openEntry(0); controller.updateEntryModal { it.copy(content = content) }; frames(scene)
                    visible(action(scene, strings(DesktopUiText.WORLD_DISMISS_ENTRY)), width, height)
                    shot(scene, "world-entry-$language-$width-$height")
                    click(scene, strings(DesktopUiText.WORLD_SAVE_ENTRY), width, height)
                    until(scene) { controller.state.value.modal == null }
                    assertEquals(content, controller.state.value.book!!.entries.first().content)
                    assertEquals(previous, f.app.worldBookRepository.getById(source.id))
                    click(scene, strings(DesktopUiText.CANCEL), width, height); until(scene) { controller.state.value.leavePrompt }
                    click(scene, strings(DesktopUiText.CONTINUE_EDITING), width, height); frames(scene)
                    shot(scene, "world-editor-$language-$width-$height")
                    click(scene, strings(DesktopUiText.SAVE), width, height); until(scene) { controller.state.value.book == null }
                    val saved = assertNotNull(f.app.worldBookRepository.getById(source.id))
                    assertEquals("Synthetic book $width", saved.name)
                    assertEquals(content, saved.entries.first().content)
                    assertEquals(previous.entries.drop(1), saved.entries.drop(1))
                } finally { scene.close() }
            }
            assertEquals(0, f.picks)
        } finally { f.close() }
    }

    @Test fun `real five page data workspace reveals externally selected update and subsequent destinations at compact widths`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.app.automaticBackupRuntime.initialize()
            var updateRequest by mutableStateOf(0)
            var consumed = 0
            val root = DesktopDataRootSwitchController(DesktopDataRootResolution.Resolved(f.app.appDataRoot,
                DesktopDataRootProvenance.CLI_OVERRIDE, f.parent.resolve("bootstrap.json")),
                DesktopDirectoryPicker { error("No directory change expected") }, { false }, { error("No migration expected") })
            for ((width, height) in sizes) {
                updateRequest = 0
                val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                    val scope = rememberCoroutineScope()
                    val automatic = remember { DesktopAutomaticBackupSettingsController(f.app.automaticBackupRuntime, scope) }
                    DesktopDataWorkspace(root, f.app.fullBackupController, f.app.mergeImportController, automatic,
                        { error("No exit expected") }, openUpdateRequest = updateRequest, onUpdateRequestConsumed = { consumed++ })
                }
                try {
                    frames(scene); val before = consumed; updateRequest = 1
                    try { until(scene) { consumed == before + 1 && selectedVisible(scene, "版本与更新", width, height) } }
                    catch (timeout: TimeoutCancellationException) {
                        shot(scene, "data-update-selected-$width-$height-failure")
                        val tabs = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }.map {
                            "${it.config.getOrNull(SemanticsProperties.ContentDescription)}:selected=${it.config.getOrNull(SemanticsProperties.Selected)}:bounds=${it.boundsInRoot}"
                        }
                        throw AssertionError("Data selected tab timeout: consumed=$consumed expected=${before + 1} viewport=$width/$height tabs=$tabs", timeout)
                    }
                    shot(scene, "data-update-selected-$width-$height")
                    if (width == 330) {
                        for ((resizedWidth, resizedHeight) in listOf(1100 to 760, 330 to 420, 700 to 520, 699 to 520)) {
                            scene.constraints = Constraints.fixed(resizedWidth, resizedHeight)
                            frames(scene)
                            until(scene) { selectedVisible(scene, "版本与更新", resizedWidth, resizedHeight) }
                            assertEquals(before + 1, consumed)
                        }
                        scene.constraints = Constraints.fixed(width, height); frames(scene)
                        until(scene) { selectedVisible(scene, "版本与更新", width, height) }
                    }
                    // A semantics/keyboard selection can target a currently scrolled-out tab.
                    action(scene, "备份与导入").config[SemanticsActions.OnClick].action!!.invoke()
                    until(scene) { selectedVisible(scene, "备份与导入", width, height) }
                    action(scene, DesktopUiText.RECOVERY_SUPPORT.zhCn).config[SemanticsActions.OnClick].action!!.invoke()
                    until(scene) { selectedVisible(scene, DesktopUiText.RECOVERY_SUPPORT.zhCn, width, height) }
                    assertTrue(f.app.taskRuntime.tasks.value.isEmpty()); assertEquals(0, f.picks)
                } finally { scene.close() }
            }
        } finally { f.close() }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun matches(node: SemanticsNode, text: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || node.children.any { matches(it, text) }
    private fun action(scene: ImageComposeScene, text: String) = nodes(scene).first {
        matches(it, text) && it.config.contains(SemanticsActions.OnClick)
    }
    private fun inBounds(node: SemanticsNode, width: Int, height: Int): Boolean = node.boundsInRoot.let {
        it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height && it.width >= 32 && it.height >= 32
    }
    private fun visible(node: SemanticsNode, width: Int, height: Int) = assertTrue(
        inBounds(node, width, height) && node.boundsInRoot.height >= 48, "${node.boundsInRoot} / $width x $height")
    private fun click(scene: ImageComposeScene, text: String, width: Int, height: Int) {
        val node = action(scene, text); visible(node, width, height)
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun selectedVisible(scene: ImageComposeScene, text: String, width: Int, height: Int) = nodes(scene).any {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && matches(it, text) &&
            it.config.getOrNull(SemanticsProperties.Selected) == true && inBounds(it, width, height) && it.boundsInRoot.width >= 130
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render().close(); delay(25) } }
    private suspend fun until(scene: ImageComposeScene, predicate: () -> Boolean) = withTimeout(5000) {
        while (!predicate()) frames(scene)
        frames(scene)
    }
    private fun shot(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_MEDIA_USAGE_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("$name.png"); Files.createDirectories(path.parent)
            scene.render().use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) } }
        }
    }
}
