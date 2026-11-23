package com.example.chatbar.ui.components

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.chatbar.domain.image.ApngDisguiseCodec
import com.example.chatbar.domain.lanSync.LanSyncHeifStill
import java.io.File

fun shareImage(context: Context, path: String, chooserTitle: String = "分享图片") {
    try {
        val sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        context.startActivity(Intent.createChooser(createImageShareIntent(context, sourceFile), chooserTitle))
    } catch (error: Exception) {
        Toast.makeText(context, "分享失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun createImageShareIntent(context: Context, sourceFile: File): Intent {
    val identity = imageExportIdentity(sourceFile, sourceFile.name)
    val authority = "${context.packageName}.fileprovider"
    val uri = if (identity.name == sourceFile.name) FileProvider.getUriForFile(context, authority, sourceFile)
    else FileProvider.getUriForFile(context, authority, sourceFile, identity.name)
    return Intent(Intent.ACTION_SEND).apply {
        type = identity.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "ChatBar image", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun shareFromImageLongPress(context: Context, path: String) {
    val sourceFile = File(path)
    if (!sourceFile.exists()) {
        Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    if (!shouldShareAsFile(sourceFile)) {
        shareImage(context, path)
        return
    }
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sourceFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, sourceFile.name)
            clipData = ClipData.newUri(context.contentResolver, "ChatBar file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享文件"))
    } catch (error: Exception) {
        Toast.makeText(context, "文件分享失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}

fun saveImageToGallery(
    context: Context,
    path: String,
    displayName: String? = null
) {
    try {
        val sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        writeGalleryImage(context, sourceFile, displayName)
        Toast.makeText(context, "已保存到 Pictures/ChatBar", Toast.LENGTH_SHORT).show()
    } catch (error: Exception) {
        Toast.makeText(context, "保存失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun writeGalleryImage(context: Context, sourceFile: File, displayName: String? = null): Uri? {
    val identity = imageExportIdentity(sourceFile, displayName?.takeIf { it.isNotBlank() } ?: sourceFile.name)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ChatBar")
        dir.mkdirs()
        val target = File(dir, identity.name)
        sourceFile.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(identity.mimeType), null)
        return null
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, identity.name)
        put(MediaStore.Images.Media.MIME_TYPE, identity.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChatBar")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建相册文件" }
    try {
        requireNotNull(resolver.openOutputStream(uri)) { "无法打开相册文件" }.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        check(resolver.update(uri, values, null, null) == 1) { "无法完成相册保存" }
        return uri
    } catch (failure: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw failure
    }
}

private data class ImageExportIdentity(val name: String, val mimeType: String)

private fun imageExportIdentity(source: File, name: String): ImageExportIdentity {
    val mediaType = LanSyncHeifStill.inspectForNaming(source.toPath())?.mediaType
        ?: return ImageExportIdentity(name, imageMimeType(source))
    val correctName = "${name.substringBeforeLast('.', name)}.${mediaType.extension}"
    return ImageExportIdentity(correctName, mediaType.mimeType)
}

private fun imageMimeType(file: File): String = when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "apng" -> "image/png"
    else -> "image/png"
}

internal fun shouldShareAsFile(file: File): Boolean {
    val extension = file.extension.lowercase()
    if (extension == "apng" || extension == "gif") return true
    return ApngDisguiseCodec.containsAnimationControl(file) || file.hasGifSignature()
}

private fun File.hasGifSignature(): Boolean = runCatching {
    inputStream().buffered().use { input ->
        val signature = ByteArray(6)
        input.read(signature) == signature.size &&
            (signature.contentEquals("GIF87a".toByteArray()) || signature.contentEquals("GIF89a".toByteArray()))
    }
}.getOrDefault(false)
