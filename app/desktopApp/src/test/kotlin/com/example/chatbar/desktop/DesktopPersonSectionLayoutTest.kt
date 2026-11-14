package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopPersonSectionLayoutTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `master stays in viewport through lower details then leaves before advanced settings`() = runBlocking(edt) {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var master = Rect.Zero; var advanced = Rect.Zero; var viewport = Rect.Zero
        val scene = ImageComposeScene(1100, 700, coroutineContext = edt) {
            scroll = rememberScrollState()
            var top by remember { mutableStateOf(0f) }
            var height by remember { mutableStateOf(0) }
            Column(Modifier.fillMaxSize().onGloballyPositioned {
                viewport = it.boundsInRoot(); top = viewport.top; height = it.size.height
            }.verticalScroll(scroll)) {
                Spacer(Modifier.height(200.dp))
                DesktopPersonSectionLayout(top, height,
                    master = { Box(Modifier.fillMaxSize().onGloballyPositioned { master = it.boundsInRoot() }) },
                    detail = { Box(Modifier.height(2200.dp).fillMaxWidth()) })
                Box(Modifier.height(600.dp).fillMaxWidth().onGloballyPositioned { advanced = it.boundsInRoot() })
            }
        }
        suspend fun settle() { repeat(8) { scene.render().close(); delay(20) } }
        try {
            settle(); assertTrue(master.height > 600f)
            scroll.scrollTo(1000); settle()
            assertTrue(master.top >= viewport.top - 1f && master.top <= viewport.top + 1f)
            assertTrue(master.bottom <= viewport.bottom + 1f)
            scroll.scrollTo(scroll.maxValue); settle()
            assertTrue(master.bottom <= advanced.top + 1f)
            assertTrue(advanced.bottom <= viewport.bottom + 1f)
        } finally { scene.close() }
    }
}
