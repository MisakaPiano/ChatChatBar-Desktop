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
        fun identity(raw: String): String = if (raw.startsWith(com.example.chatbar.domain.image.NovelAiInlinePayloadStore.PREFIX))
            "novelai-vibe-payloads/" + raw.removePrefix(com.example.chatbar.domain.image.NovelAiInlinePayloadStore.PREFIX)
        else if (isDesktopOwnedAudioCandidate(root, raw)) {
            val path = Path.of(raw).normalize().toString()
            if (System.getProperty("os.name").startsWith("Windows", true)) path.lowercase(java.util.Locale.ROOT) else path
        } else raw
        val owned = candidates.filter { (it.startsWith("images/") || it.startsWith("documents/") ||
            isDesktopPresetPayloadCandidate(it) || deleteAudio != null && isDesktopOwnedAudioCandidate(root, it)) && !it.contains("..") }.distinctBy(::identity)
        if (owned.isEmpty()) return
        val identities = owned.associateBy(::identity)
        coordinator.withExclusiveMaintenance {
            withContext(Dispatchers.IO) {
                val payloadCleanup = owned.any(::isDesktopPresetPayloadCandidate)
                if (payloadCleanup && Files.exists(root.resolve("backups"), LinkOption.NOFOLLOW_LINKS)) {
                    DesktopArchiveFiles.safe(root.resolve("backups"), true)
                    Files.newDirectoryStream(root.resolve("backups")).use { backups ->
                        require(backups.all { it.fileName.toString() in setOf(".studio-preset-import", ".studio-preset-custody") ||
                            DesktopBackupOperationJournal.isResourceFreeStatusFile(root, it) }) {
                            "编码清理待核对：其他备份引用尚未确认，文件与归属记录保留"
                        }
                    }
                }
                val retained = mutableSetOf<String>()
                fun read(file: Path): JsonElement {
                    if (payloadCleanup) require(Files.size(file) <= 16L * 1024 * 1024) {
                        "编码清理待核对：尚不能有界核实该持久化引用，文件与归属记录保留"
                    }
                    return Json.parseToJsonElement(Files.readString(file))
                }
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
                            inspect(read(directory))
                            continue
                        }
                        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                        Files.newDirectoryStream(directory, "*.json").use { files ->
                            for (file in files) {
                                require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
                                val value = read(file)
                                if (directory.fileName.toString() == DesktopCharacterBackgrounds.KEY) decodeDesktopBackgroundLibrary(value.jsonObject)
                                inspect(value)
                            }
                        }
                    }
                }
                owned.filterNot(retained::contains).forEach { path ->
                    if (isDesktopOwnedAudioCandidate(root, path)) check(deleteAudio?.invoke(path) == true) { "录音清理未完成，原文件与清理记录已保留" }
                    else if (isDesktopPresetPayloadCandidate(path)) {
                        val file = root.resolve(path)
                        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                            DesktopArchiveFiles.safe(file, false)
                            require(DesktopArchiveFiles.hash(file, Long.MAX_VALUE) == file.fileName.toString())
                            Files.delete(file)
                        }
                    } else resources.deleteOwned(path)
                }
            }
        }
    }
}

internal fun isDesktopPresetPayloadCandidate(reference: String) =
    reference.matches(Regex("novelai-vibe-payloads/[a-f0-9]{64}"))

internal fun isDesktopOwnedAudioCandidate(root: Path, raw: String): Boolean = runCatching {
    val path = Path.of(raw)
    path.isAbsolute && path == path.normalize() &&
        path.parent?.parent == root.toAbsolutePath().normalize().resolve("audio/generated") &&
        path.fileName.toString().matches(Regex("[A-Za-z0-9_-]+\\.mp3", RegexOption.IGNORE_CASE))
}.getOrDefault(false)
