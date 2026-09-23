package com.example.chatbar.data.root

import java.nio.file.Path

/**
 * appDataRoot direct children 中由数据安全基础设施保留的最小 contract。
 * `backups/` 保留整个 subtree；ownership lock 只在 root-level exact entry 时保留。
 * Windows root-entry 名称按保守的 case-insensitive 语义比较，nested 同名文件仍是普通 payload。
 */
object AppDataRootInfrastructure {
    const val BACKUPS_DIRECTORY_NAME = "backups"
    const val OWNERSHIP_LOCK_FILE_NAME = ".ccb-desktop.lock"

    fun isReservedPath(relativePath: Path): Boolean =
        isBackupsSubtree(relativePath) || isRootOwnershipLock(relativePath)

    fun isBackupsSubtree(relativePath: Path): Boolean =
        !relativePath.isAbsolute &&
            relativePath.nameCount > 0 &&
            relativePath.getName(0).toString().equals(BACKUPS_DIRECTORY_NAME, ignoreCase = true)

    fun isRootOwnershipLock(relativePath: Path): Boolean =
        !relativePath.isAbsolute &&
            relativePath.nameCount == 1 &&
            relativePath.fileName.toString().equals(OWNERSHIP_LOCK_FILE_NAME, ignoreCase = true)
}
