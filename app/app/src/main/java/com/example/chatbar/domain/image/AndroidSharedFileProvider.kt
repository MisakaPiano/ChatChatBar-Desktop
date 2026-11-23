package com.example.chatbar.domain.image

import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.chatbar.domain.lanSync.LanSyncHeifStill

/** Gives the shared image's advertised name the matching MIME type, including legacy originals. */
class AndroidSharedFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val originalType = super.getType(uri)
        if (uri.pathSegments.firstOrNull() != "chatbar_images") return originalType
        val name = if (uri.encodedQuery == null) uri.lastPathSegment else {
            val column = OpenableColumns.DISPLAY_NAME
            super.query(uri, arrayOf(column), null, null, null)?.use { names ->
                if (names.moveToFirst()) names.getString(names.getColumnIndexOrThrow(column)) else null
            }
        }
        val extension = name?.substringAfterLast('.', "")?.lowercase()
        return LanSyncHeifStill.MediaType.entries.firstOrNull { it.extension == extension }?.mimeType ?: originalType
    }
}
