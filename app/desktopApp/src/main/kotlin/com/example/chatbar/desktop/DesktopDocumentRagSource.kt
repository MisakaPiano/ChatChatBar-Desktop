package com.example.chatbar.desktop

import com.sun.jna.platform.win32.Kernel32
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Shared Desktop source admission, not a second chunker. P9-S1 budgets and file rules are
 * moved unchanged so read retrieval and preparation cannot drift. No writes or recovery. */
internal class DesktopDocumentRagSource(private val root: Path) {
    fun readDocument(reference: String): String {
        val relative = Path.of(reference)
        // Android absolute paths/assets require a separate resource migration contract.
        if (relative.isAbsolute || relative.nameCount != 2 || relative.getName(0).toString() != "documents" ||
            relative.any { it.toString() in setOf(".", "..") } ||
            reference.any { it in "<>:\"|?*" || it.code < 32 } ||
            relative.last().toString().let { it.endsWith('.') || it.endsWith(' ') })
            reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        val path = root.toAbsolutePath().normalize().resolve(relative).normalize()
        var ancestor = path.root
        for (part in path) {
            ancestor = ancestor.resolve(part)
            val attr = Files.readAttributes(ancestor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attr.isSymbolicLink || attr.isOther || if (ancestor == path) !attr.isRegularFile else !attr.isDirectory)
                reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            safeWindowsPath(ancestor)
        }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
        if (bytes.size > MAX_DOCUMENT_BYTES) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** Admission estimate only, never an alternative chunker. Mirrors the official heading
     * recognition so every possible source path is bounded before chunkDocument allocates it.
     * For body B > 700, adjacent greedy merged chunks have combined length >= 700; hence
     * 2 * floor(B / 700) + 2 conservatively bounds their count. Splitting/trim removes characters
     * and joining consumes existing separators; overlap adds at most 70 chars per final chunk.
     * All arithmetic is Long over the existing 1 MiB input bound; no plan strings are built here.
     */
    fun checkPlanBudget(content: String, fileName: String) {
        if (fileName.length > MAX_FILE_NAME_CHARS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        val headings = ArrayList<String>(6)
        val fileLabelLength = if (fileName.isBlank()) "未命名文档".length.toLong() else fileName.length.toLong()
        var labelLength = fileLabelLength
        var headingCount = 0L
        var sectionCount = 0L
        var bodyLength = 0L
        var bodyPresent = false
        var chunkBound = 0L
        var characterBound = 0L
        fun accountBody(length: Long, label: Long) {
            val count = if (length <= 700L) 1L else 2L * (length / 700L) + 2L
            sectionCount++
            chunkBound += count
            // 770 body/overlap + 5 for the official source prefix and newline.
            characterBound += count * (label + 775L)
            if (sectionCount > MAX_PLAN_SECTIONS || chunkBound > MAX_PLAN_CHUNKS ||
                characterBound > MAX_PLAN_CHARS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        }
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            val markdown = MARKDOWN_HEADING.find(trimmed)
            val plain = markdown == null && trimmed.isNotBlank() && trimmed.length <= 40 &&
                (trimmed.endsWith(':') || trimmed.endsWith('：') || PLAIN_HEADING.matches(trimmed))
            if (markdown != null || plain) {
                if (bodyPresent) accountBody(bodyLength, labelLength)
                bodyLength = 0L; bodyPresent = false
                if (++headingCount > MAX_PLAN_SECTIONS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
                val level = markdown?.groupValues?.get(1)?.length ?: 1
                val title = markdown?.groupValues?.get(2)?.trim() ?: trimmed.trimEnd(':', '：')
                if (title.length > MAX_HEADING_CHARS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
                while (headings.size >= level) headings.removeAt(headings.lastIndex)
                headings.add(title)
                val pathLength = headings.sumOf { it.length.toLong() } + (headings.size - 1) * 3L
                labelLength = if (pathLength == 0L) fileLabelLength else fileLabelLength + 3L + pathLength
                if (labelLength > MAX_SOURCE_LABEL_CHARS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            } else {
                bodyLength += line.length.toLong() + 1L
                bodyPresent = bodyPresent || line.isNotBlank()
            }
        }
        if (bodyPresent) accountBody(bodyLength, labelLength)
        // Official fallback chunks the complete input if no nonblank section body was emitted.
        if (sectionCount == 0L && content.isNotBlank()) accountBody(content.length.toLong(), fileLabelLength)
    }

    fun requireIdentity(storageId: String, entityId: String) {
        if (storageId != entityId || !entityId.matches(Regex("[A-Za-z0-9_-]{1,256}")))
            reject(DesktopDocumentRagResult.Reason.INVENTORY)
    }
    fun safeWindowsPath(path: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString())
            if (attributes == -1 || attributes and 0x400 != 0) reject(DesktopDocumentRagResult.Reason.INVENTORY)
        }
    }
    fun validVector(vector: List<Float>): Boolean {
        // Shared search accumulates Float squares. Finite inputs can still overflow or underflow
        // its norm: refuse those values rather than turning an invalid score into an empty success.
        var norm = 0f
        for (value in vector) {
            if (!value.isFinite()) return false
            norm += value * value
            if (!norm.isFinite()) return false
        }
        return norm > 0f
    }
    internal class Rejected(val reason: DesktopDocumentRagResult.Reason) : Exception()
    private fun reject(reason: DesktopDocumentRagResult.Reason): Nothing = throw Rejected(reason)
    companion object {
        const val MAX_DOCUMENT_BYTES = 1024 * 1024
        // First-stage admission caps, not truncation: 256-char names, six 128-char headings,
        // 1024-char labels, <=2048 sections/chunks (including the existing 1200-chunk fixture).
        // The 2 Mi UTF-16-char tagged-output estimate bounds label replication, ~4 MiB payload;
        // official intermediate lists/maps have bounded overhead, not an exact JVM heap claim.
        const val MAX_FILE_NAME_CHARS = 256
        const val MAX_HEADING_CHARS = 128
        const val MAX_SOURCE_LABEL_CHARS = 1024L
        const val MAX_PLAN_SECTIONS = 2048L
        const val MAX_PLAN_CHUNKS = 2048
        const val MAX_PLAN_CHARS = 2L * 1024 * 1024
        private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+(.+)$")
        private val PLAIN_HEADING = Regex("^([一二三四五六七八九十]+[、.．]|\\d+[、.．])\\s*.+$")
    }
}
