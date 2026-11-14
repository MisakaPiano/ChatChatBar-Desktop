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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Actual DPAPI/Node/WAL/author recovery across abrupt JVM loss, private synthetic roots only. */
class DesktopLanSyncResolutionProcessTest {
    @Test fun `adopted copies and one causal signature survive three actual Windows processes`() {
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("ccb-native-resolution-").toRealPath()
        val classpath = requireNotNull(System.getProperty("ccb.readonly.classpath"))
        for ((mode, exit) in listOf("adopt" to 64, "sign" to 65, "recover" to 0)) {
            val log = root.resolve("$mode.log")
            val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                DesktopLanSyncResolutionChild::class.java.name, root.toString(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            child.outputStream.close()
            try {
                assertTrue(child.waitFor(180, TimeUnit.SECONDS), "synthetic resolution process timeout")
                assertEquals(exit, child.exitValue(), Files.readString(log).take(2000))
                assertTrue(Files.readString(log).contains("NATIVE_RESOLUTION_${mode.uppercase()}_PASS"))
            } finally { if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        }
    }
}

object DesktopLanSyncResolutionChild {
    private val json = Json { encodeDefaults = true }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Path.of(args[0]).toRealPath(); check(root.fileName.toString().startsWith("ccb-native-resolution-"))
        val mode = args[1]; check(mode in setOf("adopt", "sign", "recover")); val creating = mode == "adopt"
        fun directory(parent: Path, name: String): Path = parent.resolve(name).also {
            if (creating) Files.createDirectory(it); check(Files.isDirectory(it))
        }
        val protector = WindowsDpapiSecretProtector()
        val bases = List(2) { directory(root, "node-$it") }
        val identities = bases.map { LanSyncStateStore(it.resolve("identity"), protector::protect, protector::unprotect).identity() }
        val recipients = bases.mapIndexed { index, base ->
            val owner = SyncDeviceKeyCustody(directory(base, "recipient"), identities[index].fingerprint, protector::protect, protector::unprotect)
            val pin = base.resolve("recipient-public.txt")
            if (creating) owner.createIfAbsent().also { Files.writeString(pin, it.publicKey.encoded, CREATE_NEW) }
            else owner.open(Files.readString(pin))
        }
        val initial = if (creating) SyncStreamingCipher.create() else null
        val genesisPath = root.resolve("genesis.json")
        val genesis = if (creating) {
            val cipher = requireNotNull(initial)
            val members = identities.indices.associate { index -> identities[index].fingerprint to SyncGroupMember(
                Base64.getEncoder().encodeToString(identities[index].certificate.encoded), recipients[index].publicKey.encoded) }
            val unsigned = SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch, cipher.keyCommitment(),
                LanSyncScope.schemas.keys, members, emptySet(), "unsigned")
            unsigned.copy(signature = Base64.getEncoder().encodeToString(identities[0].signSyncGroupPayload(unsigned.signingBytes()))).also {
                Files.writeString(genesisPath, json.encodeToString(SyncDeviceLedgerRecord.serializer(), it), CREATE_NEW)
            }
        } else json.decodeFromString(SyncDeviceLedgerRecord.serializer(), Files.readString(genesisPath))
        // This focused fixture retains the existing physical-owner test lineage; no new app enrollment claim.
        val trust = SyncGroupTrust(genesis.group, genesis.reference(), genesis.members.getValue(identities[0].fingerprint).certificate)
        val ledger = SyncDeviceLedger(trust).also { it.offer(genesis) }
        val backend = FileCiphertextStore(root.resolve("transport"))
        class End(val index: Int) {
            val base = bases[index]; val identity = identities[index]
            val data = directory(base, "data"); val state = directory(base, "workspace"); val runtime = directory(base, "runtime")
            val workspace = LanSyncWorkspace(data, state, { Path.of(it) })
            val custody = SyncGroupCustody(directory(base, "custody"), trust.group, recipients[index], protector::protect, protector::unprotect)
            var gated = false; var reloads = 0; var observe: (String) -> Unit = {}
            val platform = object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) {
                    check(!gated); gated = true
                    try { work() } finally { gated = false }
                }
                override suspend fun reload(sessionIds: Set<String>) { check(gated); reloads++ }
            }
            fun node() = LanSyncGroupNode(runtime, workspace, platform, identity, trust, custody, recipients[index], backend,
                protector::protect, protector::unprotect, { emptySet() }, { _, _, _ -> }, observe = { observe(it) })
            fun author() = SyncAuthorJournal(runtime.resolve("author"), trust, Base64.getEncoder().encodeToString(identity.certificate.encoded),
                protector::protect, protector::unprotect)
            fun card(name: String) {
                val path = data.resolve("entities/character_cards/card.json"); Files.createDirectories(path.parent)
                Files.writeString(path, json.encodeToString(CharacterCard.serializer(), CharacterCard("card", name, createdAt = 1, updatedAt = 1)))
            }
            fun name(unit: String = "character_cards/card") = data.resolve("entities/$unit.json").let {
                if (Files.exists(it)) json.decodeFromString(CharacterCard.serializer(), Files.readString(it)).name else null
            }
            suspend fun scan(node: LanSyncGroupNode): LanSyncGroupNodeView {
                var cursor: CommitCursor? = null
                do { cursor = node.discover(backend, cursor, 1).nextCursor } while (cursor != null)
                return node.inspect()
            }
            suspend fun send(node: LanSyncGroupNode, commit: LanSyncGroupSourceCommit) = node.upload(commit.entry.request.operation, backend,
                SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.parse("1".repeat(32))))
            suspend fun commit(node: LanSyncGroupNode): LanSyncGroupSourceCommit {
                val review = node.inspect().review(setOf("character_cards/card")); return node.commit(review, review.requiredBindings)
            }
            suspend fun enroll(): LanSyncGroupNode {
                val admitted = custody.enroll(trust, listOf(genesis))
                val capsule = SyncGroupKeyDelivery.create(ledger, requireNotNull(initial), identity.fingerprint, SyncStoreId.random(), identities[0]::signSyncGroupPayload)
                custody.rememberKey(capsule.openTentative(trust, recipients[index], CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(admitted))
                return node().also { it.initializeNewEnrollment() }
            }
        }
        val a = End(0); val b = End(1)
        val operationPath = root.resolve("resolution.txt"); val copyPath = root.resolve("copy.txt")
        val beforePath = root.resolve("pre-choice.json")
        fun halt(stage: String, code: Int): Nothing {
            println("NATIVE_RESOLUTION_${stage.uppercase()}_PASS"); System.out.flush(); Runtime.getRuntime().halt(code)
            error("SYNTHETIC_HALT_RETURNED")
        }
        if (creating) {
            val an = a.enroll(); val bn = b.enroll()
            a.card("base"); val base = a.commit(an); a.send(an, base); bn.apply(requireNotNull(b.scan(bn).plan))
            a.card("left"); val left = a.commit(an); b.card("right"); val right = b.commit(bn)
            a.send(an, left); b.send(bn, right); a.scan(an)
            Files.write(beforePath, Files.readAllBytes(a.data.resolve("entities/character_cards/card.json")), CREATE_NEW)
            val preview = an.prepareResolution(mapOf("character_cards/card" to LanSyncGroupResolutionChoice.Keep(
                LanSyncGroupResolutionSource.Version(right.entry.version.reference()), mapOf("retained" to LanSyncGroupResolutionSource.Local))))
            Files.writeString(operationPath, preview.operation, CREATE_NEW)
            Files.writeString(copyPath, (preview.resolvedUnits - "character_cards/card").single(), CREATE_NEW)
            a.observe = { if (it == "RESOLUTION_POST_SOURCE_RETAINED") halt(mode, 64) }
            an.resolve(preview.acknowledge(preview.binding, preview.requiredBindings, preview.requiredRestorations))
            error("SYNTHETIC_ADOPTION_POINT_NOT_REACHED")
        } else {
            val operation = Files.readString(operationPath); val copy = Files.readString(copyPath); val an = a.node()
            check(a.name() == "right" && a.name(copy) == "left")
            if (mode == "sign") {
                val view = an.inspect(); check(view.reservedUnits == setOf("character_cards/card", copy)); check(a.reloads >= 1)
                check(a.author().state().committedSequence == 2L && copy !in view.nativeState.units)
                check(runCatching { view.review(setOf(copy)) }.isFailure)
                var originalFound = false
                a.workspace.recovery.list().filter { it.sourceCheckpointGroup == trust.group.value }.forEach { entry ->
                    a.workspace.recovery.verify(entry)
                    a.workspace.recovery.originals(entry) { row, path ->
                        if (row.relative == "entities/character_cards/card.json" && Files.readAllBytes(path).contentEquals(Files.readAllBytes(beforePath))) originalFound = true
                    }
                }
                check(originalFound)
                a.observe = { if (it == "SIGNED_AUTHOR_ENTRY_RETAINED") halt(mode, 65) }
                an.resumeResolution(operation); error("SYNTHETIC_SIGNATURE_POINT_NOT_REACHED")
            } else {
                a.card("later edit after lost signed reply")
                val view = an.inspect(); check(view.reservedUnits.isEmpty()); check(a.author().state().committedSequence == 3L)
                val commit = requireNotNull(an.resumeResolution(operation))
                check(commit.entry.version.authorSequence == 3L && commit.entry.version.changes.keys == setOf("character_cards/card", copy))
                check(commit.entry.version.changes.getValue("character_cards/card").previous.size == 2)
                check(commit.entry.version.changes.getValue(copy).previous.isEmpty())
                check(a.name() == "later edit after lost signed reply" && a.name(copy) == "left")
                check(a.node().resumeResolution(operation)?.entry == commit.entry && a.author().state().committedSequence == 3L)
                a.send(an, commit); val bn = b.node(); bn.apply(requireNotNull(b.scan(bn).plan))
                check(b.name() == "right" && b.name(copy) == "left")
                check(a.name() == "later edit after lost signed reply")
                println("NATIVE_RESOLUTION_RECOVER_PASS")
            }
        }
    }
}
