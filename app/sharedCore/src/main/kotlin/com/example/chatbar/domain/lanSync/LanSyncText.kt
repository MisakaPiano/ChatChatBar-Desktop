package com.example.chatbar.domain.lanSync

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Refuse malformed source/protocol text rather than persisting replacement characters. */
internal fun lanSyncUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (failure: CharacterCodingException) {
    throw IllegalArgumentException("SYNC_INVALID_UTF8", failure)
}
