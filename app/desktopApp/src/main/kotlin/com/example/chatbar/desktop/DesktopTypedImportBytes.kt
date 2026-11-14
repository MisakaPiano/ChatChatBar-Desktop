package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.AppBackupCodec
import java.nio.file.Files
import java.nio.file.Path

/** Typed page pickers are not content validation. Reject known archives before whole-file allocation. */
internal object DesktopTypedImportBytes {
    fun read(path: Path, wholeFile: (Path) -> ByteArray = Files::readAllBytes): ByteArray {
        requireNotArchive(path)
        return wholeFile(path)
    }

    private fun requireNotArchive(path: Path) {
        val prefix = Files.newInputStream(path).use { it.readNBytes(8) }
        if (prefix.contentEquals(AppBackupCodec.magic))
            error("这是整应用备份文件，请到“数据 → 备份与导入”检查；未执行导入。")
        if (prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
            (prefix[2].toInt() to prefix[3].toInt()) in setOf(3 to 4, 5 to 6, 7 to 8, 6 to 6, 6 to 7))
            error("这是压缩存档文件，不能作为普通卡片或模型模板读取。会话存档请从对话的“存档”导入，整应用备份请到“数据 → 备份与导入”检查；未执行导入。")
    }

    /** Preserve Files.readString's strict UTF-8 behavior at the existing JSON-only entry points. */
    fun text(path: Path): String {
        requireNotArchive(path)
        return Files.readString(path, Charsets.UTF_8)
    }
}
