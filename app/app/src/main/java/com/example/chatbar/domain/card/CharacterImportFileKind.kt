package com.example.chatbar.domain.card

import java.io.InputStream
import java.util.Locale

/** Probe only the prefix before a role-page importer allocates a whole file. */
internal enum class CharacterImportFileKind { CHARACTER, BACKUP, SAVE_SLOT, OTHER_ARCHIVE }

internal object CharacterImportFileProbe {
    private val backupMagic = "CBBACKUP".toByteArray(Charsets.US_ASCII)

    fun inspect(input: InputStream, displayName: String?): CharacterImportFileKind {
        val prefix = ByteArray(backupMagic.size)
        var size = 0
        while (size < prefix.size) {
            val count = input.read(prefix, size, prefix.size - size)
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                prefix[size++] = byte.toByte()
            } else size += count
        }
        if (size == backupMagic.size && prefix.contentEquals(backupMagic)) return CharacterImportFileKind.BACKUP
        val extension = displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in setOf("cbbackup", "cbback")) return CharacterImportFileKind.BACKUP
        if (extension in setOf("cbsave", "save")) return CharacterImportFileKind.SAVE_SLOT
        if (size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
            prefix[2] in byteArrayOf(3, 5, 7) && prefix[3] in byteArrayOf(4, 6, 8)) {
            return CharacterImportFileKind.OTHER_ARCHIVE
        }
        return CharacterImportFileKind.CHARACTER
    }
}
