package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

object NovelAiFocusedInpaintProcessor {
    fun prepare(
        baseImage: NovelAiStudioAssetRef,
        originalMask: NovelAiStudioAssetRef?,
        region: NovelAiFocusedInpaintRegion,
        minimumContextPixels: Int
    ): NovelAiFocusedInpaintRequest {
        val base = BitmapFactory.decodeFile(File(baseImage.path).absolutePath)
            ?: error("Focused Inpainting 基图无法解码")
        val mask = originalMask?.takeIf(NovelAiStudioAssetRef::isUsable)?.let { asset ->
            BitmapFactory.decodeFile(File(asset.path).absolutePath)
                ?: error("Focused Inpainting 蒙版无法解码")
        }
        try {
            if (mask != null) {
                require(base.width == mask.width && base.height == mask.height) {
                    "Focused Inpainting 蒙版与基图尺寸不一致"
                }
            }
            val plan = NovelAiFocusedInpaintPlanner.plan(
                sourceWidth = base.width,
                sourceHeight = base.height,
                region = region,
                minimumContextPixels = minimumContextPixels
            )
            val croppedBase = Bitmap.createBitmap(
                base,
                plan.crop.left,
                plan.crop.top,
                plan.crop.width,
                plan.crop.height
            )
            val scaledBase = NovelAiLanczos3Resampler.resize(
                croppedBase,
                plan.requestSize.width,
                plan.requestSize.height
            )
            return try {
                val preparedMask = NovelAiInpaintMaskEncoder.prepareFocusedMask(mask, plan)
                NovelAiFocusedInpaintRequest(
                    plan = plan,
                    imageBase64 = encodePngBase64(scaledBase),
                    maskBase64 = preparedMask.requestMaskBase64,
                    blendMaskAlpha = preparedMask.blendMaskAlpha
                )
            } finally {
                if (scaledBase !== croppedBase) scaledBase.recycle()
                if (croppedBase !== base) croppedBase.recycle()
            }
        } finally {
            base.recycle()
            mask?.recycle()
        }
    }

    private fun encodePngBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "Focused Inpainting 基图编码失败"
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
