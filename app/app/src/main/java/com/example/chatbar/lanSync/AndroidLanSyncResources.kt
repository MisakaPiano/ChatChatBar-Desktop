package com.example.chatbar.lanSync

import com.example.chatbar.domain.image.ImageMetadataStripper
import com.example.chatbar.domain.lanSync.LanSyncPostProcess
import com.example.chatbar.domain.lanSync.LanSyncRecord
import com.example.chatbar.domain.lanSync.LanSyncRasterPolicy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

fun androidLanSyncResourcePath(dataRoot: Path, contextAlias: Path, reference: String): Path {
    val path = Paths.get(reference)
    return when {
        path.isAbsolute && path.startsWith(contextAlias) -> dataRoot.resolve(contextAlias.relativize(path))
        path.isAbsolute -> path
        else -> dataRoot.resolve(reference)
    }
}

fun validateAndroidLanSyncResource(kind: String, path: Path, record: LanSyncRecord) {
    if (kind != "images") return
    val result = LanSyncPostProcess.record(record)
    LanSyncRasterPolicy.checkPng(path, result != null)
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
    val pixels = LanSyncRasterPolicy.maximumPixels(record)
    require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= pixels &&
        (result == null || options.outMimeType == "image/png")) { "SYNC_IMAGE_INVALID" }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight).toLong() > 256L * sample) sample *= 2
    val decoded = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        requireNotNull(android.graphics.BitmapFactory.decodeStream(input, null, decoded)) { "SYNC_IMAGE_INVALID" }.recycle()
    }
}

/** A bounded static view for byte-based review callers; original file and metadata remain intact. */
fun androidLanSyncPreviewRaster(path: Path, record: LanSyncRecord): ByteArray {
    validateAndroidLanSyncResource("images", path, record)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > 2048L * sample) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
    val bitmap = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
        requireNotNull(android.graphics.BitmapFactory.decodeStream(it, null, options)) { "SYNC_IMAGE_INVALID" }
    }
    var oriented = bitmap
    try {
        val orientation = if (bounds.outMimeType == "image/gif") 1 else ImageMetadataStripper.readOrientation(path.toFile())
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (orientation != 1) oriented = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return java.io.ByteArrayOutputStream().use { output ->
            check(oriented.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "SYNC_IMAGE_INVALID" }
            output.toByteArray()
        }
    } finally { if (oriented !== bitmap) oriented.recycle(); bitmap.recycle() }
}
