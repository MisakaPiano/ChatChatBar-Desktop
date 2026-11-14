package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WebDavEndpointPolicy
import com.example.chatbar.domain.syncStore.webdav.WeakFixture
import java.net.BindException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import kotlin.test.*

/** Shared application owner with real enrollment/ciphertext/TLS; protection is synthetic only. */
class LanSyncGroupCoordinatorTest {
    private val noNetwork = object : LanSyncNetwork {
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
            LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port -> Socket("127.0.0.1", port) }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(10) }

    @Test fun emptyStartupOpenLockAndCloseNeverTouchIdentityProfileOrSetupFactories() = runBlocking {
        val factories = AtomicInteger()
        val setups = AtomicInteger()
        val coordinator = LanSyncGroupCoordinator({ false },
            { factories.incrementAndGet(); error("SYNTHETIC_UNCONFIGURED_IDENTITY_FACTORY") },
            { factories.incrementAndGet(); error("SYNTHETIC_UNCONFIGURED_JOINING_FACTORY") }, noNetwork,
            "synthetic device", { setups.incrementAndGet() })
        coordinator.startIfConfigured()
        coordinator.open()
        coordinator.lockManagement()
        coordinator.closeAndDrain()
        assertEquals(0, factories.get())
        assertEquals(0, setups.get())
        assertEquals(LanSyncGroupPanelStage.EMPTY, coordinator.state.value.stage)
        assertNull(coordinator.state.value.recoveryCode)
        assertNull(coordinator.view.value)
    }

    private class RecoveryCloseProbe(network: LanSyncNetwork) {
        val factories = AtomicInteger(); val roots = AtomicInteger(); val joins = AtomicInteger()
        val founders = AtomicInteger(); val ready = AtomicInteger(); val http = AtomicInteger()
        private val client = OkHttpClient.Builder().addInterceptor {
            http.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_USE_HTTP")
        }.build()
        val coordinator = LanSyncGroupCoordinator({ false },
            { founders.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_OPEN_FOUNDER") },
            { joins.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_OPEN_JOINING") }, network,
            "synthetic closed device", cloudRecoveryFactory = { callback ->
                factories.incrementAndGet()
                LanSyncGroupCloudRecoveryOwner(
                    root = { roots.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_CREATE_ROOT") },
                    joining = { joins.incrementAndGet(); error("SYNTHETIC_CLOSED_RECOVERY_MUST_NOT_CREATE_JOINING") },
                    deviceName = "synthetic closed device", onReady = { ready.incrementAndGet(); callback(it) },
                    client = client, endpointPolicy = WebDavEndpointPolicy.HTTPS_ONLY)
            })
        fun assertUntouched(factoryCount: Int) {
            assertEquals(factoryCount, factories.get()); assertEquals(0, roots.get()); assertEquals(0, joins.get())
            assertEquals(0, founders.get()); assertEquals(0, ready.get()); assertEquals(0, http.get())
        }
        suspend fun close() {
            try { coordinator.closeAndDrain() }
            finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun aNeverOpenedRecoveryFactoryCannotBeCreatedAfterCoordinatorQuiescence() = runBlocking {
        val probe = RecoveryCloseProbe(noNetwork)
        try {
            probe.coordinator.quiesceAndDrain()
            repeat(2) { assertNull(probe.coordinator.cloudRecovery) }
            probe.coordinator.open(); probe.coordinator.startIfConfigured()
            assertNull(probe.coordinator.cloudRecovery)
            probe.assertUntouched(0)
        } finally { probe.close() }
    }

    @Test fun aRetainedRecoveryReferenceCannotReopenOrPrepareAfterCoordinatorQuiescence() = runBlocking {
        val probe = RecoveryCloseProbe(noNetwork)
        try {
            val retained = requireNotNull(probe.coordinator.cloudRecovery)
            retained.open(); assertTrue(retained.state.value.visible)
            probe.assertUntouched(1)
            probe.coordinator.quiesceAndDrain()
            assertNull(probe.coordinator.cloudRecovery)
            // Use a valid code so an accidentally live owner cannot pass merely by rejecting input.
            val code = SyncManagementSession.create().use { session ->
                session.seal(SyncGroupTrust(SyncStoreId.random(),
                    SyncVersionRef(SyncStoreId.random(), "a".repeat(64)), session.ownerCertificate))
                session.recoveryCode()
            }
            val password = "synthetic password".toByteArray(Charsets.UTF_8)
            try {
                retained.open()
                retained.prepare(LanSyncGroupCloudDraft("synthetic recovery", "https://synthetic.invalid/group/",
                    "synthetic user", password), code)
            } finally { password.fill(0) }
            withTimeout(5_000) { retained.drain() }
            assertFalse(retained.state.value.visible); assertFalse(retained.state.value.busy)
            assertFalse(retained.state.value.complete); assertNull(retained.state.value.review)
            probe.assertUntouched(1)
        } finally { probe.close() }
    }

    private class Fixture(val root: Path, val network: LanSyncNetwork) {
        val profile = Files.createDirectory(root.resolve("profile"))
        val data = Files.createDirectory(root.resolve("data"))
        val card = data.resolve("entities/character_cards/card.json")
        val identity = LanSyncTlsIdentity.create()
        val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { Path.of(it) })
        private val owners = mutableListOf<LanSyncGroupEnrollment>()
        private val coordinators = mutableListOf<LanSyncGroupCoordinator>()
        var setupCalls = 0
        var recoveryCode: String? = null
        val reloads = AtomicInteger()
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) { reloads.incrementAndGet() }
        }
        fun founder() = LanSyncGroupEnrollment(profile, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }).also(owners::add)
        fun coordinator(sourceChanges: Flow<Long>? = null, initiallyPaused: Boolean = false, beforeSetup: suspend () -> Unit = {}) = LanSyncGroupCoordinator({ Files.exists(profile.resolve("profile.protected")) || Files.exists(profile.resolve("creation")) },
            ::founder, { error("SYNTHETIC_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic PC", { setupCalls++; beforeSetup() }, sourceChanges, initiallyPaused).also(coordinators::add)
        fun author(trust: SyncGroupTrust) = SyncAuthorJournal(profile.resolve("node/author"), trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        fun writeCard(name: String) {
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", name, createdAt = 1, updatedAt = 1))
        }
        suspend fun close() { coordinators.forEach { it.closeAndDrain() }; owners.forEach { it.close() } }
    }
    private fun fixture(network: LanSyncNetwork = noNetwork, work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-coordinator-").toRealPath()
        val f = Fixture(root, network)
        try { work(f) } finally { f.close(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun managementLockDuringQueuedCreationNeverRevealsLateRecoveryCodeOrActivatesDraft() = fixture { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = f.coordinator { entered.complete(Unit); release.await() }
        coordinator.createGroup()
        withTimeout(5_000) { entered.await() }
        assertTrue(coordinator.state.value.busy)
        coordinator.lockManagement()
        release.complete(Unit)
        await { !coordinator.state.value.busy && coordinator.state.value.error != null }
        assertTrue(requireNotNull(coordinator.state.value.error).endsWith("SYNC_GROUP_MANAGEMENT_LOCKED"))
        assertNull(coordinator.state.value.recoveryCode)
        assertNull(coordinator.state.value.setupBinding)
        assertFalse(coordinator.state.value.managementUnlocked)
        assertNull(coordinator.view.value)
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, f.founder().profile()?.stage)
        for (name in listOf("node", "custody", "local-replica", "root-actions", "app-runtime")) assertFalse(Files.exists(f.profile.resolve(name)))
        // The hidden draft must also be gone: a later save cannot activate it using any binding.
        val firstError = coordinator.state.value.error
        coordinator.recoverySaved("synthetic-binding-never-exposed")
        await { !coordinator.state.value.busy && coordinator.state.value.error != firstError }
        assertTrue(requireNotNull(coordinator.state.value.error).endsWith("SYNC_GROUP_MANAGEMENT_LOCKED"))
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, f.founder().profile()?.stage)
        coordinator.closeAndDrain()
        assertNull(coordinator.view.value)
        assertFalse(Files.exists(f.profile.resolve("app-runtime")))
    }

    @Test fun reopeningAPausedLiveFounderDraftPreservesItsSaveCodeFormButNeverResurrectsALockedCode() = fixture { f ->
        val coordinator = f.coordinator(initiallyPaused = true)
        coordinator.createGroup()
        await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        val code = requireNotNull(coordinator.state.value.recoveryCode)
        val binding = requireNotNull(coordinator.state.value.setupBinding)
        val prepared = Files.readAllBytes(f.profile.resolve("profile.protected"))
        suspend fun reopen() = coroutineScope {
            val completed = CompletableDeferred<Unit>()
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                var entered = false
                coordinator.state.collect { state ->
                    if (state.busy) entered = true else if (entered) completed.complete(Unit)
                }
            }
            try { coordinator.open(); withTimeout(5_000) { completed.await() } }
            finally { observer.cancelAndJoin() }
        }
        reopen()
        assertTrue(coordinator.state.value.paused)
        assertEquals(LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE, coordinator.state.value.stage)
        assertEquals(code, coordinator.state.value.recoveryCode)
        assertEquals(binding, coordinator.state.value.setupBinding)
        assertTrue(coordinator.state.value.managementUnlocked)
        assertContentEquals(prepared, Files.readAllBytes(f.profile.resolve("profile.protected")))
        assertNull(coordinator.view.value)
        assertFalse(Files.exists(f.profile.resolve("node")))
        coordinator.lockManagement()
        reopen()
        assertEquals(LanSyncGroupPanelStage.PREPARED_LOCKED, coordinator.state.value.stage)
        assertNull(coordinator.state.value.recoveryCode)
        assertNull(coordinator.state.value.setupBinding)
        assertFalse(coordinator.state.value.managementUnlocked)
        assertContentEquals(prepared, Files.readAllBytes(f.profile.resolve("profile.protected")))
        coordinator.unlockManagement(code)
        await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
        assertEquals(binding, coordinator.state.value.setupBinding)
        coordinator.recoverySaved(binding)
        await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
        assertTrue(coordinator.state.value.paused)
        assertEquals(0L, f.author(requireNotNull(f.founder().profile()).trust).state().committedSequence)
    }

    @Test fun explicitSavedCreationPublishesLocalReplicaAndReopenRunsWithManagementLocked() = fixture { f ->
        f.writeCard("synthetic user content")
        val original = Files.readAllBytes(f.card)
        val first = f.coordinator()
        first.createGroup()
        await { first.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !first.state.value.busy }
        val prepared = first.state.value
        assertNotNull(prepared.recoveryCode)
        val binding = requireNotNull(prepared.setupBinding)
        assertNull(first.view.value)
        first.recoverySaved(binding)
        await { first.state.value.stage == LanSyncGroupPanelStage.READY && !first.state.value.busy && first.view.value != null }
        assertEquals(1, f.setupCalls)
        assertNull(first.state.value.error)
        assertNull(first.state.value.recoveryCode)
        assertFalse(first.state.value.managementUnlocked)
        assertNotNull(first.state.value.local.lastSuccessAt)
        assertNull(first.state.value.nearby.lastSuccessAt)
        assertNull(first.state.value.cloud.lastSuccessAt)
        assertContentEquals(original, Files.readAllBytes(f.card))
        val access = requireNotNull(f.founder().runtimeAccess())
        val operations = access.node.sourceOperations()
        assertEquals(1, operations.size)
        assertNotNull(access.node.receipt(operations.single(), access.localTarget))
        assertTrue(requireNotNull(first.view.value?.plan).units.values.all { it.stage == LanSyncGroupNativeStage.UNCHANGED })
        first.lockManagement()
        val previous = first.view.value
        first.open()
        await { first.view.value !== previous && !first.state.value.busy }
        assertNull(first.state.value.error)
        assertFalse(first.state.value.managementUnlocked)
        first.closeAndDrain()

        val restarted = f.coordinator()
        restarted.startIfConfigured()
        await { restarted.state.value.stage == LanSyncGroupPanelStage.READY && !restarted.state.value.busy && restarted.view.value != null }
        assertNull(restarted.state.value.error)
        assertFalse(restarted.state.value.managementUnlocked)
        assertNull(restarted.state.value.recoveryCode)
        assertNull(restarted.state.value.local.lastSuccessAt, "Historical receipts have no invented wall-clock time")
        assertEquals(operations, requireNotNull(f.founder().runtimeAccess()).node.sourceOperations())
        assertContentEquals(original, Files.readAllBytes(f.card))
        assertEquals(1, f.setupCalls)
    }

    @Test fun localDeletionAfterInitialPublicationRemainsUnsignedAndVisibleForReview() = fixture { f ->
        f.writeCard("synthetic retained original")
        val coordinator = f.coordinator()
        coordinator.createGroup()
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
        coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy && coordinator.view.value != null }
        val access = requireNotNull(f.founder().runtimeAccess())
        val operations = access.node.sourceOperations()
        assertEquals(1, operations.size)
        val entries = access.replica.listCommits(null).entries
        Files.delete(f.card)
        coordinator.lockManagement()
        coordinator.retry()
        await { coordinator.view.value?.plan?.units?.get("character_cards/card")?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        assertFalse(Files.exists(f.card))
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(entries, access.replica.listCommits(null).entries)
        val review = requireNotNull(coordinator.view.value).review(setOf("character_cards/card"))
        assertEquals(setOf("character_cards/card"), review.requiredBindings.keys)
        assertFailsWith<IllegalArgumentException> { review.authorize() }
        assertFalse(coordinator.state.value.managementUnlocked)
    }
    private suspend fun readyCoordinator(f: Fixture, sourceChanges: Flow<Long>? = null): LanSyncGroupCoordinator {
        val coordinator = f.coordinator(sourceChanges)
        coordinator.createGroup()
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
        f.recoveryCode = requireNotNull(coordinator.state.value.recoveryCode)
        coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
        await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy && coordinator.view.value != null }
        assertNull(coordinator.state.value.error)
        return coordinator
    }

    private class ObservedNetwork(private val delegate: LanSyncNetwork) : LanSyncNetwork {
        val calls = AtomicInteger()
        val forbidden = AtomicBoolean(false)
        private fun touch() {
            calls.incrementAndGet()
            check(!forbidden.get()) { "SYNTHETIC_PAUSED_NETWORK_FORBIDDEN" }
        }
        override fun addresses(): List<String> { touch(); return delegate.addresses() }
        override fun accepts(address: InetAddress): Boolean { touch(); return delegate.accepts(address) }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
            touch(); return delegate.connect(identity, peer)
        }
        override fun listening(fingerprint: String, port: Int) { touch() }
        override fun discover(fingerprint: String): Pair<String, Int>? { touch(); return null }
        override val discoveryEnabled: Boolean get() { touch(); return false }
    }

    @Test fun optionalNearbyBindFailureDoesNotBlockLocalOrEnabledCloudAndForegroundRetriesTheListener() = fixture { f ->
        val blocked = AtomicBoolean(true)
        val attempts = AtomicInteger(); val ready = AtomicInteger(); val forbidden = AtomicBoolean(false)
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) {
                attempts.incrementAndGet()
                check(!forbidden.get()) { "SYNTHETIC_PAUSED_LISTENER_FORBIDDEN" }
                if (blocked.get()) throw BindException("synthetic optional listener unavailable")
                ready.incrementAndGet()
            }
        }
        // This test supplies its network to the actual owner, while retaining the fixture's TLS/native owners.
        val founder = f.founder()
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(), "a".repeat(64))
        val draft = founder.prepare(request, SyncRootGroupCreateAdmission { require(it == request) })
        f.recoveryCode = draft.recoveryCode()
        founder.activate(draft, LanSyncGroupRecoverySavedAdmission {})
        val access = requireNotNull(founder.runtimeAccess())
        f.writeCard("synthetic automatic source despite nearby bind failure")
        val original = Files.readAllBytes(f.card)
        val catalog = LanSyncGroupCloudRoutes(Files.createDirectory(f.root.resolve("catalog")), access.trust, access.device,
            { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            WeakFixture(Files.createDirectory(f.root.resolve("provider"))).use { provider ->
                val password = "synthetic nearby independent cloud password".toByteArray()
                val route = try { catalog.add(LanSyncGroupCloudDraft("synthetic enabled drive",
                    provider.server.url("/sync/").newBuilder().host("127.0.0.1").build().toString(),
                    "synthetic user", password, enabled = true)) } finally { password.fill(0) }
                val coordinator = LanSyncGroupCoordinator({ Files.exists(f.profile.resolve("profile.protected")) }, f::founder,
                    { error("SYNTHETIC_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "synthetic PC",
                    cloudRuntimeFactory = { current, allowed, wake, report ->
                        LanSyncGroupCloudRuntime(current, allowed, wake, report, catalog, client,
                            WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
                    })
                try {
                    val initialNearby = coordinator.state.value.nearby
                    coordinator.open()
                    withTimeout(60_000) {
                        while (coordinator.state.value.busy || coordinator.view.value == null || attempts.get() == 0 ||
                            coordinator.state.value.nearby.text == initialNearby.text ||
                            coordinator.cloudSettings.value.progress[route]?.selectionObserved != true) delay(10)
                    }
                    assertEquals(LanSyncGroupPanelStage.READY, coordinator.state.value.stage)
                    assertFalse(coordinator.state.value.paused)
                    assertNotEquals(initialNearby.text, coordinator.state.value.nearby.text)
                    assertNotNull(coordinator.state.value.local.lastSuccessAt)
                    assertNull(coordinator.state.value.nearby.lastSuccessAt)
                    assertNull(coordinator.state.value.error)
                    assertEquals(0, ready.get())
                    val source = access.node.sourceOperations().single()
                    val entry = requireNotNull(f.author(access.trust).entry(source))
                    assertNotNull(access.node.receipt(source, access.localTarget))
                    val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, route)
                    val publication = access.targetPublication(access.work.resolve("cloud-runtime").resolve(route.value).resolve("target"),
                        target, prepare = false)
                    val receipt = requireNotNull(publication.cloudReceipt())
                    assertEquals(target, receipt.target)
                    assertTrue(receipt.objects.isNotEmpty())
                    assertEquals(access.ledger().current().reference(), receipt.checkpoint)
                    assertTrue(provider.dav.puts.isNotEmpty())
                    assertTrue(provider.server.requestCount > 0)
                    assertSourceOriginal(f, entry, original)

                    val failed = coordinator.state.value.nearby.text
                    val failedAttempts = attempts.get()
                    blocked.set(false)
                    coordinator.onForeground()
                    await { ready.get() > 0 && coordinator.state.value.nearby.text != failed }
                    assertTrue(attempts.get() > failedAttempts)
                    // Authentication still goes through the ordinary pinned TLS protocol, not the wake hook.
                    liveListenerPort(f, coordinator, access)
                    assertEquals(listOf(source), access.node.sourceOperations())
                    assertEquals(1L, f.author(access.trust).state().committedSequence)
                    assertContentEquals(original, Files.readAllBytes(f.card))

                    coordinator.pauseAndDrain()
                    forbidden.set(true)
                    val calls = attempts.get(); val http = provider.server.requestCount
                    repeat(3) { coordinator.onForeground(); coordinator.onNetworkAvailable(); coordinator.retry() }
                    delay(350)
                    assertEquals(calls, attempts.get())
                    assertEquals(http, provider.server.requestCount)
                    assertTrue(coordinator.state.value.paused)
                    assertNull(coordinator.state.value.error)
                } finally { coordinator.closeAndDrain() }
            }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun pauseDrainsAnInFlightFailedNearbyStartupAndRejectsQueuedNetworkHints() {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val attempts = AtomicInteger(); val forbidden = AtomicBoolean(false)
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) {
                attempts.incrementAndGet()
                check(!forbidden.get()) { "SYNTHETIC_LATE_LISTENER_FORBIDDEN" }
                entered.complete(Unit)
                check(release.await(20, TimeUnit.SECONDS)) { "SYNTHETIC_LISTENER_RELEASE_TIMEOUT" }
                throw BindException("synthetic optional bind failure during pause")
            }
        }
        fixture(network) { f ->
            try {
                f.writeCard("synthetic source before optional listener pause")
                val original = Files.readAllBytes(f.card)
                val storage = JsonFileStorage(f.data)
                val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
                withTimeout(5_000) { entered.await() }
                coroutineScope {
                    val requested = CompletableDeferred<Unit>()
                    val stop = async(Dispatchers.IO) { requested.complete(Unit); coordinator.pauseAndDrain() }
                    requested.await()
                    release.countDown()
                    withTimeout(20_000) { stop.await() }
                }
                assertTrue(coordinator.state.value.paused)
                assertFalse(coordinator.state.value.busy)
                forbidden.set(true)
                val calls = attempts.get()
                val access = requireNotNull(f.founder().runtimeAccess())
                val source = access.node.sourceOperations().single()
                val entry = requireNotNull(f.author(access.trust).entry(source))
                val reloads = f.reloads.get()
                storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic queued save after pause", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
                repeat(3) { coordinator.onForeground(); coordinator.onNetworkAvailable(); coordinator.retry() }
                delay(350)
                assertEquals(calls, attempts.get())
                assertEquals(listOf(source), access.node.sourceOperations())
                assertEquals(reloads, f.reloads.get())
                assertEquals(1L, f.author(access.trust).state().committedSequence)
                assertSourceOriginal(f, entry, original)
            } finally { release.countDown() }
        }
    }

    @Test fun aRunningListenerFailureRebuildsOnHintsAndPausePreventsAnotherListener() {
        val failAcceptance = AtomicBoolean(false)
        val failures = AtomicInteger(); val starts = AtomicInteger(); val lastPort = AtomicInteger()
        val forbidden = AtomicBoolean(false)
        val network = object : LanSyncNetwork by noNetwork {
            override fun listening(fingerprint: String, port: Int) {
                check(!forbidden.get()) { "SYNTHETIC_LISTENER_AFTER_PAUSE" }
                lastPort.set(port); starts.incrementAndGet()
            }
            override fun accepts(address: InetAddress): Boolean {
                if (failAcceptance.compareAndSet(true, false)) {
                    failures.incrementAndGet()
                    throw java.net.SocketException("synthetic running listener failure")
                }
                return noNetwork.accepts(address)
            }
        }
        fixture(network) { f ->
            f.writeCard("synthetic source kept through listener restart")
            val original = Files.readAllBytes(f.card)
            val coordinator = readyCoordinator(f)
            try {
                await { starts.get() > 0 }
                val access = requireNotNull(f.founder().runtimeAccess())
                var sources = access.node.sourceOperations()
                withTimeout(20_000) {
                    while (sources.isEmpty()) { delay(10); sources = access.node.sourceOperations() }
                }
                val sequence = f.author(access.trust).state().committedSequence
                for (attempt in 1..2) {
                    val before = starts.get()
                    failAcceptance.set(true)
                    Socket("127.0.0.1", lastPort.get()).use { }
                    await { failures.get() == attempt }
                    if (attempt == 1) coordinator.onNetworkAvailable() else coordinator.onForeground()
                    await { starts.get() > before }
                    // This traverses the new real listener and the ordinary certificate-pinned protocol.
                    liveListenerPort(f, coordinator, access)
                    assertEquals(sources, access.node.sourceOperations())
                    assertEquals(sequence, f.author(access.trust).state().committedSequence)
                    assertContentEquals(original, Files.readAllBytes(f.card))
                    assertNull(coordinator.state.value.error)
                }
                coordinator.pauseAndDrain()
                forbidden.set(true)
                val stopped = starts.get()
                repeat(3) { coordinator.onNetworkAvailable(); coordinator.onForeground(); coordinator.retry() }
                delay(350)
                assertEquals(stopped, starts.get())
                assertTrue(coordinator.state.value.paused)
            } finally { coordinator.closeAndDrain() }
        }
    }

    /** Obtain the real app listener through an ordinary management invitation, then use its pinned TLS protocol. */
    private suspend fun liveListenerPort(f: Fixture, coordinator: LanSyncGroupCoordinator,
        access: LanSyncGroupRuntimeAccess): Int {
        coordinator.unlockManagement(requireNotNull(f.recoveryCode))
        await { coordinator.state.value.managementUnlocked && !coordinator.state.value.busy }
        coordinator.createInvitation()
        await { coordinator.state.value.invitation != null && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        val port = requireNotNull(coordinator.state.value.invitation?.bootstrap).storePort
        val remote = access.remote(LanSyncPeer(f.identity.fingerprint, "synthetic self", "10.0.0.1", port), f.network)
        assertEquals(requireNotNull(access.replica.metadata(access.vault.key)), remote.metadata(access.vault.key))
        coordinator.closeInvitation()
        coordinator.lockManagement()
        return port
    }

    private fun retainedFiles(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.iterator().asSequence().filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.associate { path ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            root.relativize(path).toString() to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun assertSourceOriginal(f: Fixture, entry: SyncAuthorJournalEntry, original: ByteArray) =
        assertRecoveryOriginal(f, entry.request.sourceCheckpoint, original)

    private fun assertRecoveryOriginal(f: Fixture, transaction: String, original: ByteArray) {
        val custody = requireNotNull(f.workspace.recovery.read(transaction))
        assertTrue(custody.hasLocalOriginals)
        f.workspace.recovery.verify(custody)
        var found = false
        f.workspace.recovery.originals(custody) { row, path ->
            if (row.relative == "entities/character_cards/card.json") {
                found = true
                assertContentEquals(original, Files.readAllBytes(path))
            }
        }
        assertTrue(found, "The independently sealed source checkpoint must retain the exact original JSON")
    }

    @Test fun preparedDeletionDoesNotPublishUntilExplicitConfirmationThenRetainsOneSignedTombstoneAndOriginals() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic original before confirmed deletion")
        val original = Files.readAllBytes(f.card)
        val coordinator = readyCoordinator(f)
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val firstOperation = access.node.sourceOperations().single()
        val firstEntry = requireNotNull(author.entry(firstOperation))
        val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
        val objects = retainedFiles(f.profile.resolve("local-replica"))
        assertEquals(1L, author.state().committedSequence)
        assertSourceOriginal(f, firstEntry, original)
        Files.delete(f.card)
        coordinator.retry()
        await { coordinator.view.value?.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW && !coordinator.state.value.busy }
        val frozen = requireNotNull(coordinator.view.value)
        coordinator.prepareSourceReview(frozen, unit)
        await { coordinator.actionsReview.value != null && !coordinator.state.value.busy }
        val action = requireNotNull(coordinator.actionsReview.value)
        val review = requireNotNull(action.source)
        assertNull(coordinator.state.value.error)
        assertTrue(action.canConfirm)
        assertNull(action.incoming)
        assertNull(action.heldReason)
        assertEquals(setOf(unit), review.requiredBindings.keys)
        assertTrue(review.units.getValue(unit).current.isEmpty())
        assertEquals(setOf(firstEntry.version.reference()), review.units.getValue(unit).versions.keys)
        assertEquals(listOf("synthetic original before confirmed deletion"), review.units.getValue(unit).versions.values.single().map {
            it.body.getValue("name").jsonPrimitive.content
        })
        assertEquals(listOf(firstOperation), access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")), "Preparing review must not publish any new ciphertext or bind a store put")
        assertFalse(Files.exists(f.card))
        coordinator.confirmActionReview(action.binding)
        await { coordinator.actionsReview.value == null && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        val operations = access.node.sourceOperations()
        assertEquals(2, operations.size)
        assertEquals(firstOperation, operations.first())
        val tombstone = requireNotNull(author.entry(operations.last()))
        assertEquals(2L, author.state().committedSequence)
        assertEquals(2L, tombstone.version.authorSequence)
        assertEquals(firstEntry.version.reference(), tombstone.version.previousByAuthor)
        assertEquals(setOf(unit), tombstone.version.changes.keys)
        val change = tombstone.version.changes.getValue(unit)
        assertNull(change.valueHash)
        assertNull(change.content)
        assertEquals(setOf(firstEntry.version.reference()), change.previous)
        assertNotNull(access.node.receipt(operations.last(), access.localTarget))
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertFalse(Files.exists(f.card))
        assertSourceOriginal(f, firstEntry, original)
        coordinator.retry()
        await { coordinator.view.value?.nativeState?.units?.get(unit)?.frontiers == setOf(tombstone.version.reference()) && !coordinator.state.value.busy }
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
    }

    @Test fun stalePreparedDeletionRefusesNewRawUserEditAndDismissedBindingCannotPublish() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic original before stale deletion")
        val original = Files.readAllBytes(f.card)
        val originalCard = requireNotNull(LanSyncJsonFiles.read(f.card, CharacterCard.serializer()))
        val coordinator = readyCoordinator(f)
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val operations = access.node.sourceOperations()
        val firstEntry = requireNotNull(author.entry(operations.single()))
        Files.delete(f.card)
        coordinator.retry()
        await { coordinator.view.value?.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW && !coordinator.state.value.busy }
        val frozen = requireNotNull(coordinator.view.value)
        val nativeBefore = frozen.nativeState
        coordinator.prepareSourceReview(frozen, unit)
        await { coordinator.actionsReview.value != null && !coordinator.state.value.busy }
        val action = requireNotNull(coordinator.actionsReview.value)
        assertTrue(action.canConfirm)
        val objects = retainedFiles(f.profile.resolve("local-replica"))
        val recovery = f.workspace.recovery.list()
        // Restore the already signed business value with a new local audit byte sequence. An
        // ordinary background scan may observe it, but has no new business proposal to publish.
        LanSyncJsonFiles.write(f.card, CharacterCard.serializer(), originalCard.copy(updatedAt = originalCard.updatedAt + 1))
        val edited = Files.readAllBytes(f.card)
        assertFalse(original.contentEquals(edited))
        coordinator.confirmActionReview(action.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error != null }
        assertTrue(requireNotNull(coordinator.state.value.error).endsWith("SYNC_GROUP_SOURCE_CHANGED"))
        assertSame(action, coordinator.actionsReview.value)
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        assertNull(author.state().pendingApproved)
        assertEquals(nativeBefore, access.node.inspect().nativeState)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        assertEquals(recovery, f.workspace.recovery.list())
        assertSourceOriginal(f, firstEntry, original)
        coordinator.cancelActionReview()
        assertNull(coordinator.actionsReview.value)
        coordinator.confirmActionReview(action.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        assertNull(author.state().pendingApproved)
        assertEquals(nativeBefore, access.node.inspect().nativeState)
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        assertSourceOriginal(f, firstEntry, original)
    }

    @Test fun dismissedDeletionCannotPublishWhileActualStorageWakePublishesExactlyTheNewBusinessValue() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic initial original before dismissed deletion")
        val original = Files.readAllBytes(f.card)
        val storage = JsonFileStorage(f.data)
        val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val initialOperation = access.node.sourceOperations().single()
        val initial = requireNotNull(author.entry(initialOperation))
        val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
        Files.delete(f.card)
        coordinator.retry()
        await { !coordinator.state.value.busy && coordinator.view.value?.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW }
        coordinator.prepareSourceReview(requireNotNull(coordinator.view.value), unit)
        await { !coordinator.state.value.busy && coordinator.actionsReview.value?.source != null }
        val dismissed = requireNotNull(coordinator.actionsReview.value)
        assertTrue(dismissed.canConfirm)
        assertTrue(requireNotNull(dismissed.source).units.getValue(unit).current.isEmpty())
        assertEquals(listOf(initialOperation), access.node.sourceOperations())
        assertEquals(1L, author.state().committedSequence)
        coordinator.cancelActionReview()
        assertNull(coordinator.actionsReview.value)

        storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic final business edit after dismissed deletion", createdAt = 1, updatedAt = 2), CharacterCard.serializer())
        val edited = Files.readAllBytes(f.card)
        val targetHash = f.workspace.capture(f.identity.fingerprint).use { it.snapshot.hashes().getValue(unit) }
        coordinator.confirmActionReview(dismissed.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertNull(coordinator.actionsReview.value)
        await {
            val plan = coordinator.view.value?.plan
            !coordinator.state.value.busy &&
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic final business edit after dismissed deletion" &&
                plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
        }
        val operations = access.node.sourceOperations()
        assertEquals(2, operations.size)
        assertEquals(initialOperation, operations.first())
        assertEquals(2L, author.state().committedSequence)
        assertNull(author.state().pendingApproved)
        val published = requireNotNull(author.entry(operations.last()))
        assertEquals(2L, published.version.authorSequence)
        assertEquals(initial.version.reference(), published.version.previousByAuthor)
        assertEquals(setOf(unit), published.version.changes.keys)
        val change = published.version.changes.getValue(unit)
        assertNotNull(change.content, "The independent storage wake must publish the edited value, never the dismissed tombstone")
        assertEquals(targetHash, change.valueHash)
        assertEquals(setOf(initial.version.reference()), change.previous)
        assertNotNull(access.node.receipt(operations.last(), access.localTarget))
        assertEquals(initial, author.entry(initialOperation))
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        Files.newDirectoryStream(f.root.resolve("workspace"), "restore-intent-*.json").use { assertFalse(it.iterator().hasNext()) }
        assertSourceOriginal(f, initial, original)
        assertSourceOriginal(f, published, edited)

        val beforeRetry = coordinator.view.value
        coordinator.retry()
        await { coordinator.view.value !== beforeRetry && !coordinator.state.value.busy && coordinator.state.value.error == null }
        assertNull(coordinator.state.value.error)
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        coordinator.confirmActionReview(dismissed.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertNull(coordinator.actionsReview.value)
        assertEquals(operations, access.node.sourceOperations())
        val afterDismissal = coordinator.view.value
        coordinator.retry()
        await { coordinator.view.value !== afterDismissal && !coordinator.state.value.busy && coordinator.state.value.error == null }
        assertNull(coordinator.state.value.error)
        assertEquals(operations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        assertEquals(initial, author.entry(initialOperation))
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        Files.newDirectoryStream(f.root.resolve("workspace"), "restore-intent-*.json").use { assertFalse(it.iterator().hasNext()) }
        assertSourceOriginal(f, initial, original)
        assertSourceOriginal(f, published, edited)
    }

    @Test fun recoveryActionsBrowseFrozenOriginalAndOnlyConfirmedRestorePublishesAFreshRevisionWithBothRawPreimages() = fixture { f ->
        val unit = "character_cards/card"
        f.writeCard("synthetic signed recovery original")
        val original = Files.readAllBytes(f.card)
        val coordinator = readyCoordinator(f)
        val access = requireNotNull(f.founder().runtimeAccess())
        val author = f.author(access.trust)
        val initialOperation = access.node.sourceOperations().single()
        val initial = requireNotNull(author.entry(initialOperation))
        f.writeCard("synthetic published user edit before recovery")
        val edited = Files.readAllBytes(f.card)
        coordinator.retry()
        await {
            val plan = coordinator.view.value?.plan
            !coordinator.state.value.busy &&
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic published user edit before recovery" &&
                plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
        }
        assertNull(coordinator.state.value.error)
        val publishedOperations = access.node.sourceOperations()
        assertEquals(2, publishedOperations.size)
        assertEquals(initialOperation, publishedOperations.first())
        assertEquals(2L, author.state().committedSequence)
        val editedEntry = requireNotNull(author.entry(publishedOperations.last()))
        assertEquals(initial.version.reference(), editedEntry.version.previousByAuthor)
        assertNotNull(access.node.receipt(publishedOperations.last(), access.localTarget))
        assertSourceOriginal(f, editedEntry, edited)
        val publishedNative = requireNotNull(coordinator.view.value).nativeState
        val objects = retainedFiles(f.profile.resolve("local-replica"))
        coordinator.refreshRecovery()
        await { coordinator.recoveryEntries.value.any { it.transaction == initial.request.sourceCheckpoint } && !coordinator.state.value.busy }
        val historical = coordinator.recoveryEntries.value.single { it.transaction == initial.request.sourceCheckpoint }
        assertTrue(historical.hasLocalOriginals)
        assertSourceOriginal(f, initial, original)
        coordinator.openRecovery(historical.transaction)
        await { coordinator.recoveryView.value?.entry?.transaction == historical.transaction && !coordinator.state.value.busy }
        val browser = requireNotNull(coordinator.recoveryView.value)
        assertEquals(historical, browser.entry)
        assertEquals(setOf(unit), browser.units.keys)
        assertEquals(listOf("synthetic signed recovery original"), browser.units.getValue(unit).map { it.body.getValue("name").jsonPrimitive.content })
        coordinator.prepareRestore(unit)
        await { coordinator.actionsReview.value?.recovery != null && !coordinator.state.value.busy }
        val cancelled = requireNotNull(coordinator.actionsReview.value)
        val firstPreview = requireNotNull(cancelled.recovery)
        assertTrue(cancelled.canConfirm)
        assertTrue(firstPreview.hasChanges)
        assertNull(cancelled.source)
        assertNull(cancelled.incoming)
        assertEquals(setOf(unit), firstPreview.restoredUnits)
        assertEquals(listOf("synthetic published user edit before recovery"), firstPreview.currentRecords.map { it.body.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("synthetic signed recovery original"), firstPreview.restoredRecords.map { it.body.getValue("name").jsonPrimitive.content })
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertEquals(publishedOperations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        assertEquals(publishedNative, access.node.inspect().nativeState)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        coordinator.cancelActionReview()
        coordinator.confirmActionReview(cancelled.binding)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertContentEquals(edited, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        assertEquals(publishedOperations, access.node.sourceOperations())
        assertEquals(2L, author.state().committedSequence)
        assertEquals(publishedNative, access.node.inspect().nativeState)
        assertEquals(objects, retainedFiles(f.profile.resolve("local-replica")))
        coordinator.prepareRestore(unit)
        await { coordinator.actionsReview.value?.recovery != null && !coordinator.state.value.busy }
        val confirmed = requireNotNull(coordinator.actionsReview.value)
        val preview = requireNotNull(confirmed.recovery)
        assertNotEquals(firstPreview.id, preview.id)
        assertSame(browser, coordinator.recoveryView.value)
        assertContentEquals(edited, Files.readAllBytes(f.card))
        coordinator.confirmActionReview(confirmed.binding)
        await { coordinator.actionsReview.value == null && !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        assertEquals("synthetic signed recovery original", requireNotNull(LanSyncJsonFiles.read(f.card, CharacterCard.serializer())).name)
        val completed = f.workspace.completedRecoveries(f.identity.fingerprint).single { it.revision == preview.id }
        assertTrue(completed.applied)
        assertEquals(setOf(unit), completed.restoredUnits)
        assertEquals(historical.transaction, completed.sourceTransaction)
        assertEquals(preview.binding, completed.previewBinding)
        assertRecoveryOriginal(f, preview.id, edited)
        assertSourceOriginal(f, initial, original)
        val operations = access.node.sourceOperations()
        assertEquals(3, operations.size)
        assertEquals(publishedOperations, operations.take(2))
        assertEquals(3L, author.state().committedSequence)
        val restored = requireNotNull(author.entry(operations.last()))
        assertEquals(3L, restored.version.authorSequence)
        assertEquals(editedEntry.version.reference(), restored.version.previousByAuthor)
        assertNotEquals(initial.version.reference(), restored.version.reference())
        assertEquals(initial.version.changes.getValue(unit).valueHash, restored.version.changes.getValue(unit).valueHash,
            "Restoring an already signed value must remain a fresh logical revision")
        assertNotNull(access.node.receipt(operations.last(), access.localTarget))
        assertEquals(setOf(restored.version.reference()), access.node.inspect().nativeState.units.getValue(unit).frontiers)
        assertEquals(LanSyncGroupNativeStage.UNCHANGED, access.node.inspect().plan?.units?.get(unit)?.stage)
        val allRecovery = f.workspace.recovery.list()
        assertTrue(allRecovery.any { it.transaction == historical.transaction })
        assertTrue(allRecovery.any { it.transaction == preview.id })
        allRecovery.forEach(f.workspace.recovery::verify)
        coordinator.closeRecovery()
        assertNull(coordinator.recoveryView.value)
        assertNull(coordinator.actionsReview.value)
        assertEquals(allRecovery, f.workspace.recovery.list())
        assertEquals(operations, access.node.sourceOperations())
        assertSourceOriginal(f, initial, original)
        assertSourceOriginal(f, editedEntry, edited)
        assertRecoveryOriginal(f, preview.id, edited)
    }

    @Test fun initiallyPausedReadyOwnerOpensReadOnlyUntilExplicitResumeThenPublishesActualStorageChanges() {
        val network = ObservedNetwork(noNetwork)
        fixture(network) { f ->
            val unit = "character_cards/card"
            f.writeCard("synthetic original before paused reopen")
            val original = Files.readAllBytes(f.card)
            val initial = readyCoordinator(f)
            val access = requireNotNull(f.founder().runtimeAccess())
            val author = f.author(access.trust)
            val firstOperation = access.node.sourceOperations().single()
            val first = requireNotNull(author.entry(firstOperation))
            val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
            initial.closeAndDrain()
            val retained = listOf("node", "custody", "local-replica", "app-runtime/replica-reads")
                .associateWith { retainedFiles(f.profile.resolve(it)) }
            val storage = JsonFileStorage(f.data)
            network.calls.set(0)
            network.forbidden.set(true)
            val coordinator = f.coordinator(storage.committedRevisionFor(LanSyncScope.schemas.keys), initiallyPaused = true)
            coordinator.open()
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy }
            assertTrue(coordinator.state.value.paused)
            assertTrue(coordinator.state.value.devices.any { it.thisDevice })
            assertFalse(coordinator.state.value.managementUnlocked)
            assertNull(coordinator.state.value.error)
            assertNull(coordinator.view.value, "Paused open must not run a native inspection or publication cycle")
            coordinator.onForeground()
            coordinator.onNetworkAvailable()
            coordinator.startIfConfigured()
            delay(350)
            assertTrue(coordinator.state.value.paused)
            assertEquals(0, network.calls.get())
            assertEquals(1L, author.state().committedSequence)
            assertEquals(listOf(firstOperation), access.node.sourceOperations())
            for ((name, files) in retained) assertEquals(files, retainedFiles(f.profile.resolve(name)), name)
            assertContentEquals(original, Files.readAllBytes(f.card))
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))

            network.forbidden.set(false)
            coordinator.resumeIfConfigured()
            await { !coordinator.state.value.paused && !coordinator.state.value.busy && coordinator.view.value != null }
            liveListenerPort(f, coordinator, access)
            assertTrue(network.calls.get() > 0)
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic committed edit after explicit resume", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val edited = Files.readAllBytes(f.card)
            await {
                val plan = coordinator.view.value?.plan
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic committed edit after explicit resume" &&
                    plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED && !coordinator.state.value.busy
            }
            assertNull(coordinator.state.value.error)
            val operations = access.node.sourceOperations()
            assertEquals(2, operations.size)
            assertEquals(firstOperation, operations.first())
            assertEquals(2L, author.state().committedSequence)
            val resumed = requireNotNull(author.entry(operations.last()))
            assertEquals(first.version.reference(), resumed.version.previousByAuthor)
            assertNotNull(access.node.receipt(operations.last(), access.localTarget))
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
            assertContentEquals(edited, Files.readAllBytes(f.card))
            assertSourceOriginal(f, first, original)
            assertSourceOriginal(f, resumed, edited)
            assertEquals(1, f.setupCalls)
        }
    }

    @Test fun recoverablePauseClosesListenerKeepsLateStorageCommitUnsignedAndResumesSameAuthorExactlyOnce() {
        val network = ObservedNetwork(noNetwork)
        fixture(network) { f ->
            val unit = "character_cards/card"
            f.writeCard("synthetic original before recoverable pause")
            val original = Files.readAllBytes(f.card)
            val storage = JsonFileStorage(f.data)
            val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
            val access = requireNotNull(f.founder().runtimeAccess())
            val author = f.author(access.trust)
            val firstOperation = access.node.sourceOperations().single()
            val first = requireNotNull(author.entry(firstOperation))
            val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
            val oldPort = liveListenerPort(f, coordinator, access)
            coordinator.pauseAndDrain()
            assertTrue(coordinator.state.value.paused)
            assertFalse(coordinator.state.value.busy)
            assertFalse(coordinator.state.value.managementUnlocked)
            assertNull(coordinator.state.value.invitation)
            assertFails { Socket("127.0.0.1", oldPort).close() }
            val retained = listOf("node", "custody", "local-replica", "app-runtime/replica-reads")
                .associateWith { retainedFiles(f.profile.resolve(it)) }
            network.calls.set(0)
            network.forbidden.set(true)
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic late committed edit while paused", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val edited = Files.readAllBytes(f.card)
            coordinator.onForeground()
            coordinator.onNetworkAvailable()
            coordinator.startIfConfigured()
            coordinator.retry()
            delay(350)
            assertTrue(coordinator.state.value.paused)
            assertNull(coordinator.state.value.error)
            assertEquals(0, network.calls.get())
            assertEquals(1L, author.state().committedSequence)
            assertEquals(listOf(firstOperation), access.node.sourceOperations())
            for ((name, files) in retained) assertEquals(files, retainedFiles(f.profile.resolve(name)), name)
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
            assertContentEquals(edited, Files.readAllBytes(f.card))

            network.forbidden.set(false)
            coordinator.resumeIfConfigured()
            await {
                val plan = coordinator.view.value?.plan
                !coordinator.state.value.paused && !coordinator.state.value.busy &&
                    plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic late committed edit while paused" &&
                    plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
            }
            assertNull(coordinator.state.value.error)
            val operations = access.node.sourceOperations()
            assertEquals(2, operations.size)
            assertEquals(firstOperation, operations.first())
            assertEquals(2L, author.state().committedSequence)
            val resumed = requireNotNull(author.entry(operations.last()))
            assertEquals(2L, resumed.version.authorSequence)
            assertEquals(first.version.reference(), resumed.version.previousByAuthor)
            assertNotNull(access.node.receipt(operations.last(), access.localTarget))
            liveListenerPort(f, coordinator, access)
            val previousView = coordinator.view.value
            coordinator.retry()
            await { coordinator.view.value !== previousView && !coordinator.state.value.busy }
            assertNull(coordinator.state.value.error)
            assertEquals(operations, access.node.sourceOperations())
            assertEquals(2L, author.state().committedSequence)
            assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
            assertContentEquals(edited, Files.readAllBytes(f.card))
            assertSourceOriginal(f, first, original)
            assertSourceOriginal(f, resumed, edited)
            assertEquals(1, f.setupCalls)
        }
    }

    @Test fun committedStorageRevisionAutomaticallyPublishesAndLateShutdownSaveSealsWithoutNetworkOrReload() {
        val forbidden = AtomicBoolean(false)
        val networkCalls = AtomicInteger()
        fun networkTouch() { networkCalls.incrementAndGet(); check(!forbidden.get()) { "SYNTHETIC_SHUTDOWN_NETWORK_FORBIDDEN" } }
        val network = object : LanSyncNetwork {
            override fun addresses(): List<String> { networkTouch(); return noNetwork.addresses() }
            override fun accepts(address: InetAddress): Boolean { networkTouch(); return noNetwork.accepts(address) }
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
                networkTouch(); return noNetwork.connect(identity, peer)
            }
            override fun listening(fingerprint: String, port: Int) { networkTouch() }
            override fun discover(fingerprint: String): Pair<String, Int>? { networkTouch(); return null }
            override val discoveryEnabled = false
        }
        fixture(network) { f ->
            val unit = "character_cards/card"
            f.writeCard("synthetic initial committed original")
            val original = Files.readAllBytes(f.card)
            val storage = JsonFileStorage(f.data)
            val coordinator = readyCoordinator(f, storage.committedRevisionFor(LanSyncScope.schemas.keys))
            val access = requireNotNull(f.founder().runtimeAccess())
            val author = f.author(access.trust)
            val firstOperation = access.node.sourceOperations().single()
            val first = requireNotNull(author.entry(firstOperation))
            val started = System.nanoTime()
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic ordinary committed edit", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val ordinaryRaw = Files.readAllBytes(f.card)
            withTimeout(10_000) { await {
                val plan = coordinator.view.value?.plan
                plan?.before?.records?.singleOrNull()?.body?.get("name")?.jsonPrimitive?.content == "synthetic ordinary committed edit" &&
                    plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
            } }
            assertTrue(System.nanoTime() - started < 60_000_000_000L, "An actual storage commit must wake the owner before the safety rescan")
            assertNull(coordinator.state.value.error)
            val ordinaryOperations = access.node.sourceOperations()
            assertEquals(2, ordinaryOperations.size)
            assertEquals(2L, author.state().committedSequence)
            assertNotNull(access.node.receipt(ordinaryOperations.last(), access.localTarget))
            assertContentEquals(ordinaryRaw, Files.readAllBytes(f.card))
            assertSourceOriginal(f, first, original)
            coordinator.quiesceAndDrain()
            val reloadsBefore = f.reloads.get()
            val networkBefore = networkCalls.get()
            forbidden.set(true)
            storage.saveEntity("character_cards", "card", CharacterCard("card", "synthetic final writer after quiesce", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            val lateRaw = Files.readAllBytes(f.card)
            assertEquals(ordinaryOperations, access.node.sourceOperations())
            assertEquals(2L, author.state().committedSequence)
            assertTrue(withTimeout(20_000) { coordinator.flushLocalAfterWriters() })
            assertEquals(reloadsBefore, f.reloads.get())
            assertEquals(networkBefore, networkCalls.get())
            assertContentEquals(lateRaw, Files.readAllBytes(f.card))
            val finalOperations = access.node.sourceOperations()
            assertEquals(3, finalOperations.size)
            assertEquals(ordinaryOperations, finalOperations.take(2))
            assertEquals(3L, author.state().committedSequence)
            val final = requireNotNull(author.entry(finalOperations.last()))
            assertEquals(3L, final.version.authorSequence)
            assertEquals(requireNotNull(author.entry(ordinaryOperations.last())).version.reference(), final.version.previousByAuthor)
            assertNotNull(access.node.receipt(finalOperations.last(), access.localTarget))
            assertSourceOriginal(f, final, lateRaw)
            assertSourceOriginal(f, first, original)
            assertTrue(coordinator.flushLocalAfterWriters())
            assertEquals(finalOperations, access.node.sourceOperations())
            assertEquals(reloadsBefore, f.reloads.get())
            assertEquals(networkBefore, networkCalls.get())
        }
    }

}
