@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import java.nio.file.*
import java.security.MessageDigest
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopWorkspaceSmallFixTest {
    @Test fun `Windows diagnostic brand loads icon and releases thread hook without showing a dialog`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        var failures = 0
        assertEquals(1, DesktopNativeDialogBrand.show({ 1 }, { failures++ }))
        assertEquals(0, failures)
    }
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `workspace navigation equal widths retains every destination in both sizes`() = runBlocking(edt) {
        for (width in listOf(600, 1100)) {
            val destinations = listOf(DesktopWorkspaceDestination("a", "角色"), DesktopWorkspaceDestination("b", "备份与迁移数据"))
            var selected: String? = null
            val scene = ImageComposeScene(width, 600) { DesktopWorkspacePage("管理", destinations, "a", onSelect = { selected = it }) {} }
            try {
                repeat(5) { scene.render().close(); yield() }
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                val tabs = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                assertEquals(2, tabs.size); assertEquals(tabs[0].boundsInRoot.width, tabs[1].boundsInRoot.width)
                assertTrue(tabs[0].config[SemanticsProperties.Selected])
                tabs[1].config[SemanticsActions.OnClick].action!!(); assertEquals("b", selected)
            } finally { scene.close() }
        }
    }
    @Test fun `taskbar entry admits only unchanged installer shortcut and never a version executable`() {
        val root = Files.createTempDirectory("ccb-taskbar-entry-")
        try {
            val home = Files.createDirectories(root.resolve("installed/versions/current"))
            val installed = home.parent.parent
            Files.writeString(installed.resolve("install-owner.txt"), "ccb-desktop-nsis-v1")
            Files.writeString(installed.resolve("ChatChatBar.exe"), "synthetic stable launcher")
            val roaming = Files.createDirectory(root.resolve("roaming"))
            val link = roaming.resolve("Microsoft/Windows/Start Menu/Programs/ChatChatBar Desktop.lnk")
            Files.createDirectories(link.parent); val bytes = "synthetic shortcut".toByteArray(); Files.write(link, bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            Files.writeString(installed.resolve("programs-shortcut.sha256"), hash)
            assertEquals(link, desktopTaskbarShortcut(home, roaming))
            Files.writeString(link, "changed by user")
            assertFails { desktopTaskbarShortcut(home, roaming) }
            assertFails { desktopTaskbarShortcut(installed, roaming) }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
