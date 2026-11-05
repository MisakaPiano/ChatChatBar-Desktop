@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopBackupPasswordPresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `workspace owns bounded password modal and hiding retained check or import retires waiting flow`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            var source: Path? = null; var picks = 0
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? { picks++; return source }
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No export requested")
            }
            val f = FinalProductDesignFixture(picker); f.initialize(false)
            val c = f.container
            source = Files.write(f.root.resolve("synthetic-encrypted.cbbackup"),
                com.example.chatbar.desktop.backup.CensusWireFixtures.bytes(encrypted = true))
            val original = Files.readAllBytes(source)
            c.automaticBackupRuntime.initialize()
            val root = DesktopDataRootSwitchController(DesktopDataRootResolution.Resolved(c.appDataRoot,
                DesktopDataRootProvenance.CLI_OVERRIDE, f.root.resolve("bootstrap.json")),
                DesktopDirectoryPicker { error("No directory change requested") }, { false }, { error("No migration requested") })
            val activity = DesktopModalActivity()
            val scene = ImageComposeScene(width, 800, coroutineContext = edt) {
                val scope = rememberCoroutineScope()
                val automatic = remember { DesktopAutomaticBackupSettingsController(c.automaticBackupRuntime, scope) }
                CompositionLocalProvider(LocalDesktopModalActivity provides activity) {
                    DesktopDataWorkspace(root, c.fullBackupController, c.mergeImportController, automatic, { error("No exit requested") })
                }
            }
            try {
                scene.frames(); scene.click("检查已有备份")
                val returnToOverview = scene.button("返回备份概览").config[SemanticsActions.OnClick].action!!
                scene.click("选择备份并检查…")
                withTimeout(15000) { c.fullBackupController.state.first { !it.busy && it.passwordRequest != null } }
                scene.frames(); assertEquals(1, activity.backupCount)
                assertEquals(1, scene.nodes().count { it.config.contains(SemanticsActions.SetText) })
                for (label in listOf("取消密码输入", "解锁并继续检查", "选择其他文件", "取消")) {
                    val bounds = scene.button(label).boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 800, label)
                }
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val file = Path.of(directory).resolve("backup-password-workspace-$width.png"); Files.createDirectories(file.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                }
                // A programmatic navigation request can leave a retained pane even while its modal owns focus.
                assertTrue(returnToOverview.invoke()); scene.frames()
                withTimeout(10000) { c.fullBackupController.state.first { !it.busy && it.passwordRequest == null } }
                withTimeout(5000) { c.fullBackupController.workRows.first { rows -> rows.none { it.active } } }
                scene.frames(); assertEquals(0, activity.count)
                assertTrue(c.fullBackupController.workRows.value.none { it.active })
                scene.click("选择备份并检查")
                val leaveImport = scene.button("数据目录").config[SemanticsActions.OnClick].action!!
                scene.click("选择备份并检查…")
                withTimeout(15000) { c.mergeImportController.state.first { !it.busy && it.passwordRequest != null } }
                scene.frames(); assertEquals(1, activity.backupCount)
                assertTrue(leaveImport.invoke()); scene.frames()
                withTimeout(10000) { c.mergeImportController.state.first { !it.busy && it.passwordRequest == null } }
                withTimeout(5000) { c.mergeImportController.workRows.first { rows -> rows.none { it.active } } }
                scene.frames(); assertEquals(0, activity.count)
                assertTrue(c.mergeImportController.workRows.value.none { it.active })
                assertEquals(2, picks); assertFalse(c.mergeImportController.state.value.ready)
                assertFalse(c.fullBackupController.state.value.restartRequired)
                assertFalse(c.mergeImportController.state.value.restartRequired)
                assertContentEquals(original, Files.readAllBytes(source)); assertTrue(f.requests.isEmpty())
            } finally { scene.close(); f.close() }
        }
    }
    @Test fun `password modal wraps compact actions preserves visibility choice and submits only explicitly`() = runBlocking(edt) {
        for (width in listOf(360, 1100)) {
            var submitted = 0; var cancelled = 0; var changed = 0
            var request by mutableStateOf(DesktopBackupPasswordRequest("synthetic.cbbackup", "请核对存档密码和文件完整性，可重试同一文件。"))
            val scene = ImageComposeScene(width, 640, coroutineContext = edt) {
                CompositionLocalProvider(LocalDesktopPalette provides desktopSemanticColors(
                    if (width == 360) ThemeMode.DARK else ThemeMode.LIGHT,
                    com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false)) {
                    DesktopBackupPasswordDialog(request, onPassword = { secret ->
                        assertContentEquals("SYNTHETIC_PASSWORD".toCharArray(), secret)
                        secret.fill('\u0000'); submitted++
                    }, onCancel = { cancelled++ }, onChooseFile = { changed++ })
                }
            }
            try {
                scene.frames()
                assertTrue(scene.button("解锁并继续检查").config.contains(SemanticsProperties.Disabled))
                assertEquals(0, submitted)
                val field = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("SYNTHETIC_PASSWORD")))
                scene.frames(); scene.click("显示密码"); scene.frames()
                assertTrue(scene.nodes().any { it.label("隐藏密码") }); assertEquals(0, submitted)
                scene.click("隐藏密码"); scene.click("解锁并继续检查"); scene.frames()
                assertEquals(1, submitted)
                request = request.copy(error = "加密备份读取未通过，请核对密码和完整性。")
                scene.frames()
                assertTrue(scene.button("解锁并继续检查").config.contains(SemanticsProperties.Disabled))
                for (label in listOf("取消密码输入", "解锁并继续检查", "选择其他文件", "取消")) {
                    val bounds = scene.button(label).boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 640, label)
                }
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val file = Path.of(directory).resolve("backup-password-retry-$width.png"); Files.createDirectories(file.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                }
                scene.click("选择其他文件"); assertEquals(1, changed)
                scene.click("取消"); assertEquals(1, cancelled)
            } finally { scene.close() }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(text: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || children.any { it.label(text) }
    private fun ImageComposeScene.button(text: String) = nodes().first { it.label(text) && it.config.contains(SemanticsActions.OnClick) }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); delay(15) } }
    private suspend fun ImageComposeScene.click(text: String) {
        val node = button(text); assertFalse(node.config.contains(SemanticsProperties.Disabled), text)
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
}
