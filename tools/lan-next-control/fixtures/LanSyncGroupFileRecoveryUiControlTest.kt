package com.example.chatbar.lanSync

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.net.BindException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Fixed cloud-only opt-in. SAF result delivery, not a claim about the DocumentsUI picker. */
@OptIn(ExperimentalTestApi::class)
class LanSyncGroupFileRecoveryUiControlTest {
    private val compose = createEmptyComposeRule()
    private var phase = "rule"
    private var observedState: (() -> String)? = null
    private var inputNodeCount = -1
    private var reopenBefore = "not-sampled"
    private var reopenAfter = "not-sampled"
    private var checkboxGeometryBefore = "not-sampled"
    private var checkboxGeometryAfter = "not-sampled"
    private var checkboxGeometryAfterBodySwipe = "not-sampled"
    private var bodyPhysicalSwipesCompleted = 0
    private val reopenVisibility = mutableListOf<Boolean>()
    private fun visibilityTrace() = synchronized(reopenVisibility) { reopenVisibility.joinToString(",") }
    private fun reopenSnapshot(coordinator: LanSyncGroupCoordinator,
        recovery: LanSyncGroupFileRecoveryOwner, launched: ActivityScenario<LanSyncActivity>): String {
        var focus = false; var attached = false
        launched.onActivity { focus = it.hasWindowFocus(); attached = it.window.decorView.isAttachedToWindow }
        val panel = coordinator.state.value
        // The dialog title has the same text after a successful open; diagnostics must not assume one text node.
        val nodes = compose.onAllNodesWithText("从加密文件恢复设备组", useUnmergedTree = true).fetchSemanticsNodes()
        val click = nodes.any { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
        val ancestorClick = nodes.any { node -> generateSequence(node.parent) { it.parent }.any {
            it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        } }
        return "stage=${panel.stage.name},panelBusy=${panel.busy},canResume=${panel.canResumeExternalRecovery}," +
            "cloudBusy=${coordinator.cloudRecovery?.state?.value?.busy == true},privacy=${panel.privacyRevision}," +
            "visible=${recovery.state.value.visible},windowFocus=$focus,decorAttached=$attached," +
            "nodeClick=$click,ancestorClick=$ancestorClick"
    }
    // Numeric geometry only; this reads no text, editable values, subtree dump, code or key.
    private fun checkboxGeometry(launched: ActivityScenario<LanSyncActivity>): String = try {
        var activityIme = "UNAVAILABLE"
        launched.onActivity { activity ->
            activity.window.decorView.rootWindowInsets?.let { insets ->
                activityIme = if (insets.isVisible(android.view.WindowInsets.Type.ime())) "VISIBLE" else "HIDDEN"
            }
        }
        val node = compose.onNodeWithContentDescription("已核对原设备组与新设备身份，并同意登记此设备").fetchSemanticsNode()
        fun rect(value: androidx.compose.ui.geometry.Rect) = "${value.left},${value.top},${value.right},${value.bottom}"
        val axis = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        val ancestors = generateSequence(node.parent) { it.parent }.take(6).mapIndexed { depth, parent ->
            val range = if (parent.config.contains(axis)) parent.config[axis] else null
            val scroll = range?.let { "${it.value()},${it.maxValue()},${it.reverseScrolling}" } ?: "none"
            "depth=$depth,root=${parent.isRoot},clippedRoot=${rect(parent.boundsInRoot)},vertical=$scroll"
        }.joinToString(";")
        // Activity insets are labelled explicitly; they do not prove the separate Dialog window's IME state.
        "activityIme=$activityIme,rootPos=${node.positionInRoot.x},${node.positionInRoot.y}," +
            "windowPos=${node.positionInWindow.x},${node.positionInWindow.y},size=${node.size.width},${node.size.height}," +
            "layoutPlaced=${node.layoutInfo.isPlaced},layoutAttached=${node.layoutInfo.isAttached}," +
            "clippedRoot=${rect(node.boundsInRoot)},clippedWindow=${rect(node.boundsInWindow)},ancestors[$ancestors]"
    } catch (failure: Throwable) { "UNAVAILABLE:${failure.javaClass.simpleName}" }

    @get:Rule val guardedCompose: TestRule = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                try { compose.apply(base, description).evaluate() }
                catch (failure: Throwable) { throw sanitized(failure) }
            }
        }
    }
    private class SafeFailure(message: String) : AssertionError(message)
    private class DiagnosticFailure(message: String) : AssertionError(message)
    private fun sanitized(failure: Throwable): AssertionError {
        // Keep the first safe snapshot: the outer Rule runs after method-finally closes the Activity.
        if (failure is SafeFailure || failure is DiagnosticFailure) return failure
        return SafeFailure("SYNTHETIC_FILE_RECOVERY_PHASE:$phase TYPE:${failure.javaClass.simpleName}" +
            (observedState?.invoke()?.let { " OBSERVED[$it,nodes=$inputNodeCount]" } ?: "") +
            " REOPEN_BEFORE[$reopenBefore] REOPEN_AFTER[$reopenAfter] VISIBLE_TRACE[${visibilityTrace()}]" +
            " CHECKBOX_GEOMETRY_BEFORE[$checkboxGeometryBefore] CHECKBOX_GEOMETRY_AFTER[$checkboxGeometryAfter]" +
            " BODY_PHYSICAL_SWIPES_COMPLETED[$bodyPhysicalSwipesCompleted]" +
            " CHECKBOX_GEOMETRY_AFTER_BODY_SWIPE[$checkboxGeometryAfterBodySwipe]").also {
            // Keep original locations; do not retain a cause/message that may contain the code or UI tree.
            it.stackTrace = failure.stackTrace
        }
    }

    @Test fun realFilePickerResultRestoresLatestConversationWithSourceAlreadyOffline() = runBlocking {
        phase = "guards-and-fresh-real-app"
        val app = guardedApp()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = app.lanSyncOwner
        val native = nativeRoot(app)
        check(!owner.hasGroupEvidence() && !Files.exists(native.resolve("identity.protected"), NOFOLLOW_LINKS))
        val oracleFile = app.cacheDir.toPath().resolve(ORACLE)
        check(!Files.exists(oracleFile, NOFOLLOW_LINKS))
        owner.pauseBackground().join()
        check(app.getSharedPreferences("lan-sync-background", Context.MODE_PRIVATE).getBoolean("user-paused", false))
        val source = Files.createTempDirectory(app.cacheDir.toPath(), "file-ui-source-").toRealPath()
        val sourceAlias = "synthetic-file-recovery-source-" + UUID.randomUUID()
        val protection = AndroidLanSyncKeyProtection(sourceAlias)
        var producer: LanSyncGroupCoordinator? = null
        var enrollment: LanSyncGroupEnrollment? = null
        var scenario: ActivityScenario<LanSyncActivity>? = null
        var failed: Throwable? = null
        var reopenObserver: kotlinx.coroutines.Job? = null
        try {
            phase = "durable-source-chat-card-document"
            val data = Files.createDirectory(source.resolve("data"))
            val state = Files.createDirectory(source.resolve("state"))
            val profile = Files.createDirectory(source.resolve("profile"))
            val sourceIdentity = LanSyncStateStore(state, protection::protect, protection::unprotect).identity()
            val document = data.resolve("documents/original.bin")
            Files.createDirectory(document.parent)
            val documentBytes = ByteArray(131_113) { ((it * 17 + 11) % 251).toByte() }
            Files.write(document, documentBytes, CREATE_NEW, WRITE)
            val storage = JsonFileStorage(data)
            val card = CharacterCard(CARD, "synthetic file recovery card",
                basicSetting = "synthetic original domain body; no edited Prompt",
                customDocuments = listOf(DocumentInfo("original-document", "original.bin", document.toString(),
                    "application/octet-stream", addedAt = 1)), createdAt = 1, updatedAt = 1)
            storage.saveEntity("character_cards", CARD, card, CharacterCard.serializer())
            val chats = ChatRepository(storage)
            chats.createSession(ChatSession(SESSION, CARD, "synthetic latest recoverable conversation",
                nextSourceTurnOrder = 2, nextTimelineTurn = 2, createdAt = 1, updatedAt = 4))
            messages().forEach { chats.addMessage(it) }
            val expectedSession = checkNotNull(chats.getSession(SESSION))
            val expectedMessages = chats.getMessagesReadOnly(SESSION)
            check(expectedMessages.size == 4 && expectedMessages.last().content == LAST)
            val sessionHash = sha(Json.encodeToString(ChatSession.serializer(), expectedSession).toByteArray())
            val messagesHash = messageHash(expectedMessages)
            val workspace = LanSyncWorkspace(data, state.resolve("workspace"), { Path.of(it) })
            val platform = object : LanSyncPlatform {
                private val mutex = Mutex()
                override suspend fun exclusive(work: suspend () -> Unit) = mutex.withLock { work() }
                override suspend fun reload(sessionIds: Set<String>) = Unit
            }
            val service = LanSyncGroupEnrollment(profile, workspace, platform, sourceIdentity,
                protection::protect, protection::unprotect, ::validateAndroidLanSyncResource)
            enrollment = service
            val draft = service.prepare(SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys,
                sha("synthetic explicit file UI founder admission".toByteArray())), SyncRootGroupCreateAdmission {})
            val code = draft.recoveryCode()
            service.activate(draft, LanSyncGroupRecoverySavedAdmission {})
            draft.close()
            val old = checkNotNull(service.runtimeAccess())
            val oldLedger = old.ledger().current()
            val noNearby = object : LanSyncNetwork {
                override fun addresses() = listOf("127.0.0.1")
                override fun accepts(address: InetAddress) = false
                override val discoveryEnabled = false
                override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                    error("SYNTHETIC_SOURCE_OFFLINE")
                override fun listening(fingerprint: String, port: Int) { throw BindException("SYNTHETIC_SOURCE_OFFLINE") }
            }
            phase = "actual-coordinator-file-export"
            val live = LanSyncGroupCoordinator({ Files.exists(profile.resolve("profile.protected")) },
                { service }, { error("SYNTHETIC_FOUNDER_NO_JOIN") }, noNearby, "synthetic offline source", initiallyPaused = true)
            producer = live
            live.open()
            await { !live.state.value.busy && live.state.value.stage == LanSyncGroupPanelStage.READY }
            val exporter = checkNotNull(live.fileExport)
            exporter.prepare()
            await(120_000) { !exporter.state.value.busy && (exporter.state.value.preparedBinding != null || exporter.state.value.error != null) }
            check(exporter.state.value.error == null)
            val originalPackage = source.resolve("source.cbgroup")
            exporter.write(checkNotNull(exporter.state.value.preparedBinding), writer = { emit ->
                Files.newOutputStream(originalPackage, CREATE_NEW, WRITE).use { emit(it) }
            }, readback = { Files.newInputStream(originalPackage) })
            await(120_000) { !exporter.state.value.busy }
            check(exporter.state.value.error == null && exporter.state.value.writeCompleted && exporter.state.value.readbackVerified)
            val sourceOperation = old.node.sourceOperations().single()
            val sourceAuthor = SyncAuthorJournal(profile.resolve("node/author"), old.trust, old.certificate,
                protection::protect, protection::unprotect)
            check(sourceAuthor.state().committedSequence == 1L)
            val version = checkNotNull(sourceAuthor.entry(sourceOperation)).version
            check(version.changes.keys == setOf(CARD_UNIT, CHAT_UNIT))
            val reference = version.reference()
            val selectedDir = app.filesDir.toPath().resolve("diagnostics/share/file-recovery-ui")
            check(!Files.exists(selectedDir, NOFOLLOW_LINKS))
            Files.createDirectories(selectedDir)
            val selected = selectedDir.resolve("synthetic.cbgroup")
            Files.copy(originalPackage, selected)
            val packageHash = hash(originalPackage)
            check(hash(selected) == packageHash)
            val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", selected.toFile())
            app.contentResolver.openInputStream(uri).use { checkNotNull(it); check(sha(it.readBytes()) == packageHash) }

            phase = "source-closed-and-moved-offline-before-ui"
            live.closeAndDrain(); producer = null
            service.close(); enrollment = null
            val sourceInventory = hashes(source)
            val offline = Files.move(source, source.resolveSibling(source.fileName.toString() + "-offline"))
            check(!Files.exists(source, NOFOLLOW_LINKS) && hashes(offline) == sourceInventory)
            check(!owner.hasGroupEvidence() && !Files.exists(native.resolve("identity.protected"), NOFOLLOW_LINKS))

            phase = "real-activity-and-open-document-result"
            val launched = ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java))
            scenario = launched
            val coordinator = owner.groupCoordinator
            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.EMPTY }
            click("从加密文件恢复设备组")
            selectAndRead(uri, code)
            val recovery = checkNotNull(coordinator.fileRecovery)
            observedState = {
                val value = recovery.state.value
                val panel = coordinator.state.value
                "panelStage=${panel.stage.name},panelBusy=${panel.busy},canResume=${panel.canResumeExternalRecovery}," +
                    "visible=${value.visible},busy=${value.busy},review=${value.review != null}," +
                    "complete=${value.complete},error=${value.error != null}," +
                    "lifecycle=${runCatching { launched.state.name }.getOrDefault("UNAVAILABLE")}," +
                    "sameOwner=${app.lanSyncOwner === owner},sameCoordinator=${owner.groupCoordinator === coordinator}," +
                    "sameRecovery=${coordinator.fileRecovery === recovery}"
            }
            await(120_000) { !recovery.state.value.busy && (recovery.state.value.review != null || recovery.state.value.error != null) }
            check(recovery.state.value.error == null)
            val reviewed = checkNotNull(recovery.state.value.review)
            check(reviewed.group == old.trust.group.value && reviewed.device != old.device)
            compose.onNodeWithText("登记此设备并恢复资料").performScrollTo().assertIsNotEnabled()

            phase = "review-recreate-clears-secrets-and-keeps-recipient"
            launched.recreate()
            phase = "review-recreate-await-resumed-and-hidden"
            await { launched.state == Lifecycle.State.RESUMED && !recovery.state.value.visible }
            phase = "review-recreate-await-current-panel-ready"
            // Recreation starts a real asynchronous open/load; rendered enabled semantics may be stale.
            await(120_000) {
                val panel = coordinator.state.value
                !panel.busy && (panel.stage == LanSyncGroupPanelStage.EMPTY || panel.stage == LanSyncGroupPanelStage.JOINING) &&
                    !recovery.state.value.visible
            }
            compose.waitForIdle()
            // Read-only bounded trace. StateFlow can conflate transitions: a false-only trace is not proof of no open.
            reopenObserver = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch(
                start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                recovery.state.collect { value -> synchronized(reopenVisibility) {
                    if (reopenVisibility.size < 8 && reopenVisibility.lastOrNull() != value.visible)
                        reopenVisibility += value.visible
                } }
            }
            reopenBefore = reopenSnapshot(coordinator, recovery, launched)
            click("从加密文件恢复设备组")
            reopenAfter = reopenSnapshot(coordinator, recovery, launched)
            phase = "review-reopen-await-visible-input"
            try {
                await { recovery.state.value.visible && recovery.state.value.review == null && !recovery.state.value.complete }
            } catch (failure: Throwable) {
                val first = sanitized(failure)
                var actionReturned: Boolean? = null
                var actionType = "none"
                var afterAction = "not-sampled"
                // Diagnostic UI action only. This never rescues the touch failure or enters native recovery.
                try {
                    compose.onNode(hasText("从加密文件恢复设备组") and hasClickAction())
                        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {
                            actionReturned = it()
                        }
                    afterAction = reopenSnapshot(coordinator, recovery, launched)
                } catch (diagnostic: Throwable) { actionType = diagnostic.javaClass.simpleName }
                throw DiagnosticFailure(first.message + " DIAGNOSTIC_ONLY_UI_ACTION[returned=$actionReturned," +
                    "type=$actionType,after=$afterAction,visibleTrace=${visibilityTrace()}] NATIVE_NOT_CONTINUED").also {
                    it.stackTrace = first.stackTrace
                }
            }
            reopenObserver?.cancel(); reopenObserver = null
            compose.waitUntil(30_000) {
                inputNodeCount = compose.onAllNodesWithText("读取并核对设备组").fetchSemanticsNodes().size
                inputNodeCount == 1
            }
            val cleared = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            check(cleared.isEmpty())
            compose.onNodeWithText("读取并核对设备组").performScrollTo().assertIsNotEnabled()
            phase = "second-file-read-await-fresh-review"
            selectAndRead(uri, code)
            await(120_000) { !recovery.state.value.busy && recovery.state.value.review != null }
            check(recovery.state.value.error == null)
            check(checkNotNull(recovery.state.value.review).device == reviewed.device)
            phase = "second-review-compose-and-scroll-settle"
            // Keep the existing readiness checks; observe clipping without changing admission.
            compose.waitForIdle()
            val admissionCheckbox = compose.onNodeWithContentDescription("已核对原设备组与新设备身份，并同意登记此设备")
            checkboxGeometryBefore = checkboxGeometry(launched)
            admissionCheckbox.performScrollTo()
            compose.waitForIdle()
            checkboxGeometryAfter = checkboxGeometry(launched)
            phase = "second-review-body-one-physical-swipe-up"
            // One additional real body gesture; it cannot replace the explicit checkbox admission.
            val bodyScroller = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            bodyScroller.assertIsDisplayed()
            bodyScroller.performTouchInput {
                check(visibleSize.width > 0 && visibleSize.height > 0)
                swipeUp(startY = visibleSize.height * 0.45f, endY = visibleSize.height * 0.15f, durationMillis = 450)
            }
            bodyPhysicalSwipesCompleted++
            compose.waitForIdle()
            checkboxGeometryAfterBodySwipe = checkboxGeometry(launched)
            phase = "second-review-checkbox-assert-off"
            admissionCheckbox.assertIsDisplayed().assertIsEnabled().assertIsOff()
            phase = "second-review-checkbox-physical-click"
            admissionCheckbox.performClick()
            phase = "second-review-checkbox-settle-and-assert-on"
            compose.waitForIdle()
            admissionCheckbox.assertIsOn()
            phase = "actual-ui-approval-and-keystore-native-import"
            click("登记此设备并恢复资料")
            await(180_000) { !recovery.state.value.busy && (recovery.state.value.complete || recovery.state.value.error != null) }
            check(recovery.state.value.error == null && recovery.state.value.complete && !recovery.state.value.pending)
            withTimeout(30_000) { recovery.drain() }
            check(coordinator.state.value.stage == LanSyncGroupPanelStage.READY)
            val access = checkNotNull(owner.groupJoining.runtimeAccess())
            check(access.device == reviewed.device && access.device != old.device)
            check(access.trust.group == old.trust.group && access.trust.genesis == old.trust.genesis &&
                access.trust.ownerCertificate == old.trust.ownerCertificate)
            val current = access.ledger().current()
            check(current.sequence == oldLedger.sequence + 1 && current.members.keys == oldLedger.members.keys + access.device)
            oldLedger.members.forEach { (id, member) -> check(current.members.getValue(id) == member) }
            check(current.members.getValue(access.device).active)
            check(current.members.getValue(access.device).certificate == access.certificate)
            // A paused app never cycles to create a Coordinator projection. Do not inspect/apply to fill it.
            check(coordinator.view.value == null)
            val observed = access.node.reconcileForPrefetch()
            check(checkNotNull(observed.history).state(reference.id) == SyncVersionState.ADMITTED)
            check(access.node.sourceOperations().isEmpty())
            val realProtection = AndroidLanSyncKeyProtection()
            val author = SyncAuthorJournal(native.resolve("group-profile/joining/node/author"), access.trust,
                access.certificate, realProtection::protect, realProtection::unprotect)
            check(author.state().committedSequence == 0L)
            assertRestored(app, sessionHash, messagesHash, sha(documentBytes))
            check(hashes(offline) == sourceInventory && hash(offline.resolve("source.cbgroup")) == packageHash)
            check(!Files.exists(access.work.resolve("cloud-connections"), NOFOLLOW_LINKS))
            phase = "remove-picker-copy-and-persist-only-public-cold-oracle"
            compose.onNodeWithText("关闭", useUnmergedTree = true).assertIsDisplayed().performClick()
            launched.close(); scenario = null
            Files.delete(selected); Files.delete(selectedDir)
            check(!Files.exists(selected, NOFOLLOW_LINKS))
            val expected = Properties().apply {
                setProperty("pid", Process.myPid().toString()); setProperty("device", access.device)
                setProperty("group", access.trust.group.value); setProperty("genesis", access.trust.genesis.toString())
                setProperty("rootHash", sha(access.trust.ownerCertificate.toByteArray()))
                setProperty("ledgerHash", sha(Json.encodeToString(SyncDeviceLedgerRecord.serializer(), current).toByteArray()))
                setProperty("sessionHash", sessionHash); setProperty("messagesHash", messagesHash)
                setProperty("documentHash", sha(documentBytes)); setProperty("referenceId", reference.id.value)
                setProperty("referenceHash", reference.bodyHash)
                setProperty("identityHash", hash(native.resolve("identity.protected")))
                setProperty("authorOriginHash", hash(native.resolve("group-profile/joining/node/author/origin.protected")))
                setProperty("nativeOriginHash", hash(native.resolve("group-profile/joining/node/native/origin.protected")))
                setProperty("nativeHeadHash", hash(native.resolve("group-profile/joining/node/native/head.protected")))
                setProperty("nativeInventoryHash", inventoryHash(hashes(native.resolve("group-profile/joining/node/native"))))
                setProperty("offlinePath", offline.toString()); setProperty("offlineInventoryHash", inventoryHash(sourceInventory))
                setProperty("sourceAbsentPath", source.toString()); setProperty("packageAbsentPath", selected.toString())
            }
            Files.newOutputStream(oracleFile, CREATE_NEW, WRITE).use { expected.store(it, "public hashes only; no code or private key") }
            check(!owner.groupCoordinator.state.value.managementUnlocked)
        } catch (failure: Throwable) { failed = failure; throw sanitized(failure) }
        finally {
            reopenObserver?.cancel()
            try { scenario?.close(); producer?.closeAndDrain(); enrollment?.close() }
            catch (cleanup: Throwable) { if (failed == null) throw sanitized(cleanup) }
            // Only this synthetic producer key. The actual receiver default key must survive phase two.
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(sourceAlias) } }
            catch (cleanup: Throwable) { if (failed == null) throw sanitized(cleanup) }
        }
    }

    @Test fun coldProcessReopensOriginalIdentityWithoutCodeSourceOrPackage() = runBlocking {
        phase = "cold-phase-guards"
        var scenario: ActivityScenario<LanSyncActivity>? = null
        var failed: Throwable? = null
        try {
            val app = guardedApp()
            val oracle = Properties().apply {
                Files.newInputStream(app.cacheDir.toPath().resolve(ORACLE)).use { load(it) }
            }
            fun expected(name: String) = checkNotNull(oracle.getProperty(name))
            check(Process.myPid().toString() != expected("pid"))
            val source = Path.of(expected("sourceAbsentPath"))
            val offline = Path.of(expected("offlinePath"))
            val selected = Path.of(expected("packageAbsentPath"))
            check(source.startsWith(app.cacheDir.toPath()) && offline.startsWith(app.cacheDir.toPath()))
            check(selected.startsWith(app.filesDir.toPath().resolve("diagnostics/share")))
            check(!Files.exists(source, NOFOLLOW_LINKS) && !Files.exists(selected, NOFOLLOW_LINKS))
            check(inventoryHash(hashes(offline)) == expected("offlineInventoryHash"))
            val owner = app.lanSyncOwner
            check(owner.hasGroupEvidence())
            check(app.getSharedPreferences("lan-sync-background", Context.MODE_PRIVATE).getBoolean("user-paused", false))
            phase = "cold-actual-activity-normal-startup-no-recovery-code"
            val launched = ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java))
            scenario = launched
            val coordinator = owner.groupCoordinator
            await(120_000) { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
            check(coordinator.state.value.error == null && !coordinator.state.value.managementUnlocked)
            val access = checkNotNull(owner.groupJoining.runtimeAccess())
            check(access.device == expected("device") && access.trust.group.value == expected("group"))
            check(access.trust.genesis.toString() == expected("genesis"))
            check(sha(access.trust.ownerCertificate.toByteArray()) == expected("rootHash"))
            check(sha(Json.encodeToString(SyncDeviceLedgerRecord.serializer(), access.ledger().current()).toByteArray()) == expected("ledgerHash"))
            val reference = SyncVersionRef(SyncStoreId.parse(expected("referenceId")), expected("referenceHash"))
            check(coordinator.view.value == null)
            val observed = access.node.reconcileForPrefetch()
            check(checkNotNull(observed.history).state(reference.id) == SyncVersionState.ADMITTED)
            check(access.node.sourceOperations().isEmpty())
            val native = nativeRoot(app)
            val protection = AndroidLanSyncKeyProtection()
            val author = SyncAuthorJournal(native.resolve("group-profile/joining/node/author"), access.trust,
                access.certificate, protection::protect, protection::unprotect)
            check(author.state().committedSequence == 0L)
            check(hash(native.resolve("identity.protected")) == expected("identityHash"))
            check(hash(native.resolve("group-profile/joining/node/author/origin.protected")) == expected("authorOriginHash"))
            check(hash(native.resolve("group-profile/joining/node/native/origin.protected")) == expected("nativeOriginHash"))
            check(hash(native.resolve("group-profile/joining/node/native/head.protected")) == expected("nativeHeadHash"))
            check(inventoryHash(hashes(native.resolve("group-profile/joining/node/native"))) == expected("nativeInventoryHash"))
            assertRestored(app, expected("sessionHash"), expected("messagesHash"), expected("documentHash"))
            check(!Files.exists(access.work.resolve("cloud-connections"), NOFOLLOW_LINKS))
            check(inventoryHash(hashes(offline)) == expected("offlineInventoryHash"))
            check(!Files.exists(source, NOFOLLOW_LINKS) && !Files.exists(selected, NOFOLLOW_LINKS))
            phase = "cold-proof-complete"
        } catch (failure: Throwable) { failed = failure; throw sanitized(failure) }
        finally {
            try { scenario?.close() }
            catch (cleanup: Throwable) { if (failed == null) throw sanitized(cleanup) }
        }
    }

    private suspend fun guardedApp(): ChatBarApp {
        check(InstrumentationRegistry.getArguments().getString("lanSyncFileRecoveryUiFixture") == "true")
        check(Build.VERSION.SDK_INT == 35 && BuildConfig.BUILD_TYPE == "lanPreview" &&
            BuildConfig.APPLICATION_ID == NEXT)
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName == NEXT &&
            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode == 85L)
        await(60_000) { check(!app.lanSyncRecoveryFailed); app.lanSyncStartupReady }
        return app
    }

    private fun click(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        if (text != "从加密文件恢复设备组" && text != "接续加密文件恢复") node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun selectAndRead(uri: android.net.Uri, code: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addDataType("*/*") }
        val actual = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            .createIntent(instrumentation.targetContext, arrayOf("*/*"))
        check(filter.match(instrumentation.targetContext.contentResolver, actual, true, "CCB_FILE_CONTROL") >= 0)
        val monitor = instrumentation.addMonitor(filter,
            ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        try {
            click("选择加密同步文件")
            compose.waitForIdle()
            check(monitor.hits == 1)
        } finally { instrumentation.removeMonitor(monitor) }
        // Selection callback deliberately clears secrets. Type only in the modal's single editable field.
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true)
            .performScrollTo().performTextInput(code)
        click("读取并核对设备组")
    }

    private suspend fun assertRestored(app: ChatBarApp, sessionHash: String, messagesHash: String, documentHash: String) {
        val card = checkNotNull(app.characterRepository.getById(CARD))
        check(card.id == CARD && card.name == "synthetic file recovery card" &&
            card.basicSetting == "synthetic original domain body; no edited Prompt")
        val doc = card.customDocuments.single()
        check(doc.id == "original-document" && doc.fileName == "original.bin")
        val file = Path.of(doc.filePath).toAbsolutePath().normalize()
        check(file.startsWith(app.filesDir.canonicalFile.toPath()) && Files.isRegularFile(file, NOFOLLOW_LINKS))
        check(Files.size(file) == 131_113L && hash(file) == documentHash)
        val session = checkNotNull(app.chatRepository.getSession(SESSION))
        val actual = app.chatRepository.getMessagesReadOnly(SESSION)
        check(sha(Json.encodeToString(ChatSession.serializer(), session).toByteArray()) == sessionHash)
        check(messageHash(actual) == messagesHash)
        check(session.id == SESSION && session.characterCardId == CARD && session.lanSyncOrigin == null)
        check(session.lastMessagePreview == LAST && session.lastMessageRole == MessageRole.ASSISTANT)
        check(actual.map { it.id } == listOf("u1", "a1", "u2", "a2") && actual.map { it.id }.distinct().size == 4)
        check(actual.all { it.sessionId == SESSION })
        check(actual.map { it.role } == listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT))
        check(actual.map { it.sourceTurnOrder } == listOf(0L, 0L, 1L, 1L))
        check(actual[0].sourceTurnId == actual[1].sourceTurnId && actual[2].sourceTurnId == actual[3].sourceTurnId &&
            actual[0].sourceTurnId != actual[2].sourceTurnId)
        check(actual.zipWithNext().all { (a, b) -> a.orderKey < b.orderKey })
        check(actual[1].generatedFromMessageId == "u1" && actual[3].generatedFromMessageId == "u2")
        check(actual.last().content == LAST && actual.last().displayContent == LAST &&
            actual.last().alternativeVersionIds == listOf("a2-v0", "a2-v1") &&
            actual.last().currentAlternativeIndex == 1 && actual.last().currentAlternativeVersionId == "a2-v1")
        check(app.jsonFileStorage.loadAll("chat_sessions", ChatSession.serializer()).count { it.id == SESSION } == 1)
        check(app.jsonFileStorage.loadAll("chat_messages", ChatMessage.serializer()).filter { it.sessionId == SESSION }.size == 4)
    }

    private fun messages() = listOf(
        ChatMessage("u1", SESSION, MessageRole.USER, "synthetic first user turn",
            currentAlternativeVersionId = "u1-v0", sourceTurnId = "turn-0", sourceTurnOrder = 0,
            timelineTurn = 0, createdAt = 1, updatedAt = 1, orderKey = 1_000_000),
        ChatMessage("a1", SESSION, MessageRole.ASSISTANT, "synthetic first active reply",
            alternatives = listOf("synthetic first discarded reply", "synthetic first active reply"),
            alternativeVersionIds = listOf("a1-v0", "a1-v1"), currentAlternativeIndex = 1,
            currentAlternativeVersionId = "a1-v1", generatedFromMessageId = "u1",
            sourceTurnId = "turn-0", sourceTurnOrder = 0, timelineTurn = 0,
            createdAt = 2, updatedAt = 2, orderKey = 2_000_000),
        ChatMessage("u2", SESSION, MessageRole.USER, "synthetic final user question before leaving",
            currentAlternativeVersionId = "u2-v0", sourceTurnId = "turn-1", sourceTurnOrder = 1,
            timelineTurn = 1, createdAt = 3, updatedAt = 3, orderKey = 3_000_000),
        ChatMessage("a2", SESSION, MessageRole.ASSISTANT, LAST,
            alternatives = listOf("synthetic last discarded candidate", LAST),
            alternativeVersionIds = listOf("a2-v0", "a2-v1"), currentAlternativeIndex = 1,
            currentAlternativeVersionId = "a2-v1", generatedFromMessageId = "u2",
            sourceTurnId = "turn-1", sourceTurnOrder = 1, timelineTurn = 1,
            createdAt = 4, updatedAt = 4, orderKey = 4_000_000))

    private suspend fun await(timeout: Long = 30_000, condition: () -> Boolean) = withTimeout(timeout) {
        while (!condition()) delay(50)
    }
    private fun nativeRoot(app: ChatBarApp) = app.noBackupFilesDir.canonicalFile.toPath().resolve("lan-sync-v1")
    private fun messageHash(values: List<ChatMessage>) = sha(values.joinToString("\n") {
        Json.encodeToString(ChatMessage.serializer(), it)
    }.toByteArray())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(path: Path): String {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun hashes(root: Path): Map<String, String> = Files.walk(root).use { files ->
        val result = sortedMapOf<String, String>()
        files.forEach { path ->
            check(!Files.isSymbolicLink(path))
            if (Files.isRegularFile(path, NOFOLLOW_LINKS)) result[root.relativize(path).toString()] = hash(path)
        }
        result
    }
    private fun inventoryHash(values: Map<String, String>) = sha(values.toSortedMap().entries.joinToString("\n") {
        it.key + "=" + it.value
    }.toByteArray())
    private companion object {
        const val NEXT = "com.example.chatbar.lansyncnext"
        const val ORACLE = "synthetic-file-recovery-public-cold-oracle.properties"
        const val CARD = "original"
        const val SESSION = "latest-conversation"
        const val CARD_UNIT = "character_cards/original"
        const val CHAT_UNIT = "conversation/latest-conversation"
        const val LAST = "Synthetic last assistant reply: the offline trip starts after this exact message."
    }
}
