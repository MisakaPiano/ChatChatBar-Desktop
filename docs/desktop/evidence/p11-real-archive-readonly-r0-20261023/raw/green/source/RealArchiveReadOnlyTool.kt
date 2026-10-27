package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import com.sun.nio.file.ExtendedOpenOption
import java.io.ByteArrayInputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*

/** Test-classpath tooling only. No data root, observer, restore service or output file. */
internal object RealArchiveReadOnlyTool {
    val MAX_SOURCE = DesktopBackupReadOnlyInspector.MAX_COMPRESSED.toLong()
    fun interface MaskedPassword { fun read(): CharArray? }
    data class Result(val exit: Int, val lines: List<String>)
    private class Hint(val version: Int, val encrypted: Boolean) : RuntimeException()
    private class Refusal(val code: String) : RuntimeException()
    private val stages = setOf("INPUT", "AUTHENTICATION", "ZIP", "ENTITY_SCHEMA", "METADATA", "MANIFEST", "SCOPE")

    @JvmStatic fun main(args: Array<String>) {
        val result = runBlocking { execute(args, MaskedPassword {
            // Never use stdin/readLine, an argument, environment, file or GUI fallback for a password.
            System.console()?.readPassword("Archive password (hidden): ")
        }) }
        result.lines.forEach(::println)
        kotlin.system.exitProcess(result.exit)
    }

    suspend fun execute(args: Array<String>, prompt: MaskedPassword): Result {
        var password: CharArray? = null
        try {
            if (args.size != 2 || args[0] != "--single-file-read-only") throw Refusal("ARGUMENTS")
            if (!System.getProperty("os.name").startsWith("Windows")) throw Refusal("PLATFORM_UNVERIFIED")
            val text = args[1]
            // Local absolute drive path only: no UNC, device namespace, ADS, relative or normalized-away segments.
            if (!text.matches(Regex("[A-Za-z]:[\\\\/].*")) || text.drop(2).contains(':')) throw Refusal("PATH_POLICY")
            val path = Path.of(text)
            if (path.any { it.toString() in setOf(".", "..") } || !path.fileName.toString().endsWith(".cbbackup", true))
                throw Refusal("FILE_TYPE")
            DesktopArchiveFiles.safe(path, false) // Every ancestor, NOFOLLOW, Windows reparse attribute.
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (before.size() !in 13..MAX_SOURCE) throw Refusal("SOURCE_LIMIT")
            // Keep a read-only Windows handle denying write/delete sharing over all independent scans.
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).use {
                val header = ByteArray(12 + 32768)
                var length = 0
                val baseline: String
                val hint: Hint
                try {
                    baseline = DesktopArchiveFiles.read(path, MAX_SOURCE) { bytes, count ->
                        val n = minOf(count, header.size - length)
                        if (n > 0) { bytes.copyInto(header, length, 0, n); length += n }
                    }
                    // Codec parses the bounded strict envelope. This is ONLY an untrusted password/version hint.
                    // No JSON parser or alternative archive decoder is introduced by the tool.
                    hint = try {
                        AppBackupCodec.decryptingForInspection(ByteArrayInputStream(header, 0, length), null) { v, e -> throw Hint(v, e) }.close()
                        throw Refusal("HEADER")
                    } catch (value: Hint) { value }
                      catch (_: Exception) { throw Refusal("HEADER_OR_VERSION") }
                } finally { header.fill(0) }
                if (hint.encrypted) {
                    password = prompt.read() ?: throw Refusal("MASKED_CONSOLE_REQUIRED_OR_CANCELLED")
                    if (password.size !in 8..1024) throw Refusal("PASSWORD_LENGTH")
                }
                val inspector = DesktopBackupReadOnlyInspector()
                val s0 = try { inspector.inspect(path, password) }
                    catch (failure: DesktopBackupReadOnlyInspector.InspectionFailure) {
                        return Result(2, listOf("R0=READ_ONLY", "S0=REJECTED_${stage(failure)}", "S1=NOT_RUN", "G2B=NOT_RUN"))
                    }
                if (s0.sourceSha256 != baseline || s0.envelopeVersion != hint.version || s0.encrypted != hint.encrypted)
                    throw Refusal("SOURCE_CHANGED")
                val lines = mutableListOf("R0=READ_ONLY", "S0=FORMAT_READABLE",
                    "ENVELOPE=${s0.envelopeVersion}", "MANIFEST=${s0.manifestVersion}", "APP_CODE=${s0.appVersionCode}",
                    "ENCRYPTED=${s0.encrypted}", "FILES=${s0.fileCount}", "BYTES=${s0.totalBytes}",
                    "UNKNOWN_ENTITIES=${s0.uninterpretedEntities}", "MODELS=${s0.modelRecords}", "EMBEDDINGS=${s0.embeddingRecords}",
                    "FORMAT_CARDS=${s0.formatCardRecords}", "WORLD_BOOKS=${s0.worldBookRecords}", "SAVE_PACKAGES=${s0.saveSlotPackages}",
                    "MOMENT_POSTS=${s0.momentPosts}", "MOMENT_TASKS=${s0.momentTasks}",
                    "KNOWN_CREDENTIAL_CATEGORIES=" + s0.credentials.sortedBy { it.name }.joinToString(",") { it.name })
                s0.scope?.let { s ->
                    lines += "SCOPE=SELECTIVE"
                    lines += "SCOPE_CHARACTERS=${s.characters}"
                    lines += "SCOPE_SESSIONS=${s.sessions}"
                    lines += "SCOPE_MOMENTS=${s.moments}"
                    lines += "SCOPE_MOMENT_IMAGES=${s.momentsImages}"
                } ?: run { lines += "SCOPE=LEGACY_FULL" }
                try {
                    val s1 = inspector.inspectMoments(path, password)
                    if (s1.records.any { record -> record.sourceSha256 != baseline }) throw Refusal("SOURCE_CHANGED")
                    lines += "S1=${s1.state.name}"
                    val reasons = setOf("KNOWN_CREDENTIALS", "UNKNOWN_ENTITY_FAMILY", "MOMENT_SCHEMA_OR_GRAPH")
                    if (s1.failure != null) lines += "S1_REASON=" + if (s1.failure in reasons) s1.failure else "UNCLASSIFIED"
                } catch (failure: DesktopBackupReadOnlyInspector.InspectionFailure) { lines += "S1=CHECK_FAILED_${stage(failure)}" }
                try {
                    inspector.inspectCredentialSources(path, password).use { census ->
                        if (census.sourceSha256 != baseline) throw Refusal("SOURCE_CHANGED")
                        lines += "G2B=" + if (census.complete) "COMPLETE_KNOWN_SUBSET" else "INCOMPLETE"
                        lines += "G2B_REASONS=" + census.limitations.sortedBy { it.name }.joinToString(",") { it.name }
                        lines += "G2B_RECORDS=${census.records.size}"
                        // Deliberately no target observer/comparison/plan call, and no source identities or digests.
                    }
                } catch (failure: DesktopBackupReadOnlyInspector.InspectionFailure) { lines += "G2B=CHECK_FAILED_${stage(failure)}" }
                DesktopArchiveFiles.safe(path, false)
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (before.fileKey() != after.fileKey() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
                    DesktopArchiveFiles.hash(path, MAX_SOURCE) != baseline) throw Refusal("SOURCE_CHANGED")
                lines += "SOURCE_BYTES=UNCHANGED"
                lines += "RESTORE_SUPPORTED=false"
                lines += "FULL_PORTABILITY_ACCEPTED=false"
                return Result(0, lines.toList())
            }
        } catch (cancelled: CancellationException) { return Result(3, listOf("R0=CANCELLED", "RESTORE_SUPPORTED=false"))
        } catch (refused: Refusal) { return Result(2, listOf("R0=REFUSED_${refused.code}", "RESTORE_SUPPORTED=false"))
        } catch (_: Exception) { return Result(2, listOf("R0=REFUSED_INPUT_OR_IO", "RESTORE_SUPPORTED=false"))
        } finally { password?.fill('\u0000') }
    }
    private fun stage(failure: DesktopBackupReadOnlyInspector.InspectionFailure) = if (failure.stage in stages) failure.stage else "UNCLASSIFIED"
}
