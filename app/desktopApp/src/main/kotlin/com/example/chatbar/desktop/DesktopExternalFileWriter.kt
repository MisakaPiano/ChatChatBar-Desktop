package com.example.chatbar.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.io.OutputStream

internal object DesktopExternalFileWriter {
    fun writeText(destination: Path, value: String) = writeBytes(destination, value.toByteArray(Charsets.UTF_8))

    fun writeBytes(destination: Path, value: ByteArray) = write(destination) { write(value) }

    internal fun write(destination: Path, producer: OutputStream.() -> Unit) {
        val target = destination.toAbsolutePath().normalize()
        val parent = target.parent ?: error("导出目标缺少父目录：$target")
        require(Files.isDirectory(parent)) { "导出目标父目录不存在：$parent" }
        val temp = Files.createTempFile(parent, ".ccb-export-", ".tmp")
        try {
            Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { output ->
                output.producer()
                output.flush()
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
