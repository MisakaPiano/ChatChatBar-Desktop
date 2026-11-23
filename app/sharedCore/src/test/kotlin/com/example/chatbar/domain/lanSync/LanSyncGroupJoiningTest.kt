package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncGroupJoiningTest {
    private class Joiner(val founder: LanSyncGroupAdministrationTest.Fixture,
        val identity: LanSyncTlsIdentity = founder.joiningIdentity, directoryPrefix: String = "join") {
        val root = Files.createDirectory(founder.root.resolve("$directoryPrefix-profile"))
        val data = Files.createDirectory(founder.root.resolve("$directoryPrefix-data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(founder.root.resolve("$directoryPrefix-workspace")), { Path.of(it) })
        var hook: (String) -> Unit = {}; var gated = false
        @Volatile var timeShift = 0L
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }
        fun owner() = LanSyncGroupJoining(root, workspace, platform, identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, { hook(it) }, wallTime = { System.currentTimeMillis() + timeShift })
        fun protectedCoordinator(network: LanSyncNetwork) = LanSyncGroupCoordinator(
            { Files.isRegularFile(root.resolve("joining/profile.protected")) },
            { LanSyncGroupEnrollment(root, workspace, platform, identity,
                { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }) },
            ::owner, network, "合成 Android", initiallyPaused = true)
        fun control() = lanSyncJson.parseToJsonElement(Files.readAllBytes(root.resolve("joining/profile.protected")).reversedArray().toString(Charsets.UTF_8)).jsonObject
        fun projectionPath() = root.resolve("joining").resolve(control().getValue("projectionFile").jsonPrimitive.content)
        fun card(id: String, time: Long = 1) = CharacterCard(id, "合成共同预设", sourcePresetKey = "synthetic-shared-card", sourcePresetVersion = 1,
            createdAt = time, updatedAt = time)
        fun put(root: Path, card: CharacterCard): Path = root.resolve("entities/character_cards/${card.id}.json").also {
            Files.createDirectories(it.parent); Files.writeString(it, lanSyncJson.encodeToString(CharacterCard.serializer(), card))
        }
        suspend fun publishedPreset() {
            put(founder.data, card("wire-card"))
            val view = founder.node.inspect(); val commit = founder.node.commit(view.review(setOf("character_cards/wire-card")))
            founder.node.upload(commit.entry.request.operation, backend(), requireNotNull(founder.service.profile()).localReplica)
        }
        fun backend() = FileCiphertextStore(founder.profile.resolve("local-replica"))
        suspend fun prove(owner: LanSyncGroupJoining): Pair<LanSyncGroupInvitation, LanSyncGroupInvitedPeer> {
            val invitation = founder.service.invitation(16384)
            founder.invitations += invitation
            val pool = Executors.newSingleThreadExecutor()
            val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket).also { it.needClientAuth = true }
            try {
                val offer = invitation.issue("10.0.0.1", listener.localPort)
                owner.prepare(offer.code()) {}
                val server = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
                owner.prove("合成加入设备") { current ->
                    LanSyncTls.connect(identity, current.host, current.port, current.inviter, 15_000) { _, _ -> Socket(InetAddress.getLoopbackAddress(), listener.localPort) }
                }
                return invitation to server.get(20, TimeUnit.SECONDS)
            } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
        }
        suspend fun admit(pair: Pair<LanSyncGroupInvitation, LanSyncGroupInvitedPeer>) {
            val preview = founder.service.prepareMember(pair.second, pair.first)
            founder.service.addMember(founder.service.resume(founder.code), preview.acknowledge(preview.request.approvalBinding))
        }
        suspend fun revoke() {
            val preview = founder.service.prepareRevocation(founder.joiningIdentity.fingerprint)
            founder.service.revokeDevice(founder.service.resume(founder.code), preview.acknowledge(preview.request.approvalBinding))
        }
    }
    private fun fixture(work: suspend (Joiner) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("group-joining-").toRealPath()
        try { LanSyncGroupAdministrationTest.Fixture(root).use { it.seed(); work(Joiner(it)) } }
        finally { check(root.toFile().deleteRecursively()) }
    }
    private fun retainedFiles(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { file -> root.relativize(file).toString() to
            lanSyncHash(Files.readAllBytes(file)) }
    }

    private class HistoricalJoinRelay(val client: Joiner, val alternate: LanSyncGroupRuntimeAccess,
        val missingCapsule: CiphertextObjectMetadata, val published: Map<CiphertextObjectMetadata, ByteArray>) : LanSyncNetwork {
        var port = 0
        val originalDials = AtomicInteger()
        val alternateDials = AtomicInteger()
        override val discoveryEnabled = false
        override fun addresses() = listOf("10.0.0.2")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override fun discoverGroup(group: SyncStoreId, local: String, fingerprint: String): LanSyncPeer? =
            LanSyncPeer(fingerprint, "合成备选设备", addresses().single(), port).takeIf {
                group == alternate.trust.group && local == client.identity.fingerprint && fingerprint == alternate.device
            }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
            if (peer.fingerprint == client.founder.identity.fingerprint) {
                originalDials.incrementAndGet()
                throw ConnectException("SYNTHETIC_ORIGINAL_INVITER_OFFLINE")
            }
            assertEquals(alternate.device, peer.fingerprint)
            alternateDials.incrementAndGet()
            return LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 5_000) { _, port ->
                Socket(InetAddress.getLoopbackAddress(), port)
            }
        }
        fun retain(key: CiphertextObjectKey, bytes: ByteArray) {
            val descriptor = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes)))
            alternate.reader.put(CiphertextPutRequest(key, SyncStoreId.random(), descriptor), bytes.inputStream())
        }
        suspend fun admitFromPublishedCopy(includeMissingCapsule: Boolean) {
            published.forEach { (metadata, bytes) ->
                if (includeMissingCapsule || metadata != missingCapsule) retain(metadata.key, bytes)
            }
            var cursor: CommitCursor? = null
            do { cursor = alternate.node.discover(alternate.reader, cursor).nextCursor } while (cursor != null)
            assertFalse(alternate.node.reconcileForPrefetch().trustBlocked)
            assertTrue(alternate.ledger().current().members.getValue(client.identity.fingerprint).active)
        }
    }

    private fun historicalJoinRelay(work: suspend (HistoricalJoinRelay) -> Unit) = fixture { client ->
        val founder = client.founder
        founder.rotateBeforeJoin()
        client.publishedPreset()
        val alternate = Joiner(founder, LanSyncTlsIdentity.create(), "alternate")
        val alternateOwner = alternate.owner()
        alternate.admit(alternate.prove(alternateOwner))
        val alternateActivation = alternateOwner.activate(client.backend())
        assertEquals(LanSyncGroupJoinStage.READY, alternateActivation.profile.stage, alternateActivation.waitingCode)
        val access = requireNotNull(alternateOwner.runtimeAccess())

        val owner = client.owner()
        client.admit(client.prove(owner))
        val oldEpoch = founder.custody().ledger().history().first().epoch
        val source = client.backend()
        val page = source.listCommits(null)
        assertNull(page.nextCursor)
        val commits = page.entries.associateWith { metadata ->
            ByteArrayOutputStream().also { assertEquals(metadata, source.readTo(metadata.key, it, metadata.descriptor)) }.toByteArray()
        }
        val missing = commits.entries.single { (_, bytes) ->
            SyncKeyDeliveryCodec.hasPrefix(bytes) && SyncKeyDeliveryCodec.decode(bytes).context.let {
                it.recipient == client.identity.fingerprint && it.epoch == oldEpoch
            }
        }.key
        val incomplete = object : CiphertextStore by source {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int) = source.listCommits(cursor, pageSize).let {
                it.copy(entries = it.entries - missing)
            }
        }
        val partial = owner.activate(incomplete)
        assertEquals("SYNC_GROUP_BOOTSTRAP_HISTORICAL_KEY_MISSING", partial.waitingCode)
        assertEquals(LanSyncGroupJoinStage.WAITING_FOR_APPROVAL, partial.profile.stage)
        assertTrue(partial.profile.membershipVerified)
        assertNull(partial.node)
        assertFalse(Files.exists(client.root.resolve("joining/custody/group.protected")))
        val relay = HistoricalJoinRelay(client, access, missing, commits)
        val transport = LanSyncGroupAppTransport(access, relay) { _, _ -> }
        try {
            relay.port = transport.start()
            work(relay)
        } finally { transport.closeAndDrain() }
    }

    @Test fun approvedColdJoinFetchesItsMissingHistoricalKeyFromAnAuthorizedMemberOverOrdinaryTls() = historicalJoinRelay { relay ->
        val client = relay.client
        val original = client.control()
        val recipient = client.root.resolve("joining/recipient/recipient.protected")
        val protectedRecipient = Files.readAllBytes(recipient)
        relay.admitFromPublishedCopy(includeMissingCapsule = true)
        val coordinator = client.protectedCoordinator(relay)
        try {
            coordinator.resumeIfConfigured()
            val cardPath = client.data.resolve("entities/character_cards/wire-card.json")
            withTimeout(45_000) {
                coordinator.state.first { it.stage == LanSyncGroupPanelStage.READY && !it.busy }
                while (!Files.isRegularFile(cardPath)) delay(25)
            }
            assertTrue(relay.originalDials.get() > 0)
            assertTrue(relay.alternateDials.get() > 0)
            assertEquals(original.getValue("operation"), client.control().getValue("operation"))
            assertEquals(original.getValue("offer"), client.control().getValue("offer"))
            assertContentEquals(protectedRecipient, Files.readAllBytes(recipient))
            val card = assertNotNull(LanSyncJsonFiles.read(cardPath, CharacterCard.serializer()))
            assertEquals(client.card("wire-card").name, card.name)
            val joined = assertNotNull(client.owner().runtimeAccess())
            assertEquals(relay.alternate.ledger().current().reference(), joined.ledger().current().reference())
            val oldKey = SyncKeyDeliveryCodec.decode(relay.published.getValue(relay.missingCapsule)).context
            val recipientKey = SyncDeviceKeyCustody(recipient.parent, client.identity.fingerprint,
                { it.reversedArray() }, { it.reversedArray() }).open(original.getValue("recipient").jsonPrimitive.content)
            val custody = SyncGroupCustody(client.root.resolve("joining/custody"), joined.trust.group, recipientKey,
                { it.reversedArray() }, { it.reversedArray() })
            assertEquals(oldKey.keyCommitment, custody.key(oldKey.epoch).keyCommitment())
        } finally { coordinator.closeAndDrain() }
    }

    @Test fun alternateMemberMustAuthorizeTheApplicantAndSupplyItsAuthenticHistoricalCapsule() = historicalJoinRelay { relay ->
        val client = relay.client
        val owner = client.owner()
        val original = client.control()
        assertTrue(assertNotNull(owner.profile()).membershipVerified)
        assertFalse(client.identity.fingerprint in relay.alternate.ledger().current().members)
        assertFailsWith<IOException> { owner.activate(owner.discoveredBootstrapStores(relay).single()) }

        relay.admitFromPublishedCopy(includeMissingCapsule = false)
        val missing = owner.activate(owner.discoveredBootstrapStores(relay).single())
        assertEquals("SYNC_GROUP_BOOTSTRAP_HISTORICAL_KEY_MISSING", missing.waitingCode)
        val key = relay.missingCapsule.key
        val authentic = SyncKeyDeliveryCodec.decode(relay.published.getValue(relay.missingCapsule))
        val tampered = authentic.copy(signature = Base64.getEncoder().encodeToString(ByteArray(64)))
        assertEquals("SYNC_KEY_DELIVERY_SIGNATURE", assertFailsWith<IllegalArgumentException> {
            tampered.verifyOwner(relay.alternate.trust, key)
        }.message)
        relay.retain(key, SyncKeyDeliveryCodec.encode(tampered))
        val rejected = owner.activate(owner.discoveredBootstrapStores(relay).single())
        assertEquals("SYNC_GROUP_BOOTSTRAP_HISTORICAL_KEY_MISSING", rejected.waitingCode)
        assertTrue(rejected.profile.membershipVerified)
        assertNull(rejected.node)
        assertTrue(relay.alternateDials.get() >= 3)
        assertFalse(Files.exists(client.root.resolve("joining/custody/group.protected")))
        assertFalse(Files.exists(client.data.resolve("entities/character_cards/wire-card.json")))
        assertEquals(original, client.control())
    }

    @Test fun firstExplicitJoinAutomaticallySendsOneRealProofWhenProtectionStarts() = fixture { f ->
        val access = requireNotNull(f.founder.service.runtimeAccess())
        val attempts = AtomicInteger()
        val network = object : LanSyncNetwork {
            override val discoveryEnabled = false
            override fun addresses() = listOf("10.0.0.1")
            override fun accepts(address: InetAddress) = address.isLoopbackAddress
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer) = LanSyncTls.connect(
                identity, peer.host, peer.port, peer.fingerprint, 5_000) { _, port -> Socket(InetAddress.getLoopbackAddress(), port) }
            override fun connectInvitationOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit): SSLSocket {
                attempts.incrementAndGet()
                return connectOwned(identity, peer, own)
            }
        }
        val proof = CompletableDeferred<LanSyncGroupInvitedPeer>()
        val transport = LanSyncGroupAppTransport(access, network) { _, _ -> }
        val coordinator = f.protectedCoordinator(network)
        try {
            val invitation = f.founder.service.invitation(transport.start()).also(f.founder.invitations::add)
            val offer = transport.invitation(invitation) { proof.complete(it) }
            coordinator.beginJoin(offer.code())
            val waiting = withTimeout(15_000) { coordinator.state.first { it.canAbandonJoin && !it.busy } }
            assertEquals("邀请已核对，等待同步服务就绪后发送证明", waiting.status)
            assertEquals(0, attempts.get())
            assertEquals(LanSyncGroupJoinStage.PREPARED, f.owner().profile()?.stage)
            // Android calls this only after its current foreground service has promoted.
            coordinator.resumeIfConfigured()
            assertEquals(f.founder.joiningIdentity.fingerprint, withTimeout(15_000) { proof.await() }.device)
            withTimeout(15_000) { coordinator.state.first { !it.busy && !it.paused && !it.canAbandonJoin } }
            assertEquals(LanSyncGroupJoinStage.WAITING_FOR_APPROVAL, f.owner().profile()?.stage)
            assertEquals(1, attempts.get())
        } finally { coordinator.closeAndDrain(); transport.closeAndDrain() }
    }

    @Test fun ordinaryMemberCanUseAFileContainingEveryAuthenticatedHeadBeyondV1TextCapacity() = fixture { f ->
        // This separate daily author has an actual entry and retains many signed forks.
        f.admit(f.founder.prove())
        val custody = f.founder.custody()
        val checkpoint = custody.ledger().current()
        val key = custody.key(checkpoint.epoch)
        val work = Files.createDirectory(f.founder.root.resolve("large-invite-evidence"))
        val outbox = SyncEncryptedOutbox(Files.createDirectory(work.resolve("outbox")), key)
        val codec = SyncGroupObjectCodec(work)
        val heads = List(150) {
            val unsigned = SyncCausalVersion(checkpoint.group, SyncStoreId.random(), checkpoint.reference(),
                f.founder.joiningIdentity.fingerprint, 1, null, emptySet(), emptyMap(), "unsigned")
            val signed = unsigned.copy(signature = Base64.getEncoder().encodeToString(
                f.founder.joiningIdentity.signSyncGroupPayload(unsigned.signingBytes())))
            val prepared = codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, signed.id), key,
                SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = signed))
            outbox.upload(prepared.request.operationId, f.backend())
            signed.reference()
        }.toSet()
        val newDevice = LanSyncTlsIdentity.create()
        val joiner = LanSyncGroupJoining(f.root, f.workspace, f.platform, newDevice, { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val invitation = f.founder.service.invitation(16384).also(f.founder.invitations::add)
        val pool = Executors.newSingleThreadExecutor()
        val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .also { it.needClientAuth = true }
        try {
            assertEquals("SYNC_GROUP_INVITE_CODE_TOO_LARGE", assertFailsWith<IllegalArgumentException> {
                invitation.issue("10.0.0.1", listener.localPort)
            }.message)
            val offer = invitation.issue("10.0.0.1", listener.localPort, forFile = true)
            assertEquals(heads, offer.bootstrap!!.dataHeads)
            val file = Files.write(work.resolve("synthetic.ccbinvite"), invitation.fileBytes(offer))
            assertFails { LanSyncGroupInviteOffer.parse(Files.readString(file)) }
            val imported = Files.newInputStream(file).use { LanSyncGroupInviteOffer.readFile(it) }
            joiner.prepareOffer(imported) {}
            val server = pool.submit<LanSyncGroupInvitedPeer> { invitation.serve(listener.accept() as SSLSocket) }
            joiner.prove("合成普通成员") { current ->
                LanSyncTls.connect(newDevice, current.host, current.port, current.inviter, 5_000) { _, port -> Socket(InetAddress.getLoopbackAddress(), port) }
            }
            val peer = server.get(15, TimeUnit.SECONDS)
            val notApproved = joiner.activate(f.backend())
            assertNull(notApproved.node)
            assertFalse(newDevice.fingerprint in f.founder.custody().ledger().current().members)
            f.admit(invitation to peer)
            val ready = joiner.activate(f.backend())
            assertEquals(LanSyncGroupJoinStage.READY, ready.profile.stage)
            val node = requireNotNull(ready.node)
            assertEquals(heads, requireNotNull(node.inspect().reconciliation.history).heads())
            assertTrue(node.sourceOperations().isEmpty())
        } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
    }

    @Test fun explicitPauseDisarmsFirstProofWithoutChangingThePreparedIdentity() = fixture { f ->
        val attempts = AtomicInteger()
        val network = object : LanSyncNetwork {
            override val discoveryEnabled = false
            override fun addresses() = emptyList<String>()
            override fun accepts(address: InetAddress) = false
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
                attempts.incrementAndGet(); error("SYNTHETIC_MUST_NOT_DIAL")
            }
        }
        val invitation = f.founder.service.invitation(16384).also(f.founder.invitations::add)
        val coordinator = f.protectedCoordinator(network)
        try {
            coordinator.beginJoin(invitation.issue("10.0.0.1", 44000).code())
            withTimeout(15_000) { coordinator.state.first { it.canAbandonJoin && !it.busy } }
            val protected = Files.readAllBytes(f.root.resolve("joining/profile.protected"))
            coordinator.pauseAndDrain()
            coordinator.resumeIfConfigured()
            withTimeout(15_000) { coordinator.state.first { !it.busy && !it.paused && it.canAbandonJoin } }
            assertEquals(0, attempts.get())
            assertContentEquals(protected, Files.readAllBytes(f.root.resolve("joining/profile.protected")))
        } finally { coordinator.closeAndDrain() }
    }
    @Test fun aRetainedV1InvitationCannotAdmitRecoveredLedgerUntilAnExplicitFreshJoin2() = fixture { f ->
        val owner = f.owner()
        f.admit(f.prove(owner))
        val old = f.control()
        val originalOffer = lanSyncJson.decodeFromJsonElement(LanSyncGroupInviteOffer.serializer(), old.getValue("offer"))
        assertTrue(originalOffer.code().startsWith("CCB-GROUP-JOIN1:"))
        val recipientPath = f.root.resolve("joining/recipient/recipient.protected")
        val recipient = Files.readAllBytes(recipientPath)
        val sourceCustody = f.founder.custody()
        val baseline = sourceCustody.ledger()
        val parent = baseline.current()
        val fresh = SyncStreamingCipher.create(parent.group)
        val graph = f.founder.service.resume(f.founder.code).use { draft ->
            fun sign(row: SyncDeviceLedgerRecord): SyncDeviceLedgerRecord =
                row.copy(signature = Base64.getEncoder().encodeToString(draft.session.signRootPayload(row.signingBytes())))
            val left = sign(parent.copy(id = SyncStoreId.random(), parent = parent.reference(), sequence = parent.sequence + 1))
            val right = sign(left.copy(id = SyncStoreId.random()))
            val recovery = sign(left.copy(id = SyncStoreId.random(), parent = null, sequence = left.sequence + 1,
                epoch = fresh.epoch, keyCommitment = fresh.keyCommitment(),
                recovery = SyncGroupLedgerRecovery(setOf(left.reference(), right.reference()), left.reference())))
            val recovered = SyncDeviceLedger(baseline.trustAnchor)
            (baseline.ancestry() + listOf(left, right, recovery)).forEach {
                assertEquals(SyncVersionState.ADMITTED, recovered.offer(it))
            }
            val publicationRoot = Files.createDirectory(f.founder.root.resolve("recovered-join-publication"))
            val outboxes = Files.createDirectory(f.founder.root.resolve("recovered-join-outboxes"))
            fun cipher(epoch: SyncStoreId): SyncStreamingCipher = if (epoch == fresh.epoch) fresh else sourceCustody.key(epoch)
            val publication = LanSyncGroupRootPublication(publicationRoot, draft.session.ownerCertificate,
                draft.session::signRootPayload, { recovered }, ::cipher, { epoch ->
                    SyncEncryptedOutbox(Files.createDirectories(outboxes.resolve(epoch.value)), cipher(epoch))
                })
            publication.upload(f.backend(), requireNotNull(f.founder.service.profile()).localReplica)
            recovered
        }

        val waiting = owner.activate(f.backend())
        assertEquals("SYNC_GROUP_JOIN_RECOVERY_INVITATION_REQUIRED", waiting.waitingCode)
        assertEquals(LanSyncGroupJoinStage.WAITING_FOR_APPROVAL, waiting.profile.stage)
        assertFalse(waiting.profile.membershipVerified)
        assertNull(waiting.node)
        assertFalse(Files.exists(f.root.resolve("joining/custody/group.protected")))
        assertFalse(assertNotNull(f.owner().profile()).membershipVerified)
        assertEquals(old.getValue("offer"), f.control().getValue("offer"))
        assertContentEquals(recipient, Files.readAllBytes(recipientPath))

        val refreshed = LanSyncGroupInvitation(f.founder.identity, graph.trustAnchor, { graph },
            bootstrap = requireNotNull(originalOffer.bootstrap)).use { invitation ->
            invitation.issue(originalOffer.host, originalOffer.port)
        }
        assertTrue(refreshed.code().startsWith("CCB-GROUP-JOIN2:"))
        owner.refreshInvitation(refreshed.code()) {}
        val joined = owner.activate(f.backend())
        assertEquals(LanSyncGroupJoinStage.READY, joined.profile.stage, joined.waitingCode)
        assertEquals(graph.current().reference(), requireNotNull(owner.runtimeAccess()).ledger().current().reference())
        assertEquals(old.getValue("operation"), f.control().getValue("operation"))
        assertContentEquals(recipient, Files.readAllBytes(recipientPath))
    }

    @Test fun realPeerApprovalBootstrapsProtectedProfileAndUniquePresetProjectionWithoutRewritingPhysicalIdsOrAuthoring() = fixture { f ->
        f.publishedPreset()
        val local = f.put(f.data, f.card("physical-card", 987654321)); val bytes = Files.readAllBytes(local)
        val pair = f.prove(f.owner())
        val waiting = f.owner().activate(f.backend())
        assertNull(waiting.node); assertNotEquals(LanSyncGroupJoinStage.READY, waiting.profile.stage)
        assertFalse(Files.exists(f.root.resolve("joining/node/author/origin.protected")))
        f.admit(pair)
        val activated = f.owner().activate(f.backend())
        assertEquals(LanSyncGroupJoinStage.READY, activated.profile.stage)
        val node = requireNotNull(activated.node); val plan = requireNotNull(node.inspect().plan)
        assertEquals(LanSyncGroupNativeStage.READY_OBSERVATION, plan.units.getValue("character_cards/wire-card").stage)
        node.apply(plan)
        assertContentEquals(bytes, Files.readAllBytes(local)); assertFalse(Files.exists(f.data.resolve("entities/character_cards/wire-card.json")))
        assertNotNull(f.owner().open())
        val record = lanSyncJson.parseToJsonElement(Files.readAllBytes(f.root.resolve("joining/profile.protected")).reversedArray().toString(Charsets.UTF_8)).jsonObject
        val recipient = SyncDeviceKeyCustody(f.root.resolve("joining/recipient"), f.founder.joiningIdentity.fingerprint, { it.reversedArray() }, { it.reversedArray() })
            .open(record.getValue("recipient").jsonPrimitive.content)
        val author = SyncAuthorJournal(f.root.resolve("joining/node/author"), activated.profile.trust,
            java.util.Base64.getEncoder().encodeToString(f.founder.joiningIdentity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        assertEquals(0L, author.state().committedSequence)
        assertEquals(f.founder.joiningIdentity.fingerprint, recipient.device)
        Files.move(f.root.resolve("joining/node/native/head.protected"), f.root.resolve("joining/lost-head.protected"))
        assertFails { f.owner().open() }
        assertFalse(Files.exists(f.root.resolve("joining/node/native/head.protected")))
    }
    @Test fun nativeEnrollmentReplyLossRetainsProjectionAndIncarnationWhileLaterLocalEditsSurviveRetry() = fixture { f ->
        f.publishedPreset(); val local = f.put(f.data, f.card("physical-card"))
        f.admit(f.prove(f.owner()))
        var interrupted = false
        f.hook = { if (it == "GROUP_JOIN_NATIVE_ENROLLED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_JOIN_REPLY_LOST") } }
        assertFailsWith<IOException> { f.owner().activate(f.backend()) }; assertTrue(interrupted)
        val origin = Files.readAllBytes(f.root.resolve("joining/node/native/origin.protected"))
        val projection = Files.readAllBytes(f.projectionPath())
        f.hook = {}; f.put(f.data, f.card("physical-card").copy(basicSetting = "加入后继续编辑"))
        val later = Files.readAllBytes(local)
        assertEquals(LanSyncGroupJoinStage.READY, f.owner().activate(f.backend()).profile.stage)
        assertContentEquals(origin, Files.readAllBytes(f.root.resolve("joining/node/native/origin.protected")))
        assertContentEquals(projection, Files.readAllBytes(f.projectionPath()))
        assertContentEquals(later, Files.readAllBytes(local))
    }
    @Test fun missingAdmittedCustodyRefusesAnExplicitRetryInsteadOfReenrollingFromOldCapsules() = fixture { f ->
        f.admit(f.prove(f.owner())); var interrupted = false
        f.hook = { if (it == "GROUP_JOIN_KEYS_ADMITTED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_JOIN_KEYS_REPLY_LOST") } }
        assertFailsWith<IOException> { f.owner().activate(f.backend()) }; assertTrue(interrupted)
        f.hook = {}; val file = f.root.resolve("joining/custody/group.protected")
        Files.move(file, file.resolveSibling("lost-group.protected"))
        assertEquals("SYNC_GROUP_JOIN_CUSTODY_MISSING", assertFailsWith<IllegalArgumentException> { f.owner().activate(f.backend()) }.message)
        assertFalse(Files.exists(file)); assertFalse(Files.exists(f.root.resolve("joining/node/author/origin.protected")))
    }
    @Test fun expiredUnusedInviteAndAnUncertainAttemptRefreshTheCapabilityWithoutChangingRecipientOrOperation() = fixture { f ->
        val invitation = f.founder.service.invitation(16384).also(f.founder.invitations::add)
        val offer = invitation.issue("10.0.0.1", 44000); f.owner().prepare(offer.code()) {}
        val before = f.control(); val key = Files.readAllBytes(f.root.resolve("joining/recipient/recipient.protected"))
        var connections = 0; f.timeShift = 240_000
        assertFails { f.owner().prove("synthetic") { connections++; error("MUST_NOT_CONNECT") } }
        assertEquals(0, connections)
        val expired = assertNotNull(f.owner().profile())
        assertEquals(LanSyncGroupJoinStage.PREPARED, expired.stage)
        assertTrue(expired.invitationExpired)
        f.timeShift = 0
        val pool = Executors.newSingleThreadExecutor()
        val listener = (invitation.invitationContext().serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket).also { it.needClientAuth = true }
        try {
            val fresh = invitation.issue("10.0.0.2", listener.localPort)
            assertFalse(f.owner().refreshInvitation(fresh.code()) {}.invitationExpired)
            assertEquals(before.getValue("operation"), f.control().getValue("operation"))
            assertEquals(before.getValue("recipient"), f.control().getValue("recipient"))
            assertContentEquals(key, Files.readAllBytes(f.root.resolve("joining/recipient/recipient.protected")))
            val server = pool.submit<Boolean> { runCatching { invitation.serve(listener.accept() as SSLSocket) }.isFailure }
            f.hook = { if (it == "GROUP_JOIN_PROOF_ATTEMPT_RETAINED") throw IOException("SYNTHETIC_UNKNOWN_PROOF_ATTEMPT") }
            assertFailsWith<IOException> {
                f.owner().prove("synthetic") { current ->
                    connections++
                    LanSyncTls.connect(f.founder.joiningIdentity, current.host, current.port, current.inviter, 15_000) { _, _ ->
                        Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                    }
                }
            }
            assertTrue(server.get(20, TimeUnit.SECONDS))
        } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
        f.hook = {}; assertEquals(LanSyncGroupJoinStage.PROOF_ATTEMPTED, f.owner().profile()!!.stage)
        assertFalse(f.owner().profile()!!.canAbandon)
        assertFailsWith<IllegalArgumentException> { f.owner().abandonUnprovedInvitation() }
        val next = invitation.issue("10.0.0.3", 44002)
        f.owner().refreshInvitation(next.code()) {}
        assertEquals(LanSyncGroupJoinStage.PROOF_ATTEMPTED, f.owner().profile()!!.stage)
        val alienGroup = SyncStoreId.random()
        val alien = next.copy(trust = next.trust.copy(group = alienGroup), bootstrap = next.bootstrap!!.copy(vault = next.bootstrap!!.vault.copy(group = alienGroup)))
        assertFails { f.owner().refreshInvitation(alien.code()) {} }
        assertEquals(before.getValue("operation"), f.control().getValue("operation"))
        assertContentEquals(key, Files.readAllBytes(f.root.resolve("joining/recipient/recipient.protected"))); assertEquals(1, connections)
    }
    @Test fun failedDialCanArchiveTheExactUnprovedApplicationAndPrepareAgainAfterColdOpen() = fixture { f ->
        val invitation = f.founder.service.invitation(16384).also(f.founder.invitations::add)
        val offer = invitation.issue("10.0.0.1", 44000)
        val prepared = f.owner().prepare(offer.code()) {}
        val root = f.root.resolve("joining")
        val operation = f.control().getValue("operation").jsonPrimitive.content
        val profile = Files.readAllBytes(root.resolve("profile.protected"))
        val recipient = Files.readAllBytes(root.resolve("recipient/recipient.protected"))
        assertFailsWith<IOException> { f.owner().prove("synthetic") { throw IOException("SYNTHETIC_JOIN_DIAL_FAILED") } }
        assertEquals(prepared, f.owner().profile())
        assertTrue(prepared.canAbandon)
        f.hook = { if (it == "UNPROVED_JOIN_ARCHIVED") throw IOException("SYNTHETIC_ARCHIVE_REPLY_LOST") }
        assertFailsWith<IOException> { f.owner().abandonUnprovedInvitation() }
        f.hook = {}
        assertNull(f.owner().profile())
        assertFalse(Files.exists(root))
        val archive = f.root.resolve("abandoned-joins").resolve(operation)
        assertContentEquals(profile, Files.readAllBytes(archive.resolve("profile.protected")))
        assertContentEquals(recipient, Files.readAllBytes(archive.resolve("recipient/recipient.protected")))
        val replacement = invitation.issue("10.0.0.2", 44001)
        val next = f.owner().prepare(replacement.code()) {}
        assertEquals(prepared.device, next.device)
        assertNotEquals(operation, f.control().getValue("operation").jsonPrimitive.content)
        assertContentEquals(recipient, Files.readAllBytes(archive.resolve("recipient/recipient.protected")))
    }

    private fun legacyAttempt(f: Joiner): ByteArray {
        val path = f.root.resolve("joining/profile.protected")
        val legacy = JsonObject(f.control() + ("stage" to JsonPrimitive(LanSyncGroupJoinStage.PROOF_ATTEMPTED.name)))
            .toString().toByteArray(Charsets.UTF_8).reversedArray()
        Files.write(path, legacy)
        return legacy
    }

    @Test fun legacyPreDialAttemptCanBeShelvedWhileASecondGroupIsPreparedThenRestoredExactly() = fixture { f ->
        val owner = f.owner()
        val invitation = f.founder.service.invitation(16384).also(f.founder.invitations::add)
        val offer = invitation.issue("10.0.0.1", 44000)
        val original = owner.prepare(offer.code()) {}
        val legacy = legacyAttempt(f)
        val root = f.root.resolve("joining")
        val recipient = Files.readAllBytes(root.resolve("recipient/recipient.protected"))
        assertFalse(owner.profile()!!.canAbandon)
        assertTrue(owner.profile()!!.canShelve)
        owner.shelveUnsettledInvitation()
        assertNull(f.owner().profile())
        val shelved = f.owner().shelvedInvitations().single()
        assertEquals(original.trust.group.value, shelved.group)
        assertFailsWith<IllegalArgumentException> { owner.prepare(offer.code()) {} }
        val other = SyncStoreId.random()
        val bootstrap = requireNotNull(offer.bootstrap)
        val alternative = offer.copy(trust = offer.trust.copy(group = other),
            bootstrap = bootstrap.copy(vault = bootstrap.vault.copy(group = other)))
        assertEquals(other, owner.prepare(alternative.code()) {}.trust.group)
        assertFailsWith<IllegalArgumentException> { owner.restoreShelvedInvitation(shelved.operation) }
        owner.abandonUnprovedInvitation()
        val restored = f.owner().restoreShelvedInvitation(shelved.operation)
        assertEquals(original.device, restored.device)
        assertContentEquals(legacy, Files.readAllBytes(root.resolve("profile.protected")))
        assertContentEquals(recipient, Files.readAllBytes(root.resolve("recipient/recipient.protected")))
        assertTrue(owner.shelvedInvitations().isEmpty())
    }

    @Test fun possiblyApprovedLegacyAttemptRestoresTheExactRecipientAndCompletesWithoutASecondApproval() = fixture { f ->
        f.admit(f.prove(f.owner()))
        val legacy = legacyAttempt(f)
        val owner = f.owner()
        val original = owner.profile()!!
        owner.shelveUnsettledInvitation()
        val shelved = owner.shelvedInvitations().single()
        f.owner().restoreShelvedInvitation(shelved.operation)
        assertContentEquals(legacy, Files.readAllBytes(f.root.resolve("joining/profile.protected")))
        val joined = f.owner().activate(f.backend())
        assertEquals(LanSyncGroupJoinStage.READY, joined.profile.stage)
        assertEquals(original.device, joined.profile.device)
        assertFalse(joined.profile.canShelve)
        assertFailsWith<IllegalArgumentException> { f.owner().shelveUnsettledInvitation() }
        assertEquals(0, requireNotNull(joined.node).sourceOperations().size)
    }

    @Test fun approvedJoinCanContinueAtAMovedPinnedStoreWithoutChangingItsOfferOrRecipient() = fixture { f ->
        val proof = f.prove(f.owner())
        val owner = f.owner()
        val profile = f.control()
        val originalKey = Files.readAllBytes(f.root.resolve("joining/recipient/recipient.protected"))
        val originalHost = requireNotNull(profile["offer"]).jsonObject.getValue("host").jsonPrimitive.content
        val inviter = requireNotNull(profile["offer"]).jsonObject.getValue("inviter").jsonPrimitive.content
        val access = requireNotNull(f.founder.service.runtimeAccess())
        var servingPort = 0
        var wrongHint = true
        val network = object : LanSyncNetwork {
            override val discoveryEnabled = false
            override fun addresses() = listOf("10.0.0.2")
            override fun accepts(address: InetAddress) = address.isLoopbackAddress
            override fun discoverGroup(group: SyncStoreId, local: String, fingerprint: String): LanSyncPeer {
                assertEquals(access.trust.group, group)
                assertEquals(f.founder.joiningIdentity.fingerprint, local)
                assertEquals(inviter, fingerprint)
                return LanSyncPeer(if (wrongHint) "0".repeat(64) else fingerprint, "synthetic", "10.0.0.2", servingPort)
            }
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
                if (peer.host == originalHost) throw java.net.ConnectException("synthetic moved endpoint")
                return LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port ->
                    Socket(InetAddress.getLoopbackAddress(), port)
                }
            }
        }
        val transport = LanSyncGroupAppTransport(access, network) { _, _ -> }
        try {
            servingPort = transport.start()
            assertFailsWith<LanSyncJoinRouteFailure> { owner.activate(owner.bootstrapStore(network)) }
            assertTrue(owner.discoveredBootstrapStores(network).isEmpty())
            wrongHint = false
            // The ordinary authenticated store rejects the still-unapproved client at this same address.
            assertFailsWith<IOException> { owner.activate(owner.discoveredBootstrapStores(network).single()) }
            assertEquals(LanSyncGroupJoinStage.WAITING_FOR_APPROVAL, owner.profile()?.stage)
            assertFalse(requireNotNull(owner.profile()).membershipVerified)
            f.admit(proof)
            val active = owner.activate(owner.discoveredBootstrapStores(network).single())
            assertEquals(LanSyncGroupJoinStage.READY, active.profile.stage)
            assertEquals(profile.getValue("offer"), f.control().getValue("offer"))
            assertEquals(profile.getValue("operation"), f.control().getValue("operation"))
            assertContentEquals(originalKey, Files.readAllBytes(f.root.resolve("joining/recipient/recipient.protected")))
            assertTrue(requireNotNull(active.node).sourceOperations().isEmpty())
        } finally { transport.closeAndDrain() }
    }
    @Test fun legacyAttemptWithLocallyRetainedSignedApprovalCannotBeShelvedWhileOffline() = fixture { f ->
        f.admit(f.prove(f.owner()))
        val owner = f.owner()
        val backend = f.backend()
        val interruptedScan = object : CiphertextStore by backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                if (cursor != null) throw IOException("SYNTHETIC_SOURCE_OFFLINE")
                val page = backend.listCommits(null, pageSize)
                assertNull(page.nextCursor)
                return page.copy(nextCursor = CommitCursor("synthetic-next-page"))
            }
        }
        assertFailsWith<IOException> { owner.activate(interruptedScan) }
        legacyAttempt(f)
        val root = f.root.resolve("joining")
        val recipient = Files.readAllBytes(root.resolve("recipient/recipient.protected"))
        assertFalse(Files.exists(root.resolve("custody/group.protected")))
        assertEquals("SYNC_GROUP_JOIN_HAS_LOCAL_ADMISSION", assertFailsWith<IllegalArgumentException> {
            f.owner().shelveUnsettledInvitation()
        }.message)
        val retained = requireNotNull(f.owner().profile())
        assertTrue(retained.membershipVerified)
        assertFalse(retained.canShelve)
        assertContentEquals(recipient, Files.readAllBytes(root.resolve("recipient/recipient.protected")))
        assertFalse(Files.exists(root.resolve("custody/group.protected")))
        assertEquals(LanSyncGroupJoinStage.READY, f.owner().activate(backend).profile.stage)
    }
    @Test fun verifiedRevocationCanArchiveOnlyAnUnactivatedApplicationAndKeepTheOriginalBytes() = fixture { f ->
        val owner = f.owner()
        f.admit(f.prove(owner))
        f.revoke()
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", owner.activate(f.backend()).waitingCode)
        val cold = f.owner()
        assertTrue(cold.profile()!!.canArchiveRevoked)
        assertFalse(cold.profile()!!.canShelve)
        val root = f.root.resolve("joining")
        assertFalse(Files.exists(root.resolve("custody/group.protected")))
        val record = Files.readAllBytes(root.resolve("profile.protected"))
        val recipient = Files.readAllBytes(root.resolve("recipient/recipient.protected"))
        val archive = cold.archiveRevokedInvitation()
        assertNull(f.owner().profile())
        assertContentEquals(record, Files.readAllBytes(archive.resolve("profile.protected")))
        assertContentEquals(recipient, Files.readAllBytes(archive.resolve("recipient/recipient.protected")))
    }

    @Test fun revokedJoinWithAdmittedKeysAndMissingInitialDataArchivesWithoutReusingItsRecipient() = fixture { f ->
        val root = f.root.resolve("joining")
        val backend = f.backend()
        val initial = backend.listCommits(null).entries.map { it.key }.toSet()
        f.publishedPreset()
        val withheld = backend.listCommits(null).entries.map { it.key }.toSet() - initial
        assertTrue(withheld.isNotEmpty())
        val owner = f.owner()
        f.admit(f.prove(owner))
        val incomplete = object : CiphertextStore by backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                val page = backend.listCommits(cursor, pageSize)
                return page.copy(entries = page.entries.filterNot { it.key in withheld })
            }
        }
        val awaiting = owner.activate(incomplete)
        assertEquals("SYNC_GROUP_JOIN_INITIAL_DATA_MISSING", awaiting.waitingCode)
        assertEquals(LanSyncGroupJoinStage.KEYS_ADMITTED, awaiting.profile.stage)
        assertTrue(awaiting.profile.membershipVerified)
        assertFalse(awaiting.profile.membershipRevoked)
        assertFalse(awaiting.profile.canArchiveRevoked)
        assertFalse(awaiting.profile.canShelve)
        assertFailsWith<IllegalArgumentException> { owner.archiveRevokedInvitation() }
        assertTrue(Files.isRegularFile(root.resolve("custody/group.protected")))
        assertFalse(Files.exists(root.resolve("node")))
        val originalRecipient = f.control().getValue("recipient")

        f.revoke()
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", owner.activate(incomplete).waitingCode)
        val cold = f.owner()
        val revoked = assertNotNull(cold.profile())
        assertEquals(awaiting.profile.localReplica, revoked.localReplica)
        assertTrue(revoked.membershipRevoked && revoked.canArchiveRevoked)
        val retained = retainedFiles(root)
        val archive = cold.archiveRevokedInvitation()
        assertEquals(retained, retainedFiles(archive))
        assertNull(cold.profile())

        LanSyncGroupAdministrationTest.Fixture(Files.createDirectory(f.founder.root.resolve("another-group"))).use { next ->
            next.seed()
            next.service.invitation(16384).use { invitation ->
                val prepared = cold.prepare(invitation.issue("10.0.0.1", 16385).code()) {}
                assertNotEquals(revoked.trust.group, prepared.trust.group)
                assertEquals(revoked.device, prepared.device)
                assertNotEquals(originalRecipient, f.control().getValue("recipient"))
                assertFalse(Files.exists(root.resolve("custody/group.protected")))
            }
        }
        assertEquals(retained, retainedFiles(archive))
    }

    @Test fun revokedJoinWithOnlyNodePreparationArchivesAfterALocalEditInterruptedEnrollment() = fixture { f ->
        val originalCard = f.card("physical-card")
        val card = f.put(f.data, originalCard)
        val owner = f.owner()
        f.admit(f.prove(owner))
        f.hook = { event ->
            if (event == "GROUP_JOIN_PROJECTION_RETAINED") f.put(f.data, originalCard.copy(name = "准备启用时的本机编辑"))
        }
        assertEquals("SYNC_GROUP_ENROLLMENT_LOCAL_CHANGED", assertFailsWith<IllegalArgumentException> {
            owner.activate(f.backend())
        }.message)
        f.hook = {}
        val root = f.root.resolve("joining")
        assertTrue(Files.isRegularFile(root.resolve("node/inbox/ciphertext-mirror/store.id")))
        assertFalse(Files.exists(root.resolve("node/author/origin.protected")))
        assertFalse(Files.exists(root.resolve("node/native/origin.protected")))
        val localEdit = Files.readAllBytes(card)
        assertEquals(LanSyncGroupJoinStage.KEYS_ADMITTED, owner.profile()?.stage)
        f.revoke()
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", owner.activate(f.backend()).waitingCode)
        val uncertain = root.resolve("node/author/unrecognized-evidence")
        Files.writeString(uncertain, "synthetic unclassified writer evidence")
        assertFalse(assertNotNull(f.owner().profile()).canArchiveRevoked)
        assertEquals("SYNC_GROUP_JOIN_REVOKED_ARCHIVE_NOT_AVAILABLE", assertFailsWith<IllegalArgumentException> {
            f.owner().archiveRevokedInvitation()
        }.message)
        Files.delete(uncertain)

        val cold = f.owner()
        val revoked = assertNotNull(cold.profile())
        assertTrue(revoked.membershipRevoked && revoked.canArchiveRevoked)
        val originals = retainedFiles(root)
        val archive = cold.archiveRevokedInvitation()
        assertEquals(originals, retainedFiles(archive))
        assertContentEquals(localEdit, Files.readAllBytes(card))
        assertNull(cold.profile())
    }

    @Test fun verifiedRevocationCannotArchiveAnAuthorOriginWrittenBeforeTheNativeStageMarker() = fixture { f ->
        val owner = f.owner()
        f.admit(f.prove(owner))
        f.hook = { if (it == "AUTHOR_ORIGIN_RETAINED") throw IOException("SYNTHETIC_JOIN_NATIVE_REPLY_LOST") }
        assertEquals("SYNTHETIC_JOIN_NATIVE_REPLY_LOST", assertFailsWith<IOException> { owner.activate(f.backend()) }.message)
        f.hook = {}
        val root = f.root.resolve("joining")
        assertTrue(Files.isRegularFile(root.resolve("node/author/origin.protected")))
        assertEquals(LanSyncGroupJoinStage.KEYS_ADMITTED, owner.profile()?.stage)
        f.revoke()
        val stopped = owner.activate(f.backend())
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", stopped.waitingCode)
        assertTrue(stopped.profile.membershipRevoked)
        val retained = retainedFiles(root)
        val cold = f.owner()
        assertFalse(assertNotNull(cold.profile()).canArchiveRevoked)
        assertEquals("SYNC_GROUP_JOIN_REVOKED_ARCHIVE_NOT_AVAILABLE", assertFailsWith<IllegalArgumentException> {
            cold.archiveRevokedInvitation()
        }.message)
        assertEquals(retained, retainedFiles(root))
    }

    private suspend fun publishRevocationSibling(f: Joiner, pending: LanSyncGroupJoiningProfile) {
        f.revoke()
        val revokedLedger = f.founder.custody().ledger()
        val original = revokedLedger.current()
        val unsigned = original.copy(id = SyncStoreId.random(), signature = "unsigned")
        f.founder.service.resume(f.founder.code).use { draft ->
            val sign = draft.session::signRootPayload
            val sibling = unsigned.copy(signature = Base64.getEncoder().encodeToString(sign(unsigned.signingBytes())))
            val ledger = SyncDeviceLedger(pending.trust).also { fork ->
                revokedLedger.history().dropLast(1).forEach { fork.offer(it) }
                fork.offer(sibling)
            }
            assertEquals(sibling.reference(), ledger.current().reference())
            val notice = SyncLedgerNotice.create(ledger, pending.device, SyncStoreId.random(), sign)
            val payload = SyncLedgerNoticeCodec.encode(notice)
            val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, notice.context.objectId)
            val descriptor = CiphertextDescriptor(payload.size.toLong(), CiphertextSha256.parse(lanSyncHash(payload)))
            f.backend().put(CiphertextPutRequest(key, notice.context.objectId, descriptor), payload.inputStream())
        }
    }

    @Test fun coldSignedLedgerForkKeepsTheSameApplicationForExplicitRecheckWithoutShelving() = fixture { f ->
        val owner = f.owner()
        f.admit(f.prove(owner))
        val pending = assertNotNull(owner.profile())
        publishRevocationSibling(f, pending)
        assertEquals("SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT", owner.activate(f.backend()).waitingCode)
        val root = f.root.resolve("joining")
        val retained = retainedFiles(root)
        val cold = f.owner()
        val conflicted = assertNotNull(cold.profile())
        assertEquals(pending.stage, conflicted.stage)
        assertEquals(pending.trust, conflicted.trust)
        assertEquals(pending.device, conflicted.device)
        assertEquals(pending.localReplica, conflicted.localReplica)
        assertTrue(conflicted.ledgerConflict)
        assertFalse(conflicted.canAbandon || conflicted.canShelve || conflicted.canArchiveRevoked)
        assertEquals(conflicted, cold.resumePreparation())
        assertEquals(retained, retainedFiles(root))
        assertEquals("SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT", assertFailsWith<IllegalArgumentException> {
            cold.shelveUnsettledInvitation()
        }.message)
        assertEquals("SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT", assertFailsWith<IllegalArgumentException> {
            cold.archiveRevokedInvitation()
        }.message)
        val retried = cold.activate(f.backend())
        assertEquals("SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT", retried.waitingCode)
        assertEquals(conflicted, retried.profile)
        assertNull(retried.node)
        assertFalse(Files.exists(root.resolve("custody/group.protected")))
        assertEquals(retained, retainedFiles(root))
    }
    @Test fun aLegacyAttemptWithVerifiedMembershipButMissingHistoricalKeysShowsTheSignedForkWhenReopened() = fixture { f ->
        f.founder.rotateBeforeJoin()
        val historicalEpoch = f.founder.custody().ledger().history().first().epoch
        val owner = f.owner()
        f.admit(f.prove(owner))
        val backend = f.backend()
        val withheld = backend.listCommits(null).entries.filter { metadata ->
            val output = ByteArrayOutputStream()
            assertNotNull(backend.readTo(metadata.key, output, metadata.descriptor))
            val bytes = output.toByteArray()
            if (!SyncKeyDeliveryCodec.hasPrefix(bytes)) false else SyncKeyDeliveryCodec.decode(bytes).context.let { context ->
                context.epoch == historicalEpoch && context.recipient == f.founder.joiningIdentity.fingerprint
            }
        }.map { it.key }.toSet()
        assertTrue(withheld.isNotEmpty())
        val incomplete = object : CiphertextStore by backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage =
                backend.listCommits(cursor, pageSize).let { page -> page.copy(entries = page.entries.filterNot { it.key in withheld }) }
        }
        val awaiting = owner.activate(incomplete)
        assertEquals("SYNC_GROUP_BOOTSTRAP_HISTORICAL_KEY_MISSING", awaiting.waitingCode)
        assertTrue(awaiting.profile.membershipVerified)
        legacyAttempt(f)
        val pending = assertNotNull(f.owner().profile())
        assertEquals(LanSyncGroupJoinStage.PROOF_ATTEMPTED, pending.stage)
        assertTrue(pending.membershipVerified)
        assertRetainedForkAfterApproval(f, pending, incomplete)
        assertFalse(Files.exists(f.root.resolve("joining/custody/group.protected")))
    }

    @Test fun aJoinWithAdmittedKeysShowsTheSignedForkWhenReopenedBeforeNativeEnrollment() = fixture { f ->
        val owner = f.owner()
        f.admit(f.prove(owner))
        f.hook = { if (it == "GROUP_JOIN_KEYS_ADMITTED") throw IOException("SYNTHETIC_PAUSE_BEFORE_NATIVE_ENROLLMENT") }
        assertEquals("SYNTHETIC_PAUSE_BEFORE_NATIVE_ENROLLMENT", assertFailsWith<IOException> { owner.activate(f.backend()) }.message)
        f.hook = {}
        val pending = assertNotNull(f.owner().profile())
        val custody = f.root.resolve("joining/custody/group.protected")
        val admittedBytes = Files.readAllBytes(custody)
        assertEquals(LanSyncGroupJoinStage.KEYS_ADMITTED, pending.stage)
        assertTrue(pending.membershipVerified)
        assertRetainedForkAfterApproval(f, pending, f.backend())
        assertContentEquals(admittedBytes, Files.readAllBytes(custody))
    }

    private suspend fun assertRetainedForkAfterApproval(f: Joiner, pending: LanSyncGroupJoiningProfile, source: CiphertextStore) {
        val offline = object : CiphertextStore by source {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = throw IOException("SYNTHETIC_SOURCE_OFFLINE")
        }
        val owner = f.owner()
        val root = f.root.resolve("joining")
        val control = f.control()
        val recipient = Files.readAllBytes(root.resolve("recipient/recipient.protected"))
        assertFailsWith<IOException> { owner.activate(offline) }
        assertEquals(pending, f.owner().profile())
        publishRevocationSibling(f, pending)
        val result = owner.activate(source)
        assertEquals("SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT", result.waitingCode)
        assertNull(result.node)

        val evidence = retainedFiles(root)
        val cold = f.owner()
        val conflict = assertNotNull(cold.profile())
        assertEquals(pending.copy(ledgerConflict = true, membershipVerified = false), conflict)
        assertEquals(conflict, cold.resumePreparation())
        assertEquals("SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT", assertFailsWith<IllegalArgumentException> {
            cold.archiveRevokedInvitation()
        }.message)
        assertFailsWith<IOException> { cold.activate(offline) }
        assertEquals(conflict, f.owner().profile())
        assertEquals(control, f.control())
        assertContentEquals(recipient, Files.readAllBytes(root.resolve("recipient/recipient.protected")))
        assertEquals(evidence, retainedFiles(root))
        assertFalse(Files.exists(root.resolve("node")))
    }

    @Test fun explicitReprepareAfterAnUnsignedProjectionInterruptionUsesFreshLocalAndRemoteEvidenceWithoutLosingOriginals() = fixture { f ->
        f.publishedPreset(); val local = f.put(f.data, f.card("physical-card"))
        f.admit(f.prove(f.owner()))
        var interrupted = false
        f.hook = { if (it == "GROUP_JOIN_PROJECTION_RETAINED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_PROJECTION_REPLY_LOST") } }
        assertFailsWith<IOException> { f.owner().activate(f.backend()) }; assertTrue(interrupted)
        val originalPlan = f.projectionPath(); val originalBytes = Files.readAllBytes(originalPlan)
        f.hook = {}; f.put(f.data, f.card("physical-card", 12345)); val later = Files.readAllBytes(local)
        f.put(f.founder.data, f.card("wire-card").copy(basicSetting = "共享预设的新修订"))
        val view = f.founder.node.inspect(); val review = view.review(setOf("character_cards/wire-card"))
        val commit = f.founder.node.commit(review, review.requiredBindings)
        f.founder.node.upload(commit.entry.request.operation, f.backend(), f.founder.service.profile()!!.localReplica)
        assertFails { f.owner().activate(f.backend()) }
        assertFalse(Files.exists(f.root.resolve("joining/node/author/origin.protected")))
        f.owner().refreshPreparation()
        assertEquals(LanSyncGroupJoinStage.READY, f.owner().activate(f.backend()).profile.stage)
        assertNotEquals(originalPlan, f.projectionPath()); assertContentEquals(originalBytes, Files.readAllBytes(originalPlan))
        assertContentEquals(later, Files.readAllBytes(local))
        val activePlan = Files.readAllBytes(f.projectionPath()); val nativeOrigin = Files.readAllBytes(f.root.resolve("joining/node/native/origin.protected"))
        assertFails { f.owner().refreshPreparation() }
        assertContentEquals(activePlan, Files.readAllBytes(f.projectionPath())); assertContentEquals(nativeOrigin, Files.readAllBytes(f.root.resolve("joining/node/native/origin.protected")))
    }
    @Test fun reprepareAfterOnlyTheAuthorOriginPreservesThatIdentityAndFrozenPhysicalMappingButRefreshesRawCas() = fixture { f ->
        f.publishedPreset(); val local = f.put(f.data, f.card("physical-card")); f.admit(f.prove(f.owner()))
        var interrupted = false
        f.hook = { if (it == "AUTHOR_ORIGIN_RETAINED" && !interrupted) { interrupted = true; throw IOException("SYNTHETIC_FIRST_ORIGIN_REPLY_LOST") } }
        assertFailsWith<IOException> { f.owner().activate(f.backend()) }; assertTrue(interrupted)
        val origin = Files.readAllBytes(f.root.resolve("joining/node/author/origin.protected"))
        val old = f.projectionPath(); f.hook = {}; f.put(f.data, f.card("physical-card", 777)); val later = Files.readAllBytes(local)
        assertFails { f.owner().activate(f.backend()) }
        f.owner().refreshPreparation(); val result = f.owner().activate(f.backend())
        assertEquals(LanSyncGroupJoinStage.READY, result.profile.stage)
        assertContentEquals(origin, Files.readAllBytes(f.root.resolve("joining/node/author/origin.protected")))
        assertNotEquals(old, f.projectionPath()); assertContentEquals(later, Files.readAllBytes(local))
        assertTrue(result.node!!.inspect().plan!!.units.containsKey("character_cards/wire-card"))
        assertFalse(Files.exists(f.data.resolve("entities/character_cards/wire-card.json")))
    }
}
