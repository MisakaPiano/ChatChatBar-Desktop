package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Exact-candidate cleanup only, after commit. Unknown/corrupt authority means retain, never sweep. */
internal class DesktopOwnedImageCleanup(
    private val root: Path,
    private val resources: DesktopCharacterResourceStore,
    private val coordinator: DesktopDataOperationCoordinator,
    private val deleteAudio: (suspend (String) -> Boolean)? = null,
) {
    suspend fun deleteUnreferenced(candidates: List<String>) {
        fun identity(raw: String): String = if (isDesktopOwnedAudioCandidate(root, raw)) {
            val path = Path.of(raw).normalize().toString()
            if (System.getProperty("os.name").startsWith("Windows", true)) path.lowercase(java.util.Locale.ROOT) else path
        } else raw
        val owned = candidates.filter { (it.startsWith("images/") || it.startsWith("documents/") ||
            deleteAudio != null && isDesktopOwnedAudioCandidate(root, it)) && !it.contains("..") }.distinctBy(::identity)
        if (owned.isEmpty()) return
        val identities = owned.associateBy(::identity)
        coordinator.withExclusiveMaintenance {
            withContext(Dispatchers.IO) {
                val retained = mutableSetOf<String>()
                fun inspect(value: JsonElement) {
                    when (value) {
                        is JsonPrimitive -> if (value.isString) identities[identity(value.content)]?.let { retained += it }
                        is JsonArray -> value.forEach(::inspect)
                        is JsonObject -> value.values.forEach(::inspect)
                    }
                }
                val entities = root.resolve("entities")
                require(Files.isDirectory(entities, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entities)) {
                    "图片清理未执行：无法核实持久化引用"
                }
                // No recursion and no tolerant loadAll: an unreadable authority prevents deletion.
                Files.newDirectoryStream(entities).use { directories ->
                    for (directory in directories) {
                        require(!Files.isSymbolicLink(directory)) { "图片清理未执行：不安全的实体目录" }
                        if (Files.isRegularFile(directory, LinkOption.NOFOLLOW_LINKS) && directory.fileName.toString().endsWith(".json")) {
                            inspect(Json.parseToJsonElement(Files.readString(directory)))
                            continue
                        }
                        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                        Files.newDirectoryStream(directory, "*.json").use { files ->
                            for (file in files) {
                                require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
                                val value = Json.parseToJsonElement(Files.readString(file))
                                if (directory.fileName.toString() == DesktopCharacterBackgrounds.KEY) decodeDesktopBackgroundLibrary(value.jsonObject)
                                inspect(value)
                            }
                        }
                    }
                }
                owned.filterNot(retained::contains).forEach { path ->
                    if (isDesktopOwnedAudioCandidate(root, path)) check(deleteAudio?.invoke(path) == true) { "录音清理未完成，原文件与清理记录已保留" }
                    else resources.deleteOwned(path)
                }
            }
        }
    }
}

internal fun isDesktopOwnedAudioCandidate(root: Path, raw: String): Boolean = runCatching {
    val path = Path.of(raw)
    path.isAbsolute && path == path.normalize() &&
        path.parent?.parent == root.toAbsolutePath().normalize().resolve("audio/generated") &&
        path.fileName.toString().matches(Regex("[A-Za-z0-9_-]+\\.mp3", RegexOption.IGNORE_CASE))
}.getOrDefault(false)
