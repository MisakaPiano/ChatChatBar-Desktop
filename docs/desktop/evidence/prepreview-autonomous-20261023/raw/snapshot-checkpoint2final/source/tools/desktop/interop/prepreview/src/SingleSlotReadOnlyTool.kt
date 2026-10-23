package com.example.chatbar.desktop

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlinx.coroutines.runBlocking

/** Explicit single-file private offline probe. No repositories, extraction or restore. */
internal object SingleSlotReadOnlyTool {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        try {
            check(args.size == 1)
            val source = Path.of(args[0])
            DesktopArchiveFiles.safe(source, false)
            FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).use {
                val before = DesktopArchiveFiles.hash(source, 256L * 1024 * 1024)
                val result = DesktopSaveSlotInspector().inspect(source)
                check(DesktopArchiveFiles.hash(source, 256L * 1024 * 1024) == before)
                println("SLOT_FORMAT=${result.format.name}")
                println("SLOT_STATUS=${result.status.name}")
                println("SLOT_CODE=${result.code.name}")
                println("SOURCE_BYTES=UNCHANGED")
                println("RESTORE_PERFORMED=false")
            }
        } catch (_: Exception) {
            println("SLOT=REFUSED_INPUT_OR_IO")
            kotlin.system.exitProcess(2)
        }
    }
}
