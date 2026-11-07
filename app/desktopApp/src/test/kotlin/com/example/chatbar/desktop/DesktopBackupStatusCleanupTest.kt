package com.example.chatbar.desktop

import com.example.chatbar.domain.image.NovelAiInlinePayloadStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopBackupStatusCleanupTest {
    private class Fixture {
        val root = Files.createTempDirectory("status-payload-cleanup-").also { Files.createDirectory(it.resolve("entities")) }
        val gate = DesktopDataOperationCoordinator()
        val payloads = NovelAiInlinePayloadStore(root.toFile())
        val cleanup = DesktopOwnedImageCleanup(root, DesktopCharacterResourceStore(root), gate)
        val journal = DesktopBackupOperationJournal(root, DesktopBackupOperationJournal.Lane.BACKUP)
        fun payload(value: String): String {
            val reference = payloads.retainStream(value.byteInputStream())
            check(reference.startsWith(NovelAiInlinePayloadStore.PREFIX))
            return "novelai-vibe-payloads/" + reference.substringAfter(':')
        }
        suspend fun close() { gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun `actual fixed status records do not block unreferenced payload cleanup or grant deletion of referenced payload`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.journal.begin(DesktopBackupOperationJournal.Kind.EXPORT)
            DesktopBackupOperationJournal(f.root, DesktopBackupOperationJournal.Lane.MERGE).begin(DesktopBackupOperationJournal.Kind.MERGE_CHECK)
            val markers = Files.newDirectoryStream(f.root.resolve("backups")).use { paths -> paths.associateWith(Files::readAllBytes) }
            val unused = f.payload("AQID".repeat(2048)); val used = f.payload("BAUG".repeat(2048))
            Files.writeString(f.root.resolve("entities/reference.json"), """{"vibe":"chatbar-vibe-file:${used.substringAfterLast('/')}"}""")
            f.cleanup.deleteUnreferenced(listOf(unused, used))
            assertFalse(Files.exists(f.root.resolve(unused)))
            assertTrue(Files.exists(f.root.resolve(used)))
            for ((path, bytes) in markers) assertContentEquals(bytes, Files.readAllBytes(path))
        } finally { f.close() }
    }

    @Test fun `unknown backups and malformed or unsafe fixed markers retain payloads and original evidence`() = runBlocking<Unit> {
        val malformed = listOf<(String) -> String>(
            { it.dropLast(1) + ",\"path\":\"images/private.png\"}" },
            { it.replace("\"version\":1", "\"version\":1,\"version\":1") },
            { it.replace("\"version\":1", "\"version\":2") },
            { it.replace(Regex("\"id\":\"[^\"]+\""), "\"id\":\"not-a-uuid\"") },
            { it.replace("\"EXPORT\"", "\"UNKNOWN_KIND\"") },
            { it.replace("\"RUNNING\"", "\"UNKNOWN_PHASE\"") },
            { " ".repeat(2049) }, { "" })
        for (case in 0 until malformed.size + 3) {
            val f = Fixture()
            try {
                f.journal.begin(DesktopBackupOperationJournal.Kind.EXPORT)
                val marker = f.root.resolve("backups/backup-operation-backup.json")
                var evidence = marker
                when {
                    case < malformed.size -> Files.writeString(marker, malformed[case](Files.readString(marker)))
                    case == malformed.size -> evidence = Files.writeString(f.root.resolve("backups/unknown.json"), "{}")
                    case == malformed.size + 1 -> evidence = Files.createDirectory(f.root.resolve("backups/actual-snapshot"))
                    else -> { Files.delete(marker); Files.createDirectory(marker) }
                }
                val before = if (Files.isRegularFile(evidence)) Files.readAllBytes(evidence) else null
                val payload = f.payload("AQID".repeat(2048)); val bytes = Files.readAllBytes(f.root.resolve(payload))
                assertFails { f.cleanup.deleteUnreferenced(listOf(payload)) }
                assertContentEquals(bytes, Files.readAllBytes(f.root.resolve(payload)))
                if (before != null) assertContentEquals(before, Files.readAllBytes(evidence))
                else assertTrue(Files.isDirectory(evidence))
            } finally { f.close() }
        }
    }
}
