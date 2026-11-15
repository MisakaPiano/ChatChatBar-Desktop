package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Explicit preview-only offline data, never auto-installed and never sent to an AI provider. */
internal object LanSyncPreviewSample {
    private const val PREFIX = "ccb-lan-preview-sample-v2"
    private const val CARD = "$PREFIX-card"
    private const val SESSION = "$PREFIX-session"
    private const val EXAMPLE_TIME = 1793534400000L
    private fun reference(identity: String, hash: String) =
        "ccb-lan-v1/images/${UUID.nameUUIDFromBytes("$PREFIX-$identity".toByteArray(Charsets.UTF_8))}/$hash/png"

    fun records(imageHash: String, alternateImageHash: String = imageHash): List<LanSyncRecord> {
        val image = reference("chat-image", imageHash)
        val card = CharacterCard(CARD, "局域网离线样例 2", avatar = image, createdAt = 1, updatedAt = 1)
        val session = ChatSession(SESSION, CARD, "局域网离线样例 2", longTermMemoryEnabled = false,
            longTermMemory = "离线合成记忆：这段资料仅用于核对同步与保留情况。", createdAt = 1, updatedAt = 1)
        val message = ChatMessage("$PREFIX-message", SESSION, MessageRole.ASSISTANT, "这是离线合成会话。可以编辑这条消息，再到另一端核对。",
            images = listOf(image), alternatives = listOf("这是样例的第一个消息版本。", "这是离线合成会话。可以编辑这条消息，再到另一端核对。"),
            alternativeVersionIds = listOf("$PREFIX-message-version-0", "$PREFIX-message-version-1"), currentAlternativeIndex = 1,
            currentAlternativeVersionId = "$PREFIX-message-version-1", createdAt = 1, updatedAt = 1, orderKey = 1_000_000)
        val versions = listOf(imageHash, alternateImageHash).mapIndexed { index, hash ->
            val path = reference("moment-version-$index", hash)
            val prompt = if (index == 0) "离线蓝色格样例" else "离线暖色格样例"
            MomentImageVersion("$PREFIX-image-version-$index", path, prompt,
                GeneratedImageMetadata(path, prompt, negativePrompt = "离线合成资料，无生成调用", sizePreset = "offline-sample",
                    width = 32, height = 32), prompt, EXAMPLE_TIME + index)
        }
        val selected = versions.last()
        val post = MomentPost("$PREFIX-post", CARD, SESSION, senderName = "离线样例角色",
            text = "这是离线合成动态。两种颜色的图片版本用于核对保留情况；可以修改正文或点赞，再到另一端查看。",
            imagePath = selected.imagePath, imagePrompt = selected.imagePrompt,
            generatedImageMetadata = selected.generatedImageMetadata, imageBrief = selected.imageBrief,
            scheduledAt = EXAMPLE_TIME, generatedAt = EXAMPLE_TIME, desktopImageVersions = versions)
        // Year2100 is deliberately beyond an ordinary acceptance session, even after explicit approval.
        val pending = MomentTask("$PREFIX-pending", CARD, SESSION, 4102444800000L,
            requiresLocalScheduleApproval = true, createdAt = EXAMPLE_TIME, updatedAt = EXAMPLE_TIME)
        val running = pending.copy(id = "$PREFIX-running", status = MomentTaskStatus.RUNNING,
            scheduledAt = EXAMPLE_TIME, postId = post.id)
        return listOf(
            LanSyncRecord("character_cards", CARD, lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), card).jsonObject),
            LanSyncRecord("chat_sessions", SESSION, lanSyncJson.encodeToJsonElement(ChatSession.serializer(), session).jsonObject),
            LanSyncRecord("chat_messages", message.id, lanSyncJson.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject),
            LanSyncRecord("moment_posts", post.id, lanSyncJson.encodeToJsonElement(MomentPost.serializer(), post).jsonObject),
            LanSyncRecord("moment_tasks", pending.id, lanSyncJson.encodeToJsonElement(MomentTask.serializer(), pending).jsonObject),
            LanSyncRecord("moment_tasks", running.id, lanSyncJson.encodeToJsonElement(MomentTask.serializer(), running).jsonObject))
    }
    fun image(warm: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream(); val writer = DataOutputStream(output)
        writer.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val tag = type.toByteArray(Charsets.US_ASCII); val crc = CRC32().apply { update(tag); update(bytes) }
            writer.writeInt(bytes.size); writer.write(tag); writer.write(bytes); writer.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream(); DataOutputStream(header).apply { writeInt(32); writeInt(32); write(byteArrayOf(8, 2, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        val pixels = ByteArrayOutputStream()
        val dark = if (warm) intArrayOf(210, 110, 50) else intArrayOf(70, 130, 200)
        val light = if (warm) intArrayOf(250, 235, 215) else intArrayOf(225, 235, 245)
        DeflaterOutputStream(pixels).use { compressed -> for (y in 0 until 32) {
            compressed.write(0)
            for (x in 0 until 32) { val alternate = (x / 8 + y / 8) % 2 == 0
                (if (alternate) dark else light).forEach { compressed.write(it) } }
        } }
        chunk("IDAT", pixels.toByteArray()); chunk("IEND", byteArrayOf()); return output.toByteArray()
    }
}
