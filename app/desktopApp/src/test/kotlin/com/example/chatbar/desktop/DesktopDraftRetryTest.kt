@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.*

class DesktopDraftRetryTest {
    @Test fun `draft recovery action appears only when available and click invokes explicit retry`() = runBlocking {
        var available by mutableStateOf(false)
        var calls = 0; var bounds = Rect.Zero
        val scene = ImageComposeScene(400, 140) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
                    DesktopDraftRetry(available) { calls++ }
                }
            }
        }
        try {
            repeat(3) { scene.render().close(); yield() }
            assertEquals(0f, bounds.height)
            available = true
            repeat(3) { scene.render().close(); yield() }
            assertTrue(bounds.width > 0 && bounds.height > 0)
            scene.sendPointerEvent(PointerEventType.Press, bounds.center, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, bounds.center, button = PointerButton.Primary)
            repeat(3) { scene.render().close(); yield() }
            assertEquals(1, calls)
            available = false
            repeat(3) { scene.render().close(); yield() }
            assertEquals(0f, bounds.height)
        } finally { scene.close() }
    }
}
