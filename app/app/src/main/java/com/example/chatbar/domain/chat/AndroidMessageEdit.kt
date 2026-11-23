package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.lanSync.LanSyncOperationGate
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal sealed interface AndroidMessageEditResult {
    data class Saved(val message: ChatMessage, val refreshPending: Boolean = false) : AndroidMessageEditResult
    data object Stale : AndroidMessageEditResult
    data object Failed : AndroidMessageEditResult
    data object Empty : AndroidMessageEditResult
    data object Deleted : AndroidMessageEditResult
    data object DeletedWithRefreshPending : AndroidMessageEditResult
}

internal suspend fun saveAndroidMessageEdit(
    repository: ChatRepository,
    expected: ChatMessage,
    replacement: ChatMessage
): AndroidMessageEditResult {
    var committed: ChatMessage? = null
    return try {
        if (repository.updateMessageIfUnchanged(expected, replacement) { committed = it }) {
            AndroidMessageEditResult.Saved(checkNotNull(committed))
        } else AndroidMessageEditResult.Stale
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        // A preview/index write can fail after the message itself was atomically installed.
        committed?.let { AndroidMessageEditResult.Saved(it, refreshPending = true) } ?: AndroidMessageEditResult.Failed
    }
}

/** Retire only exact replaced files after their message commits. An uncertain inventory retains every candidate. */
internal suspend fun retireUnreferencedAndroidChatImages(root: Path, gate: LanSyncOperationGate, removedPaths: List<String>) {
    if (removedPaths.isEmpty()) return
    gate.exclusive {
        withContext(Dispatchers.IO) {
            val canonicalRoot = root.toRealPath()
            val images = canonicalRoot.resolve("images")
            val files = removedPaths.mapNotNull { raw ->
                val path = Paths.get(raw)
                if (!path.isAbsolute || path != path.normalize()) return@mapNotNull null
                val relative = when {
                    path.startsWith(canonicalRoot) -> canonicalRoot.relativize(path)
                    path.startsWith(root.toAbsolutePath().normalize()) -> root.toAbsolutePath().normalize().relativize(path)
                    else -> return@mapNotNull null
                }
                val file = canonicalRoot.resolve(relative)
                // This inventory authorizes only images copied by the ordinary chat picker.
                val owned = file.parent == images && file.fileName.toString().startsWith("chat_img_")
                if (!owned) return@mapNotNull null
                try {
                    val real = file.toRealPath()
                    check(file == real && Files.isRegularFile(file, NOFOLLOW_LINKS))
                    file
                } catch (_: NoSuchFileException) {
                    null
                }
            }.toSet()
            if (files.isEmpty()) return@withContext

            val retainedNames = mutableSetOf<String>()
            val candidateNames = files.mapTo(mutableSetOf()) { it.fileName.toString() }
            val entities = canonicalRoot.resolve("entities")
            check(Files.isDirectory(entities, NOFOLLOW_LINKS))
            var bytesLeft = 64L * 1024 * 1024
            var filesLeft = 20_000
            suspend fun inspect(entity: Path) {
                currentCoroutineContext().ensureActive()
                check(Files.isRegularFile(entity, NOFOLLOW_LINKS))
                val size = Files.size(entity)
                check(size in 1..minOf(bytesLeft, 8L * 1024 * 1024) && filesLeft-- > 0)
                val bytes = Files.readAllBytes(entity)
                check(bytes.size.toLong() == size)
                bytesLeft -= size
                val value = Json.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
                collectRetainedImageNames(value, candidateNames, retainedNames)
            }
            Files.newDirectoryStream(entities).use { directories ->
                for (entry in directories) {
                    check(!Files.isSymbolicLink(entry))
                    if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                        Files.newDirectoryStream(entry, "*.json").use { rows -> for (row in rows) inspect(row) }
                    } else if (entry.fileName.toString().endsWith(".json")) inspect(entry)
                }
            }
            files.filterNot { it.fileName.toString() in retainedNames }.forEach(Files::deleteIfExists)
        }
    }
}

private fun collectRetainedImageNames(value: JsonElement, candidates: Set<String>, retained: MutableSet<String>) {
    // Compare basenames conservatively so relative references, canonical aliases and legacy JSON are all retained.
    when (value) {
        is JsonPrimitive -> if (value.isString) value.content.substringAfterLast('/').takeIf(candidates::contains)?.let(retained::add)
        is JsonArray -> value.forEach { collectRetainedImageNames(it, candidates, retained) }
        is JsonObject -> value.values.forEach { collectRetainedImageNames(it, candidates, retained) }
    }
}
