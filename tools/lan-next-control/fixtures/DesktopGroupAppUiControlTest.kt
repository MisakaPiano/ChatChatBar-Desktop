@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop.lanSync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.WindowsSecretRootResolver
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.example.chatbar.domain.syncStore.CiphertextObjectMetadata
import com.example.chatbar.domain.syncStore.CiphertextStore
import com.example.chatbar.domain.syncStore.SyncAuthenticatedGroupObject
import com.example.chatbar.domain.syncStore.SyncVersionRef
import com.example.chatbar.domain.syncStore.SyncVersionState
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/** Cloud-only real entry/component + AppContainer proof; never a packaged-Main/window proof. */
class DesktopGroupAppUiControlTest {
    private class Roots(val runner: Path, val local: Path, val roaming: Path)
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test
    fun actualGroupSceneKeepsAppOwnerAfterLeavingAndReopensProtectedProfile() {
        var phase = "native-guard"
        var failedPhase: String? = null
        var first: DesktopAppContainer? = null
        var reopened: DesktopAppContainer? = null
        var scene: ImageComposeScene? = null
        val owned = mutableListOf<Path>()
        var runner: Path? = null
        try {
            val roots = guardedRoots()
            runner = roots.runner
            runBlocking(edt) {
                try {
                    phase = "isolated-roots"
                    val parent = Files.createTempDirectory(roots.runner, "ccb-group-scene-control-").toRealPath()
                    check(parent.startsWith(roots.runner) && parent != roots.runner)
                    owned.add(parent)
                    val data = Files.createDirectory(parent.resolve("data"))
                    val resolved = DesktopDataRootResolution.Resolved(
                        data, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
                    val syncRoot = DesktopLanSyncStartup.localRoot(data).toAbsolutePath().normalize()
                    val credentials = WindowsSecretRootResolver.resolve(data).toAbsolutePath().normalize()
                    check(syncRoot.startsWith(roots.local) && credentials.startsWith(roots.local))
                    check(!Files.exists(syncRoot, NOFOLLOW_LINKS) && !Files.exists(credentials, NOFOLLOW_LINKS))
                    owned.add(syncRoot)
                    owned.add(credentials)

                    phase = "first-app-initialize"
                    val app = newContainer(resolved)
                    first = app
                    app.initializePersistentState()
                    val coordinator = app.lanSyncGroupCoordinator
                    check(!app.hasLanSyncGroupEvidence())
                    check(coordinator.state.value.stage == LanSyncGroupPanelStage.EMPTY)
                    val visible = mutableStateOf(true)
                    val currentScene = ImageComposeScene(1000, 1600, coroutineContext = edt) {
                        CompositionLocalProvider(LocalDesktopUiStrings provides DesktopUiStrings(DesktopUiLanguage.ZH_CN)) {
                            if (visible.value) {
                                DesktopLanSyncEntry(coordinator, { app.lanSync }, app::hasLanSyncGroupEvidence)
                            } else Box(Modifier.fillMaxSize())
                        }
                    }
                    scene = currentScene

                    phase = "create-through-ui"
                    awaitScene(currentScene) { enabled(currentScene, "创建新设备组") }
                    click(currentScene, "创建新设备组")
                    awaitScene(currentScene) {
                        coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE &&
                            !coordinator.state.value.busy && has(currentScene, "完成创建")
                    }
                    check(coordinator.state.value.error == null)
                    var recoveryCode: String? = checkNotNull(coordinator.state.value.recoveryCode)
                    check(recoveryCode!!.isNotBlank())
                    check(!enabled(currentScene, "完成创建"))

                    phase = "save-code-confirmation-through-ui"
                    click(currentScene, "我已妥善保存恢复码")
                    awaitScene(currentScene) { enabled(currentScene, "完成创建") }
                    click(currentScene, "完成创建")
                    awaitScene(currentScene) {
                        coordinator.state.value.stage == LanSyncGroupPanelStage.READY &&
                            !coordinator.state.value.busy && !coordinator.state.value.managementUnlocked
                    }
                    check(coordinator.state.value.error == null)
                    val originalProfile = checkNotNull(app.lanSyncGroupEnrollment.profile())
                    val originalIdentity = originalProfile.device
                    val originalGroup = originalProfile.trust.group
                    val protectedIdentity = Files.readAllBytes(syncRoot.resolve("identity.protected"))
                    check(protectedIdentity.isNotEmpty())

                    phase = "unlock-through-ui"
                    awaitScene(currentScene) { editable(currentScene).size == 1 }
                    setOnlyText(currentScene, checkNotNull(recoveryCode))
                    recoveryCode = null
                    awaitScene(currentScene) { enabled(currentScene, "解锁管理") }
                    click(currentScene, "解锁管理")
                    awaitScene(currentScene) {
                        coordinator.state.value.managementUnlocked && !coordinator.state.value.busy
                    }
                    check(coordinator.state.value.error == null)

                    phase = "leave-entry-disposal-locks-management"
                    visible.value = false
                    awaitScene(currentScene) { !coordinator.state.value.managementUnlocked }
                    check(coordinator.state.value.recoveryCode == null)
                    check(coordinator.state.value.invitation == null)
                    check(coordinator.state.value.stage == LanSyncGroupPanelStage.READY)
                    check(!has(currentScene, "设备组同步"))

                    phase = "save-real-repository-with-entry-absent"
                    val card = CharacterCard(
                        id = UUID.randomUUID().toString(),
                        name = "合成组页离页自动发布",
                        createdAt = 1L,
                        updatedAt = 1L,
                        momentsEnabled = false,
                    )
                    val unit = "character_cards/${card.id}"
                    app.characterRepository.save(card)
                    val cardFile = data.resolve("entities/character_cards/${card.id}.json")
                    val originalCard = Files.readAllBytes(cardFile)
                    check(originalCard.isNotEmpty())

                    phase = "await-automatic-authenticated-publication"
                    // No retry/open/inspect/commit/upload call is allowed to advance this phase.
                    var published: SyncAuthenticatedGroupObject? = null
                    withTimeout(95_000L) {
                        while (published == null) {
                            val view = coordinator.view.value
                            val candidate = if (view == null) null else view.reconciliation.authenticatedVersions.firstOrNull { actual ->
                                val version = actual.envelope.version
                                version != null && version.group == originalGroup && version.author == originalIdentity &&
                                    version.changes[unit]?.content != null &&
                                    view.reconciliation.history?.state(version.id) == SyncVersionState.ADMITTED &&
                                    view.nativeState.units[unit]?.frontiers?.contains(version.reference()) == true
                            }
                            if (candidate != null && coordinator.state.value.local.lastSuccessAt != null) published = candidate
                            else delay(100L)
                        }
                    }
                    val actual = checkNotNull(published)
                    val reference: SyncVersionRef = checkNotNull(actual.envelope.version).reference()
                    check(!coordinator.state.value.managementUnlocked)
                    check(coordinator.state.value.error == null)
                    check(Files.readAllBytes(cardFile).contentEquals(originalCard))

                    phase = "read-exact-local-publication-receipt"
                    val access = checkNotNull(app.lanSyncGroupEnrollment.runtimeAccess())
                    check(access.device == originalIdentity && access.trust.group == originalGroup)
                    val localReplica = access.replica
                    val receipts = access.node.sourceOperations().mapNotNull { operation ->
                        access.node.receipt(operation, originalProfile.localReplica)
                    }
                    check(receipts.any {
                        it.target == originalProfile.localReplica && it.metadata == actual.metadata.descriptor
                    })

                    phase = "close-scene-before-final-writer"
                    currentScene.close()
                    scene = null

                    phase = "accept-real-task-runtime-final-writer"
                    val exitCard = CharacterCard(
                        id = UUID.randomUUID().toString(),
                        name = "合成关闭排水后的最终写入",
                        createdAt = 2L,
                        updatedAt = 2L,
                        momentsEnabled = false,
                    )
                    val exitUnit = "character_cards/${exitCard.id}"
                    val exitFile = data.resolve("entities/character_cards/${exitCard.id}.json")
                    val finalWriterEntered = CompletableDeferred<Unit>()
                    val finalWriterCompleted = CompletableDeferred<Unit>()
                    var exitRaw: ByteArray? = null
                    app.taskRuntime.launchNovelAi("synthetic-group-shutdown-final-writer") {
                        try {
                            finalWriterEntered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                app.characterRepository.save(exitCard)
                                exitRaw = Files.readAllBytes(exitFile)
                                finalWriterCompleted.complete(Unit)
                            }
                        }
                    }
                    withTimeout(10_000L) { finalWriterEntered.await() }
                    check(!Files.exists(exitFile, NOFOLLOW_LINKS))
                    check(!finalWriterCompleted.isCompleted)

                    phase = "actual-app-close-drains-and-seals-final-writer"
                    // Only actual AppContainer.close may cancel/drain the accepted writer and seal it.
                    withTimeout(45_000L) { app.close() }
                    first = null

                    phase = "verify-final-writer-durable-before-any-reopen"
                    check(finalWriterCompleted.isCompleted)
                    finalWriterCompleted.await()
                    val finalWriterRaw = checkNotNull(exitRaw)
                    check(finalWriterRaw.isNotEmpty())
                    check(Files.readAllBytes(exitFile).contentEquals(finalWriterRaw))

                    phase = "verify-final-authenticated-native-frontier-before-any-reopen"
                    // Read the old owner's final view only; never inspect/retry/open another node here.
                    val finalView = checkNotNull(coordinator.view.value)
                    val finalActual = checkNotNull(finalView.reconciliation.authenticatedVersions.firstOrNull { candidate ->
                        val version = candidate.envelope.version
                        version != null && version.group == originalGroup && version.author == originalIdentity &&
                            version.changes[exitUnit]?.content != null &&
                            finalView.reconciliation.history?.state(version.id) == SyncVersionState.ADMITTED &&
                            finalView.nativeState.units[exitUnit]?.frontiers?.contains(version.reference()) == true
                    })
                    val exitReference = checkNotNull(finalActual.envelope.version).reference()

                    phase = "verify-exact-final-ciphertext-durable-before-any-reopen"
                    // Captured before close: only the public canonical local store is read here.
                    // Complete expected ciphertext hashes are checked before any new owner can repair it.
                    withContext(Dispatchers.IO) {
                        verifyCiphertext(localReplica, finalActual.metadata)
                        finalActual.envelope.blobs.values.forEach { binding ->
                            verifyCiphertext(localReplica, CiphertextObjectMetadata(binding.key, binding.descriptor))
                        }
                    }

                    phase = "reopen-app-without-group-page"
                    val second = newContainer(resolved)
                    reopened = second
                    second.initializePersistentState()
                    val secondCoordinator = second.lanSyncGroupCoordinator
                    withTimeout(45_000L) {
                        while (secondCoordinator.state.value.stage != LanSyncGroupPanelStage.READY ||
                            secondCoordinator.state.value.busy || secondCoordinator.view.value == null) delay(50L)
                    }
                    check(secondCoordinator.state.value.error == null)
                    check(!secondCoordinator.state.value.managementUnlocked)
                    check(secondCoordinator.state.value.recoveryCode == null)
                    val secondProfile = checkNotNull(second.lanSyncGroupEnrollment.profile())
                    check(secondProfile.device == originalIdentity && secondProfile.trust.group == originalGroup)
                    check(Files.readAllBytes(syncRoot.resolve("identity.protected")).contentEquals(protectedIdentity))
                    check(second.characterRepository.getById(card.id) == card)
                    check(Files.readAllBytes(cardFile).contentEquals(originalCard))
                    check(second.characterRepository.getById(exitCard.id) == exitCard)
                    check(Files.readAllBytes(exitFile).contentEquals(finalWriterRaw))
                    val secondView = checkNotNull(secondCoordinator.view.value)
                    check(secondView.reconciliation.history?.contains(reference) == true)
                    check(secondView.nativeState.units[unit]?.frontiers?.contains(reference) == true)
                    check(secondView.reconciliation.history?.contains(exitReference) == true)
                    check(secondView.nativeState.units[exitUnit]?.frontiers?.contains(exitReference) == true)

                    phase = "normal-reopened-app-drain"
                    withTimeout(45_000L) { second.close() }
                    reopened = null
                } catch (_: Throwable) {
                    failedPhase = phase
                } finally {
                    phase = "scene-cleanup"
                    try { scene?.close() } catch (_: Throwable) { if (failedPhase == null) failedPhase = phase }
                    scene = null
                    phase = "reopened-app-cleanup"
                    try { reopened?.close() } catch (_: Throwable) { if (failedPhase == null) failedPhase = phase }
                    reopened = null
                    phase = "first-app-cleanup"
                    try { first?.close() } catch (_: Throwable) { if (failedPhase == null) failedPhase = phase }
                    first = null
                }
            }
        } catch (_: Throwable) {
            if (failedPhase == null) failedPhase = phase
        } finally {
            phase = "owned-synthetic-root-cleanup"
            owned.asReversed().forEach { path ->
                try { deleteOwned(path, checkNotNull(runner)) }
                catch (_: Throwable) { if (failedPhase == null) failedPhase = phase }
            }
        }
        failedPhase?.let { throw AssertionError("DESKTOP_GROUP_UI_CONTROL_FAILED:$it") }
    }

    private fun verifyCiphertext(store: CiphertextStore, metadata: CiphertextObjectMetadata) {
        check(store.metadata(metadata.key) == metadata)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) {
                check(count < metadata.descriptor.length)
                digest.update(value.toByte())
                count++
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                check(count <= metadata.descriptor.length && length.toLong() <= metadata.descriptor.length - count)
                digest.update(bytes, offset, length)
                count += length.toLong()
            }
        }
        check(store.readTo(metadata.key, sink, metadata.descriptor) == metadata)
        check(count == metadata.descriptor.length)
        check(HexFormat.of().formatHex(digest.digest()) == metadata.descriptor.sha256.hex)
    }

    private fun newContainer(resolved: DesktopDataRootResolution.Resolved) = DesktopAppContainer(
        resolvedRoot = resolved,
        bundledAssets = { path ->
            check(path == "presets/manifest.json")
            "{\"entries\":[]}".toByteArray(Charsets.UTF_8)
        },
    )

    private fun guardedRoots(): Roots {
        check(System.getenv("GITHUB_ACTIONS") == "true")
        check(System.getenv("CCB_GROUP_UI_CONTROL") == "true")
        check(System.getProperty("os.name").startsWith("Windows"))
        fun existing(name: String): Path {
            val raw = Path.of(checkNotNull(System.getenv(name)))
            check(raw.isAbsolute)
            return raw.toRealPath().also { check(Files.isDirectory(it, NOFOLLOW_LINKS)) }
        }
        val runner = existing("RUNNER_TEMP")
        val local = existing("LOCALAPPDATA")
        val roaming = existing("APPDATA")
        check(local != runner && roaming != runner && local.startsWith(runner) && roaming.startsWith(runner))
        check(!local.startsWith(roaming) && !roaming.startsWith(local))
        check(!Files.exists(local.resolve(WindowsSecretRootResolver.DIRECTORY_NAME), NOFOLLOW_LINKS))
        check(!Files.exists(local.resolve("ChatChatBarDesktopLanSync"), NOFOLLOW_LINKS))
        return Roots(runner, local, roaming)
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun matches(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.children.any { matches(it, label) }
    private fun has(scene: ImageComposeScene, label: String) = nodes(scene).any { matches(it, label) }
    private fun button(scene: ImageComposeScene, label: String) =
        nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && matches(it, label) }
    private fun enabled(scene: ImageComposeScene, label: String) =
        nodes(scene).any {
            it.config.contains(SemanticsActions.OnClick) && matches(it, label) &&
                !it.config.contains(SemanticsProperties.Disabled)
        }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = button(scene, label)
        check(!node.config.contains(SemanticsProperties.Disabled))
        val bounds = node.boundsInRoot
        check(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= 1000 && bounds.bottom <= 1600)
        check(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun editable(scene: ImageComposeScene) = nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }
    private fun setOnlyText(scene: ImageComposeScene, value: String) {
        val field = editable(scene).single()
        check(checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
    }
    private suspend fun awaitScene(scene: ImageComposeScene, ready: () -> Boolean) = withTimeout(45_000L) {
        while (true) {
            scene.render(System.nanoTime()).close()
            if (ready()) break
            delay(25L)
        }
    }
    private fun deleteOwned(path: Path, runner: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        val target = path.toRealPath()
        check(target != runner && target.startsWith(runner))
        Files.walk(target).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { entry ->
                check(entry.toAbsolutePath().normalize().startsWith(target))
                Files.delete(entry)
            }
        }
    }
}
