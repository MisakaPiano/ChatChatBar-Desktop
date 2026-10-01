package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.WorldBook
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

/** Deterministic digest of the entity representation that repository JSON actually persists. */
object EditorPostCommitFingerprint {
    fun character(json: Json, card: CharacterCard): String =
        fingerprint(json, json.encodeToJsonElement(CharacterCard.serializer(), card))

    /** WorldBookRepository alone stamps updatedAt, so it is excluded from semantic comparison. */
    fun worldBook(json: Json, book: WorldBook): String =
        fingerprint(json, json.encodeToJsonElement(WorldBook.serializer(), book.copy(updatedAt = 0L)))

    private fun fingerprint(json: Json, element: JsonElement): String =
        sha256(json.encodeToString(JsonElement.serializer(), canonical(element)))

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
