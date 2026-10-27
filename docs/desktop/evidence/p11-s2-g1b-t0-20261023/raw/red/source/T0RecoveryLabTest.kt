package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

class T0RecoveryLabTest {
    private fun fixture(label: String, topology: T0RecoveryLab.Topology): T0RecoveryLab {
        val parent = Files.createDirectories(Path.of("build/g1b-t0")).toAbsolutePath().normalize()
        return T0RecoveryLab(Files.createTempDirectory(parent, "$label-${topology.name}-")).also { it.seed(topology) }
    }
    private fun both(block: (T0RecoveryLab) -> Unit) = T0RecoveryLab.Topology.entries.forEach { block(fixture("case", it)) }
    private fun blocked(lab: T0RecoveryLab) {
        assertEquals(T0RecoveryLab.Admission.BLOCKED, lab.consume())
        assertFalse(Files.exists(lab.base.resolve("business-A")))
        assertEquals("SYNTHETIC_SOURCE_NOT_A_CBBackup", Files.readString(lab.base.resolve("source.synthetic")))
    }
    private fun old(lab: T0RecoveryLab) {
        assertEquals(T0RecoveryLab.Admission.ALL_OLD, lab.gate())
        T0RecoveryLab.KEYS.forEachIndexed { i, key -> assertTrue(lab.store("A").load(key) == "FAKE_T0_OLD_$i") }
        assertTrue(Files.readString(lab.root("A").resolve("data")).endsWith("GEN_1"))
    }
    private fun snapshot(path: Path) = Files.walk(path).use { paths -> paths.filter { Files.isRegularFile(it) }.toList()
        .associate { path.relativize(it).toString() to T0RecoveryLab.hash(it) } }
    private fun child(lab: T0RecoveryLab, mode: String, expected: Int = 0, order: String = "KEY_FIRST", cut: String = "NONE"): String {
        val cp = listOf(T0SyntheticChild::class.java, DesktopDataRootOwnership::class.java,
            com.example.chatbar.data.operation.AppDataOperationGate::class.java, Unit::class.java,
            kotlinx.coroutines.Job::class.java, Json::class.java, kotlinx.serialization.KSerializer::class.java,
            com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct().joinToString(File.pathSeparator)
        val log = lab.base.resolve("child-$mode-${System.nanoTime()}.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Xmx96m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-cp", cp,
            T0SyntheticChild::class.java.name, lab.base.toString(), mode, order, cut)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "T0_OWNED_CHILD_TIMEOUT")
            assertEquals(expected, process.exitValue(), "T0_CHILD_EXIT_MISMATCH")
            Files.writeString(log.resolveSibling(log.fileName.toString() + ".exit"), process.exitValue().toString())
            println("T0_CHILD mode=$mode expected=$expected actual=${process.exitValue()} alive=${process.isAlive}")
            return Files.readString(log)
        } finally { if (process.isAlive) process.destroyForcibly() } // Only this invocation's known child.
    }

    @Test fun `real resolver shares logical keys while test namespaces separate equal model IDs`() {
        val a = fixture("resolver", T0RecoveryLab.Topology.SHARED)
        assertEquals(a.secretRoot("A"), a.secretRoot("B"))
        a.store("A").save(T0RecoveryLab.KEYS[0], "FAKE_T0_OTHER_ACCOUNT")
        assertTrue(a.store("B").load(T0RecoveryLab.KEYS[0]) == "FAKE_T0_OTHER_ACCOUNT")
        val b = fixture("resolver", T0RecoveryLab.Topology.PER_ROOT)
        assertNotEquals(b.secretRoot("A"), b.secretRoot("B"))
        b.store("A").save(T0RecoveryLab.KEYS[0], "FAKE_T0_OTHER_ACCOUNT")
        assertTrue(b.store("B").load(T0RecoveryLab.KEYS[0]) == "FAKE_T0_OLD_0")
    }
    @Test fun `hard KEY FIRST and DATA FIRST exits block business and fresh processes recover OLD`() = both { lab ->
        // Each ordering has a separate retained transaction, not a reset of the same evidence.
        for (order in listOf("KEY_FIRST", "DATA_FIRST")) {
            val f = fixture("hard-first", lab.topology)
            child(f, "apply", 71, order, "FIRST_WRITE")
            blocked(f)
            assertTrue(child(f, "recover").contains("T0_RECOVERY=ALL_OLD"))
            old(f); assertTrue(Files.exists(f.home().resolve("old/0")))
            val proof = snapshot(f.home())
            child(f, "recover"); assertEquals(proof, snapshot(f.home()))
            assertEquals(T0RecoveryLab.Admission.ALL_OLD, f.consume())
        }
    }
    @Test fun `all NEW bytes without completion receipt cannot activate and roll back after restart`() = both { lab ->
        child(lab, "apply", 71, cut = "ALL_WRITES")
        blocked(lab); assertTrue(Files.readString(lab.root("A").resolve("data")).endsWith("GEN_2"))
        child(lab, "recover"); old(lab)
    }
    @Test fun `second protected key failure retains mixed evidence and is recoverable`() = both { lab ->
        lab.prepare(); lab.publish()
        assertFailsWith<java.io.IOException> { lab.apply("KEY_FIRST", "SECOND_KEY_FAIL") }
        assertTrue(lab.store("A").load(T0RecoveryLab.KEYS[0]) == "FAKE_T0_NEW_0")
        assertTrue(lab.store("A").load(T0RecoveryLab.KEYS[1]) == "FAKE_T0_OLD_1")
        blocked(lab); child(lab, "recover"); old(lab)
    }
    @Test fun `before intent hard exit has no live writes and after intent exit blocks before fresh recovery`() = both { lab ->
        child(lab, "before-intent", 71); old(lab)
        assertFalse(Files.exists(lab.home().resolve("reservation")))
        val after = fixture("published", lab.topology)
        child(after, "after-intent", 71); blocked(after)
        child(after, "recover"); old(after)
    }
    @Test fun `successful NEW receipt is revalidated on every repeated fresh startup`() = both { lab ->
        child(lab, "apply")
        assertEquals(T0RecoveryLab.Admission.ALL_NEW, lab.consume())
        val proof = snapshot(lab.home()); child(lab, "recover"); child(lab, "recover")
        assertEquals(T0RecoveryLab.Admission.ALL_NEW, lab.gate()); assertEquals(proof, snapshot(lab.home()))
    }
    @Test fun `interrupted recovery is idempotent across another hard exit`() = both { lab ->
        child(lab, "apply", 71, cut = "ALL_WRITES")
        child(lab, "recover-crash", 72); blocked(lab)
        child(lab, "recover"); old(lab)
        val proof = snapshot(lab.home()); child(lab, "recover"); assertEquals(proof, snapshot(lab.home()))
    }
    @Test fun `decision published without receipt fails closed even when all live bytes are NEW`() = both { lab ->
        child(lab, "apply", 71, cut = "DECISION_ONLY")
        blocked(lab); assertFails { lab.recover() }; blocked(lab)
    }
    @Test fun completedReceiptWithoutDecisionMustRemainBlocked() = both { lab ->
        lab.prepare(); lab.publish(); lab.apply("DATA_FIRST")
        Files.copy(lab.home().resolve("receipt"), lab.home().resolve("receipt.original"))
        Files.move(lab.home().resolve("decision"), lab.home().resolve("decision.unavailable"))
        blocked(lab)
        assertFails { lab.recover() }
        assertTrue(Files.readString(lab.root("A").resolve("data")).endsWith("GEN_2"))
    }
    @Test fun `lost corrupt conflicting receipts retain bytes and cannot activate`() {
        for (topology in T0RecoveryLab.Topology.entries) for (fault in listOf("lost", "corrupt", "conflict")) {
            val lab = fixture("receipt-$fault", topology)
            lab.prepare(); lab.publish(); lab.apply("DATA_FIRST")
            val receipt = lab.home().resolve("receipt")
            Files.copy(receipt, lab.home().resolve("receipt.original"))
            when (fault) {
                "lost" -> Files.move(receipt, lab.home().resolve("receipt.unavailable"))
                "corrupt" -> Files.writeString(receipt, "DAMAGED")
                else -> Files.writeString(lab.home().resolve("decision"), "ALL_OLD")
            }
            val bytes = lab.targets("A").map { T0RecoveryLab.hash(it) }
            blocked(lab); assertFails { lab.recover() }
            assertEquals(bytes, lab.targets("A").map { T0RecoveryLab.hash(it) })
        }
    }
    @Test fun `lost intent and changed rollback evidence are unknown rather than empty`() {
        for (topology in T0RecoveryLab.Topology.entries) for (fault in listOf("intent", "old")) {
            val lab = fixture("evidence-$fault", topology)
            lab.prepare(); lab.publish()
            if (fault == "intent") Files.move(lab.home().resolve("intent"), lab.home().resolve("intent.unavailable"))
            else Files.writeString(lab.home().resolve("old/0"), "FOREIGN")
            blocked(lab); assertFails { lab.recover() }
        }
    }
    @Test fun `external data generation or protected key change cannot be overwritten by recovery`() {
        for (topology in T0RecoveryLab.Topology.entries) for (fault in listOf("data", "key")) {
            val lab = fixture("external-$fault", topology)
            lab.prepare(); lab.publish()
            if (fault == "data") Files.writeString(lab.root("A").resolve("data"), "FOREIGN_GEN_99")
            else lab.store("A").save(T0RecoveryLab.KEYS[0], "FAKE_T0_FOREIGN_ACCOUNT")
            val before = lab.targets("A").map { T0RecoveryLab.hash(it) }
            blocked(lab); assertFails { lab.recover() }
            assertEquals(before, lab.targets("A").map { T0RecoveryLab.hash(it) })
        }
    }
    @Test fun `offline registered B blocks shared changes but disjoint namespace A retains unavailable B`() {
        for (topology in T0RecoveryLab.Topology.entries) {
            val lab = fixture("offline", topology)
            val before = snapshot(lab.root("B")); val blobs = snapshot(lab.secretRoot("B"))
            Files.move(lab.root("B"), lab.base.resolve("offline-B")) // Synthetic unavailable path, not physical disk/ACL evidence.
            if (topology == T0RecoveryLab.Topology.SHARED) {
                assertFails { lab.prepare() }; blocked(lab)
            } else {
                lab.prepare(); lab.publish(); lab.apply("DATA_FIRST")
                assertEquals(T0RecoveryLab.Admission.ALL_NEW, lab.consume())
                assertEquals(blobs, snapshot(lab.secretRoot("B")))
            }
            assertEquals(before, snapshot(lab.base.resolve("offline-B")))
        }
    }
    @Test fun `same model ID different provider endpoint blocks shared binding and is isolated per root`() {
        for (topology in T0RecoveryLab.Topology.entries) {
            val lab = fixture("endpoints", topology)
            Files.writeString(lab.root("B").resolve("binding"), "same-model|other-provider|https://beta.invalid")
            if (topology == T0RecoveryLab.Topology.SHARED) assertFails { lab.prepare() }
            else {
                val before = snapshot(lab.secretRoot("B")); lab.prepare(); lab.publish(); lab.apply("DATA_FIRST")
                assertEquals(before, snapshot(lab.secretRoot("B"))); assertEquals(T0RecoveryLab.Admission.ALL_NEW, lab.gate())
            }
        }
    }
    @Test fun `directory clone replacement root switch and Portable relocation need rebind approval`() {
        for (topology in T0RecoveryLab.Topology.entries) for (mode in listOf("clone", "move")) {
            val lab = fixture("identity-$mode", topology); lab.prepare(); lab.publish()
            val original = lab.root("A"); Files.move(original, lab.base.resolve("retained-A"))
            if (mode == "clone") {
                Files.createDirectory(original)
                Files.list(lab.base.resolve("retained-A")).use { it.filter { Files.isRegularFile(it) }.forEach { Files.copy(it, original.resolve(it.fileName)) } }
            }
            blocked(lab); assertFails { lab.recover() }
            assertTrue(Files.exists(lab.base.resolve("retained-A/root.id")))
        }
    }
    @Test fun `invalid fake protection context and inaccessible key location cannot become ABSENT`() = both { lab ->
        lab.prepare(); lab.publish()
        assertFails { lab.recover(context = "other-context") }; blocked(lab)
        val target = lab.store("A").secretPath(T0RecoveryLab.KEYS[0])
        Files.move(target, target.resolveSibling("retained.ccbsecret")); Files.createDirectory(target)
        assertFails { lab.store("A").load(T0RecoveryLab.KEYS[0]) }
        assertFails { lab.recover() }; blocked(lab)
    }
    @Test fun `Community no protected sink and missing conflict consent cannot prepare a transaction`() = both { lab ->
        val before = lab.targets("A").map { T0RecoveryLab.hash(it) }
        assertFails { lab.prepare(community = true) }; assertFails { lab.prepare(consent = false) }
        assertEquals(before, lab.targets("A").map { T0RecoveryLab.hash(it) }); assertFalse(Files.exists(lab.home()))
    }
    @Test fun `cross process gates exclude shared consumers but permit independent namespaces`() = both { lab ->
        lab.locked {
            val output = child(lab, "gate-B")
            assertTrue(output.contains(if (lab.topology == T0RecoveryLab.Topology.SHARED) "T0_GATE=BLOCKED" else "T0_GATE=ALL_OLD"))
        }
    }
    @Test fun `legacy writer bypasses new locks so marker cannot prove downgrade protection`() = both { lab ->
        lab.prepare(); lab.publish()
        lab.locked { lab.store("A").save(T0RecoveryLab.KEYS[0], "FAKE_T0_LEGACY_WRITER") }
        blocked(lab); assertFails { lab.recover() }
        assertTrue(lab.store("A").load(T0RecoveryLab.KEYS[0]) == "FAKE_T0_LEGACY_WRITER")
    }
    @Test fun `unregistered shared consumer proves the closed registry assumption is not global proof`() {
        val lab = fixture("unknown-consumer", T0RecoveryLab.Topology.SHARED)
        val c = Files.createDirectory(lab.base.resolve("unregistered-C"))
        Files.writeString(c.resolve("data"), "SYNTHETIC_DATA_GEN_1")
        lab.prepare(); lab.publish(); lab.apply("DATA_FIRST")
        // Negative observation: a non-cooperating unknown consumer could still combine OLD data with NEW key.
        assertTrue(Files.readString(c.resolve("data")).endsWith("GEN_1"))
        assertTrue(lab.store("B").load(T0RecoveryLab.KEYS[0]) == "FAKE_T0_NEW_0")
        println("T0_LIMIT UNKNOWN_SHARED_CONSUMER_CLOSURE_NOT_PROVEN")
    }
}
