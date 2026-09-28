package com.example.chatbar.domain.chat

import java.net.URI
import kotlinx.serialization.json.*

/** Sanitizes retained copies only; the provider receives the unmodified serialized body. */
class ModelDiagnosticScrubber(private val apiKey: String) {
    private val json = Json { isLenient = true }

    fun url(source: String): String {
        val sanitized = runCatching {
            val uri = URI(source)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrElse { "[INVALID URL]" }
        return text(sanitized, 512)
    }

    fun json(source: String, limit: Int): String {
        val redacted = runCatching {
            redact(json.parseToJsonElement(source)).toString()
        }.getOrElse { source }
        return text(redacted, limit)
    }

    fun text(source: String, limit: Int): String {
        var result = source
        if (apiKey.isNotBlank()) result = result.replace(apiKey, "[REDACTED]")
        result = BEARER.replace(result, "Bearer [REDACTED]")
        result = IMAGE_DATA.replace(result, "[IMAGE DATA REDACTED]")
        result = CREDENTIAL_FIELD.replace(result) { match ->
            match.groupValues[1] + "[REDACTED]" + match.groupValues[2]
        }
        return if (result.length <= limit) result else result.take(limit) + "… [truncated]"
    }

    private fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (CREDENTIAL_KEY.containsMatchIn(key)) JsonPrimitive("[REDACTED]") else redact(value)
        })
        is JsonArray -> JsonArray(element.map(::redact))
        is JsonPrimitive -> if (element.isString) {
            JsonPrimitive(text(element.content, 65_536))
        } else {
            element
        }
    }

    private companion object {
        val BEARER = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+")
        val IMAGE_DATA = Regex("(?i)data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+")
        val CREDENTIAL_KEY = Regex("(?i)(?:api[_-]?key|authorization|access[_-]?token|secret|password)")
        val CREDENTIAL_FIELD = Regex("(?i)([\\\"](?:api[_-]?key|authorization|access[_-]?token|secret|password)[\\\"]\\s*:\\s*[\\\"])[^\\\"]*([\\\"])")
    }
}
