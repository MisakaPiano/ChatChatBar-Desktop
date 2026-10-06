package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

object NovelAiInpaintResultComposer {
    fun compose(
        generatedPng: ByteArray,
        baseImage: NovelAiStudioAssetRef,
        focusedPlan: NovelAiFocusedInpaintPlan,
        blendMaskAlpha: ByteArray
    ): ByteArray {
        val generated = BitmapFactory.decodeByteArray(generatedPng, 0, generatedPng.size)
            ?: error("Inpaint 结果无法解码")
        val base = BitmapFactory.decodeFile(File(baseImage.path).absolutePath)
            ?: error("Inpaint 基图无法解码")
        require(
            generated.width == focusedPlan.requestSize.width &&
                generated.height == focusedPlan.requestSize.height
        ) {
            "Focused Inpainting 结果与请求尺寸不一致"
        }
        require(blendMaskAlpha.size == focusedPlan.requestSize.width * focusedPlan.requestSize.height) {
            "Focused Inpainting 羽化蒙版尺寸不一致"
        }
        require(
            focusedPlan.sourceWidth == base.width && focusedPlan.sourceHeight == base.height &&
                focusedPlan.crop.right <= base.width && focusedPlan.crop.bottom <= base.height
        ) { "Focused Inpainting 聚焦区域与基图不匹配" }
        val scaledGenerated = NovelAiLanczos3Resampler.resize(
            generated,
            focusedPlan.crop.width,
            focusedPlan.crop.height
        )
        val requestBlendMask = Bitmap.createBitmap(
            focusedPlan.requestSize.width,
            focusedPlan.requestSize.height,
            Bitmap.Config.ARGB_8888
        )
        val requestMaskPixels = IntArray(blendMaskAlpha.size) { index ->
            ((blendMaskAlpha[index].toInt() and 0xff) shl 24) or 0x00ffffff
        }
        requestBlendMask.setPixels(
            requestMaskPixels,
            0,
            focusedPlan.requestSize.width,
            0,
            0,
            focusedPlan.requestSize.width,
            focusedPlan.requestSize.height
        )
        val scaledBlendMask = NovelAiLanczos3Resampler.resize(
            requestBlendMask,
            focusedPlan.crop.width,
            focusedPlan.crop.height
        )
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        return try {
            val size = base.width * base.height
            val basePixels = IntArray(size)
            base.getPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)
            val generatedPixels = IntArray(focusedPlan.crop.width * focusedPlan.crop.height)
            scaledGenerated.getPixels(
                generatedPixels,
                0,
                focusedPlan.crop.width,
                0,
                0,
                focusedPlan.crop.width,
                focusedPlan.crop.height
            )
            val blendPixels = IntArray(generatedPixels.size)
            scaledBlendMask.getPixels(
                blendPixels,
                0,
                focusedPlan.crop.width,
                0,
                0,
                focusedPlan.crop.width,
                focusedPlan.crop.height
            )
            val composed = basePixels.copyOf()
            for (y in 0 until focusedPlan.crop.height) {
                for (x in 0 until focusedPlan.crop.width) {
                    val outputIndex = (focusedPlan.crop.top + y) * base.width + focusedPlan.crop.left + x
                    val generatedIndex = y * focusedPlan.crop.width + x
                    composed[outputIndex] = composeOfficialPixel(
                        basePixels[outputIndex],
                        generatedPixels[generatedIndex],
                        blendPixels[generatedIndex] ushr 24 and 0xff
                    )
                }
            }
            output.setPixels(composed, 0, base.width, 0, 0, base.width, base.height)
            val encoded = ByteArrayOutputStream().use { stream ->
                check(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Inpaint 结果合成失败"
                }
                stream.toByteArray()
            }
            preserveTextChunks(encoded, generatedPng)
        } finally {
            output.recycle()
            generated.recycle()
            if (scaledGenerated !== generated) scaledGenerated.recycle()
            requestBlendMask.recycle()
            if (scaledBlendMask !== requestBlendMask) scaledBlendMask.recycle()
            base.recycle()
        }
    }

    internal fun composeOfficialPixel(base: Int, generated: Int, maskWeight: Int): Int =
        NovelAiInpaintPixelPolicy.composeOfficialPixel(base, generated, maskWeight)
    private fun preserveTextChunks(encoded: ByteArray, source: ByteArray): ByteArray =
        NovelAiInpaintPixelPolicy.preserveTextChunks(encoded, source)
}
