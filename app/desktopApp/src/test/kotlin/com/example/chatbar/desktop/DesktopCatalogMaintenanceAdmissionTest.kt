package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopCatalogMaintenanceAdmissionTest {
    private fun database(parent: Path, name: String): Pair<Path, DanbooruCatalogMetadata> {
        val file = parent.resolve("$name.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE tags(name TEXT, cn_name TEXT, category INTEGER, post_count INTEGER)") }
            db.autoCommit = false
            db.prepareStatement("INSERT INTO tags VALUES (?, '合成', 0, ?)").use { row ->
                repeat(10000) { row.setString(1, "${name}_$it"); row.setInt(2, it); row.executeUpdate() }
            }; db.commit()
        }
        val bytes = Files.readAllBytes(file)
        val digest = MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()); update(bytes) }
        return file to DanbooruCatalogMetadata(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, "inline", bytes.size.toLong(), 10000, "tags")
    }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }; output.toByteArray()
    }
    private class Fixture(val parent: Path, val bundle: Map<String, ByteArray>, val beforeAsset: (String) -> Unit = {}) {
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { name ->
                beforeAsset(name); bundle[name] ?: """{"entries":[]}""".toByteArray()
            })
        suspend fun close() { app.close(); check(parent.toFile().deleteRecursively()) }
    }
    private fun bundle(parent: Path, source: Path, metadata: DanbooruCatalogMetadata): Map<String, ByteArray> {
        val index = parent.resolve("inline-index.sqlite")
        DesktopNovelAiSqlDatabase(source).use { db -> DesktopNovelAiIndexWriter(index).use {
            buildNovelAiRankedIndex(it, db, "tags", metadata.sourceSha, "danbooru")
        } }
        val bytes = Files.readAllBytes(index)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return mapOf("danbooru/catalog.json" to Json.encodeToString(DanbooruCatalogMetadata.serializer(), metadata).toByteArray(),
            "danbooru/tag.sqlite.bundle" to gzip(Files.readAllBytes(source)),
            "tag_completion/danbooru.json" to """{"format":2,"source":"${metadata.sourceSha}","sha256":"$sha","bytes":${bytes.size}}""".toByteArray(),
            "tag_completion/danbooru.sqlite.binz" to gzip(bytes))
    }
    private suspend fun snapshotWaits(app: DesktopAppContainer, snapshot: Deferred<*>) {
        withTimeout(8000) { while (!snapshot.isCompleted && app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) delay(10) }
        assertFalse(snapshot.isCompleted, "snapshot must wait for the complete catalog write owner")
    }

    @Test fun `actual application cold catalog installation retains admission through both databases before a real snapshot`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("ccb-catalog-cold-admission-")
        val (source, metadata) = database(parent, "old")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fixture = Fixture(parent, bundle(parent, source, metadata)) { name ->
            if (name == "danbooru/tag.sqlite.bundle") { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        }
        try {
            val install = async(Dispatchers.Default) { fixture.app.novelAiInfrastructure.tags.catalogMetadata() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(8, TimeUnit.SECONDS) })
            val snapshot = async(Dispatchers.Default) { fixture.app.coordinatedSnapshotService.createSnapshot() }
            snapshotWaits(fixture.app, snapshot)
            release.countDown(); assertEquals(metadata, withTimeout(10000) { install.await() })
            val saved = withTimeout(10000) { snapshot.await() }; assertTrue(saved.validation.valid)
            val names = Files.walk(saved.directory).use { files -> files.map { it.fileName.toString() }.toList() }
            assertTrue("${metadata.sourceSha}.sqlite" in names)
            assertTrue(names.any { it.endsWith(".sqlite") && it != "${metadata.sourceSha}.sqlite" })
            assertTrue(fixture.app.coordinatedSnapshotService.validateSnapshot(saved.directory).valid)
        } finally { release.countDown(); fixture.close() }
    }

    @Test fun `actual catalog updater holds download index publication and cleanup until the real snapshot can capture the new authority`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("ccb-catalog-update-admission-")
        val (old, metadata) = database(parent, "old")
        val fixture = Fixture(parent, bundle(parent, old, metadata))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val catalog = fixture.app.novelAiInfrastructure.tags; assertEquals(metadata, catalog.catalogMetadata())
            val (fresh, next) = database(parent, "fresh"); val payload = Files.readAllBytes(fresh)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(payload.toResponseBody()).build()
            }.build()
            val info = DanbooruCatalogUpdateInfo(metadata, next.sourceSha, "inline", "https://raw.githubusercontent.com/synthetic/tag.sqlite", "", next.sourceSizeBytes)
            val install = async(Dispatchers.Default) { DesktopNovelAiCatalogUpdate(catalog, client).install(info) {} }
            assertTrue(withContext(Dispatchers.IO) { entered.await(8, TimeUnit.SECONDS) })
            val snapshot = async(Dispatchers.Default) { fixture.app.coordinatedSnapshotService.createSnapshot() }
            snapshotWaits(fixture.app, snapshot)
            release.countDown(); withTimeout(10000) { install.await() }
            val saved = withTimeout(10000) { snapshot.await() }; assertTrue(saved.validation.valid)
            val pointers = Files.walk(saved.directory).use { files -> files.filter { it.fileName.toString() == "active-catalog.json" }.toList() }
            assertEquals(next, Json.decodeFromString(DanbooruCatalogMetadata.serializer(), Files.readString(pointers.single())))
            assertEquals(next, catalog.catalogMetadata())
            assertFalse(Files.list(catalog.updateRoot).use { files -> files.anyMatch { it.fileName.toString().endsWith(".part") } })
            assertTrue(fixture.app.coordinatedSnapshotService.validateSnapshot(saved.directory).valid)
        } finally { release.countDown(); fixture.close() }
    }
}
