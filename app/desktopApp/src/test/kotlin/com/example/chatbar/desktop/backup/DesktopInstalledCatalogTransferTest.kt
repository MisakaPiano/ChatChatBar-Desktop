package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo
import java.nio.file.*
import java.security.MessageDigest
import java.sql.DriverManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopInstalledCatalogTransferTest {
    private fun database(base: Path, name: String): Pair<Path, DanbooruCatalogMetadata> {
        val file = base.resolve("$name.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE tags(name TEXT, cn_name TEXT, category INTEGER, post_count INTEGER)") }
            db.autoCommit = false
            db.prepareStatement("INSERT INTO tags VALUES (?, '合成', 0, ?)").use { row ->
                repeat(10000) { row.setString(1, "${name}_$it"); row.setInt(2, it); row.executeUpdate() }
            }; db.commit()
        }
        val bytes = Files.readAllBytes(file)
        val sha = MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()); update(bytes) }
            .digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return file to DanbooruCatalogMetadata(sha, "synthetic-$name", bytes.size.toLong(), 10000, "tags")
    }
    private fun portable(root: Path, source: Pair<Path, DanbooruCatalogMetadata>) {
        Files.createDirectories(root.resolve("danbooru_catalog"))
        Files.copy(source.first, root.resolve(DesktopNovelAiCatalogTransfer.DATABASE))
        Files.writeString(root.resolve(DesktopNovelAiCatalogTransfer.METADATA),
            Json.encodeToString(DanbooruCatalogMetadata.serializer(), source.second))
    }
    private fun tags(root: Path, gate: DesktopDataOperationCoordinator = DesktopDataOperationCoordinator()) = DesktopNovelAiTagCatalog(
        DesktopNovelAiCatalogAssets(root.resolve("auxiliary/novelai"), gate, gate::withSynchronousNormalOperation) {
            error("Imported catalog must work offline without a bundled fallback: $it")
        })
    private fun own(root: Path, base: Path) = assertIs<DesktopDataRootOwnershipResult.Acquired>(
        DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")))).ownership
    private suspend fun prepare(base: Path, computer: DesktopFullBackupMapping.Export, phone: DesktopFullBackupStaging,
        selection: DesktopMergePreview.Selection, name: String): Path {
        val root = Files.createDirectory(base.resolve(name))
        val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("keys-$name")), MergeRootSyntheticProtector)
        own(root, base).use {
            val pending = authority.begin()
            DesktopMergeRootPreparation.prepare(root, authority, pending, computer, phone, selection, base.resolve("work")).use {
                authority.complete(pending, it.files); authority.verifyAndResolve()
            }
        }
        return root
    }
    private suspend fun preview(computer: DesktopFullBackupMapping.Export, phone: DesktopFullBackupStaging): DesktopMergePreview =
        DesktopMergePreview.create(DesktopMergeRootPreparation.computerEntities(computer), phone.entities,
            computer.files.filterKeys { !it.startsWith("entities/") }.mapValues { DesktopArchiveFiles.hash(it.value, DesktopFullBackupSource.MAX_FILE) },
            phone.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 }, phone.source.manifest.scope)

    @Test fun `actual updated catalog exports selected original pair and cold imported lookup and reexport retain the chosen offline version`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("ccb-installed-catalog-roundtrip-")
        val root = Files.createDirectory(base.resolve("source")); val work = Files.createDirectory(base.resolve("work"))
        val old = database(base, "old"); val fresh = database(base, "fresh"); portable(root, old)
        val gate = DesktopDataOperationCoordinator(); val focus = DesktopAudioFocus()
        try {
            own(root, base).use {
                val tags = tags(root, gate); assertEquals(old.second, tags.catalogMetadata())
                val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
                    .protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(Files.readAllBytes(fresh.first).toResponseBody()).build() }.build()
                DesktopNovelAiCatalogUpdate(tags, client).install(DanbooruCatalogUpdateInfo(old.second, fresh.second.sourceSha,
                    fresh.second.sourceCommitTime, "https://raw.githubusercontent.com/synthetic/tag.sqlite", "", fresh.second.sourceSizeBytes)) {}
                val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
                val chosen = AppBackupSelection(globalOptions = setOf("tagCatalog"))
                val catalog = service.catalog()
                assertTrue(catalog.estimate(chosen).bytes > catalog.estimate(AppBackupSelection(globalOptions = setOf("themeMode"))).bytes)
                val archive = base.resolve("selected.cbbackup")
                service.exportModern(archive, null, selection = chosen)
                assertTrue(service.verifyFile(archive, null).summary.fileCount >= 2)
                DesktopFullBackupStaging.prepare(archive, null, work).use { phone ->
                    assertContentEquals(Files.readAllBytes(fresh.first), Files.readAllBytes(phone.files.getValue(DesktopNovelAiCatalogTransfer.DATABASE)))
                    assertEquals(fresh.second, Json.decodeFromString(DanbooruCatalogMetadata.serializer(),
                        Files.readString(phone.files.getValue(DesktopNovelAiCatalogTransfer.METADATA))))
                    val empty = Files.createDirectory(base.resolve("empty"))
                    DesktopFullBackupMapping(empty).export(work, 88, StrictBackupZipReader.Limits().expanded, forMerge = true).use { pc ->
                        val p = preview(pc, phone); assertTrue(p.conflicts.isEmpty())
                        val destination = prepare(base, pc, phone, p.select(emptyMap()), "imported")
                        val reopened = tags(destination)
                        assertEquals(fresh.second, reopened.catalogMetadata())
                        assertTrue(reopened.search("fresh_123").candidates.isNotEmpty())
                        val completions = mutableListOf<NovelAiTagCandidate>()
                        reopened.streamCompletion("fresh_123", { completions += it }, { fail(it) })
                        assertTrue(completions.isNotEmpty())
                        DesktopFullBackupMapping(destination).export(work, 88, StrictBackupZipReader.Limits().expanded).use { again ->
                            assertContentEquals(Files.readAllBytes(fresh.first), Files.readAllBytes(again.files.getValue(DesktopNovelAiCatalogTransfer.DATABASE)))
                        }
                    }
                }
                val omitted = base.resolve("omitted.cbbackup")
                service.exportModern(omitted, null, selection = AppBackupSelection(globalOptions = setOf("themeMode")))
                DesktopFullBackupStaging.prepare(omitted, null, work).use { assertTrue(it.files.keys.none { p -> p.startsWith("danbooru_catalog/") }) }
                assertContentEquals(Files.readAllBytes(old.first), Files.readAllBytes(root.resolve(DesktopNovelAiCatalogTransfer.DATABASE)))
            }
        } finally { gate.closeAndDrain(); focus.closeAndDrain(); assertTrue(base.toFile().deleteRecursively()) }
    }

    @Test fun `PC capture survives card only merge and different catalog versions require one explicit source choice`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("ccb-installed-catalog-merge-")
        val root = Files.createDirectory(base.resolve("pc")); val work = Files.createDirectory(base.resolve("work"))
        val old = database(base, "computer"); val other = database(base, "phone"); portable(root, old)
        try {
            assertEquals(old.second, tags(root).catalogMetadata())
            // The PC capture must come from native authority even when no transport copy remains.
            Files.delete(root.resolve(DesktopNovelAiCatalogTransfer.DATABASE)); Files.delete(root.resolve(DesktopNovelAiCatalogTransfer.METADATA))
            Files.delete(root.resolve("danbooru_catalog"))
            val mapping = DesktopFullBackupMapping(root)
            mapping.export(work, 88, StrictBackupZipReader.Limits().expanded, forMerge = true).use { pc ->
                val cardArchive = Files.write(base.resolve("card.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = mapOf(
                    "entities/character_cards/card.json" to """{"id":"card","name":"Synthetic","createdAt":1,"updatedAt":1}""".toByteArray())))
                DesktopFullBackupStaging.prepare(cardArchive, null, work).use { phone ->
                    val p = preview(pc, phone)
                    val destination = prepare(base, pc, phone, p.select(emptyMap()), "card-merged")
                    assertEquals(old.second, tags(destination).catalogMetadata())
                }
                val phoneRoot = Files.createDirectory(base.resolve("phone-root")); portable(phoneRoot, other)
                DesktopFullBackupMapping(phoneRoot).export(work, 88, StrictBackupZipReader.Limits().expanded).use { export ->
                    val archive = base.resolve("catalog.cbbackup"); DesktopFullBackupWriter.export(export.files, archive, null)
                    own(root, base).use {
                        val actual = DesktopMergeImportService(DesktopDataRootResolution.Resolved(root,
                            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, base.resolve("bootstrap.json")), DesktopDataOperationCoordinator(),
                            com.example.chatbar.desktop.security.InMemoryDesktopSecretStore(), {}, { it() })
                        try {
                            val inspected = actual.inspect(archive, null)
                            val details = assertNotNull(inspected.catalogDetails)
                            assertTrue(details.computer.single().contains(old.second.sourceSha.take(12)))
                            assertTrue(details.phone.single().contains(other.second.sourceSha.take(12)))
                            assertTrue(details.computer.single().contains("10000"))
                        } finally { actual.close() }
                    }
                    DesktopFullBackupStaging.prepare(archive, null, work).use { phone ->
                        val p = preview(pc, phone); val conflict = p.conflicts.single()
                        assertEquals(DesktopMergePreview.CATALOG_KEY, conflict.key)
                        assertEquals(setOf(DesktopMergePreview.Choice.KEEP_PC, DesktopMergePreview.Choice.TAKE_PHONE, DesktopMergePreview.Choice.SKIP), conflict.choices)
                        assertFailsWith<IllegalArgumentException> { p.select(emptyMap()) }
                        for (choice in conflict.choices) {
                            val destination = prepare(base, pc, phone, p.select(mapOf(conflict.key to choice)), "choice-${choice.name}")
                            assertEquals(if (choice == DesktopMergePreview.Choice.TAKE_PHONE) other.second else old.second, tags(destination).catalogMetadata())
                        }
                    }
                }
                mapping.verifySource(pc)
                val manifest = root.resolve("auxiliary/novelai/active-catalog.json")
                Files.writeString(manifest, Files.readString(manifest).replace("synthetic-computer", "changed-computer"))
                assertFailsWith<IllegalStateException> { mapping.verifySource(pc) }
            }
        } finally { assertTrue(base.toFile().deleteRecursively()) }
    }

    @Test fun `incomplete or hash mismatched imported catalog refuses activation without a bundled fallback or authority publication`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("ccb-installed-catalog-invalid-")
        try {
            val source = database(base, "valid")
            val root = Files.createDirectory(base.resolve("incomplete")); portable(root, source)
            Files.delete(root.resolve(DesktopNovelAiCatalogTransfer.DATABASE))
            assertFailsWith<IllegalStateException> { tags(root).catalogMetadata() }
            assertFalse(Files.exists(root.resolve("auxiliary/novelai/active-catalog.json")))
            Files.copy(source.first, root.resolve(DesktopNovelAiCatalogTransfer.DATABASE))
            Files.write(root.resolve(DesktopNovelAiCatalogTransfer.DATABASE), byteArrayOf(0), StandardOpenOption.APPEND)
            assertFailsWith<IllegalStateException> { tags(root).catalogMetadata() }
            assertFalse(Files.exists(root.resolve("auxiliary/novelai/active-catalog.json")))
        } finally { assertTrue(base.toFile().deleteRecursively()) }
    }
}
