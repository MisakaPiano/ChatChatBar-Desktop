package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import java.io.InputStream
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Holds only credential-free individual files plus volatile secrets. Never a runnable application root. */
internal class DesktopFullBackupStaging private constructor(
    val source: DesktopFullBackupSource.Verified,
    val files: Map<String, Path>,
    val entities: Map<String, JsonObject>,
    val preferences: JsonObject,
    val secrets: DesktopImportSecrets,
    private val hashes: Map<String, String>,
    private val scratch: DesktopBackupScratch,
) : AutoCloseable {
    suspend fun verifyUnchanged() {
        files.forEach { (name, path) ->
            currentCoroutineContext().ensureActive()
            check(DesktopArchiveFiles.hash(path, DesktopFullBackupSource.MAX_FILE) == hashes.getValue(name)) { "IMPORT_STAGING_CHANGED" }
        }
    }
    override fun toString() = "FullBackupStaging(code=${source.manifest.appVersionCode},files=${files.size})"
    override fun close() { try { secrets.close() } finally { scratch.close() } }

    companion object {
        suspend fun prepare(source: Path, password: CharArray?, workspace: Path): DesktopFullBackupStaging = withContext(Dispatchers.IO) {
            val scratch = DesktopBackupScratch.create(workspace, "incoming-")
            val secrets = DesktopImportSecrets()
            try {
                val files = linkedMapOf<String, Path>()
                var preferences: JsonObject? = null
                val context = currentCoroutineContext()
                val verified = DesktopFullBackupSource.verify(source, password, object : DesktopFullBackupSource.TentativeSink {
                    override fun entity(path: String, original: JsonObject, raw: ByteArray) {
                        val clean = secrets.entity(path, original)
                        val destination = scratch.directory.resolve("entry-${files.size}")
                        if (DesktopBackupPolicy.entityType(path) in setOf("moment_posts", "moment_tasks", "save_slots", "chat_messages", "generated_voice_messages")) scratch.write(destination, raw)
                        else scratch.write(destination, clean.toString().toByteArray(Charsets.UTF_8))
                        check(files.put(path, destination) == null)
                    }
                    override fun preferences(original: JsonObject) { preferences = secrets.preferences(original) }
                    override fun media(path: String, input: InputStream) {
                        val destination = scratch.directory.resolve("entry-${files.size}")
                        scratch.output(destination).use { output ->
                            val buffer = ByteArray(32768)
                            try { while (true) {
                                context.ensureActive()
                                val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n)
                            } } finally { buffer.fill(0) }
                        }
                        check(files.put(path, destination) == null)
                    }
                }) { context.ensureActive() }
                context.ensureActive()
                check(files.keys == verified.inventory.keys)
                val hashes = files.mapValues { (_, path) -> DesktopArchiveFiles.hash(path, DesktopFullBackupSource.MAX_FILE) }
                DesktopFullBackupStaging(verified, files.toMap(), DesktopBackupRecords.files(files.filterKeys { it.startsWith("entities/") }, hashes), requireNotNull(preferences), secrets, hashes, scratch)
            } catch (failure: Throwable) {
                secrets.close()
                try { scratch.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            } finally { password?.fill('\u0000') }
        }
    }
}
