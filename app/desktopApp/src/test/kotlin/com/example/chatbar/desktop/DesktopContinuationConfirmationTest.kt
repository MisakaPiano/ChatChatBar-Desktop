@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopContinuationConfirmationTest {
    @Test fun `blank button requests confirmation while cancel preserves draft and full editor`() = runBlocking {
        val composer = DesktopComposerInput("session", " \n")
        composer.open(true)
        assertTrue(composer.canClickSend(true))
        assertFalse(composer.canSend(true))
        composer.requestContinuation(true)
        assertTrue(composer.continuationPending)
        composer.cancelContinuation()
        composer.confirmContinuation(true) { fail("Cancelled continuation") }
        assertEquals(" \n", composer.input.text)
        assertTrue(composer.expanded)
    }

    @Test fun `confirmation dispatches once and blocks a second click until admission returns`() = runBlocking {
        val composer = DesktopComposerInput("session", "")
        composer.open(true); composer.requestContinuation(true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<String?>()
        var requests = 0
        val first = launch { composer.confirmContinuation(true) { requests++; entered.complete(Unit); release.await() } }
        entered.await()
        assertFalse(composer.canClickSend(true))
        composer.requestContinuation(true)
        composer.confirmContinuation(true) { requests++; "duplicate" }
        assertEquals(1, requests)
        release.complete("task"); first.join()
        assertFalse(composer.expanded)
        assertEquals("", composer.input.text)
    }

    @Test fun `changed draft attachment or admission cannot turn an old confirmation into a request`() = runBlocking {
        for (change in 0..2) {
            val composer = DesktopComposerInput("session", "")
            composer.requestContinuation(true)
            when (change) {
                0 -> composer.edit(false, TextFieldValue("new draft")) { }
                1 -> composer.hasAttachments = true
            }
            composer.confirmContinuation(change != 2) { fail("Stale confirmation") }
            assertFalse(composer.continuationPending)
            if (change == 0) assertEquals("new draft", composer.input.text)
        }
    }

    @Test fun `no session and active IME cannot request continuation`() {
        val absent = DesktopComposerInput(null, "")
        absent.requestContinuation(true); assertFalse(absent.continuationPending)
        val composer = DesktopComposerInput("session", "")
        composer.edit(false, TextFieldValue(" ", TextRange(1), TextRange(0, 1))) { }
        composer.requestContinuation(true)
        assertFalse(composer.continuationPending)
    }

    @Test fun `rendered empty full editor exposes confirmation and cancellation before any continuation`() = runBlocking {
        val composer = DesktopComposerInput("session", "")
        composer.open(true)
        val scope = this
        var requests = 0
        val scene = ImageComposeScene(700, 800) {
            Box(Modifier.fillMaxSize()) {
                if (composer.expanded) DesktopFullComposer(composer, true, null, null, {},
                    onSend = { composer.requestContinuation(true) })
                if (composer.continuationPending) DesktopContinuationConfirmation(true, composer::cancelContinuation) {
                    scope.launch { composer.confirmContinuation(true) { requests++; "task" } }
                }
            }
        }
        suspend fun frames() { repeat(4) { scene.render().close(); yield() } }
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun click(label: String) {
            var node = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
            while (!node.config.contains(SemanticsActions.OnClick)) node = requireNotNull(node.parent)
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            frames(); click("发送"); frames()
            assertTrue(composer.continuationPending); assertEquals(0, requests)
            click("取消"); frames()
            assertFalse(composer.continuationPending); assertTrue(composer.expanded); assertEquals(0, requests)
            click("发送"); frames(); click("继续生成"); frames()
            assertEquals(1, requests); assertFalse(composer.expanded)
        } finally { scene.close() }
    }
}
