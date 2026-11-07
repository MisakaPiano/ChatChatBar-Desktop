package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Only an already absent top-level historical post avatar may remain absent in modern transport. */
internal object DesktopHistoricalMomentAvatarPolicy {
    fun allows(entity: String, fields: List<String>, code: Int): Boolean =
        code in 85..CURRENT_DESKTOP_BACKUP_CODE &&
            entity.removePrefix("entities/").substringBefore('/').removeSuffix(".json") == "moment_posts" &&
            fields == listOf("senderAvatar")

    fun requireMissing(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        check(Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) { "HISTORICAL_AVATAR_NOT_CONFIRMED_MISSING" }
        var ancestor = requireNotNull(absolute.parent)
        while (Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
        // Unknown access, directories masquerading as files, links and reparse ancestors are not absence.
        DesktopArchiveFiles.safe(ancestor, true)
    }

    fun missingExternal(value: String, root: Path): Pair<String, Path>? {
        if (value.isBlank() || value.startsWith(DesktopBackupPolicy.PREFIX) ||
            value.startsWith("http:") || value.startsWith("https:") || value.startsWith("chatbar-save-slot-")) return null
        val path = Path.of(value)
        if (!path.isAbsolute || path.normalize().startsWith(root.toAbsolutePath().normalize())) return null
        requireMissing(path)
        return "images/migrated_external/missing-avatar-${DesktopBackupPolicy.digest(value.toByteArray(Charsets.UTF_8))}" to path
    }

    fun warning(count: Int): String = if (count == 0) "" else
        "已有 $count 条朋友圈记录的历史头像文件缺失。记录和缺失状态已保留，恢复后这些头像仍缺失。"
}
