package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopResponsiveChatWiringTest {
    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
    private fun composer() = source("DesktopPrimaryChatPanel.kt").substringAfter("private fun PrimaryComposer(")
        .substringBefore("internal fun DesktopSessionSettingsBody(")

    @Test fun `workspace uses actual remaining width and a separate rail allowance`() {
        val panel = source("DesktopPrimaryChatPanel.kt")
        assertTrue(panel.contains("desktopChatWorkspaceLayout(maxWidth.value, state.people.isNotEmpty())"))
        assertTrue(panel.contains("Modifier.width(workspace.widthDp.dp)"))
        assertTrue(panel.contains("Modifier.width(workspace.headerWidthDp.dp)"))
        assertFalse(panel.contains("placement.workspaceWidthDp - railWidth"))
    }

    @Test fun `height preference is shell owned and drag handle never belongs to text field`() {
        val shell = source("DesktopPrimaryShell.kt")
        assertTrue(shell.contains("remember { DesktopComposerLayoutState() }"))
        val body = composer()
        assertTrue(body.contains("DesktopComposerResizeHandle {"))
        assertTrue(body.contains("layout.heightDp"))
        assertFalse(source("DesktopFullComposer.kt").contains("pointerInput"))
    }

    @Test fun `all composer heights keep attachment fullscreen and send in one action rail`() {
        val body = composer()
        assertFalse(body.contains("if (collapsed) PrimaryComposerAction("))
        assertTrue(body.contains("DesktopChatAttachmentAction(running == null)"))
        assertTrue(body.contains("DesktopAppIcons.ExpandComposer"))
        assertFalse(body.contains("if (!collapsed) StatusText("))
        assertFalse(body.contains("DesktopChatImageToolbar("))
        assertFalse(body.contains("chooseSessionBackground"))
        assertTrue(source("DesktopFullComposer.kt").contains("desktopComposerPlaceholder(sendKey)"))
        assertTrue(body.contains("Modifier.weight(1f)"))
        assertFalse(body.contains("ActionRow {"))
    }

    @Test fun `composer preserves multiline value and composition guarded Ctrl Enter`() {
        val body = source("DesktopFullComposer.kt")
        assertTrue(body.contains("value = composer.input"))
        assertTrue(body.contains("composer.edit(full, it, onDraft)"))
        assertTrue(composer().contains("controller::editComposer"))
        assertTrue(body.contains("desktopComposerSendKey(event.key, event.type, event.isCtrlPressed,"))
        assertTrue(body.contains("mode = sendKey, shift = event.isShiftPressed"))
        assertTrue(body.contains("event.isCtrlPressed"))
        assertFalse(body.contains("singleLine = true"))
    }

    @Test fun `normal and collapsed share the same guarded Send Stop action`() {
        val body = composer()
        assertTrue(body.contains("val compact = maxWidth < 480.dp || height < 88f"))
        assertTrue(body.contains("if (running != null) controller.stop(running.taskId)"))
        assertTrue(body.contains("else onSend()"))
        assertTrue(source("DesktopPrimaryChatPanel.kt").contains("composer.send(canLaunch, controller::send)"))
        val action = source("DesktopComposerSendAction.kt")
        assertTrue(action.contains("if (compact || running != null) DesktopChatIconAction("))
        assertTrue(action.contains("else BootstrapButton(t(DesktopUiText.SEND)"))
        assertTrue(action.contains("enabled = running != null || canSend"))
        assertTrue(action.contains("DesktopAppIcons.Send else DesktopAppIcons.Stop"))
        assertEquals(1, Regex("onSendKey, performAction").findAll(body).count())
        assertFalse(action.contains("rememberCoroutineScope"),
            "Changing button presentation must not cancel a composer-owned Send")
    }

    @Test fun `shell lifetime preference has no persistence or session key`() {
        val shell = source("DesktopPrimaryShell.kt")
        assertTrue(shell.indexOf("remember { DesktopComposerLayoutState() }") < shell.indexOf("when (route)"))
        assertTrue(shell.contains("DesktopPrimaryChatPanel(primaryChatController, size, composerLayout,"))
        assertTrue(shell.contains("compactNavigation = compactChatNavigation"))
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
