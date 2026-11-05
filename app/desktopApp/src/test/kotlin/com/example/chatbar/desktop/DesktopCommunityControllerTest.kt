package com.example.chatbar.desktop

import com.example.chatbar.domain.community.*
import com.example.chatbar.domain.card.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopCommunityControllerTest {
    @Test fun `next metadata prefetch is reused and explicit refresh bypasses warm metadata without downloading packages`() = runBlocking {
        val parent = Files.createTempDirectory("community-metadata-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { com.example.chatbar.desktop.security.InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            MockWebServer().use { server ->
                val first = AtomicInteger(); val next = AtomicInteger(); val other = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path!!.startsWith("/rest/v1/community_runtime_config?") -> MockResponse().setBody("""[{"enabled":true}]""")
                        request.path!!.startsWith("/rest/v1/community_items?") -> {
                            val offset = if (request.path!!.contains("offset=20&")) { next.incrementAndGet(); 20 } else { first.incrementAndGet(); 0 }
                            MockResponse().setBody(Json.encodeToString((offset until if (offset == 0) 21 else 25).map {
                                CommunityItemDto(id = "item-$it", type = "character", title = "Synthetic $it") }))
                        }
                        else -> { other.incrementAndGet(); MockResponse().setResponseCode(404) }
                    }
                }
                val typed = app.createTypedTransferController(UnconfiguredDesktopFilePicker)
                val owner = app.createCommunityController(typed, CommunityConfiguration(server.url("/").toString(), "synthetic-public"),
                    httpFactory = { OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build() })
                owner.refresh(); until { !owner.state.value.busy }
                assertEquals(20, owner.state.value.items.size); assertEquals(1, first.get()); assertTrue(owner.state.value.hasMore)
                owner.prefetchNextPage(); until { !owner.state.value.busy }
                assertEquals(20, owner.state.value.items.size); assertEquals(1, next.get())
                owner.nextPage(); until { !owner.state.value.busy }
                assertEquals(25, owner.state.value.items.size); assertEquals(1, next.get()); assertFalse(owner.state.value.hasMore)
                owner.refresh(); until { !owner.state.value.busy }
                assertEquals(20, owner.state.value.items.size); assertEquals(2, first.get()); assertEquals(0, other.get())
            }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `queued cancellation releases admission and first completion cannot clear a reentrant successor`() = runBlocking {
        val parent = Files.createTempDirectory("community-admission-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { com.example.chatbar.desktop.security.InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            MockWebServer().use { server ->
                val readStarted = AtomicBoolean()
                val release = java.util.concurrent.CountDownLatch(1)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path!!.startsWith("/rest/v1/community_runtime_config?") -> MockResponse().setBody("""[{"enabled":true}]""")
                        else -> { readStarted.set(true); release.await(5, java.util.concurrent.TimeUnit.SECONDS); MockResponse().setBody("{}").setResponseCode(503) }
                    }
                }
                val http = OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                val local = DesktopCommunityLocalPackages(app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.characterResourceStore, app.dataOperationCoordinator)
                val client = CommunityClient(CommunityConfiguration(server.url("/").toString(), "synthetic-public"), local,
                    DesktopCommunitySessionStore(com.example.chatbar.desktop.security.InMemoryDesktopSecretStore(), app.transferJson), app.transferJson, http)
                val scheduler = TestCoroutineScheduler()
                val returning = object : DesktopCommunityLoginReturn {
                    override fun prepare() = DesktopCommunityReturnStatus.READY
                    override fun release() = Unit
                }
                val owner = DesktopCommunityController(client, http, app.createTypedTransferController(UnconfiguredDesktopFilePicker),
                    app.characterRepository, app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.dataOperationCoordinator,
                    browse = {}, workDispatcher = StandardTestDispatcher(scheduler), loginReturn = returning)
                try {
                    owner.open(); assertTrue(owner.state.value.busy)
                    owner.stop(); scheduler.runCurrent()
                    assertFalse(owner.state.value.busy); assertEquals(0, server.requestCount)
                    assertTrue(client.refreshEnabledStatus(true))
                    val item = CommunityItemDto(id = "remote", type = "character", filePath = "owner/held.json").toDomain()
                    val successor = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        owner.state.drop(1).first { !it.busy }
                        owner.detail(item)
                    }
                    owner.beginLogin(); scheduler.runCurrent()
                    withTimeout(5_000) { while (!readStarted.get()) { scheduler.runCurrent(); delay(10) } }
                    successor.join()
                    assertTrue(owner.state.value.busy)
                    owner.section(true) // Refused while the actual successor still owns admission.
                    assertFalse(owner.state.value.mine)
                    release.countDown()
                    withTimeout(5_000) { while (owner.state.value.busy) { scheduler.runCurrent(); delay(10) } }
                    assertTrue(owner.state.value.error)
                } finally {
                    release.countDown()
                    val closing = async { owner.closeAndDrain() }
                    while (!closing.isCompleted) { scheduler.runCurrent(); delay(10) }
                    closing.await()
                }
            }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
    @Test fun `real community owner previews without counts imports through typed custody and obeys a later shutdown`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-community-controller-")
        val root = Files.createDirectory(parent.resolve("root"))
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, parent.resolve("bootstrap.json")))
        try {
        val enabled = AtomicBoolean(true); val corrupt = AtomicBoolean(false); val downloads = AtomicInteger()
        val data = CharacterCardPackage(card = PackagedCharacterCard(name = "Synthetic community", greeting = "Synthetic greeting"))
        val raw = app.transferJson.encodeToString(CharacterCardPackage.serializer(), data)
        val item = CommunityItemDto(id = "remote", type = "character", title = data.card.name,
            filePath = "owner/packages/character/synthetic.json", sha256 = CommunityPackagePolicy.sha256(raw.toByteArray()),
            sizeBytes = raw.toByteArray().size.toLong(), schemaVersion = 9, updatedAt = "2026-01-01")
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/rest/v1/community_runtime_config?") -> MockResponse().setBody("[{\"enabled\":${enabled.get()}}]")
                    request.path!!.startsWith("/rest/v1/community_items?") -> MockResponse().setBody(Json.encodeToString(listOf(item)))
                    request.path!!.startsWith("/storage/v1/object/public/community-packages/") -> MockResponse().setBody(if (corrupt.get()) "synthetic-corruption" else raw)
                    request.path == "/rest/v1/rpc/increment_community_download_count" -> { downloads.incrementAndGet(); MockResponse().setBody("null") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val http = OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
            val local = DesktopCommunityLocalPackages(app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.characterResourceStore, app.dataOperationCoordinator)
            val sessions = object : CommunitySessionStore {
                override fun load(): CommunitySession? = null
                override fun save(session: CommunitySession) = error("No login authorized in this fixture")
                override fun clear() = error("No logout authorized in this fixture")
            }
            val client = CommunityClient(CommunityConfiguration(server.url("/").toString(), "synthetic-public"),
                local, sessions, app.transferJson, http, loadSessionAtStartup = false)
            val typed = app.createTypedTransferController(UnconfiguredDesktopFilePicker)
            val owner = DesktopCommunityController(client, http, typed, app.characterRepository,
                app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.dataOperationCoordinator,
                browse = { error("Browser not authorized in this fixture") }, pollMillis = 60_000)
            try {
                owner.start(); until { owner.state.value.enabled }
                owner.open(); until { !owner.state.value.busy }
                assertEquals(listOf("remote"), owner.state.value.items.map { it.id })
                owner.detail(item.toDomain()); until { !owner.state.value.busy }
                assertIs<CommunityImportPayload.Character>(owner.state.value.detailPayload)
                assertEquals(0, downloads.get()); assertTrue(app.characterRepository.getAll().isEmpty())
                owner.importItem(item.toDomain()); until { !owner.state.value.busy }
                val imported = app.characterRepository.getAll().single()
                assertEquals(item.id, imported.communityItemId); assertTrue(imported.isCommunityDownload)
                assertEquals(1, downloads.get()); assertNull(typed.state.value.pendingConflict)
                corrupt.set(true); owner.detail(item.toDomain()); until { !owner.state.value.busy }
                assertTrue(owner.state.value.error); assertNull(owner.state.value.detailPayload)
                assertEquals(listOf(imported), app.characterRepository.getAll()); assertEquals(1, downloads.get())
                enabled.set(false); owner.refresh(); until { !owner.state.value.busy }
                assertFalse(owner.state.value.enabled); assertEquals(listOf(imported), app.characterRepository.getAll())
            } finally { owner.closeAndDrain() }
        }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }
}
