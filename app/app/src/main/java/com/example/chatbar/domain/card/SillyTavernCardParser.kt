package com.example.chatbar.domain.card

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Android Uri/ContentResolver ingress for the shared SillyTavern parser authority. */
object AndroidSillyTavernCardParser {
    fun parseUri(context: Context, uri: Uri): SillyTavernCard {
        val rawJson: String?
        val pngBytes: ByteArray?
        if (isPngContent(context, uri)) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            pngBytes = bytes
            rawJson = bytes?.let(SillyTavernCardParser::extractCharaChunk)
        } else {
            pngBytes = null
            rawJson = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
        }

        if (rawJson == null) {
            // Preserve Android's last-resort PNG probe when MIME/name probing did not identify it.
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val chunk = bytes?.let(SillyTavernCardParser::extractCharaChunk)
                ?: throw IllegalArgumentException("未提取出角色卡数据")
            return SillyTavernCardParser.parseJson(chunk).copy(pngBytes = bytes)
        }

        return SillyTavernCardParser.parseJson(rawJson, pngBytes)
    }

    private fun isPngContent(context: Context, uri: Uri): Boolean {
        if (context.contentResolver.getType(uri) == "image/png") return true
        val displayName = getDisplayName(context, uri)
        if (displayName?.lowercase()?.endsWith(".png") == true) return true
        if (uri.path?.lowercase()?.endsWith(".png") == true) return true
        return hasPngMagicHeader(context, uri)
    }

    private fun getDisplayName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return name
    }

    private fun hasPngMagicHeader(context: Context, uri: Uri): Boolean {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(8)
                if (stream.read(header) == 8) {
                    return header[0] == 0x89.toByte() &&
                        header[1] == 'P'.code.toByte() &&
                        header[2] == 'N'.code.toByte() &&
                        header[3] == 'G'.code.toByte()
                }
            }
        } catch (_: Exception) {
        }
        return false
    }
}
