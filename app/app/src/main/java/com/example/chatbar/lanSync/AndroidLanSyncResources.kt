package com.example.chatbar.lanSync

import com.example.chatbar.domain.image.NovelAiPostProcessPolicy
import com.example.chatbar.domain.image.NovelAiPostProcessResultRecord
import com.example.chatbar.domain.image.NovelAiPostProcessTab
import com.example.chatbar.domain.lanSync.LanSyncPostProcess
import com.example.chatbar.domain.lanSync.LanSyncRecord
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
    require(Files.size(path) <= if (result == null) 32L * 1024 * 1024 else NovelAiPostProcessResultRecord.MAX_IMAGE_BYTES.toLong()) { "SYNC_IMAGE_BUDGET" }
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
    val pixels = if (result == null) 32_000_000L else NovelAiPostProcessPolicy.MAX_PIXELS *
        if (result.operation == NovelAiPostProcessTab.UPSCALE.name) 4 else 1
    require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= pixels &&
        (result == null || options.outMimeType == "image/png")) { "SYNC_IMAGE_INVALID" }
}
