package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal enum class DesktopShortcutDestination(val argument: String) { DESKTOP("desktop"), START_MENU("programs") }

/** Invokes only the current, externally receipted installer helper, never an arbitrary executable. */
internal fun desktopInstalledShortcutManager(home: Path): Pair<Path, Path> {
    val current = home.toAbsolutePath().normalize()
    require(current.parent.fileName.toString() == "versions" && current.toRealPath() == current)
    val root = current.parent.parent
    fun regular(path: Path, limit: Long): Path {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) in 1..limit)
        return path
    }
    fun hash(path: Path) = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(16384)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    require(Files.readString(regular(root.resolve("install-owner.txt"), 128)) == "ccb-desktop-nsis-v1")
    require(Files.readString(regular(root.resolve("current.txt"), 256)).trim() == current.fileName.toString())
    val manifest = regular(current.resolve("install-manifest.json"), 2097152)
    require(Files.readString(regular(root.resolve("receipts/${current.fileName}.sha256"), 128)).trim() == hash(manifest))
    val document = Json.parseToJsonElement(Files.readString(manifest)).jsonObject
    require(document["product"]?.jsonPrimitive?.content == "ccb-desktop-nsis-v1")
    val entry = document["files"]!!.jsonArray.single { it.jsonObject["path"]?.jsonPrimitive?.content == "install-manager.exe" }.jsonObject
    val manager = regular(current.resolve("install-manager.exe"), 16777216)
    require(Files.size(manager) == entry["bytes"]?.jsonPrimitive?.long && hash(manager) == entry["sha256"]?.jsonPrimitive?.content)
    return manager to root
}

internal suspend fun desktopCreateShortcut(destination: DesktopShortcutDestination): String = withContext(Dispatchers.IO) {
    val home = (DesktopApplicationHome.resolve() as? DesktopApplicationHomeResult.Available)?.path ?: error("NOT_INSTALLED")
    val (manager, root) = desktopInstalledShortcutManager(home)
    val process = ProcessBuilder(manager.toString(), "--shortcuts", root.toString(), destination.argument, "--quiet")
        .directory(root.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!process.waitFor(30, TimeUnit.SECONDS)) "尚未确认快捷方式创建结果，请稍后核对；没有关闭应用。"
    else when (process.exitValue()) {
        0 -> if (destination == DesktopShortcutDestination.DESKTOP) "桌面快捷方式已创建，可从桌面打开当前版本。" else "已添加到开始菜单，可搜索 ChatChatBar Desktop。"
        3 -> "发现被修改或由其他程序创建的快捷方式，已保留；本次未确认完成。"
        else -> "快捷方式未创建，请使用官方安装包修复程序和快捷方式。"
    }
}
