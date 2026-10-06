package com.example.chatbar.domain.image

import android.graphics.Bitmap

internal object NovelAiLanczos3Resampler {
    fun resize(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return Bitmap.createBitmap(NovelAiPixelResampler.resize(pixels, source.width, source.height,
            targetWidth, targetHeight), targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }
}
