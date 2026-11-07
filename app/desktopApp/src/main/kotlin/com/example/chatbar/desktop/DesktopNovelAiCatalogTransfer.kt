package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import java.nio.file.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/** Original database + metadata cross the wire; ranked indexes remain disposable derived files. */
internal object DesktopNovelAiCatalogTransfer {
    const val DATABASE = "danbooru_catalog/tag.sqlite"
    const val METADATA = "danbooru_catalog/catalog.json"
    val wirePaths = setOf(DATABASE, METADATA)
    private val json = Json { ignoreUnknownKeys = true }

    class Source(val database: Path, val manifest: Path, val metadata: DanbooruCatalogMetadata) {
        val files get() = linkedMapOf(DATABASE to database, METADATA to manifest)
    }

    fun validate(database: Path, manifest: Path): Source {
        DesktopArchiveFiles.safe(database, false); DesktopArchiveFiles.safe(manifest, false)
        check(Files.size(manifest) <= 1024 * 1024) { "CATALOG_METADATA_SIZE" }
        val metadata = json.decodeFromString(DanbooruCatalogMetadata.serializer(), Files.readString(manifest))
        require(metadata.sourceSha.matches(Regex("[a-f0-9]{40}")) && metadata.sourceSizeBytes in 1..256_000_000)
        DesktopNovelAiCatalogUpdate.verify(database, metadata.sourceSizeBytes, metadata.sourceSha)
        val structure = DesktopNovelAiSqlDatabase(database).use { validateNovelAiCatalogStructure(it) }
        check(structure.tableName == metadata.tableName &&
            (metadata.rowCount == 0L || structure.rowCount == metadata.rowCount)) { "CATALOG_METADATA_CONTENT" }
        return Source(database, manifest, metadata)
    }

    fun portable(root: Path): Source? {
        val directory = root.resolve("danbooru_catalog")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
        DesktopArchiveFiles.safe(directory, true)
        Files.newDirectoryStream(directory).use { files ->
            check(files.map { it.fileName.toString() }.toSet() == setOf("tag.sqlite", "catalog.json")) { "CATALOG_FILE_SET" }
        }
        return validate(root.resolve(DATABASE), root.resolve(METADATA))
    }

    /** Native authority wins over an older retained transport copy, including during PC merge capture. */
    fun installed(root: Path): Source? {
        val directory = root.resolve("auxiliary/novelai")
        val manifest = directory.resolve("active-catalog.json")
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return portable(root)
        DesktopArchiveFiles.safe(directory, true); DesktopArchiveFiles.safe(manifest, false)
        check(Files.size(manifest) <= 1024 * 1024)
        val metadata = json.decodeFromString(DanbooruCatalogMetadata.serializer(), Files.readString(manifest))
        require(metadata.sourceSha.matches(Regex("[a-f0-9]{40}")))
        return validate(directory.resolve("${metadata.sourceSha}.sqlite"), manifest)
    }

    /** First use of a verified imported pair. No network or bundled replacement on invalid input. */
    suspend fun activatePortable(source: Source, nativeRoot: Path) {
        Files.createDirectories(nativeRoot); DesktopArchiveFiles.safe(nativeRoot, true)
        val suffix = UUID.randomUUID().toString()
        val stagedDatabase = nativeRoot.resolve("catalog-$suffix.part")
        val stagedIndex = nativeRoot.resolve("index-$suffix.part")
        val stagedManifest = nativeRoot.resolve("manifest-$suffix.part")
        val metadata = source.metadata
        val manifestBytes = Files.readAllBytes(source.manifest)
        val manifestHash = DesktopArchiveFiles.hash(source.manifest, 1024L * 1024)
        check(json.decodeFromString(DanbooruCatalogMetadata.serializer(), manifestBytes.toString(Charsets.UTF_8)) == metadata)
        check(java.security.MessageDigest.getInstance("SHA-256").digest(manifestBytes).joinToString("") { "%02x".format(it.toInt() and 255) } == manifestHash)
        try {
            DesktopArchiveFiles.copyNew(source.database, stagedDatabase, 256L shl 20)
            DesktopNovelAiCatalogUpdate.verify(stagedDatabase, metadata.sourceSizeBytes, metadata.sourceSha)
            DesktopNovelAiSqlDatabase(stagedDatabase).use { db -> DesktopNovelAiIndexWriter(stagedIndex).use {
                buildNovelAiRankedIndex(it, db, metadata.tableName, metadata.sourceSha, "danbooru")
            } }
            validate(source.database, source.manifest)
            check(DesktopArchiveFiles.hash(source.manifest, 1024L * 1024) == manifestHash) { "CATALOG_SOURCE_CHANGED" }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                check(!Files.exists(nativeRoot.resolve("active-catalog.json"), LinkOption.NOFOLLOW_LINKS)) { "CATALOG_AUTHORITY_CHANGED" }
                val database = nativeRoot.resolve("${metadata.sourceSha}.sqlite")
                if (Files.exists(database, LinkOption.NOFOLLOW_LINKS))
                    DesktopNovelAiCatalogUpdate.verify(database, metadata.sourceSizeBytes, metadata.sourceSha)
                else Files.move(stagedDatabase, database, StandardCopyOption.ATOMIC_MOVE)
                val index = nativeRoot.resolve("index-${metadata.sourceSha}.sqlite")
                // Same-source derived generations can be replaced by a fully prepared index.
                Files.move(stagedIndex, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                java.io.FileOutputStream(stagedManifest.toFile()).use {
                    it.write(manifestBytes); it.fd.sync()
                }
                Files.move(stagedManifest, nativeRoot.resolve("active-catalog.json"), StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            Files.deleteIfExists(stagedDatabase); Files.deleteIfExists(stagedIndex); Files.deleteIfExists(stagedManifest)
        }
    }
}
