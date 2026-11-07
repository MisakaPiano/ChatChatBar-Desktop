package com.example.chatbar.desktop

import com.example.chatbar.domain.image.NovelAiPostProcessPolicy
import com.example.chatbar.domain.image.NovelAiPostProcessTab
import java.awt.image.BufferedImage

/** Output-only budget; ordinary imports and API input keep their original limits. */
internal object DesktopPostProcessRaster {
    const val MAX_BYTES = 100 * 1024 * 1024
    fun decode(bytes: ByteArray, tab: NovelAiPostProcessTab, longestSide: Int? = null): BufferedImage {
        require(bytes.size in 8..MAX_BYTES)
        require(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) { "服务器未返回 PNG" }
        DesktopImageEditing.requireStatic(bytes)
        return DesktopImageEditing.decode(bytes, longestSide = longestSide, maximumBytes = MAX_BYTES,
            maximumPixels = NovelAiPostProcessPolicy.MAX_PIXELS.toLong() * if (tab == NovelAiPostProcessTab.UPSCALE) 4 else 1)
    }
}
