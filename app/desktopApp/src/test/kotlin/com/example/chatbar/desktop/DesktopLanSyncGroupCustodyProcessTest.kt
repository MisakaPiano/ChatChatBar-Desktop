package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.nio.file.*
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class DesktopLanSyncGroupCustodyProcessTest {
    @Test fun `native DPAPI group trust checkpoint and actual encryption key survive separate JVM reopen`() {
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("ccb-native-group-dpapi-").toRealPath()
        val classpath = requireNotNull(System.getProperty("ccb.readonly.classpath"))
        for (mode in listOf("write", "read")) {
            val log = root.resolve("$mode.log")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                DesktopLanSyncGroupCustodyChild::class.java.name, root.toString(), mode).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            process.outputStream.close()
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "synthetic group child timeout")
                assertEquals(0, process.exitValue(), Files.readString(log).take(1000))
                assertTrue(Files.readString(log).contains("GROUP_CUSTODY_${mode.uppercase()}_PASS"))
                assertTrue(Files.readString(log).contains("ROOT_CREATION_LEDGER_${mode.uppercase()}_PASS"))
                assertTrue(Files.readString(log).contains("MANAGEMENT_VAULT_${mode.uppercase()}_PASS"))
            } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        }
    }
}

object DesktopLanSyncGroupCustodyChild {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]).toRealPath(); check(root.fileName.toString().startsWith("ccb-native-group-dpapi-"))
        val protector = WindowsDpapiSecretProtector()
        val identity = LanSyncStateStore(root.resolve("identity"), protector::protect, protector::unprotect).identity()
        val recipientRoot = root.resolve("recipient"); val groupRoot = root.resolve("group")
        val writing = args[1] == "write"; check(writing || args[1] == "read")
        if (writing) { Files.createDirectory(recipientRoot); Files.createDirectory(groupRoot) }
        val recipientCustody = SyncDeviceKeyCustody(recipientRoot, identity.fingerprint, protector::protect, protector::unprotect)
        val recipient = if (writing) recipientCustody.createIfAbsent() else recipientCustody.open(Files.readString(root.resolve("recipient-public.txt")))
        val creationRoot = root.resolve("creation"); val rootJournalPath = root.resolve("root-actions")
        if (writing) { Files.createDirectory(creationRoot); Files.createDirectory(rootJournalPath) }
        val certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
        // Synthetic recovery code stays only in this disposable root, never in uploaded reports.
        val admin = if (writing) SyncManagementSession.create() else SyncManagementSession.open(
            Files.readString(root.resolve("synthetic-management-code.txt")), Files.readAllBytes(root.resolve("management-vault.bin")))
        val adminCertificate = admin.ownerCertificate
        check(lanSyncHash(Base64.getDecoder().decode(adminCertificate)) != identity.fingerprint)
        val creation = SyncRootGroupCreation.managed(creationRoot, adminCertificate, admin.recipientPublicKey,
            certificate, recipient, admin::protectJournal, admin::unprotectJournal)
        val creationOperation = if (writing) SyncStoreId.random().also {
            Files.writeString(root.resolve("creation-operation.txt"), it.value, StandardOpenOption.CREATE_NEW)
        } else SyncStoreId.parse(Files.readString(root.resolve("creation-operation.txt")))
        val created = if (writing) creation.create(SyncRootGroupCreateRequest(creationOperation, setOf("synthetic_records"), "a".repeat(64)),
            SyncRootGroupCreateAdmission {}, admin::signRootPayload) else creation.resume(creationOperation) { error("SYNTHETIC_GENESIS_MUST_NOT_RESIGN") }
        if (writing) {
            Files.write(root.resolve("management-vault.bin"), admin.seal(created.trust).bytes(), StandardOpenOption.CREATE_NEW)
            Files.writeString(root.resolve("synthetic-management-code.txt"), admin.recoveryCode(), StandardOpenOption.CREATE_NEW)
        } else check(admin.trust == created.trust)
        val group = created.trust.group
        if (!writing) check(group.value == Files.readString(root.resolve("group-id.txt")))
        val custody = SyncGroupCustody(groupRoot, group, recipient, protector::protect, protector::unprotect)
        val rootJournal = SyncRootLedgerJournal(rootJournalPath, created.trust, adminCertificate, admin::protectJournal, admin::unprotectJournal)
        val content = "synthetic native protected group content".toByteArray(Charsets.US_ASCII)
        if (writing) {
            val initialLedger = created.enrollCreator(custody)
            rootJournal.initializeNewEnrollment(initialLedger)
            val parent = initialLedger.current()
            val rootRequest = SyncRootLedgerRequest(SyncStoreId.random(), parent.reference(), parent.members, parent.coveredTypes,
                parent.acceptedHeads, true, "b".repeat(64))
            val rootCommit = rootJournal.commit(rootRequest, custody::ledger, SyncRootLedgerAdmission {}, admin::signRootPayload)
            Files.writeString(root.resolve("root-operation.txt"), rootRequest.operation.value, StandardOpenOption.CREATE_NEW)
            val ledger = rootJournal.applyToCustody(rootCommit, custody)
            val key = custody.key(ledger.current().epoch); val trust = created.trust
            val member = ledger.current().members.getValue(identity.fingerprint)
            val capsule = SyncGroupKeyDelivery.create(ledger, key, identity.fingerprint, SyncStoreId.random(), admin::signRootPayload)
            val opened = capsule.openTentative(trust, recipient, CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(ledger)
            custody.rememberKey(opened)
            admin.close(); check(!admin.isUnlocked)
            val authorRoot = Files.createDirectory(root.resolve("author"))
            val journal = SyncAuthorJournal(authorRoot, trust, member.certificate, protector::protect, protector::unprotect)
            journal.initializeNewEnrollment()
            val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            val request = SyncAuthorCommitRequest(SyncStoreId.random(), ledger.current().reference(), emptySet(),
                mapOf("synthetic/content" to SyncUnitChange(hash, SyncPlaintextIdentity(content.size.toLong(), hash), emptySet())),
                UUID.randomUUID().toString(), hash, hash)
            journal.commitApproved(request, ledger, SyncAuthorCommitAdmission { check(it.approvalBinding == hash) }, identity::signSyncGroupPayload)
            Files.writeString(root.resolve("author-operation.txt"), request.operation.value, StandardOpenOption.CREATE_NEW)
            val blob = SyncStoreId.random()
            key.encrypt(content.inputStream(), SyncEncryptionContext(group, key.epoch, CiphertextObjectKey(CiphertextObjectKind.BLOB, blob)), root).use {
                Files.move(it.path, root.resolve("probe.ciphertext"), StandardCopyOption.ATOMIC_MOVE)
            }
            Files.writeString(root.resolve("recipient-public.txt"), recipient.publicKey.encoded, StandardOpenOption.CREATE_NEW)
            Files.writeString(root.resolve("group-id.txt"), group.value, StandardOpenOption.CREATE_NEW)
            Files.writeString(root.resolve("blob-id.txt"), blob.value, StandardOpenOption.CREATE_NEW)
            check(!Files.readAllBytes(groupRoot.resolve("group.protected")).toString(Charsets.ISO_8859_1).contains(member.certificate))
        } else {
            val ledger = custody.ledger(); val latest = ledger.current()
            check(ledger.trustAnchor.checkpoint == latest.reference())
            val key = custody.key(latest.epoch); check(key.keyCommitment() == latest.keyCommitment)
            check(latest.epoch != created.genesis.epoch && custody.key(created.genesis.epoch).keyCommitment() == created.genesis.keyCommitment)
            check(rootJournal.state().completedSequence == 1L && rootJournal.nextRecoveryRequest(custody) == null)
            val rootEntry = requireNotNull(rootJournal.entry(SyncStoreId.parse(Files.readString(root.resolve("root-operation.txt")))))
            check(rootEntry.record.reference() == latest.reference())
            check(rootJournal.commit(rootEntry.request, custody::ledger, SyncRootLedgerAdmission { error("SYNTHETIC_ROOT_MUST_NOT_REAPPROVE") },
                { error("SYNTHETIC_ROOT_MUST_NOT_RESIGN") }).entry == rootEntry)
            admin.close(); check(!admin.isUnlocked)
            val journal = SyncAuthorJournal(root.resolve("author"), ledger.trustAnchor,
                Base64.getEncoder().encodeToString(identity.certificate.encoded), protector::protect, protector::unprotect)
            check(journal.state().committedSequence == 1L)
            val entry = requireNotNull(journal.entry(SyncStoreId.parse(Files.readString(root.resolve("author-operation.txt")))))
            check(entry.version.author == identity.fingerprint && entry.version.authorSequence == 1L)
            check(journal.commitApproved(entry.request, ledger, SyncAuthorCommitAdmission { error("SYNTHETIC_MUST_NOT_REAPPROVE") }) {
                error("SYNTHETIC_MUST_NOT_RESIGN")
            } == entry)
            val blob = SyncStoreId.parse(Files.readString(root.resolve("blob-id.txt")))
            Files.newInputStream(root.resolve("probe.ciphertext")).use { input ->
                key.decrypt(input, SyncEncryptionContext(group, latest.epoch, CiphertextObjectKey(CiphertextObjectKind.BLOB, blob)), root).use {
                    check(Files.readAllBytes(it.path).contentEquals(content))
                }
            }
        }
        println("ROOT_CREATION_LEDGER_${args[1].uppercase()}_PASS")
        println("MANAGEMENT_VAULT_${args[1].uppercase()}_PASS")
        println("GROUP_CUSTODY_${args[1].uppercase()}_PASS")
    }
}
