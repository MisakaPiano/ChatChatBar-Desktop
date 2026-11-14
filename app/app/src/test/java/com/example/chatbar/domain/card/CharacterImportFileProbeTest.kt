package com.example.chatbar.domain.card

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterImportFileProbeTest {
    @Test fun backupMagicWinsEvenWhenRenamedToPngAndNeverReadsPayload() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int {
                check(reads < 8) { "Must not read backup payload" }
                return "CBBACKUP"[reads++].code
            }
        }
        assertEquals(CharacterImportFileKind.BACKUP, CharacterImportFileProbe.inspect(input, "card.png"))
        assertEquals(8, reads)
    }
    @Test fun backupAndSaveNamesAreDirectedToTheirExistingImporters() {
        for (name in listOf("old.CBBACKUP", "old.cbback"))
            assertEquals(CharacterImportFileKind.BACKUP, kind("", name))
        for (name in listOf("old.cbsave", "old.SAVE"))
            assertEquals(CharacterImportFileKind.SAVE_SLOT, kind("{}", name))
    }
    @Test fun zipWithoutSaveExtensionIsRejectedBeforeCharacterDecode() {
        assertEquals(CharacterImportFileKind.OTHER_ARCHIVE, kind("PK\u0003\u0004anything", "unknown.zip"))
        assertEquals(CharacterImportFileKind.SAVE_SLOT, kind("PK\u0003\u0004anything", "test.cbsave"))
    }
    @Test fun pngAndJsonRemainCharacterCandidates() {
        assertEquals(CharacterImportFileKind.CHARACTER, kind("\u0089PNG\r\n\u001a\n", "card.png"))
        assertEquals(CharacterImportFileKind.CHARACTER, kind("{\"card\":", "card.json"))
        assertEquals(CharacterImportFileKind.CHARACTER, kind("", null))
    }
    @Test fun shortReadsAreAccumulatedWithoutReadingPastPrefix() {
        val stream = object : ByteArrayInputStream("CBBACKUPpayload".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 1))
        }
        assertEquals(CharacterImportFileKind.BACKUP, CharacterImportFileProbe.inspect(stream, null))
        assertEquals('p'.code, stream.read())
    }
    private fun kind(content: String, name: String?) = CharacterImportFileProbe.inspect(content.byteInputStream(), name)
}
