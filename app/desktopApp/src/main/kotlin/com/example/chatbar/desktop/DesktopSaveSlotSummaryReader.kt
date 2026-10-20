package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/** Official lightweight catalog projection; inline bodies and Base64 values are skipped. */
internal object DesktopSaveSlotSummaryReader {
    fun read(storageId: String, input: InputStream): SaveSlotSummary {
        var id = storageId
        var sessionId = ""
        var name = ""
        var description: String? = null
        var schemaVersion = 1
        var createdAt = 0L
        var inlineMessageCount = 0
        var inlineImageCount = 0
        var inlineAudioCount = 0
        var packageMessageCount: Int? = null
        var packageImageCount: Int? = null
        var packageAudioCount: Int? = null
        var imagePolicy = SaveSlotImagePolicy.ORIGINAL
        var includeAudio = true
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.strictness = com.google.gson.Strictness.STRICT
            reader.beginObject()
            val fields = hashSetOf<String>()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(fields.add(field)) { "存档摘要字段重复" }
                when (field) {
                    "id" -> id = reader.nextString()
                    "sessionId" -> sessionId = reader.nextString()
                    "name" -> name = reader.nextString()
                    "description" -> description = reader.nextNullableString()
                    "schemaVersion" -> schemaVersion = reader.nextInt()
                    "createdAt" -> createdAt = reader.nextLong()
                    "imagePolicy" -> imagePolicy = SaveSlotImagePolicy.valueOf(reader.nextString())
                    "includeAudio" -> includeAudio = reader.nextBoolean()
                    "messages" -> inlineMessageCount = reader.countArrayValues()
                    "imageResources" -> inlineImageCount = reader.countObjectValues()
                    "audioResources" -> inlineAudioCount = reader.countObjectValues()
                    "packageRef" -> {
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                        } else {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "messageCount" -> packageMessageCount = reader.nextInt()
                                    "imageCount" -> packageImageCount = reader.nextInt()
                                    "audioCount" -> packageAudioCount = reader.nextInt()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            check(reader.peek() == JsonToken.END_DOCUMENT)
        }
        require(sessionId.isNotBlank() && name.isNotBlank() && id == storageId && schemaVersion in 1..8) { "存档摘要身份无效" }
        require(listOfNotNull(inlineMessageCount, inlineImageCount, inlineAudioCount,
            packageMessageCount, packageImageCount, packageAudioCount).all { it >= 0 })
        return SaveSlotSummary(
            id = id,
            sessionId = sessionId,
            name = name,
            description = description,
            messageCount = packageMessageCount ?: inlineMessageCount,
            createdAt = createdAt,
            schemaVersion = schemaVersion,
            imagePolicy = imagePolicy,
            imageCount = packageImageCount ?: inlineImageCount,
            includeAudio = includeAudio,
            audioCount = packageAudioCount ?: inlineAudioCount
        )
    }
}

private fun JsonReader.nextNullableString(): String? = if (peek() == JsonToken.NULL) {
    nextNull()
    null
} else {
    nextString()
}

private fun JsonReader.countArrayValues(): Int {
    var count = 0
    beginArray()
    while (hasNext()) {
        skipValue()
        count++
    }
    endArray()
    return count
}

private fun JsonReader.countObjectValues(): Int {
    var count = 0
    beginObject()
    while (hasNext()) {
        nextName()
        skipValue()
        count++
    }
    endObject()
    return count
}
