package com.example.chatbar.desktop.backup

/** Stable categories only: never expose an entity payload, credential or parser message. */
internal enum class DesktopBackupCompatibility {
    MOMENTS_UNSUPPORTED, ENTITY_UNSUPPORTED, ENTITY_SCHEMA_UNSUPPORTED, CREDENTIAL_APPROVAL_REQUIRED;

    fun userMessage(): String = when (this) {
        MOMENTS_UNSUPPORTED -> "备份包含尚未支持的朋友圈数据，已拒绝整包恢复；原档案保留。"
        ENTITY_UNSUPPORTED -> "备份包含尚未支持的数据类型，已拒绝整包恢复；原档案保留。"
        ENTITY_SCHEMA_UNSUPPORTED -> "备份的数据字段或版本尚不兼容，已拒绝整包恢复；原档案保留。"
        CREDENTIAL_APPROVAL_REQUIRED -> "备份包含外来凭据，未获得凭据迁移授权；已拒绝整包恢复，未写入凭据库。"
    }
}

internal class DesktopBackupCompatibilityException(val kind: DesktopBackupCompatibility) :
    IllegalArgumentException(kind.name)
