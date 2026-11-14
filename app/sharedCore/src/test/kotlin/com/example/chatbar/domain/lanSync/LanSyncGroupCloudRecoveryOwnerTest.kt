package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

/** Actual temporary owner lifecycle over private synthetic HTTP; no real provider or daily root. */
class LanSyncGroupCloudRecoveryOwnerTest {
    private class Fixture(val shared: WeakFixture, https: Boolean = false) {
        private val httpsIdentity = if (https) LanSyncTlsIdentity.create() else null
        private val httpsServer = httpsIdentity?.let { identity -> MockWebServer().apply {
            dispatcher = shared.dav
            useHttps(identity.context { false }.socketFactory, false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        } }
        val endpoint: String = (httpsServer ?: shared.server).url("/sync/").newBuilder().host("127.0.0.1").build().toString()
        val recoveryRequests get() = (httpsServer ?: shared.server).requestCount
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(30, TimeUnit.SECONDS).apply {
            httpsIdentity?.let { identity ->
                val trust = object : X509TrustManager {
                    override fun getAcceptedIssuers() = arrayOf(identity.certificate)
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { error("SYNTHETIC_NO_CLIENT_AUTH") }
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        require(chain.size == 1 && chain.single().encoded.contentEquals(identity.certificate.encoded))
                        chain.single().checkValidity(); chain.single().verify(identity.certificate.publicKey)
                    }
                }
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
                sslSocketFactory(context.socketFactory, trust)
                hostnameVerifier { host, session -> host == "127.0.0.1" &&
                    session.peerCertificates.single().encoded.contentEquals(identity.certificate.encoded) }
            }
        }.build()
        val roots = AtomicInteger(); val joiners = AtomicInteger(); val readyCalls = AtomicInteger()
        val beforeSetupCalls = AtomicInteger()
        var joiningObserve: (String) -> Unit = {}
        var unprotectDaily: (ByteArray) -> ByteArray = { it.reversedArray() }
        var onReady: suspend (LanSyncGroupRuntimeAccess) -> Unit = {}
        val ready = CompletableDeferred<LanSyncGroupRuntimeAccess>()
        val owners = mutableListOf<LanSyncGroupCloudRecoveryOwner>()
        private val fileOwners = mutableListOf<LanSyncGroupFileRecoveryOwner>()
        private val coordinators = mutableListOf<LanSyncGroupCoordinator>()
        private val placeholders = mutableListOf<LanSyncGroupEnrollment>()
        val coordinatorFactories = AtomicInteger(); val nearbyCalls = AtomicInteger()
        val original = LanSyncGroupAdministrationTest.Fixture(directory("original"))
        val recoveryRoot = shared.root.resolve("recovery-owner")
        val dailyRoot = shared.root.resolve("recovered-daily")
        val dailyData = shared.root.resolve("recovered-data")
        val dailyIdentityFile = shared.root.resolve("recovered-daily-identity.protected")
        private val dailyIdentityLock = Any()
        private val dailyIdentity: LanSyncTlsIdentity get() = synchronized(dailyIdentityLock) {
            if (!Files.exists(dailyIdentityFile)) {
                val encoded = LanSyncTlsIdentity.create().encode()
                try { Files.write(dailyIdentityFile, encoded.reversedArray()) } finally { encoded.fill(0) }
            }
            val encoded = Files.readAllBytes(dailyIdentityFile).reversedArray()
            try { LanSyncTlsIdentity.decode(encoded) } finally { encoded.fill(0) }
        }
        private val workspace by lazy {
            LanSyncWorkspace(Files.createDirectory(dailyData), directory("recovered-workspace"), { Path.of(it) })
        }
        private val platform = object : LanSyncPlatform {
            private var admitted = false
            override suspend fun exclusive(work: suspend () -> Unit) {
                check(!admitted); admitted = true
                try { work() } finally { admitted = false }
            }
            override suspend fun reload(sessionIds: Set<String>) { check(admitted) }
        }
        private val transport = WebDavHttpTransport(shared.server.url("/sync/").newBuilder().host("127.0.0.1").build(), client,
            { "Bearer synthetic-seed-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        private val parts = WeakWebDavChunkAssembly(transport, directory("seed-parts"), shared.route, 4096,
            WebDavIoBudget(minimumFreeBytes = 0))
        lateinit var source: LanSyncGroupSourceCommit
        lateinit var originalLedger: SyncDeviceLedgerRecord
        lateinit var originalBytes: ByteArray
        val username = "synthetic recovery user"
        val password = "synthetic recovery password"
        fun directory(name: String): Path = Files.createDirectory(shared.root.resolve(name))
        private fun joining(): LanSyncGroupJoining {
            joiners.incrementAndGet()
            Files.createDirectories(dailyRoot)
            return LanSyncGroupJoining(dailyRoot, workspace, platform, dailyIdentity,
                { it.reversedArray() }, { unprotectDaily(it) }, { _, _, _ -> }, { joiningObserve(it) })
        }
        fun owner(networkAllowed: () -> Boolean = { true },
            cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() },
            clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }, observe: (String) -> Unit = {},
            afterReady: suspend (LanSyncGroupRuntimeAccess) -> Unit = {}): LanSyncGroupCloudRecoveryOwner =
            LanSyncGroupCloudRecoveryOwner(root = {
                roots.incrementAndGet(); Files.createDirectories(recoveryRoot)
            }, joining = ::joining, deviceName = "synthetic restored daily device",
                beforeSetup = { beforeSetupCalls.incrementAndGet() }, networkAllowed = networkAllowed, cloudWork = cloudWork,
                onReady = { value -> onReady(value); afterReady(value); readyCalls.incrementAndGet(); ready.complete(value) }, client = client,
                endpointPolicy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP, clockMillis = clockMillis, observe = observe).also(owners::add)
        fun fileOwner() = LanSyncGroupFileRecoveryOwner(root = {
            roots.incrementAndGet(); Files.createDirectories(recoveryRoot)
        }, joining = ::joining, deviceName = "synthetic restored daily device",
            onReady = { value -> onReady(value); readyCalls.incrementAndGet(); ready.complete(value) }).also(fileOwners::add)
        fun retainedJoining() = joining()
        fun coordinator(): LanSyncGroupCoordinator {
            val network = object : LanSyncNetwork {
                private fun forbidden(): Nothing { nearbyCalls.incrementAndGet(); error("SYNTHETIC_PAUSED_RECOVERY_NEARBY_FORBIDDEN") }
                override fun addresses(): List<String> = forbidden()
                override fun accepts(address: InetAddress): Boolean = forbidden()
                override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): javax.net.ssl.SSLSocket = forbidden()
                override fun listening(fingerprint: String, port: Int) { forbidden() }
                override fun discover(fingerprint: String): Pair<String, Int>? = forbidden()
                override val discoveryEnabled: Boolean get() = forbidden()
            }
            return LanSyncGroupCoordinator(
                hasExistingEvidence = { Files.exists(dailyRoot.resolve("joining/profile.protected")) },
                enrollment = {
                    LanSyncGroupEnrollment(dailyRoot, workspace, platform, dailyIdentity,
                        { it.reversedArray() }, { unprotectDaily(it) }, { _, _, _ -> }).also(placeholders::add)
                }, joining = ::joining, network = network, deviceName = "synthetic restored daily device",
                initiallyPaused = true, cloudRecoveryFactory = { callback ->
                    coordinatorFactories.incrementAndGet(); owner(afterReady = callback)
                }).also(coordinators::add)
        }
        fun draft(secret: ByteArray) = LanSyncGroupCloudDraft("synthetic recovery", endpoint, username, secret, enabled = false)
        suspend fun seed() { original.seed() }
        suspend fun publishCloud() {
            val access = requireNotNull(original.service.runtimeAccess())
            suspend fun scan() {
                var cursor: CommitCursor? = null
                do { cursor = access.node.discover(access.replica, cursor).nextCursor } while (cursor != null)
            }
            scan()
            val card = original.data.resolve("entities/character_cards/card.json")
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "synthetic owner recovery body",
                basicSetting = "retained original body", createdAt = 1, updatedAt = 1))
            originalBytes = Files.readAllBytes(card)
            source = access.node.commit(access.node.inspect().review(setOf("character_cards/card")))
            access.node.upload(source.entry.request.operation, access.replica, access.localTarget); scan()
            access.targetPublication(directory("seed-target"), SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, shared.route)).upload(parts)
            originalLedger = original.custody().ledger().current()
        }
        fun assertNoDailyActivation() {
            assertFalse(Files.exists(dailyRoot.resolve("joining/node/author/origin.protected")))
            assertFalse(Files.exists(dailyRoot.resolve("joining/node/native/origin.protected")))
            if (Files.exists(dailyData)) Files.newDirectoryStream(dailyData).use { assertFalse(it.iterator().hasNext()) }
        }
        suspend fun close() {
            coordinators.forEach { it.closeAndDrain() }; placeholders.forEach { it.close() }
            owners.forEach { it.closeAndDrain() }; fileOwners.forEach { it.closeAndDrain() }
            transport.close(); original.close(); httpsServer?.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
    private fun fixture(https: Boolean = false, work: suspend (Fixture) -> Unit) = weakFixture { shared -> runBlocking {
        val f = Fixture(shared, https)
        try { f.seed(); work(f) } finally { f.close() }
    } }

    private suspend fun prepareReview(f: Fixture, owner: LanSyncGroupCloudRecoveryOwner,
        expectFreshDaily: Boolean = true): LanSyncGroupCloudRecoveryReview {
        owner.open()
        val secret = f.password.toByteArray(Charsets.UTF_8)
        try { owner.prepare(f.draft(secret), f.original.code) } finally { secret.fill(0) }
        val prepared = withTimeout(30_000) { owner.state.first { !it.busy && (it.review != null || it.error != null) } }
        assertNull(prepared.error); assertFalse(prepared.complete)
        if (expectFreshDaily) f.assertNoDailyActivation()
        return requireNotNull(prepared.review)
    }

    private fun managementState(f: Fixture): SyncRootLedgerState {
        val root = f.recoveryRoot.resolve("management")
        return SyncManagementSession.open(f.original.code, Files.readAllBytes(root.resolve("management-vault.bin"))).use { session ->
            SyncRootLedgerJournal(root.resolve("root-actions"), session.trust, session.ownerCertificate,
                session::protectJournal, session::unprotectJournal).state()
        }
    }

    private fun managementEntry(f: Fixture, operation: SyncStoreId): SyncRootLedgerEntry {
        val root = f.recoveryRoot.resolve("management")
        return SyncManagementSession.open(f.original.code, Files.readAllBytes(root.resolve("management-vault.bin"))).use { session ->
            requireNotNull(SyncRootLedgerJournal(root.resolve("root-actions"), session.trust, session.ownerCertificate,
                session::protectJournal, session::unprotectJournal).entry(operation))
        }
    }

    /** Read the actual retained intent without repairing the missing index/head crash window. */
    private fun retainedRootIntent(f: Fixture, file: Path): SyncRootLedgerEntry {
        val root = f.recoveryRoot.resolve("management")
        return SyncManagementSession.open(f.original.code, Files.readAllBytes(root.resolve("management-vault.bin"))).use { session ->
            val plain = session.unprotectJournal(Files.readAllBytes(file))
            try {
                val frame = lanSyncJson.parseToJsonElement(lanSyncUtf8(plain)).jsonObject
                assertEquals("INTENT", frame.getValue("kind").jsonPrimitive.content)
                lanSyncJson.decodeFromJsonElement(SyncRootLedgerEntry.serializer(), frame.getValue("payload"))
            } finally { plain.fill(0) }
        }
    }

    private fun fileBytes(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) }
    }

    private fun assertFilesUnchanged(expected: Map<String, ByteArray>, root: Path) {
        val actual = fileBytes(root)
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertContentEquals(bytes, actual.getValue(name), name) }
    }

    private suspend fun resumeApprovedRootAcrossBackends(f: Fixture, fromCloud: Boolean, point: String) {
        f.publishCloud()
        val originalAccess = requireNotNull(f.original.service.runtimeAccess())
        val packageFile = f.shared.root.resolve("approved-root-switch.cbgroup")
        val descriptor = originalAccess.prepareEncryptedFile(f.directory("approved-root-switch-export"), {}).use { prepared ->
            Files.newOutputStream(packageFile).use { prepared.writeTo(it) }
            prepared.descriptor
        }
        f.original.close()
        val originalBefore = fileBytes(f.original.root)
        val providerPuts = f.shared.dav.puts.toList()
        val firstCloud = if (fromCloud) f.owner() else null
        val firstFile = if (fromCloud) null else f.fileOwner()
        val firstDevice = if (firstCloud != null) {
            val initial = prepareReview(f, firstCloud)
            assertTrue(f.recoveryRequests > 0)
            firstCloud.lock(); firstCloud.closeAndDrain()
            initial.device
        } else {
            val owner = requireNotNull(firstFile)
            owner.open(); owner.prepare({ Files.newInputStream(packageFile) }, f.original.code)
            val state = withTimeout(30_000) { owner.state.first { !it.busy && (it.review != null || it.error != null) } }
            assertNull(state.error); assertFalse(state.complete)
            val initial = requireNotNull(state.review)
            assertEquals(0, f.recoveryRequests)
            owner.lock(); owner.closeAndDrain()
            initial.device
        }
        // The first UI preparation was never confirmed. This separate real management call
        // explicitly acknowledges its own typed proof before faulting the actual root protocol.
        assertEquals(0L, managementState(f).completedSequence)
        assertNull(managementState(f).pendingOperation)
        val daily = f.retainedJoining()
        val member = requireNotNull(daily.recoveryMember())
        assertEquals(firstDevice, member.first)
        assertEquals(LanSyncGroupJoinStage.PREPARED, requireNotNull(daily.profile()).stage)
        f.assertNoDailyActivation()
        val managementRoot = f.recoveryRoot.resolve("management")
        val actions = managementRoot.resolve("root-actions")
        val origin = Files.readAllBytes(actions.resolve("origin.protected"))
        val recipient = fileBytes(f.dailyRoot.resolve("joining/recipient"))
        val identity = Files.readAllBytes(f.dailyIdentityFile)
        val dailyProfile = Files.readAllBytes(f.dailyRoot.resolve("joining/profile.protected"))
        val oldDelivery = fileBytes(managementRoot.resolve("root-delivery"))
        val interrupted = AtomicBoolean()
        val management = LanSyncGroupManagementRecovery(managementRoot, observe = {
            if (it == point && interrupted.compareAndSet(false, true)) throw IOException("SYNTHETIC_APPROVED_ROOT_BACKEND_SWITCH")
        })
        val approved = try {
            management.unlock(f.original.code)
            val parent = management.ledger().current()
            assertEquals(f.originalLedger, parent)
            val preview = daily.prepareRecoveryDevice(management, "synthetic restored daily device")
            assertEquals(member.first, preview.device); assertEquals(member.second, preview.proof.member)
            assertEquals(parent.reference(), preview.request.expectedParent)
            assertEquals(parent.members + member, preview.request.members)
            assertEquals(parent.coveredTypes, preview.request.coveredTypes)
            assertEquals(parent.acceptedHeads, preview.request.acceptedHeads); assertFalse(preview.request.rotateKey)
            assertFailsWith<IOException> { management.addRecoveryDevice(preview.acknowledge(preview.request.approvalBinding)) }
            assertTrue(interrupted.get(), point)
            preview
        } finally { management.close() }
        val retained = fileBytes(actions).filterKeys { it.startsWith("intent-") || it.startsWith("signed-") || it.startsWith("operation-") }
        val intentName = retained.keys.single { it.startsWith("intent-") }
        val intent = retainedRootIntent(f, actions.resolve(intentName))
        assertEquals(approved.request, intent.request)
        assertEquals(f.originalLedger.reference(), intent.record.parent)
        assertEquals(f.originalLedger.sequence + 1, intent.record.sequence)
        assertEquals(f.originalLedger.members + member, intent.record.members)
        assertEquals(f.originalLedger.epoch, intent.record.epoch)
        assertEquals(f.originalLedger.keyCommitment, intent.record.keyCommitment)
        val signed = retained.keys.filter { it.startsWith("signed-") }
        assertEquals(if (point == "APPROVED_ROOT_INTENT_RETAINED") 0 else 1, signed.size)
        assertEquals(if (point == "APPROVED_ROOT_INTENT_RETAINED") 0 else 1, retained.keys.count { it.startsWith("operation-") })
        if (point != "ROOT_DELIVERY_SPEC_RETAINED") assertFalse(Files.exists(actions.resolve("head.protected")))
        else {
            val spec = "ledger-${intent.record.id.value}.json"
            assertFalse(spec in oldDelivery)
            assertTrue(spec in fileBytes(managementRoot.resolve("root-delivery")), "The fault must be the newly approved root's delivery spec")
        }
        assertContentEquals(origin, Files.readAllBytes(actions.resolve("origin.protected")))
        assertFilesUnchanged(recipient, f.dailyRoot.resolve("joining/recipient"))
        assertContentEquals(identity, Files.readAllBytes(f.dailyIdentityFile))
        assertContentEquals(dailyProfile, Files.readAllBytes(f.dailyRoot.resolve("joining/profile.protected")))
        assertEquals(0, f.readyCalls.get()); f.assertNoDailyActivation()
        val requests = f.recoveryRequests; val seedRequests = f.shared.server.requestCount
        val coldFile = if (fromCloud) f.fileOwner() else null
        val coldCloud = if (fromCloud) null else f.owner()
        val binding = if (coldFile != null) {
            coldFile.open(); coldFile.prepare({ Files.newInputStream(packageFile) }, f.original.code)
            val state = withTimeout(30_000) { coldFile.state.first { !it.busy && (it.review != null || it.error != null) } }
            assertNull(state.error); assertFalse(state.complete)
            requireNotNull(state.review).also { assertEquals(member.first, it.device) }.binding
        } else prepareReview(f, requireNotNull(coldCloud)).also { assertEquals(member.first, it.device) }.binding
        val resumed = managementState(f)
        assertEquals(1L, resumed.completedSequence); assertNull(resumed.pendingOperation)
        assertEquals(intent.record.reference(), resumed.last)
        val entry = managementEntry(f, approved.request.operation)
        assertEquals(approved.request, entry.request); assertEquals(intent.record.reference(), entry.record.reference())
        assertTrue(SyncDeviceSignatures.decode(originalAccess.trust.ownerCertificate).verifies(entry.record.signingBytes(), entry.record.signature))
        retained.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(actions.resolve(name)), name) }
        assertContentEquals(origin, Files.readAllBytes(actions.resolve("origin.protected")))
        assertFilesUnchanged(recipient, f.dailyRoot.resolve("joining/recipient"))
        assertContentEquals(identity, Files.readAllBytes(f.dailyIdentityFile))
        assertContentEquals(dailyProfile, Files.readAllBytes(f.dailyRoot.resolve("joining/profile.protected")))
        f.assertNoDailyActivation(); assertEquals(0, f.readyCalls.get())
        val resumedActions = fileBytes(actions)
        if (coldFile != null) coldFile.confirm(binding) else requireNotNull(coldCloud).confirm(binding)
        if (coldFile != null) {
            val state = withTimeout(30_000) { coldFile.state.first { !it.busy && (it.complete || it.error != null) } }
            withTimeout(10_000) { coldFile.drain() }
            assertNull(state.error, point); assertTrue(state.complete, point); assertFalse(state.pending)
            assertEquals(requests, f.recoveryRequests)
        } else {
            val owner = requireNotNull(coldCloud)
            val state = withTimeout(30_000) { owner.state.first { !it.busy && (it.complete || it.error != null) } }
            withTimeout(10_000) { owner.drain() }
            assertNull(state.error, point); assertTrue(state.complete, point)
            assertTrue(f.recoveryRequests > requests)
        }
        val access = withTimeout(5_000) { f.ready.await() }
        assertEquals(seedRequests, f.shared.server.requestCount)
        assertEquals(providerPuts, f.shared.dav.puts.toList())
        assertEquals(resumed, managementState(f)); assertFilesUnchanged(resumedActions, actions)
        assertEquals(entry.record, access.ledger().current())
        assertEquals(member.first, access.device); assertEquals(member.second.certificate, access.certificate)
        assertEquals(member.second, access.ledger().current().members.getValue(access.device))
        assertFilesUnchanged(recipient, f.dailyRoot.resolve("joining/recipient"))
        assertContentEquals(identity, Files.readAllBytes(f.dailyIdentityFile))
        assertFalse(Files.exists(access.work.resolve("cloud-connections")))
        val card = f.dailyData.resolve("entities/character_cards/card.json")
        val restored = requireNotNull(LanSyncJsonFiles.read(card, CharacterCard.serializer()))
        assertEquals("synthetic owner recovery body", restored.name); assertEquals("retained original body", restored.basicSetting)
        val expectedHash = f.source.entry.version.changes.getValue("character_cards/card").valueHash
        assertEquals(expectedHash, access.node.inspect().nativeState.units.getValue("character_cards/card").valueHash)
        val nativeBytes = fileBytes(f.dailyData)
        val origins = listOf("author", "native").associateWith { Files.readAllBytes(f.dailyRoot.resolve("joining/node/$it/origin.protected")) }
        val coldJoining = f.retainedJoining()
        assertEquals(LanSyncGroupJoinStage.READY, requireNotNull(coldJoining.profile()).stage)
        val coldAccess = requireNotNull(coldJoining.runtimeAccess())
        assertEquals(access.device, coldAccess.device); assertEquals(access.certificate, coldAccess.certificate)
        assertEquals(entry.record, coldAccess.ledger().current())
        assertEquals(expectedHash, coldAccess.node.inspect().nativeState.units.getValue("character_cards/card").valueHash)
        val author = SyncAuthorJournal(f.dailyRoot.resolve("joining/node/author"), coldAccess.trust, coldAccess.certificate,
            { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence); assertTrue(coldAccess.node.sourceOperations().isEmpty())
        origins.forEach { (kind, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.dailyRoot.resolve("joining/node/$kind/origin.protected"))) }
        assertFilesUnchanged(nativeBytes, f.dailyData); assertFilesUnchanged(originalBefore, f.original.root)
        Files.newInputStream(packageFile).use { LanSyncGroupEncryptedFilePackage.verifyReadback(it, descriptor) }
        assertEquals(1, f.readyCalls.get()); assertEquals(resumed, managementState(f))
    }

    @Test fun anApprovedCloudRootIntentColdResumesFromFileWithoutAnotherRootOperation() = fixture(https = true) {
        resumeApprovedRootAcrossBackends(it, fromCloud = true, point = "APPROVED_ROOT_INTENT_RETAINED")
    }
    @Test fun aRetainedCloudRootSignatureColdResumesFromFileWithoutResigning() = fixture(https = true) {
        resumeApprovedRootAcrossBackends(it, fromCloud = true, point = "ROOT_SIGNED_ENTRY_RETAINED")
    }
    @Test fun anInterruptedCloudRootLocalPublicationColdResumesFromFileWithoutAnotherRootOperation() = fixture(https = true) {
        resumeApprovedRootAcrossBackends(it, fromCloud = true, point = "ROOT_DELIVERY_SPEC_RETAINED")
    }
    @Test fun anApprovedFileRootIntentColdResumesFromCloudWithoutAnotherRootOperation() = fixture(https = true) {
        resumeApprovedRootAcrossBackends(it, fromCloud = false, point = "APPROVED_ROOT_INTENT_RETAINED")
    }
    @Test fun aRetainedFileRootSignatureColdResumesFromCloudWithoutResigning() = fixture(https = true) {
        resumeApprovedRootAcrossBackends(it, fromCloud = false, point = "ROOT_SIGNED_ENTRY_RETAINED")
    }
    @Test fun anInterruptedFileRootLocalPublicationColdResumesFromCloudWithoutAnotherRootOperation() = fixture(https = true) {
        resumeApprovedRootAcrossBackends(it, fromCloud = false, point = "ROOT_DELIVERY_SPEC_RETAINED")
    }

    @Test fun confirmedDataTransferContinuesPastFiveMinutesAfterManagementHasLocked() = fixture { f ->
        f.publishCloud()
        val clock = AtomicLong(); val locked = AtomicBoolean(); val dataGets = AtomicInteger()
        f.shared.dav.overrideRequest = { request ->
            if (clock.get() > 300_000 && request.method == "GET") dataGets.incrementAndGet()
            null
        }
        lateinit var owner: LanSyncGroupCloudRecoveryOwner
        owner = f.owner(clockMillis = clock::get, observe = { point ->
            if (point == "CLOUD_RECOVERY_MANAGEMENT_LOCKED") locked.set(true)
            if (point == "CLOUD_RECOVERY_DATA_PHASE") {
                check(locked.get()); clock.addAndGet(512_000); owner.hide()
            }
        })
        val review = prepareReview(f, owner)
        owner.confirm(review.binding)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { owner.drain() }
        assertTrue(locked.get()); assertEquals(512_000L, clock.get()); assertTrue(dataGets.get() > 0)
        assertTrue(owner.state.value.complete); assertNull(owner.state.value.error)
        assertFalse(owner.state.value.visible, "Finishing the hidden app-owned batch must not reopen the private form")
        assertEquals("synthetic owner recovery body", JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single().name)
        assertEquals(review.device, access.device); assertEquals(1L, managementState(f).completedSequence)
        val author = SyncAuthorJournal(f.dailyRoot.resolve("joining/node/author"), access.trust, access.certificate,
            { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence)
    }

    @Test fun anUnconfirmedReviewStillExpiresInsteadOfGrantingLongLivedManagement() = fixture { f ->
        f.publishCloud()
        val clock = AtomicLong(); val owner = f.owner(clockMillis = clock::get)
        val review = prepareReview(f, owner)
        assertEquals(0L, managementState(f).completedSequence)
        clock.set(300_001)
        owner.confirm(review.binding)
        withTimeout(5_000) { owner.drain() }
        assertFalse(owner.state.value.complete); assertNull(owner.state.value.review)
        assertNotNull(owner.state.value.error); assertEquals(0, f.readyCalls.get())
        assertEquals(0L, managementState(f).completedSequence); f.assertNoDailyActivation()
    }

    @Test fun stoppingDataAfterFiveMinutesDrainsHttpAndColdRetryReusesTheOriginalRootApproval() = fixture { f ->
        f.publishCloud()
        val clock = AtomicLong(); val entered = CountDownLatch(1); val blocked = AtomicBoolean()
        f.shared.dav.overrideRequest = { request ->
            if (clock.get() > 300_000 && request.method == "GET" && blocked.compareAndSet(false, true)) {
                entered.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            } else null
        }
        val first = f.owner(clockMillis = clock::get, observe = { point ->
            if (point == "CLOUD_RECOVERY_DATA_PHASE") clock.addAndGet(512_000)
        })
        val review = prepareReview(f, first); first.confirm(review.binding)
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        first.hide()
        assertFalse(first.state.value.visible); assertTrue(first.state.value.busy); assertNull(first.state.value.review)
        first.lock(); withTimeout(5_000) { first.drain() }
        assertFalse(first.state.value.visible); assertEquals(0, f.readyCalls.get()); f.assertNoDailyActivation()
        val approved = managementState(f); assertEquals(1L, approved.completedSequence)
        val origin = Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected"))
        first.closeAndDrain(); f.shared.dav.overrideRequest = null
        val second = f.owner(clockMillis = clock::get); val resumed = prepareReview(f, second)
        assertEquals(review.device, resumed.device); assertEquals(approved, managementState(f))
        second.confirm(resumed.binding)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { second.drain() }
        assertTrue(second.state.value.complete); assertEquals(review.device, access.device)
        assertEquals(approved, managementState(f))
        assertContentEquals(origin, Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected")))
    }

    @Test fun anExplicitReadyConfirmationSavesAndEnablesTheProtectedConnectionBeforeOnReady() = fixture(https = true) { f ->
        f.publishCloud()
        val atReady = CompletableDeferred<List<LanSyncGroupCloudRoute>>()
        f.onReady = { access -> atReady.complete(access.cloudRoutes().rows()) }
        val owner = f.owner(); val review = prepareReview(f, owner)
        val connectionRoot = f.dailyRoot.resolve("joining/app-runtime/cloud-connections")
        assertFalse(Files.exists(connectionRoot), "Preparation must not persist the optional connection")
        val puts = f.shared.dav.puts.toList()
        owner.confirm(review.binding, saveConnection = true)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { owner.drain() }
        val completed = owner.state.value
        assertTrue(completed.complete); assertNull(completed.error)
        assertEquals(true, completed.connectionEnabled); assertNotNull(completed.connectionNotice)
        val row = withTimeout(5_000) { atReady.await() }.single()
        assertTrue(row.enabled); assertEquals(f.endpoint, row.endpoint); assertEquals(f.username, row.username)
        assertEquals("synthetic recovery", row.name); assertEquals(listOf(row), access.cloudRoutes().rows())
        access.cloudRoutes().credentials(row.id).use { credentials ->
            credentials.withPassword { assertContentEquals(f.password.toByteArray(Charsets.UTF_8), it) }
        }
        val protected = Files.readAllBytes(connectionRoot.resolve("cloud-routes.protected")).toString(Charsets.UTF_8)
        assertFalse(protected.contains(f.password)); assertFalse(protected.contains(f.original.code))
        assertFalse(protected.contains(java.util.Base64.getEncoder().encodeToString(f.password.toByteArray(Charsets.UTF_8))))
        assertEquals(puts, f.shared.dav.puts.toList(), "Saving an enabled local connection is not a provider delivery receipt")
        assertEquals("synthetic owner recovery body", JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single().name)
        assertEquals(1, f.readyCalls.get())
    }

    @Test fun aLocalConnectionWriteFailureCannotUndoReadyRecoveryOrPretendTheConnectionIsEnabled() = fixture(https = true) { f ->
        f.publishCloud()
        val blocked = f.dailyRoot.resolve("joining/app-runtime/cloud-connections")
        val retained = "synthetic existing non-directory evidence".toByteArray(Charsets.UTF_8)
        f.joiningObserve = { point -> if (point == "GROUP_JOIN_READY") {
            Files.createDirectories(blocked.parent); Files.write(blocked, retained)
        } }
        val owner = f.owner(); val review = prepareReview(f, owner)
        assertFalse(Files.exists(blocked))
        owner.confirm(review.binding, saveConnection = true)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { owner.drain() }
        val completed = owner.state.value
        assertTrue(completed.complete); assertNull(completed.error)
        assertEquals(false, completed.connectionEnabled); assertNotNull(completed.connectionNotice)
        assertContentEquals(retained, Files.readAllBytes(blocked))
        assertEquals(1, f.readyCalls.get()); assertTrue(access.ledger().current().members.getValue(access.device).active)
        assertEquals("synthetic owner recovery body", JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single().name)
        val author = SyncAuthorJournal(f.dailyRoot.resolve("joining/node/author"), access.trust, access.certificate,
            { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence)
    }

    @Test fun openingDeniedAndUnpromotedRecoveryNeverCreatePrivateOrDailyOwnersOrUseHttp() = fixture { f ->
        val denied = f.owner(networkAllowed = { false })
        denied.open()
        assertTrue(denied.state.value.visible)
        assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get()); assertEquals(0, f.beforeSetupCalls.get())
        assertFalse(Files.exists(f.recoveryRoot)); assertFalse(Files.exists(f.dailyRoot))
        var secret = f.password.toByteArray(Charsets.UTF_8)
        try { denied.prepare(f.draft(secret), f.original.code) } finally { secret.fill(0) }
        withTimeout(5_000) { denied.drain() }
        assertNotNull(denied.state.value.error); assertFalse(denied.state.value.complete)
        assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get()); assertEquals(0, f.shared.server.requestCount)

        val protectedCalls = AtomicInteger()
        val unpromoted = f.owner(cloudWork = { protectedCalls.incrementAndGet(); false })
        unpromoted.open()
        secret = f.password.toByteArray(Charsets.UTF_8)
        try { unpromoted.prepare(f.draft(secret), f.original.code) } finally { secret.fill(0) }
        withTimeout(5_000) { unpromoted.drain() }
        assertEquals(1, protectedCalls.get()); assertNotNull(unpromoted.state.value.error)
        assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get()); assertEquals(0, f.beforeSetupCalls.get())
        assertEquals(0, f.shared.server.requestCount); assertEquals(0, f.readyCalls.get())
        assertFalse(Files.exists(f.recoveryRoot)); assertFalse(Files.exists(f.dailyRoot))
    }

    @Test fun synchronousPasswordCopySurvivesUiErasureAndOnlyExplicitConfirmationAdmitsTheRealDailyOwner() = fixture { f ->
        f.publishCloud()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val gateCalls = AtomicInteger(); val wrongAuthentication = AtomicInteger()
        val expectedAuthorization = Credentials.basic(f.username, f.password, Charsets.UTF_8)
        f.shared.dav.overrideRequest = { request ->
            if (request.getHeader("Authorization") != expectedAuthorization) {
                wrongAuthentication.incrementAndGet(); MockResponse().setResponseCode(401)
            } else null
        }
        val owner = f.owner(cloudWork = { work ->
            if (gateCalls.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            work()
        })
        owner.open()
        val requests = f.shared.server.requestCount; val puts = f.shared.dav.puts.toList()
        val secret = f.password.toByteArray(Charsets.UTF_8)
        try { owner.prepare(f.draft(secret), f.original.code) } finally { secret.fill(0) }
        assertTrue(secret.all { it == 0.toByte() })
        withTimeout(5_000) { entered.await() }
        assertEquals(requests, f.shared.server.requestCount)
        assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get())
        release.complete(Unit)
        val prepared = withTimeout(30_000) { owner.state.first { !it.busy && (it.review != null || it.error != null) } }
        assertNull(prepared.error)
        val review = requireNotNull(prepared.review)
        assertFalse(prepared.complete); assertEquals(0, f.readyCalls.get())
        assertEquals(f.originalLedger.group.value, review.group)
        assertEquals(f.originalLedger.members.size, review.memberCount)
        assertEquals("synthetic restored daily device", review.name)
        assertNotEquals(f.original.identity.fingerprint, review.device)
        assertEquals(f.originalLedger, f.original.custody().ledger().current())
        f.assertNoDailyActivation()
        listOf(owner.toString(), prepared.toString(), review.toString()).forEach {
            assertFalse(it.contains(f.original.code)); assertFalse(it.contains(f.password)); assertFalse(it.contains(f.username))
        }
        owner.confirm(review.binding)
        val access = withTimeout(30_000) { f.ready.await() }
        val completed = withTimeout(5_000) { owner.state.first { !it.busy && (it.complete || it.error != null) } }
        assertNull(completed.error); assertTrue(completed.complete)
        assertNull(completed.connectionEnabled); assertNull(completed.connectionNotice)
        assertFalse(Files.exists(access.work.resolve("cloud-connections")), "The one-argument confirmation only restores the group")
        assertEquals(1, f.readyCalls.get()); assertEquals(review.device, access.device)
        assertEquals(f.originalLedger.group, access.trust.group); assertEquals(f.originalLedger.reference(), access.ledger().history().dropLast(1).last().reference())
        val card = JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single()
        assertEquals("synthetic owner recovery body", card.name); assertEquals("retained original body", card.basicSetting)
        val author = SyncAuthorJournal(f.dailyRoot.resolve("joining/node/author"), access.trust, access.certificate,
            { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence); assertTrue(access.node.sourceOperations().isEmpty())
        assertEquals(puts, f.shared.dav.puts.toList(), "Recovery does not claim that its new local root approval was published to the cloud")
        assertEquals(0, wrongAuthentication.get())
        assertContentEquals(f.originalBytes, Files.readAllBytes(f.original.data.resolve("entities/character_cards/card.json")))
        owner.lock(); withTimeout(5_000) { owner.drain() }
        assertFalse(owner.state.value.visible); assertEquals(1, f.readyCalls.get())
        val requestsAfterReady = f.shared.server.requestCount; val rootsAfterReady = f.roots.get()
        val readyLedger = access.ledger().current()
        val reopened = f.owner(); reopened.open()
        val again = f.password.toByteArray(Charsets.UTF_8)
        try { reopened.prepare(f.draft(again), f.original.code) } finally { again.fill(0) }
        withTimeout(5_000) { reopened.drain() }
        assertFalse(reopened.state.value.complete); assertEquals(1, f.readyCalls.get())
        val resumed = requireNotNull(reopened.state.value.review)
        assertEquals(access.device, resumed.device)
        reopened.confirm(resumed.binding, saveConnection = false)
        withTimeout(5_000) { reopened.drain() }
        assertTrue(reopened.state.value.complete); assertEquals(2, f.readyCalls.get())
        assertEquals(rootsAfterReady, f.roots.get()); assertEquals(requestsAfterReady, f.shared.server.requestCount)
        assertEquals(readyLedger, access.ledger().current()); assertEquals(0L, author.state().committedSequence)
    }

    @Test fun coldReadyRetryFinishesNativeDataAndReconfirmsConnectionWithoutAnotherRootSignature() = fixture(https = true) { f ->
        f.publishCloud()
        val interrupted = AtomicBoolean()
        f.joiningObserve = { point -> if (point == "GROUP_JOIN_READY" && interrupted.compareAndSet(false, true))
            throw IOException("SYNTHETIC_READY_BEFORE_NATIVE_AND_CONNECTION") }
        val first = f.owner(); val review = prepareReview(f, first)
        first.confirm(review.binding, saveConnection = true)
        withTimeout(30_000) { first.drain() }
        assertTrue(interrupted.get()); assertFalse(first.state.value.complete)
        assertNotNull(first.state.value.error); assertEquals(0, f.readyCalls.get())
        assertTrue(JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).isEmpty())
        val approved = managementState(f)
        val origin = Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected"))
        val dailyOrigin = Files.readAllBytes(f.dailyRoot.resolve("joining/node/author/origin.protected"))
        assertEquals(1L, approved.completedSequence)
        first.closeAndDrain(); f.joiningObserve = {}
        val roots = f.roots.get(); val requests = f.shared.server.requestCount
        val second = f.owner(); val resumed = prepareReview(f, second, expectFreshDaily = false)
        assertEquals(review.device, resumed.device); assertEquals(0, f.readyCalls.get())
        assertFalse(second.state.value.complete); assertNull(second.state.value.connectionEnabled)
        assertEquals(roots, f.roots.get()); assertEquals(requests, f.shared.server.requestCount)
        assertEquals(approved, managementState(f))
        assertContentEquals(dailyOrigin, Files.readAllBytes(f.dailyRoot.resolve("joining/node/author/origin.protected")))
        second.confirm(resumed.binding, saveConnection = true)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { second.drain() }
        assertTrue(second.state.value.complete); assertNull(second.state.value.error)
        assertEquals(true, second.state.value.connectionEnabled)
        assertTrue(access.cloudRoutes().rows().single().enabled)
        assertEquals("synthetic owner recovery body", JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single().name)
        assertEquals(approved, managementState(f))
        assertContentEquals(origin, Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected")))
        val author = SyncAuthorJournal(f.dailyRoot.resolve("joining/node/author"), access.trust, access.certificate,
            { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence)
        assertEquals(review.device, access.device)
    }

    @Test fun coordinatorReadyLoadKeepsTheInterruptedExternalRecoveryEntryAndExplicitConfirmationFinishesIt() = fixture(https = true) { f ->
        f.publishCloud()
        val atReady = CompletableDeferred<List<LanSyncGroupCloudRoute>>()
        f.onReady = { access -> atReady.complete(access.cloudRoutes().rows()) }
        val interrupted = AtomicBoolean()
        f.joiningObserve = { point -> if (point == "GROUP_JOIN_READY" && interrupted.compareAndSet(false, true))
            throw IOException("SYNTHETIC_COORDINATOR_READY_BEFORE_NATIVE_AND_CONNECTION") }
        val coordinator = f.coordinator()
        assertEquals(0, f.coordinatorFactories.get()); assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get())
        coordinator.openCloudRecovery()
        val owner = requireNotNull(coordinator.cloudRecovery)
        assertEquals(1, f.coordinatorFactories.get()); assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get())
        suspend fun prepareThroughEntry(): LanSyncGroupCloudRecoveryReview {
            val secret = f.password.toByteArray(Charsets.UTF_8)
            try { assertTrue(coordinator.prepareCloudRecovery(f.draft(secret), f.original.code)) }
            finally { secret.fill(0) }
            val state = withTimeout(30_000) { owner.state.first { !it.busy && (it.review != null || it.error != null) } }
            assertNull(state.error); assertFalse(state.complete)
            return requireNotNull(state.review)
        }
        val initial = prepareThroughEntry()
        f.assertNoDailyActivation()
        owner.confirm(initial.binding, saveConnection = true)
        withTimeout(30_000) { owner.drain() }
        assertTrue(interrupted.get()); assertFalse(owner.state.value.complete); assertNotNull(owner.state.value.error)
        assertEquals(0, f.readyCalls.get())
        val storage = JsonFileStorage(f.dailyData)
        assertTrue(storage.loadAll("character_cards", CharacterCard.serializer()).isEmpty())
        val approved = managementState(f)
        assertEquals(1L, approved.completedSequence); assertNull(approved.pendingOperation)
        val managementOrigin = Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected"))
        val dailyOrigins = listOf("author", "native").associateWith {
            Files.readAllBytes(f.dailyRoot.resolve("joining/node/$it/origin.protected"))
        }
        owner.lock(); owner.drain()
        val requestsBeforeLoad = f.recoveryRequests; val rootsBeforeLoad = f.roots.get()
        coordinator.open()
        val loaded = withTimeout(20_000) { coordinator.state.first { it.stage == LanSyncGroupPanelStage.READY && !it.busy } }
        assertNull(loaded.error); assertTrue(loaded.paused); assertTrue(loaded.canResumeExternalRecovery)
        assertTrue(storage.loadAll("character_cards", CharacterCard.serializer()).isEmpty(), "Read-only READY load must not apply the pending original")
        assertEquals(0, f.nearbyCalls.get()); assertEquals(approved, managementState(f))
        assertEquals(requestsBeforeLoad, f.recoveryRequests)
        dailyOrigins.forEach { (kind, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.dailyRoot.resolve("joining/node/$kind/origin.protected"))) }

        f.joiningObserve = {}
        coordinator.openCloudRecovery()
        assertSame(owner, coordinator.cloudRecovery)
        val resumed = prepareThroughEntry()
        assertEquals(initial.device, resumed.device); assertNotEquals(initial.binding, resumed.binding)
        assertEquals(approved, managementState(f)); assertEquals(0, f.readyCalls.get())
        assertEquals(rootsBeforeLoad, f.roots.get()); assertEquals(requestsBeforeLoad, f.recoveryRequests)
        owner.confirm(resumed.binding, saveConnection = true)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(10_000) { owner.drain() }
        assertTrue(owner.state.value.complete); assertNull(owner.state.value.error); assertEquals(true, owner.state.value.connectionEnabled)
        assertTrue(coordinator.state.value.paused); assertTrue(coordinator.state.value.canResumeExternalRecovery)
        assertEquals(LanSyncGroupPanelStage.READY, coordinator.state.value.stage)
        val restored = storage.loadAll("character_cards", CharacterCard.serializer()).single()
        assertEquals("synthetic owner recovery body", restored.name); assertEquals("retained original body", restored.basicSetting)
        val row = access.cloudRoutes().rows().single()
        assertTrue(row.enabled); assertEquals(f.endpoint, row.endpoint); assertEquals(f.username, row.username)
        assertEquals(listOf(row), withTimeout(5_000) { atReady.await() }, "The enabled row must already exist at the actual ready callback")
        access.cloudRoutes().credentials(row.id).use { credentials ->
            credentials.withPassword { assertContentEquals(f.password.toByteArray(Charsets.UTF_8), it) }
        }
        val protected = Files.readAllBytes(access.work.resolve("cloud-connections/cloud-routes.protected")).toString(Charsets.UTF_8)
        assertFalse(protected.contains(f.password)); assertFalse(protected.contains(f.original.code))
        assertEquals(approved, managementState(f))
        assertContentEquals(managementOrigin, Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected")))
        dailyOrigins.forEach { (kind, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.dailyRoot.resolve("joining/node/$kind/origin.protected"))) }
        val author = SyncAuthorJournal(f.dailyRoot.resolve("joining/node/author"), access.trust, access.certificate,
            { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence); assertTrue(access.node.sourceOperations().isEmpty())
        assertEquals(1, f.readyCalls.get()); assertEquals(1, f.coordinatorFactories.get()); assertEquals(0, f.nearbyCalls.get())
        assertContentEquals(f.originalBytes, Files.readAllBytes(f.original.data.resolve("entities/character_cards/card.json")))
    }

    @Test fun aCloudPreparedRecoveryContinuesFromAnActualFileUsingTheSameManagementOriginAndDailyKeysWithoutHttp() = fixture(https = true) { f ->
        f.publishCloud()
        val originalAccess = requireNotNull(f.original.service.runtimeAccess())
        val packageFile = f.shared.root.resolve("same-original-group.cbgroup")
        originalAccess.prepareEncryptedFile(f.directory("same-group-file-export"), {}).use { prepared ->
            Files.newOutputStream(packageFile).use { prepared.writeTo(it) }
            Files.newInputStream(packageFile).use { LanSyncGroupEncryptedFilePackage.verifyReadback(it, prepared.descriptor) }
        }
        val packageBytes = Files.readAllBytes(packageFile)
        val cloud = f.owner()
        val cloudReview = prepareReview(f, cloud)
        assertTrue(f.recoveryRequests > 0, "The first review must have used the actual synthetic HTTPS provider")
        val before = managementState(f)
        assertEquals(0L, before.completedSequence); assertNull(before.pendingOperation)
        val managementOrigin = Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected"))
        val daily = f.retainedJoining()
        assertEquals(LanSyncGroupJoinStage.PREPARED, requireNotNull(daily.profile()).stage)
        val member = requireNotNull(daily.recoveryMember())
        assertEquals(cloudReview.device, member.first)
        fun recipientFiles(): Map<String, ByteArray> = Files.walk(f.dailyRoot.resolve("joining/recipient")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().associate { path ->
                f.dailyRoot.resolve("joining/recipient").relativize(path).toString() to Files.readAllBytes(path)
            }
        }
        val recipient = recipientFiles()
        assertTrue(recipient.isNotEmpty()); f.assertNoDailyActivation()
        cloud.lock(); withTimeout(5_000) { cloud.drain() }
        assertFalse(cloud.state.value.visible); assertFalse(cloud.state.value.busy); assertEquals(0, f.readyCalls.get())
        val providerRequests = f.recoveryRequests; val seedRequests = f.shared.server.requestCount
        val providerPuts = f.shared.dav.puts.toList()

        val file = f.fileOwner()
        file.open(); file.prepare({ Files.newInputStream(packageFile) }, f.original.code)
        val reviewed = withTimeout(30_000) { file.state.first { !it.busy && (it.review != null || it.error != null) } }
        assertNull(reviewed.error); assertFalse(reviewed.complete)
        val fileReview = requireNotNull(reviewed.review)
        assertEquals(cloudReview.group, fileReview.group); assertEquals(cloudReview.rootFingerprint, fileReview.rootFingerprint)
        assertEquals(member.first, fileReview.device); assertEquals(member, daily.recoveryMember())
        assertEquals(before, managementState(f))
        assertContentEquals(managementOrigin, Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected")))
        f.assertNoDailyActivation()
        assertEquals(providerRequests, f.recoveryRequests); assertEquals(seedRequests, f.shared.server.requestCount)
        file.confirm(fileReview.binding)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(10_000) { file.drain() }
        assertTrue(file.state.value.complete); assertNull(file.state.value.error); assertFalse(file.state.value.pending)
        val after = managementState(f)
        assertEquals(1L, after.completedSequence); assertNull(after.pendingOperation)
        assertContentEquals(managementOrigin, Files.readAllBytes(f.recoveryRoot.resolve("management/root-actions/origin.protected")))
        assertEquals(member.first, access.device); assertEquals(member.second.certificate, access.certificate)
        assertEquals(member.second, access.ledger().current().members.getValue(access.device))
        assertEquals(f.originalLedger.sequence + 1, access.ledger().current().sequence)
        assertEquals(f.originalLedger.members.size + 1, access.ledger().current().members.size)
        assertEquals(LanSyncGroupJoinStage.READY, requireNotNull(daily.profile()).stage)
        val retainedRecipient = recipientFiles()
        assertEquals(recipient.keys, retainedRecipient.keys)
        recipient.forEach { (path, bytes) -> assertContentEquals(bytes, retainedRecipient.getValue(path), path) }
        val author = SyncAuthorJournal(f.dailyRoot.resolve("joining/node/author"), access.trust, access.certificate,
            { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence); assertTrue(access.node.sourceOperations().isEmpty())
        val restored = JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single()
        assertEquals("synthetic owner recovery body", restored.name); assertEquals("retained original body", restored.basicSetting)
        assertEquals(f.source.entry.version.changes.getValue("character_cards/card").valueHash,
            access.node.inspect().nativeState.units.getValue("character_cards/card").valueHash)
        assertEquals(1, f.readyCalls.get())
        assertEquals(providerRequests, f.recoveryRequests); assertEquals(seedRequests, f.shared.server.requestCount)
        assertEquals(providerPuts, f.shared.dav.puts.toList())
        assertFalse(Files.exists(access.work.resolve("cloud-connections")), "The unconfirmed cloud connection must not be saved by file recovery")
        assertContentEquals(packageBytes, Files.readAllBytes(packageFile))
        assertContentEquals(f.originalBytes, Files.readAllBytes(f.original.data.resolve("entities/character_cards/card.json")))
        assertEquals(f.originalLedger, f.original.custody().ledger().current())
    }

    @Test fun anEnabledConnectionWithAFailedPostWriteReadbackIsReportedUnknownAndRemainsUserAuthorized() = fixture(https = true) { f ->
        f.publishCloud()
        val failRead = AtomicBoolean(); val failedReads = AtomicInteger()
        f.unprotectDaily = { bytes ->
            if (failRead.compareAndSet(true, false)) {
                failedReads.incrementAndGet(); throw IOException("SYNTHETIC_POST_WRITE_CATALOG_READ")
            }
            bytes.reversedArray()
        }
        val atReady = CompletableDeferred<List<LanSyncGroupCloudRoute>>()
        f.onReady = { access -> atReady.complete(access.cloudRoutes().rows()) }
        val owner = f.owner(observe = { if (it == "CLOUD_RECOVERY_CONNECTION_SAVED") failRead.set(true) })
        val review = prepareReview(f, owner)
        owner.confirm(review.binding, saveConnection = true)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { owner.drain() }
        assertEquals(1, failedReads.get())
        assertTrue(owner.state.value.complete); assertNull(owner.state.value.error)
        assertNull(owner.state.value.connectionEnabled)
        assertNotNull(owner.state.value.connectionNotice)
        val row = withTimeout(5_000) { atReady.await() }.single()
        assertTrue(row.enabled); assertEquals(f.endpoint, row.endpoint)
        assertEquals(listOf(row), access.cloudRoutes().rows())
        assertEquals("synthetic owner recovery body", JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single().name)
        assertEquals(1L, managementState(f).completedSequence)
        assertEquals(1, f.readyCalls.get())
    }

    @Test fun coldRetryAfterRootApprovalResumesTheSameMemberWithoutAnotherRootSignature() = fixture(https = true) { f ->
        f.publishCloud()
        val interrupted = AtomicBoolean()
        f.joiningObserve = { point ->
            if (point == "GROUP_RECOVERY_JOIN_APPROVAL_SOURCE_RETAINED" && interrupted.compareAndSet(false, true))
                throw IOException("SYNTHETIC_APPROVED_MEMBER_ACTIVATION_INTERRUPTED")
        }
        suspend fun prepare(owner: LanSyncGroupCloudRecoveryOwner): LanSyncGroupCloudRecoveryReview {
            owner.open()
            val secret = f.password.toByteArray(Charsets.UTF_8)
            try { owner.prepare(f.draft(secret), f.original.code) } finally { secret.fill(0) }
            val state = withTimeout(30_000) { owner.state.first { !it.busy && (it.review != null || it.error != null) } }
            assertNull(state.error)
            return requireNotNull(state.review)
        }
        val first = f.owner(); val initial = prepare(first)
        first.confirm(initial.binding)
        withTimeout(30_000) { first.drain() }
        assertTrue(interrupted.get()); assertNotNull(first.state.value.error); assertEquals(0, f.readyCalls.get())
        f.assertNoDailyActivation()
        val management = f.recoveryRoot.resolve("management")
        val origin = Files.readAllBytes(management.resolve("root-actions/origin.protected"))
        fun counter(): SyncRootLedgerState = SyncManagementSession.open(f.original.code,
            Files.readAllBytes(management.resolve("management-vault.bin"))).use { session ->
            SyncRootLedgerJournal(management.resolve("root-actions"), session.trust, session.ownerCertificate,
                session::protectJournal, session::unprotectJournal).state()
        }
        val approved = counter(); assertEquals(1L, approved.completedSequence)
        first.closeAndDrain(); f.joiningObserve = {}
        val second = f.owner(); val resumed = prepare(second)
        assertEquals(initial.device, resumed.device); assertNotEquals(initial.binding, resumed.binding)
        assertEquals(approved, counter())
        second.confirm(resumed.binding, saveConnection = true)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { second.drain() }
        assertTrue(second.state.value.complete); assertEquals(approved, counter())
        assertEquals(true, second.state.value.connectionEnabled)
        assertTrue(access.cloudRoutes().rows().single().enabled)
        assertContentEquals(origin, Files.readAllBytes(management.resolve("root-actions/origin.protected")))
        assertEquals(initial.device, access.device)
        assertEquals(f.originalLedger.members.size + 1, access.ledger().current().members.size)
        assertEquals("synthetic owner recovery body", JsonFileStorage(f.dailyData).loadAll("character_cards", CharacterCard.serializer()).single().name)
        assertEquals(f.originalLedger, f.original.custody().ledger().current())
    }

    @Test fun lockingAProtectedGateOrARealStalledHttpReadDrainsWithoutALateReadyCallbackOrNativeOrigin() = fixture { f ->
        val gateEntered = CompletableDeferred<Unit>()
        val gated = f.owner(cloudWork = { gateEntered.complete(Unit); awaitCancellation() })
        gated.open()
        var secret = f.password.toByteArray(Charsets.UTF_8)
        try { gated.prepare(f.draft(secret), f.original.code) } finally { secret.fill(0) }
        withTimeout(5_000) { gateEntered.await() }
        gated.lock(); withTimeout(5_000) { gated.drain() }
        assertFalse(gated.state.value.visible); assertFalse(gated.state.value.complete)
        assertEquals(0, f.roots.get()); assertEquals(0, f.joiners.get()); assertEquals(0, f.readyCalls.get())
        assertEquals(0, f.shared.server.requestCount)

        f.publishCloud()
        val entered = CountDownLatch(1); val stalled = AtomicBoolean(false)
        f.shared.dav.overrideRequest = { request ->
            if (request.method == "PROPFIND" && stalled.compareAndSet(false, true)) {
                entered.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            } else null
        }
        val owner = f.owner()
        owner.open(); secret = f.password.toByteArray(Charsets.UTF_8)
        try { owner.prepare(f.draft(secret), f.original.code) } finally { secret.fill(0) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        owner.lock(); withTimeout(5_000) { owner.drain() }
        assertFalse(owner.state.value.visible); assertFalse(owner.state.value.complete)
        assertEquals(0, f.readyCalls.get()); f.assertNoDailyActivation()
        assertFalse(Files.exists(f.dailyRoot.resolve("joining/profile.protected")))
        assertEquals(f.originalLedger, f.original.custody().ledger().current())
    }
}
