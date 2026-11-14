@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopReplyDeletionTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `whole alternative deletion route remains full width and opens only through click`() = runBlocking(edt) {
        for (width in listOf(240, 680)) {
            var opened = 0
            val scene = ImageComposeScene(width, 150, coroutineContext = edt) {
                Box(Modifier.fillMaxSize()) { DesktopDeleteAllAlternativesAction { opened++ } }
            }
            try {
                repeat(3) { scene.render().close(); delay(20) }
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                val nodes = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
                val action = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "delete-all-alternatives" }
                assertEquals(0, opened)
                assertTrue(action.boundsInRoot.width >= width - 1)
                assertTrue(action.boundsInRoot.height >= 44)
                assertEquals(Role.Button, action.config[SemanticsProperties.Role])
                assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(1, opened)
            } finally { scene.close() }
        }
    }
}
