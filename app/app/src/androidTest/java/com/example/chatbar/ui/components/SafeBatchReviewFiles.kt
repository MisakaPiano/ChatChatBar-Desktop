package com.example.chatbar.ui.components

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Retain synthetic originals in app-internal files for CI run-as collection before uninstall. */
object SafeBatchReviewFiles {
    fun captureScreen(context: Context, name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        check(bitmap.width > 100 && bitmap.height > 100)
        val file = File(File(context.getExternalFilesDir(null), "safe-batch-review").also { it.mkdirs() }, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        retain(context, file)
        val metrics = context.resources.displayMetrics
        val info = File(context.getExternalFilesDir(null), "$name.display.json")
        info.writeText(org.json.JSONObject().put("widthPixels", metrics.widthPixels)
            .put("heightPixels", metrics.heightPixels).put("densityDpi", metrics.densityDpi)
            .put("screenshotWidth", bitmap.width).put("screenshotHeight", bitmap.height).toString())
        retain(context, info)
    }
    fun retain(context: Context, file: File) {
        val directory = File(context.filesDir, "safe-batch-retained").apply { mkdirs() }
        file.copyTo(File(directory, file.name), overwrite = true)
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // MediaStore.Downloads/RELATIVE_PATH do not exist on API26.
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (file.extension == "png") "image/png" else "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ccb-safe-batch-review")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
    }
}
