package com.example.chatbar.desktop

import java.nio.file.Path

/** Narrow classpath reader for legacy `asset:` Character resources. */
internal class DesktopBundledAssetReader(
    private val classLoader: ClassLoader = DesktopBundledAssetReader::class.java.classLoader,
    private val resourceRoot: String = "chatbar-assets",
) : (String) -> ByteArray {
    override fun invoke(logicalPath: String): ByteArray {
        val normalized = validate(logicalPath)
        val resourceName = "$resourceRoot/$normalized"
        return classLoader.getResourceAsStream(resourceName)?.use { it.readBytes() }
            ?: error("Desktop bundled Character asset 不存在：$logicalPath")
    }

    internal fun validate(logicalPath: String): String {
        require(logicalPath.isNotBlank()) { "Character asset 路径不能为空" }
        require(!logicalPath.startsWith('/') && !logicalPath.startsWith('\\')) {
            "Character asset 路径不能是绝对路径"
        }
        val path = runCatching { Path.of(logicalPath.replace('\\', '/')) }
            .getOrElse { throw IllegalArgumentException("无效 Character asset 路径：$logicalPath", it) }
        require(!path.isAbsolute) { "Character asset 路径不能是绝对路径" }
        require(path.none { it.toString() == "." || it.toString() == ".." }) {
            "Character asset 路径不允许 traversal"
        }
        val value = path.normalize().joinToString("/")
        require(value.isNotBlank()) { "Character asset 路径不能为空" }
        return value
    }
}
