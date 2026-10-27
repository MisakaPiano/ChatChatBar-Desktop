package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStartupGateTest {
    private class Fixture : AutoCloseable {
        val parent = Files.createTempDirectory("g1b-synthetic-")
        val root = Files.createDirectory(parent.resolve("root"))
        val home = Files.createDirectory(parent.resolve("image"))
        val local = Files.createDirectory(parent.resolve("local"))
        val domain = root.resolve("entities/document_index_transactions")
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME))
        fun select() = DesktopStartupRootSelection.Selected(resolved)
        fun portable(): DesktopStartupRootSelection.PortablePendingActivation {
            Files.writeString(home.resolve("portable.flag"), DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN)
            Files.createDirectory(home.resolve("UserData"))
            return runBlocking { assertIs<DesktopStartupRootSelection.PortablePendingActivation>(
                DesktopDataDirectory.selectRoot(mapOf("LOCALAPPDATA" to local.toString()), parent,
                    applicationHomeResolution = DesktopApplicationHome.resolve(null, home))) }
        }
        override fun close() {
            // Fail instead of deleting a fixture which might still be used by a live owner.
            assertFalse(DesktopDataRootOwnership.isHeld(root))
            assertFalse(DesktopDataRootOwnership.isHeld(home.resolve("UserData")))
            assertTrue(parent.toFile().deleteRecursively())
        }
    }
    private inline fun fixture(body: (Fixture) -> Unit) = Fixture().use(body)
    private fun result(code: DesktopIndexPreflightCode) = DesktopIndexPreflightResult(code, 0, 0, 0)
    private fun snapshot(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.toList().associate { path -> root.relativize(path).toString() to
            if (Files.isDirectory(path)) "DIR"
            // Windows refuses another data read of the ownership-locked file. It is existing
            // infrastructure, not G0 payload: observe its fixed length without opening a stream.
            else if (path.fileName.toString() == DesktopDataRootOwnership.LOCK_FILE_NAME) "OWNERSHIP:${Files.size(path)}"
            else MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) } }
    }
    private fun blocked(f: Fixture, gate: DesktopStartupGate): DesktopStartupBlockedException {
        val failure = assertFailsWith<DesktopStartupBlockedException> { runDesktopMain(gate) { error("business forbidden") } }
        assertFalse(DesktopDataRootOwnership.isHeld(f.root))
        return failure
    }

    @Test fun `Main ingress inspects under owner before business and real container initialization`() = fixture { f ->
        val calls = mutableListOf<String>()
        val gate = DesktopStartupGate(presenter = {}, select = { calls += "select"; f.select() },
            acquire = { calls += "acquire"; DesktopDataRootOwnership.acquire(it) }, inspect = {
                assertTrue(DesktopDataRootOwnership.isHeld(it.appDataRoot))
                val before = snapshot(f.root)
                val checked = DesktopIndexStartupPreflight(it.appDataRoot).inspect()
                assertEquals(before, snapshot(f.root)) // G0 only; excludes subsequent business writes.
                calls += "G0"; checked
            }, closeOwnership = { calls += "release"; it.close() })
        runDesktopMain(gate) { root ->
            calls += "container"
            val container = DesktopAppContainer(root, secretStoreFactory = { InMemoryDesktopSecretStore() })
            runDesktopApplicationLifecycle({ calls += "initialize"; container.initializePersistentState() },
                { calls += "application" }, { calls += "drain"; container.close() })
        }
        assertEquals(listOf("select", "acquire", "G0", "container", "initialize", "application", "drain", "release"), calls)
        assertFalse(DesktopDataRootOwnership.isHeld(f.root))
    }

    @Test fun `Main refuses every candidate domain shape without interpreting payloads or constructing business`() {
        for (shape in listOf("empty", "active", "pending", "completed", "heads", "unknown")) fixture { f ->
            Files.createDirectories(f.domain)
            if (shape != "empty") Files.writeString(f.domain.resolve(shape), "not a journal; never parse")
            var presentations = 0
            val gate = DesktopStartupGate(select = { f.select() }, presenter = {
                presentations++; assertEquals("G0_DOMAIN_PRESENT_UNSUPPORTED", it.code)
                assertTrue(DesktopDataRootOwnership.isHeld(f.root))
                val view = DesktopStartupBlockedView(it)
                var closed = false; view.close { closed = true }; assertTrue(closed)
            })
            val before = snapshot(f.domain)
            blocked(f, gate)
            assertEquals(before, snapshot(f.domain)); assertEquals(1, presentations)
            assertEquals(setOf("entities", DesktopDataRootOwnership.LOCK_FILE_NAME), Files.list(f.root).use { p -> p.map { it.fileName.toString() }.toList().toSet() })
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(f.resolved)).ownership.close()
        }
    }

    @Test fun `every G0 refusal code is fixed and no business factory is reached`() {
        for (code in DesktopIndexPreflightCode.entries.filter { it != DesktopIndexPreflightCode.NO_DOMAIN }) fixture { f ->
            var presented: DesktopStartupDiagnostic? = null
            val failure = blocked(f, DesktopStartupGate(select = { f.select() }, inspect = { result(code) },
                presenter = { presented = it }))
            assertEquals("G0_${code.name}", failure.diagnostic.code)
            assertEquals(presented, failure.diagnostic)
            assertEquals(setOf(DesktopDataRootOwnership.LOCK_FILE_NAME), Files.list(f.root).use { it.map { p -> p.fileName.toString() }.toList().toSet() })
        }
    }

    @Test fun `Portable Main performs first G0 before owned probe and a fresh second G0 before business`() = fixture { f ->
        val pending = f.portable()
        val calls = mutableListOf<String>()
        var acquisitions = 0
        val validator = DesktopPortableRootActivationValidator(DesktopPortableRootResolver(), { "ordered" }, {
            calls += "probe"; assertTrue(DesktopDataRootOwnership.isHeld(pending.appDataRoot))
            Files.write(it, byteArrayOf(1), StandardOpenOption.CREATE_NEW)
        }, { calls += "cleanup"; Files.delete(it) })
        runDesktopMain(DesktopStartupGate(presenter = {}, select = { pending }, acquire = {
            acquisitions++; DesktopDataRootOwnership.acquire(it)
        }, inspect = {
            calls += "G0"; val before = snapshot(pending.appDataRoot)
            DesktopIndexStartupPreflight(it.appDataRoot).inspect().also { assertEquals(before, snapshot(pending.appDataRoot)) }
        }, activate = { DesktopDataDirectory.activatePortable(it, validator) })) {
            assertEquals(pending.appDataRoot, it.appDataRoot); calls += "business"
        }
        assertEquals(listOf("G0", "probe", "cleanup", "G0", "business"), calls)
        assertEquals(1, acquisitions)
    }

    @Test fun `Portable first refusal never activates or falls back`() = fixture { f ->
        val pending = f.portable()
        Files.createDirectories(pending.appDataRoot.resolve("entities/document_index_transactions"))
        var activated = false
        assertFailsWith<DesktopStartupBlockedException> { runDesktopMain(DesktopStartupGate(select = { pending },
            activate = { activated = true; error("forbidden") }, presenter = {})) { error("forbidden") } }
        assertFalse(activated)
        assertFalse(Files.exists(f.local.resolve(DesktopDataDirectory.DIRECTORY_NAME)))
        assertFalse(Files.list(pending.appDataRoot).use { it.anyMatch { p -> p.fileName.toString().startsWith(".portable-write-probe") } })
    }

    @Test fun `domain created before or during probe is refused by fresh second G0`() {
        for (duringProbe in listOf(false, true)) fixture { f ->
            val pending = f.portable()
            var checks = 0
            val domain = pending.appDataRoot.resolve("entities/document_index_transactions")
            val validator = DesktopPortableRootActivationValidator(DesktopPortableRootResolver(), { "domain" }, {
                Files.write(it, byteArrayOf(2), StandardOpenOption.CREATE_NEW)
                if (duringProbe) Files.createDirectories(domain)
            }, Files::delete)
            val error = assertFailsWith<DesktopStartupBlockedException> { runDesktopMain(DesktopStartupGate(
                select = { pending }, inspect = { checks++; DesktopIndexStartupPreflight(it.appDataRoot).inspect() },
                activate = { if (!duringProbe) Files.createDirectories(domain); DesktopDataDirectory.activatePortable(it, validator) },
                presenter = {})) { error("forbidden") } }
            assertEquals("G0_DOMAIN_PRESENT_UNSUPPORTED", error.diagnostic.code); assertEquals(2, checks)
            assertTrue(Files.isDirectory(domain))
        }
    }

    @Test fun `Portable identity drift during probe blocks rather than falling back`() = fixture { f ->
        val pending = f.portable()
        val validator = DesktopPortableRootActivationValidator(DesktopPortableRootResolver(), { "drift" }, {
            Files.write(it, byteArrayOf(2), StandardOpenOption.CREATE_NEW)
            Files.move(f.home.resolve("portable.flag"), f.home.resolve("original-marker"))
            Files.writeString(f.home.resolve("portable.flag"), DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN)
        }, Files::delete)
        val error = assertFailsWith<DesktopStartupBlockedException> { runDesktopMain(DesktopStartupGate(select = { pending },
            activate = { DesktopDataDirectory.activatePortable(it, validator) }, presenter = {})) { error("forbidden") } }
        assertEquals("PORTABLE_CANDIDATE_CHANGED", error.diagnostic.code)
        assertTrue(Files.exists(f.home.resolve("original-marker")))
    }

    @Test fun `probe and cleanup failures preserve actual retained evidence and show warning`() {
        for (cleanup in listOf(false, true)) fixture { f ->
            val pending = f.portable()
            val validator = DesktopPortableRootActivationValidator(DesktopPortableRootResolver(), { "retained" }, {
                Files.write(it, byteArrayOf(42), StandardOpenOption.CREATE_NEW)
                if (!cleanup) throw IOException("synthetic private path must not escape")
            }, { throw IOException("synthetic cleanup") })
            val error = assertFailsWith<DesktopStartupBlockedException> { runDesktopMain(DesktopStartupGate(select = { pending },
                activate = { DesktopDataDirectory.activatePortable(it, validator) }, presenter = {
                    assertEquals(cleanup, it.retainedProbe)
                    assertEquals(cleanup, DesktopStartupBlockedView(it).message.contains("可能保留"))
                })) { error("forbidden") } }
            assertEquals(if (cleanup) "PORTABLE_PROBE_CLEANUP_FAILED" else "PORTABLE_WRITE_PROBE_FAILED", error.diagnostic.code)
            val retained = pending.appDataRoot.resolve(".portable-write-probe-retained.tmp")
            assertEquals(cleanup, Files.exists(retained))
            if (cleanup) assertContentEquals(byteArrayOf(42), Files.readAllBytes(retained))
        }
    }

    @Test fun `default empty root creates only ownership infrastructure before G0`() = fixture { f ->
        val defaultRoot = f.local.resolve(DesktopDataDirectory.DIRECTORY_NAME)
        runDesktopMain(DesktopStartupGate(presenter = {}, select = { DesktopDataDirectory.selectRoot(
            mapOf("LOCALAPPDATA" to f.local.toString()), f.parent,
            applicationHomeResolution = DesktopApplicationHome.resolve(null, f.home)) }, inspect = {
            assertEquals(defaultRoot, it.appDataRoot)
            assertEquals(setOf(DesktopDataRootOwnership.LOCK_FILE_NAME), Files.list(defaultRoot).use { s -> s.map { p -> p.fileName.toString() }.toList().toSet() })
            val before = snapshot(defaultRoot)
            DesktopIndexStartupPreflight(defaultRoot).inspect().also { assertEquals(before, snapshot(defaultRoot)) }
        })) { assertEquals(defaultRoot, it.appDataRoot) }
        assertFalse(DesktopDataRootOwnership.isHeld(defaultRoot))
    }

    @Test fun `second Main instance keeps existing AlreadyInUse path and never inspects or constructs business`() = fixture { f ->
        assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(f.resolved)).ownership.use {
            var notices = 0
            runDesktopMain(DesktopStartupGate(presenter = {}, select = { f.select() }, inspect = { error("forbidden") },
                alreadyInUse = DesktopAlreadyInUsePresenter { notices++ })) { error("forbidden") }
            assertEquals(1, notices); assertTrue(DesktopDataRootOwnership.isHeld(f.root))
        }
    }

    @Test fun `malformed root authority blocks without ownership or fallback`() = fixture { f ->
        Files.writeString(f.local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME), "{invalid")
        val before = snapshot(f.parent)
        val failure = blocked(f, DesktopStartupGate(select = { DesktopDataDirectory.selectRoot(
            mapOf("LOCALAPPDATA" to f.local.toString()), f.parent,
            applicationHomeResolution = DesktopApplicationHome.resolve(null, f.home)) },
            acquire = { error("forbidden") }, presenter = {}))
        assertEquals("ROOT_BOOTSTRAP_LOAD_FAILED", failure.diagnostic.code)
        assertEquals(before, snapshot(f.parent))
    }

    @Test fun `CLI bypasses broken lower authorities while malformed CLI never falls back`() = fixture { f ->
        for (valid in listOf(true, false)) {
            var businessCalls = 0
            val gate = DesktopStartupGate(select = { DesktopDataDirectory.selectRoot(
                mapOf("LOCALAPPDATA" to f.local.toString()), f.parent, if (valid) f.root else Path.of("relative"),
                applicationHomeResolution = DesktopApplicationHomeResult.Failure("private synthetic text")) }, presenter = {})
            if (valid) runDesktopMain(gate) { businessCalls++; assertEquals(f.root, it.appDataRoot) }
            else assertEquals("ROOT_INVALID_CLI_OVERRIDE", blocked(f, gate).diagnostic.code)
            assertEquals(if (valid) 1 else 0, businessCalls)
        }
    }

    @Test fun `ownership failure is safe startup refusal without constructing business`() = fixture { f ->
        val absent = f.resolved.copy(appDataRoot = f.root.resolve("missing"))
        val failure = blocked(f, DesktopStartupGate(select = { DesktopStartupRootSelection.Selected(absent) }, presenter = {}))
        assertEquals("OWNERSHIP_ROOT_MISSING", failure.diagnostic.code)
        assertFalse(Files.exists(absent.appDataRoot))
    }

    @Test fun `presenter failure remains sanitized refusal and exits nonzero without unsafe window`() = fixture { f ->
        val reports = mutableListOf<String>()
        val exits = mutableListOf<Int>()
        runDesktopProcessLifecycle({ runDesktopMain(DesktopStartupGate(select = { f.select() },
            inspect = { result(DesktopIndexPreflightCode.READ_ERROR) },
            presenter = { throw IllegalStateException("SECRET / private / raw payload") }, report = reports::add)) { error("forbidden") } },
            terminate = exits::add, reportFailure = { error("not unsafe shutdown") })
        assertEquals(listOf(2), exits)
        assertEquals(listOf("CCB STARTUP_BLOCKED G0_READ_ERROR PRESENTATION_FAILED"), reports)
    }

    @Test fun `blocked view copies only stable diagnostic and closes normally`() {
        for (retained in listOf(false, true)) {
            val view = DesktopStartupBlockedView(DesktopStartupDiagnostic.activation(DesktopPortableActivationFailureKind.PROBE_CLEANUP_FAILED, retained))
            var copied = ""; var closes = 0
            view.copyDiagnostic { copied = it }; view.close { closes++ }
            assertEquals("CCB STARTUP_BLOCKED PORTABLE_PROBE_CLEANUP_FAILED", copied)
            assertEquals(1, closes)
            assertTrue(view.message.contains("数据没有被自动清空或恢复"))
            assertTrue(view.closeLabel.contains("Close")); assertTrue(view.copyLabel.contains("Copy"))
        }
    }

    @Test fun `unexpected selection and G0 exceptions are refused without exposing raw exception`() = fixture { f ->
        for (inSelection in listOf(false, true)) {
            val failure = blocked(f, DesktopStartupGate(select = {
                if (inSelection) throw IOException("synthetic sensitive text") else f.select()
            }, inspect = { throw IOException("synthetic sensitive text") }, presenter = {
                assertFalse(DesktopStartupBlockedView(it).message.contains("sensitive"))
            }))
            assertEquals("G0_READ_ERROR", failure.diagnostic.code)
        }
    }

    @Test fun `G0 cancellation waits for structured IO cleanup before safe refusal and owner release`() = fixture { f -> runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val childStarted = CompletableDeferred<Unit>()
        val draining = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lifecycle = scope.async {
            assertFailsWith<DesktopStartupBlockedException> { runDesktopMain(DesktopStartupGate(select = { f.select() }, inspect = {
                coroutineScope {
                    launch {
                        childStarted.complete(Unit)
                        try { awaitCancellation() } finally { withContext(NonCancellable) { draining.complete(Unit); release.await() } }
                    }
                    childStarted.await(); throw CancellationException("synthetic cancellation")
                }
            }, presenter = { assertEquals("STARTUP_CANCELLED", it.code) })) { error("forbidden") } }
        }
        try {
            withTimeout(10_000) { draining.await() }
            assertTrue(DesktopDataRootOwnership.isHeld(f.root)); assertFalse(lifecycle.isCompleted)
            release.complete(Unit)
            withTimeout(10_000) { lifecycle.await() }
            assertFalse(DesktopDataRootOwnership.isHeld(f.root))
        } finally {
            release.complete(Unit)
            withTimeout(10_000) { lifecycle.join() }
            scope.cancel()
        }
    } }

    @Test fun `business initialization failure still drains then takes original unsafe reporting path`() = fixture { f ->
        val calls = mutableListOf<String>()
        runDesktopProcessLifecycle({ runDesktopMain(DesktopStartupGate(presenter = {}, select = { f.select() })) {
            runDesktopApplicationLifecycle({ calls += "initialize"; throw IOException("synthetic") },
                { error("forbidden") }, { calls += "drain" })
        } }, terminate = { error("no successful exit") }, reportFailure = { calls += "failure" })
        assertEquals(listOf("initialize", "drain", "failure"), calls)
        assertFalse(DesktopDataRootOwnership.isHeld(f.root))
    }

    @Test fun `ownership cleanup failure cannot be misreported as safe startup refusal`() = fixture { f ->
        var unsafe = 0
        runDesktopProcessLifecycle({ runDesktopMain(DesktopStartupGate(select = { f.select() },
            inspect = { result(DesktopIndexPreflightCode.READ_ERROR) }, presenter = {},
            closeOwnership = { it.close(); throw IOException("synthetic close failure") })) { error("forbidden") } },
            terminate = { error("must not claim safe refusal") }, reportFailure = { unsafe++ })
        assertEquals(1, unsafe)
    }

    @Test fun `restart after refusal performs a new G0 instead of caching admission`() = fixture { f ->
        var checks = 0; var businessCalls = 0
        val gate = DesktopStartupGate(select = { f.select() }, inspect = {
            checks++; DesktopIndexStartupPreflight(it.appDataRoot).inspect()
        }, presenter = {})
        runDesktopMain(gate) { businessCalls++ }
        Files.createDirectories(f.domain)
        blocked(f, gate)
        Files.delete(f.domain)
        runDesktopMain(gate) { businessCalls++ }
        assertEquals(3, checks); assertEquals(2, businessCalls)
    }
}
