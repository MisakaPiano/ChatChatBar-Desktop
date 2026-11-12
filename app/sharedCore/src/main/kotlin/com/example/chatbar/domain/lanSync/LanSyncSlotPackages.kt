package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** An immutable v8 archive keeps its own source namespace, as in the shared import envelope. */
internal object LanSyncSlotPackages {
    fun validate(path: Path, outer: JsonObject): SaveSlot {
        lanSyncSafe(path); require(Files.size(path) in 1..LanSyncSnapshot.MAX_RESOURCE) { "SYNC_SLOT_PACKAGE_LIMIT" }
        val library = lanSyncJson.decodeFromJsonElement(SaveSlot.serializer(), outer)
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= 8192 && entries.map { it.name }.toSet().size == entries.size) { "SYNC_SLOT_PACKAGE_ENTRIES" }
            var total = 0L
            entries.forEach { entry ->
                require(!entry.isDirectory && entry.name.split('/').none { it in setOf("", ".", "..") } && !entry.name.contains('\\') &&
                    (entry.name in setOf("manifest.json", "messages.jsonl", "rag.jsonl", "voices.jsonl") || entry.name.startsWith("media/images/") || entry.name.startsWith("media/audio/"))) { "SYNC_SLOT_PACKAGE_PATH" }
                require(entry.size in 0..LanSyncSnapshot.MAX_RESOURCE) { "SYNC_SLOT_PACKAGE_LIMIT" }
                val crc = CRC32(); var count = 0L
                zip.getInputStream(entry).use { input -> val buffer = ByteArray(16 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; count += n; total += n
                        require(count <= entry.size && total <= LanSyncSnapshot.MAX_RESOURCE) { "SYNC_SLOT_PACKAGE_LIMIT" }; crc.update(buffer, 0, n) }
                }
                require(count == entry.size && crc.value == entry.crc) { "SYNC_SLOT_PACKAGE_CRC" }
            }
            val manifest = requireNotNull(zip.getEntry("manifest.json")) { "SYNC_SLOT_PACKAGE_MANIFEST" }
            require(manifest.size in 1..256 * 1024)
            val bytes = zip.getInputStream(manifest).use { it.readBytes() }
            val nested = lanSyncJson.decodeFromJsonElement(SaveSlot.serializer(), lanSyncJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)))
            val inner = requireNotNull(nested.packageRef); val reference = requireNotNull(library.packageRef)
            require(library.schemaVersion == 8 && nested.schemaVersion == 8 && (reference.byteLength <= 0 || reference.byteLength == Files.size(path))) { "SYNC_SLOT_PACKAGE_ENVELOPE" }
            require(library.id == nested.id && library.sessionId == nested.sessionId || library.id != nested.id && reference.fileName == "${library.id}.cbsave") { "SYNC_SLOT_PACKAGE_OWNER" }
            require(nested.copy(id = library.id, sessionId = library.sessionId, name = library.name, createdAt = library.createdAt,
                packageRef = inner.copy(fileName = reference.fileName, byteLength = reference.byteLength)) == library) { "SYNC_SLOT_PACKAGE_CONTENT" }
            // Existing shared validation checks counts, ordered message IDs and included media dependencies.
            runBlocking { SaveSlotPackageStorage.Reader(path.parent.toFile(), library, zip, lanSyncJson).validate() }
            return nested
        }
    }
}
