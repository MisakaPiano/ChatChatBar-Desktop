package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.IOException
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

/** Actual temporary owner lifecycle over private synthetic HTTP; no real provider or daily root. */
class LanSyncGroupCloudRecoveryOwnerTest {
    private class Fixture(val shared: WeakFixture) {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(30, TimeUnit.SECONDS).build()
        val roots = AtomicInteger(); val joiners = AtomicInteger(); val readyCalls = AtomicInteger()
        val beforeSetupCalls = AtomicInteger()
        var joiningObserve: (String) -> Unit = {}
        val ready = CompletableDeferred<LanSyncGroupRuntimeAccess>()
        val owners = mutableListOf<LanSyncGroupCloudRecoveryOwner>()
        val original = LanSyncGroupAdministrationTest.Fixture(directory("original"))
        val recoveryRoot = shared.root.resolve("recovery-owner")
        val dailyRoot = shared.root.resolve("recovered-daily")
        val dailyData = shared.root.resolve("recovered-data")
        private val dailyIdentity by lazy { LanSyncTlsIdentity.create() }
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
                { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, { joiningObserve(it) })
        }
        fun owner(networkAllowed: () -> Boolean = { true },
            cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() }): LanSyncGroupCloudRecoveryOwner =
            LanSyncGroupCloudRecoveryOwner(root = {
                roots.incrementAndGet(); Files.createDirectories(recoveryRoot)
            }, joining = ::joining, deviceName = "synthetic restored daily device",
                beforeSetup = { beforeSetupCalls.incrementAndGet() }, networkAllowed = networkAllowed, cloudWork = cloudWork,
                onReady = { value -> readyCalls.incrementAndGet(); ready.complete(value) }, client = client,
                endpointPolicy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).also(owners::add)
        fun draft(secret: ByteArray) = LanSyncGroupCloudDraft("synthetic recovery", shared.server.url("/sync/")
            .newBuilder().host("127.0.0.1").build().toString(), username, secret, enabled = false)
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
            owners.forEach { it.closeAndDrain() }
            transport.close(); original.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
    private fun fixture(work: suspend (Fixture) -> Unit) = weakFixture { shared -> runBlocking {
        val f = Fixture(shared)
        try { f.seed(); work(f) } finally { f.close() }
    } }

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
        assertTrue(reopened.state.value.complete); assertEquals(2, f.readyCalls.get())
        assertEquals(rootsAfterReady, f.roots.get()); assertEquals(requestsAfterReady, f.shared.server.requestCount)
        assertEquals(readyLedger, access.ledger().current()); assertEquals(0L, author.state().committedSequence)
    }

    @Test fun coldRetryAfterRootApprovalResumesTheSameMemberWithoutAnotherRootSignature() = fixture { f ->
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
        second.confirm(resumed.binding)
        val access = withTimeout(30_000) { f.ready.await() }
        withTimeout(5_000) { second.drain() }
        assertTrue(second.state.value.complete); assertEquals(approved, counter())
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
