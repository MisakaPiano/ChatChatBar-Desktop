package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

/** Enabled route custody and actual enrolled/HTTP owners; no real credentials or provider endpoints. */
class LanSyncGroupCloudRuntimeTest {
    private fun assertSafeCatalogFailure(failure: Throwable) {
        // Coroutine stack recovery may copy a safe exception and retain that safe original as cause.
        generateSequence(failure) { it.cause }.forEach {
            assertTrue(it is IllegalStateException)
            assertEquals("SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE", it.message)
        }
    }

    private class Fixture(val root: Path) {
        val admin = LanSyncGroupAdministrationTest.Fixture(root)
        val reloads = AtomicInteger(); val wakes = AtomicInteger(); val permitted = AtomicBoolean(true)
        val reports = mutableListOf<Map<SyncStoreId, LanSyncGroupCloudRouteProgress>>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS).build()
        val owners = mutableListOf<LanSyncGroupEnrollment>(); val runtimes = mutableListOf<LanSyncGroupCloudRuntime>()
        val clock = AtomicLong()
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
        fun runtime(schedule: LanSyncGroupCloudSchedule = LanSyncGroupCloudSchedule(clock::get, { 0 })) =
            LanSyncGroupCloudRuntime(access, permitted::get, { wakes.incrementAndGet() },
                { synchronized(reports) { reports += it } }, catalog, client, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP,
                schedule, clock::get).also(runtimes::add)
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

    @Test fun explicitlyAnonymousWebDavOmitsAuthorizationInsteadOfSendingAnEmptyBasicCredential() = fixture { f ->
        WeakFixture(f.directory("anonymous-provider")).use { provider ->
            val endpoint = provider.server.url("/sync/").newBuilder().host("127.0.0.1").build().toString()
            val id = f.catalog.add(LanSyncGroupCloudDraft("synthetic anonymous drive", endpoint, "", byteArrayOf(), enabled = true))
            provider.dav.overrideRequest = { request ->
                if (request.getHeader("Authorization") != null) MockResponse().setResponseCode(401) else null
            }
            assertTrue(f.runtime().cycle())
            assertTrue(f.latest().getValue(id).selectionObserved)
            val count = provider.server.requestCount
            assertTrue(count > 0)
            repeat(count) { assertNull(requireNotNull(provider.server.takeRequest(1, TimeUnit.SECONDS)).getHeader("Authorization")) }
            f.assertOriginal()
        }
    }

    @Test fun aDamagedProtectedCatalogIsVisibleAndRetainedWithoutAProviderRequestOrAReset() = fixture { f ->
        WeakFixture(f.directory("damaged-catalog-provider")).use { provider ->
            f.add(provider)
            val file = f.root.resolve("catalog/cloud-routes.protected")
            val damaged = byteArrayOf(1, 2, 3, 4, 5)
            Files.write(file, damaged)
            val runtime = f.runtime()
            val failure = assertFailsWith<IllegalStateException> { runtime.prepareScheduledBatch() }
            assertEquals("SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE", failure.message)
            assertSafeCatalogFailure(failure)
            assertContentEquals(damaged, Files.readAllBytes(file))
            assertEquals(0, provider.server.requestCount)
            assertTrue(requireNotNull(runtime.nextDelayMillis()) > 0)
            f.assertOriginal()
        }
    }

    @Test fun aPreviouslyObservedCatalogIsRecheckedBeforeAnotherPlatformLeaseAndRetainsItsHistoricalProgressWhenDamaged() = fixture { f ->
        WeakFixture(f.directory("cached-damaged-catalog-provider")).use { provider ->
            val id = f.add(provider); val runtime = f.runtime()
            assertTrue(runtime.cycle())
            val prior = f.latest().getValue(id)
            assertTrue(prior.selectionObserved); assertNotNull(prior.lastObservedAt)
            val file = f.root.resolve("catalog/cloud-routes.protected")
            val damaged = byteArrayOf(7, 2, 9, 4, 1)
            Files.write(file, damaged)
            val requests = provider.server.requestCount
            val failure = assertFailsWith<IllegalStateException> { runtime.prepareScheduledBatch(forceIdle = true) }
            assertEquals("SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE", failure.message); assertSafeCatalogFailure(failure)
            assertEquals(requests, provider.server.requestCount)
            assertEquals(setOf(id), f.latest().keys)
            val failed = f.latest().getValue(id)
            assertFalse(failed.selectionObserved); assertEquals(prior.lastObservedAt, failed.lastObservedAt)
            assertNotEquals(prior.text, failed.text); assertTrue(failed.text.isNotBlank())
            assertTrue(requireNotNull(runtime.nextDelayMillis()) > 0)
            assertContentEquals(damaged, Files.readAllBytes(file)); f.assertOriginal()
        }
    }

    @Test fun catalogDamageDuringDiscoveryStopsBeforeOutgoingIoInsteadOfReportingNoEnabledConnections() = fixture { f ->
        WeakFixture(f.directory("mid-cycle-damaged-catalog-provider")).use { provider ->
            val id = f.add(provider); val runtime = f.runtime()
            assertTrue(runtime.cycle())
            val prior = f.latest().getValue(id)
            val file = f.root.resolve("catalog/cloud-routes.protected")
            val damaged = byteArrayOf(9, 8, 7, 6, 5)
            val corrupted = AtomicBoolean(false)
            val puts = provider.dav.puts.toList()
            val requests = provider.server.requestCount
            provider.dav.overrideRequest = { request ->
                if (request.method == "PROPFIND" && corrupted.compareAndSet(false, true)) Files.write(file, damaged)
                null
            }
            val failure = assertFailsWith<IllegalStateException> { runtime.cycle() }
            assertEquals("SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE", failure.message); assertSafeCatalogFailure(failure)
            assertTrue(corrupted.get()); assertTrue(provider.server.requestCount > requests)
            assertEquals(puts, provider.dav.puts.toList())
            assertEquals(setOf(id), f.latest().keys)
            val failed = f.latest().getValue(id)
            assertFalse(failed.selectionObserved); assertEquals(prior.lastObservedAt, failed.lastObservedAt)
            assertNotEquals(prior.text, failed.text); assertTrue(failed.text.isNotBlank())
            val stopped = provider.server.requestCount
            assertEquals("SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE",
                assertFailsWith<IllegalStateException> { runtime.prepareScheduledBatch(forceIdle = true) }.message)
            assertEquals(stopped, provider.server.requestCount)
            assertContentEquals(damaged, Files.readAllBytes(file)); f.assertOriginal()
        }
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
            val receipt = requireNotNull(owner.chunkReceipt())
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
            Files.newDirectoryStream(directory, "chunk-receipt-*.json").use { assertFalse(it.iterator().hasNext()) }
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

    @Test fun cancellingBeforeHttpInterruptsAProtectedCatalogReaderAndRetainsItsOriginalFile() = fixture { f ->
        WeakFixture(f.directory("catalog-cancel-provider")).use { provider ->
            f.add(provider)
            val file = f.root.resolve("catalog/cloud-routes.protected")
            val retained = Files.readAllBytes(file)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            var borrowed: ByteArray? = null
            val gated = LanSyncGroupCloudRoutes(f.root.resolve("catalog"), f.access.trust, f.access.device,
                { it.reversedArray() }, { bytes ->
                    borrowed = bytes; entered.countDown()
                    try { check(release.await(30, TimeUnit.SECONDS)); bytes.reversedArray() }
                    catch (failure: InterruptedException) { interrupted.set(true); throw failure }
                }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            val runtime = LanSyncGroupCloudRuntime(f.access, f.permitted::get, { f.wakes.incrementAndGet() },
                { synchronized(f.reports) { f.reports += it } }, gated, f.client,
                WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).also(f.runtimes::add)
            try {
                coroutineScope {
                    val job = launch(Dispatchers.IO) { runtime.cycle() }
                    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                    withTimeout(5_000) { job.cancelAndJoin(); runtime.drain() }
                    assertTrue(job.isCancelled)
                }
                assertTrue(interrupted.get(), "Cancellation must interrupt the local custody read before HTTP exists")
                assertTrue(requireNotNull(borrowed).all { it == 0.toByte() })
                assertContentEquals(retained, Files.readAllBytes(file))
                assertEquals(0, provider.server.requestCount)
                assertFalse(Files.exists(f.access.work.resolve("cloud-runtime")))
                f.assertOriginal()
            } finally { release.countDown() }
        }
    }

    @Test fun actualRuntimeDiscoversTheLogicalAssemblyNamespaceAlongsideWholeObjectCompatibility() = fixture { f ->
        WeakFixture(f.directory("assembly-provider")).use { provider ->
            val id = f.add(provider)
            val target = f.access.targetPublication(f.directory("assembly-seed-plan"),
                SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id), prepare = false)
            val plan = target.prepare()
            WebDavHttpTransport(provider.server.url("/sync/").newBuilder().host("127.0.0.1").build(), f.client,
                policy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).use { transport ->
                val parts = WeakWebDavChunkAssembly(transport, f.directory("assembly-seed-parts"), id,
                    chunkBytes = 512, budget = WebDavIoBudget(minimumFreeBytes = 0))
                for (item in plan.objects) {
                    val output = ByteArrayOutputStream()
                    assertNotNull(f.access.reader.readTo(item.key, output, item.descriptor))
                    parts.append(CiphertextPutRequest(item.key, item.operation, item.descriptor), output.toByteArray().inputStream())
                }
            }
            assertTrue(provider.dav.files.keys.none { it.contains("/weak-attempts-v1/") })
            val inspected = AtomicInteger()
            provider.dav.overrideRequest = { request ->
                if (request.method == "GET" && request.requestUrl!!.encodedPath.contains("/weak-assemblies-v1/commits/"))
                    inspected.incrementAndGet()
                null
            }
            val runtime = f.runtime()
            assertTrue(runtime.cycle())
            assertTrue(inspected.get() > 0, "The actual runtime must inspect the provider's logical assembly claims")
            assertTrue(f.latest().getValue(id).selectionObserved)
            assertTrue(f.wakes.get() > 0)
            f.assertOriginal()
        }
    }

    @Test fun localPreflightAndFrequentTimerChecksNeverCreateHttpForEmptyDisabledOrIdleRoutes() = fixture { f ->
        WeakFixture(f.directory("scheduled-idle-provider")).use { provider ->
            val runtime = f.runtime()
            assertEquals(0L, runtime.nextDelayMillis())
            assertFalse(runtime.prepareScheduledBatch()); assertNull(runtime.nextDelayMillis())
            val id = f.add(provider, enabled = false)
            assertFalse(runtime.prepareScheduledBatch()); assertNull(runtime.nextDelayMillis())
            assertFalse(Files.exists(f.access.work.resolve("cloud-runtime")))
            assertEquals(0, provider.server.requestCount)
            f.catalog.setEnabled(id, true); runtime.configurationChanged()
            assertTrue(runtime.prepareScheduledBatch()); assertEquals(0, provider.server.requestCount)
            assertFalse(Files.exists(f.access.work.resolve("cloud-runtime")))
            assertTrue(runtime.cycle(forceIdle = false))
            val observed = requireNotNull(f.latest().getValue(id).lastObservedAt)
            val requests = provider.server.requestCount
            repeat(5) {
                assertFalse(runtime.prepareScheduledBatch())
                assertTrue(runtime.cycle(forceIdle = false)) // A skipped, already observed selection keeps its historical status.
                assertEquals(requests, provider.server.requestCount)
                assertEquals(observed, f.latest().getValue(id).lastObservedAt)
                assertTrue(f.latest().getValue(id).selectionObserved)
            }
            assertEquals(60_000L, runtime.nextDelayMillis())
            f.clock.addAndGet(59_999); assertFalse(runtime.prepareScheduledBatch())
            f.clock.incrementAndGet(); assertTrue(runtime.prepareScheduledBatch())
            assertEquals(requests, provider.server.requestCount)
            assertTrue(runtime.cycle(forceIdle = false)); assertTrue(provider.server.requestCount > requests)
            assertEquals(observed, f.latest().getValue(id).lastObservedAt)
            f.assertOriginal()
        }
    }

    @Test fun aRateLimitedAssemblyGetCannotReuseHistoricalCompletionOrBypassItsFailureFloor() {
        for ((status, retryAfter) in listOf(429 to null, 503 to null, 429 to "120")) fixture { f ->
            WeakFixture(f.directory("assembly-backoff-provider")).use { provider ->
                val id = f.add(provider)
                val schedule = LanSyncGroupCloudSchedule(f.clock::get, { 0 })
                val runtime = f.runtime(schedule)
                assertTrue(runtime.cycle())
                val observed = requireNotNull(f.latest().getValue(id).lastObservedAt)
                val target = f.access.work.resolve("cloud-runtime/${id.value}/target")
                val original = requireNotNull(f.access.targetPublication(target,
                    SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id), prepare = false).chunkReceipt())
                val wakes = f.wakes.get()
                val throttled = AtomicInteger(); val requestsAtResponse = AtomicInteger()
                provider.dav.overrideRequest = { request ->
                    if (request.method == "GET" && request.requestUrl!!.encodedPath.contains("/weak-assemblies-v1/commits/")) {
                        throttled.incrementAndGet(); requestsAtResponse.set(provider.server.requestCount)
                        MockResponse().setResponseCode(status).apply { if (retryAfter != null) setHeader("Retry-After", retryAfter) }
                    } else null
                }
                // Explicit wake bypasses the old success idle interval, but never a failed-request floor.
                assertFalse(runtime.cycle())
                assertEquals(1, throttled.get())
                assertEquals(wakes, f.wakes.get())
                val requests = provider.server.requestCount
                assertEquals(requestsAtResponse.get(), requests, "No further provider HTTP is allowed after this route is throttled")
                val floor = if (retryAfter == null) 15_000L else 120_000L
                assertEquals(floor, runtime.nextDelayMillis())
                assertEquals(LanSyncGroupCloudSchedule.FailureCategory.TRANSIENT, schedule.state(id)?.failure)
                assertFalse(f.latest().getValue(id).selectionObserved)
                assertEquals(observed, f.latest().getValue(id).lastObservedAt)
                assertEquals(original, f.access.targetPublication(target,
                    SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id), prepare = false).chunkReceipt())
                repeat(2) {
                    assertFalse(runtime.prepareScheduledBatch(forceIdle = true))
                    assertFalse(runtime.cycle()); assertFalse(runtime.cycle(forceIdle = false))
                    assertEquals(requests, provider.server.requestCount)
                }
                f.clock.addAndGet(floor - 1)
                assertFalse(runtime.prepareScheduledBatch(forceIdle = true)); assertFalse(runtime.cycle())
                assertEquals(requests, provider.server.requestCount)
                f.clock.incrementAndGet(); provider.dav.overrideRequest = null
                assertTrue(runtime.prepareScheduledBatch(forceIdle = true)); assertTrue(runtime.cycle())
                assertTrue(provider.server.requestCount > requests)
                assertTrue(f.latest().getValue(id).selectionObserved)
                assertEquals(observed, f.latest().getValue(id).lastObservedAt)
                assertEquals(original, f.access.targetPublication(target,
                    SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id), prepare = false).chunkReceipt())
                f.assertOriginal()
            }
        }
    }

    @Test fun aReal503RetryAfterBlocksBothFrequentTimersAndExplicitWakeUntilItsRelativeFloor() = fixture { f ->
        WeakFixture(f.directory("retry-after-provider")).use { provider ->
            val id = f.add(provider); val runtime = f.runtime()
            provider.dav.overrideRequest = { request ->
                if (request.method == "PROPFIND") MockResponse().setResponseCode(503).setHeader("Retry-After", "120") else null
            }
            assertTrue(runtime.prepareScheduledBatch()); assertFalse(runtime.cycle(forceIdle = false))
            val requests = provider.server.requestCount; assertTrue(requests > 0)
            assertEquals(120_000L, runtime.nextDelayMillis())
            repeat(4) {
                assertFalse(runtime.prepareScheduledBatch()); assertFalse(runtime.prepareScheduledBatch(forceIdle = true))
                assertFalse(runtime.cycle()); assertFalse(runtime.cycle(forceIdle = false))
                assertEquals(requests, provider.server.requestCount)
            }
            f.clock.addAndGet(119_999); assertFalse(runtime.prepareScheduledBatch(forceIdle = true))
            f.clock.incrementAndGet(); provider.dav.overrideRequest = null
            assertTrue(runtime.prepareScheduledBatch()); assertTrue(runtime.cycle(forceIdle = false))
            assertTrue(provider.server.requestCount > requests); assertTrue(f.latest().getValue(id).selectionObserved)
            assertNotNull(f.latest().getValue(id).lastObservedAt); f.assertOriginal()
        }
    }

    @Test fun aMissingAssemblyCandidateDoesNotBecomeARouteRateLimitWhenTheSelectionWasAlreadyObserved() = fixture { f ->
        WeakFixture(f.directory("missing-assembly-provider")).use { provider ->
            val id = f.add(provider); val schedule = LanSyncGroupCloudSchedule(f.clock::get, { 0 }); val runtime = f.runtime(schedule)
            assertTrue(runtime.cycle())
            val observed = requireNotNull(f.latest().getValue(id).lastObservedAt)
            val missing = AtomicInteger()
            provider.dav.overrideRequest = { request ->
                if (request.method == "GET" && request.requestUrl!!.encodedPath.contains("/weak-assemblies-v1/commits/")) {
                    missing.incrementAndGet(); MockResponse().setResponseCode(404)
                } else null
            }
            assertTrue(runtime.cycle())
            assertTrue(missing.get() > 0); assertNull(schedule.state(id)?.failure)
            assertTrue(f.latest().getValue(id).selectionObserved)
            assertEquals(observed, f.latest().getValue(id).lastObservedAt)
            f.assertOriginal()
        }
    }

    @Test fun anAuthenticationBackoffDoesNotDelayTheHealthyRouteAndExplicitWakeCannotBypassIt() = fixture { f ->
        WeakFixture(f.directory("scheduled-auth-provider")).use { failed ->
            WeakFixture(f.directory("scheduled-healthy-provider")).use { healthy ->
                val failing = f.add(failed); val good = f.add(healthy)
                val schedule = LanSyncGroupCloudSchedule(f.clock::get, { 0 }); val runtime = f.runtime(schedule)
                failed.dav.overrideRequest = { request -> if (request.method == "PROPFIND") MockResponse().setResponseCode(401) else null }
                assertFalse(runtime.cycle())
                val badRequests = failed.server.requestCount; val goodRequests = healthy.server.requestCount
                val observed = requireNotNull(f.latest().getValue(good).lastObservedAt)
                assertTrue(requireNotNull(schedule.state(failing)).needsConfiguration)
                assertEquals(60_000L, runtime.nextDelayMillis())
                assertTrue(runtime.prepareScheduledBatch(forceIdle = true))
                assertFalse(runtime.cycle())
                assertEquals(badRequests, failed.server.requestCount); assertTrue(healthy.server.requestCount > goodRequests)
                assertFalse(f.latest().getValue(failing).selectionObserved)
                assertTrue(f.latest().getValue(good).selectionObserved)
                assertEquals(observed, f.latest().getValue(good).lastObservedAt)
                f.assertOriginal()
            }
        }
    }

    @Test fun deniedPlatformDefersWithoutHttpAndAnExplicitWakeOnlyBypassesThatIdleWait() = fixture { f ->
        WeakFixture(f.directory("platform-idle-provider")).use { provider ->
            f.add(provider); val runtime = f.runtime(); f.permitted.set(false)
            // Preflight precedes foreground promotion: it needs no transport permission or HTTP.
            assertTrue(runtime.prepareScheduledBatch()); assertEquals(0, provider.server.requestCount)
            assertFalse(runtime.cycle()); assertEquals(60_000L, runtime.nextDelayMillis())
            assertEquals(0, provider.server.requestCount)
            f.permitted.set(true)
            assertFalse(runtime.prepareScheduledBatch()); assertEquals(60_000L, runtime.nextDelayMillis())
            assertTrue(runtime.prepareScheduledBatch(forceIdle = true)); assertEquals(0, provider.server.requestCount)
            provider.dav.overrideRequest = { request ->
                if (request.method == "PROPFIND") MockResponse().setResponseCode(503).setHeader("Retry-After", "120") else null
            }
            assertFalse(runtime.cycle()); val requests = provider.server.requestCount
            runtime.deferUntilWake()
            assertFalse(runtime.prepareScheduledBatch(forceIdle = true)); assertEquals(120_000L, runtime.nextDelayMillis())
            assertFalse(runtime.cycle()); assertEquals(requests, provider.server.requestCount)
            f.assertOriginal()
        }
    }

    @Test fun dirtyDuringAnOldFrozenUploadPreservesItsReceiptTimeWithoutClaimingTheNewSourceWasObserved() = fixture { f ->
        WeakFixture(f.directory("dirty-in-flight-provider")).use { provider ->
            val id = f.add(provider); val runtime = f.runtime(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val gated = AtomicBoolean(false)
            provider.dav.overrideRequest = { request ->
                if (request.method == "PUT" && gated.compareAndSet(false, true)) {
                    entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
                }
                null
            }
            try {
                coroutineScope {
                    val old = async(Dispatchers.IO) { runtime.cycle(recoverNative = false, outgoingOnly = true) }
                    try {
                        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                        LanSyncJsonFiles.write(f.card, CharacterCard.serializer(), CharacterCard("card", "synthetic new dirty source", createdAt = 1, updatedAt = 2))
                        val edited = Files.readAllBytes(f.card)
                        val next = f.access.node.commit(f.access.node.inspect().review(setOf("character_cards/card")))
                        f.access.node.upload(next.entry.request.operation, f.access.replica, f.access.localTarget)
                        runtime.markDirty(); release.countDown()
                        assertFalse(withTimeout(10_000) { old.await() })
                        val historical = requireNotNull(f.latest().getValue(id).lastObservedAt)
                        assertFalse(f.latest().getValue(id).selectionObserved)
                        val target = f.access.work.resolve("cloud-runtime/${id.value}/target")
                        Files.newDirectoryStream(target, "chunk-receipt-*.json").use { assertTrue(it.iterator().hasNext()) }
                        assertFalse(runtime.prepareScheduledBatch()); assertTrue(runtime.prepareScheduledBatch(forceIdle = true))
                        assertTrue(runtime.cycle(recoverNative = false, outgoingOnly = true))
                        assertTrue(f.latest().getValue(id).selectionObserved)
                        val receipt = requireNotNull(f.access.targetPublication(target, SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id),
                            recoverNative = false, prepare = false).chunkReceipt())
                        assertEquals(receipt.objects.maxOf { it.readbackObservation.observedAtMillis }, f.latest().getValue(id).lastObservedAt)
                        val latest = f.latest().getValue(id).lastObservedAt; val requests = provider.server.requestCount
                        assertTrue(runtime.cycle(recoverNative = false, outgoingOnly = true))
                        assertEquals(requests, provider.server.requestCount); assertEquals(latest, f.latest().getValue(id).lastObservedAt)
                        assertNotNull(historical)
                        assertEquals(2L, f.author().state().committedSequence)
                        assertEquals(f.source.entry, f.author().entry(f.source.entry.request.operation))
                        assertEquals(next.entry, f.author().entry(next.entry.request.operation))
                        assertContentEquals(edited, Files.readAllBytes(f.card)); assertContentEquals(f.origin,
                            Files.readAllBytes(f.admin.profile.resolve("node/author/origin.protected")))
                        val original = requireNotNull(f.admin.workspace.recovery.read(f.source.entry.request.sourceCheckpoint))
                        var found = false
                        f.admin.workspace.recovery.originals(original) { row, path -> if (row.relative == "entities/character_cards/card.json") {
                            found = true; assertContentEquals(f.raw, Files.readAllBytes(path))
                        } }
                        assertTrue(found); assertEquals(0, f.reloads.get())
                    } finally { release.countDown() }
                }
            } finally { release.countDown() }
        }
    }
}
