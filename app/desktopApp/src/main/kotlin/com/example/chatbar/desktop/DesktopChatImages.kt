package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DesktopPendingImage(val id: String = UUID.randomUUID().toString(), val bytes: ByteArray, val restoredDisguise: Boolean = false)

/** Immutable deep copies. Only this transaction's newly created paths may be rolled back. */
internal class DesktopChatImages(
    private val resources: DesktopCharacterResourceStore,
    private val chats: ChatRepository,
    private val gate: AppDataOperationGate = NoOpAppDataOperationGate,
    private val cleanup: suspend (List<String>) -> Unit = {},
    private val persistMessage: suspend (ChatMessage) -> ChatMessage = chats::addMessage,
    private val updateMessage: suspend (ChatMessage) -> Unit = chats::updateMessage,
) {
    private val editMutex = Mutex()
    fun read(reference: String): ByteArray = resources.readBytes(reference)

    suspend fun prepare(path: Path): DesktopPendingImage {
        require(Files.size(path) <= DesktopImageEditing.MAX_BYTES) { "图片大小超过 32 MB" }
        val bytes = Files.readAllBytes(path)
        return prepareBytes(bytes)
    }

    suspend fun prepareBytes(bytes: ByteArray): DesktopPendingImage {
        require(bytes.size <= DesktopImageEditing.MAX_BYTES) { "图片大小超过 32 MB" }
        val temporary = Files.createTempFile("ccb-attachment-inspect-", ".png")
        val canonical = try {
            Files.write(temporary, bytes)
            com.example.chatbar.domain.image.ApngDisguiseCodec.inspectDisguise(temporary.toFile()) != null
        } finally { Files.deleteIfExists(temporary) }
        val prepared = if (canonical) DesktopImageTools.restore(bytes) else bytes.copyOf()
        DesktopImageEditing.decode(prepared)
        return DesktopPendingImage(bytes = prepared, restoredDisguise = canonical)
    }

    private fun import(bytes: ByteArray): String {
        DesktopImageEditing.decode(bytes)
        val extension = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "jpg"
            bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
            else -> error("仅支持 PNG、APNG、JPEG、WebP、GIF 图片")
        }
        // Copy the encoded source intact, including embedded generation metadata. Raster edits
        // deliberately create a separate PNG; merely attaching a file must not rewrite it.
        return resources.materializeImage(PackagedImage("image.$extension", Base64.getEncoder().encodeToString(bytes)),
            System.currentTimeMillis(), "p7" + UUID.randomUUID())
    }

    suspend fun persistUser(sessionId: String, text: String, attachments: List<DesktopPendingImage>): ChatMessage =
        gate.withNormalOperation {
            withContext(NonCancellable + Dispatchers.IO) {
                val created = mutableListOf<String>()
                val messageId = UUID.randomUUID().toString()
                try {
                    attachments.forEach { created += import(it.bytes) }
                    persistMessage(ChatMessage.create(sessionId, MessageRole.USER, text, images = created.toList())
                        .copy(id = messageId))
                } catch (failure: Throwable) {
                    // addMessage can commit its entity before an index/session write fails. Retain the
                    // image whenever that durable outcome is present OR cannot be established.
                    val outcome = runCatching { chats.readMessageDurable(messageId, sessionId) }.getOrNull()
                    if (outcome == com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Missing) {
                        created.forEach { runCatching { resources.deleteOwned(it) }.onFailure(failure::addSuppressed) }
                    }
                    throw failure
                }
            }
        }

    suspend fun replaceBackground(sessionId: String, bytes: ByteArray?): String? {
        val previous = gate.withNormalOperation {
            withContext(NonCancellable + Dispatchers.IO) {
                val session = requireNotNull(chats.getSession(sessionId)) { "会话不存在" }
                val newReference = bytes?.let(::import)
                try {
                    chats.saveSessionSettingsDraft(session, session.copy(chatBackground = newReference))
                } catch (failure: Throwable) {
                    val durable = runCatching { chats.readSessionDurable(sessionId) }.getOrNull()
                    if (newReference != null && durable is com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Valid && durable.value.chatBackground != newReference)
                        runCatching { resources.deleteOwned(newReference) }.onFailure(failure::addSuppressed)
                    throw failure
                }
                session.chatBackground
            }
        }
        return cleanupRemoved(listOfNotNull(previous))
    }

    /** Editing keeps only references from the opening message; additions are fresh owned copies. */
    suspend fun editMessage(original: ChatMessage, content: String, retained: List<String>, additions: List<DesktopPendingImage>): String? =
        editMutex.withLock {
            require(retained.distinct().size == retained.size && retained.all { it in original.images })
            require(content.isNotBlank() || retained.isNotEmpty() || additions.isNotEmpty())
            gate.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
                check(chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original)) { "消息已变化，请重新打开编辑" }
                val added = additions.map { import(it.bytes) }
                // An indeterminate update (including partial metadata/index writes) retains all files.
                val paths = retained + added
                updateMessage(MessageAlternativeVersionPolicy.collapseToEditedContent(original, content).copy(
                    images = paths, generatedImageMetadata = original.generatedImageMetadata.filter { it.imagePath in paths },
                    formatRepairNotice = null))
            } }
            cleanupRemoved(original.images.filterNot(retained::contains))
        }

    suspend fun deleteImage(original: ChatMessage, reference: String): String? = editMutex.withLock {
        require(reference in original.images)
        gate.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            check(chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original)) { "消息已变化，请重新打开图片操作" }
            val remaining = original.images.filterNot { it == reference }
            if (remaining.isEmpty() && original.content.isBlank()) chats.deleteMessage(original.id, original.sessionId)
            else updateMessage(original.copy(images = remaining,
                generatedImageMetadata = original.generatedImageMetadata.filter { it.imagePath in remaining }))
        } }
        cleanupRemoved(listOf(reference))
    }

    suspend fun cleanupRemoved(references: List<String>): String? {
        // Imported card/legacy/external resources have a different owner.
        val owned = references.filter { Regex("images/card_[0-9]+_p7(?:[0-9a-f-]+|chat[0-9a-f-]+_[0-9]+)\\.(png|jpg|webp|gif)").matches(it) }
        return try { cleanup(owned); null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { "已保存；旧图片清理未完成：${failure.message}" }
    }

    fun jpegBase64(reference: String): String = jpegBase64(read(reference))

    fun jpegBase64(encoded: ByteArray): String {
        val original = DesktopImageEditing.decode(encoded, longestSide = 1600)
        val size = minOf(1.0, 1600.0 / maxOf(original.width, original.height))
        val rgb = java.awt.image.BufferedImage((original.width * size).toInt().coerceAtLeast(1),
            (original.height * size).toInt().coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().let { g ->
            try { g.color = java.awt.Color.WHITE; g.fillRect(0, 0, rgb.width, rgb.height)
                g.drawImage(original, 0, 0, rgb.width, rgb.height, null)
            } finally { g.dispose() }
        }
        val bytes = java.io.ByteArrayOutputStream().use {
            check(javax.imageio.ImageIO.write(rgb, "jpeg", it)); it.toByteArray()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
