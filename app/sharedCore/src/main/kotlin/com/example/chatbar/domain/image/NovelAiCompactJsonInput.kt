package com.example.chatbar.domain.image

import java.io.InputStream
import java.io.Reader
import java.io.Writer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class NovelAiCompactJsonSyntaxException : IllegalArgumentException("JSON 字符串或转义无效")

/** Pull-based lexical projection: no complete JSON copy or worker thread is created. */
internal class NovelAiCompactJsonInput(
    source: InputStream,
    private val payloads: NovelAiInlinePayloadStore,
    private val checkActive: () -> Unit,
) : InputStream() {
    private val input = source.reader(Charsets.UTF_8).buffered()
    private var pending = ByteArray(0)
    private var offset = 0
    private var closed = false
    private var eof = false
    private var quoted = false
    private var escaped = false
    private var unicodeDigits = 0
    private var unicodeValue = 0
    private var invalidToken = false
    private var longToken = false
    private var payloadKey = false
    private val token = StringBuilder(32)

    private fun tokenChar(c: Char) {
        if (token.length < 32) token.append(c) else longToken = true
    }

    private fun fill(): Boolean {
        check(!closed)
        if (offset < pending.size) return true
        if (eof) return false
        checkActive()
        val output = StringBuilder(2049)
        while (output.length < 2048 || output.lastOrNull()?.isHighSurrogate() == true) {
            val next = input.read()
            if (next < 0) { eof = true; break }
            val c = next.toChar()
            if (quoted) {
                output.append(c)
                when {
                    unicodeDigits > 0 -> {
                        val digit = c.digitToIntOrNull(16)
                        if (digit == null) invalidToken = true else unicodeValue = unicodeValue * 16 + digit
                        if (--unicodeDigits == 0) tokenChar(unicodeValue.toChar())
                    }
                    escaped -> {
                        escaped = false
                        when (c) {
                            'u' -> { unicodeDigits = 4; unicodeValue = 0 }
                            '"', '\\', '/' -> tokenChar(c)
                            'b' -> tokenChar('\b')
                            'f' -> tokenChar('\u000c')
                            'n' -> tokenChar('\n')
                            'r' -> tokenChar('\r')
                            't' -> tokenChar('\t')
                            else -> invalidToken = true
                        }
                    }
                    c == '\\' -> escaped = true
                    c == '"' -> { quoted = false; payloadKey = !invalidToken && !longToken && token.toString() == "encodedVibe" }
                    else -> tokenChar(c)
                }
            } else if (c == '"') {
                if (payloadKey) {
                    val value = payloads.retainDecodedJsonString(checkActive) { sink -> copyDecodedString(input, sink, checkActive) }
                    output.append(Json.encodeToString(value))
                    payloadKey = false
                } else {
                    output.append(c); quoted = true; escaped = false; unicodeDigits = 0
                    invalidToken = false; longToken = false; token.setLength(0)
                }
            } else {
                output.append(c)
                if (!c.isWhitespace() && c != ':') payloadKey = false
            }
        }
        pending = output.toString().toByteArray(Charsets.UTF_8)
        offset = 0
        return pending.isNotEmpty()
    }

    override fun read(): Int = if (fill()) pending[offset++].toInt() and 255 else -1

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        require(off >= 0 && len >= 0 && off <= buffer.size - len)
        if (len == 0) return 0
        if (!fill()) return -1
        val count = minOf(len, pending.size - offset)
        pending.copyInto(buffer, off, offset, offset + count); offset += count
        return count
    }

    override fun close() {
        if (closed) return
        closed = true; pending = ByteArray(0)
        input.close()
    }
}

private fun copyDecodedString(input: Reader, output: Writer, checkActive: () -> Unit) {
    var count = 0
    fun next(): Int = input.read().also { if (it < 0) throw NovelAiCompactJsonSyntaxException() }
    while (true) {
        if (count++ % 8192 == 0) checkActive()
        val c = next()
        if (c == '"'.code) return
        if (c == '\\'.code) {
            when (val escaped = next().toChar()) {
                '"', '\\', '/' -> output.write(escaped.code)
                'b' -> output.write(8)
                'f' -> output.write(12)
                'n' -> output.write(10)
                'r' -> output.write(13)
                't' -> output.write(9)
                'u' -> {
                    val digits = CharArray(4) { next().toChar() }
                    output.write(String(digits).toIntOrNull(16) ?: throw NovelAiCompactJsonSyntaxException())
                }
                else -> throw NovelAiCompactJsonSyntaxException()
            }
        } else {
            if (c < 32) throw NovelAiCompactJsonSyntaxException()
            output.write(c)
        }
    }
}
