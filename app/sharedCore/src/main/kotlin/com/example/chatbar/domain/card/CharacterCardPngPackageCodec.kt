package com.example.chatbar.domain.card

import java.util.Base64
import kotlinx.serialization.json.Json

/** Adds the authoritative ChatBar Character Package payload to an already rendered PNG. */
object CharacterCardPngPackageCodec {
    fun attach(
        renderedPng: ByteArray,
        packageData: CharacterCardPackage,
        json: Json,
    ): ByteArray {
        val packageJson = json.encodeToString(CharacterCardPackage.serializer(), packageData)
        val payload = Base64.getEncoder().encodeToString(packageJson.toByteArray(Charsets.UTF_8))
        return PngTextChunks.insertTextChunk(
            renderedPng,
            PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
            payload,
        )
    }
}
