package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopResponsiveChatWiringTest {
    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
    private fun composer() = source("DesktopPrimaryChatPanel.kt").substringAfter("private fun PrimaryComposer(")
        .substringBefore("private fun PrimaryUtilities(")

    @Test fun `workspace uses actual remaining width and a separate rail allowance`() {
        val panel = source("DesktopPrimaryChatPanel.kt")
        assertTrue(panel.contains("DesktopChatNavigationPlacementPolicy.resolve(maxWidth.value)"))
        assertTrue(panel.contains("Modifier.width(placement.workspaceWidthDp.dp)"))
        assertTrue(panel.contains("Modifier.width(placement.contentWidthDp.dp)"))
    }

    @Test fun `height preference is shell owned and drag handle never belongs to text field`() {
        val shell = source("DesktopPrimaryShell.kt")
        assertTrue(shell.contains("remember { DesktopComposerLayoutState() }"))
        val body = composer()
        assertTrue(body.contains("DesktopComposerResizeHandle {"))
        assertTrue(body.contains("layout.heightDp"))
        assertFalse(source("DesktopFullComposer.kt").contains("pointerInput"))
    }

    @Test fun `collapsed uses inline action while normal footer aligns hint and action`() {
        val body = composer()
        assertTrue(body.contains("if (collapsed) PrimaryComposerAction("))
        assertTrue(body.contains("if (!collapsed) Row("))
        assertTrue(body.contains("DesktopUiText.COMPOSER_HINT"))
        assertTrue(body.contains("Modifier.weight(1f)"))
        assertFalse(body.contains("ActionRow {"))
    }

    @Test fun `composer preserves multiline value and composition guarded Ctrl Enter`() {
        val body = source("DesktopFullComposer.kt")
        assertTrue(body.contains("value = composer.input"))
        assertTrue(body.contains("composer.edit(full, it, onDraft)"))
        assertTrue(composer().contains("controller::editComposer"))
        assertTrue(body.contains("desktopComposerSendKey(event.key, event.type, event.isCtrlPressed,"))
        assertTrue(body.contains("composer.input, canLaunch, onSend)"))
        assertTrue(body.contains("event.isCtrlPressed"))
        assertFalse(body.contains("singleLine = true"))
    }

    @Test fun `normal and collapsed share the same guarded Send Stop action`() {
        val body = composer()
        assertTrue(body.contains("iconOnly = true"))
        assertTrue(body.contains("iconOnly = false"))
        assertTrue(body.contains("if (running != null) controller.stop(running.taskId)"))
        assertTrue(body.contains("else onSend()"))
        assertTrue(source("DesktopPrimaryChatPanel.kt").contains("composer.send(canLaunch, controller::send)"))
        assertTrue(body.contains("if (iconOnly) DesktopChatIconAction("))
        assertTrue(body.contains("else BootstrapButton(label"))
        assertTrue(body.contains("enabled = !send || canSend"))
        assertTrue(body.contains("DesktopAppIcons.Send else DesktopAppIcons.Stop"))
        assertEquals(2, Regex("onClick = performAction").findAll(body).count())
        assertFalse(body.substringAfter("private fun PrimaryComposerAction(").contains("rememberCoroutineScope"),
            "Changing button presentation must not cancel a composer-owned Send")
    }

    @Test fun `shell lifetime preference has no persistence or session key`() {
        val shell = source("DesktopPrimaryShell.kt")
        assertTrue(shell.indexOf("remember { DesktopComposerLayoutState() }") < shell.indexOf("when (route)"))
        assertTrue(shell.contains("DesktopPrimaryChatPanel(primaryChatController, size, composerLayout)"))
        val layout = source("DesktopResponsiveChatLayout.kt")
        for (forbidden in listOf("AppSettings", "ChatSession", "Repository", "java.nio.file", "java.io", "rememberSaveable")) {
            assertFalse(layout.contains(forbidden))
        }
        assertFalse(source("DesktopPrimaryChatPanel.kt").contains("remember(state.selectedSession?.id) { DesktopComposerLayoutState"))
    }

    @Test fun `only the handle consumes vertical drags with density conversion and stable callback`() {
        val handle = source("DesktopChatControls.kt").substringAfter("fun DesktopComposerResizeHandle(")
            .substringBefore("fun DesktopChatDisclosure(")
        assertTrue(handle.contains("rememberUpdatedState(onDrag)"))
        assertTrue(handle.contains("detectVerticalDragGestures"))
        assertTrue(handle.contains("change.consume()"))
        assertTrue(handle.contains("amount / density.density"))
        assertTrue(handle.contains("pointerHoverIcon"))
        val panel = source("DesktopPrimaryChatPanel.kt")
        assertFalse(panel.contains("VerticalScrollbar"))
        assertTrue(panel.contains("LaunchedEffect(workspaceHeightDp) { composerLayout.clamp(workspaceHeightDp) }"))
    }
}
