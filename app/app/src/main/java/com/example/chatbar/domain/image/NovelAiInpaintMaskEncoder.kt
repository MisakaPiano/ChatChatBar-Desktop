package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

data class NovelAiPreparedFocusedMask(
    val requestMaskBase64: String,
    val blendMaskAlpha: ByteArray
)

object NovelAiInpaintMaskEncoder {
    fun encodeBinaryPngBase64(asset: NovelAiStudioAssetRef): String {
        val source = File(asset.path)
        require(source.isFile) { "Inpaint 蒙版文件不存在" }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("Inpaint 蒙版无法解码")
        return try {
            val selected = BooleanArray(bitmap.width * bitmap.height)
            val pixels = IntArray(selected.size)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.indices.forEach { index -> selected[index] = isSelected(pixels[index], LEGACY_THRESHOLD) }
            encodeBinaryMask(selected, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    internal fun prepareFocusedMask(
        fullResolutionMask: Bitmap?,
        plan: NovelAiFocusedInpaintPlan
    ): NovelAiPreparedFocusedMask {
        if (fullResolutionMask != null) {
            require(
                fullResolutionMask.width == plan.sourceWidth &&
                    fullResolutionMask.height == plan.sourceHeight
            ) { "Focused Inpainting 蒙版与规划尺寸不一致" }
        }
        val pixels = fullResolutionMask?.let { bitmap ->
            IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
        val mask = NovelAiFocusedMaskPolicy.prepare(pixels, plan)
        return NovelAiPreparedFocusedMask(encodeBinaryMask(mask.requestSelection,
            plan.requestSize.width, plan.requestSize.height), mask.blendMaskAlpha)
    }

    private fun encodeBinaryMask(selection: BooleanArray, width: Int, height: Int): String {
        require(selection.size == width * height) { "Inpaint 蒙版数据尺寸无效" }
        val pixels = IntArray(selection.size) { index -> if (selection[index]) Color.WHITE else Color.BLACK }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Inpaint 蒙版 PNG 编码失败" }
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun isSelected(color: Int, threshold: Int): Boolean {
        val intensity = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return Color.alpha(color) > threshold && intensity > threshold
    }

    private const val LATENT_SCALE = 8
    private const val API_THRESHOLD = 155
    private const val LEGACY_THRESHOLD = 128
    private const val DILATION_RADIUS = 4
    private const val BLUR_RADIUS = 20
    private const val BLUR_ITERATIONS = 2
}
