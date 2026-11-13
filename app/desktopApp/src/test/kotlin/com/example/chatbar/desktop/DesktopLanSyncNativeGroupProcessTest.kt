package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Real DPAPI and abrupt separate-JVM recovery; synthetic roots never leave the ephemeral runner. */
class DesktopLanSyncNativeGroupProcessTest {
    @Test fun `native source journal and interrupted control receipt survive actual DPAPI process restart`() {
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("ccb-native-group-control-").toRealPath()
        val classpath = requireNotNull(System.getProperty("ccb.readonly.classpath"))
        for ((mode, expectedExit) in listOf("write" to 63, "read" to 0)) {
            val log = root.resolve("$mode.log")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                DesktopLanSyncNativeGroupChild::class.java.name, root.toString(), mode).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            process.outputStream.close()
            try {
                assertTrue(process.waitFor(120, TimeUnit.SECONDS), "synthetic native control child timeout")
                assertEquals(expectedExit, process.exitValue(), Files.readString(log).take(1600))
                assertTrue(Files.readString(log).contains(if (mode == "write") "NATIVE_GROUP_WRITE_INTENT_SEALED" else "NATIVE_GROUP_READ_RECOVERED"))
            } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        }
    }
}

object DesktopLanSyncNativeGroupChild {
    private val json = Json { encodeDefaults = true }
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]).toRealPath(); check(root.fileName.toString().startsWith("ccb-native-group-control-"))
        val writing = args[1] == "write"; check(writing || args[1] == "read")
        fun directory(name: String) = root.resolve(name).also { if (writing) Files.createDirectory(it); check(Files.isDirectory(it)) }
        val protector = WindowsDpapiSecretProtector()
        val identity = LanSyncStateStore(root.resolve("identity"), protector::protect, protector::unprotect).identity()
        if (writing) Files.writeString(root.resolve("actor.txt"), identity.fingerprint, CREATE_NEW)
        else check(identity.fingerprint == Files.readString(root.resolve("actor.txt")))
        val recipientCustody = SyncDeviceKeyCustody(directory("recipient"), identity.fingerprint, protector::protect, protector::unprotect)
        val recipient = if (writing) recipientCustody.createIfAbsent() else recipientCustody.open(Files.readString(root.resolve("recipient-public.txt")))
        val initial = if (writing) SyncStreamingCipher.create() else null
        val group = initial?.group ?: SyncStoreId.parse(Files.readString(root.resolve("group-id.txt")))
        val custody = SyncGroupCustody(directory("group"), group, recipient, protector::protect, protector::unprotect)
        if (writing) {
            val key = requireNotNull(initial)
            val member = SyncGroupMember(Base64.getEncoder().encodeToString(identity.certificate.encoded), recipient.publicKey.encoded)
            val genesis = SyncDeviceLedgerRecord(group, SyncStoreId.random(), null, 1, key.epoch, key.keyCommitment(), LanSyncScope.schemas.keys,
                mapOf(identity.fingerprint to member), emptySet(), "unsigned").let {
                it.copy(signature = Base64.getEncoder().encodeToString(identity.signSyncGroupPayload(it.signingBytes())))
            }
            val trust = SyncGroupTrust(group, genesis.reference(), member.certificate)
            val ledger = custody.enroll(trust, listOf(genesis))
            val delivery = SyncGroupKeyDelivery.create(ledger, key, identity.fingerprint, SyncStoreId.random(), identity::signSyncGroupPayload)
            custody.rememberKey(delivery.openTentative(trust, recipient, CiphertextObjectKey(CiphertextObjectKind.COMMIT, delivery.context.objectId)).admit(ledger))
            Files.writeString(root.resolve("recipient-public.txt"), recipient.publicKey.encoded, CREATE_NEW)
            Files.writeString(root.resolve("group-id.txt"), group.value, CREATE_NEW)
        }
        val ledger = custody.ledger(); val trust = ledger.trustAnchor
        val key = custody.key(ledger.current().epoch); check(key.keyCommitment() == ledger.current().keyCommitment)
        val authorRoot = directory("author"); val sourceRoot = directory("source-writer"); val controlRoot = directory("native-control")
        val queueRoot = directory("outbox"); val data = directory("data"); val state = directory("state")
        val journal = SyncAuthorJournal(authorRoot, trust, Base64.getEncoder().encodeToString(identity.certificate.encoded), protector::protect, protector::unprotect)
        val workspace = LanSyncWorkspace(data, state, { Path.of(it) })
        var view = SyncInboxReconciliation(emptyList(), SyncCausalHistory(group, ledger.admission(emptyList(), emptyList())), emptyList(), false, ledger.current().reference())
        fun control(crash: Boolean = false) = LanSyncGroupNativeApplyOwner(controlRoot, workspace, trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), LanSyncProjection(), custody::ledger, { view },
            protector::protect, protector::unprotect, observe = { stage -> if (crash && stage == "NATIVE_SOURCE_INTENT_RETAINED") {
                println("NATIVE_GROUP_WRITE_INTENT_SEALED"); System.out.flush(); Runtime.getRuntime().halt(63)
            } }, authorJournal = journal)
        fun writer() = LanSyncGroupSourceWriter(sourceRoot, workspace, identity, journal, custody::ledger, custody::key,
            { epoch -> check(epoch == key.epoch); SyncEncryptedOutbox(queueRoot, custody.key(epoch)) }, { view }, { control().state() })
        fun card(name: String) {
            val path = data.resolve("entities/character_cards/card.json"); Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(CharacterCard.serializer(), CharacterCard("card", name, createdAt = 1, updatedAt = 1)))
        }
        fun observeOwn(commit: LanSyncGroupSourceCommit) {
            val history = SyncCausalHistory(group, ledger.admission(listOf(commit.entry.version), listOf(commit.metadata)))
            check(history.offer(commit.entry.version) == SyncVersionState.ADMITTED)
            view = SyncInboxReconciliation(emptyList(), history, listOf(commit.metadata), false, ledger.current().reference())
        }
        if (writing) {
            journal.initializeNewEnrollment(); control().initializeNewEnrollment(); card("synthetic-native-original")
            val evidence = LanSyncGroupRecordEvidence(requireNotNull(view.history), requireNotNull(view.ledgerCheckpoint))
            val plan = workspace.capture(identity.fingerprint).use { LanSyncGroupNativePlanner.prepare(it, view, emptyMap(), emptyMap(), evidence) }
            val review = LanSyncGroupSourceReview.prepare(plan, view, setOf("character_cards/card"), emptyMap(), evidence)
            val operation = SyncStoreId.random(); val commit = writer().commit(review.authorize(), operation)
            check(journal.state().committedSequence == 1L); observeOwn(commit)
            Files.writeString(root.resolve("source-operation.txt"), operation.value, CREATE_NEW)
            control(crash = true).recordSource(commit)
            error("SYNTHETIC_CRASH_POINT_NOT_REACHED")
        } else {
            check(journal.state().committedSequence == 1L)
            val commit = writer().resume(SyncStoreId.parse(Files.readString(root.resolve("source-operation.txt"))))
            observeOwn(commit); check(control().state().pendingTransaction != null)
            card("synthetic-native-later-edit")
            val recovered = control().recover(); check(recovered.pendingTransaction == null)
            check(recovered.units.getValue("character_cards/card").frontiers == setOf(commit.entry.version.reference()))
            val receipt = control().recordSource(commit)
            check(receipt.status == LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED && receipt.heldUnits.isEmpty())
            check(!workspace.applied(receipt.transaction))
            val actual = json.decodeFromString(CharacterCard.serializer(), Files.readString(data.resolve("entities/character_cards/card.json")))
            check(actual.name == "synthetic-native-later-edit" && journal.state().committedSequence == 1L)
            Files.newDirectoryStream(controlRoot, "*.protected").use { files -> files.forEach {
                val encrypted = Files.readAllBytes(it).toString(Charsets.ISO_8859_1)
                check(!encrypted.contains(group.value) && !encrypted.contains(identity.fingerprint))
            } }
            println("NATIVE_GROUP_READ_RECOVERED")
        }
    }
}
