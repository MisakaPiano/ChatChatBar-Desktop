package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatDraft
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.net.Socket
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** Actual joined daily profile, coordinator callbacks, invitation/store TLS and root management.
 * File routes and platform protection are synthetic; this is not native OS/UI acceptance. */
class LanSyncGroupCoordinatorManagementRecoveryTest {
    private val network = object : LanSyncNetwork {
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
            LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port -> Socket("127.0.0.1", port) }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(25_000) { while (!condition()) delay(10) }
    private suspend fun awaitRecovery(coordinator: LanSyncGroupCoordinator, action: String,
        condition: (LanSyncGroupPanelState) -> Boolean) {
        val completed = withTimeoutOrNull(25_000) {
            while (!condition(coordinator.state.value)) delay(10)
            true
        } == true
        val state = coordinator.state.value
        assertTrue(completed, "$action: stage=${state.stage}, busy=${state.busy}, error=${state.error}, hasExport=${state.membershipRecoveryExportBinding != null}")
    }
    private class Joined(val f: LanSyncGroupAdministrationTest.Fixture, private val network: LanSyncNetwork) {
        val root = Files.createDirectory(f.root.resolve("joined-profile"))
        val data = Files.createDirectory(f.root.resolve("joined-data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(f.root.resolve("joined-workspace")), { Path.of(it) })
        private val gate = Mutex()
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = gate.withLock { work() }
            override suspend fun reload(sessionIds: Set<String>) { }
        }
        var observe: (String) -> Unit = {}
        val joining = LanSyncGroupJoining(root, workspace, platform, f.joiningIdentity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, { point -> observe(point) })
        private val founders = mutableListOf<LanSyncGroupEnrollment>()
        private fun founderAtJoinedProfile() = LanSyncGroupEnrollment(root, workspace, platform, f.joiningIdentity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }).also(founders::add)
        fun newCoordinator() = LanSyncGroupCoordinator({ Files.isRegularFile(root.resolve("joining/profile.protected")) },
            ::founderAtJoinedProfile, { joining }, network, "synthetic surviving phone")
        val coordinator = newCoordinator()
        lateinit var node: LanSyncGroupNode
        lateinit var trust: SyncGroupTrust
        suspend fun join() {
            val invitation = f.service.invitation(16384).also(f.invitations::add)
            val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket)
                .also { it.needClientAuth = true }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val offer = invitation.issue("10.0.0.1", listener.localPort)
                joining.prepare(offer.code()) { }
                val proof = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
                joining.prove("synthetic surviving phone") { current ->
                    LanSyncTls.connect(f.joiningIdentity, current.host, current.port, current.inviter, 15_000) { _, _ ->
                        Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                    }
                }
                val preview = f.service.prepareMember(proof.get(20, TimeUnit.SECONDS), invitation)
                f.service.addMember(f.service.resume(f.code), preview.acknowledge(preview.request.approvalBinding))
                val activated = joining.activate(FileCiphertextStore(f.profile.resolve("local-replica")))
                assertEquals(LanSyncGroupJoinStage.READY, activated.profile.stage)
                trust = activated.profile.trust; node = requireNotNull(activated.node)
            } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
        }
        fun author() = SyncAuthorJournal(root.resolve("joining/node/author"), trust,
            Base64.getEncoder().encodeToString(f.joiningIdentity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        fun receivingKey(): SyncDeviceEncryptionKey {
            val path = root.resolve("joining")
            val profile = lanSyncJson.parseToJsonElement(lanSyncUtf8(Files.readAllBytes(path.resolve("profile.protected")).reversedArray()))
            val retainedId = profile.jsonObject.getValue("recipient").jsonPrimitive.content
            return SyncDeviceKeyCustody(path.resolve("recipient"), f.joiningIdentity.fingerprint,
                { it.reversedArray() }, { it.reversedArray() }).open(retainedId)
        }
        suspend fun close() { coordinator.closeAndDrain(); founders.forEach { it.close() } }
    }
    private fun rootState(root: Path, code: String, vault: SyncManagementVaultBlob): SyncRootLedgerState =
        SyncManagementSession.open(code, vault.bytes()).use { session ->
            SyncRootLedgerJournal(root.resolve("root-actions"), session.trust, session.ownerCertificate,
                session::protectJournal, session::unprotectJournal).state()
        }
    private fun prove(offer: LanSyncGroupInviteOffer, identity: LanSyncTlsIdentity, key: SyncDeviceEncryptionKey) =
        LanSyncGroupInviteClient.exchange(offer, identity, key, "synthetic newly approved tablet", {
            LanSyncTls.connect(identity, offer.host, offer.port, offer.inviter, 15_000) { _, port -> Socket(InetAddress.getLoopbackAddress(), port) }
        })

    @Test fun independentManagersRetainBothSignedMembershipBranchesAndCannotResumeOrdinaryWorkAfterColdOpen() =
        actualAdministrators(receiveBeforeSecondApproval = false)

    @Test fun recoveredManagerThatSeesFirstApprovalSignsTheNextMemberFromItsNewParent() =
        actualAdministrators(receiveBeforeSecondApproval = true)

    @Test fun signedMembershipRecoveryRetainsBothBranchesAndRequiresExplicitImportAfterColdOpen() =
        actualAdministrators(receiveBeforeSecondApproval = false, recoverFork = true)

    @Test fun retainedDailyAuthorContinuesAfterRecoveryWithoutExportingDiscardedContentOrItsReference() =
        actualAdministrators(receiveBeforeSecondApproval = false, recoverFork = true, continueAuthor = true)

    private data class SignedRecovery(val bytes: ByteArray, val proof: SyncVerifiedLedgerRecoveryFile)

    private suspend fun issueRecovery(coordinator: LanSyncGroupCoordinator, joined: Joined,
        access: LanSyncGroupRuntimeAccess, parent: SyncDeviceLedgerRecord, primary: SyncDeviceLedgerRecord,
        other: SyncDeviceLedgerRecord, kept: String, excludedKey: SyncDeviceEncryptionKey): SignedRecovery {
        val groupFile = joined.root.resolve("joining/custody/group.protected")
        val originals = Files.readAllBytes(groupFile)
        val recoveryRoot = access.work.resolve("membership-recovery")
        val selected = parent.members.filterValues { it.active }.keys + kept
        val checkpoints = setOf(primary.reference(), other.reference())
        coordinator.prepareMembershipRecovery(joined.f.code)
        await { !coordinator.state.value.busy && coordinator.state.value.membershipRecovery != null }
        val review = requireNotNull(coordinator.state.value.membershipRecovery)
        assertEquals(checkpoints.map { it.id.value to it.bodyHash }.toSet(), review.heads.map { it.id to it.bodyHash }.toSet())
        val rows = review.members.associateBy { it.fingerprint }
        assertEquals(primary.members.keys + other.members.keys, rows.keys)
        assertEquals(listOf(primary.id.value), rows.getValue(kept).activeAtHeads)
        assertEquals(listOf(other.id.value), rows.getValue(excludedKey.device).activeAtHeads)
        assertEquals(LanSyncMembershipRecoveryAuthority.CHOICE, rows.getValue(excludedKey.device).authority)
        assertEquals(LanSyncMembershipRecoveryAuthority.REQUIRED_ACTIVE, rows.getValue(access.device).authority)
        assertContentEquals(originals, Files.readAllBytes(groupFile))
        assertFalse(Files.exists(recoveryRoot))
        coordinator.confirmMembershipRecovery("stale-${review.binding}", primary.id.value, selected)
        await { !coordinator.state.value.busy && coordinator.state.value.error?.contains("SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED") == true }
        assertEquals(LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT, coordinator.state.value.stage)
        assertNull(coordinator.state.value.membershipRecoveryExportBinding)
        assertFalse(Files.exists(recoveryRoot))
        assertContentEquals(originals, Files.readAllBytes(groupFile))

        coordinator.confirmMembershipRecovery(review.binding, primary.id.value, selected)
        awaitRecovery(coordinator, "signed recovery issuance") {
            !it.busy && it.stage == LanSyncGroupPanelStage.READY && it.membershipRecoveryExportBinding != null
        }
        assertNull(coordinator.state.value.membershipRecovery)
        val binding = requireNotNull(coordinator.state.value.membershipRecoveryExportBinding)
        val interrupted = CompletableDeferred<ByteArray>()
        coordinator.saveMembershipRecoveryPackage(binding) {
            interrupted.complete(it.copyOf())
            error("SYNC_GROUP_MEMBERSHIP_RECOVERY_OUTPUT_UNAVAILABLE")
        }
        val original = withTimeout(15_000) { interrupted.await() }
        await { !coordinator.state.value.busy && coordinator.state.value.error?.contains("SYNC_GROUP_MEMBERSHIP_RECOVERY_OUTPUT_UNAVAILABLE") == true }
        val retry = exportPackage(coordinator, binding)
        assertContentEquals(original, retry)
        Files.newDirectoryStream(recoveryRoot, "package-*.ccbgroupfix").use { files ->
            assertContentEquals(retry, Files.readAllBytes(files.single()))
        }
        val receiver = joined.receivingKey()
        assertEquals(parent.members.getValue(access.device).recipientKey, receiver.publicKey.encoded)
        val signed = SyncLedgerRecoveryFileCodec.read(retry.inputStream(), access.trust, receiver)
        assertEquals(binding, signed.binding)
        assertEquals(primary.reference(), signed.primary)
        assertEquals(checkpoints, signed.recovery.recovery?.parents)
        assertEquals(selected, signed.recovery.members.filterValues { it.active }.keys)
        assertTrue(signed.recovery.epoch !in signed.records.filter { it.recovery == null }.map { it.epoch })
        assertEquals(maxOf(primary.sequence, other.sequence) + 1, signed.recovery.sequence)
        assertTrue(signed.keys.any { it.cipher.epoch == signed.recovery.epoch && it.recipient == access.device })
        assertTrue(signed.records.containsAll(listOf(parent, primary, other)))
        assertEquals(access.trust.genesis, signed.records.single { it.sequence == 1L }.reference())
        assertEquals(signed.recovery, access.ledger().current())
        assertNull(access.verifiedLedgerFork())
        assertEquals("SYNC_GROUP_LEDGER_RECOVERY_FILE_RECIPIENT_REVOKED", assertFailsWith<IllegalArgumentException> {
            SyncLedgerRecoveryFileCodec.read(retry.inputStream(), access.trust, excludedKey)
        }.message)
        assertFailsWith<IllegalArgumentException> {
            SyncLedgerRecoveryFileCodec.read(retry.inputStream(), access.trust.copy(group = SyncStoreId.random()), receiver)
        }
        return SignedRecovery(retry, signed)
    }

    private suspend fun exportPackage(coordinator: LanSyncGroupCoordinator, binding: String): ByteArray {
        val result = CompletableDeferred<ByteArray>()
        coordinator.saveMembershipRecoveryPackage(binding) { result.complete(it.copyOf()) }
        val bytes = withTimeout(15_000) { result.await() }
        await { !coordinator.state.value.busy }
        assertNull(coordinator.state.value.error)
        return bytes
    }

    private suspend fun applySignedRecovery(coordinator: LanSyncGroupCoordinator, founder: LanSyncGroupAdministrationTest.Fixture,
        signed: SignedRecovery, parent: SyncDeviceLedgerRecord) {
        val groupFile = founder.profile.resolve("custody/group.protected")
        val originals = Files.readAllBytes(groupFile)
        coordinator.prepareMembershipRecoveryImport { signed.bytes.inputStream() }
        await { !coordinator.state.value.busy && coordinator.state.value.membershipRecoveryImport != null }
        val review = requireNotNull(coordinator.state.value.membershipRecoveryImport)
        assertEquals(signed.proof.binding, review.binding)
        assertEquals(parent.sequence, review.commonSequence)
        assertEquals(signed.proof.primary.id.value, review.primaryHeadId)
        assertEquals(signed.proof.recovery.recovery?.parents, review.heads.map { SyncVersionRef(SyncStoreId.parse(it.id), it.bodyHash) }.toSet())
        assertEquals(signed.proof.recovery.members.filterValues { it.active }.keys, review.activeDevices)
        assertFalse(review.remainingFork)
        assertContentEquals(originals, Files.readAllBytes(groupFile))
        assertEquals(LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT, coordinator.state.value.stage)
        coordinator.confirmMembershipRecoveryImport("stale-${review.binding}")
        await { !coordinator.state.value.busy && coordinator.state.value.error?.contains("SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED") == true }
        assertContentEquals(originals, Files.readAllBytes(groupFile))
        assertEquals(LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT, coordinator.state.value.stage)
        coordinator.confirmMembershipRecoveryImport(review.binding)
        awaitRecovery(coordinator, "explicit recovery import") {
            !it.busy && it.stage == LanSyncGroupPanelStage.READY
        }
        assertNull(coordinator.state.value.membershipRecoveryImport)
        val ledger = founder.custody().ledger()
        assertEquals(signed.proof.recovery, ledger.current())
        assertEquals(signed.proof.records.toSet(), ledger.ancestry().toSet())
        assertContentEquals(signed.bytes, exportPackage(coordinator, review.binding))
        assertTrue(coordinator.state.value.paused)
        coordinator.resumeIfConfigured()
        awaitRecovery(coordinator, "normal sync resume after explicit import") {
            !it.paused && !it.busy && it.stage == LanSyncGroupPanelStage.READY &&
                coordinator.view.value?.stage == LanSyncGroupNodeStage.READY
        }
        coordinator.unlockManagement(founder.code)
        awaitRecovery(coordinator, "normal management unlock after explicit import") { !it.busy && it.managementCanInvite }
        coordinator.createInvitation()
        awaitRecovery(coordinator, "new invitation after explicit import") { !it.busy && it.invitation != null }
        val offer = requireNotNull(coordinator.state.value.invitation)
        assertTrue(offer.code().startsWith("CCB-GROUP-JOIN2:"))
        assertEquals(ledger.current().reference(), offer.trust.checkpoint)
        coordinator.closeInvitation()
    }

    private fun actualAdministrators(receiveBeforeSecondApproval: Boolean, recoverFork: Boolean = false, continueAuthor: Boolean = false) = runBlocking {
        val root = Files.createTempDirectory("synthetic-membership-administrators-").toRealPath()
        try {
            LanSyncGroupAdministrationTest.Fixture(root).use { founder ->
                founder.seed()
                val joined = Joined(founder, network)
                val endpoints = mutableListOf<LanSyncGroupAppTransport>()
                try {
                    joined.join()
                    val a = requireNotNull(founder.service.runtimeAccess())
                    val b = requireNotNull(joined.joining.runtimeAccess())
                    val parent = a.ledger().current()
                    assertEquals(parent.reference(), b.ledger().current().reference())
                    val originals = buildMap {
                        for ((data, id) in mapOf(founder.data to "founder-original", joined.data to "other-manager-original")) {
                            val path = Files.createDirectories(data.resolve("entities/character_cards")).resolve("$id.json")
                            LanSyncJsonFiles.write(path, CharacterCard.serializer(),
                                CharacterCard(id, "synthetic private original", createdAt = 1, updatedAt = 1))
                            put(path, Files.readAllBytes(path))
                            if (recoverFork) {
                                val chat = ChatRepository(JsonFileStorage(data))
                                val session = "draft-$id"
                                chat.createSession(ChatSession(session, id, "Synthetic private conversation", createdAt = 1, updatedAt = 1))
                                chat.updateComposerDraft(ChatDraft(session, "Unsent draft for $id", updatedAt = 2))
                                val draft = data.resolve("entities/chat_drafts/$session.json")
                                put(draft, Files.readAllBytes(draft))
                            }
                        }
                    }
                    fun transport(runtime: LanSyncGroupRuntimeAccess) =
                        LanSyncGroupAppTransport(runtime, network) { _, _ -> }.also(endpoints::add)
                    suspend fun proveThrough(transport: LanSyncGroupAppTransport, invitation: LanSyncGroupInvitation,
                        identity: LanSyncTlsIdentity, key: SyncDeviceEncryptionKey = SyncDeviceEncryptionKey.create(identity.fingerprint)): LanSyncGroupInvitedPeer {
                        val recognized = CompletableDeferred<LanSyncGroupInvitedPeer>()
                        val offer = transport.invitation(invitation) { recognized.complete(it) }
                        prove(offer, identity, key)
                        val peer = withTimeout(15_000) { recognized.await() }
                        assertEquals(identity.fingerprint, peer.device)
                        return peer
                    }
                    suspend fun receiveFrom(runtime: LanSyncGroupRuntimeAccess, from: CiphertextStore) {
                        val route = runtime.reader.discoveryFrom(from, "synthetic-management-peer")
                        var cursor: CommitCursor? = null
                        do { cursor = runtime.node.discover(route, cursor).nextCursor } while (cursor != null)
                    }
                    fun remote(caller: LanSyncGroupAppTransport, host: LanSyncGroupRuntimeAccess, port: Int) =
                        caller.remote(LanSyncPeer(host.device, "synthetic previously approved member", "10.0.0.1", port))
                    fun writeChat(data: Path, session: String, message: String, body: String): Path {
                        val chat = Files.createDirectories(data.resolve("entities/chat_sessions")).resolve("$session.json")
                        if (!Files.exists(chat)) LanSyncJsonFiles.write(chat, ChatSession.serializer(),
                            ChatSession(session, "separate-card-$session", session, createdAt = 1, updatedAt = 1))
                        val record = Files.createDirectories(data.resolve("entities/chat_messages")).resolve("${session}_$message.json")
                        LanSyncJsonFiles.write(record, ChatMessage.serializer(), ChatMessage(message, session, MessageRole.USER, body,
                            createdAt = 2, updatedAt = 2))
                        return record
                    }

                    val manager = b.managementRecovery()
                    val vault = b.encryptedManagementBackup()
                    suspend fun recoverManager(checkpoint: SyncVersionRef) =
                        manager.recover(founder.code, vault.bytes(), b.reader, LanSyncManagementRecoveryAdmission {},
                            b.trust.copy(checkpoint = checkpoint))
                    if (!receiveBeforeSecondApproval) recoverManager(parent.reference())
                    val recipientC = LanSyncTlsIdentity.create()
                    val recipientD = LanSyncTlsIdentity.create()
                    val recipientDKey = SyncDeviceEncryptionKey.create(recipientD.fingerprint)
                    val originalTransport = transport(a)
                    val initialPort = originalTransport.start()
                    val initialInvite = founder.service.invitation(initialPort)
                    val c = proveThrough(originalTransport, initialInvite, recipientC)
                    val cPreview = founder.service.prepareMember(c, initialInvite)
                    val cRow = founder.service.addMember(founder.service.resume(founder.code),
                        cPreview.acknowledge(cPreview.request.approvalBinding)).ledger
                    assertEquals(parent.reference(), cRow.parent)
                    assertTrue(cRow.members.getValue(recipientC.fingerprint).active)

                    val secondary = transport(b)
                    val secondaryPort = secondary.start()
                    if (receiveBeforeSecondApproval) {
                        receiveFrom(b, remote(secondary, a, initialPort))
                        b.node.inspectLocal()
                        assertEquals(cRow.reference(), b.ledger().current().reference())
                    }
                    originalTransport.closeAndDrain()

                    val dRow = try {
                        val known = b.ledger().current().reference()
                        assertEquals(if (receiveBeforeSecondApproval) cRow.reference() else parent.reference(), known)
                        if (receiveBeforeSecondApproval) recoverManager(known)
                        val secondInvite = b.invitation(secondaryPort)
                        val d = proveThrough(secondary, secondInvite, recipientD, recipientDKey)
                        val dPreview = manager.prepareMember(d, secondInvite)
                        val result = manager.addMember(dPreview.acknowledge(dPreview.request.approvalBinding)).ledger
                        manager.publish(b.replica, b.localTarget)
                        receiveFrom(b, b.replica)
                        b.node.inspectLocal()
                        assertEquals(result.reference(), b.ledger().current().reference())
                        result
                    } finally { manager.close() }
                    assertTrue(dRow.members.getValue(recipientD.fingerprint).active)

                    val held = if (continueAuthor) {
                        writeChat(joined.data, "discarded", "original", "落选内容只由原设备保留")
                        val signed = b.node.commit(b.node.inspect().review(setOf("conversation/discarded")))
                        assertEquals(dRow.reference(), signed.entry.version.deviceLedger)
                        b.node.upload(signed.entry.request.operation, b.replica, b.localTarget)
                        signed
                    } else null
                    val heldFile = joined.data.resolve("entities/chat_messages/discarded_original.json")
                    val heldBytes = held?.let { Files.readAllBytes(heldFile) }

                    val backOnline = transport(a)
                    val reopenedPort = backOnline.start()
                    val otherStore = remote(backOnline, b, secondaryPort)
                    val membershipOnly = if (held == null) otherStore else object : CiphertextStore by otherStore {
                        override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = otherStore.listCommits(cursor, pageSize).let {
                            CommitPage(it.entries.filterNot { row -> row == held.metadata.metadata }, it.nextCursor)
                        }
                    }
                    receiveFrom(a, membershipOnly)
                    if (receiveBeforeSecondApproval) {
                        assertEquals(cRow.reference(), dRow.parent)
                        assertTrue(dRow.members.getValue(recipientC.fingerprint).active)
                        a.node.inspectLocal()
                        assertEquals(dRow.reference(), a.ledger().current().reference())
                        assertNull(a.verifiedLedgerFork())
                    } else {
                        assertEquals(parent.reference(), dRow.parent)
                        assertNotEquals(cRow.reference(), dRow.reference())
                        assertFalse(recipientC.fingerprint in dRow.members)
                        assertFalse(recipientD.fingerprint in cRow.members)
                        // Exchange both candidates before asking either participant to reconcile.
                        receiveFrom(b, remote(secondary, a, reopenedPort))
                        val live = joined.coordinator
                        live.startIfConfigured()
                        await { !live.state.value.busy && live.state.value.stage == LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT }
                        val warm = assertNotNull(live.state.value.membershipConflict)
                        assertEquals(parent.sequence, warm.commonSequence)
                        assertEquals(setOf(recipientC.fingerprint, recipientD.fingerprint), warm.branches.flatMap { it.added }.toSet())
                        assertFalse(live.state.value.managementCanInvite)
                        assertNull(live.state.value.invitation)
                        assertNull(live.actionsReview.value)
                        val blocked = runCatching { a.node.inspectLocal() }
                        if (blocked.isSuccess) assertEquals(LanSyncGroupNodeStage.TRUST_BLOCKED, blocked.getOrThrow().stage)
                        else assertEquals("SYNC_GROUP_LEDGER_FORK_OR_MISSING", blocked.exceptionOrNull()?.message)
                        val originalFork = assertNotNull(founder.custody().verifiedFork())
                        assertEquals(setOf(cRow, dRow), originalFork.branches.toSet())
                        assertEquals(cRow.reference(), founder.custody().trust().checkpoint)
                        assertFails { remote(backOnline, b, secondaryPort).listCommits(null) }

                        endpoints.forEach { it.closeAndDrain() }
                        live.closeAndDrain()
                        val cold = joined.newCoordinator()
                        val signed = try {
                            cold.startIfConfigured()
                            await { !cold.state.value.busy && cold.state.value.stage == LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT }
                            assertEquals(warm, cold.state.value.membershipConflict)
                            cold.pauseAndDrain()
                            cold.resumeIfConfigured()
                            await { !cold.state.value.paused && !cold.state.value.busy }
                            assertEquals(warm, cold.state.value.membershipConflict)
                            assertTrue(cold.state.value.status.startsWith("成员变更冲突"))
                            cold.recoverManagement(founder.code)
                            await { cold.state.value.error?.contains("SYNC_GROUP_MEMBERSHIP_CONFLICT") == true }
                            assertFalse(cold.state.value.managementUnlocked)
                            if (recoverFork) {
                                cold.pauseAndDrain()
                                issueRecovery(cold, joined, b, parent, cRow, dRow, recipientC.fingerprint, recipientDKey)
                            } else null
                        } finally { cold.closeAndDrain() }
                        if (signed == null) {
                            assertEquals(setOf(cRow, dRow), requireNotNull(b.verifiedLedgerFork()).branches.toSet())
                        } else {
                            val coldSigner = joined.newCoordinator()
                            try {
                                coldSigner.startIfConfigured()
                                await { !coldSigner.state.value.busy && coldSigner.state.value.stage == LanSyncGroupPanelStage.READY }
                                assertContentEquals(signed.bytes, exportPackage(coldSigner, signed.proof.binding))
                                assertEquals(signed.proof.recovery, b.ledger().current())
                            } finally { coldSigner.closeAndDrain() }
                        }

                        founder.service.close()
                        val founderPlatform = object : LanSyncPlatform {
                            override suspend fun exclusive(work: suspend () -> Unit) = work()
                            override suspend fun reload(sessionIds: Set<String>) {
                                if (!recoverFork) fail("Conflicted root must stay offline")
                            }
                        }
                        fun founderOwner() = LanSyncGroupEnrollment(founder.profile, founder.workspace, founderPlatform,
                            founder.identity, { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
                        fun coldFounder(): LanSyncGroupCoordinator {
                            val reopened = founderOwner()
                            return LanSyncGroupCoordinator({ true }, { reopened }, { fail("Unexpected join owner") },
                                network, "synthetic original founder")
                        }
                        val aCold = coldFounder()
                        try {
                            aCold.startIfConfigured()
                            await { !aCold.state.value.busy && aCold.state.value.stage == LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT }
                            assertEquals(warm, aCold.state.value.membershipConflict)
                            assertFalse(aCold.state.value.managementCanInvite)
                            if (signed != null) {
                                aCold.pauseAndDrain()
                                applySignedRecovery(aCold, founder, signed, parent)
                            }
                        } finally { aCold.closeAndDrain() }
                        if (held != null && signed != null) founderOwner().use { reopened ->
                            val firstA = requireNotNull(reopened.runtimeAccess()); val firstB = requireNotNull(joined.joining.runtimeAccess())
                            val old = held.entry.version
                            assertTrue(old.reference() !in signed.proof.recovery.acceptedHeads)
                            assertFalse(requireNotNull(firstA.node.inspectLocal().reconciliation.history).contains(old.reference()))
                            assertFalse(Files.exists(founder.data.resolve("entities/chat_messages/discarded_original.json")))
                            val authorOrigin = joined.root.resolve("joining/node/author/origin.protected")
                            val oldOrigin = Files.readAllBytes(authorOrigin)
                            val oldIntent = joined.root.resolve("joining/node/author/intent-${old.authorSequence.toString().padStart(19, '0')}.protected")
                            val oldFact = Files.readAllBytes(oldIntent)
                            val firstView = firstB.node.inspectLocal()
                            assertTrue("conversation/discarded" in requireNotNull(firstView.plan).recoveryHeldUnits)
                            assertTrue(held.entry.request.operation !in firstB.node.sourceOperations(eligibleForDelivery = true))
                            assertEquals(LanSyncGroupNativePlanner.RECOVERY_HELD, assertFailsWith<IllegalArgumentException> {
                                firstB.node.upload(held.entry.request.operation, firstB.replica, firstB.localTarget)
                            }.message)
                            val firstOperation = SyncStoreId.random()
                            writeChat(joined.data, "independent", "first", "恢复后第一则独立消息")
                            joined.observe = { point ->
                                if (point == "SIGNED_AUTHOR_ENTRY_RETAINED") throw IOException("SYNTHETIC_STOP_BEFORE_RECOVERED_AUTHOR_HEAD")
                            }
                            val nextView = firstB.node.inspectLocal()
                            assertEquals("SYNTHETIC_STOP_BEFORE_RECOVERED_AUTHOR_HEAD", assertFailsWith<IOException> {
                                firstB.node.commit(nextView.review(setOf("conversation/independent")), operation = firstOperation)
                            }.message)
                            joined.observe = {}
                            val secondB = requireNotNull(joined.joining.runtimeAccess())
                            secondB.node.inspectLocal()
                            val author = SyncAuthorJournal(authorOrigin.parent, secondB.trust, secondB.certificate,
                                { it.reversedArray() }, { it.reversedArray() })
                            val first = requireNotNull(author.entry(firstOperation)).version
                            assertEquals(old.authorSequence + 1, first.authorSequence)
                            assertEquals(signed.proof.recovery.reference(), first.authorRecovery)
                            assertNull(first.previousByAuthor)
                            assertTrue(old.reference() !in first.parents)
                            assertEquals(old.author, first.author)
                            val wrongAnchor = first.copy(id = SyncStoreId.random(), authorRecovery = dRow.reference(), signature = "unsigned").let {
                                it.copy(signature = Base64.getEncoder().encodeToString(founder.joiningIdentity.signSyncGroupPayload(it.signingBytes())))
                            }
                            assertEquals("SYNC_GROUP_AUTHOR_RECOVERY_AUTHORITY", assertFailsWith<IllegalArgumentException> {
                                firstA.ledger().admission(emptyList(), emptyList()).requireAuthorized(wrongAnchor)
                            }.message)
                            secondB.node.upload(firstOperation, secondB.replica, secondB.localTarget)

                            suspend fun receiveIndependent(aReceiver: LanSyncGroupRuntimeAccess, bSource: LanSyncGroupRuntimeAccess, id: String, body: String) {
                                val bTransport = transport(bSource); val aTransport = transport(aReceiver)
                                val route = remote(aTransport, bSource, bTransport.start())
                                aReceiver.reader.routes("authorproof", listOf(LanSyncGroupReplicaReader.Route("author-proof-tls", route)))
                                try {
                                    val noDiscardedVersion = object : CiphertextStore by route {
                                        override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = route.listCommits(cursor, pageSize).let {
                                            CommitPage(it.entries.filterNot { row -> row == held.metadata.metadata }, it.nextCursor)
                                        }
                                    }
                                    receiveFrom(aReceiver, noDiscardedVersion)
                                    val incoming = requireNotNull(aReceiver.node.inspect().plan)
                                    aReceiver.node.apply(incoming)
                                    val record = founder.data.resolve("entities/chat_messages/independent_$id.json")
                                    assertEquals(body, LanSyncJsonFiles.read(record, ChatMessage.serializer())?.content)
                                    assertFalse(Files.exists(founder.data.resolve("entities/chat_messages/discarded_original.json")))
                                    assertFalse(requireNotNull(aReceiver.node.inspectLocal().reconciliation.history).contains(old.reference()))
                                } finally {
                                    aReceiver.reader.removeRoutes("authorproof")
                                    aTransport.closeAndDrain(); bTransport.closeAndDrain()
                                }
                            }
                            receiveIndependent(firstA, secondB, "first", "恢复后第一则独立消息")
                            val thirdB = requireNotNull(joined.joining.runtimeAccess())
                            writeChat(joined.data, "independent", "second", "双方冷开后续发")
                            val next = thirdB.node.commit(thirdB.node.inspectLocal().review(setOf("conversation/independent")))
                            assertEquals(first.reference(), next.entry.version.previousByAuthor)
                            assertNull(next.entry.version.authorRecovery)
                            assertEquals(first.authorSequence + 1, next.entry.version.authorSequence)
                            thirdB.node.upload(next.entry.request.operation, thirdB.replica, thirdB.localTarget)
                            receiveIndependent(requireNotNull(reopened.runtimeAccess()), thirdB, "second", "双方冷开后续发")
                            val history = requireNotNull(firstA.node.inspectLocal().reconciliation.history)
                            assertFalse(history.contains(old.reference()))
                            assertContentEquals(oldOrigin, Files.readAllBytes(authorOrigin))
                            assertContentEquals(oldFact, Files.readAllBytes(oldIntent))
                            assertContentEquals(requireNotNull(heldBytes), Files.readAllBytes(heldFile))
                        }
                        if (signed != null) {
                            val reopened = coldFounder()
                            try {
                                reopened.startIfConfigured()
                                await { !reopened.state.value.busy && reopened.state.value.stage == LanSyncGroupPanelStage.READY &&
                                    reopened.view.value?.stage == LanSyncGroupNodeStage.READY }
                                assertEquals(signed.proof.recovery, founder.custody().ledger().current())
                                assertNull(reopened.state.value.membershipConflict)
                                assertContentEquals(signed.bytes, exportPackage(reopened, signed.proof.binding))
                            } finally { reopened.closeAndDrain() }
                        }
                    }
                    originals.forEach { (file, raw) -> assertContentEquals(raw, Files.readAllBytes(file)) }
                } finally { endpoints.forEach { it.closeAndDrain() }; joined.close() }
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun joinedCoordinatorRecoversOriginalManagementApprovesRealPeerAndPageLockCannotReplayOldApproval() = runBlocking {
        val root = Files.createTempDirectory("synthetic-coordinator-management-recovery-").toRealPath()
        try {
            LanSyncGroupAdministrationTest.Fixture(root).use { f ->
                f.seed(); f.rotateBeforeJoin()
                val joined = Joined(f, network)
                try {
                    joined.join()
                    val card = joined.data.resolve("entities/character_cards/retained.json"); Files.createDirectories(card.parent)
                    LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("retained", "existing daily original", createdAt = 1, updatedAt = 1))
                    val source = joined.node.commit(joined.node.inspect().review(setOf("character_cards/retained")))
                    val sourceOriginals = requireNotNull(joined.workspace.recovery.read(source.entry.request.sourceCheckpoint))
                    val raw = Files.readAllBytes(card)
                    val dailyOrigin = Files.readAllBytes(joined.root.resolve("joining/node/author/origin.protected"))
                    val nativeOrigin = Files.readAllBytes(joined.root.resolve("joining/node/native/origin.protected"))
                    val vault = joined.joining.encryptedManagementBackup()
                    val originalLedger = f.custody().ledger().current()
                    f.service.close(); Files.move(f.profile, f.root.resolve("unavailable-founder-profile"))
                    val c = joined.coordinator
                    c.startIfConfigured()
                    await { c.state.value.stage == LanSyncGroupPanelStage.READY && !c.state.value.busy && c.view.value != null }
                    assertNull(c.state.value.error)
                    assertFalse(c.state.value.localManagementAvailable)
                    assertTrue(c.state.value.managementRecoveryAvailable)
                    assertFalse(c.state.value.managementUnlocked)
                    c.recoverManagement(f.code)
                    await { c.state.value.managementUnlocked && c.state.value.managementCanInvite && !c.state.value.busy }
                    assertNull(c.state.value.error)
                    assertNull(c.state.value.recoveryCode)
                    val access = requireNotNull(joined.joining.runtimeAccess())
                    assertEquals(joined.trust.group, access.trust.group)
                    assertEquals(joined.trust.ownerCertificate, access.trust.ownerCertificate)
                    val managementRoot = access.work.resolve("recovered-management")
                    val managementOrigin = Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected"))
                    assertEquals(0L, rootState(managementRoot, f.code, vault).completedSequence)

                    val newIdentity = LanSyncTlsIdentity.create()
                    val newKey = SyncDeviceEncryptionKey.create(newIdentity.fingerprint)
                    c.createInvitation()
                    await { c.state.value.invitation != null && !c.state.value.busy }
                    val firstOffer = requireNotNull(c.state.value.invitation)
                    assertEquals(f.joiningIdentity.fingerprint, firstOffer.inviter)
                    prove(firstOffer, newIdentity, newKey)
                    await { c.state.value.peerConfirmation?.fingerprint == newIdentity.fingerprint && !c.state.value.busy }
                    val staleBinding = requireNotNull(c.state.value.peerConfirmation).binding
                    c.lockManagement()
                    assertFalse(c.state.value.managementUnlocked)
                    assertFalse(c.state.value.managementCanInvite)
                    assertNull(c.state.value.peerConfirmation)
                    assertNull(c.state.value.invitation)
                    assertNull(c.state.value.recoveryCode)
                    c.acceptPeer(staleBinding)
                    await { !c.state.value.busy && c.state.value.error?.endsWith("SYNC_GROUP_MEMBER_CONFIRMATION_REQUIRED") == true }
                    assertEquals(originalLedger, access.ledger().current())
                    assertFalse(newIdentity.fingerprint in access.ledger().current().members)
                    assertEquals(0L, rootState(managementRoot, f.code, vault).completedSequence)

                    c.recoverManagement(f.code)
                    await { c.state.value.managementUnlocked && c.state.value.managementCanInvite && !c.state.value.busy }
                    assertNull(c.state.value.error)
                    assertContentEquals(managementOrigin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))
                    c.createInvitation()
                    await { c.state.value.invitation != null && !c.state.value.busy }
                    val offer = requireNotNull(c.state.value.invitation)
                    assertNotEquals(firstOffer.token, offer.token)
                    prove(offer, newIdentity, newKey)
                    await { c.state.value.peerConfirmation?.fingerprint == newIdentity.fingerprint && !c.state.value.busy }
                    val confirmation = requireNotNull(c.state.value.peerConfirmation)
                    assertEquals("synthetic newly approved tablet", confirmation.name)
                    c.acceptPeer(confirmation.binding)
                    await { !c.state.value.busy && c.state.value.devices.any { it.id == newIdentity.fingerprint } &&
                        c.view.value?.reconciliation?.ledgerCheckpoint != originalLedger.reference() }
                    assertNull(c.state.value.error)
                    val admitted = access.ledger().current()
                    assertEquals(originalLedger.reference(), admitted.parent)
                    assertEquals(originalLedger.sequence + 1, admitted.sequence)
                    assertEquals(originalLedger.epoch, admitted.epoch)
                    assertTrue(admitted.members.getValue(newIdentity.fingerprint).active)
                    assertEquals(newKey.publicKey.encoded, admitted.members.getValue(newIdentity.fingerprint).recipientKey)
                    assertEquals("synthetic newly approved tablet", c.state.value.devices.single { it.id == newIdentity.fingerprint }.name)
                    assertFalse(c.state.value.managementUnlocked)
                    assertNull(c.state.value.recoveryCode)
                    assertEquals(1L, rootState(managementRoot, f.code, vault).completedSequence)
                    assertContentEquals(managementOrigin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))

                    // Read-only enrollment evidence comes through the coordinator's ordinary app TLS listener.
                    val remote = LanSyncGroupRemoteStore(offer.trust.group, Files.createDirectory(root.resolve("new-peer-route-cache")), {
                        LanSyncTls.connect(newIdentity, offer.host, requireNotNull(offer.bootstrap).storePort, offer.inviter, 15_000) { _, port ->
                            Socket(InetAddress.getLoopbackAddress(), port)
                        }
                    }, authenticatedConnect = true)
                    val recipient = SyncGroupCustody(Files.createDirectory(root.resolve("new-peer-custody")), joined.trust.group,
                        newKey, { it.reversedArray() }, { it.reversedArray() })
                    val inbox = SyncGroupInbox(Files.createDirectory(root.resolve("new-peer-inbox")), remote, recipient, newKey)
                    var cursor: CommitCursor? = null
                    do { cursor = inbox.discover(cursor, 2).nextCursor } while (cursor != null)
                    val evidence = inbox.prepareEnrollment(offer.trust)
                    assertEquals(SyncGroupBootstrapStage.READY, evidence.stage)
                    assertEquals(admitted, requireNotNull(evidence.evidence).records.last())
                    assertFalse(Files.exists(root.resolve("new-peer-custody/group.protected")))

                    c.recoverManagement(f.code)
                    await { c.state.value.managementUnlocked && !c.state.value.busy }
                    assertNull(c.state.value.error)
                    c.lockManagement()
                    c.acceptPeer(confirmation.binding)
                    await { !c.state.value.busy && c.state.value.error?.endsWith("SYNC_GROUP_MEMBER_CONFIRMATION_REQUIRED") == true }
                    assertEquals(admitted, access.ledger().current())
                    assertEquals(1L, rootState(managementRoot, f.code, vault).completedSequence)
                    assertContentEquals(managementOrigin, Files.readAllBytes(managementRoot.resolve("root-actions/origin.protected")))
                    assertContentEquals(dailyOrigin, Files.readAllBytes(joined.root.resolve("joining/node/author/origin.protected")))
                    assertContentEquals(nativeOrigin, Files.readAllBytes(joined.root.resolve("joining/node/native/origin.protected")))
                    assertContentEquals(raw, Files.readAllBytes(card))
                    assertEquals(1L, joined.author().state().committedSequence)
                    assertEquals(source.entry, joined.author().entry(source.entry.request.operation))
                    joined.workspace.recovery.verify(sourceOriginals)
                    assertFalse(c.state.value.managementUnlocked)
                    assertFalse(c.state.value.managementCanInvite)
                    assertNull(c.state.value.recoveryCode)
                } finally { joined.close() }
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
