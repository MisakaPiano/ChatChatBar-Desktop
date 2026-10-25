@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopSupportSummaryTest {
    private val commit = "0123456789abcdef0123456789abcdef01234567"
    private val properties = mapOf("os.name" to "Windows 11", "os.arch" to "amd64", "java.specification.version" to "17")

    @Test fun `data page copies only explicit support text without a network check or system clipboard`() = panel { fail ->
        var copied: String? = null
        setContent { DesktopUpdatePanel { text -> if (fail) error("Synthetic-private-clipboard-detail") else copied = text } }
        repeat(6) { render(System.nanoTime()).close(); yield() }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun all() = semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
        val button = all().first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "复制基本诊断" } == true &&
            node.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(6) { render(System.nanoTime()).close(); yield() }
        val visible = all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
        if (fail) {
            assertNull(copied)
            assertTrue(visible.contains("剪贴板暂时不可用"))
            assertFalse(visible.contains("Synthetic-private-clipboard-detail"))
        } else {
            assertTrue(assertNotNull(copied).endsWith("updateCheck=NOT_CHECKED"))
            assertTrue(visible.contains("基本诊断已复制"))
        }
    }

    private fun panel(block: suspend ImageComposeScene.(Boolean) -> Unit) = runBlocking(object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }) {
        for (fail in listOf(false, true)) ImageComposeScene(1000, 1000).use { scene -> scene.block(fail) }
    }

    @Test fun `support output includes exact build and validated baseline without reading other properties`() {
        val read = mutableListOf<String>()
        val summary = DesktopSupportSummary.create(DesktopBuildIdentity("0.1.0-preview.1007", commit),
            DesktopSupportSummary.UpdateState.NOT_CHECKED) { read += it; properties[it] }
        assertEquals(setOf("os.name", "os.arch", "java.specification.version"), read.toSet())
        assertTrue(summary.contains("sourceCommit=$commit"))
        assertTrue(summary.contains("channel=PREVIEW"))
        assertTrue(summary.contains("validatedUpstream=1.4.1"))
        assertTrue(summary.contains("platform=WINDOWS\narchitecture=X64\njavaFeature=17"))
        assertEquals(10, summary.lines().size)
    }

    @Test fun `all fixed update states are reported independently of private request evidence`() {
        for (state in DesktopSupportSummary.UpdateState.entries) {
            val summary = DesktopSupportSummary.create(DesktopBuildIdentity("0.1.0", commit), state, properties::get)
            assertTrue(summary.endsWith("updateCheck=${state.name}"))
            assertTrue(summary.contains("channel=STABLE"))
        }
    }

    @Test fun `untrusted build and system strings cannot inject paths tokens or extra lines`() {
        val private = "Synthetic-private-value\nC:/Synthetic-private-path"
        val summary = DesktopSupportSummary.create(DesktopBuildIdentity(private, private),
            DesktopSupportSummary.UpdateState.FAILED) { private }
        assertFalse(summary.contains("Synthetic-private"))
        assertTrue(summary.contains("desktopVersion=UNAVAILABLE"))
        assertTrue(summary.contains("sourceCommit=UNAVAILABLE"))
        assertTrue(summary.contains("channel=UNKNOWN"))
        assertTrue(summary.contains("platform=UNKNOWN\narchitecture=UNKNOWN\njavaFeature=UNKNOWN"))
        assertEquals(10, summary.lines().size)
    }

    @Test fun `missing platform information stays unknown rather than assuming Windows`() {
        val summary = DesktopSupportSummary.create(DesktopBuildIdentity("development", "development"),
            DesktopSupportSummary.UpdateState.NOT_CHECKED) { null }
        assertTrue(summary.contains("platform=UNKNOWN"))
        assertTrue(summary.contains("javaFeature=UNKNOWN"))
    }
}
