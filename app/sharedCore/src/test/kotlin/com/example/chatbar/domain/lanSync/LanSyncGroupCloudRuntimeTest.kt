package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

/** Enabled route custody and actual enrolled/HTTP owners; no real credentials or provider endpoints. */
class LanSyncGroupCloudRuntimeTest {
    private class Fixture(val root: Path) {
        val admin = LanSyncGroupAdministrationTest.Fixture(root)
        val reloads = AtomicInteger(); val wakes = AtomicInteger(); val permitted = AtomicBoolean(true)
        val reports = mutableListOf<Map<SyncStoreId, LanSyncGroupCloudRouteProgress>>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS).build()
        val owners = mutableListOf<LanSyncGroupEnrollment>(); val runtimes = mutableListOf<LanSyncGroupCloudRuntime>()
        lateinit var access: LanSyncGroupRuntimeAccess
        lateinit var catalog: LanSyncGroupCloudRoutes
        lateinit var source: LanSyncGroupSourceCommit
        lateinit var origin: ByteArray
        lateinit var raw: ByteArray
        val card get() = admin.data.resolve("entities/character_cards/card.json")
        fun directory(name: String) = Files.createDirectory(root.resolve(name))
        fun author() = SyncAuthorJournal(admin.profile.resolve("node/author"), access.trust,
            Base64.getEncoder().encodeToString(admin.identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        suspend fun seed() {
            admin.seed()
            val owner = LanSyncGroupEnrollment(admin.profile, admin.workspace, object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) = work()
                override suspend fun reload(sessionIds: Set<String>) { reloads.incrementAndGet() }
            }, admin.identity, { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }).also(owners::add)
            access = requireNotNull(owner.runtimeAccess())
            var cursor: CommitCursor? = null
            do { cursor = access.node.discover(access.replica, cursor).nextCursor } while (cursor != null)
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "synthetic runtime original", createdAt = 1, updatedAt = 1))
            raw = Files.readAllBytes(card)
            source = access.node.commit(access.node.inspect().review(setOf("character_cards/card")))
            access.node.upload(source.entry.request.operation, access.replica, access.localTarget)
            origin = Files.readAllBytes(admin.profile.resolve("node/author/origin.protected"))
            catalog = LanSyncGroupCloudRoutes(directory("catalog"), access.trust, access.device,
                { it.reversedArray() }, { it.reversedArray() }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        }
        fun add(provider: WeakFixture, name: String = "synthetic drive", enabled: Boolean = true): SyncStoreId {
            val password = "synthetic password".toByteArray()
            try { return catalog.add(LanSyncGroupCloudDraft(name, provider.server.url("/sync/").newBuilder().host("127.0.0.1").build().toString(),
                "synthetic user", password, enabled)) } finally { password.fill(0) }
        }
        fun runtime() = LanSyncGroupCloudRuntime(access, permitted::get, { wakes.incrementAndGet() },
            { synchronized(reports) { reports += it } }, catalog, client, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).also(runtimes::add)
        fun latest() = synchronized(reports) { reports.lastOrNull().orEmpty() }
        suspend fun assertOriginal() {
            assertEquals(1L, author().state().committedSequence)
            assertEquals(source.entry, author().entry(source.entry.request.operation))
            assertEquals(listOf(source.entry.request.operation), access.node.sourceOperations())
            assertContentEquals(origin, Files.readAllBytes(admin.profile.resolve("node/author/origin.protected")))
            assertContentEquals(raw, Files.readAllBytes(card))
            assertEquals(0, reloads.get())
        }
        suspend fun close() {
            runtimes.forEach { it.close() }; runtimes.forEach { it.drain() }
            owners.forEach { it.close() }; admin.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
    private fun fixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-cloud-runtime-").toRealPath(); val f = Fixture(root)
        try { f.seed(); work(f) } finally { f.close(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun platformDeniedDoesNotOpenTheCatalogAndDisabledOrEmptyTargetsNeverUseHttp() = fixture { f ->
        f.permitted.set(false)
        val production = LanSyncGroupCloudRuntime(f.access, f.permitted::get, { error("SYNTHETIC_FORBIDDEN_WAKE") }, { })
        try {
            assertFalse(production.cycle())
            assertFalse(Files.exists(f.access.work.resolve("cloud-connections")))
        } finally { production.close(); production.drain() }
        WeakFixture(f.directory("disabled-provider")).use { provider ->
            f.add(provider, enabled = false)
            val runtime = f.runtime()
            assertFalse(runtime.cycle())
            f.permitted.set(true)
            assertTrue(runtime.cycle())
            assertEquals(0, provider.server.requestCount); assertEquals(0, f.wakes.get())
            f.assertOriginal()
        }
    }

    @Test fun enabledCatalogRunsRealHttpThenOutgoingOnlyReusesTheExactHistoricalReceiptWithoutDiscoveryOrNewTime() = fixture { f ->
        WeakFixture(f.directory("enabled-provider")).use { provider ->
            val id = f.add(provider); val runtime = f.runtime()
            assertTrue(runtime.cycle())
            val progress = f.latest().getValue(id)
            assertNotNull(progress.lastObservedAt); assertNull(progress.pendingOperations)
            assertTrue(progress.selectionObserved)
            assertTrue(provider.dav.files.isNotEmpty()); assertEquals(1, f.wakes.get())
            val owner = f.access.targetPublication(f.access.work.resolve("cloud-runtime/${id.value}/target"),
                SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id), prepare = false)
            val receipt = requireNotNull(owner.weakReceipt())
            assertEquals(receipt.objects.maxOf { it.readbackObservation.observedAtMillis }, progress.lastObservedAt)
            val requests = provider.server.requestCount
            repeat(requests) {
                val request = requireNotNull(provider.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals(Credentials.basic("synthetic user", "synthetic password", Charsets.UTF_8), request.getHeader("Authorization"))
            }
            assertTrue(runtime.cycle(recoverNative = false, outgoingOnly = true))
            assertEquals(requests, provider.server.requestCount)
            assertEquals(progress.lastObservedAt, f.latest().getValue(id).lastObservedAt)
            assertEquals(1, f.wakes.get())
            assertFalse(f.latest().toString().contains("synthetic password"))
            f.assertOriginal()
        }
    }

    @Test fun oneFailedRouteDoesNotPreventAnotherEnabledRouteCompletingAndNearbyRoutesRemainOwnedSeparately() = fixture { f ->
        WeakFixture(f.directory("failing-provider")).use { failed ->
            WeakFixture(f.directory("healthy-provider")).use { healthy ->
                val failingId = f.add(failed, "synthetic unavailable"); val healthyId = f.add(healthy, "synthetic healthy")
                failed.dav.overrideRequest = { MockResponse().setResponseCode(401) }
                val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
                val body = byteArrayOf(1, 3, 7)
                val descriptor = CiphertextDescriptor(body.size.toLong(), CiphertextSha256.parse(lanSyncHash(body)))
                val near = FileCiphertextStore(f.directory("nearby-store"))
                near.put(CiphertextPutRequest(key, SyncStoreId.random(), descriptor), body.inputStream())
                f.access.reader.routes("nearby", listOf(LanSyncGroupReplicaReader.Route("synthetic-nearby", near)))
                assertFalse(f.runtime().cycle())
                assertNull(f.latest().getValue(failingId).lastObservedAt)
                assertFalse(f.latest().getValue(failingId).selectionObserved)
                assertNotNull(f.latest().getValue(healthyId).lastObservedAt)
                assertTrue(f.latest().getValue(healthyId).selectionObserved)
                assertTrue(healthy.dav.files.isNotEmpty())
                val output = java.io.ByteArrayOutputStream()
                assertEquals(CiphertextObjectMetadata(key, descriptor), f.access.reader.readTo(key, output, descriptor))
                assertContentEquals(body, output.toByteArray())
                f.assertOriginal()
            }
        }
    }

    @Test fun disablingTheEnabledCatalogDuringPutStopsThisRouteWithoutCompletionAndKeepsItsPartialEvidence() = fixture { f ->
        WeakFixture(f.directory("disable-during-provider")).use { provider ->
            val id = f.add(provider); var changed = false
            provider.dav.overrideRequest = { request ->
                if (request.method == "PUT" && !changed) { changed = true; f.catalog.setEnabled(id, false) }
                null
            }
            val runtime = f.runtime()
            assertFalse(runtime.cycle())
            assertTrue(changed); assertFalse(f.catalog.rows().single().enabled)
            assertFalse(id in f.latest(), "Disabled routes must leave the current-enabled aggregate")
            val directory = f.access.work.resolve("cloud-runtime/${id.value}/target")
            Files.newDirectoryStream(directory, "weak-receipt-*.json").use { assertFalse(it.iterator().hasNext()) }
            val retained = provider.dav.files.mapValues { it.value.copyOf() }
            assertTrue(retained.isNotEmpty())
            val before = provider.server.requestCount
            assertTrue(runtime.cycle()); assertEquals(before, provider.server.requestCount)
            retained.forEach { (path, bytes) -> assertContentEquals(bytes, provider.dav.files.getValue(path)) }
            f.assertOriginal()
        }
    }

    @Test fun pauseCancelsARealStalledRequestDrainsAndAllowsAnExplicitLaterCycleWithoutResettingTheDailyOwner() = fixture { f ->
        WeakFixture(f.directory("pause-provider")).use { provider ->
            f.add(provider); val entered = CountDownLatch(1)
            provider.dav.overrideRequest = { request ->
                if (request.method == "PROPFIND") { entered.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) } else null
            }
            val runtime = f.runtime()
            coroutineScope {
                val job = async(Dispatchers.IO) { runtime.cycle() }
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                runtime.pause()
                assertFalse(withTimeout(5_000) { job.await() })
                withTimeout(5_000) { runtime.drain() }
            }
            provider.dav.overrideRequest = null
            assertTrue(runtime.cycle())
            runtime.close()
            val requests = provider.server.requestCount
            assertFalse(runtime.cycle()); assertEquals(requests, provider.server.requestCount)
            assertTrue(f.catalog.rows().single().enabled)
            f.assertOriginal()
        }
    }

    @Test fun cancellingTheCycleJobClosesItsStalledHttpBeforeJoiningTheBlockingIoChild() = fixture { f ->
        WeakFixture(f.directory("cancel-provider")).use { provider ->
            f.add(provider); val entered = CountDownLatch(1)
            provider.dav.overrideRequest = { request ->
                if (request.method == "PROPFIND") { entered.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) } else null
            }
            val runtime = f.runtime()
            coroutineScope {
                val job = launch(Dispatchers.IO) { runtime.cycle() }
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                withTimeout(5_000) { job.cancelAndJoin(); runtime.drain() }
                assertTrue(job.isCancelled)
            }
            assertTrue(provider.dav.files.isEmpty())
            f.assertOriginal()
        }
    }
}
