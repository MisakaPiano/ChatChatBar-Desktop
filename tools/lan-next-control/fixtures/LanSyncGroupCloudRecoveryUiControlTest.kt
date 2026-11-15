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
            " PASSIVE_AUTHOR_COUNTS[versions=$observedLocalVersions,changes=$observedLocalChanges,importedCard=$observedImportedCardChanges,importedChat=$observedImportedChatChanges,otherNativeMismatch=$observedOtherNativeMismatches,unaccountedSigned=$observedUnaccountedSigned]" +
            " PASSIVE_BOOLEANS[stableSigned=$observedStableSigned,exactCardRevision=$observedExactCardRevision,exactCardReceipt=$observedExactCardReceipt]" +
            " CONTENT_MARKER_COUNTS[card=$observedCardMarkerCount,session=$observedSessionMarkerCount,maxMessageMultiplicity=$observedMessageMarkerMultiplicity]").also {
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
            retainSignedHistory(app, baseline.signed)
            val oracle = Properties().apply {
                setProperty("pid", Process.myPid().toString()); setProperty("device", access.device)
                setProperty("group", access.trust.group.value); setProperty("genesis", access.trust.genesis.toString())
                setProperty("rootHash", sha(access.trust.ownerCertificate.toByteArray()))
                setProperty("ledgerHash", sha(Json.encodeToString(SyncDeviceLedgerRecord.serializer(), current).toByteArray()))
                setProperty("sessionHash", sessionHash); setProperty("messagesHash", messagesHash); setProperty("documentHash", sha(documentBytes))
                setProperty("referenceId", reference.id.value); setProperty("referenceHash", reference.bodyHash)
                setProperty("sourceCardHash", sourceCardHash); setProperty("sourceChatHash", sourceChatHash)
                setProperty("historyRefs", saveMap(versionMap(baseline.observed.versions)))
                setProperty("signedHistory", saveMap(signedHistory(app)))
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
            val retainedRefs = readMap(expected("historyRefs")); val retainedSigned = readMap(expected("signedHistory"))
            val coldBaseline = awaitPassiveBaseline(actual, coordinator, access, reference,
                expected("sourceCardHash"), expected("sourceChatHash"), retainedRefs, retainedSigned)
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
            check(newReference != reference && newVersion.changes.getValue(CARD_UNIT).valueHash == expectedHash)
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, row.id)
            phase = "await-existing-exact-new-version-cloud-receipt-no-probe"
            await(180_000) {
                val observed = passive(coordinator) ?: return@await false
                val receipt = existingCloudReceipt(access, target, observed, newReference, reference)
                if (receipt == null) false else { observedExactCardReceipt = true; true }
            }
            check(observedExactCardRevision && observedExactCardReceipt)
            awaitExactCardRevision(actual, coordinator, access, coldBaseline, reference,
                expected("sourceCardHash"), expected("sourceChatHash"), expectedHash)
            check(actual.characterRepository.getById(CARD) == edited)
            assertRestored(actual, expected("sessionHash"), expected("messagesHash"), expected("documentHash"), EDITED_CARD_NAME)
            retainSignedHistory(actual, retainedSigned)
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
    private data class PassiveBaseline(val observed: PassiveView, val signed: Map<String, String>)
    private var observedLocalVersions = 0
    private var observedLocalChanges = 0
    private var observedImportedCardChanges = 0
    private var observedImportedChatChanges = 0
    private var observedOtherNativeMismatches = 0
    private var observedUnaccountedSigned = 0
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
    private fun signedHistory(app: ChatBarApp): Map<String, String> {
        val directory = nativeRoot(app).resolve("group-profile/joining/node/author")
        check(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        return Files.newDirectoryStream(directory, "signed-*.protected").use { paths ->
            val result = sortedMapOf<String, String>()
            for (path in paths) {
                val name = path.fileName.toString()
                check(name.matches(Regex("signed-[0-9]{19}\\.protected")))
                result[name] = hash(path)
            }
            result
        }
    }
    private fun retainSignedHistory(app: ChatBarApp, before: Map<String, String>) {
        val current = signedHistory(app)
        check(before.all { (name, digest) -> current[name] == digest })
    }
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
        observed: PassiveView, signed: Map<String, String>, allowedCard: SyncVersionRef? = null): Boolean {
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
        val names = local.mapTo(linkedSetOf()) { "signed-${it.authorSequence.toString().padStart(19, '0')}.protected" }
        observedUnaccountedSigned = (signed.keys - names).size + (names - signed.keys).size
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
        return observedUnaccountedSigned == 0 && observedOtherNativeMismatches == 0
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
        retainedVersions: Map<String, String> = emptyMap(), retainedSigned: Map<String, String> = emptyMap()): PassiveBaseline {
        var accepted: PassiveBaseline? = null
        await(180_000) {
            val before = signedHistory(app)
            val observed = passive(coordinator) ?: return@await false
            if (!observed.history.contains(reference)) return@await false
            retainVersions(observed, retainedVersions)
            check(retainedSigned.all { (name, digest) -> before[name] == digest })
            val actual = localVersionsMatchNative(app, access, observed, before)
            val after = signedHistory(app)
            observedStableSigned = before == after
            if (actual && observedStableSigned && importedMatches(observed, reference, cardHash, chatHash)) {
                accepted = PassiveBaseline(observed, after); true
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
        await(180_000) {
            val signed = signedHistory(app)
            val observed = passive(coordinator) ?: return@await false
            retainVersions(observed, prior); retainSignedHistory(app, baseline.signed)
            val candidates = observed.versions.filter { version ->
                version.author == access.device && version.id.value !in prior && version.authorSequence > priorMaximum &&
                    version.changes[CARD_UNIT]?.valueHash == expectedHash && CHAT_UNIT !in version.changes
            }
            check(candidates.size <= 1)
            val candidate = candidates.singleOrNull() ?: return@await false
            check(candidate.changes.getValue(CARD_UNIT).previous == priorCard.frontiers)
            val reference = candidate.reference()
            val actual = localVersionsMatchNative(app, access, observed, signed, reference)
            observedStableSigned = signed == signedHistory(app)
            if (actual && observedStableSigned && importedMatches(observed, source, sourceCardHash, sourceChatHash, reference)) {
                observedExactCardRevision = true; accepted = observed; true
            } else false
        }
        return checkNotNull(accepted)
    }

    /** Existing immutable plan/receipt bytes only. No TargetPublication construction or recovery. */
    private fun existingCloudReceipt(access: LanSyncGroupRuntimeAccess, target: SyncDeliveryTarget,
        observed: PassiveView, required: SyncVersionRef, retainedSource: SyncVersionRef = required): LanSyncGroupCloudTargetReceipt? {
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
