package com.example.chatbar.lanSync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import com.example.chatbar.domain.syncStore.webdav.WebDavCloudClient
import java.net.BindException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.net.ssl.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** DRAFT: zero native execution. Production Activity/factory/client and default-save choice only. */
@OptIn(ExperimentalTestApi::class)
class LanSyncGroupCloudRecoveryUiControlTest {
    private val compose = createEmptyComposeRule()
    private var phase = "rule"
    private var formReadAttempts = 0
    private var formBodySwipesCompleted = 0
    private var formReadGeometryBefore = "not-sampled"
    private var formReadGeometryAfter = "not-sampled"
    private var consentBodySwipesCompleted = 0
    private var consentGeometryBefore = "not-sampled"
    private var consentGeometryAfter = "not-sampled"
    private fun consentGeometry(): String = try {
        val node = compose.onNode(hasContentDescription("已核对原设备组和新设备身份，并同意登记此设备") and isToggleable()).fetchSemanticsNode()
        val rect = node.boundsInRoot
        "root=${rect.left},${rect.top},${rect.right},${rect.bottom},size=${node.size.width},${node.size.height},placed=${node.layoutInfo.isPlaced},attached=${node.layoutInfo.isAttached}"
    } catch (failure: Throwable) { "UNAVAILABLE:${failure.javaClass.simpleName}" }
    // Dialog-node coordinates only; no text, input value, UI tree or Activity-IME inference.
    private fun readButtonGeometry(): String = try {
        val node = clickable("读取并核对设备组").fetchSemanticsNode()
        fun rect(value: androidx.compose.ui.geometry.Rect) = "${value.left},${value.top},${value.right},${value.bottom}"
        "dialogNodeRoot=${rect(node.boundsInRoot)},dialogNodeWindow=${rect(node.boundsInWindow)}," +
            "size=${node.size.width},${node.size.height},placed=${node.layoutInfo.isPlaced},attached=${node.layoutInfo.isAttached}"
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
    private fun sanitized(failure: Throwable): AssertionError {
        if (failure is SafeFailure) return failure
        return SafeFailure("SYNTHETIC_CLOUD_RECOVERY_PHASE:$phase TYPE:${failure.javaClass.simpleName}" +
            " FORM_READ_ATTEMPTS[$formReadAttempts] FORM_BODY_SWIPES_COMPLETED[$formBodySwipesCompleted]" +
            " FORM_READ_GEOMETRY_BEFORE[$formReadGeometryBefore] FORM_READ_GEOMETRY_AFTER[$formReadGeometryAfter]" +
            " CONSENT_BODY_SWIPES_COMPLETED[$consentBodySwipesCompleted] CONSENT_GEOMETRY_BEFORE[$consentGeometryBefore] CONSENT_GEOMETRY_AFTER[$consentGeometryAfter]").also {
            it.stackTrace = failure.stackTrace // never original message, cause, UI tree or credential
        }
    }

    @Test fun realCloudRecoveryUiRestoresOfflineLatestConversationAndSavesKeystoreConnection() = runBlocking {
        phase = "guards-and-fresh-app"
        val app = guardedApp()
        val owner = app.lanSyncOwner
        val native = nativeRoot(app)
        check(!owner.hasGroupEvidence() && !Files.exists(native.resolve("identity.protected"), NOFOLLOW_LINKS))
        check(!app.getSharedPreferences("lan-sync-background", Context.MODE_PRIVATE).getBoolean("user-paused", false))
        val input = app.cacheDir.toPath().resolve(INPUT)
        val config = Properties().apply { Files.newInputStream(input).use { load(it) } }
        check(config.stringPropertyNames() == setOf("format", "port", "username", "password", "certificate"))
        check(config.getProperty("format") == "1" && config.getProperty("username") == USER)
        val port = config.getProperty("port").toInt().also { check(it in 1024..65535) }
        val endpoint = "https://10.0.2.2:$port/"
        val pass = config.getProperty("password").also { check(it.matches(Regex("[0-9a-f]{48}"))) }
        val cert = Base64.getDecoder().decode(config.getProperty("certificate"))
        val certFile = app.cacheDir.toPath().resolve(CERT)
        Files.write(certFile, cert, CREATE_NEW, WRITE)
        Files.delete(input); config.clear()
        var trust: AutoCloseable? = null
        val source = Files.createTempDirectory(app.cacheDir.toPath(), "cloud-ui-source-").toRealPath()
        val sourceAlias = "synthetic-cloud-recovery-source-" + UUID.randomUUID()
        val protection = AndroidLanSyncKeyProtection(sourceAlias)
        var producer: LanSyncGroupCoordinator? = null
        var enrollment: LanSyncGroupEnrollment? = null
        var scenario: ActivityScenario<LanSyncActivity>? = null
        var failed: Throwable? = null
        try {
            phase = "default-client-positive-and-hostname-negative-tls-preflight"
            trust = CloudUiTestTrust.install(cert)
            // This is the public production client, not an injected recovery client.
            tlsPreflight(endpoint, pass, cert)
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
            val card = CharacterCard(CARD, "synthetic cloud recovery card",
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

            phase = "ordinary-source-admission-and-explicitly-enabled-cloud-route"
            val password = pass.toByteArray(Charsets.UTF_8)
            val sourceRoute = try { old.cloudRoutes().add(LanSyncGroupCloudDraft("synthetic source cloud", endpoint,
                USER, password, enabled = true)) } finally { password.fill(0) }
            val live = LanSyncGroupCoordinator({ Files.exists(profile.resolve("profile.protected")) },
                { service }, { error("SYNTHETIC_FOUNDER_NO_JOIN") }, noNearby, "synthetic offline source",
                sourceChanges = storage.committedRevisionFor(LanSyncScope.schemas.keys))
            producer = live
            live.open() // ordinary local publisher and ordinary CloudRuntime, no test network client
            await(180_000) { !live.state.value.busy && live.state.value.local.lastSuccessAt != null &&
                live.cloudSettings.value.progress[sourceRoute]?.selectionObserved == true }
            check(live.state.value.stage == LanSyncGroupPanelStage.READY && live.state.value.error == null)
            live.pauseAndDrain()
            val sourceOperation = old.node.sourceOperations().single()
            val sourceAuthor = SyncAuthorJournal(profile.resolve("node/author"), old.trust, old.certificate,
                protection::protect, protection::unprotect)
            check(sourceAuthor.state().committedSequence == 1L)
            val version = checkNotNull(sourceAuthor.entry(sourceOperation)).version
            check(version.changes.keys == setOf(CARD_UNIT, CHAT_UNIT))
            val reference = version.reference()
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, sourceRoute)
            val publication = old.targetPublication(old.work.resolve("cloud-runtime/${sourceRoute.value}/target"), target, prepare = false)
            val receipt = checkNotNull(publication.cloudReceipt())
            check(receipt.target == target && receipt.checkpoint == oldLedger.reference())
            check(receipt.objects.any { it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION &&
                it.objectRequest.slot == reference.id.value })
            check(receipt.objects.any { it.objectRequest.role == LanSyncGroupTargetObjectRole.RESOURCE })
            check(receipt.objects.all { it.evidence.readback is LanSyncGroupCloudTargetObservation.Chunks })
            phase = "source-closed-and-moved-offline-before-ui"
            live.closeAndDrain(); producer = null
            service.close(); enrollment = null
            val sourceInventory = hashes(source)
            val offline = Files.move(source, source.resolveSibling(source.fileName.toString() + "-offline"))
            check(!Files.exists(source, NOFOLLOW_LINKS) && hashes(offline) == sourceInventory)
            check(!owner.hasGroupEvidence())

            phase = "real-activity-default-factory-cloud-ui-prepare"
            val launched = ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java))
            scenario = launched
            val coordinator = owner.groupCoordinator
            awaitPanel(coordinator)
            click("从网盘恢复设备组")
            val recovery = checkNotNull(coordinator.cloudRecovery)
            enterAndRead(endpoint, pass, code)
            phase = "first-cloud-review-await"
            await(180_000) { !recovery.state.value.busy && (recovery.state.value.review != null || recovery.state.value.error != null) }
            check(recovery.state.value.error == null)
            val reviewed = checkNotNull(recovery.state.value.review)
            check(reviewed.group == old.trust.group.value && reviewed.device != old.device)
            clickable("登记此设备并恢复资料").performScrollTo().assertIsNotEnabled()

            phase = "real-recreate-clears-input-and-keeps-recipient"
            launched.recreate()
            await { launched.state == Lifecycle.State.RESUMED && !recovery.state.value.visible }
            awaitPanel(coordinator) // current raw panel !busy + legal stage, before Compose idle/touch
            compose.waitForIdle()
            click("从网盘恢复设备组")
            await { recovery.state.value.visible && recovery.state.value.review == null && !recovery.state.value.complete }
            compose.waitUntil(30_000) { fields().fetchSemanticsNodes().size == 4 }
            fields().fetchSemanticsNodes().forEach {
                check(it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty())
            }
            clickable("读取并核对设备组").performScrollTo().assertIsNotEnabled()
            enterAndRead(endpoint, pass, code)
            phase = "second-cloud-review-await"
            await(180_000) { !recovery.state.value.busy && (recovery.state.value.review != null || recovery.state.value.error != null) }
            check(recovery.state.value.error == null && checkNotNull(recovery.state.value.review).device == reviewed.device)
            compose.waitForIdle() // settle review-binding LaunchedEffect(clearSecrets) before consent
            val save = compose.onNode(hasContentDescription("恢复后保存此连接并继续自动同步") and isToggleable())
            save.performScrollTo(); compose.waitForIdle(); save.assertIsOn()
            val consent = compose.onNode(hasContentDescription("已核对原设备组和新设备身份，并同意登记此设备") and isToggleable())
            phase = "second-cloud-review-consent-one-physical-body-swipe"
            consent.performScrollTo(); compose.waitForIdle()
            consentGeometryBefore = consentGeometry()
            val reviewBody = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()) and
                hasAnyDescendant(hasContentDescription("已核对原设备组和新设备身份，并同意登记此设备") and isToggleable()))
            reviewBody.assertIsDisplayed()
            reviewBody.performTouchInput {
                check(visibleSize.width > 0 && visibleSize.height > 0)
                swipeUp(startY = visibleSize.height * 0.45f, endY = visibleSize.height * 0.15f, durationMillis = 450)
            }
            consentBodySwipesCompleted++
            check(consentBodySwipesCompleted == 1)
            compose.waitForIdle()
            consentGeometryAfter = consentGeometry()
            phase = "second-cloud-review-consent-displayed-enabled-off"
            consent.assertIsDisplayed().assertIsEnabled().assertIsOff()
            phase = "second-cloud-review-consent-physical-click"
            consent.performClick() // one real physical click, never direct owner/action invocation
            phase = "second-cloud-review-consent-await-on"
            compose.waitUntil(30_000) {
                consent.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] ==
                    androidx.compose.ui.state.ToggleableState.On
            }
            consent.assertIsOn()
            phase = "actual-ui-approval-and-keystore-native-restore"
            click("登记此设备并恢复资料")
            await(180_000) { !recovery.state.value.busy && (recovery.state.value.complete || recovery.state.value.error != null) }
            check(recovery.state.value.error == null && recovery.state.value.complete && recovery.state.value.connectionEnabled == true)
            withTimeout(30_000) { recovery.drain() }
            await(120_000) { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
            val access = checkNotNull(owner.groupJoining.runtimeAccess())
            check(access.device == reviewed.device && access.device != old.device)
            check(access.trust.group == old.trust.group && access.trust.genesis == old.trust.genesis &&
                access.trust.ownerCertificate == old.trust.ownerCertificate)
            val current = access.ledger().current()
            check(current.sequence == oldLedger.sequence + 1 && current.members.keys == oldLedger.members.keys + access.device)
            oldLedger.members.forEach { (id, member) -> check(current.members.getValue(id) == member) }
            check(current.members.getValue(access.device).active && current.members.getValue(access.device).certificate == access.certificate)
            check(checkNotNull(access.node.reconcileForPrefetch().history).state(reference.id) == SyncVersionState.ADMITTED)
            assertAuthorZero(app, access)
            assertRestored(app, sessionHash, messagesHash, sha(documentBytes))
            val row = access.cloudRoutes().rows().single()
            check(row.enabled && row.endpoint == endpoint && row.username == USER)
            val routeFile = access.work.resolve("cloud-connections/cloud-routes.protected")
            check(Files.isRegularFile(routeFile, NOFOLLOW_LINKS))
            check(!Files.readAllBytes(routeFile).toString(Charsets.ISO_8859_1).contains(pass))
            check(hashes(offline) == sourceInventory && !Files.exists(source, NOFOLLOW_LINKS))
            click("关闭")
            launched.close(); scenario = null
            owner.closeAndDrain() // cleanup, no sticky pause; next process gets a fresh real owner
            val oracle = Properties().apply {
                setProperty("pid", Process.myPid().toString()); setProperty("device", access.device)
                setProperty("group", access.trust.group.value); setProperty("genesis", access.trust.genesis.toString())
                setProperty("rootHash", sha(access.trust.ownerCertificate.toByteArray()))
                setProperty("ledgerHash", sha(Json.encodeToString(SyncDeviceLedgerRecord.serializer(), current).toByteArray()))
                setProperty("sessionHash", sessionHash); setProperty("messagesHash", messagesHash); setProperty("documentHash", sha(documentBytes))
                setProperty("referenceId", reference.id.value); setProperty("referenceHash", reference.bodyHash)
                setProperty("identityHash", hash(native.resolve("identity.protected")))
                setProperty("authorOriginHash", hash(native.resolve("group-profile/joining/node/author/origin.protected")))
                setProperty("nativeOriginHash", hash(native.resolve("group-profile/joining/node/native/origin.protected")))
                setProperty("nativeHeadHash", hash(native.resolve("group-profile/joining/node/native/head.protected")))
                setProperty("routeId", row.id.value); setProperty("routeHash", hash(routeFile)); setProperty("endpoint", endpoint)
                setProperty("offlinePath", offline.toString()); setProperty("offlineInventoryHash", inventoryHash(sourceInventory))
                setProperty("sourceAbsentPath", source.toString()); setProperty("certificateHash", sha(cert))
            }
            Files.newOutputStream(app.cacheDir.toPath().resolve(ORACLE), CREATE_NEW, WRITE).use { oracle.store(it, "public hashes only") }
            phase = "phase-one-complete"
        } catch (failure: Throwable) { failed = failure; throw sanitized(failure) }
        finally {
            try { scenario?.close(); producer?.closeAndDrain(); enrollment?.close(); owner.closeAndDrain() }
            catch (cleanup: Throwable) { if (failed == null) throw sanitized(cleanup) }
            finally {
                try { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(sourceAlias) } }
                finally { trust?.close() } // never remove default receiver key
            }
        }
    }

    @Test fun coldProcessReopensSavedRouteAndOrdinaryAndroidHooksUploadNewRevision() = runBlocking {
        phase = "cold-guards-no-recovery-input"
        var scenario: ActivityScenario<LanSyncActivity>? = null
        var trust: AutoCloseable? = null
        var app: ChatBarApp? = null
        var failed: Throwable? = null
        try {
            val actual = guardedApp(); app = actual
            check(!Files.exists(actual.cacheDir.toPath().resolve(INPUT), NOFOLLOW_LINKS))
            val oracle = Properties().apply { Files.newInputStream(actual.cacheDir.toPath().resolve(ORACLE)).use { load(it) } }
            fun expected(name: String) = checkNotNull(oracle.getProperty(name))
            check(Process.myPid().toString() != expected("pid"))
            val cert = Files.readAllBytes(actual.cacheDir.toPath().resolve(CERT))
            check(sha(cert) == expected("certificateHash")); trust = CloudUiTestTrust.install(cert)
            val source = Path.of(expected("sourceAbsentPath")); val offline = Path.of(expected("offlinePath"))
            check(source.isAbsolute && offline.isAbsolute)
            check(Files.isSameFile(source.parent, actual.cacheDir.toPath()) && Files.isSameFile(offline.parent, actual.cacheDir.toPath()))
            check(!Files.exists(source, NOFOLLOW_LINKS) && inventoryHash(hashes(offline)) == expected("offlineInventoryHash"))
            val owner = actual.lanSyncOwner
            check(owner.hasGroupEvidence() && !actual.getSharedPreferences("lan-sync-background", Context.MODE_PRIVATE).getBoolean("user-paused", false))
            phase = "cold-normal-activity-startup-keystore-and-route"
            val launched = ActivityScenario.launch<LanSyncActivity>(Intent(actual, LanSyncActivity::class.java)); scenario = launched
            val coordinator = owner.groupCoordinator
            await(120_000) { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
            check(coordinator.state.value.error == null && !coordinator.state.value.managementUnlocked)
            val access = checkNotNull(owner.groupJoining.runtimeAccess())
            check(access.device == expected("device") && access.trust.group.value == expected("group") && access.trust.genesis.toString() == expected("genesis"))
            check(sha(access.trust.ownerCertificate.toByteArray()) == expected("rootHash"))
            check(sha(Json.encodeToString(SyncDeviceLedgerRecord.serializer(), access.ledger().current()).toByteArray()) == expected("ledgerHash"))
            val native = nativeRoot(actual)
            for ((name, path) in listOf("identityHash" to "identity.protected", "authorOriginHash" to "group-profile/joining/node/author/origin.protected",
                "nativeOriginHash" to "group-profile/joining/node/native/origin.protected", "nativeHeadHash" to "group-profile/joining/node/native/head.protected"))
                check(hash(native.resolve(path)) == expected(name))
            assertAuthorZero(actual, access)
            val reference = SyncVersionRef(SyncStoreId.parse(expected("referenceId")), expected("referenceHash"))
            check(checkNotNull(access.node.reconcileForPrefetch().history).state(reference.id) == SyncVersionState.ADMITTED)
            assertRestored(actual, expected("sessionHash"), expected("messagesHash"), expected("documentHash"))
            val row = access.cloudRoutes().rows().single()
            check(row.enabled && row.id.value == expected("routeId") && row.endpoint == expected("endpoint") && row.username == USER)
            check(hash(access.work.resolve("cloud-connections/cloud-routes.protected")) == expected("routeHash"))
            await(120_000) { owner.backgroundState.value.phase == AndroidLanSyncBackgroundPhase.RUNNING }
            phase = "ordinary-android-source-change-and-saved-route-auto-upload"
            // Normal committed app data change; no Sync button, Runtime.run or node.upload call.
            val card = checkNotNull(actual.characterRepository.getById(CARD))
            actual.jsonFileStorage.saveEntity("character_cards", CARD, card.copy(name = "synthetic cold ordinary revision", updatedAt = 5), CharacterCard.serializer())
            val protection = AndroidLanSyncKeyProtection()
            val author = SyncAuthorJournal(native.resolve("group-profile/joining/node/author"), access.trust, access.certificate,
                protection::protect, protection::unprotect)
            await(180_000) { author.state().committedSequence == 1L }
            val operation = access.node.sourceOperations().single()
            val newReference = checkNotNull(author.entry(operation)).version.reference()
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, row.id)
            await(180_000) {
                access.targetPublication(access.work.resolve("cloud-runtime/${row.id.value}/target"), target, prepare = false)
                    .cloudReceipt()?.objects?.any { it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION && it.objectRequest.slot == newReference.id.value } == true
            }
            val newReceipt = checkNotNull(access.targetPublication(access.work.resolve("cloud-runtime/${row.id.value}/target"), target, prepare = false).cloudReceipt())
            check(newReceipt.target == target && newReceipt.objects.all { it.evidence.readback is LanSyncGroupCloudTargetObservation.Chunks })
            check(newReference != reference && author.state().committedSequence == 1L)
            check(messageHash(actual.chatRepository.getMessagesReadOnly(SESSION)) == expected("messagesHash"))
            check(!Files.exists(source, NOFOLLOW_LINKS) && inventoryHash(hashes(offline)) == expected("offlineInventoryHash"))
            // A target readback receipt proves encrypted cloud upload, not a second peer's delivery.
            phase = "cold-saved-route-and-new-upload-complete"
        } catch (failure: Throwable) { failed = failure; throw sanitized(failure) }
        finally {
            try { scenario?.close(); app?.lanSyncOwner?.closeAndDrain() }
            catch (cleanup: Throwable) { if (failed == null) throw sanitized(cleanup) }
            finally { trust?.close() }
        }
    }

    private suspend fun guardedApp(): ChatBarApp {
        check(InstrumentationRegistry.getArguments().getString("lanSyncCloudRecoveryUiFixture") == "true")
        check(Build.VERSION.SDK_INT == 35 && BuildConfig.BUILD_TYPE == "lanPreview" && BuildConfig.APPLICATION_ID == NEXT)
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName == NEXT && app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode == 85L)
        await(60_000) { check(!app.lanSyncRecoveryFailed); app.lanSyncStartupReady }
        return app
    }
    private suspend fun awaitPanel(coordinator: LanSyncGroupCoordinator) {
        await(120_000) { val panel = coordinator.state.value
            !panel.busy && panel.stage in setOf(LanSyncGroupPanelStage.EMPTY, LanSyncGroupPanelStage.JOINING) }
        compose.waitForIdle()
    }
    private fun clickable(text: String) = compose.onNode(hasText(text) and hasClickAction(), useUnmergedTree = false)
    private fun click(text: String) {
        val node = clickable(text)
        if (text !in setOf("从网盘恢复设备组", "接续网盘恢复与连接设置", "关闭")) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun fields() = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true)
    private fun enterAndRead(endpoint: String, pass: String, code: String) {
        compose.waitUntil(30_000) { fields().fetchSemanticsNodes().size == 4 }
        listOf(endpoint, USER, pass, code).forEachIndexed { index, text -> fields()[index].performScrollTo().performTextReplacement(text) }
        formReadAttempts++
        check(formReadAttempts in 1..2) // Exactly the original first read and post-recreate read.
        phase = "cloud-form-read-$formReadAttempts-scroll-and-one-physical-body-swipe"
        val read = clickable("读取并核对设备组")
        read.performScrollTo()
        compose.waitForIdle()
        formReadGeometryBefore = readButtonGeometry()
        // Only the dialog body containing the read button, not an editable field's own scroller.
        val body = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()) and
            hasAnyDescendant(hasText("读取并核对设备组") and hasClickAction()))
        body.assertIsDisplayed()
        body.performTouchInput {
            check(visibleSize.width > 0 && visibleSize.height > 0)
            swipeUp(startY = visibleSize.height * 0.45f, endY = visibleSize.height * 0.15f, durationMillis = 450)
        }
        formBodySwipesCompleted++
        check(formBodySwipesCompleted == formReadAttempts)
        compose.waitForIdle()
        formReadGeometryAfter = readButtonGeometry()
        phase = "cloud-form-read-$formReadAttempts-displayed-enabled-physical-click"
        // Do not call click(): a second semantic scroll could undo the actual body gesture.
        read.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private suspend fun assertAuthorZero(app: ChatBarApp, access: LanSyncGroupRuntimeAccess) {
        val p = AndroidLanSyncKeyProtection()
        val author = SyncAuthorJournal(nativeRoot(app).resolve("group-profile/joining/node/author"), access.trust,
            access.certificate, p::protect, p::unprotect)
        check(author.state().committedSequence == 0L && access.node.sourceOperations().isEmpty())
    }
    private fun tlsPreflight(endpoint: String, pass: String, cert: ByteArray) {
        fun close(client: OkHttpClient) { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        val client = WebDavCloudClient.create()
        try {
            val request = Request.Builder().url(endpoint).method("PROPFIND", null)
                .header("Depth", "0").header("Authorization", Credentials.basic(USER, pass, Charsets.UTF_8)).build()
            client.newCall(request).execute().use {
                check(it.code == 207 && checkNotNull(it.handshake).peerCertificates.first().encoded.contentEquals(cert))
            }
            // Only this negative probe maps a deliberately wrong hostname to the real server.
            // Default TLS/hostname verifier is retained; no interceptor/fake transport is used.
            val negative = client.newBuilder().dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    check(hostname == "wrong.invalid")
                    return listOf(InetAddress.getByName("10.0.2.2"))
                }
            }).build()
            try {
                val bad = request.newBuilder().url(endpoint.replace("10.0.2.2", "wrong.invalid")).build()
                var rejected = false
                try { negative.newCall(bad).execute().use { } } catch (_: SSLPeerUnverifiedException) { rejected = true }
                check(rejected)
            } finally { close(negative) }
        } finally { close(client) }
        // Remove only our test provider while no business cloud work exists, then prove the
        // unique private certificate is refused by the real default client trust source.
        CloudUiTestTrust.withPlatformTrust {
            val untrusted = WebDavCloudClient.create()
            try {
                var rejected = false
                try { untrusted.newCall(Request.Builder().url(endpoint).build()).execute().use { } }
                catch (_: SSLHandshakeException) { rejected = true }
                check(rejected)
            } finally { close(untrusted) }
        }
    }
    private suspend fun assertRestored(app: ChatBarApp, sessionHash: String, messagesHash: String, documentHash: String) {
        val card = checkNotNull(app.characterRepository.getById(CARD))
        check(card.id == CARD && card.name == "synthetic cloud recovery card" &&
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

    private suspend fun await(timeout: Long = 30_000, condition: suspend () -> Boolean) = withTimeout(timeout) {
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
        const val ORACLE = "synthetic-cloud-recovery-public-cold-oracle.properties"
        const val INPUT = "synthetic-cloud-recovery-input.properties"
        const val CERT = "synthetic-cloud-recovery-public-cert.der"
        const val USER = "synthetic-android-cloud-ui"
        const val CARD = "original"
        const val SESSION = "latest-conversation"
        const val CARD_UNIT = "character_cards/original"
        const val CHAT_UNIT = "conversation/latest-conversation"
        const val LAST = "Synthetic last assistant reply: the offline trip starts after this exact message."
    }
}


/** One public certificate, original platform TM implementation, instrumentation-process scope. */
class CloudUiTrustManagerFactorySpi : TrustManagerFactorySpi() {
    override fun engineInit(store: KeyStore?) { check(store == null) }
    override fun engineInit(parameters: ManagerFactoryParameters?) { error("TEST_TRUST_PARAMETERS_FORBIDDEN") }
    override fun engineGetTrustManagers(): Array<TrustManager> = arrayOf(checkNotNull(CloudUiTestTrust.manager))
}
object CloudUiTestTrust {
    private const val NAME = "CCBSyntheticCloudUiTrust"
    @Volatile var manager: X509TrustManager? = null
        private set
    private var provider: Provider? = null
    private var algorithm = ""
    @Synchronized fun install(certificate: ByteArray): AutoCloseable {
        check(provider == null && Security.getProvider(NAME) == null)
        val before = Security.getProviders().toList()
        algorithm = TrustManagerFactory.getDefaultAlgorithm()
        val factory = TrustManagerFactory.getInstance(algorithm)
        val originalProvider = factory.provider
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream()) as X509Certificate
        cert.checkValidity()
        check(cert.subjectAlternativeNames.any { it[0] == 7 && it[1] == "10.0.2.2" })
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("only-loopback-public-cert", cert) }
        factory.init(store)
        manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val test = object : Provider(NAME, 1.0, "single synthetic certificate; this process only") {
            init { put("TrustManagerFactory.$algorithm", CloudUiTrustManagerFactorySpi::class.java.name) }
        }
        try {
            check(Security.insertProviderAt(test, 1) == 1); provider = test
            check(TrustManagerFactory.getInstance(algorithm).provider === test && originalProvider !== test)
        } catch (failure: Throwable) {
            if (Security.getProvider(NAME) === test) Security.removeProvider(NAME)
            provider = null; manager = null
            check(Security.getProviders().toList().zip(before).all { (a, b) -> a === b } && Security.getProviders().size == before.size)
            throw failure
        }
        return AutoCloseable {
            synchronized(this) {
                check(Security.getProvider(NAME) === test)
                Security.removeProvider(NAME); provider = null; manager = null
                val after = Security.getProviders().toList()
                check(after.size == before.size && after.zip(before).all { (a, b) -> a === b })
                check(TrustManagerFactory.getDefaultAlgorithm() == algorithm)
            }
        }
    }
    @Synchronized fun withPlatformTrust(work: () -> Unit) {
        val active = checkNotNull(provider)
        check(Security.getProvider(NAME) === active)
        Security.removeProvider(NAME)
        try { work() } finally { check(Security.insertProviderAt(active, 1) == 1) }
    }
}
