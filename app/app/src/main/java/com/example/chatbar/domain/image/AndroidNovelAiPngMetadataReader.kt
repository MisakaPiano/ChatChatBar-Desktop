package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Bitmap adapter for the shared ordinary/alpha metadata precedence and parser. */
object AndroidNovelAiPngMetadataReader {
    fun read(path: String) = NovelAiPngMetadataReader.read(path, ::alpha)
    fun readStudio(path: String) = NovelAiPngMetadataReader.readStudio(path, ::alpha)
    fun readEnhance(path: String) = NovelAiPngMetadataReader.readEnhance(path, ::alpha)

    private fun alpha(path: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outMimeType !in setOf("image/png", "image/webp")) return null
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= PrivacyPngEncoder.MAX_PIXELS)
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }) ?: return null
        return try {
            val column = IntArray(bitmap.height)
            var columnX = -1
            StealthAlphaMetadata.decode(bitmap.width, bitmap.height) { x, y ->
                if (columnX != x) {
                    bitmap.getPixels(column, 0, 1, x, 0, 1, bitmap.height)
                    columnX = x
                }
                column[y] ushr 24
            }
        } finally { bitmap.recycle() }
    }
}
