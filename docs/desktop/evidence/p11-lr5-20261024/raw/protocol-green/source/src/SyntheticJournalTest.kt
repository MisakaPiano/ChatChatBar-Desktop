package com.example.chatbar.desktop.lr5

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class ChildResult(val exit: Int, val text: String) {
    fun state(expected: String) {
        assertTrue("expected $expected; fixed output=$text", text.contains("STATE=$expected\n"))
        assertTrue(text.contains("BUSINESS_AUTHORIZED=false"))
        assertEquals(if (expected == "RECOVERY_BLOCKED") 2 else if (expected == "RESTART_REQUIRED") 3 else 0, exit)
    }
}
internal class Fixture(val label: String) {
    private val parent = Path.of(System.getProperty("ccb.lr5.sandboxes"))
    private val evidence = Path.of(System.getProperty("ccb.lr5.evidence")).resolve(label)
    private val instance = SyntheticJournal.fresh(parent)
    val root: Path = instance.first
    val claim = instance.second
    private var sequence = 0
    private val oldProof = retainedProof()
    init { Files.createDirectory(evidence); inventory("initial") }
    fun retainedProof(): String = digest((listOf("source") + SyntheticJournal.ids.map { "old/$it" }).flatMap { Files.readAllBytes(root.resolve(it)).toList() }.toByteArray())
    fun retained() { assertEquals(oldProof, retainedProof()); inventory("final") }
    fun child(command: String, fault: String = "NONE", order: String = "KEY_FIRST"): ChildResult {
        val process = launch(command, fault, order)
        val finished = process.first.waitFor(25, TimeUnit.SECONDS)
        if (!finished) { process.first.destroyForcibly(); process.first.waitFor(5, TimeUnit.SECONDS) }
        assertTrue("bounded child timeout; child cleanup attempted", finished)
        val code = process.first.exitValue(); val text = Files.readString(process.second).replace("\r\n", "\n")
        Files.writeString(process.second.resolveSibling(process.second.fileName.toString() + ".exit"), "$code\n", StandardOpenOption.CREATE_NEW)
        assertFalse("path redaction", text.contains(root.toString())); assertFalse("claim redaction", text.contains(claim))
        assertFalse("fake secret redaction", text.contains("SYNTHETIC_ONLY")); inventory("child-$sequence")
        return ChildResult(code, text)
    }
    fun launch(command: String, fault: String = "NONE", order: String = "KEY_FIRST"): Pair<Process, Path> {
        sequence++
        val log = evidence.resolve("child-$sequence.stdout")
        val args = listOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx96m", "-XX:ActiveProcessorCount=1",
            "-XX:+DisableAttachMechanism", "-XX:-HeapDumpOnOutOfMemoryError", "-Djava.awt.headless=true", "-cp", System.getProperty("ccb.lr5.classpath"),
            SyntheticJournalActor::class.java.name, root.toString(), claim, command, fault, order)
        val builder = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile())
        for (k in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(k)
        return builder.start() to log
    }
    fun cut(stage: String, order: String = "KEY_FIRST") {
        val child = child("APPLY", stage, order); assertEquals(71, child.exit)
        assertTrue("actual halt stage", child.text.contains("STAGE=$stage\n")); assertFalse(child.text.contains("STATE="))
    }
    fun mutateContext(key: String, value: String) {
        val file = root.resolve("context")
        val keys = setOf("root", "oldRoot", "newRoot", "profileRoot", "profile", "protector", "account", "endpoint", "purpose", "generation", "consumers", "source")
        Files.write(file, ExperimentWire.encode("CONTEXT", ExperimentWire.decode(Files.readAllBytes(file), "CONTEXT", keys) + (key to value)))
    }
    fun inventory(name: String) {
        val rows = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().map {
            val name = root.relativize(it).toString().replace('\\','/')
            if (it.fileName.toString() == "lock") "$name\t${Files.size(it)}\tLOCK_CONTROL_NOT_READ"
            else { val b = Files.readAllBytes(it); "$name\t${b.size}\t${digest(b)}" }
        }.toList() }
        Files.writeString(evidence.resolve("$name.inventory.tsv"), rows.joinToString("\n", postfix = "\n"))
    }
    fun preserveSelectedBytes() {
        val destination = Files.createDirectory(evidence.resolve("retained-synthetic-state"))
        Files.walk(root).use { paths -> paths.forEach { p ->
            val dest = destination.resolve(root.relativize(p).toString())
            if (Files.isDirectory(p)) { if (p != root) Files.createDirectory(dest) }
            else Files.copy(p, dest)
        } }
    }
}

class SyntheticJournalTest {
    @Test fun hardHaltsAtEveryPersistedBoundaryRequireIndependentRecovery() {
        val stages = listOf("BEFORE_INTENT", "AFTER_INTENT", "AFTER_KEY1", "AFTER_KEY2", "AFTER_DATA", "AFTER_VOICE", "AFTER_MOMENT", "BEFORE_RECEIPT", "AFTER_RECEIPT", "BEFORE_PUBLICATION", "AFTER_PUBLICATION", "BEFORE_REOPEN")
        for (stage in stages) {
            val f = Fixture("cut-$stage"); f.cut(stage)
            if (stage == "BEFORE_INTENT") f.child("RECOVER").state("RECOVERY_BLOCKED")
            else { f.child("RECOVER").state("ALL_NEW"); f.child("VERIFY").state("ALL_NEW"); f.child("RECOVER").state("ALL_NEW") }
            f.retained()
            if (stage in setOf("AFTER_KEY1", "AFTER_PUBLICATION")) f.preserveSelectedBytes()
        }
    }
    @Test fun dataFirstMixIsNotBusinessReadyAndRestartsToNew() {
        val f = Fixture("data-first"); f.cut("AFTER_DATA", "DATA_FIRST")
        assertArrayEquals(SyntheticJournal.bytes("data", "NEW"), Files.readAllBytes(f.root.resolve("new/data")))
        assertArrayEquals(SyntheticJournal.bytes("key1", "OLD"), Files.readAllBytes(f.root.resolve("new/key1")))
        f.child("VERIFY").state("RECOVERY_BLOCKED"); f.child("RECOVER").state("ALL_NEW"); f.retained()
    }
    @Test fun secondKeyFailurePreservesMixedEvidenceAndRecovers() {
        val f = Fixture("second-key-fail"); f.child("APPLY", "SECOND_KEY_FAIL").state("RECOVERY_BLOCKED")
        assertArrayEquals(SyntheticJournal.bytes("key1", "NEW"), Files.readAllBytes(f.root.resolve("new/key1")))
        assertArrayEquals(SyntheticJournal.bytes("key2", "OLD"), Files.readAllBytes(f.root.resolve("new/key2")))
        f.child("RECOVER").state("ALL_NEW"); f.retained()
    }
    @Test fun explicitRollbackIsAllOldNotImportSuccessAndRetainsNewBytes() {
        val f = Fixture("rollback"); f.cut("AFTER_KEY1")
        f.child("ROLLBACK").state("ALL_OLD"); f.child("VERIFY").state("ALL_OLD")
        SyntheticJournal.ids.forEach { assertArrayEquals(SyntheticJournal.bytes(it, "NEW"), Files.readAllBytes(f.root.resolve("wal/$it.new"))) }
        f.retained(); f.preserveSelectedBytes()
    }
    @Test fun receiptMissingAfterPublicationNeverMeansAllNew() {
        val f = Fixture("receipt-lost-published"); f.cut("AFTER_PUBLICATION"); Files.delete(f.root.resolve("wal/receipt"))
        f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained(); f.preserveSelectedBytes()
    }
    @Test fun partialAndCorruptReceiptBeforePublicationAreRetainedAndBlock() {
        for (bytes in listOf(byteArrayOf(1), "corrupt-checksum".toByteArray())) {
            val f = Fixture("receipt-bad-${bytes.size}"); f.cut("AFTER_RECEIPT"); Files.write(f.root.resolve("wal/receipt"), bytes)
            f.child("RECOVER").state("RECOVERY_BLOCKED")
            assertArrayEquals(bytes, Files.readAllBytes(f.root.resolve("wal/receipt"))); f.retained()
        }
    }
    @Test fun missingPrepublicationReceiptReplaysOnlyFromValidatedIntent() {
        val f = Fixture("receipt-lost-intent"); f.cut("AFTER_RECEIPT"); Files.delete(f.root.resolve("wal/receipt"))
        f.child("RECOVER").state("ALL_NEW"); f.retained()
    }
    @Test fun publishedReceiptCorruptionAndWrongBindingBlock() {
        val f = Fixture("receipt-bad-published"); f.cut("AFTER_PUBLICATION")
        val receipt = f.root.resolve("wal/receipt"); val b = Files.readAllBytes(receipt); b[10] = (b[10].toInt() xor 1).toByte(); Files.write(receipt, b)
        f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
    }
    @Test fun authorityLostAtAnyNonemptyPreparationIsNotNoPending() {
        for (stage in listOf("AFTER_INTENT", "AFTER_KEY1", "AFTER_PUBLICATION")) {
            val f = Fixture("authority-lost-$stage"); f.cut(stage); Files.delete(f.root.resolve("profile/authority"))
            f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        }
    }
    @Test fun futureVersionChecksumAndPartialAuthorityBlock() {
        for ((label, bytes) in listOf("future" to "LR5-SYNTHETIC-999\n".toByteArray(), "checksum" to ByteArray(40) { 65 }, "partial" to byteArrayOf(10))) {
            val f = Fixture("authority-$label"); f.cut("AFTER_INTENT"); Files.write(f.root.resolve("profile/authority"), bytes)
            f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        }
    }
    @Test fun corruptOldAndNewEvidenceNeverGetsReconstructed() {
        for (side in listOf("old", "new")) {
            val f = Fixture("evidence-corrupt-$side"); f.cut("AFTER_KEY1"); Files.writeString(f.root.resolve("wal/key2.$side"), "SYNTHETIC_FOREIGN")
            f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        }
    }
    @Test fun externallyChangedTargetBlocksBeforeAnyRecoveryWrites() {
        for (id in listOf("data", "key1", "moment")) {
            val f = Fixture("foreign-$id"); f.cut("AFTER_KEY1"); Files.writeString(f.root.resolve("new/$id"), "SYNTHETIC_FOREIGN")
            val before = SyntheticJournal.ids.associateWith { Files.readAllBytes(f.root.resolve("new/$it")) }
            f.child("RECOVER").state("RECOVERY_BLOCKED")
            before.forEach { (k, v) -> assertArrayEquals(v, Files.readAllBytes(f.root.resolve("new/$k"))) }; f.retained()
        }
    }
    @Test fun changedProtectionAccountEndpointPurposeAndGenerationAreNotAbsent() {
        for ((key, value) in mapOf("profile" to "OTHER_PROFILE", "protector" to "OTHER_FAKE", "account" to "OTHER_ACCOUNT", "endpoint" to "https://other.invalid/v1", "purpose" to "OTHER_PURPOSE", "generation" to "2")) {
            val f = Fixture("identity-$key"); f.cut("AFTER_INTENT"); f.mutateContext(key, value)
            f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        }
    }
    @Test fun unknownThirdConsumerAndNonclosedConsumerObservationBlock() {
        val f = Fixture("third-consumer"); f.cut("AFTER_INTENT"); Files.writeString(f.root.resolve("registry"), "data|key1|key2|voice|moment|third")
        f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        val unknown = Fixture("unknown-consumer"); unknown.cut("AFTER_INTENT"); unknown.mutateContext("consumers", "UNKNOWN_OFFLINE_ROOT")
        unknown.child("RECOVER").state("RECOVERY_BLOCKED"); unknown.retained()
    }
    @Test fun missingParticipantAndIncompleteDormantSetBlock() {
        for (id in listOf("key2", "voice", "moment")) {
            val f = Fixture("offline-$id"); f.cut("AFTER_INTENT"); Files.move(f.root.resolve("new/$id"), f.root.resolve("offline-$id"))
            f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        }
    }
    @Test fun clonedSandboxCannotUseCopiedNativeRootWitnesses() {
        val f = Fixture("clone"); f.cut("AFTER_INTENT")
        val clone = Files.createTempDirectory(Path.of(System.getProperty("ccb.lr5.sandboxes")), "synthetic-clone-")
        Files.walk(f.root).use { all -> all.forEach { p ->
            val target = clone.resolve(f.root.relativize(p).toString())
            if (Files.isDirectory(p)) { if (p != f.root) Files.createDirectory(target) } else Files.copy(p, target)
        } }
        val outcome = SyntheticJournal(clone, f.claim).run("RECOVER")
        assertEquals("RECOVERY_BLOCKED", outcome.state); assertEquals("ROOT_IDENTITY", outcome.reason); f.retained()
    }
    @Test fun capacityAndMidwriteIoFailureKeepOldAndTentativeBytes() {
        val low = Fixture("capacity-before"); low.child("APPLY", "LOW_CAPACITY").state("RECOVERY_BLOCKED")
        low.child("RECOVER").state("ALL_OLD"); low.retained()
        val mid = Fixture("capacity-write"); mid.child("APPLY", "LOW_DISK_DURING_WRITE").state("RECOVERY_BLOCKED")
        assertTrue(Files.list(mid.root.resolve("wal")).use { s -> s.anyMatch { it.fileName.toString().startsWith("part-") } })
        mid.child("RECOVER").state("RECOVERY_BLOCKED"); mid.retained()
    }
    @Test fun cancellationBeforeAndAfterIntentHasDistinctRecovery() {
        val before = Fixture("cancel-before"); before.child("APPLY", "CANCEL_BEFORE_INTENT").state("RECOVERY_BLOCKED")
        before.child("RECOVER").state("RECOVERY_BLOCKED"); before.retained()
        val after = Fixture("cancel-after"); after.child("APPLY", "CANCEL_AFTER_INTENT").state("RESTART_REQUIRED")
        after.child("RECOVER").state("ALL_NEW"); after.retained()
    }
    @Test fun crashDuringRecoveryRemainsRestartable() {
        val f = Fixture("recovery-crash"); f.cut("AFTER_KEY1")
        assertEquals(71, f.child("RECOVER", "AFTER_VOICE").exit)
        f.child("RECOVER").state("ALL_NEW"); f.child("RECOVER").state("ALL_NEW"); f.retained()
    }
    @Test fun cooperatingProcessIsLockedButNoncooperatorIsNotFenced() {
        val f = Fixture("locking"); f.cut("AFTER_INTENT"); val hold = f.launch("HOLD")
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(f.root.resolve("lock-ready")) && System.nanoTime() < deadline && hold.first.isAlive) Thread.sleep(20)
            assertTrue(Files.exists(f.root.resolve("lock-ready")))
            val locked = f.child("RECOVER"); locked.state("RECOVERY_BLOCKED"); assertTrue(locked.text.contains("REASON=LOCKED"))
            Files.writeString(f.root.resolve("new/key1"), "SYNTHETIC_UNCOOPERATING_WRITER")
            assertTrue(hold.first.waitFor(10, TimeUnit.SECONDS)); assertEquals(3, hold.first.exitValue())
            Files.writeString(hold.second.resolveSibling(hold.second.fileName.toString() + ".exit"), "3\n", StandardOpenOption.CREATE_NEW)
            f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        } finally {
            if (hold.first.isAlive) { hold.first.destroyForcibly(); hold.first.waitFor(5, TimeUnit.SECONDS) }
            val exitFile = hold.second.resolveSibling(hold.second.fileName.toString() + ".exit")
            if (!Files.exists(exitFile) && !hold.first.isAlive) Files.writeString(exitFile, "${hold.first.exitValue()}\n", StandardOpenOption.CREATE_NEW)
        }
    }
    @Test fun oldRootAndSourceForeignChangesNeverGetOverwritten() {
        for (name in listOf("source", "old/key1")) {
            val f = Fixture("old-foreign-${name.replace('/','-')}"); f.cut("AFTER_INTENT")
            Files.writeString(f.root.resolve(name), "SYNTHETIC_EXTERNAL")
            f.child("RECOVER").state("RECOVERY_BLOCKED")
            assertEquals("SYNTHETIC_EXTERNAL", Files.readString(f.root.resolve(name))); f.inventory("foreign-retained")
        }
    }
    @Test fun unknownParticipantAndUnsupportedCommunityNeverBecomeReady() {
        val f = Fixture("community-unknown"); Files.writeString(f.root.resolve("new/community"), "SYNTHETIC_SESSION")
        f.child("APPLY").state("RECOVERY_BLOCKED"); f.retained()
    }
    @Test fun receiptCannotHideParticipantRevertedToRecognizedOldBytes() {
        val f = Fixture("receipt-reverted-old"); f.cut("AFTER_RECEIPT")
        Files.write(f.root.resolve("new/key2"), SyntheticJournal.bytes("key2", "OLD"))
        val receipt = Files.readAllBytes(f.root.resolve("wal/receipt"))
        f.child("RECOVER").state("RECOVERY_BLOCKED")
        assertArrayEquals(receipt, Files.readAllBytes(f.root.resolve("wal/receipt"))); f.retained()
    }
    @Test fun unknownWalAndAlternateAuthorityFilesAreNotSilentlyIgnored() {
        for (relative in listOf("wal/unknown-participant", "profile/alternate-authority")) {
            val f = Fixture("unknown-${relative.substringAfter('/')}"); f.cut("AFTER_INTENT")
            Files.writeString(f.root.resolve(relative), "SYNTHETIC_FUTURE")
            f.child("RECOVER").state("RECOVERY_BLOCKED"); f.retained()
        }
    }
    @Test fun participantCopiesArePhysicallyIndependentAndExactOnReopen() {
        val f = Fixture("independent-copies"); f.child("APPLY").state("ALL_NEW")
        for (id in SyntheticJournal.ids) {
            assertFalse(Files.isSameFile(f.root.resolve("old/$id"), f.root.resolve("new/$id")))
            assertFalse(Files.isSameFile(f.root.resolve("old/$id"), f.root.resolve("wal/$id.old")))
            assertFalse(Files.isSameFile(f.root.resolve("wal/$id.old"), f.root.resolve("wal/$id.new")))
            assertArrayEquals(SyntheticJournal.bytes(id, "OLD"), Files.readAllBytes(f.root.resolve("wal/$id.old")))
            assertArrayEquals(SyntheticJournal.bytes(id, "NEW"), Files.readAllBytes(f.root.resolve("new/$id")))
        }
        f.child("VERIFY").state("ALL_NEW"); f.retained()
    }
    @Test fun oversizedMetadataAndTargetBytesRefuseWithoutPublication() {
        for (relative in listOf("context", "new/data")) {
            val f = Fixture("oversize-${relative.replace('/','-')}")
            Files.write(f.root.resolve(relative), ByteArray(32769) { 65 })
            f.child("APPLY").state("RECOVERY_BLOCKED")
            assertFalse(Files.exists(f.root.resolve("profile/authority"))); f.retained()
        }
    }
    @Test fun baselineReopenIsAllOldAndCompletedApplyCannotRestartPreparation() {
        val f = Fixture("repeat-complete"); f.child("RECOVER").state("ALL_OLD")
        f.child("APPLY").state("ALL_NEW"); f.child("APPLY").state("RECOVERY_BLOCKED")
        f.child("VERIFY").state("ALL_NEW"); f.retained()
    }
}

class ExperimentWireTest {
    @Test fun futureDuplicateMalformedOversizedAndNoncanonicalWireRefuse() {
        val good = ExperimentWire.encode("TEST", mapOf("a" to "one", "b" to "two"))
        assertEquals(mapOf("a" to "one", "b" to "two"), ExperimentWire.decode(good, "TEST", setOf("a", "b")))
        val variants = listOf(good.toString(Charsets.UTF_8).replace("SYNTHETIC-1", "SYNTHETIC-2").toByteArray(),
            good.toString(Charsets.UTF_8).replace("b=two", "a=two").toByteArray(), byteArrayOf(-1), ByteArray(32769), good.copyOf(good.size - 2))
        for (bad in variants) {
            try { ExperimentWire.decode(bad, "TEST", setOf("a", "b")); fail("wire accepted malformed bytes") }
            catch (_: Refused) { }
        }
    }
    @Test fun foreignSandboxClaimIsRefusedBeforeAnyWrites() {
        val (root, _) = SyntheticJournal.fresh(Path.of(System.getProperty("ccb.lr5.sandboxes")))
        try { SyntheticJournal(root, "00000000-0000-0000-0000-000000000000"); fail("accepted wrong claim") }
        catch (e: Refused) { assertEquals("SANDBOX_CLAIM", e.code) }
    }
}
