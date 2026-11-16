package com.example.chatbar.desktop

import java.nio.file.Path

internal fun desktopUpdateCacheCleanupMessage(
    cache: Path,
    removed: Long,
    bytes: Long,
    retained: Long,
    missing: Long,
    unavailable: Long,
    installationConfirmed: Boolean,
    available: Long?,
    completedAt: String
): String = buildString {
    append("更新缓存扫描结果（$completedAt）\n位置：$cache\n")
    if (!installationConfirmed) append("尚无本版本的安装完成确认；未把下载或待安装包当作已用包。\n")
    append("本次清理 $removed 份已完成安装的旧包，共 $bytes 字节。\n")
    if (retained == 0L) append("本次扫描未发现需要保留的安装包。\n")
    else append("保留 $retained 份待安装或尚未确认完成的安装包。\n")
    append("保留 $missing 条已无安装包的校验记录")
    if (unavailable > 0) append("；另有 $unavailable 项正在使用或无法确认，原件与记录保留")
    append("。\n这是本次扫描时的结果；再次清理可刷新统计。\n")
    append(available?.let { "缓存所在磁盘当前可用 $it 字节。" }
        ?: "无法读取缓存所在磁盘的余量，请核对后重试下载。")
}
