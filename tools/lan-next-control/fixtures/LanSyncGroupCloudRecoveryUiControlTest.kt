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
            " CONSENT_BODY_SWIPES_COMPLETED[$consentBodySwipesCompleted] CONSENT_GEOMETRY_BEFORE[$consentGeometryBefore] CONSENT_GEOMETRY_AFTER[$consentGeometryAfter]" +
            " PASSIVE_AUTHOR_COUNTS[versions=$observedLocalVersions,changes=$observedLocalChanges,importedCard=$observedImportedCardChanges,importedChat=$observedImportedChatChanges,otherNativeMismatch=$observedOtherNativeMismatches,unaccountedSigned=$observedUnaccountedSigned,unaccountedIntent=$observedUnaccountedIntent]" +
            " PASSIVE_BOOLEANS[stableControlHistory=$observedStableSigned,denseAuthorLineage=$observedDenseAuthorLineage,exactCardRevision=$observedExactCardRevision,exactCardReceipt=$observedExactCardReceipt]" +
            " CONTENT_MARKER_COUNTS[card=$observedCardMarkerCount,session=$observedSessionMarkerCount,maxMessageMultiplicity=$observedMessageMarkerMultiplicity]" +
            " PASSIVE_CLOUD_DIAGNOSTIC[$passiveCloudDiagnostic]" +
            " PASSIVE_ROUTE_DIAGNOSTIC[$passiveRouteDiagnostic]" +
            " PASSIVE_UPLOAD_EXECUTION[$passiveUploadExecution]" +
            " PASSIVE_CLOUD_WORKER_EXECUTION[$passiveCloudWorkerExecution]" +
            " PASSIVE_CLOUD_WORKER_RECENT[$passiveCloudWorkerRecent]" +
            " PASSIVE_CLOUD_WORKER_COUNTS[samples=$passiveCloudWorkerSamples,capped=$passiveCloudWorkerSamplesCapped]").also {
            it.stackTrace = failure.stackTrace // never original message, cause, UI tree or credential
        }
    }

    @Test fun realCloudRecoveryUiRestoresOfflineLatestConversationAndSavesKeystoreConnection() = runBlocking {
        passiveUploadExecutionSampler = PassiveUploadExecutionSampler()
        passiveUploadExecution = PassiveUploadExecution()
        passiveUploadExecutionAt = 0L
        passiveCloudWorkerExecution = PassiveCloudWorkerExecution()
        passiveCloudWorkerRecent.clear()
        passiveCloudWorkerSamples = 0
        passiveCloudWorkerSamplesCapped = false
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
            val sourceObserved = checkNotNull(passive(live))
            val version = sourceObserved.versions.single()
            check(version.author == old.device && version.authorSequence == 1L)
            check(version.changes.keys == setOf(CARD_UNIT, CHAT_UNIT))
            val reference = version.reference()
            val sourceCardHash = checkNotNull(version.changes.getValue(CARD_UNIT).valueHash)
            val sourceChatHash = checkNotNull(version.changes.getValue(CHAT_UNIT).valueHash)
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, sourceRoute)
            val receipt = checkNotNull(existingCloudReceipt(old, target, sourceObserved, reference))
            check(receipt.target == target && receipt.checkpoint == oldLedger.reference())
            check(receipt.objects.any { it.objectRequest.role == LanSyncGroupTargetObjectRole.RESOURCE })
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
            phase = "full-original-chat-replies-document-before-author-diagnostics"
            assertRestored(app, sessionHash, messagesHash, sha(documentBytes))
            phase = "passive-imported-refs-default-local-version-classification"
            val baseline = awaitPassiveBaseline(app, coordinator, access, reference, sourceCardHash, sourceChatHash)
            val row = access.cloudRoutes().rows().single()
            check(row.enabled && row.endpoint == endpoint && row.username == USER)
            val routeFile = access.work.resolve("cloud-connections/cloud-routes.protected")
            check(Files.isRegularFile(routeFile, NOFOLLOW_LINKS))
            check(!Files.readAllBytes(routeFile).toString(Charsets.ISO_8859_1).contains(pass))
            check(hashes(offline) == sourceInventory && !Files.exists(source, NOFOLLOW_LINKS))
            click("关闭")
            launched.close(); scenario = null
            owner.closeAndDrain() // cleanup, no sticky pause; next process gets a fresh real owner
            retainAuthorHistory(app, baseline.author)
            retainNativeHistory(app, baseline.nativeControl)
            val oracle = Properties().apply {
                setProperty("pid", Process.myPid().toString()); setProperty("device", access.device)
                setProperty("group", access.trust.group.value); setProperty("genesis", access.trust.genesis.toString())
                setProperty("rootHash", sha(access.trust.ownerCertificate.toByteArray()))
                setProperty("ledgerHash", sha(Json.encodeToString(SyncDeviceLedgerRecord.serializer(), current).toByteArray()))
                setProperty("sessionHash", sessionHash); setProperty("messagesHash", messagesHash); setProperty("documentHash", sha(documentBytes))
                setProperty("referenceId", reference.id.value); setProperty("referenceHash", reference.bodyHash)
                setProperty("sourceCardHash", sourceCardHash); setProperty("sourceChatHash", sourceChatHash)
                setProperty("historyRefs", saveMap(versionMap(baseline.observed.versions)))
                setProperty("authorHistory", saveMap(authorHistory(app)))
                setProperty("nativeHistory", saveMap(nativeHistory(app)))
                setProperty("nativeSequence", baseline.observed.view.nativeState.completedSequence.toString())
                setProperty("identityHash", hash(native.resolve("identity.protected")))
                setProperty("authorOriginHash", hash(native.resolve("group-profile/joining/node/author/origin.protected")))
                setProperty("nativeOriginHash", hash(native.resolve("group-profile/joining/node/native/origin.protected")))
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
        passiveUploadExecutionSampler = PassiveUploadExecutionSampler()
        passiveUploadExecution = PassiveUploadExecution()
        passiveUploadExecutionAt = 0L
        passiveCloudWorkerExecution = PassiveCloudWorkerExecution()
        passiveCloudWorkerRecent.clear()
        passiveCloudWorkerSamples = 0
        passiveCloudWorkerSamplesCapped = false
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
                "nativeOriginHash" to "group-profile/joining/node/native/origin.protected"))
                check(hash(native.resolve(path)) == expected(name))
            phase = "cold-full-content-and-immutable-history-before-edit"
            val reference = SyncVersionRef(SyncStoreId.parse(expected("referenceId")), expected("referenceHash"))
            assertRestored(actual, expected("sessionHash"), expected("messagesHash"), expected("documentHash"))
            val retainedRefs = readMap(expected("historyRefs")); val retainedAuthor = readMap(expected("authorHistory"))
            val retainedNative = readMap(expected("nativeHistory"))
            val coldBaseline = awaitPassiveBaseline(actual, coordinator, access, reference,
                expected("sourceCardHash"), expected("sourceChatHash"), retainedRefs, retainedAuthor, retainedNative)
            check(coldBaseline.observed.view.nativeState.completedSequence >= expected("nativeSequence").toLong())
            val row = access.cloudRoutes().rows().single()
            check(row.enabled && row.id.value == expected("routeId") && row.endpoint == expected("endpoint") && row.username == USER)
            check(hash(access.work.resolve("cloud-connections/cloud-routes.protected")) == expected("routeHash"))
            await(120_000) { owner.backgroundState.value.phase == AndroidLanSyncBackgroundPhase.RUNNING }
            phase = "ordinary-android-source-change-and-saved-route-auto-upload"
            // Normal committed app data change; no Sync button, Runtime.run or node.upload call.
            val card = checkNotNull(actual.characterRepository.getById(CARD))
            val expectedHash = expectedCardHash(checkNotNull(coldBaseline.observed.view.plan).before)
            check(expectedHash != expected("sourceCardHash"))
            val edited = card.copy(name = EDITED_CARD_NAME, updatedAt = 5)
            actual.characterRepository.save(edited) // ordinary committed repository hook, no Sync/probe/upload
            phase = "await-exact-card-version-native-frontier"
            val editedView = awaitExactCardRevision(actual, coordinator, access, coldBaseline, reference,
                expected("sourceCardHash"), expected("sourceChatHash"), expectedHash)
            val newVersion = editedView.versions.single { it.author == access.device && CARD_UNIT in it.changes }
            val newReference = newVersion.reference()
            val editedNativeHistory = nativeHistory(actual)
            val editedAuthorHistory = authorHistory(actual)
            check(newReference != reference && newVersion.changes.getValue(CARD_UNIT).valueHash == expectedHash)
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, row.id)
            phase = "await-existing-exact-new-version-cloud-receipt-no-probe"
            observePassiveRouteClass(coordinator, target, PassiveRouteBoundary.BEFORE_WAIT)
            try {
                await(180_000) {
                    observePassiveRouteClass(coordinator, target)
                    val observed = passive(coordinator)
                    attemptPassiveCloudDiagnostic {
                        samplePassiveCloudDiagnostic(coordinator, access, target, observed, newReference, reference,
                            actual.lanSyncStartupReady, launched.state, owner.backgroundState.value.phase)
                    }
                    if (observed == null) return@await false
                    val receipt = existingCloudReceipt(access, target, observed, newReference, reference)
                    if (receipt == null) false else { observedExactCardReceipt = true; true }
                }
            } catch (failure: Throwable) {
                attemptPassiveCloudDiagnostic {
                    samplePassiveCloudDiagnostic(coordinator, access, target, passive(coordinator), newReference, reference,
                        actual.lanSyncStartupReady, launched.state, owner.backgroundState.value.phase, force = true)
                }
                throw failure
            } finally {
                observePassiveRouteClass(coordinator, target, PassiveRouteBoundary.AFTER_WAIT)
            }
            check(observedExactCardRevision && observedExactCardReceipt)
            awaitExactCardRevision(actual, coordinator, access, coldBaseline, reference,
                expected("sourceCardHash"), expected("sourceChatHash"), expectedHash)
            check(actual.characterRepository.getById(CARD) == edited)
            assertRestored(actual, expected("sessionHash"), expected("messagesHash"), expected("documentHash"), EDITED_CARD_NAME)
            retainAuthorHistory(actual, retainedAuthor)
            retainAuthorHistory(actual, editedAuthorHistory)
            retainNativeHistory(actual, retainedNative)
            retainNativeHistory(actual, editedNativeHistory)
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
    private data class PassiveView(val view: LanSyncGroupNodeView, val history: SyncCausalHistory,
        val versions: List<SyncCausalVersion>)
    private data class PassiveBaseline(val observed: PassiveView, val author: Map<String, String>, val nativeControl: Map<String, String>)
    private var observedLocalVersions = 0
    private var observedLocalChanges = 0
    private var observedImportedCardChanges = 0
    private var observedImportedChatChanges = 0
    private var observedOtherNativeMismatches = 0
    private var observedUnaccountedSigned = 0
    private var observedUnaccountedIntent = 0
    private var observedDenseAuthorLineage = false
    private var observedStableSigned = false
    private var observedExactCardRevision = false
    private var observedExactCardReceipt = false
    private var observedCardMarkerCount = 0
    private var observedSessionMarkerCount = 0
    private var observedMessageMarkerMultiplicity = 0

    // Only existing in-memory runtime projection. Never inspect/reconcile/capture/apply here.
    private fun passive(coordinator: LanSyncGroupCoordinator): PassiveView? {
        val view = coordinator.view.value ?: return null
        val history = view.reconciliation.history?.snapshot() ?: return null
        return PassiveView(view, history, history.admittedVersions())
    }
    private fun controlHistory(app: ChatBarApp, area: String, prefixes: Set<String>): Map<String, String> {
        check(area in setOf("author", "native"))
        val directory = nativeRoot(app).resolve("group-profile/joining/node/$area")
        check(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        val result = sortedMapOf<String, String>()
        for (prefix in prefixes) {
            Files.newDirectoryStream(directory, "$prefix-*.protected").use { paths ->
                for (path in paths) {
                    val name = path.fileName.toString()
                    check(name.matches(Regex("$prefix-[0-9]{19}\\.protected")))
                    check(checkNotNull(name.removePrefix("$prefix-").removeSuffix(".protected").toLongOrNull()) > 0L)
                    result[name] = hash(path)
                }
            }
        }
        return result
    }
    private fun authorHistory(app: ChatBarApp) = controlHistory(app, "author", setOf("intent", "signed"))
    private fun nativeHistory(app: ChatBarApp) = controlHistory(app, "native", setOf("intent", "receipt"))
    private fun retainPrefix(current: Map<String, String>, before: Map<String, String>) =
        check(before.all { (name, digest) -> current[name] == digest })
    private fun retainAuthorHistory(app: ChatBarApp, before: Map<String, String>) = retainPrefix(authorHistory(app), before)
    private fun retainNativeHistory(app: ChatBarApp, before: Map<String, String>) = retainPrefix(nativeHistory(app), before)
    private fun saveMap(values: Map<String, String>): String = values.toSortedMap().entries.joinToString("\n") { it.key + "=" + it.value }
    private fun readMap(value: String): Map<String, String> = if (value.isEmpty()) emptyMap() else value.lines().associate { line ->
        val pair = line.split('=', limit = 2); check(pair.size == 2); pair[0] to pair[1]
    }
    private fun versionMap(versions: List<SyncCausalVersion>) = versions.associate { it.id.value to it.bodyHash() }
    private fun retainVersions(observed: PassiveView, before: Map<String, String>) {
        val current = versionMap(observed.versions)
        check(before.all { (id, digest) -> current[id] == digest })
    }

    /** Classify actual admitted signed versions, never a global author counter assumption. */
    private fun localVersionsMatchNative(app: ChatBarApp, access: LanSyncGroupRuntimeAccess,
        observed: PassiveView, controls: Map<String, String>, allowedCard: SyncVersionRef? = null): Boolean {
        val local = observed.versions.filter { it.author == access.device }
        observedLocalVersions = local.size
        observedLocalChanges = local.sumOf { it.changes.size }
        val cards = local.filter { CARD_UNIT in it.changes }
        observedImportedCardChanges = cards.size
        observedImportedChatChanges = local.count { CHAT_UNIT in it.changes }
        check(local.all { it.group == access.trust.group } && access.device !in observed.history.authorForks())
        check(local.map { it.authorSequence }.distinct().size == local.size)
        check(observedImportedChatChanges == 0)
        check(if (allowedCard == null) cards.isEmpty() else cards.size == 1 && cards.single().reference() == allowedCard)
        val signed = controls.keys.filterTo(linkedSetOf()) { it.startsWith("signed-") }
        val intents = controls.keys.filterTo(linkedSetOf()) { it.startsWith("intent-") }
        fun names(prefix: String) = local.mapTo(linkedSetOf()) { "$prefix-${it.authorSequence.toString().padStart(19, '0')}.protected" }
        val expectedSigned = names("signed"); val expectedIntents = names("intent")
        observedUnaccountedSigned = (signed - expectedSigned).size + (expectedSigned - signed).size
        observedUnaccountedIntent = (intents - expectedIntents).size + (expectedIntents - intents).size
        fun dense(names: Set<String>, prefix: String): Boolean = names.map {
            it.removePrefix("$prefix-").removeSuffix(".protected").toLong()
        }.sorted().withIndex().all { (index, sequence) -> sequence == index.toLong() + 1L }
        val ordered = local.sortedBy { it.authorSequence }
        observedDenseAuthorLineage = dense(signed, "signed") && dense(intents, "intent") &&
            ordered.withIndex().all { (index, version) -> version.authorSequence == index.toLong() + 1L &&
                version.previousByAuthor == ordered.getOrNull(index - 1)?.reference() }
        val projected = observed.view.plan?.before?.hashes() ?: return false
        val otherUnits = local.flatMap { it.changes.keys }.toSet() - setOf(CARD_UNIT, CHAT_UNIT)
        observedOtherNativeMismatches = otherUnits.count { unit ->
            val latest = local.filter { unit in it.changes }.maxBy { it.authorSequence }
            val change = latest.changes.getValue(unit)
            val native = observed.view.nativeState.units[unit]
            // Classify actual non-imported native units; do not assert unknown units are proven defaults.
            change.content == null || native == null || projected[unit] != change.valueHash || native.valueHash != change.valueHash ||
                latest.reference() !in native.frontiers
        }
        return observedUnaccountedSigned == 0 && observedUnaccountedIntent == 0 && observedDenseAuthorLineage && observedOtherNativeMismatches == 0
    }
    private fun importedMatches(observed: PassiveView, reference: SyncVersionRef,
        cardHash: String, chatHash: String, cardReference: SyncVersionRef = reference): Boolean {
        if (!observed.history.contains(reference)) return false
        val source = observed.versions.single { it.reference() == reference }
        check(source.changes.keys == setOf(CARD_UNIT, CHAT_UNIT))
        check(source.changes.getValue(CARD_UNIT).valueHash == cardHash && source.changes.getValue(CHAT_UNIT).valueHash == chatHash)
        val card = observed.view.nativeState.units[CARD_UNIT] ?: return false
        val chat = observed.view.nativeState.units[CHAT_UNIT] ?: return false
        val plan = observed.view.plan ?: return false
        val expectedCard = if (cardReference == reference) cardHash else
            observed.versions.single { it.reference() == cardReference }.changes.getValue(CARD_UNIT).valueHash
        return card.valueHash == expectedCard && card.frontiers == setOf(cardReference) &&
            chat.valueHash == chatHash && chat.frontiers == setOf(reference) &&
            plan.before.hashes()[CARD_UNIT] == expectedCard && plan.before.hashes()[CHAT_UNIT] == chatHash
    }
    private suspend fun awaitPassiveBaseline(app: ChatBarApp, coordinator: LanSyncGroupCoordinator,
        access: LanSyncGroupRuntimeAccess, reference: SyncVersionRef, cardHash: String, chatHash: String,
        retainedVersions: Map<String, String> = emptyMap(), retainedAuthor: Map<String, String> = emptyMap(),
        retainedNative: Map<String, String> = emptyMap()): PassiveBaseline {
        var accepted: PassiveBaseline? = null
        var authorPrefix = retainedAuthor; var nativePrefix = retainedNative
        await(180_000) {
            val before = authorHistory(app); val nativeBefore = nativeHistory(app)
            retainPrefix(before, authorPrefix); retainPrefix(nativeBefore, nativePrefix)
            authorPrefix = before; nativePrefix = nativeBefore
            val observed = passive(coordinator) ?: return@await false
            if (!observed.history.contains(reference)) return@await false
            retainVersions(observed, retainedVersions)
            val after = authorHistory(app); val nativeAfter = nativeHistory(app)
            retainPrefix(after, before); retainPrefix(nativeAfter, nativeBefore)
            authorPrefix = after; nativePrefix = nativeAfter
            observedStableSigned = before == after && nativeBefore == nativeAfter
            // Normal append/pending work retries; only a stable, fully projected dense sample can pass.
            val actual = observedStableSigned && localVersionsMatchNative(app, access, observed, after)
            if (actual && observedStableSigned && importedMatches(observed, reference, cardHash, chatHash)) {
                accepted = PassiveBaseline(observed, after, nativeAfter); true
            } else false
        }
        return checkNotNull(accepted)
    }
    private fun expectedCardHash(before: LanSyncSnapshot): String {
        check(before.records.count { it.type == "character_cards" && it.id == CARD } == 1)
        val records = before.records.map { record ->
            if (record.type != "character_cards" || record.id != CARD) record else record.copy(body =
                kotlinx.serialization.json.JsonObject(record.body.toMutableMap().apply {
                    put("name", kotlinx.serialization.json.JsonPrimitive(EDITED_CARD_NAME))
                    put("updatedAt", kotlinx.serialization.json.JsonPrimitive(5))
                }))
        }
        // Official frozen snapshot/unit hashing; no second projection or business serialization format.
        return before.copy(records = records).hashes().getValue(CARD_UNIT)
    }
    private suspend fun awaitExactCardRevision(app: ChatBarApp, coordinator: LanSyncGroupCoordinator,
        access: LanSyncGroupRuntimeAccess, baseline: PassiveBaseline, source: SyncVersionRef,
        sourceCardHash: String, sourceChatHash: String, expectedHash: String): PassiveView {
        val prior = versionMap(baseline.observed.versions)
        val priorMaximum = baseline.observed.versions.filter { it.author == access.device }.maxOfOrNull { it.authorSequence } ?: 0L
        val priorCard = baseline.observed.view.nativeState.units.getValue(CARD_UNIT)
        var accepted: PassiveView? = null
        var authorPrefix = baseline.author; var nativePrefix = baseline.nativeControl
        await(180_000) {
            val controls = authorHistory(app); val nativeBefore = nativeHistory(app)
            retainPrefix(controls, authorPrefix); retainPrefix(nativeBefore, nativePrefix)
            authorPrefix = controls; nativePrefix = nativeBefore
            val observed = passive(coordinator) ?: return@await false
            retainVersions(observed, prior)
            val candidates = observed.versions.filter { version ->
                version.author == access.device && version.id.value !in prior && version.authorSequence > priorMaximum &&
                    version.changes[CARD_UNIT]?.valueHash == expectedHash && CHAT_UNIT !in version.changes
            }
            check(candidates.size <= 1)
            val candidate = candidates.singleOrNull() ?: return@await false
            check(candidate.changes.getValue(CARD_UNIT).previous == priorCard.frontiers)
            val reference = candidate.reference()
            val after = authorHistory(app); val nativeAfter = nativeHistory(app)
            retainPrefix(after, controls); retainPrefix(nativeAfter, nativeBefore)
            authorPrefix = after; nativePrefix = nativeAfter
            observedStableSigned = controls == after && nativeBefore == nativeAfter
            val actual = observedStableSigned && localVersionsMatchNative(app, access, observed, after, reference)
            if (actual && observedStableSigned && importedMatches(observed, source, sourceCardHash, sourceChatHash, reference)) {
                observedExactCardRevision = true; accepted = observed; true
            } else false
        }
        return checkNotNull(accepted)
    }

    private enum class PassivePlatformClass { CONFIGURATION_UNAVAILABLE, FOREGROUND_REQUIRED, SYSTEM_TIME_LIMIT, STOP_PENDING, START_OR_PROMOTION_UNAVAILABLE, OTHER_FAILURE, OTHER_OR_NORMAL }
    private fun passivePlatformClass(text: String): PassivePlatformClass = when (text) {
        "\u7f51\u76d8\u8fde\u63a5\u8bbe\u7f6e\u6682\u65f6\u65e0\u6cd5\u8bfb\u53d6\uff0c\u539f\u6587\u4ef6\u5df2\u4fdd\u7559\uff1b\u8bf7\u6253\u5f00\u8fde\u63a5\u8bbe\u7f6e\u68c0\u67e5" -> PassivePlatformClass.CONFIGURATION_UNAVAILABLE
        "\u7f51\u76d8\u7b49\u5f85\u5207\u56de\u5e94\u7528\u540e\u7ee7\u7eed\uff0c\u5f85\u4f20\u961f\u5217\u5df2\u4fdd\u7559" -> PassivePlatformClass.FOREGROUND_REQUIRED
        "\u7f51\u76d8\u5df2\u8fbe\u5230\u7cfb\u7edf\u540e\u53f0\u65f6\u9650\uff0c\u5f85\u4f20\u961f\u5217\u5df2\u4fdd\u7559\uff0c\u7b49\u5f85\u6062\u590d" -> PassivePlatformClass.SYSTEM_TIME_LIMIT
        "\u4e0a\u4e00\u6279\u7f51\u76d8\u4f20\u8f93\u4ecd\u5728\u505c\u6b62\uff0c\u5f85\u4f20\u961f\u5217\u5df2\u4fdd\u7559" -> PassivePlatformClass.STOP_PENDING
        "\u7cfb\u7edf\u5c1a\u672a\u5141\u8bb8\u672c\u6279\u7f51\u76d8\u540e\u53f0\u4f20\u8f93\uff0c\u8bf7\u5207\u56de\u5e94\u7528\u68c0\u67e5\uff1b\u5f85\u4f20\u961f\u5217\u5df2\u4fdd\u7559" -> PassivePlatformClass.START_OR_PROMOTION_UNAVAILABLE
        "\u7f51\u76d8\u6838\u5bf9\u672a\u5b8c\u6210\uff0c\u5f85\u4f20\u961f\u5217\u5df2\u4fdd\u7559" -> PassivePlatformClass.OTHER_FAILURE
        else -> PassivePlatformClass.OTHER_OR_NORMAL
    }

    // Uncompiled controller fragment: only classify already-existing progress.text; no owner/IO calls.
    private enum class PassiveRouteClass { UNOBSERVED, WAITING, READING, CHECKING, PREPARING, PARTIAL_HELD, UPLOADING, FULL_OBSERVED, PAUSED, ROUTE_CHANGED, AUTH, PERMISSION, STALE_PARTIAL, PARTIAL_UPLOADING, LOCAL_CACHE, QUOTA, CANDIDATE_HELD, INCOMPLETE, CONFIG_UNAVAILABLE, UNKNOWN }
    private fun passiveRouteClass(text: String?): PassiveRouteClass = when (text) {
        null -> PassiveRouteClass.UNOBSERVED
        "\u7b49\u5f85\u68c0\u67e5\u672c\u6b21\u9009\u5b9a\u7248\u672c" -> PassiveRouteClass.WAITING
        "\u6b63\u5728\u8bfb\u53d6\u6b64\u7f51\u76d8\u7684\u52a0\u5bc6\u5019\u9009" -> PassiveRouteClass.READING
        "\u6b63\u5728\u68c0\u67e5\u672c\u6b21\u9009\u5b9a\u7248\u672c\u7684\u6295\u9012\u8bb0\u5f55" -> PassiveRouteClass.CHECKING
        "\u6b63\u5728\u4fdd\u5168\u672c\u6b21\u9009\u5b9a\u7248\u672c" -> PassiveRouteClass.PREPARING
        "\u72ec\u7acb\u8d44\u6599\u5df2\u5206\u9879\u6838\u9a8c\uff0c\u5b8c\u6574\u9009\u5b9a\u8303\u56f4\u4ecd\u5f85\u8865\u9f50" -> PassiveRouteClass.PARTIAL_HELD
        "\u6b63\u5728\u6295\u9012\u672c\u6b21\u9009\u5b9a\u7248\u672c" -> PassiveRouteClass.UPLOADING
        "\u672c\u6b21\u7f51\u76d8\u5de5\u4f5c\u5df2\u6682\u505c\uff0c\u5f85\u4f20\u8d44\u6599\u5df2\u4fdd\u7559" -> PassiveRouteClass.PAUSED
        "\u672c\u673a\u7f51\u76d8\u8bbe\u7f6e\u5df2\u53d8\u66f4\uff0c\u5f85\u4f20\u8d44\u6599\u5df2\u4fdd\u7559" -> PassiveRouteClass.ROUTE_CHANGED
        "\u8d26\u53f7\u9a8c\u8bc1\u672a\u901a\u8fc7\uff0c\u8bf7\u4fee\u6539\u6b64\u7f51\u76d8\u914d\u7f6e\uff1b\u5f85\u4f20\u8d44\u6599\u5df2\u4fdd\u7559" -> PassiveRouteClass.AUTH
        "\u6b64\u7f51\u76d8\u672a\u5141\u8bb8\u672c\u6b21\u8bbf\u95ee\uff0c\u8bf7\u68c0\u67e5\u6b64\u7f51\u76d8\u914d\u7f6e\uff1b\u5f85\u4f20\u8d44\u6599\u5df2\u4fdd\u7559" -> PassiveRouteClass.PERMISSION
        "\u672c\u673a\u8d44\u6599\u5df2\u53d8\u5316\uff0c\u8f83\u65e9\u8303\u56f4\u7684\u5206\u9879\u6838\u9a8c\u5df2\u4fdd\u7559\uff0c\u4ecd\u5f85\u540c\u6b65" -> PassiveRouteClass.STALE_PARTIAL
        "\u6b63\u5728\u5206\u9879\u6295\u9012\u72ec\u7acb\u8d44\u6599\uff0c\u5b8c\u6574\u8303\u56f4\u4ecd\u5f85\u8865\u9f50" -> PassiveRouteClass.PARTIAL_UPLOADING
        "\u672c\u673a\u4fdd\u5168\u7684\u7f51\u76d8\u5206\u7247\u5f85\u68c0\u67e5\uff0c\u539f\u59cb\u8d44\u6599\u4e0e\u5206\u7247\u5df2\u4fdd\u7559" -> PassiveRouteClass.LOCAL_CACHE
        "\u6b64\u7f51\u76d8\u7a7a\u95f4\u4e0d\u8db3\uff0c\u5f85\u4f20\u8d44\u6599\u5df2\u4fdd\u7559" -> PassiveRouteClass.QUOTA
        "\u6b64\u7f51\u76d8\u7684\u5019\u9009\u5c1a\u672a\u901a\u8fc7\u6838\u9a8c\uff0c\u5f85\u4f20\u8d44\u6599\u5df2\u4fdd\u7559" -> PassiveRouteClass.CANDIDATE_HELD
        "\u6b64\u7f51\u76d8\u672c\u6b21\u5c1a\u672a\u5b8c\u6210\uff0c\u5f85\u4f20\u8d44\u6599\u5df2\u4fdd\u7559" -> PassiveRouteClass.INCOMPLETE
        "\u7f51\u76d8\u8fde\u63a5\u8bbe\u7f6e\u6682\u65f6\u65e0\u6cd5\u8bfb\u53d6\uff0c\u539f\u6587\u4ef6\u5df2\u4fdd\u7559\uff1b\u8bf7\u6253\u5f00\u8fde\u63a5\u8bbe\u7f6e\u68c0\u67e5" -> PassiveRouteClass.CONFIG_UNAVAILABLE
        else -> if (text.startsWith("\u672c\u6b21\u9009\u5b9a\u7248\u672c\u5df2\u8bfb\u56de\u6838\u9a8c\uff1b\u5168\u7ec4\u4ecd\u5f85\u540e\u7eed\u68c0\u67e5")) PassiveRouteClass.FULL_OBSERVED else PassiveRouteClass.UNKNOWN
    }

    private enum class PassiveRouteBoundary { POLL, BEFORE_WAIT, AFTER_WAIT }
    /** First-seen order of closed enum classes only; not a complete transition/event log. */
    private data class PassiveRouteDiagnostic(
        var before: PassiveRouteClass? = null, var current: PassiveRouteClass? = null,
        var after: PassiveRouteClass? = null,
        var observedInFirstSeenOrder: List<PassiveRouteClass> = emptyList(),
        var readUnavailableObserved: Boolean = false)
    private val passiveRouteClassesObserved = linkedSetOf<PassiveRouteClass>()
    private val passiveRouteDiagnostic = PassiveRouteDiagnostic()
    private fun observePassiveRouteClass(coordinator: LanSyncGroupCoordinator, target: SyncDeliveryTarget,
        boundary: PassiveRouteBoundary = PassiveRouteBoundary.POLL) {
        try {
            val classification = passiveRouteClass(coordinator.cloudSettings.value.progress[target.id]?.text)
            passiveRouteClassesObserved += classification
            passiveRouteDiagnostic.current = classification
            passiveRouteDiagnostic.observedInFirstSeenOrder = passiveRouteClassesObserved.toList()
            when (boundary) {
                PassiveRouteBoundary.BEFORE_WAIT -> passiveRouteDiagnostic.before = classification
                PassiveRouteBoundary.AFTER_WAIT -> passiveRouteDiagnostic.after = classification
                PassiveRouteBoundary.POLL -> Unit
            }
        } catch (_: Throwable) {
            passiveRouteDiagnostic.current = null
            passiveRouteDiagnostic.readUnavailableObserved = true
            when (boundary) {
                PassiveRouteBoundary.BEFORE_WAIT -> passiveRouteDiagnostic.before = null
                PassiveRouteBoundary.AFTER_WAIT -> passiveRouteDiagnostic.after = null
                PassiveRouteBoundary.POLL -> Unit
            }
        }
    }

    // Controller-only diagnostic fragment for the frozen c643 source. Not compiled or installed.
    // Use one sampler instance per test method; call once per second on the instrumentation thread.
    // The selected-result limit is not a hard bound on JVM Thread.getAllStackTraces enumeration cost.
    private enum class PassiveUploadPoint {
        NO_BLOCKING_UPLOAD_STACK, LOCK_OR_ENTRY, INITIAL_LOCAL_VALIDATION, HISTORICAL_INDEX,
        REMOTE_OBJECT_PROBE, LOCAL_STAGE, CHUNK_APPEND, CHUNK_READBACK,
        FINAL_READBACK_VALIDATION, RECEIPT_AND_INDEX, UNLOCATED_UPLOAD
    }
    private enum class PassiveUploadWait { KEYSTORE, CRYPTO, FILE_LOCK, SOCKET_IO, FILE_IO, OTHER }
    private data class PassiveUploadExecution(
        val uploadStacks: Int = -1,
        val custodyReadStacks: Int = -1,
        val points: Set<PassiveUploadPoint> = emptySet(),
        val waits: Set<PassiveUploadWait> = emptySet(),
        val states: Set<Thread.State> = emptySet(),
        val samplesByPoint: Map<PassiveUploadPoint, Int> = emptyMap(),
        val capped: Boolean = false,
        val readUnavailable: Boolean = false
    )
    private class PassiveUploadExecutionSampler {
    private val passiveUploadSamples = linkedMapOf<PassiveUploadPoint, Int>()

    fun sample(): PassiveUploadExecution = try {
        fun uploadFrame(frame: StackTraceElement): Boolean =
            frame.className.startsWith("com.example.chatbar.domain.lanSync.LanSyncGroupTargetPublication") &&
                (frame.className.contains("uploadCloud") || frame.methodName.contains("uploadCloud"))
        fun point(frames: Array<StackTraceElement>): PassiveUploadPoint {
            // These are call-site lines in c643 LanSyncGroupTargetPublication.kt, not generated line IDs.
            // Inlined/unavailable source lines stay UNLOCATED rather than inventing a phase.
            val lines = frames.filter(::uploadFrame).map { it.lineNumber }.toSet()
            return when {
                405 in lines -> PassiveUploadPoint.INITIAL_LOCAL_VALIDATION
                lines.any { it in 410..418 } -> PassiveUploadPoint.HISTORICAL_INDEX
                429 in lines -> PassiveUploadPoint.REMOTE_OBJECT_PROBE
                434 in lines -> PassiveUploadPoint.LOCAL_STAGE
                lines.any { it in 435..438 } -> PassiveUploadPoint.CHUNK_APPEND
                lines.any { it in 440..449 } -> PassiveUploadPoint.CHUNK_READBACK
                453 in lines -> PassiveUploadPoint.FINAL_READBACK_VALIDATION
                lines.any { it in 454..458 } -> PassiveUploadPoint.RECEIPT_AND_INDEX
                frames.any { it.className.startsWith("com.example.chatbar.domain.lanSync.LanSyncGroupTargetPublication") &&
                    it.methodName.contains("probeCloudObject") } -> PassiveUploadPoint.REMOTE_OBJECT_PROBE
                lines.any { it in 398..404 } -> PassiveUploadPoint.LOCK_OR_ENTRY
                else -> PassiveUploadPoint.UNLOCATED_UPLOAD
            }
        }
        fun waitKind(frames: Array<StackTraceElement>): PassiveUploadWait {
            // Only closed categories are emitted. Never emit thread names, frames, paths or messages.
            for (frame in frames.take(24)) {
                val owner = frame.className; val method = frame.methodName
                if (owner.startsWith("android.security.keystore")) return PassiveUploadWait.KEYSTORE
                if ((owner.contains("FileChannel") || owner.contains("FileDispatcher")) &&
                    (method == "lock" || method == "lock0")) return PassiveUploadWait.FILE_LOCK
                if ((owner.contains("SocketInputStream") || owner.contains("SocketOutputStream") ||
                        owner.contains("ConscryptEngineSocket") || owner.contains("ConscryptFileDescriptorSocket")) &&
                    (method.contains("read", ignoreCase = true) || method.contains("write", ignoreCase = true)))
                    return PassiveUploadWait.SOCKET_IO
                if (owner == "java.io.FileInputStream" || owner == "java.io.FileOutputStream") return PassiveUploadWait.FILE_IO
                if (owner == "javax.crypto.Cipher" || owner.startsWith("java.security.MessageDigest")) return PassiveUploadWait.CRYPTO
            }
            return PassiveUploadWait.OTHER
        }
        val selected = Thread.getAllStackTraces().entries.filter { (_, frames) -> frames.any(::uploadFrame) }
        val bounded = selected.take(64)
        val points = bounded.mapTo(linkedSetOf()) { point(it.value) }
        if (bounded.isEmpty()) points += PassiveUploadPoint.NO_BLOCKING_UPLOAD_STACK
        points.forEach { passiveUploadSamples[it] = ((passiveUploadSamples[it] ?: 0) + 1).coerceAtMost(4096) }
        PassiveUploadExecution(
            uploadStacks = bounded.size,
            custodyReadStacks = bounded.count { entry -> entry.value.any {
                it.className == "com.example.chatbar.domain.syncStore.SyncGroupCustody" && it.methodName == "read"
            } },
            points = points,
            waits = bounded.mapTo(linkedSetOf()) { waitKind(it.value) },
            states = bounded.mapTo(linkedSetOf()) { it.key.state },
            samplesByPoint = passiveUploadSamples.toMap(),
            capped = selected.size > bounded.size
        )
    } catch (_: Exception) {
        PassiveUploadExecution(samplesByPoint = passiveUploadSamples.toMap(), readUnavailable = true)
    }
    }

    private var passiveUploadExecutionSampler: PassiveUploadExecutionSampler? = null
    private var passiveUploadExecution = PassiveUploadExecution()
    private var passiveUploadExecutionAt = 0L
    /** Plain call from the original instrumentation-thread poll, never a worker or UI task. */
    private fun samplePassiveUploadExecution(coordinator: LanSyncGroupCoordinator) {
        val now = System.nanoTime()
        // The existing file sampler's forced final read must not cause a second thread snapshot in one second.
        if (passiveUploadExecutionAt != 0L && now - passiveUploadExecutionAt < 1_000_000_000L) return
        passiveUploadExecutionAt = now
        passiveUploadExecution = try { checkNotNull(passiveUploadExecutionSampler).sample() }
        catch (_: Throwable) { PassiveUploadExecution(samplesByPoint = passiveUploadExecution.samplesByPoint, readUnavailable = true) }
        recordPassiveCloudWorkerExecution(coordinator)
    }

    // Controller-only, uncompiled fragment. Requires the existing Coordinator instance.
    // Read once alongside each 128 sample on the instrumentation thread. No owner locks or actions.
    private data class PassiveCloudWorkerExecution(
        val workerPresent: Boolean? = null,
        val workerActive: Boolean? = null,
        val workerCompleted: Boolean? = null,
        val workerCancelled: Boolean? = null,
        val parentActive: Boolean? = null,
        val coordinatorClosed: Boolean? = null,
        val coordinatorPaused: Boolean? = null,
        val readUnavailable: Boolean = false
    )

    private fun samplePassiveCloudWorkerExecution(
        coordinator: com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator
    ): PassiveCloudWorkerExecution {
        var unavailable = false
        fun read(name: String): Any? = try {
            com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator::class.java
                .getDeclaredField(name).apply { isAccessible = true }.get(coordinator)
        } catch (_: Exception) { unavailable = true; null }
        val rawWorker = read("cloudWorker")
        val worker = rawWorker as? kotlinx.coroutines.Job
        val parentScope = read("scope") as? kotlinx.coroutines.CoroutineScope
        val parent = parentScope?.coroutineContext?.get(kotlinx.coroutines.Job)
        val closed = read("closed") as? Boolean
        val paused = read("paused") as? Boolean
        if (rawWorker != null && worker == null || parentScope == null || parent == null || closed == null || paused == null)
            unavailable = true
        return PassiveCloudWorkerExecution(
            workerPresent = if (unavailable && worker == null) null else worker != null,
            workerActive = worker?.isActive,
            workerCompleted = worker?.isCompleted,
            workerCancelled = worker?.isCancelled,
            parentActive = parent?.isActive,
            coordinatorClosed = closed,
            coordinatorPaused = paused,
            readUnavailable = unavailable
        )
    }

    // Unknown is never false. These fields are a non-transactional observation: cloudWorker is not
    // volatile, and reading its reference does not fence the entire lifecycle. Preserve consecutive
    // observations. A completed child with an active parent and an open, unpaused Coordinator supports
    // the exited-worker branch; it does not establish the cancellation cause or explain another run.
    // Never read a cancellation cause/message, call join/start/cancel, or acquire the lifecycle lock.

    private var passiveCloudWorkerExecution = PassiveCloudWorkerExecution()
    private val passiveCloudWorkerRecent = mutableListOf<PassiveCloudWorkerExecution>()
    private var passiveCloudWorkerSamples = 0
    private var passiveCloudWorkerSamplesCapped = false
    private fun recordPassiveCloudWorkerExecution(coordinator: LanSyncGroupCoordinator) {
        passiveCloudWorkerExecution = try { samplePassiveCloudWorkerExecution(coordinator) }
        catch (_: Throwable) { PassiveCloudWorkerExecution(readUnavailable = true) }
        passiveCloudWorkerSamples = (passiveCloudWorkerSamples + 1).coerceAtMost(4096)
        passiveCloudWorkerRecent += passiveCloudWorkerExecution // retain unavailable entries; never deduplicate
        if (passiveCloudWorkerRecent.size > 8) {
            passiveCloudWorkerRecent.removeAt(0)
            passiveCloudWorkerSamplesCapped = true
        }
    }

    private enum class PassiveCloudRead { NOT_SAMPLED, NO_TARGET_DIRECTORY, STABLE_FILES, FILE_SET_CHANGED, READ_UNAVAILABLE }
    private enum class PassiveCloudPart { NONE, ENUMERATION, FULL_PLAN, FULL_RECEIPT, PARTIAL_PLAN, PARTIAL_RECEIPT }
    /** Only counts, Boolean and closed enum types can enter the public failure transcript. */
    private data class PassiveCloudDiagnostic(
        var read: PassiveCloudRead = PassiveCloudRead.NOT_SAMPLED,
        var part: PassiveCloudPart = PassiveCloudPart.NONE,
        var targetDirectoryPresent: Boolean = false,
        var fullPlans: Int = -1, var fullReceipts: Int = -1, var partialPlans: Int = -1, var partialReceipts: Int = -1,
        var fullPlansWantNew: Int = -1, var fullPlansWantBoth: Int = -1,
        var fullReceiptsNewSlot: Int = -1, var duplicateNewSlots: Int = -1,
        var authenticatedNewWitnesses: Int = -1, var keyDescriptorCandidates: Int = -1, var contextCandidates: Int = -1,
        var partialPlansWantNew: Int = -1, var partialReceiptsWantNew: Int = -1,
        var partialReceiptsReportNewMetadata: Int = -1, var partialReceiptsReportNewCardPackage: Int = -1,
        var partialReceiptsReportOldCardPackage: Int = -1, var partialReceiptsReportOldChatPackage: Int = -1,
        var partialPendingReasons: Set<LanSyncGroupPartialPendingReason> = emptySet(),
        var requiredAdmitted: Boolean? = null, var sourceAdmitted: Boolean? = null,
        var routeProgressPresent: Boolean = false, var routeEnabled: Boolean? = null, var selectionObserved: Boolean? = null,
        var lastObservedPresent: Boolean = false, var lastPartialObservedPresent: Boolean = false,
        var partialProgressPresent: Boolean = false, var pendingOperations: Int = -1,
        var completeUnitPackages: Int = -1, var pendingUnitPackages: Int = -1, var pendingControlSlots: Int = -1,
        var observedObjects: Int = -1, var availableObjects: Int = -1,
        var paused: Boolean = false, var panelBusy: Boolean = false, var panelError: Boolean = false,
        var cloudSettingsBusy: Boolean = false, var cloudSettingsError: Boolean = false,
        var startupReady: Boolean = false, var activity: Lifecycle.State? = null,
        var background: AndroidLanSyncBackgroundPhase? = null, var nodeStage: LanSyncGroupNodeStage? = null,
        var platform: PassivePlatformClass = PassivePlatformClass.OTHER_OR_NORMAL,
        var platformClassesObserved: Set<PassivePlatformClass> = emptySet())
    private var passiveCloudDiagnostic = PassiveCloudDiagnostic()
    private var passiveCloudDiagnosticAt = 0L
    private val passivePlatformClassesObserved = linkedSetOf<PassivePlatformClass>()
    private inline fun attemptPassiveCloudDiagnostic(read: () -> Unit) {
        try { read() }
        catch (_: Throwable) { passiveCloudDiagnostic = PassiveCloudDiagnostic(read = PassiveCloudRead.READ_UNAVAILABLE) }
    }

    /** Diagnostic only: reads existing projections/immutable files; never affects the completion predicate. */
    private fun samplePassiveCloudDiagnostic(coordinator: LanSyncGroupCoordinator, access: LanSyncGroupRuntimeAccess,
        target: SyncDeliveryTarget, observed: PassiveView?, required: SyncVersionRef, retainedSource: SyncVersionRef,
        startupReady: Boolean, activity: Lifecycle.State, background: AndroidLanSyncBackgroundPhase, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && passiveCloudDiagnosticAt != 0L && now - passiveCloudDiagnosticAt < 1_000_000_000L) return
        passiveCloudDiagnosticAt = now
        samplePassiveUploadExecution(coordinator)
        val panel = coordinator.state.value; val settings = coordinator.cloudSettings.value
        val progress = settings.progress[target.id]; val partial = progress?.partial
        val platform = passivePlatformClass(panel.cloud.text)
        passivePlatformClassesObserved += platform
        val diagnostic = PassiveCloudDiagnostic(requiredAdmitted = observed?.history?.contains(required),
            sourceAdmitted = observed?.history?.contains(retainedSource), routeProgressPresent = progress != null,
            routeEnabled = settings.rows.singleOrNull { it.id == target.id }?.enabled,
            selectionObserved = progress?.selectionObserved, lastObservedPresent = progress?.lastObservedAt != null,
            lastPartialObservedPresent = progress?.lastPartialObservedAt != null, partialProgressPresent = partial != null,
            pendingOperations = progress?.pendingOperations ?: -1,
            completeUnitPackages = partial?.completeUnitPackages ?: -1, pendingUnitPackages = partial?.pendingUnitPackages ?: -1,
            pendingControlSlots = partial?.pendingControlSlots ?: -1, observedObjects = partial?.observedObjects ?: -1,
            availableObjects = partial?.availableObjects ?: -1, paused = panel.paused, panelBusy = panel.busy,
            panelError = panel.error != null, cloudSettingsBusy = settings.busy, cloudSettingsError = settings.error != null,
            startupReady = startupReady, activity = activity, background = background, nodeStage = observed?.view?.stage,
            platform = platform, platformClassesObserved = passivePlatformClassesObserved.toSet())
        val reasons = linkedSetOf<LanSyncGroupPartialPendingReason>()
        try {
            val directory = access.work.resolve("cloud-runtime/${target.id.value}/target")
            if (!Files.exists(directory, NOFOLLOW_LINKS)) { diagnostic.read = PassiveCloudRead.NO_TARGET_DIRECTORY; return }
            check(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
            diagnostic.targetDirectoryPresent = true; diagnostic.part = PassiveCloudPart.ENUMERATION
            fun files(pattern: String): List<Path> = Files.newDirectoryStream(directory, pattern).use { entries ->
                entries.toList().also { values -> check(values.all { Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }) }
            }
            fun <T> read(path: Path, serializer: kotlinx.serialization.KSerializer<T>): T {
                val raw = metadataBytes(path)
                val value = Json.decodeFromString(serializer, raw.toString(Charsets.UTF_8))
                check(hash(path) == sha(raw))
                return value
            }
            val plans = files("plan-*.json"); val receipts = files("cloud-receipt-*.json")
            val partialPlans = files("partial-plan-*.json"); val partialReceipts = files("partial-cloud-receipt-*.json")
            val before = (plans + receipts + partialPlans + partialReceipts).toSet()
            diagnostic.fullPlans = plans.size; diagnostic.fullReceipts = receipts.size
            diagnostic.partialPlans = partialPlans.size; diagnostic.partialReceipts = partialReceipts.size
            diagnostic.fullPlansWantNew = 0; diagnostic.fullPlansWantBoth = 0; diagnostic.fullReceiptsNewSlot = 0
            diagnostic.duplicateNewSlots = 0
            diagnostic.keyDescriptorCandidates = if (observed == null) -1 else 0
            diagnostic.contextCandidates = if (observed == null) -1 else 0
            diagnostic.partialPlansWantNew = 0; diagnostic.partialReceiptsWantNew = 0
            diagnostic.partialReceiptsReportNewMetadata = 0; diagnostic.partialReceiptsReportNewCardPackage = 0
            diagnostic.partialReceiptsReportOldCardPackage = 0; diagnostic.partialReceiptsReportOldChatPackage = 0
            val witnesses = observed?.view?.reconciliation?.authenticatedVersions.orEmpty().filter { it.envelope.version?.reference() == required }
            diagnostic.authenticatedNewWitnesses = if (observed == null) -1 else witnesses.size
            diagnostic.part = PassiveCloudPart.FULL_PLAN
            for (path in plans) {
                val plan = read(path, LanSyncGroupTargetPlan.serializer())
                if (plan.target == target && plan.group == access.trust.group && required in plan.versions) {
                    diagnostic.fullPlansWantNew++
                    if (retainedSource in plan.versions) diagnostic.fullPlansWantBoth++
                }
            }
            diagnostic.part = PassiveCloudPart.FULL_RECEIPT
            for (path in receipts) {
                val receipt = read(path, LanSyncGroupCloudTargetReceipt.serializer())
                if (receipt.target != target) continue
                val slots = receipt.objects.filter { it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION && it.objectRequest.slot == required.id.value }
                if (slots.size > 1) diagnostic.duplicateNewSlots++
                val slot = slots.singleOrNull() ?: continue
                diagnostic.fullReceiptsNewSlot++
                val matching = witnesses.filter { it.metadata.key == slot.objectRequest.key && it.metadata.descriptor == slot.objectRequest.descriptor }
                if (matching.isNotEmpty()) diagnostic.keyDescriptorCandidates++
                val planPath = directory.resolve("plan-" + path.fileName.toString().removePrefix("cloud-receipt-"))
                if (!Files.isRegularFile(planPath, NOFOLLOW_LINKS)) continue
                val plan = read(planPath, LanSyncGroupTargetPlan.serializer())
                if (matching.any { it.context.group == plan.group && it.context.epoch == plan.epoch && it.keyCommitment == plan.commitment }) diagnostic.contextCandidates++
            }
            diagnostic.part = PassiveCloudPart.PARTIAL_PLAN
            for (path in partialPlans) {
                val plan = read(path, LanSyncGroupPartialTargetPlan.serializer())
                if (plan.target == target && plan.scope.group == access.trust.group && required in plan.scope.wantedVersionRefs) {
                    diagnostic.partialPlansWantNew++; reasons += plan.selection.pending.map { it.reason }
                }
            }
            diagnostic.part = PassiveCloudPart.PARTIAL_RECEIPT
            for (path in partialReceipts) {
                val receipt = read(path, LanSyncGroupPartialCloudReceipt.serializer())
                if (receipt.target != target || receipt.scope.group != access.trust.group || required !in receipt.scope.wantedVersionRefs) continue
                diagnostic.partialReceiptsWantNew++
                val selection = receipt.observedSelection
                if (required in selection.metadataRefs) diagnostic.partialReceiptsReportNewMetadata++
                if (selection.completeUnitPackages.any { it.ref.version == required && it.ref.unit == CARD_UNIT }) diagnostic.partialReceiptsReportNewCardPackage++
                if (selection.completeUnitPackages.any { it.ref.version == retainedSource && it.ref.unit == CARD_UNIT }) diagnostic.partialReceiptsReportOldCardPackage++
                if (selection.completeUnitPackages.any { it.ref.version == retainedSource && it.ref.unit == CHAT_UNIT }) diagnostic.partialReceiptsReportOldChatPackage++
                reasons += selection.pending.map { it.reason }
            }
            val after = (files("plan-*.json") + files("cloud-receipt-*.json") + files("partial-plan-*.json") + files("partial-cloud-receipt-*.json")).toSet()
            diagnostic.read = if (before == after) PassiveCloudRead.STABLE_FILES else PassiveCloudRead.FILE_SET_CHANGED
            diagnostic.part = PassiveCloudPart.NONE
        } catch (_: Exception) { diagnostic.read = PassiveCloudRead.READ_UNAVAILABLE }
        finally {
            // Enumeration counts describe the initial file set. Derived matching counts are unknown
            // unless every typed file was read and the final file set is unchanged.
            if (diagnostic.read != PassiveCloudRead.STABLE_FILES) {
                diagnostic.fullPlansWantNew = -1; diagnostic.fullPlansWantBoth = -1
                diagnostic.fullReceiptsNewSlot = -1; diagnostic.duplicateNewSlots = -1
                diagnostic.keyDescriptorCandidates = -1; diagnostic.contextCandidates = -1
                diagnostic.partialPlansWantNew = -1; diagnostic.partialReceiptsWantNew = -1
                diagnostic.partialReceiptsReportNewMetadata = -1; diagnostic.partialReceiptsReportNewCardPackage = -1
                diagnostic.partialReceiptsReportOldCardPackage = -1; diagnostic.partialReceiptsReportOldChatPackage = -1
                reasons.clear()
            }
            diagnostic.partialPendingReasons = reasons.toSet()
            passiveCloudDiagnostic = diagnostic
        }
    }

    /** Existing immutable plan/receipt bytes only. No TargetPublication construction or recovery. */
    private fun existingCloudReceipt(access: LanSyncGroupRuntimeAccess, target: SyncDeliveryTarget,
        observed: PassiveView, required: SyncVersionRef, retainedSource: SyncVersionRef = required): LanSyncGroupCloudTargetReceipt? {
        if (!observed.history.contains(required) || !observed.history.contains(retainedSource)) return null
        val directory = access.work.resolve("cloud-runtime/${target.id.value}/target")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return null
        check(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        return Files.newDirectoryStream(directory, "cloud-receipt-*.json").use { paths ->
            var found: LanSyncGroupCloudTargetReceipt? = null
            for (path in paths) {
                val name = path.fileName.toString()
                check(name.matches(Regex("cloud-receipt-[a-f0-9]{64}\\.json")))
                val receiptRaw = metadataBytes(path)
                val receipt = Json.decodeFromString(LanSyncGroupCloudTargetReceipt.serializer(), receiptRaw.toString(Charsets.UTF_8))
                val selected = receipt.objects.singleOrNull { it.objectRequest.role == LanSyncGroupTargetObjectRole.VERSION &&
                    it.objectRequest.slot == required.id.value } ?: continue
                val planPath = directory.resolve("plan-" + name.removePrefix("cloud-receipt-"))
                val planRaw = metadataBytes(planPath)
                val plan = Json.decodeFromString(LanSyncGroupTargetPlan.serializer(), planRaw.toString(Charsets.UTF_8))
                check(receipt.planHash == sha(planRaw) && receipt.target == target && receipt.checkpoint == plan.checkpoint)
                check(plan.group == access.trust.group && plan.target == target && required in plan.versions && retainedSource in plan.versions)
                check(plan.checkpoint == observed.view.reconciliation.ledgerCheckpoint && receipt.rejectedCandidates >= 0)
                check(receipt.objects.map { it.objectRequest } == plan.objects)
                check(plan.objects.map { it.key }.distinct().size == plan.objects.size)
                val witnesses = observed.view.reconciliation.authenticatedVersions.filter { it.envelope.version?.reference() == required }
                if (witnesses.none { it.metadata.key == selected.objectRequest.key && it.metadata.descriptor == selected.objectRequest.descriptor &&
                    it.context.group == plan.group && it.context.epoch == plan.epoch && it.keyCommitment == plan.commitment }) continue
                receipt.objects.forEach { row ->
                    val readback = row.evidence.readback as? LanSyncGroupCloudTargetObservation.Chunks ?: error("EXPECTED_CHUNK_READBACK")
                    check(readback.observation.logicalKey == row.objectRequest.key && readback.observation.logicalDescriptor == row.objectRequest.descriptor)
                    check(readback.observation.manifest.route == target.id && readback.observation.manifest.manifestKey == row.objectRequest.key)
                    check(readback.observation.manifest.manifestDescriptor.length > 0 && readback.observation.observedAtMillis >= 0)
                    check(readback.observation.chunkCount >= 0 &&
                        (readback.observation.chunkCount == 0L) == (row.objectRequest.descriptor.length == 0L))
                    val evidence = row.evidence
                    if (evidence is LanSyncGroupCloudTargetEvidence.Appended) {
                        val append = (evidence.append as? LanSyncGroupCloudTargetObservation.Chunks)?.observation ?: error("EXPECTED_CHUNK_APPEND")
                        val actual = readback.observation
                        check(append.logicalKey == actual.logicalKey && append.logicalDescriptor == actual.logicalDescriptor &&
                            append.logicalOperation == actual.logicalOperation && append.manifest == actual.manifest && append.chunkCount == actual.chunkCount)
                        check(append.logicalOperation == row.objectRequest.operation && actual.logicalOperation == row.objectRequest.operation && append.observedAtMillis >= 0)
                    }
                }
                check(sha(metadataBytes(path)) == sha(receiptRaw) && sha(metadataBytes(planPath)) == sha(planRaw))
                found = receipt
            }
            found
        }
    }
    private fun metadataBytes(path: Path): ByteArray {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        // Fixture metadata decode budget only, unrelated to attachment/storage/business-data limits.
        check(Files.size(path) in 1..(Runtime.getRuntime().maxMemory() / 16))
        return Files.readAllBytes(path)
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
    private suspend fun assertRestored(app: ChatBarApp, sessionHash: String, messagesHash: String, documentHash: String,
        expectedCardName: String = "synthetic cloud recovery card") {
        val card = checkNotNull(app.characterRepository.getById(CARD))
        check(card.id == CARD && card.name == expectedCardName &&
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
        val allCards = app.jsonFileStorage.loadAll("character_cards", CharacterCard.serializer())
        val allSessions = app.jsonFileStorage.loadAll("chat_sessions", ChatSession.serializer())
        val allMessages = app.jsonFileStorage.loadAll("chat_messages", ChatMessage.serializer())
        observedCardMarkerCount = allCards.count { it.basicSetting == "synthetic original domain body; no edited Prompt" }
        observedSessionMarkerCount = allSessions.count { it.title == "synthetic latest recoverable conversation" }
        val messageMarkerCounts = messages().map { marker -> allMessages.count { it.content == marker.content } }
        observedMessageMarkerMultiplicity = messageMarkerCounts.maxOrNull() ?: 0
        check(observedCardMarkerCount == 1 && observedSessionMarkerCount == 1 && messageMarkerCounts.all { it == 1 })
        check(allSessions.count { it.id == SESSION } == 1)
        check(allMessages.filter { it.sessionId == SESSION }.size == 4)
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
        const val EDITED_CARD_NAME = "synthetic cold ordinary revision"
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
