package com.example.chatbar.domain.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A confirmed avatar is an owned derivative; external source images are never changed. */
object PlayerAvatarStorage {
    suspend fun crop(context: Context, uri: Uri, rect: ImageCropFractionRect): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input); BitmapFactory.decodeStream(input, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "头像图片无法读取" }
        val decode = BitmapFactory.Options().apply {
            while (bounds.outWidth / inSampleSize.coerceAtLeast(1) > 2048 || bounds.outHeight / inSampleSize.coerceAtLeast(1) > 2048)
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Match the existing crop preview's EXIF orientation, with a bounded decoded bitmap.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                var sample = 1
                while (info.size.width / sample > 2048 || info.size.height / sample > 2048) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
        } else context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input); BitmapFactory.decodeStream(input, null, decode)
        } ?: error("头像图片无法读取")
        try {
            val left = (rect.left * source.width).roundToInt().coerceIn(0, source.width - 1)
            val top = (rect.top * source.height).roundToInt().coerceIn(0, source.height - 1)
            val right = (rect.right * source.width).roundToInt().coerceIn(left + 1, source.width)
            val bottom = (rect.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
            val cropped = Bitmap.createBitmap(source, left, top, right-left, bottom-top)
            try {
                val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
                try {
                    val folder = File(context.filesDir, "images/player-avatars").apply { check(isDirectory || mkdirs()) }
                    val output = File(folder, "${UUID.randomUUID()}.png")
                    try {
                        output.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        output.absolutePath
                    } catch (error: Throwable) { output.delete(); throw error }
                } finally { if (scaled !== cropped) scaled.recycle() }
            } finally { if (cropped !== source) cropped.recycle() }
        } finally { source.recycle() }
    }
}
