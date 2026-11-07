package com.example.chatbar.domain.image

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object NovelAiHistoryExportNaming {
    private val invalidCharacters = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
    private val imageExtension = Regex("(?i)\\.(png|jpe?g|webp|gif)$")

    fun displayName(
        sourcePath: String,
        createdAt: Long,
        selectionIndex: Int,
        customTitle: String,
        locale: Locale = Locale.US,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val extension = File(sourcePath).extension
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "png"
        val cleanedTitle = customTitle
            .trim()
            .replace(imageExtension, "")
            .replace(invalidCharacters, "_")
            .trim(' ', '.')
            .take(120)
        val base = cleanedTitle.ifBlank {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", locale).apply {
                this.timeZone = timeZone
            }.format(Date(createdAt))
            "CCBImage_$timestamp"
        }
        return "${base}_${selectionIndex}.$extension"
    }

    fun sameName(first: String, second: String): Boolean =
        first.trim().equals(second.trim(), ignoreCase = true)
}
