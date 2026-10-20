package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.data.local.entity.AppSettings
import java.io.*
import java.nio.file.*
import java.util.zip.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

class DesktopFullBackupArchiveTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("ccb-backup-test-")
        val work = Files.createDirectory(root.resolve("work"))
        val archive = DesktopFullBackupArchive(work)
        override fun close() { root.toFile().deleteRecursively() }
        fun input(name: String, bytes: ByteArray) = root.resolve(name).also { Files.write(it, bytes) }
    }
    private val settings get() = backupJson.encodeToString(AppSettings()).toByteArray()

    /** Independent ZIP/inventory construction following the selected official v1 envelope. */
    private fun golden(files: Map<String, ByteArray>, password: CharArray? = null,
        preferences: BackupPreferences = BackupPreferences(), mutateInventory: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(AppBackupCodec.encrypting(output, password)).use { zip ->
            fun entry(name: String, value: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() }
            files.forEach { (name, value) -> entry("files/$name", value) }
            val prefs = backupJson.encodeToString(preferences).toByteArray()
            entry("preferences.json", prefs)
            val records = files.map { (name, bytes) -> AppBackupFileRecord(name, bytes.size.toLong(),
                if (mutateInventory) "0".repeat(64) else DesktopBackupPolicy.digest(bytes)) }
            entry("inventory.jsonl", records.joinToString("\n", postfix = "\n") { backupJson.encodeToString(it) }.toByteArray())
            entry("manifest.json", backupJson.encodeToString(AppBackupSummary(appVersionCode = 84, appVersionName = "1.4.5",
                createdAt = 1, sourceFilesRoot = "/data/user/0/com.example.chatbar/files",
                counts = files.keys.filter { it.startsWith("entities/") }.groupingBy(DesktopBackupPolicy::entityType).eachCount(),
                fileCount = files.size, totalBytes = files.values.sumOf { it.size.toLong() },
                preferencesSha256 = DesktopBackupPolicy.digest(prefs))).toByteArray())
        }
        return output.toByteArray()
    }
    @Test fun `official plain envelope and inventory materialize only validated records`() = runBlocking {
        Fixture().use { f ->
            val content = mapOf("entities/app_settings.json" to settings, "images/sample.png" to byteArrayOf(1, 2, 3))
            val file = f.input("official.cbbackup", golden(content))
            val original = Files.readAllBytes(file)
            val prepared = f.archive.inspect(file, null)
            assertEquals(2, prepared.summary.fileCount)
            content.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(prepared.files.getValue(path))) }
            f.archive.verify(prepared)
            assertContentEquals(original, Files.readAllBytes(file))
        }
    }
    @Test fun `encrypted source authenticates wrong password and truncated tail before plaintext staging`() = runBlocking {
        Fixture().use { f ->
            val password = "synthetic-password".toCharArray()
            val bytes = golden(mapOf("entities/app_settings.json" to settings), password)
            val file = f.input("encrypted.cbbackup", bytes)
            assertFails { f.archive.inspect(file, "wrong-password".toCharArray()) }
            assertFalse(Files.walk(f.work).use { it.anyMatch { path -> path.fileName.toString() == "validated-payload.zip" } })
            assertFails { f.archive.inspect(f.input("truncated.cbbackup", bytes.copyOf(bytes.size - 8)), password) }
            assertFalse(Files.walk(f.work).use { it.anyMatch { path -> path.fileName.toString() == "validated-payload.zip" } })
            assertEquals(1, f.archive.inspect(file, password).files.size)
            password.fill('\u0000')
        }
    }
    @Test fun `credential payload is refused before plaintext file is created`() = runBlocking {
        Fixture().use { f ->
            val file = f.input("with-credential.cbbackup", golden(mapOf("entities/app_settings.json" to settings),
                "synthetic-password".toCharArray(), BackupPreferences(novelAiToken = "synthetic-not-a-real-key")))
            assertFails { f.archive.inspect(file, "synthetic-password".toCharArray()) }
            assertEquals(listOf("source.cbbackup"), Files.walk(f.work).use { stream -> stream.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList() })
            val entity = backupJson.encodeToString(AppSettings(siliconFlowApiKey = "synthetic-not-a-real-key")).toByteArray()
            assertFails { f.archive.inspect(f.input("model-credential.cbbackup", golden(mapOf("entities/app_settings.json" to entity))), null) }
            assertFalse(Files.walk(f.work).use { it.anyMatch { path -> path.fileName.toString() == "validated-payload.zip" } })
        }
    }
    @Test fun `unknown stores traversal case collisions and invalid hashes preserve source and refuse`() = runBlocking {
        Fixture().use { f ->
            val cases = listOf(
                golden(mapOf("entities/unknown/s.json" to "{}".toByteArray())),
                golden(mapOf("images/../escape.png" to byteArrayOf(1))),
                golden(mapOf("images/A.png" to byteArrayOf(1), "images/a.png" to byteArrayOf(2))),
                golden(mapOf("images/sample.png" to byteArrayOf(1)), mutateInventory = true),
            )
            cases.forEachIndexed { i, bytes ->
                val source = f.input("invalid-$i.cbbackup", bytes)
                assertFails { f.archive.inspect(source, null) }
                assertContentEquals(bytes, Files.readAllBytes(source))
            }
            assertFalse(Files.walk(f.work).use { it.anyMatch { path -> path.fileName.toString() == "validated-payload.zip" } })
        }
    }
    @Test fun `export verifies exact file and refuses existing destination with or without encryption`() = runBlocking {
        Fixture().use { f ->
            val sources = mapOf("entities/app_settings.json" to f.input("settings.json", settings))
            listOf(null, "synthetic-password".toCharArray()).forEachIndexed { i, password ->
                val target = f.root.resolve("export-$i.cbbackup")
                val hash = f.archive.export(sources, target, "synthetic-root", password)
                assertEquals(hash, DesktopArchiveFiles.hash(target, DesktopBackupPolicy.MAX_TOTAL))
                val prepared = f.archive.inspect(target, password)
                assertContentEquals(settings, Files.readAllBytes(prepared.files.values.single()))
                assertFails { f.archive.export(sources, target, "synthetic-root", password) }
                assertEquals(hash, DesktopArchiveFiles.hash(target, DesktopBackupPolicy.MAX_TOTAL))
                password?.fill('\u0000')
            }
        }
    }
    @Test fun `changed staged record cannot be published`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.archive.inspect(f.input("source.cbbackup", golden(mapOf("images/a.png" to byteArrayOf(1)))), null)
            Files.write(prepared.files.values.single(), byteArrayOf(2))
            assertFails { f.archive.verify(prepared) }
            assertTrue(Files.exists(prepared.source))
        }
    }
}
