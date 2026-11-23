package com.example.chatbar.domain.image

import android.content.Context
import android.net.Uri
import com.example.chatbar.domain.lanSync.LanSyncHeifStill
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Copies the selected original, then gives a verified HEIF still its content-based extension. */
internal suspend fun copyAndroidChatImage(context: Context, uri: Uri): File {
    val resolver = context.contentResolver
    val fallbackExtension = when (resolver.getType(uri)) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    val images = File(context.filesDir, "images")
    Files.createDirectories(images.toPath())
    var copy = File.createTempFile("chat_img_", ".$fallbackExtension", images)
    var complete = false
    try {
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开所选图片" }.use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        }
        require(bytes > 0) { "所选图片为空，请重新选择" }
        val mediaType = LanSyncHeifStill.inspectForNaming(copy.toPath())?.mediaType
        currentCoroutineContext().ensureActive()
        if (mediaType != null && copy.extension != mediaType.extension) {
            val correctName = File(images, "${copy.nameWithoutExtension}.${mediaType.extension}")
            copy = Files.move(copy.toPath(), correctName.toPath()).toFile()
        }
        complete = true
        return copy
    } finally {
        if (!complete) copy.delete()
    }
}
