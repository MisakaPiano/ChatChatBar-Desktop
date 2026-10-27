package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.*

/** One explicitly selected file; source is locked read-only, no directory discovery or output files. */
internal object LargeArchiveTool {
    @JvmStatic fun main(args: Array<String>) = kotlinx.coroutines.runBlocking<Unit> {
        var password: CharArray? = null
        var exit = 0
        try {
            require(args.size in 1..2 && (args.size == 1 || args[1] == "--password"))
            require(listOf("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS").all { System.getenv(it).isNullOrEmpty() })
            val source = Path.of(args[0]).toAbsolutePath().normalize()
            require(source.fileName.toString().endsWith(".cbbackup", true))
            DesktopArchiveFiles.safe(source, false)
            require(Files.size(source) in 1..(4L shl 30))
            if (args.size == 2) {
                val console = System.console()
                if (console == null) { println("LR4=PASSWORD_CONSOLE_REQUIRED"); return@runBlocking kotlin.system.exitProcess(2) }
                password = console.readPassword("Archive password (hidden): ") ?: error("CANCELLED")
            }
            FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).use {
                val before = DesktopArchiveFiles.hash(source, 4L shl 30)
                try {
                val start = System.nanoTime()
                val report = LargeArchiveReadOnly.consume(Files.newInputStream(source), password, preserveHistorical = true, active = {
                    check(!Thread.currentThread().isInterrupted && System.nanoTime() - start < 30L*60*1_000_000_000)
                })
                check(DesktopArchiveFiles.hash(source, 4L shl 30) == before)
                println("LR4_ZIP_CENTRAL_INVENTORY=VERIFIED")
                println("LR4_ENVELOPE=${report.version}"); println("LR4_CODE=${report.code}")
                println("LR4_ENCRYPTED=${report.encrypted}"); println("LR4_FILES=${report.files}")
                println("LR4_TYPED_RECORDS=${report.typedRecords}"); println("LR4_UNSUPPORTED_RECORDS=${report.unsupportedRecords}")
                println("LR4_RESOURCE_REFERENCES=${report.resourceReferences}"); println("LR4_MISSING_RESOURCES=${report.missingResources}")
                println("LR4_UNINTERPRETED_RESOURCES=${report.uninterpretedResources}"); println("LR4_DEPENDENCY_ISSUES=${report.dependencyIssues}")
                println("LR4_HISTORIC_VOICES=${report.historicVoices}"); println("LR4_NESTED_PACKAGES=${report.nestedPackages}"); println("LR4_SCOPE=${report.scope}")
                println("LR4_MOMENTS=${report.moments}"); report.reasons.sorted().forEach { reason -> println("LR4_REASON=$reason") }
                println("LR4_SEMANTICS=${report.semanticState}"); println("LR4_RESTORE_SUPPORTED=false")
                } finally {
                    check(DesktopArchiveFiles.hash(source, 4L shl 30) == before)
                    println("LR4_SOURCE_BYTES=UNCHANGED")
                }
            }
        } catch (e: LargeArchiveReadOnly.Refusal) { println("LR4=REFUSED_${e.stage}"); exit = 2
        } catch (_: Throwable) { println("LR4=REFUSED_INPUT_OR_IO"); exit = 2
        } finally { password?.fill('\u0000') }
        if (exit != 0) kotlin.system.exitProcess(exit)
    }
}
