package com.example.chatbar.desktop.backup

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.*
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Only owned synthetic paths; wire models are independent of Inspector DTOs. */
class RealArchiveReadOnlyToolTest {
    private val fakeKey = "R0_SYNTHETIC_KEY_NEVER_REAL"
    private fun generatedPassword() = CharArray(24) { ('a'.code + SecureRandom().nextInt(26)).toChar() }
    private fun wire(files: Map<String, ByteArray> = emptyMap(), password: CharArray? = null,
        prefs: Official145Preferences = Official145Preferences(), badHash: Boolean = false): ByteArray {
        val preferences = officialWireJson.encodeToString(prefs).toByteArray()
        val counts = files.keys.filter { it.startsWith("entities/") }.groupingBy { it.removePrefix("entities/").substringBefore('/').removeSuffix(".json") }.eachCount()
        val manifest = officialWireJson.encodeToString(Official145Manifest(appVersionCode = 84, appVersionName = "1.4.5",
            createdAt = 1, sourceFilesRoot = "synthetic-private-source", counts = counts, fileCount = files.size,
            totalBytes = files.values.sumOf { it.size.toLong() }, preferencesSha256 = CensusWireFixtures.digest(preferences)))
        val inventory = files.entries.joinToString("\n") { (p, b) -> officialWireJson.encodeToString(
            OfficialWireFileRecord(p, b.size.toLong(), if (badHash) "0".repeat(64) else CensusWireFixtures.digest(b))) }
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(Official147FixtureCodec.encrypting(out, password, 1)).use { zip ->
                for ((p, b) in files.mapKeys { "files/${it.key}" } + mapOf("preferences.json" to preferences,
                    "inventory.jsonl" to inventory.toByteArray(), "manifest.json" to manifest.toByteArray())) {
                    zip.putNextEntry(ZipEntry(p)); zip.write(b); zip.closeEntry()
                }
            }
        }.toByteArray()
    }
    private suspend fun owned(label: String, bytes: ByteArray, block: suspend (Path) -> Unit) {
        val base = Files.createDirectories(Path.of("build/readonly-r0/synthetic")).toAbsolutePath().normalize()
        val dir = Files.createTempDirectory(base, "$label-")
        val source = Files.write(dir.resolve("input.cbbackup"), bytes)
        // Represents an existing root/journal, not a running application data root.
        val sentinel = Files.writeString(dir.resolve("synthetic-existing-journal"), "R0-existing-evidence")
        val before = Files.list(dir).use { it.toList().associate { p -> p.fileName.toString() to Files.readAllBytes(p).toList() } }
        try { block(source) } finally {
            val after = Files.list(dir).use { it.toList().associate { p -> p.fileName.toString() to Files.readAllBytes(p).toList() } }
            assertTrue(before == after) // No source mutation, plaintext copies, cleanup or journal mutation.
            assertTrue(Files.exists(sentinel))
        }
    }
    private suspend fun check(path: Path, password: CharArray? = null): RealArchiveReadOnlyTool.Result {
        val r = RealArchiveReadOnlyTool.execute(arrayOf("--single-file-read-only", path.toString())) { password }
        val output = r.lines.joinToString("\n")
        assertFalse(output.contains(fakeKey)); assertFalse(output.contains(path.toString()))
        assertFalse(output.contains("synthetic-private-source")); assertFalse(output.contains("synthetic-private-name"))
        assertFalse(Regex("[a-f0-9]{64}").containsMatchIn(output))
        assertTrue(r.lines.all { it.matches(Regex("[A-Z0-9_]+=[A-Za-z0-9_,]*")) })
        return r
    }
    @Test fun plainCode84UsesThreeSeparateReports() = runBlocking {
        owned("plain", wire()) { p ->
            val r = check(p); assertEquals(0, r.exit)
            assertTrue("S0=FORMAT_READABLE" in r.lines); assertTrue("S1=NOT_APPLICABLE" in r.lines)
            assertTrue("G2B=COMPLETE_KNOWN_SUBSET" in r.lines); assertTrue("SOURCE_BYTES=UNCHANGED" in r.lines)
            assertTrue("APP_CODE=84" in r.lines); assertTrue("RESTORE_SUPPORTED=false" in r.lines)
        }
    }
    @Test fun plainCredentialsAreCategoriesOnly() = runBlocking {
        owned("keys", wire(prefs = Official145Preferences(novelAiToken = fakeKey, fishAudioKey = fakeKey))) { p ->
            val r = check(p); assertEquals(0, r.exit)
            assertTrue("S1_REASON=KNOWN_CREDENTIALS" in r.lines); assertTrue("G2B=COMPLETE_KNOWN_SUBSET" in r.lines)
        }
    }
    @Test fun encryptedCode84UsesVolatileMaskedInputAndWipesIt() = runBlocking {
        val pw = generatedPassword()
        try { owned("encrypted", wire(password = pw, prefs = Official145Preferences(novelAiToken = fakeKey))) { p ->
            val entered = pw.copyOf(); val r = check(p, entered)
            assertEquals(0, r.exit); assertTrue("ENCRYPTED=true" in r.lines)
            assertTrue(entered.all { it == '\u0000' })
        } } finally { pw.fill('\u0000') }
    }
    @Test fun wrongPasswordIsSanitizedAndWiped() = runBlocking {
        val pw = generatedPassword()
        try { owned("wrong-password", wire(password = pw)) { p ->
            val wrong = generatedPassword(); val r = check(p, wrong)
            assertEquals(2, r.exit); assertTrue("S0=REJECTED_AUTHENTICATION" in r.lines)
            assertTrue("G2B=NOT_RUN" in r.lines); assertTrue(wrong.all { it == '\u0000' })
        } } finally { pw.fill('\u0000') }
    }
    @Test fun authenticationTagCorruptionIsRejected() = runBlocking {
        val pw = generatedPassword()
        try { val bytes = wire(password = pw); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            owned("bad-tag", bytes) { p -> assertTrue("S0=REJECTED_AUTHENTICATION" in check(p, pw.copyOf()).lines) }
        } finally { pw.fill('\u0000') }
    }
    @Test fun missingMaskedConsoleHasNoStdinFallback() = runBlocking {
        val pw = generatedPassword()
        try { owned("no-console", wire(password = pw)) { p ->
            val r = check(p); assertEquals(2, r.exit)
            assertTrue("R0=REFUSED_MASKED_CONSOLE_REQUIRED_OR_CANCELLED" in r.lines)
        } } finally { pw.fill('\u0000') }
    }
    @Test fun realChildJvmRejectsEncryptedFileWithoutConsole() = runBlocking {
        val pw = generatedPassword()
        try { owned("child-no-console", wire(password = pw)) { p ->
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "-Xmx256m", "-Djava.awt.headless=true", "-cp", System.getProperty("ccb.readonly.classpath"),
                RealArchiveReadOnlyTool::class.java.name, "--single-file-read-only", p.toString()).redirectErrorStream(true).start()
            process.outputStream.close() // A pipe is not a masked terminal. No password sent to the child.
            assertTrue(process.waitFor(45, TimeUnit.SECONDS))
            val text = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(2, process.exitValue()); assertTrue(text.contains("MASKED_CONSOLE_REQUIRED_OR_CANCELLED"))
            assertFalse(text.contains(p.toString())); assertFalse(text.contains("Exception"))
        } } finally { pw.fill('\u0000') }
    }
    @Test fun fileSelectionArgumentsNeverPromptOrScan() = runBlocking {
        for (args in listOf(emptyArray(), arrayOf("--password", "forbidden"), arrayOf("--single-file-read-only", "x", "extra"))) {
            var called = false
            val r = RealArchiveReadOnlyTool.execute(args) { called = true; null }
            assertEquals(2, r.exit); assertFalse(called)
        }
    }
    @Test fun pathPolicyRejectsRelativeUncAdsAndWrongExtension() = runBlocking {
        for (p in listOf("relative.cbbackup", "\\\\host\\share\\a.cbbackup", "\\\\?\\C:\\a.cbbackup", "C:\\a.cbbackup:secret", "C:\\a.txt", "C:\\..\\a.cbbackup")) {
            var called = false
            assertEquals(2, RealArchiveReadOnlyTool.execute(arrayOf("--single-file-read-only", p)) { called = true; null }.exit)
            assertFalse(called)
        }
    }
    @Test fun directoryAndMissingFileRejectedWithoutPrompt() = runBlocking {
        val base = Files.createDirectories(Path.of("build/readonly-r0/paths/directory.cbbackup")).toAbsolutePath()
        assertEquals(2, check(base).exit); assertEquals(2, check(base.resolve("missing.cbbackup")).exit)
    }
    @Test fun oversizedSourceRejectedBeforeReadingOrPassword() = runBlocking {
        val p = Path.of("build/readonly-r0/oversize.cbbackup").toAbsolutePath()
        RandomAccessFile(p.toFile(), "rw").use { it.setLength(RealArchiveReadOnlyTool.MAX_SOURCE + 1) }
        try { assertTrue("R0=REFUSED_SOURCE_LIMIT" in check(p).lines) } finally { Files.delete(p) }
    }
    @Test fun unknownEnvelopeVersionCannotReachPasswordPrompt() = runBlocking {
        val bytes = wire()
        val index = bytes.toString(Charsets.ISO_8859_1).indexOf("\"version\":1") + "\"version\":".length
        assertTrue(index > 0); bytes[index] = '9'.code.toByte()
        owned("future", bytes) { p -> assertEquals(2, check(p).exit) }
    }
    @Test fun damagedZipRejectedWithoutDumpingPayload() = runBlocking {
        val bytes = wire(); bytes[bytes.lastIndex - 21] = 0 // ZIP end signature.
        owned("zip", bytes) { p -> assertTrue("S0=REJECTED_ZIP" in check(p).lines) }
    }
    @Test fun finalInventoryFailureDoesNotPublishAnyCensus() = runBlocking {
        owned("bad-hash", wire(mapOf("images/x.png" to byteArrayOf(1, 2)), badHash = true)) { p ->
            val r = check(p); assertTrue("S0=REJECTED_MANIFEST" in r.lines); assertTrue("G2B=NOT_RUN" in r.lines)
        }
    }
    @Test fun unknownEntityIsReadableButNotCensusCompleteOrMomentVerified() = runBlocking {
        owned("unknown", wire(mapOf("entities/future/x.json" to """{"text":"synthetic-private-name"}""".toByteArray()))) { p ->
            val r = check(p); assertEquals(0, r.exit); assertTrue("S0=FORMAT_READABLE" in r.lines)
            assertTrue("G2B=INCOMPLETE" in r.lines); assertTrue("S1_REASON=UNKNOWN_ENTITY_FAMILY" in r.lines)
        }
    }
    @Test fun characterChatSaveSlotAndMediaRemainReadableWithIncompleteCensus() = runBlocking {
        val files = mapOf(
            "entities/character_cards/c.json" to officialWireJson.encodeToString(com.example.chatbar.data.local.entity.CharacterCard("c", "synthetic-private-name", createdAt = 1, updatedAt = 1)).toByteArray(),
            "entities/chat_sessions/s.json" to """{"id":"s","characterCardId":"c","title":"synthetic-private-name","createdAt":1,"updatedAt":1}""".toByteArray(),
            "entities/save_slots/a.json" to """{"id":"a","sessionId":"s","name":"synthetic-private-name","createdAt":1}""".toByteArray(),
            "save_slot_packages/s.cbsave" to byteArrayOf(1, 2, 3), "images/x.png" to byteArrayOf(4, 5), "audio/x.mp3" to byteArrayOf(6, 7))
        owned("mixed", wire(files)) { p ->
            val r = check(p); assertEquals(0, r.exit); assertTrue("S0=FORMAT_READABLE" in r.lines)
            assertTrue("SAVE_PACKAGES=1" in r.lines); assertTrue("G2B=INCOMPLETE" in r.lines)
            assertTrue(r.lines.any { it.startsWith("G2B_REASONS=") && "NESTED_CONTENT" in it })
        }
    }
    @Test fun momentsSchemaFailureDoesNotRenameReadableFormatAsCorrupt() = runBlocking {
        owned("moments", wire(mapOf("entities/moment_posts/p.json" to "{}".toByteArray()))) { p ->
            val r = check(p); assertEquals(0, r.exit); assertTrue("S0=FORMAT_READABLE" in r.lines)
            assertTrue("S1_REASON=MOMENT_SCHEMA_OR_GRAPH" in r.lines); assertTrue("G2B=INCOMPLETE" in r.lines)
        }
    }
    @Test fun cancellationReturnsFixedCodeAndWipesPassword() = runBlocking {
        val pw = generatedPassword()
        try { owned("cancel", wire(password = pw)) { p ->
            val entered = pw.copyOf()
            val r = RealArchiveReadOnlyTool.execute(arrayOf("--single-file-read-only", p.toString())) {
                // Simulate terminal cancellation before the password ownership handoff.
                entered.fill('\u0000'); throw CancellationException("synthetic-private-name")
            }
            assertEquals(3, r.exit); assertTrue(entered.all { it == '\u0000' }); assertEquals("R0=CANCELLED", r.lines.first())
        } } finally { pw.fill('\u0000') }
    }
    @Test fun readOnlyGuardDeniesConcurrentWriteAndReleasesHandle() = runBlocking {
        val pw = generatedPassword()
        try { owned("sharing", wire(password = pw)) { p ->
            var denied = false
            val r = RealArchiveReadOnlyTool.execute(arrayOf("--single-file-read-only", p.toString())) {
                try { Files.newOutputStream(p, StandardOpenOption.WRITE).use { } } catch (_: Exception) { denied = true }
                pw.copyOf()
            }
            assertEquals(0, r.exit); assertTrue(denied)
            Files.newOutputStream(p, StandardOpenOption.WRITE).use { } // Handle released, no bytes changed.
        } } finally { pw.fill('\u0000') }
    }
    @Test fun junctionAncestorIsRejectedBeforePasswordInput() = runBlocking {
        val base = Files.createDirectories(Path.of("build/readonly-r0/junctions")).toAbsolutePath().normalize()
        val target = Files.createTempDirectory(base, "target-")
        val source = Files.write(target.resolve("input.cbbackup"), wire())
        val link = base.resolve("link-" + java.util.UUID.randomUUID())
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        assertTrue(process.waitFor(15, TimeUnit.SECONDS)); process.inputStream.close()
        assertEquals(0, process.exitValue())
        try {
            var called = false
            val r = RealArchiveReadOnlyTool.execute(arrayOf("--single-file-read-only", link.resolve("input.cbbackup").toString())) { called = true; null }
            assertEquals(2, r.exit); assertFalse(called); assertTrue(Files.exists(source))
        } finally { Files.delete(link) } // Remove only this invocation's junction, never recurse into its target.
    }
}
