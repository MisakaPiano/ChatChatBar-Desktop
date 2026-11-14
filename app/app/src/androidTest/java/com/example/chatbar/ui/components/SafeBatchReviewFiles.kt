package com.example.chatbar.ui.components

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Synthetic review originals must survive AGP uninstalling the target app after connected tests. */
object SafeBatchReviewFiles {
    fun retain(context: Context, file: File) {
        check(android.os.Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (file.extension == "png") "image/png" else "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ccb-safe-batch-review")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
    }
}
