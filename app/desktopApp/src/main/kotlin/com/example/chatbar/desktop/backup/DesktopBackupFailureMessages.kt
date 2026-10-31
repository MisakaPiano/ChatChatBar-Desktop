package com.example.chatbar.desktop.backup

private fun recordMemoryMessage(actual: Long, limit: Long): String =
    "单条资料至少需要读取 " + actual + " 字节，超过本次单条处理预算 " + limit + " 字节。请保留原件并查看错误详情，以便处理该大型记录；原数据未改变。"

internal fun DesktopBackupRecords.ResourceLimit.userMessage(): String = recordMemoryMessage(actual, limit)
internal fun DesktopFullBackupSource.Refusal.userMessage(): String = when {
    passwordRequired -> "该备份已加密，请输入存档密码（8–1024 个字符）后重新检查。当前数据未改变。"
    resourceActual != null && resourceLimit != null -> recordMemoryMessage(requireNotNull(resourceActual), requireNotNull(resourceLimit))
    encrypted == true && stage == "AUTHENTICATION_ZIP" -> "加密备份读取未通过，请核对存档密码和文件完整性；当前数据未改变。"
    stage.contains("ENTITY_SCHEMA") -> "备份包含当前版本无法识别的数据结构（" + stage + "），请展开错误详情；当前数据未改变。"
    else -> "备份读取未通过（" + stage + "），请核对文件结构与完整性并查看错误详情；当前数据未改变。"
}
internal fun DesktopFullBackupWriter.Refusal.userMessage(): String =
    if (resourceActual != null && resourceLimit != null) recordMemoryMessage(requireNotNull(resourceActual), requireNotNull(resourceLimit))
    else "备份导出未完成（" + stage + "）。请核对目标文件、可用空间和备份密码。"
