package com.example.chatbar.domain.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.chatbar.domain.voice.FishAudioStorage
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private const val MAX_COMPRESSED_IMAGE_EDGE = 1600
fun SaveSlotPackageStorage(context: Context): SaveSlotPackageStorage =
    SaveSlotPackageStorage(context.filesDir, ::writeCompressedImage)

private fun FishAudioStorage.archivePort() = object : SaveSlotAudioRestorePort {
    override suspend fun restoreArchivedAudioStream(sessionId: String, resourceId: String, input: InputStream): SaveSlotRestoredAudio {
        val stored = this@archivePort.restoreArchivedAudioStream(sessionId, resourceId, input)
        return SaveSlotRestoredAudio(stored.path, stored.durationMs, stored.byteLength)
    }
    override fun deleteIfOwned(path: String) = this@archivePort.deleteIfOwned(path)
}
suspend fun SaveSlotPackageStorage.Reader.restoreVoices(targetSessionId: String, fishAudioStorage: FishAudioStorage) =
    restoreVoices(targetSessionId, fishAudioStorage.archivePort())
fun SaveSlotPackageStorage.Reader.cleanupCreatedFiles(fishAudioStorage: FishAudioStorage) =
    cleanupCreatedFiles(fishAudioStorage.archivePort())

private fun writeCompressedImage(source: File, output: OutputStream) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码：${source.name}" }
    var sample = 1
    while (
        bounds.outWidth / sample > MAX_COMPRESSED_IMAGE_EDGE ||
        bounds.outHeight / sample > MAX_COMPRESSED_IMAGE_EDGE
    ) {
        sample *= 2
    }
    val bitmap = BitmapFactory.decodeFile(
        source.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    ) ?: error("图片无法解码：${source.name}")
    try {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
            "图片压缩失败：${source.name}"
        }
    } finally {
        bitmap.recycle()
    }
}
