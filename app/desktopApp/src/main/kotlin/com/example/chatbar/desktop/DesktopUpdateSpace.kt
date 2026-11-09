package com.example.chatbar.desktop

import java.nio.file.Path
import kotlinx.serialization.json.*

internal data class DesktopUpdateSpace(val cache: Path, val required: Long, val available: Long)

internal fun desktopUpdateSpace(value: JsonObject, expectedCache: Path): DesktopUpdateSpace? = runCatching {
    fun count(key: String) = value.getValue(key).jsonPrimitive.let {
        require(!it.isString); requireNotNull(it.longOrNull).also { number -> require(number >= 0) }
    }
    val text = value.getValue("cache").jsonPrimitive.also { require(it.isString) }.content
    require(text.length in 1..8192 && text.none { it.code < 32 })
    val cache = Path.of(text); require(cache.isAbsolute)
    require(cache.normalize() == expectedCache.toAbsolutePath().normalize())
    val required = count("requiredBytes"); val available = count("availableBytes")
    require(required in 1..4L * 1024 * 1024 * 1024 && available < required)
    DesktopUpdateSpace(cache.normalize(), required, available)
}.getOrNull()

internal fun desktopUpdateSpaceMessage(space: DesktopUpdateSpace): String {
    fun mib(bytes: Long) = "${(bytes + 1024 * 1024 - 1) / (1024 * 1024)} MiB（$bytes 字节）"
    return "更新缓存所在磁盘 ${space.cache.root} 空间不足。\n缓存位置：${space.cache}\n" +
        "需要 ${mib(space.required)}，可用 ${mib(space.available)}，还差 ${mib(space.required - space.available)}。\n" +
        "请清理已验证的旧更新安装包或释放该磁盘空间后重试；当前程序和资料未改变。"
}
