package com.example.chatbar.lanSync

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Two explicit instrumentation processes on a disposable cloud emulator; never pulls keys/data into reports. */
class LanSyncGroupKeystoreProcessTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val cache get() = context.cacheDir.canonicalFile.toPath().toRealPath()
    private val pointer get() = cache.resolve("synthetic-group-keystore-process-v1.txt")
    private fun optIn() = assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncGroupNativeFixture") == "true")
    private fun report(marker: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2,
        android.os.Bundle().apply { putString("stream", marker + "\n") })
    @Test fun seedApprovedSourceIntent() {
        optIn(); check(!Files.exists(pointer)) { "SYNTHETIC_GROUP_PROOF_ALREADY_SEEDED" }
        val root = Files.createTempDirectory(cache, "ccb-native-group-android-").toRealPath()
        pointer.toFile().writeText(root.toString(), Charsets.UTF_8)
        val phase = Phase(root, true)
        report("ROOT_CREATION_AND_LEDGER_SEED_PASS")
        report("MANAGEMENT_VAULT_SEED_PASS")
        phase.card("synthetic-keystore-original")
        val view = phase.view
        val evidence = LanSyncGroupRecordEvidence(requireNotNull(view.history), requireNotNull(view.ledgerCheckpoint))
        val plan = phase.workspace.capture(phase.identity.fingerprint).use {
            LanSyncGroupNativePlanner.prepare(it, view, emptyMap(), emptyMap(), evidence)
        }
        val review = LanSyncGroupSourceReview.prepare(plan, view, setOf("character_cards/card"), emptyMap(), evidence)
        val operation = SyncStoreId.random(); val commit = phase.writer().commit(review.authorize(), operation)
        root.resolve("source-operation.txt").toFile().writeText(operation.value, Charsets.UTF_8)
        root.resolve("seed-pid.txt").toFile().writeText(android.os.Process.myPid().toString(), Charsets.UTF_8)
        phase.observeOwn(commit)
        try {
            phase.control(interrupt = true).recordSource(commit)
            fail("SYNTHETIC_SOURCE_INTENT_INTERRUPTION_NOT_REACHED")
        } catch (expected: IOException) { assertEquals("SYNTHETIC_ANDROID_SOURCE_INTENT_INTERRUPTION", expected.message) }
        assertNotNull(phase.control().state().pendingTransaction)
        assertEquals(1L, phase.journal.state().committedSequence)
        report("GROUP_NATIVE_KEYSTORE_SEED_PASS")
    }
    @Test fun reopenInAnotherProcessAndRecoverOnlyTheHistoricalReceipt() {
        optIn(); check(Files.isRegularFile(pointer)) { "SYNTHETIC_GROUP_PROOF_SEED_MISSING" }
        val root = Paths.get(pointer.toFile().readText(Charsets.UTF_8)).toRealPath()
        check(root.startsWith(cache) && root.fileName.toString().startsWith("ccb-native-group-android-"))
        assertNotEquals(root.resolve("seed-pid.txt").toFile().readText(Charsets.UTF_8).toInt(), android.os.Process.myPid())
        val phase = Phase(root, false)
        report("ROOT_CREATION_AND_LEDGER_REOPEN_PASS")
        report("MANAGEMENT_VAULT_REOPEN_PASS")
        val commit = phase.writer().resume(SyncStoreId.parse(root.resolve("source-operation.txt").toFile().readText(Charsets.UTF_8)))
        phase.observeOwn(commit); assertNotNull(phase.control().state().pendingTransaction)
        phase.card("synthetic-keystore-later-edit")
        val recovered = phase.control().recover()
        assertNull(recovered.pendingTransaction)
        assertEquals(setOf(commit.entry.version.reference()), recovered.units.getValue("character_cards/card").frontiers)
        val receipt = phase.control().recordSource(commit)
        assertEquals(LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED, receipt.status)
        assertFalse(phase.workspace.applied(receipt.transaction)); assertTrue(receipt.heldUnits.isEmpty())
        val actual = phase.json.decodeFromString(CharacterCard.serializer(), phase.data.resolve("entities/character_cards/card.json").toFile().readText(Charsets.UTF_8))
        assertEquals("synthetic-keystore-later-edit", actual.name)
        assertEquals(1L, phase.journal.state().committedSequence)
        report("GROUP_NATIVE_KEYSTORE_REOPEN_PASS")
    }

    private class Phase(val root: Path, private val seed: Boolean) {
        val json = Json { encodeDefaults = true }
        private fun directory(name: String) = root.resolve(name).also { if (seed) Files.createDirectory(it); check(Files.isDirectory(it)) }
        private fun text(name: String) = root.resolve(name).toFile().readText(Charsets.UTF_8)
        private fun save(name: String, value: String) = root.resolve(name).toFile().writeText(value, Charsets.UTF_8)
        private val alias = if (seed) "synthetic-native-group-${UUID.randomUUID()}".also { save("alias.txt", it) } else text("alias.txt")
        private val protection = AndroidLanSyncKeyProtection(alias)
        val identity = LanSyncStateStore(root.resolve("identity"), protection::protect, protection::unprotect).identity()
        private val recipientCustody = SyncDeviceKeyCustody(directory("recipient"), identity.fingerprint, protection::protect, protection::unprotect)
        private val recipient = if (seed) recipientCustody.createIfAbsent() else recipientCustody.open(text("recipient-public.txt"))
        private val certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
        // Artificial code is retained only inside the disposable cloud-emulator fixture, never reports.
        private val admin = if (seed) SyncManagementSession.create() else SyncManagementSession.open(text("synthetic-management-code.txt"),
            Files.readAllBytes(root.resolve("management-vault.bin")))
        private val adminCertificate = admin.ownerCertificate
        private val creation = SyncRootGroupCreation.managed(directory("creation"), adminCertificate, admin.recipientPublicKey,
            certificate, recipient, admin::protectJournal, admin::unprotectJournal)
        private val creationOperation = if (seed) SyncStoreId.random().also { save("creation-operation.txt", it.value) }
            else SyncStoreId.parse(text("creation-operation.txt"))
        private val created = if (seed) creation.create(SyncRootGroupCreateRequest(creationOperation, LanSyncScope.schemas.keys, "a".repeat(64)),
            SyncRootGroupCreateAdmission {}, admin::signRootPayload)
            else creation.resume(creationOperation) { error("SYNTHETIC_GENESIS_MUST_NOT_RESIGN") }
        private val group = created.trust.group
        private val custody = SyncGroupCustody(directory("group"), group, recipient, protection::protect, protection::unprotect)
        private val rootJournal = SyncRootLedgerJournal(directory("root-actions"), created.trust, adminCertificate, admin::protectJournal, admin::unprotectJournal)
        init {
            check(lanSyncHash(Base64.getDecoder().decode(adminCertificate)) != identity.fingerprint)
            if (seed) {
                Files.write(root.resolve("management-vault.bin"), admin.seal(created.trust).bytes())
                save("synthetic-management-code.txt", admin.recoveryCode())
                val initialLedger = created.enrollCreator(custody)
                rootJournal.initializeNewEnrollment(initialLedger)
                val parent = initialLedger.current()
                val request = SyncRootLedgerRequest(SyncStoreId.random(), parent.reference(), parent.members, parent.coveredTypes,
                    parent.acceptedHeads, true, "b".repeat(64))
                save("root-operation.txt", request.operation.value)
                val commit = rootJournal.commit(request, custody::ledger, SyncRootLedgerAdmission {}, admin::signRootPayload)
                val ledger = rootJournal.applyToCustody(commit, custody); val key = custody.key(ledger.current().epoch)
                val delivery = SyncGroupKeyDelivery.create(ledger, key, identity.fingerprint, SyncStoreId.random(), admin::signRootPayload)
                custody.rememberKey(delivery.openTentative(created.trust, recipient, CiphertextObjectKey(CiphertextObjectKind.COMMIT, delivery.context.objectId)).admit(ledger))
                save("actor.txt", identity.fingerprint); save("recipient-public.txt", recipient.publicKey.encoded); save("group-id.txt", group.value)
            } else {
                check(admin.trust == created.trust)
                check(identity.fingerprint == text("actor.txt") && group.value == text("group-id.txt"))
                val ledger = custody.ledger()
                check(ledger.current().epoch != created.genesis.epoch && custody.key(created.genesis.epoch).keyCommitment() == created.genesis.keyCommitment)
                check(rootJournal.state().completedSequence == 1L && rootJournal.nextRecoveryRequest(custody) == null)
                val entry = requireNotNull(rootJournal.entry(SyncStoreId.parse(text("root-operation.txt"))))
                check(entry.record.reference() == ledger.current().reference())
                check(rootJournal.commit(entry.request, custody::ledger, SyncRootLedgerAdmission { error("SYNTHETIC_ROOT_MUST_NOT_REAPPROVE") },
                    { error("SYNTHETIC_ROOT_MUST_NOT_RESIGN") }).entry == entry)
            }
            admin.close(); check(!admin.isUnlocked)
        }
        private val ledger = custody.ledger(); private val trust = ledger.trustAnchor
        private val key = custody.key(ledger.current().epoch)
        private val authorRoot = directory("author"); private val writerRoot = directory("writer"); private val controlRoot = directory("control")
        private val queueRoot = directory("queue"); val data = directory("data"); private val state = directory("state")
        val journal = SyncAuthorJournal(authorRoot, trust, Base64.getEncoder().encodeToString(identity.certificate.encoded), protection::protect, protection::unprotect)
        val workspace = LanSyncWorkspace(data, state, { Paths.get(it) })
        var view = SyncInboxReconciliation(emptyList(), SyncCausalHistory(group, ledger.admission(emptyList(), emptyList())), emptyList(), false, ledger.current().reference())
        init { if (seed) { journal.initializeNewEnrollment(); control().initializeNewEnrollment() } }
        fun control(interrupt: Boolean = false) = LanSyncGroupNativeApplyOwner(controlRoot, workspace, trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), LanSyncProjection(), custody::ledger, { view },
            protection::protect, protection::unprotect, observe = { if (interrupt && it == "NATIVE_SOURCE_INTENT_RETAINED") {
                throw IOException("SYNTHETIC_ANDROID_SOURCE_INTENT_INTERRUPTION")
            } }, authorJournal = journal)
        fun writer() = LanSyncGroupSourceWriter(writerRoot, workspace, identity, journal, custody::ledger, custody::key,
            { epoch -> check(epoch == key.epoch); SyncEncryptedOutbox(queueRoot, custody.key(epoch)) }, { view }, { control().state() })
        fun card(name: String) {
            val file = data.resolve("entities/character_cards/card.json"); Files.createDirectories(file.parent)
            file.toFile().writeText(json.encodeToString(CharacterCard.serializer(), CharacterCard("card", name, createdAt = 1, updatedAt = 1)), Charsets.UTF_8)
        }
        fun observeOwn(commit: LanSyncGroupSourceCommit) {
            val history = SyncCausalHistory(group, ledger.admission(listOf(commit.entry.version), listOf(commit.metadata)))
            check(history.offer(commit.entry.version) == SyncVersionState.ADMITTED)
            view = SyncInboxReconciliation(emptyList(), history, listOf(commit.metadata), false, ledger.current().reference())
        }
    }
}
