package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
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
        val extension = desktopImageResourceExtension(bytes)
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

    /** Gallery owns the bytes. Changing the session selection never collects previous backgrounds. */
    suspend fun selectBackgroundReference(sessionId: String, reference: String?) = gate.withNormalOperation {
        withContext(NonCancellable + Dispatchers.IO) {
            reference?.let(resources::readBytes)
            val session = requireNotNull(chats.getSession(sessionId)) { "会话不存在" }
            chats.saveSessionSettingsDraft(session, session.copy(chatBackground = reference))
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

    /** Explicit per-session player image; absence restores the global default. */
    suspend fun replacePlayerAvatar(sessionId: String, bytes: ByteArray?): String? {
        val previous = gate.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            val session = requireNotNull(chats.getSession(sessionId)) { "会话不存在" }
            val reference = bytes?.let(::import)
            // A failed/uncertain save keeps the candidate, as it may already be durably referenced.
            chats.saveSessionSettingsDraft(session, session.copy(desktopPlayerAvatar = reference))
            session.desktopPlayerAvatar
        } }
        return cleanupRemoved(listOfNotNull(previous))
    }

    /** Editing keeps only references from the opening message; additions are fresh owned copies. */
    suspend fun editMessage(original: ChatMessage, content: String, retained: List<String>, additions: List<DesktopPendingImage>,
        preferAddedImages: Boolean = false): String? =
        editMutex.withLock {
            require(retained.distinct().size == retained.size && retained.all { it in original.images })
            require(content.isNotBlank() || retained.isNotEmpty() || additions.isNotEmpty())
            require(!preferAddedImages || original.role == MessageRole.USER && retained == original.images && additions.isNotEmpty())
            gate.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
                check(chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original)) { "消息已变化，请重新打开编辑" }
                val added = additions.map { import(it.bytes) }
                // An indeterminate update (including partial metadata/index writes) retains all files.
                val paths = if (preferAddedImages) added + retained else retained + added
                updateMessage(MessageAlternativeVersionPolicy.editCurrentContent(original, content).copy(
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

    fun jpegBase64(reference: String): String = encodeRequestJpeg(resources.readChatRequestImage(reference, REQUEST_IMAGE_SIDE))

    fun jpegBase64(encoded: ByteArray): String =
        encodeRequestJpeg(DesktopImageEditing.decode(encoded, longestSide = REQUEST_IMAGE_SIDE))

    private fun encodeRequestJpeg(original: BufferedImage): String = try {
        val scale = minOf(1.0, REQUEST_IMAGE_SIDE.toDouble() / maxOf(original.width, original.height))
        val rgb = BufferedImage((original.width * scale).toInt().coerceAtLeast(1),
            (original.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        try {
            val graphics = rgb.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, rgb.width, rgb.height)
                graphics.drawImage(original, 0, 0, rgb.width, rgb.height, null)
            } finally {
                graphics.dispose()
            }
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(rgb, "jpeg", output))
                Base64.getEncoder().encodeToString(output.toByteArray())
            }
        } finally {
            rgb.flush()
        }
    } finally {
        original.flush()
    }

    private companion object {
        const val REQUEST_IMAGE_SIDE = 1600
    }
}
