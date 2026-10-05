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
) {
    suspend fun deleteUnreferenced(candidates: List<String>) {
        val owned = candidates.distinct().filter { (it.startsWith("images/") || it.startsWith("documents/")) && !it.contains("..") }
        if (owned.isEmpty()) return
        coordinator.withExclusiveMaintenance {
            withContext(Dispatchers.IO) {
                val retained = mutableSetOf<String>()
                fun inspect(value: JsonElement) {
                    when (value) {
                        is JsonPrimitive -> if (value.isString && value.content in owned) retained += value.content
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
                                inspect(Json.parseToJsonElement(Files.readString(file)))
                            }
                        }
                    }
                }
                owned.filterNot(retained::contains).forEach(resources::deleteOwned)
            }
        }
    }
}
