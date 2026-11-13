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
    fun validate(path: Path, outer: JsonObject, budget: LanSyncResourceBudget = LanSyncResourceBudget()): SaveSlot {
        lanSyncSafe(path); budget.requireResource(Files.size(path))
        val library = lanSyncJson.decodeFromJsonElement(SaveSlot.serializer(), outer)
        ZipFile(path.toFile()).use { zip ->
            val entries = mutableListOf<java.util.zip.ZipEntry>()
            val sourceEntries = zip.entries()
            while (sourceEntries.hasMoreElements()) {
                require(entries.size < budget.maximumArchiveEntries) { "SYNC_SLOT_PACKAGE_ENTRIES" }
                entries.add(sourceEntries.nextElement())
            }
            require(entries.map { it.name }.toSet().size == entries.size) { "SYNC_SLOT_PACKAGE_ENTRIES" }
            var declared = 0L
            entries.forEach { entry ->
                require(entry.size >= 0) { "SYNC_SLOT_PACKAGE_LIMIT" }
                declared = lanSyncAddLength(declared, entry.size)
            }
            require(budget.maximumExpandedArchiveBytes == null || declared <= budget.maximumExpandedArchiveBytes) { "SYNC_SLOT_PACKAGE_LIMIT" }
            budget.requireSpace(path.parent, declared)
            var total = 0L
            entries.forEach { entry ->
                require(!entry.isDirectory && entry.name.split('/').none { it in setOf("", ".", "..") } && !entry.name.contains('\\') &&
                    (entry.name in setOf("manifest.json", "messages.jsonl", "rag.jsonl", "voices.jsonl") || entry.name.startsWith("media/images/") || entry.name.startsWith("media/audio/"))) { "SYNC_SLOT_PACKAGE_PATH" }
                require(entry.size >= 0) { "SYNC_SLOT_PACKAGE_LIMIT" }
                val crc = CRC32(); var count = 0L
                var lineBytes = 0
                zip.getInputStream(entry).use { input -> val buffer = ByteArray(16 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        count = lanSyncAddLength(count, n.toLong()); total = lanSyncAddLength(total, n.toLong())
                        require(count <= entry.size && total <= declared) { "SYNC_SLOT_PACKAGE_LIMIT" }
                        if (entry.name.endsWith(".jsonl")) for (index in 0 until n) {
                            if (buffer[index] == 10.toByte()) lineBytes = 0 else {
                                require(lineBytes < budget.maximumArchiveLineBytes) { "SYNC_SLOT_LINE_BUDGET" }; lineBytes++
                            }
                        }
                        crc.update(buffer, 0, n)
                    }
                }
                require(count == entry.size && crc.value == entry.crc) { "SYNC_SLOT_PACKAGE_CRC" }
                if (entry.name.endsWith(".jsonl")) {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    try { java.io.InputStreamReader(zip.getInputStream(entry), decoder).use { reader ->
                        val chars = CharArray(8192); while (reader.read(chars) >= 0) { }
                    } } catch (failure: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("SYNC_INVALID_UTF8", failure) }
                }
            }
            val manifest = requireNotNull(zip.getEntry("manifest.json")) { "SYNC_SLOT_PACKAGE_MANIFEST" }
            require(manifest.size in 1..budget.maximumRecordBytes.toLong()) { "SYNC_SLOT_MANIFEST_BUDGET" }
            val bytes = zip.getInputStream(manifest).use { it.readBytes() }
            val nested = lanSyncJson.decodeFromJsonElement(SaveSlot.serializer(), lanSyncJson.parseToJsonElement(lanSyncUtf8(bytes)))
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
