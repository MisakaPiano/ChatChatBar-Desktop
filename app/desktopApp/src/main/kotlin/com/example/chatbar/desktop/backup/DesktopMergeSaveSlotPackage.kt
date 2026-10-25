package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.*
import com.example.chatbar.data.local.entity.SaveSlot
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Rewrites a verified v8 package without interpreting media bytes or activating session restore. */
internal object DesktopMergeSaveSlotPackage {
    class Catalog internal constructor(val manifest: DesktopMergeRecord, val declarations: Set<Pair<String, String>>, val sha256: String) {
        override fun toString() = "MergePackageCatalog(declarations=${declarations.size})"
    }

    /** Only compact declarations survive this owned immutable scan; no message/media collection. */
    suspend fun catalog(source: Path, slot: DesktopMergeRecord, workspace: Path): Catalog = withContext(Dispatchers.IO) {
        require(slot.type == "save_slots") { "MERGE_PACKAGE_OWNER_TYPE" }
        DesktopBackupScratch.create(workspace, "merge-slot-catalog-").use { scratch ->
            val original = scratch.directory.resolve("original.cbsave")
            val hash = scratch.copy(source, original, DesktopSaveSlotArchive.MAX_ARCHIVE)
            check(DesktopSaveSlotInspector(true).inspect(original).status == SlotInspectStatus.STRUCTURE_VALID) { "MERGE_SOURCE_PACKAGE_INVALID" }
            ZipFile(original.toFile()).use { zip ->
                val bytes = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).use { it.readNBytes(256 * 1024 + 1) }
                check(bytes.size <= 256 * 1024) { "MERGE_PACKAGE_MANIFEST_BUDGET" }
                val manifest = DesktopMergeRecord(slot.relative, parse(bytes))
                val nested = backupJson.decodeFromJsonElement<SaveSlot>(manifest.value)
                val library = backupJson.decodeFromJsonElement<SaveSlot>(slot.value)
                DesktopSaveSlotLibraryEnvelope.requireCompatible(library, nested, Files.size(original))
                val owners = DesktopMergeIdentity().forPackageOwner(nested.id, nested.sessionId, library.id, library.sessionId)
                val context = currentCoroutineContext(); val identities = DesktopMergeIdentity()
                val declarations = identities.declarations(manifest) { context.ensureActive() }.toMutableSet()
                for ((name, type) in listOf("messages.jsonl" to "chat_messages", "rag.jsonl" to "vector_chunks", "voices.jsonl" to "generated_voice_messages")) {
                    val entry = zip.getEntry(name) ?: continue
                    zip.getInputStream(entry).use { input -> lines(input) { value ->
                        declarations += identities.declarations(DesktopMergeRecord("entities/$type/record.json", value)) { context.ensureActive() }
                        check(declarations.size <= 80000) { "MERGE_PACKAGE_DECLARATION_BUDGET" }
                    } }
                }
                check(DesktopArchiveFiles.hash(source, DesktopSaveSlotArchive.MAX_ARCHIVE) == hash) { "MERGE_SOURCE_PACKAGE_CHANGED" }
                Catalog(slot, declarations.mapTo(linkedSetOf()) { (type, id) -> type to owners.value(type, id) }, hash)
            }
        }
    }

    class Prepared internal constructor(val original: Path, val rewritten: Path, val fileName: String,
        val originalSha256: String, val rewrittenSha256: String, val bytes: Long,
        val manifest: JsonObject,
        private val scratch: DesktopBackupScratch) : AutoCloseable {
        override fun close() = scratch.close()
        override fun toString() = "MergeSaveSlotPackage(bytes=$bytes)"
    }

    suspend fun prepare(source: Path, slot: DesktopMergeRecord, identity: DesktopMergeIdentity,
        otherMemoryRecords: Collection<DesktopMergeRecord>, workspace: Path,
        onEntry: suspend () -> Unit = {}): Prepared = withContext(Dispatchers.IO) {
        require(slot.type == "save_slots") { "MERGE_PACKAGE_OWNER_TYPE" }
        val scratch = DesktopBackupScratch.create(workspace, "merge-slot-")
        try {
            val context = currentCoroutineContext()
            val original = scratch.directory.resolve("original.cbsave")
            val hash = scratch.copy(source, original, DesktopSaveSlotArchive.MAX_ARCHIVE)
            val inspector = DesktopSaveSlotInspector(retainHistoricalVoices = true)
            check(inspector.inspect(original).status == SlotInspectStatus.STRUCTURE_VALID) { "MERGE_SOURCE_PACKAGE_INVALID" }
            val output = scratch.directory.resolve("rewritten.cbsave")
            val oldSlotId = requireNotNull(slot.text("id"))
            val name = identity.value("save_slots", oldSlotId) + ".cbsave"
            var rewrittenManifest: JsonObject? = null
            require(name.matches(Regex("[A-Za-z0-9_-]{1,128}\\.cbsave"))) { "MERGE_PACKAGE_TARGET_NAME" }
            ZipFile(original.toFile()).use { zip ->
                val manifestBytes = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).use { it.readNBytes(256 * 1024 + 1) }
                check(manifestBytes.size <= 256 * 1024) { "MERGE_PACKAGE_MANIFEST_BUDGET" }
                val manifest = parse(manifestBytes)
                val nested = backupJson.decodeFromJsonElement<SaveSlot>(manifest)
                val library = backupJson.decodeFromJsonElement<SaveSlot>(slot.value)
                DesktopSaveSlotLibraryEnvelope.requireCompatible(library, nested, Files.size(original))
                val packageIdentity = identity.forPackageOwner(nested.id, nested.sessionId, library.id, library.sessionId)
                val manifestRecord = DesktopMergeRecord(slot.relative, manifest)
                val mapper = DesktopMergeGraphRemapper(otherMemoryRecords + manifestRecord, packageIdentity) { context.ensureActive() }
                ZipOutputStream(scratch.output(output)).use { out ->
                    val buffer = ByteArray(32768)
                    try {
                        for (entry in zip.entries().asSequence()) {
                            context.ensureActive()
                            out.putNextEntry(ZipEntry(entry.name))
                            zip.getInputStream(entry).use { input ->
                                when (entry.name) {
                                    "manifest.json" -> {
                                        val mapped = mapper.rewrite(manifestRecord).toMutableMap()
                                        val ref = mapped.getValue("packageRef").jsonObject.toMutableMap()
                                        ref["fileName"] = JsonPrimitive(name); ref["byteLength"] = JsonPrimitive(0)
                                        mapped["packageRef"] = JsonObject(ref)
                                        mapped["name"] = JsonPrimitive(library.name)
                                        mapped["createdAt"] = JsonPrimitive(library.createdAt)
                                        rewrittenManifest = JsonObject(mapped)
                                        out.write(JsonObject(mapped).toString().toByteArray(Charsets.UTF_8))
                                    }
                                    "messages.jsonl", "rag.jsonl", "voices.jsonl" -> {
                                        val type = when (entry.name) { "messages.jsonl" -> "chat_messages"; "rag.jsonl" -> "vector_chunks"; else -> "generated_voice_messages" }
                                        lines(input) { value ->
                                            context.ensureActive()
                                            val record = DesktopMergeRecord("entities/$type/record.json", value)
                                            out.write(mapper.rewrite(record).toString().toByteArray(Charsets.UTF_8)); out.write('\n'.code)
                                        }
                                    }
                                    else -> while (true) {
                                        context.ensureActive(); val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n)
                                    }
                                }
                            }
                            out.closeEntry()
                            onEntry()
                        }
                    } finally { buffer.fill(0) }
                }
            }
            FileChannel.open(output, StandardOpenOption.WRITE).use { it.force(true) }
            check(inspector.inspect(output).status == SlotInspectStatus.STRUCTURE_VALID) { "MERGE_REWRITTEN_PACKAGE_INVALID" }
            check(DesktopArchiveFiles.hash(original, DesktopSaveSlotArchive.MAX_ARCHIVE) == hash &&
                DesktopArchiveFiles.hash(source, DesktopSaveSlotArchive.MAX_ARCHIVE) == hash) { "MERGE_SOURCE_PACKAGE_CHANGED" }
            Prepared(original, output, name, hash, DesktopArchiveFiles.hash(output, DesktopSaveSlotArchive.MAX_ARCHIVE), Files.size(output),
                requireNotNull(rewrittenManifest), scratch)
        } catch (failure: Throwable) {
            try { scratch.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun parse(bytes: ByteArray): JsonObject {
        val writer = java.io.StringWriter()
        bytes.inputStream().strictUtf8Reader().use { reader -> BackupJsonStream(reader, writer) { value, _ -> value }.copy() }
        return backupJson.parseToJsonElement(writer.toString()).jsonObject
    }

    private suspend fun lines(input: InputStream, consume: suspend (JsonObject) -> Unit) {
        val buffered = input.buffered(32768); val record = ByteArrayOutputStream()
        var count = 0
        while (true) {
            if (++count % 8192 == 0) currentCoroutineContext().ensureActive()
            val next = buffered.read()
            if (next == -1 || next == '\n'.code) {
                val bytes = record.toByteArray(); record.reset()
                if (bytes.any { it != 13.toByte() && it != 32.toByte() && it != 9.toByte() }) consume(parse(bytes))
                if (next == -1) return
            } else {
                check(record.size() < 1024 * 1024) { "MERGE_PACKAGE_RECORD_BUDGET" }; record.write(next)
            }
        }
    }
}
