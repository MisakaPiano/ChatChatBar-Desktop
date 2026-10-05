package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class DesktopPendingImage(val id: String = UUID.randomUUID().toString(), val bytes: ByteArray)

/** Immutable deep copies. Only this transaction's newly created paths may be rolled back. */
internal class DesktopChatImages(
    private val resources: DesktopCharacterResourceStore,
    private val chats: ChatRepository,
    private val gate: AppDataOperationGate = NoOpAppDataOperationGate,
    private val cleanup: suspend (List<String>) -> Unit = {},
    private val persistMessage: suspend (ChatMessage) -> ChatMessage = chats::addMessage,
) {
    fun read(reference: String): ByteArray = resources.readBytes(reference)

    fun prepare(path: Path): DesktopPendingImage {
        require(Files.size(path) <= DesktopImageEditing.MAX_BYTES) { "图片大小超过 32 MB" }
        val bytes = Files.readAllBytes(path)
        DesktopImageEditing.requireStatic(bytes)
        DesktopImageEditing.decode(bytes)
        return DesktopPendingImage(bytes = bytes)
    }

    private fun import(bytes: ByteArray): String {
        DesktopImageEditing.requireStatic(bytes)
        DesktopImageEditing.decode(bytes)
        val extension = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "jpg"
            bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> error("仅支持 PNG、JPEG、WebP 静态图片")
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

    suspend fun cleanupRemoved(references: List<String>): String? {
        // Imported card/legacy/external resources have a different owner.
        val owned = references.filter { Regex("images/card_[0-9]+_p7[0-9a-f-]+\\.(png|jpg|webp)").matches(it) }
        return try { cleanup(owned); null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { "已保存；旧图片清理未完成：${failure.message}" }
    }

    fun jpegBase64(reference: String): String {
        val original = DesktopImageEditing.decode(read(reference), longestSide = 1600)
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
