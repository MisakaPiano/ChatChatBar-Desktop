package com.example.chatbar.domain.lanSync

import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A configurable in-memory decode working budget, independent of attachment/storage size. */
data class LanSyncMetadataBudget(
    val maximumDecodedCost: Long = Runtime.getRuntime().maxMemory() / 4,
    val maximumDepth: Int = 128,
    val maximumStringCharacters: Long = 8L * 1024 * 1024,
) {
    init { require(maximumDecodedCost > 0 && maximumDepth > 0 && maximumStringCharacters > 0) }

    internal fun validate(path: Path): Long {
        lanSyncSafe(path)
        var cost = 0L; var depth = 0; var quoted = false; var escaped = false
        var stringCharacters = 0L; var scalar = false
        fun charge(amount: Long) {
            cost = lanSyncAddLength(cost, amount)
            require(cost <= maximumDecodedCost) { "SYNC_METADATA_WORKING_BUDGET" }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            InputStreamReader(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), decoder).use { input ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    for (index in 0 until count) {
                        val char = buffer[index]
                        if (quoted) {
                            stringCharacters = lanSyncAddLength(stringCharacters, 1)
                            require(stringCharacters <= maximumStringCharacters) { "SYNC_METADATA_STRING_BUDGET" }
                            charge(8) // Retained UTF-16 text plus comparison/validation working copies.
                            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                        } else when (char) {
                            '"' -> { quoted = true; escaped = false; stringCharacters = 0; scalar = false; charge(128) }
                            '{', '[' -> { depth++; require(depth <= maximumDepth) { "SYNC_METADATA_DEPTH" }; scalar = false; charge(128) }
                            '}', ']' -> { depth--; require(depth >= 0) { "SYNC_METADATA_DEPTH" }; scalar = false }
                            ',', ':' -> { scalar = false; charge(64) }
                            ' ', '\n', '\r', '\t' -> scalar = false
                            else -> { if (!scalar) { charge(64); scalar = true }; charge(8) }
                        }
                    }
                }
            }
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("SYNC_INVALID_UTF8", failure)
        }
        // Full JSON grammar and bracket matching are independently checked by the typed decoder.
        require(!quoted && depth == 0) { "SYNC_METADATA_STRUCTURE" }
        return cost
    }
}
