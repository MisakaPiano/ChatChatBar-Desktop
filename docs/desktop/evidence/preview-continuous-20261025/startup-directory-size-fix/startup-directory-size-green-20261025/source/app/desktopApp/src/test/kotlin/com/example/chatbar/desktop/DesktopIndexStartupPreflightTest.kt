package com.example.chatbar.desktop

import com.sun.jna.platform.win32.Kernel32
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

class DesktopIndexStartupPreflightTest {
    private class Fixture {
        val parent = Files.createTempDirectory("p9-g0-synthetic-")
        val root = Files.createDirectory(parent.resolve("root"))
        val domain = root.resolve("entities/document_index_transactions")
        var owner: DesktopDataRootOwnership? = null
        val openStreams = AtomicInteger()
        fun acquire() {
            owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
                DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("unused-bootstrap")))).ownership
        }
        fun directory(path: Path): DirectoryStream<Path> {
            val stream = Files.newDirectoryStream(path); openStreams.incrementAndGet()
            return object : DirectoryStream<Path> {
                override fun iterator() = stream.iterator()
                override fun close() { try { stream.close() } finally { openStreams.decrementAndGet() } }
            }
        }
        fun probe(budget: DesktopIndexPreflightBudget = DesktopIndexPreflightBudget(),
            step: suspend (DesktopIndexPreflightStep) -> Unit = {},
            attributes: (Path) -> BasicFileAttributes = ::attrs,
            directory: (Path) -> DirectoryStream<Path> = ::directory,
            identity: (Path, Boolean) -> Any? = DesktopIndexStartupPreflight::readWindowsIdentity) =
            DesktopIndexStartupPreflight(root, budget, step, attributes, directory, identity)
        fun snapshot(): Map<String, String> {
            val result = sortedMapOf<String, String>()
            fun visit(path: Path) {
                val a = attrs(path); val rel = parent.relativize(path).toString()
                val reparse = Kernel32.INSTANCE.GetFileAttributes(path.toString()) and 0x400 != 0
                val value = if (a.isSymbolicLink || reparse) "LINK:${a.fileKey()}" else if (a.isDirectory) "DIR" else
                    if (path.fileName.toString() == DesktopDataRootOwnership.LOCK_FILE_NAME) "OWNERSHIP:${a.size()}" else digest(Files.readAllBytes(path))
                result[rel] = "$value:${a.creationTime()}:${a.lastModifiedTime()}"
                if (a.isDirectory && !a.isSymbolicLink && !reparse) Files.newDirectoryStream(path).use { it.forEach(::visit) }
            }
            visit(parent); return result
        }
        fun close() {
            owner?.close()
            val absolute = parent.toAbsolutePath().normalize()
            check(absolute.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize() &&
                absolute.fileName.toString().startsWith("p9-g0-synthetic-"))
            fun remove(path: Path) {
                val a = attrs(path)
                val reparse = Kernel32.INSTANCE.GetFileAttributes(path.toString()) and 0x400 != 0
                if (a.isDirectory && !a.isSymbolicLink && !reparse) Files.newDirectoryStream(path).use { it.forEach(::remove) }
                Files.delete(path)
            }
            remove(absolute)
        }
    }
    private suspend fun fixture(ownership: Boolean = true, block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { if (ownership) f.acquire(); withTimeout(30_000) { block(f) } } finally { f.close() }
    }
    private suspend fun unchanged(f: Fixture, case: String, expected: DesktopIndexPreflightCode,
        probe: DesktopIndexStartupPreflight = f.probe()) {
        val before = f.snapshot()
        val result = probe.inspect()
        assertEquals(expected, result.code); assertEquals(before, f.snapshot()); assertEquals(0, f.openStreams.get())
        assertFalse(result.externalFileLinksProvenAbsent)
        assertFalse(result.toString().contains(f.root.toString()))
        record(case, result.code.name, before, f.snapshot())
    }

    @Test fun `multiple legacy layouts remain untouched and unrelated corruption is not certified`() = runBlocking {
        for (layout in listOf("empty", "legacy", "damaged-vectors")) fixture { f ->
            when (layout) {
                "legacy" -> { Files.createDirectories(f.root.resolve("documents")); Files.writeString(f.root.resolve("documents/old.txt"), "Synthetic legacy document") }
                "damaged-vectors" -> { Files.createDirectories(f.root.resolve("entities/vector_chunks/.backup-synthetic")); Files.writeString(f.root.resolve("entities/vector_chunks/bad.json"), "{broken") }
            }
            unchanged(f, "legacy-$layout", DesktopIndexPreflightCode.NO_DOMAIN)
        }
    }
    @Test fun `missing entities is not created and repeated checks have no cached readiness`() = runBlocking { fixture { f ->
        val probe = f.probe()
        unchanged(f, "no-entities-first", DesktopIndexPreflightCode.NO_DOMAIN, probe)
        unchanged(f, "no-entities-repeat", DesktopIndexPreflightCode.NO_DOMAIN, probe)
        assertFalse(Files.exists(f.root.resolve("entities")))
        Files.createDirectories(f.domain)
        unchanged(f, "domain-added-after-first-result", DesktopIndexPreflightCode.DOMAIN_PRESENT_UNSUPPORTED, probe)
    } }
    @Test fun `directory allocation size changes do not imply root content mutation`() = runBlocking {
        for (domainPresent in listOf(false, true)) fixture { f ->
            Files.createDirectories(if (domainPresent) f.domain else f.root.resolve("entities"))
            val reads = hashMapOf<Path, Int>()
            val probe = f.probe(attributes = { path ->
                val a = attrs(path)
                if (!a.isDirectory) a else object : BasicFileAttributes by a {
                    // The same unchanged Windows directory was observed as 0 then 4096.
                    private val observedSize = if (reads.merge(path, 1, Int::plus) == 1) 0L else 4096L
                    override fun size() = observedSize
                }
            })
            unchanged(f, "directory-allocation-drift-$domainPresent", if (domainPresent)
                DesktopIndexPreflightCode.DOMAIN_PRESENT_UNSUPPORTED else DesktopIndexPreflightCode.NO_DOMAIN, probe)
        }
    }
    @Test fun `regular file length changes still refuse startup metadata inspection`() = runBlocking { fixture { f ->
        Files.createDirectories(f.domain)
        val file = Files.writeString(f.domain.resolve("synthetic.json"), "x")
        var reads = 0
        val probe = f.probe(attributes = { path ->
            val a = attrs(path)
            if (path != file) a else object : BasicFileAttributes by a {
                private val observedSize = if (++reads == 1) 1L else 2L
                override fun size() = observedSize
            }
        })
        unchanged(f, "regular-file-length-drift", DesktopIndexPreflightCode.CHANGED_DURING_CHECK, probe)
    } }
    @Test fun `empty domain is unsupported and never ready`() = runBlocking { fixture { f ->
        Files.createDirectories(f.domain)
        unchanged(f, "empty-domain", DesktopIndexPreflightCode.DOMAIN_PRESENT_UNSUPPORTED)
    } }
    @Test fun `protocol looking names and invalid payload bodies are never interpreted`() = runBlocking {
        for (name in listOf("active", "pending", "completed", "heads", "history")) fixture { f ->
            val dir = Files.createDirectories(f.domain.resolve(name))
            Files.writeString(dir.resolve("intent.json"), "{invalid synthetic private payload")
            Files.writeString(dir.resolve("commit.marker"), "COMMITTED")
            unchanged(f, "unsupported-$name", DesktopIndexPreflightCode.DOMAIN_PRESENT_UNSUPPORTED)
        }
    }
    @Test fun `missing and released ownership never reuse a previous result`() = runBlocking { fixture(false) { f ->
        val probe = f.probe()
        unchanged(f, "ownership-absent", DesktopIndexPreflightCode.OWNERSHIP_MISSING, probe)
        f.acquire(); unchanged(f, "ownership-present", DesktopIndexPreflightCode.NO_DOMAIN, probe)
        f.owner!!.close(); unchanged(f, "ownership-released", DesktopIndexPreflightCode.OWNERSHIP_MISSING, probe)
    } }
    @Test fun `entities or candidate domain ordinary file is unsafe`() = runBlocking {
        for (candidate in listOf(false, true)) fixture { f ->
            if (candidate) Files.createDirectory(f.root.resolve("entities"))
            Files.writeString(if (candidate) f.domain else f.root.resolve("entities"), "Synthetic")
            unchanged(f, "file-instead-of-directory-$candidate", DesktopIndexPreflightCode.UNSAFE_PATH)
        }
    }
    @Test fun `root type substitution is refused despite the old lock remaining held`() = runBlocking { fixture { f ->
        unchanged(f, "simulated-root-file-replacement", DesktopIndexPreflightCode.UNSAFE_PATH, f.probe(attributes = { path ->
            val a = attrs(path)
            if (path != f.root) a else object : BasicFileAttributes by a {
                override fun isDirectory() = false
                override fun isRegularFile() = true
            }
        }))
    } }
    @Test fun `directory identity change during final check is detected without undoing actor changes`() = runBlocking { fixture { f ->
        var expected: Map<String, String>? = null; var replaced = false
        val probe = f.probe(step = { if (it == DesktopIndexPreflightStep.BEFORE_RECHECK) {
            replaced = true; expected = f.snapshot()
        } }, identity = { path, directory ->
            val key = DesktopIndexStartupPreflight.readWindowsIdentity(path, directory)
            if (path == f.root && replaced) Pair("REPLACED_IDENTITY", key) else key
        })
        assertEquals(DesktopIndexPreflightCode.CHANGED_DURING_CHECK, probe.inspect().code)
        assertEquals(expected, f.snapshot()); assertEquals(0, f.openStreams.get())
        record("root-identity-race", "CHANGED_DURING_CHECK", expected!!, f.snapshot())
    } }
    @Test fun `appearance and deletion races cannot turn into a missing domain result`() = runBlocking {
        for (appear in listOf(true, false)) fixture { f ->
            if (!appear) Files.createDirectories(f.domain)
            var expected: Map<String, String>? = null
            val probe = f.probe(step = { if (it == DesktopIndexPreflightStep.BEFORE_RECHECK) {
                if (appear) Files.createDirectories(f.domain) else Files.delete(f.domain)
                expected = f.snapshot()
            } })
            assertEquals(DesktopIndexPreflightCode.CHANGED_DURING_CHECK, probe.inspect().code)
            assertEquals(expected, f.snapshot()); assertEquals(0, f.openStreams.get())
            record("target-race-$appear", "CHANGED_DURING_CHECK", expected!!, f.snapshot())
        }
    }
    @Test fun `permission and enumeration errors expose no sensitive exception text`() = runBlocking {
        for (enumeration in listOf(false, true)) fixture { f ->
            val probe = if (enumeration) f.probe(directory = { throw AccessDeniedException("SYNTHETIC_SECRET_PATH") }) else
                f.probe(attributes = { throw AccessDeniedException("SYNTHETIC_SECRET_PATH") })
            unchanged(f, "injected-access-denied-$enumeration", DesktopIndexPreflightCode.READ_ERROR, probe)
            assertFalse(probe.inspect().toString().contains("SYNTHETIC_SECRET_PATH"))
        }
    }
    @Test fun `missing file identity is unsafe rather than assumed valid`() = runBlocking { fixture { f ->
        unchanged(f, "file-identity-unavailable", DesktopIndexPreflightCode.UNSAFE_PATH, f.probe(identity = { _, _ -> null }))
    } }
    @Test fun `ownership loss during inspection is rechecked before returning`() = runBlocking { fixture { f ->
        unchanged(f, "ownership-loss-at-recheck", DesktopIndexPreflightCode.OWNERSHIP_MISSING,
            f.probe(step = { if (it == DesktopIndexPreflightStep.BEFORE_RECHECK) f.owner!!.close() }))
    } }
    @Test fun `entry depth name path and file size budgets fail closed with no writes`() = runBlocking {
        for (kind in listOf("entries", "depth", "names", "paths", "bytes")) fixture { f ->
            Files.createDirectories(f.domain.resolve("active/pending")); Files.writeString(f.domain.resolve("large.json"), "x")
            val b = when (kind) {
                "entries" -> DesktopIndexPreflightBudget(entries = 2)
                "depth" -> DesktopIndexPreflightBudget(depth = 1)
                "names" -> DesktopIndexPreflightBudget(totalNameChars = 1)
                "paths" -> DesktopIndexPreflightBudget(totalPathChars = 1)
                else -> DesktopIndexPreflightBudget(fileBytes = 0)
            }
            unchanged(f, "budget-$kind", DesktopIndexPreflightCode.BUDGET_EXCEEDED, f.probe(b))
        }
    }
    @Test fun `huge metadata sizes are rejected without allocation or content reads`() = runBlocking { fixture { f ->
        Files.createDirectories(f.domain); val file = Files.writeString(f.domain.resolve("size.json"), "x")
        unchanged(f, "metadata-size-long-max", DesktopIndexPreflightCode.BUDGET_EXCEEDED, f.probe(attributes = { path ->
            val a = attrs(path)
            if (path != file) a else object : BasicFileAttributes by a { override fun size() = Long.MAX_VALUE }
        }))
        assertFailsWith<IllegalArgumentException> { DesktopIndexPreflightBudget(entries = Long.MAX_VALUE) }
    } }
    @Test fun `windows aliases and case collisions are rejected by metadata inventory`() = runBlocking { fixture { f ->
        Files.createDirectories(f.domain)
        val fake = f.root.resolve("Entities")
        unchanged(f, "simulated-case-collision", DesktopIndexPreflightCode.UNSAFE_PATH, f.probe(directory = { path ->
            if (path != f.root) f.directory(path) else object : DirectoryStream<Path> {
                override fun iterator() = mutableListOf(f.root.resolve("entities"), fake).iterator()
                override fun close() = Unit
            }
        }))
        for (name in listOf("CON", "aux.txt", "LPT1", "name.", "name ", "A~1", "x:stream", "..", "bad?name"))
            assertFalse(DesktopIndexStartupPreflight.safeWindowsName(name), name)
        assertTrue(DesktopIndexStartupPreflight.safeWindowsName("合成-world.json"))
    } }
    @Test fun `cancellation closes an open directory and leaves no detached work`() = runBlocking { fixture { f -> coroutineScope {
        val before = f.snapshot(); val opened = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        val probe = f.probe(step = { if (it == DesktopIndexPreflightStep.DIRECTORY_OPENED) { opened.complete(Unit); hold.await() } })
        val job = async { probe.inspect() }; opened.await(); assertEquals(1, f.openStreams.get())
        job.cancelAndJoin(); assertTrue(job.isCancelled); assertEquals(0, f.openStreams.get())
        assertEquals(before, f.snapshot()); assertTrue(DesktopDataRootOwnership.isHeld(f.root))
        unchanged(f, "after-cancellation", DesktopIndexPreflightCode.NO_DOMAIN)
        record("cancelled-open-directory", "CANCELLED", before, f.snapshot())
    } } }
    @Test fun `hardlink duplicates inside domain are unsafe and outside links never certified`() = runBlocking {
        for (inside in listOf(true, false)) fixture { f ->
            Files.createDirectories(f.domain)
            val target = Files.writeString(if (inside) f.domain.resolve("one.json") else f.parent.resolve("outside.json"), "Synthetic")
            Files.createLink(f.domain.resolve("two.json"), target)
            unchanged(f, "hardlink-$inside", if (inside) DesktopIndexPreflightCode.UNSAFE_PATH else DesktopIndexPreflightCode.DOMAIN_PRESENT_UNSUPPORTED)
        }
    }
    @Test fun `real Windows junctions in entities and domain are refused without following targets`() = runBlocking {
      for (location in listOf("entities", "domain")) fixture { f ->
        val link = if (location == "entities") f.root.resolve("entities") else {
            Files.createDirectory(f.root.resolve("entities")); f.domain
        }
        val target = Files.createDirectory(f.parent.resolve("outside"))
        Files.writeString(target.resolve("do-not-read.txt"), "Synthetic outside content")
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS)); val available = process.exitValue() == 0
        if (!available) recordLimit("JUNCTION_FIXTURE_UNAVAILABLE")
        assumeTrue("JUNCTION_FIXTURE_UNAVAILABLE", available)
        unchanged(f, "real-junction-$location", DesktopIndexPreflightCode.UNSAFE_PATH)
      }
    }
    @Test fun `real symbolic links in entities and domain are refused when supported`() = runBlocking {
        // Active ownership prevents renaming this Windows root; do not weaken/release it
        // to pretend a root replacement was tested. Root identity/type races use explicit seams.
        recordLimit("REAL_ROOT_LINK_REPLACEMENT_NOT_TESTED_ACTIVE_OWNERSHIP_PREVENTS_RENAME")
        for (location in listOf("entities", "domain")) fixture { f ->
            val target = Files.createDirectory(f.parent.resolve("outside")); Files.writeString(target.resolve("secret.txt"), "Synthetic")
            val link = when (location) {
                "entities" -> f.root.resolve("entities")
                else -> { Files.createDirectory(f.root.resolve("entities")); f.domain }
            }
            val available = try { Files.createSymbolicLink(link, target); true } catch (_: FileSystemException) { false }
            if (!available) recordLimit("SYMLINK_FIXTURE_UNAVAILABLE")
            assumeTrue("SYMLINK_FIXTURE_UNAVAILABLE", available)
            unchanged(f, "real-symlink-$location", DesktopIndexPreflightCode.UNSAFE_PATH)
        }
    }

    companion object {
        private fun attrs(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun record(case: String, result: String, before: Map<String, String>, after: Map<String, String>) {
            val row = buildJsonObject { put("case", case); put("result", result); put("filesAndDirectoriesUnchanged", before == after)
                put("entries", before.size); put("snapshotHash", digest(before.toString().toByteArray())) }
            append(row)
        }
        private fun recordLimit(reason: String) = append(buildJsonObject { put("notTested", reason) })
        @Synchronized private fun append(row: JsonObject) {
            System.getenv("CCB_G0_EVIDENCE")?.let { path -> Files.writeString(Path.of(path).resolve("zero-write-events.jsonl"),
                row.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
        }
    }
}
