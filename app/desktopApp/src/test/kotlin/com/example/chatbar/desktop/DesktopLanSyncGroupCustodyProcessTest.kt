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
        val cipher = if (writing) SyncStreamingCipher.create() else null
        val group = cipher?.group ?: SyncStoreId.parse(Files.readString(root.resolve("group-id.txt")))
        val custody = SyncGroupCustody(groupRoot, group, recipient, protector::protect, protector::unprotect)
        val content = "synthetic native protected group content".toByteArray(Charsets.US_ASCII)
        if (writing) {
            val key = requireNotNull(cipher)
            val member = SyncGroupMember(Base64.getEncoder().encodeToString(identity.certificate.encoded), recipient.publicKey.encoded)
            val genesis = SyncDeviceLedgerRecord(group, SyncStoreId.random(), null, 1, key.epoch, key.keyCommitment(), setOf("synthetic_records"),
                mapOf(identity.fingerprint to member), emptySet(), "unsigned").let { row ->
                row.copy(signature = Base64.getEncoder().encodeToString(identity.signSyncGroupPayload(row.signingBytes())))
            }
            val trust = SyncGroupTrust(group, genesis.reference(), member.certificate)
            val ledger = custody.enroll(trust, listOf(genesis))
            val capsule = SyncGroupKeyDelivery.create(ledger, key, identity.fingerprint, SyncStoreId.random(), identity::signSyncGroupPayload)
            val opened = capsule.openTentative(trust, recipient, CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(ledger)
            custody.rememberKey(opened)
            val authorRoot = Files.createDirectory(root.resolve("author"))
            val journal = SyncAuthorJournal(authorRoot, group, member.certificate, protector::protect, protector::unprotect)
            journal.initializeNewEnrollment()
            val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            val request = SyncAuthorCommitRequest(SyncStoreId.random(), genesis.reference(), emptySet(),
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
            val journal = SyncAuthorJournal(root.resolve("author"), group,
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
        println("GROUP_CUSTODY_${args[1].uppercase()}_PASS")
    }
}
