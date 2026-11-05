@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopRecoveryWorkspaceTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `narrow and wide recovery guidance wraps its original-page actions without adding work`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val selected = mutableListOf<DesktopRecoveryDestination>()
            val scroll = ScrollState(0)
            val statuses = listOf(DesktopRecoveryStatus(DesktopRecoveryArea.DIRECTORY, DesktopRecoveryPhase.IDLE),
                DesktopRecoveryStatus(DesktopRecoveryArea.BACKUP, DesktopRecoveryPhase.CHECKED),
                DesktopRecoveryStatus(DesktopRecoveryArea.MERGE, DesktopRecoveryPhase.PREVIEW))
            val scene = ImageComposeScene(width, 760) {
                Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).verticalScroll(scroll).padding(16.dp)) {
                    DesktopRecoveryPanel(statuses, true) { selected += it }
                }
            }
            try {
                frames(scene)
                val buttons = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }
                assertEquals(DesktopRecoveryDestination.entries.size, buttons.size)
                for (button in buttons) {
                    assertTrue(button.boundsInRoot.left >= 0 && button.boundsInRoot.right <= width + 1)
                    button.config[SemanticsActions.OnClick].action!!.invoke()
                }
                assertEquals(DesktopRecoveryDestination.entries.toList(), selected)
                capture(scene, "recovery-$width-top")
                scroll.scrollTo(scroll.maxValue); frames(scene)
                capture(scene, "recovery-$width-bottom")
            } finally { scene.close() }
        }
    }
    @Test fun `recovery destinations select original workspace panes without choosing files submitting import or changing data`() = runBlocking(edt) {
        var picks = 0; var directories = 0; var exits = 0
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? { picks++; return null }
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? { picks++; return null }
        }
        val f = FinalProductDesignFixture(picker); f.initialize(false)
        val c = f.container
        val automaticState = c.automaticBackupRuntime.initialize()
        assertEquals(false, automaticState.effectiveSettings?.automaticBackup?.enabled)
        assertFalse(automaticState.schedulerRunning)
        c.characterRepository.save(CharacterCard.create("Synthetic recovery character").copy(id = "card"))
        c.chatRepository.createSession(ChatSession("room", "card", "Recovery room", createdAt = 1, updatedAt = 1))
        c.primaryChatController.refresh(); c.primaryChatController.selectSession("room")
        c.primaryChatController.editComposer("Retained recovery navigation draft")
        val original = Files.readAllBytes(c.appDataRoot.resolve("entities/character_cards/card.json"))
        val root = DesktopDataRootSwitchController(DesktopDataRootResolution.Resolved(c.appDataRoot,
            DesktopDataRootProvenance.CLI_OVERRIDE, f.root.resolve("bootstrap.json")),
            DesktopDirectoryPicker { directories++; null }, { false }, { error("No migration submission") })
        val initialMerge = c.mergeImportController.state.value
        val scene = ImageComposeScene(1100, 800) {
            val scope = rememberCoroutineScope()
            val automatic = remember { DesktopAutomaticBackupSettingsController(c.automaticBackupRuntime, scope) }
            DesktopDataWorkspace(root, c.fullBackupController, c.mergeImportController, automatic, { exits++ })
        }
        try {
            frames(scene)
            val routes = listOf(
                DesktopUiText.RECOVERY_OPEN_EXPORT to "选择导出内容…",
                DesktopUiText.RECOVERY_OPEN_CHECK to "检查备份文件",
                DesktopUiText.RECOVERY_OPEN_IMPORT to "导入与合并",
                DesktopUiText.RECOVERY_OPEN_LOCATION to DesktopUiText.CURRENT_DATA_DIRECTORY.zhCn,
                DesktopUiText.RECOVERY_OPEN_AUTO to DesktopUiText.BACKUP_MINIMUM.zhCn,
                DesktopUiText.RECOVERY_OPEN_SUPPORT to "复制基本诊断",
            )
            for ((entry, expected) in routes) {
                click(scene, DesktopUiText.RECOVERY_SUPPORT.zhCn); frames(scene)
                click(scene, entry.zhCn); frames(scene)
                val reached = withTimeoutOrNull(5000) { while (!has(scene, expected)) frames(scene); true } == true
                if (!reached) capture(scene, "recovery-navigation-${entry.name}-failure")
                assertTrue(reached, "destination=${entry.name}, expected=$expected, visible=" + nodes(scene)
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }.take(4000))
                assertEquals(0, picks); assertEquals(0, directories); assertEquals(0, exits)
                if (entry == DesktopUiText.RECOVERY_OPEN_IMPORT) assertTrue(nodes(scene).none { it.config.contains(SemanticsActions.SetText) })
                assertEquals(initialMerge, c.mergeImportController.state.value)
                assertTrue(c.mergeImportController.workRows.value.isEmpty())
                assertTrue(c.fullBackupController.workRows.value.isEmpty())
                assertEquals("Retained recovery navigation draft", c.primaryChatController.state.value.composerDraft)
                assertContentEquals(original, Files.readAllBytes(c.appDataRoot.resolve("entities/character_cards/card.json")))
                assertTrue(f.requests.isEmpty())
            }
        } finally { scene.close(); f.close() }
    }

    @Test fun `stale recovery click cannot escape actual backup cleanup or original migration and restart guards`() = runBlocking(edt) {
        val picked = CompletableDeferred<Unit>(); val pickerRelease = java.util.concurrent.CountDownLatch(1)
        val migrationStarted = CompletableDeferred<Unit>(); val migrationRelease = CompletableDeferred<Unit>()
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? = error("No inspection requested")
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? {
                picked.complete(Unit); check(pickerRelease.await(20, java.util.concurrent.TimeUnit.SECONDS)); return null
            }
        }
        val f = FinalProductDesignFixture(picker); f.initialize(false); val c = f.container
        val target = f.root.resolve("synthetic-migration-target")
        val root = DesktopDataRootSwitchController(DesktopDataRootResolution.Resolved(c.appDataRoot,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, f.root.resolve("bootstrap.json")), DesktopDirectoryPicker { target }, { false }, {
            migrationStarted.complete(Unit); migrationRelease.await()
            DesktopDataRootMigrationResult.Failure(DesktopDataRootMigrationFailureKind.entries.first(), c.appDataRoot,
                destinationRoot = target, restartRequired = true, sourceStillAuthoritative = null)
        })
        val scene = ImageComposeScene(1100, 800) {
            val scope = rememberCoroutineScope()
            val automatic = remember { DesktopAutomaticBackupSettingsController(c.automaticBackupRuntime, scope) }
            DesktopDataWorkspace(root, c.fullBackupController, c.mergeImportController, automatic, {})
        }
        var migrating: Job? = null
        try {
            frames(scene); click(scene, DesktopUiText.RECOVERY_SUPPORT.zhCn)
            val stale = nodes(scene).first { matches(it, DesktopUiText.RECOVERY_OPEN_SUPPORT.zhCn) && it.config.contains(SemanticsActions.OnClick) }
                .config[SemanticsActions.OnClick].action!!
            root.chooseDestination(); frames(scene)
            assertIs<DesktopDataRootSwitchState.CandidateSelected>(root.state.value)
            stale.invoke(); frames(scene); assertFalse(has(scene, "复制基本诊断"))
            root.cancelCandidate(); frames(scene)
            assertTrue(has(scene, DesktopUiText.RECOVERY_SCOPE.zhCn))

            c.fullBackupController.export(null); withTimeout(5000) { picked.await() }; frames(scene)
            stale.invoke(); frames(scene); assertFalse(has(scene, "复制基本诊断"))
            assertTrue(c.fullBackupController.state.value.busy)
            pickerRelease.countDown()
            withTimeout(5000) { c.fullBackupController.workRows.first { it.single().phase == DesktopWorkPhase.CANCELLED } }
            frames(scene); assertTrue(has(scene, DesktopUiText.RECOVERY_SCOPE.zhCn))

            root.chooseDestination(); migrating = launch { root.confirmMigration() }
            withTimeout(5000) { migrationStarted.await() }; frames(scene)
            val running = root.state.value
            assertIs<DesktopDataRootSwitchState.Migrating>(running)
            stale.invoke(); frames(scene); assertSame(running, root.state.value); assertFalse(has(scene, "复制基本诊断"))
            migrationRelease.complete(Unit); assertNotNull(migrating).join(); frames(scene)
            val restart = root.state.value
            assertIs<DesktopDataRootSwitchState.RestartRequired>(restart)
            stale.invoke(); frames(scene); assertSame(restart, root.state.value); assertFalse(has(scene, "复制基本诊断"))
            assertFalse(Files.exists(target)); assertTrue(c.mergeImportController.workRows.value.isEmpty()); assertTrue(f.requests.isEmpty())
        } finally { pickerRelease.countDown(); migrationRelease.complete(Unit); migrating?.cancelAndJoin(); scene.close(); f.close() }
    }

    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render().close(); yield(); delay(20) } }
    private suspend fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { matches(it, label) && it.config.contains(SemanticsActions.OnClick) }
        node.config[SemanticsActions.OnClick].action!!.invoke(); frames(scene)
    }
    private fun has(scene: ImageComposeScene, label: String) = nodes(scene).any { matches(it, label) }
    private fun matches(node: SemanticsNode, label: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_RECOVERY_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("$name.png"); Files.createDirectories(path.parent)
            scene.render().use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }
    }
}
