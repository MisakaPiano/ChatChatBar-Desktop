package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import java.nio.file.*

/** Rebuildable Studio catalog/index/vibe caches. Unknown files still block a partial backup. */
internal object DesktopBackupAuxiliary {
    suspend fun validate(root: Path) {
        DesktopArchiveFiles.safe(root, true)
        var count = 0
        Files.walk(root).use { paths -> for (path in paths.iterator().asSequence()) {
            check(++count <= 40000) { "AUXILIARY_ENTRY_BUDGET" }
            val relative = root.relativize(path).joinToString("/")
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                DesktopArchiveFiles.safe(path, true)
                check(relative in setOf("", "novelai", "novelai/vibe-cache")) { "UNCLASSIFIED_AUXILIARY_ENTRY" }
            } else {
                DesktopArchiveFiles.safe(path, false)
                check(relative.matches(Regex("novelai/(?:[a-f0-9]{40}|[a-f0-9]{64}|index-[a-f0-9]{40})\\.sqlite")) ||
                    relative == "novelai/active-catalog.json" ||
                    relative.matches(Regex("novelai/vibe-cache/[a-f0-9]{64}\\.vibe")) ||
                    relative.matches(Regex("novelai/(?:[a-f0-9]{40}|[a-f0-9]{64})\\.[a-f0-9-]{36}\\.tmp")) ||
                    relative.matches(Regex("novelai/vibe-cache/\\.[a-f0-9]{64}\\.vibe\\.[a-f0-9-]{36}\\.tmp"))) {
                    "UNCLASSIFIED_AUXILIARY_ENTRY"
                }
                if (relative.endsWith(".sqlite")) {
                    Files.newInputStream(path).use { input ->
                        check(input.readNBytes(16).contentEquals("SQLite format 3\u0000".toByteArray())) { "AUXILIARY_SQLITE_HEADER" }
                    }
                    val name = path.fileName.toString().removeSuffix(".sqlite")
                    if (name.matches(Regex("[a-f0-9]{64}"))) check(DesktopArchiveFiles.hash(path, 256L shl 20) == name) { "AUXILIARY_CONTENT_CHANGED" }
                    if (name.matches(Regex("[a-f0-9]{40}"))) {
                        val digest = java.security.MessageDigest.getInstance("SHA-1")
                        digest.update("blob ${Files.size(path)}\u0000".toByteArray())
                        Files.newInputStream(path).use { input -> val buffer = ByteArray(65536)
                            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                        }
                        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == name) { "AUXILIARY_CONTENT_CHANGED" }
                    }
                }
            }
        } }
    }
}
