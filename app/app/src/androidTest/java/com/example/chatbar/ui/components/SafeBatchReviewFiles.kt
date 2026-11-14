package com.example.chatbar.ui.components

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Synthetic review originals must survive AGP uninstalling the target app after connected tests. */
object SafeBatchReviewFiles {
    fun captureScreen(context: Context, name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        check(bitmap.width > 100 && bitmap.height > 100)
        val file = File(File(context.getExternalFilesDir(null), "safe-batch-review").also { it.mkdirs() }, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        retain(context, file)
    }
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
