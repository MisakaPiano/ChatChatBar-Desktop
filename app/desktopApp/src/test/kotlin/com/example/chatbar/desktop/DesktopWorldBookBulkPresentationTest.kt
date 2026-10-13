@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopWorldBookBulkPresentationTest {
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `English bulk controls are translated and preserve selection behavior`() = runBlocking(awt) {
        fixture(320, 1100, DesktopUiLanguage.EN) { editor, scene ->
            scene.until { scene.nodes().any { it.label("Disabled") } }
            scene.action("Enable all")
            scene.click("Select entries")
            listOf("Select all", "Invert selection", "Clear selection", "Enable selected",
                "Disable selected", "Done selecting").forEach { scene.action(it) }
            assertFalse(scene.nodes().any { it.label("已停用") || it.label("全选") })
            val before = editor.state.value.book!!.entries
            scene.click("Select all")
            scene.click("Invert selection")
            assertEquals(before, editor.state.value.book!!.entries)
            scene.click("Select all")
            scene.click("Enable selected")
            scene.until { editor.state.value.book!!.entries.all { it.enabled } }
            scene.click("Done selecting")
            scene.until { scene.nodes().any { it.label("Select entries") } }
        }
    }

    @Test fun `narrow bulk controls select by id and retain drafts after completing selection`() = runBlocking(awt) {
        fixture(320, 1100) { editor, scene ->
            val original = editor.state.value.book!!.entries
            scene.click("开始多选")
            scene.click("全选")
            assertEquals(original, editor.state.value.book!!.entries)
            scene.click("反选")
            assertEquals(original, editor.state.value.book!!.entries)
            assertTrue(scene.action("启用所选").config.contains(SemanticsProperties.Disabled))
            scene.click("1. Alpha")
            scene.click("启用所选")
            scene.until { editor.state.value.book!!.entries[0].enabled }
            assertEquals(original.map { if (it.id == original[0].id) it.copy(enabled = true) else it }, editor.state.value.book!!.entries)
            scene.click("全选")
            scene.click("清空选择")
            assertTrue(scene.action("停用所选").config.contains(SemanticsProperties.Disabled))
            scene.click("2. Beta")
            scene.click("停用所选")
            scene.until { !editor.state.value.book!!.entries[1].enabled }
            val modified = editor.state.value.book
            scene.click("完成多选")
            assertEquals(modified, editor.state.value.book)
            assertTrue(editor.state.value.dirty)
            scene.click("一键全开")
            scene.until { editor.state.value.book!!.entries.all { it.enabled } }
            assertTrue(scene.action("一键全开").config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun `selection survives reorder by id but not deletion or a different draft`() = runBlocking(awt) {
        fixture(320, 1100) { editor, scene ->
            val original = editor.state.value.book!!.entries
            scene.click("开始多选")
            scene.click("1. Alpha")
            editor.edit { it.copy(entries = it.entries.reversed()) }
            scene.until { scene.nodes().any { it.label("2. Alpha") } }
            scene.click("启用所选")
            scene.until { editor.state.value.book!!.entries.all { it.enabled } }
            editor.deleteEntry(1)
            scene.until { scene.action("停用所选").config.contains(SemanticsProperties.Disabled) }
            scene.click("停用所选")
            assertEquals(listOf(original[1]), editor.state.value.book!!.entries)
            editor.discard()
            editor.openNew()
            scene.until { scene.nodes().any { it.label("开始多选") } }
            assertTrue(scene.action("开始多选").config.contains(SemanticsProperties.Disabled))
            assertFalse(scene.nodes().any { it.label("完成多选") })
        }
    }

    @Test fun `empty narrow list and modal protection retain add edit and delete actions`() = runBlocking(awt) {
        fixture(260, 1100) { editor, scene ->
            scene.until { scene.nodes().any { it.label("开始多选") } }
            val buttons = scene.nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(buttons.isNotEmpty())
            buttons.forEach { assertTrue(it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 260, "${it.boundsInRoot}") }
            editor.openEntry(0)
            val before = editor.state.value
            scene.until { scene.action("一键全开").config.contains(SemanticsProperties.Disabled) }
            scene.click("一键全开")
            assertEquals(before, editor.state.value)
            editor.dismissEntry()
            editor.edit { it.copy(entries = emptyList()) }
            scene.until { scene.action("开始多选").config.contains(SemanticsProperties.Disabled) }
            val empty = editor.state.value
            scene.click("一键全开")
            assertEquals(empty, editor.state.value)
        }
    }

    private suspend fun fixture(width: Int, height: Int, language: DesktopUiLanguage = DesktopUiLanguage.ZH_CN,
        block: suspend (DesktopWorldBookEditorController, ImageComposeScene) -> Unit) {
        val parent = Files.createTempDirectory("desktop-world-bulk-")
        viewportWidth = width
        viewportHeight = height
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        val editor = app.worldBookEditorController
        var scene: ImageComposeScene? = null
        try {
            val book = WorldBook.create("Fixture").copy(entries = listOf(
                WorldBookEntry.create(listOf("a"), "A").copy(name = "Alpha", enabled = false),
                WorldBookEntry.create(listOf("b"), "B").copy(name = "Beta")))
            app.worldBookRepository.save(book)
            editor.openExisting(book.id)
            scene = ImageComposeScene(width, height) {
                val state by editor.state.collectAsState()
                CompositionLocalProvider(LocalDesktopUiStrings provides DesktopUiStrings(language)) {
                    Column { DesktopWorldBookEntries(editor, state, {}, editor::deleteEntry) }
                }
            }
            block(editor, scene)
        } finally { scene?.close(); app.close(); parent.toFile().deleteRecursively() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(text: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    private fun ImageComposeScene.action(label: String): SemanticsNode {
        var node = nodes().single { it.label(label) }
        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = checkNotNull(node.parent)
        return node
    }
    private suspend fun ImageComposeScene.click(label: String) {
        until { nodes().any { it.label(label) } }
        val bounds = action(label).boundsInRoot
        assertTrue(bounds.left >= 0 && bounds.right <= viewportWidth && bounds.top >= 0 && bounds.bottom <= viewportHeight, "$label: $bounds")
        sendPointerEvent(PointerEventType.Press, bounds.center, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, bounds.center, button = PointerButton.Primary)
        render(System.nanoTime()).close()
        yield()
        render(System.nanoTime()).close()
    }
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) = withTimeout(5000) {
        do { render(System.nanoTime()).close(); yield() } while (!predicate())
    }
}
