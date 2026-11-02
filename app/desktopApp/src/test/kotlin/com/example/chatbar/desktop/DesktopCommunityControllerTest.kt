package com.example.chatbar.desktop

import com.example.chatbar.domain.community.*
import com.example.chatbar.domain.card.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopCommunityControllerTest {
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
