package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.*
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.runBlocking

/** Supplemental authentication-only tool. Does not bypass or replace S0 admission.
 * Streams the entire decrypted byte stream to EOF without parsing/storing any plaintext.
 * ZIP, inventory, schema, source app identity and restore are deliberately NOT verified.
 */
internal object StreamingEnvelopeProbe {
    private const val MAX_SOURCE = 4L * 1024 * 1024 * 1024
    data class Result(val version: Int, val encrypted: Boolean, val bytes: Long)

    fun consume(source: InputStream, password: CharArray?, maximum: Long = MAX_SOURCE): Result {
        var version = 0
        var encrypted = false
        var count = 0L
        source.use { raw -> AppBackupCodec.decryptingForInspection(raw, password) { v, e -> version = v; encrypted = e }.use { plain ->
            val buffer = ByteArray(65536)
            try {
                while (true) {
                    if (Thread.currentThread().isInterrupted) error("CANCELLED")
                    val n = plain.read(buffer)
                    if (n < 0) break
                    count += n
                    check(count <= maximum) { "STREAM_LIMIT" }
                }
            } finally { buffer.fill(0) }
        } }
        return Result(version, encrypted, count)
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        var password: CharArray? = null
        var exit = 0
        try {
            check(args.size in 1..2 && (args.size == 1 || args[1] == "--container"))
            val console = System.console()
            if (console == null) {
                println("ENVELOPE_PROBE=MASKED_CONSOLE_UNAVAILABLE")
                kotlin.system.exitProcess(2)
            }
            val path = Path.of(args[0])
            DesktopArchiveFiles.safe(path, false)
            check(Files.size(path) in 13..MAX_SOURCE)
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).use {
                val before = DesktopArchiveFiles.hash(path, MAX_SOURCE)
                password = console.readPassword("Archive password (hidden): ") ?: error("CANCELLED")
                check(password!!.size in 8..1024)
                val result = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { consume(it, password) }
                val container = if (args.size == 2) Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
                    StreamingContainerProbe.consume(it, password)
                } else null
                check(DesktopArchiveFiles.hash(path, MAX_SOURCE) == before)
                println("ENVELOPE_VERSION=${result.version}")
                println("AUTHENTICATED_ENCRYPTED_STREAM=${result.encrypted}")
                println("SOURCE_BYTES=UNCHANGED")
                println("FULL_FORMAT_SCHEMA=NOT_CHECKED")
                container?.let {
                    println("STREAM_LOCAL_CRC_INVENTORY=VERIFIED")
                    println("APP_CODE=${it.appCode}")
                    println("MANIFEST_VERSION=${it.format}")
                    println("FILES=${it.fileCount}")
                    println("SCOPE_PRESENT=${it.selective}")
                    it.entityCounts.toSortedMap().forEach { (kind, count) -> println("KNOWN_COUNT_${kind.uppercase(Locale.ROOT)}=$count") }
                    println("UNKNOWN_ENTITY_FILES=${it.unknownEntityFiles}")
                    println("CENTRAL_DIRECTORY_ENTITY_SCOPE_GRAPH=NOT_VERIFIED")
                }
                println("S0_S1_G2B=NOT_PERFORMED")
                println("RESTORE_AUTHORIZED=false")
            }
        } catch (_: Exception) {
            println("ENVELOPE_PROBE=REFUSED_AUTHENTICATION_INPUT_OR_LIMIT")
            exit = 2
        } finally { password?.fill('\u0000') }
        kotlin.system.exitProcess(exit)
    }
}
