package com.example.chatbar.data.repository

fun interface ModelStorageKeyPolicy {
    fun storageKey(logicalModelId: String): String
}

/** Preserves the upstream/Android model filename contract exactly. */
object IdentityModelStorageKeyPolicy : ModelStorageKeyPolicy {
    override fun storageKey(logicalModelId: String): String = logicalModelId
}

/**
 * Maps logical model IDs to a Windows-safe physical filename key.
 *
 * Every ID is encoded so safe and unsafe logical IDs share one collision-free namespace. The
 * serialized ModelConfig.id remains authoritative and is never replaced by this key.
 */
object WindowsSafeModelStorageKeyPolicy : ModelStorageKeyPolicy {
    private const val PREFIX = "ccb-model-v1-"
    private const val HEX_DIGITS = "0123456789abcdef"

    override fun storageKey(logicalModelId: String): String = buildString {
        append(PREFIX)
        logicalModelId.toByteArray(Charsets.UTF_8).forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    internal fun logicalModelId(storageKey: String): String? {
        if (!storageKey.startsWith(PREFIX)) return null
        val encoded = storageKey.removePrefix(PREFIX)
        if (encoded.length % 2 != 0) return null
        return runCatching {
            ByteArray(encoded.length / 2) { index ->
                encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }.toString(Charsets.UTF_8)
        }.getOrNull()
    }
}
