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

import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState

/** Real Windows app factories, recovery Popup callbacks and DPAPI; not packaged Main/physical user proof. */
class DesktopCloudRecoveryUiControlTest {
    private class Roots(val runner: Path, val local: Path, val roaming: Path)
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun realRecoveryPopupPreservesPreparationAndBothConnectionChoicesAcrossColdAppRoots() {
        var phase = "native-guard"
        var failed: String? = null
        var failedType = "cleanup"
        var failedStack: Array<StackTraceElement>? = null
        var runner: Path? = null
        val owned = mutableListOf<Path>()
        val apps = mutableListOf<DesktopAppContainer>()
        val scenes = mutableListOf<ImageComposeScene>()
        var password: String? = null
        try {
            val roots = guardedRoots(); runner = roots.runner
            val secretFile = Path.of(checkNotNull(System.getenv("CCB_RECOVERY_UI_PASSWORD_FILE"))).toRealPath()
            check(secretFile.startsWith(roots.runner) && secretFile != roots.runner)
            password = Files.readString(secretFile, Charsets.UTF_8)
            val username = checkNotNull(System.getenv("CCB_RECOVERY_UI_USER"))
            val endpoint = checkNotNull(System.getenv("CCB_RECOVERY_UI_ENDPOINT")).toHttpUrl()
            check(endpoint.isHttps && endpoint.host == "127.0.0.1" && endpoint.encodedPath == "/")
            check(!System.getProperty("javax.net.ssl.trustStore").isNullOrBlank())
            runBlocking(edt) {
                for (save in listOf(false, true)) {
                    val mode = if (save) "save" else "restore"
                    fun makeApp(label: String): Pair<DesktopAppContainer, DesktopDataRootResolution.Resolved> {
                        val parent = Files.createTempDirectory(roots.runner, "ccb-recovery-ui-$mode-$label-").toRealPath()
                        check(parent.startsWith(roots.runner) && parent != roots.runner); owned.add(parent)
                        val data = Files.createDirectory(parent.resolve("data"))
                        val resolved = DesktopDataRootResolution.Resolved(data, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
                        val sync = DesktopLanSyncStartup.localRoot(data).toAbsolutePath().normalize()
                        val secrets = WindowsSecretRootResolver.resolve(data).toAbsolutePath().normalize()
                        check(sync.startsWith(roots.local) && secrets.startsWith(roots.local))
                        check(!Files.exists(sync)); owned.add(sync)
                        // Normal roots of one Windows user share the protected credential authority.
                        // It must either be new or have been created by an earlier app in this test.
                        check(!Files.exists(secrets) || secrets in owned)
                        if (secrets !in owned) owned.add(secrets)
                        return newContainer(resolved).also(apps::add) to resolved
                    }
                    fun show(app: DesktopAppContainer): ImageComposeScene = ImageComposeScene(1000, 1600, coroutineContext = edt) {
                        CompositionLocalProvider(LocalDesktopUiStrings provides DesktopUiStrings(DesktopUiLanguage.ZH_CN)) {
                            DesktopLanSyncEntry(app.lanSyncGroupCoordinator, { app.lanSync }, app::hasLanSyncGroupEvidence)
                        }
                    }.also(scenes::add)
                    phase = "$mode-founder-create-ui"
                    val (founder, _) = makeApp("founder"); founder.initializePersistentState()
                    val fc = founder.lanSyncGroupCoordinator; val fs = show(founder)
                    awaitScene(fs) { enabled(fs, "创建新设备组") }; click(fs, "创建新设备组")
                    awaitScene(fs) { fc.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !fc.state.value.busy }
                    var code: String? = checkNotNull(fc.state.value.recoveryCode)
                    click(fs, "我已妥善保存恢复码"); awaitScene(fs) { enabled(fs, "完成创建") }; click(fs, "完成创建")
                    awaitScene(fs) { fc.state.value.stage == LanSyncGroupPanelStage.READY && !fc.state.value.busy }
                    fs.close(); scenes.remove(fs)
                    phase = "$mode-source-publication-with-page-absent"
                    val card = CharacterCard("synthetic-recovery-$mode", "synthetic platform recovery $mode", createdAt = 1L, updatedAt = 1L)
                    val unit = "character_cards/${card.id}"; founder.characterRepository.save(card)
                    withTimeout(95_000L) {
                        while (fc.view.value?.reconciliation?.authenticatedVersions?.none { actual ->
                            actual.envelope.version?.changes?.get(unit)?.content != null &&
                                fc.view.value?.reconciliation?.history?.state(checkNotNull(actual.envelope.version).id) == SyncVersionState.ADMITTED
                        } != false) delay(50)
                    }
                    val source = checkNotNull(founder.lanSyncGroupEnrollment.runtimeAccess())
                    val originalTrust = source.trust
                    val remote = endpoint.newBuilder().addPathSegment(mode).addPathSegment("").build()
                    withContext(Dispatchers.IO) {
                        val client = WebDavCloudClient.create()
                        val transport = WebDavHttpTransport(remote, client, { Credentials.basic(username, checkNotNull(password), Charsets.UTF_8) }, WebDavEndpointPolicy.HTTPS_ONLY)
                        try {
                            val work = Files.createTempDirectory(roots.runner, "ccb-recovery-ui-seed-").toRealPath(); owned.add(work)
                            val route = SyncStoreId.random()
                            val attempts = WeakWebDavAttemptStore(transport, Files.createDirectory(work.resolve("whole")), route)
                            val parts = WeakWebDavChunkAssembly(transport, Files.createDirectory(work.resolve("parts")), route)
                            val target = source.targetPublication(Files.createDirectory(work.resolve("target")), SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route))
                            check(target.uploadCloud(attempts, parts).objects.isNotEmpty())
                        } finally { transport.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
                    }
                    founder.close(); apps.remove(founder) // Old device is offline before receiver recovery starts.
                    phase = "$mode-empty-app-initialize"
                    val (receiver, resolved) = makeApp("receiver"); receiver.initializePersistentState()
                    val rc = receiver.lanSyncGroupCoordinator; val recovery = checkNotNull(rc.cloudRecovery); val rs = show(receiver)
                    phase = "$mode-empty-app-await-recovery-entry"
                    awaitScene(rs) { enabled(rs, "从网盘恢复设备组") }; check(!receiver.hasLanSyncGroupEvidence())
                    phase = "$mode-empty-app-open-recovery-popup"
                    click(rs, "从网盘恢复设备组")
                    phase = "$mode-empty-app-await-four-fields"
                    awaitScene(rs) { recoveryFields(rs).size == 4 }
                    phase = "$mode-empty-app-fill-four-fields"
                    setRecoveryInputs(rs, remote.toString(), username, checkNotNull(password), checkNotNull(code))
                    phase = "$mode-empty-app-await-read-button"
                    awaitScene(rs) { enabled(rs, "读取并核对设备组") }; click(rs, "读取并核对设备组")
                    phase = "$mode-empty-app-read-and-await-review"
                    awaitScene(rs) { recovery.state.value.review != null && !recovery.state.value.busy &&
                        hasToggle(rs, "恢复后保存此连接并继续自动同步") }
                    val first = checkNotNull(recovery.state.value.review)
                    check(first.group == originalTrust.group.value && !recovery.state.value.complete)
                    check(!enabled(rs, "登记此设备并恢复资料"))
                    phase = "$mode-explicit-close-retains-preparation-not-completion"
                    click(rs, "关闭")
                    awaitScene(rs) { !recovery.state.value.visible && rc.state.value.stage == LanSyncGroupPanelStage.JOINING && !rc.state.value.busy }
                    check(!recovery.state.value.complete)
                    check(receiver.lanSyncGroupJoining.profile()?.stage != LanSyncGroupJoinStage.READY)
                    click(rs, "从网盘恢复设备组")
                    awaitScene(rs) { recoveryFields(rs).size == 4 }
                    check(recoveryFields(rs).all { it.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty() })
                    setRecoveryInputs(rs, remote.toString(), username, checkNotNull(password), checkNotNull(code))
                    awaitScene(rs) { enabled(rs, "读取并核对设备组") }; click(rs, "读取并核对设备组")
                    awaitScene(rs) { recovery.state.value.review != null && !recovery.state.value.busy &&
                        hasToggle(rs, "恢复后保存此连接并继续自动同步") }
                    check(checkNotNull(recovery.state.value.review).device == first.device)
                    phase = "$mode-confirm-real-checkbox-choice"
                    check(toggle(rs, "恢复后保存此连接并继续自动同步") == ToggleableState.On)
                    if (!save) { click(rs, "恢复后保存此连接并继续自动同步"); check(toggle(rs, "恢复后保存此连接并继续自动同步") == ToggleableState.Off) }
                    click(rs, "我已核对以上身份，并同意登记此设备")
                    awaitScene(rs) { enabled(rs, "登记此设备并恢复资料") }; click(rs, "登记此设备并恢复资料")
                    awaitScene(rs) { recovery.state.value.complete && !recovery.state.value.busy && rc.state.value.stage == LanSyncGroupPanelStage.READY }
                    check(recovery.state.value.error == null)
                    check(receiver.characterRepository.getById(card.id) == card)
                    val daily = checkNotNull(receiver.lanSyncGroupJoining.runtimeAccess())
                    check(daily.trust.group == originalTrust.group && daily.trust.genesis == originalTrust.genesis && daily.trust.ownerCertificate == originalTrust.ownerCertificate)
                    check(daily.device == first.device)
                    val routes = daily.cloudRoutes().rows()
                    if (save) { check(routes.size == 1 && routes.single().enabled); check(recovery.state.value.connectionEnabled == true) }
                    else { check(routes.isEmpty()); check(recovery.state.value.connectionEnabled == null) }
                    val catalog = daily.work.resolve("cloud-connections/cloud-routes.protected")
                    val protectedCatalog = if (save) Files.readAllBytes(catalog) else null
                    if (protectedCatalog != null) check(!String(protectedCatalog, Charsets.ISO_8859_1).contains(checkNotNull(password)))
                    rs.close(); scenes.remove(rs); receiver.close(); apps.remove(receiver)
                    phase = "$mode-cold-app-start-without-sync-page"
                    val cold = newContainer(resolved).also(apps::add); cold.initializePersistentState()
                    withTimeout(60_000L) {
                        while (cold.lanSyncGroupCoordinator.state.value.stage != LanSyncGroupPanelStage.READY || cold.lanSyncGroupCoordinator.state.value.busy) delay(50)
                    }
                    val coldDaily = checkNotNull(cold.lanSyncGroupJoining.runtimeAccess())
                    check(coldDaily.device == first.device && coldDaily.trust.group == originalTrust.group)
                    check(cold.characterRepository.getById(card.id) == card)
                    check(coldDaily.cloudRoutes().rows() == routes)
                    if (protectedCatalog != null) check(Files.readAllBytes(catalog).contentEquals(protectedCatalog))
                    cold.close(); apps.remove(cold); code = null
                }
            }
        } catch (failure: Throwable) {
            failed = phase
            failedType = failure.javaClass.simpleName
            failedStack = failure.stackTrace
        }
        finally {
            password = null
            runBlocking(edt) { withContext(NonCancellable) {
                scenes.asReversed().forEach { try { it.close() } catch (_: Throwable) { if (failed == null) failed = "scene-cleanup" } }
                apps.asReversed().forEach { try { it.close() } catch (_: Throwable) { if (failed == null) failed = "app-cleanup" } }
            } }
            owned.asReversed().forEach { try { deleteOwned(it, checkNotNull(runner)) } catch (_: Throwable) { if (failed == null) failed = "owned-root-cleanup" } }
        }
        failed?.let {
            val sanitized = AssertionError("DESKTOP_RECOVERY_UI_PHASE_FAILED:$it:$failedType")
            failedStack?.let { originalFrames -> sanitized.stackTrace = originalFrames }
            throw sanitized
        }
    }

    private fun recoveryFields(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.map { walk(it.rootSemanticsNode).filter { node -> node.config.contains(SemanticsActions.SetText) } }
            .singleOrNull { it.size == 4 }.orEmpty()
    }
    private suspend fun setRecoveryInputs(scene: ImageComposeScene, endpoint: String, username: String, password: String, code: String) {
        // Rendered nodes can precede initial LaunchedEffects and keyed secret-field replacement.
        // Let the real UI settle, then acquire a current node for each independent interaction.
        settleScene(scene)
        val values = listOf(endpoint, username, password, code)
        values.forEachIndexed { index, value ->
            val fields = recoveryFields(scene); check(fields.size == 4)
            val field = fields[index]
            check(checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
            settleScene(scene)
            val current = recoveryFields(scene); check(current.size == 4)
            // Secret EditableText may be visually masked; never log its text, length or digest.
            check(current[index].config.getOrNull(SemanticsProperties.EditableText)?.text?.length == value.length)
        }
        recoveryFields(scene).zip(values).forEach { (field, value) ->
            check(field.config.getOrNull(SemanticsProperties.EditableText)?.text?.length == value.length)
        }
    }
    private suspend fun settleScene(scene: ImageComposeScene) {
        repeat(3) { scene.render(System.nanoTime()).close(); delay(25L) }
        scene.render(System.nanoTime()).close()
    }
    private fun hasToggle(scene: ImageComposeScene, label: String): Boolean = nodes(scene).any {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox && matches(it, label) &&
            it.config.getOrNull(SemanticsProperties.ToggleableState) != null
    }
    private fun toggle(scene: ImageComposeScene, label: String): ToggleableState = checkNotNull(nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox && matches(it, label)
    }.config.getOrNull(SemanticsProperties.ToggleableState))
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
    private suspend fun click(scene: ImageComposeScene, label: String) {
        val node = button(scene, label)
        check(!node.config.contains(SemanticsProperties.Disabled))
        val bounds = node.boundsInRoot
        check(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= 1000 && bounds.bottom <= 1600)
        check(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        settleScene(scene)
    }
    private fun editable(scene: ImageComposeScene) = nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }
    private fun setOnlyText(scene: ImageComposeScene, value: String) {
        val field = editable(scene).single()
        check(checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
    }
    private suspend fun awaitScene(scene: ImageComposeScene, ready: () -> Boolean) = withTimeout(45_000L) {
        while (true) {
            scene.render(System.nanoTime()).close()
            // Observe after queued composition/lifecycle effects, rather than acting on the
            // first frame before the application has processed its own initialization.
            delay(25L)
            scene.render(System.nanoTime()).close()
            if (ready()) break
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
