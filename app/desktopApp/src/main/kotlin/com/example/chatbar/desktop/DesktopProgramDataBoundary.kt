package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Read-only policy shared by data-directory preparation and update handoff. */
internal object DesktopProgramDataBoundary {
    private fun canonicalCandidate(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing = absolute
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = requireNotNull(existing.parent)
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
    }

    fun protectedRoots(applicationHome: Path): List<Path> {
        val home = canonicalCandidate(applicationHome)
        val roots = mutableListOf(home)
        val versions = home.parent
        if (versions?.fileName?.toString()?.equals("versions", ignoreCase = true) == true) {
            val managed = requireNotNull(versions.parent)
            val owner = managed.resolve("install-owner.txt")
            val exists = try { Files.readAttributes(owner, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS); true }
                catch (_: NoSuchFileException) { false }
            if (exists) {
                DesktopArchiveFiles.safe(owner, false)
                check(Files.size(owner) in 1..128 && Files.readString(owner) == "ccb-desktop-nsis-v1")
                roots.add(managed)
            }
        }
        return roots
    }

    fun conflicts(applicationHome: Path, dataRoot: Path): Boolean {
        val candidate = canonicalCandidate(dataRoot).toString().lowercase(Locale.ROOT)
        return protectedRoots(applicationHome).any { root ->
            val program = root.toString().lowercase(Locale.ROOT)
            val data = Path.of(candidate); val owned = Path.of(program)
            data == owned || data.startsWith(owned) || owned.startsWith(data)
        }
    }
}
