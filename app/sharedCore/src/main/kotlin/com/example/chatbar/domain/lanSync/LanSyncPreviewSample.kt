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
    private const val CARD = "ccb-lan-preview-sample-card"
    private const val SESSION = "ccb-lan-preview-sample-session"
    fun records(imageHash: String): List<LanSyncRecord> {
        val image = "ccb-lan-v1/images/$imageHash/png"
        val card = CharacterCard(CARD, "局域网离线样例", avatar = image, createdAt = 1, updatedAt = 1)
        val session = ChatSession(SESSION, CARD, "局域网离线样例", longTermMemoryEnabled = false,
            longTermMemory = "离线合成记忆：这段资料仅用于核对同步与保留情况。", createdAt = 1, updatedAt = 1)
        val message = ChatMessage("ccb-lan-preview-sample-message", SESSION, MessageRole.ASSISTANT, "这是离线合成会话。可以编辑这条消息，再到另一端核对。",
            images = listOf(image), alternatives = listOf("这是样例的第一个消息版本。", "这是离线合成会话。可以编辑这条消息，再到另一端核对。"),
            alternativeVersionIds = listOf("sample-version-0", "sample-version-1"), currentAlternativeIndex = 1,
            currentAlternativeVersionId = "sample-version-1", createdAt = 1, updatedAt = 1, orderKey = 1_000_000)
        return listOf(
            LanSyncRecord("character_cards", CARD, lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), card).jsonObject),
            LanSyncRecord("chat_sessions", SESSION, lanSyncJson.encodeToJsonElement(ChatSession.serializer(), session).jsonObject),
            LanSyncRecord("chat_messages", message.id, lanSyncJson.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject))
    }
    fun image(): ByteArray {
        val output = ByteArrayOutputStream(); val writer = DataOutputStream(output)
        writer.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val tag = type.toByteArray(Charsets.US_ASCII); val crc = CRC32().apply { update(tag); update(bytes) }
            writer.writeInt(bytes.size); writer.write(tag); writer.write(bytes); writer.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream(); DataOutputStream(header).apply { writeInt(32); writeInt(32); write(byteArrayOf(8, 2, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        val pixels = ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use { compressed -> for (y in 0 until 32) {
            compressed.write(0)
            for (x in 0 until 32) { val alternate = (x / 8 + y / 8) % 2 == 0
                compressed.write(if (alternate) 70 else 225); compressed.write(if (alternate) 130 else 235); compressed.write(if (alternate) 200 else 245) }
        } }
        chunk("IDAT", pixels.toByteArray()); chunk("IEND", byteArrayOf()); return output.toByteArray()
    }
}
