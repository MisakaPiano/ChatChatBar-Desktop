package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncGroupJoiningTest {
    private class Joiner(val founder: LanSyncGroupAdministrationTest.Fixture) {
        val root = Files.createDirectory(founder.root.resolve("join-profile"))
        val data = Files.createDirectory(founder.root.resolve("join-data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(founder.root.resolve("join-workspace")), { Path.of(it) })
        var hook: (String) -> Unit = {}; var gated = false
        var timeShift = 0L
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }
        fun owner() = LanSyncGroupJoining(root, workspace, platform, founder.joiningIdentity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, { hook(it) }, wallTime = { System.currentTimeMillis() + timeShift })
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
                    LanSyncTls.connect(founder.joiningIdentity, current.host, current.port, current.inviter, 15_000) { _, _ -> Socket(InetAddress.getLoopbackAddress(), listener.localPort) }
                }
                return invitation to server.get(20, TimeUnit.SECONDS)
            } finally { listener.close(); pool.shutdownNow(); check(pool.awaitTermination(25, TimeUnit.SECONDS)) }
        }
        suspend fun admit(pair: Pair<LanSyncGroupInvitation, LanSyncGroupInvitedPeer>) {
            val preview = founder.service.prepareMember(pair.second, pair.first)
            founder.service.addMember(founder.service.resume(founder.code), preview.acknowledge(preview.request.approvalBinding))
        }
    }
    private fun fixture(work: suspend (Joiner) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("group-joining-").toRealPath()
        try { LanSyncGroupAdministrationTest.Fixture(root).use { it.seed(); work(Joiner(it)) } }
        finally { check(root.toFile().deleteRecursively()) }
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
        assertEquals(0, connections); assertEquals(LanSyncGroupJoinStage.PREPARED, f.owner().profile()!!.stage)
        f.timeShift = 0
        val fresh = invitation.issue("10.0.0.2", 44001)
        f.owner().refreshInvitation(fresh.code()) {}
        assertEquals(before.getValue("operation"), f.control().getValue("operation"))
        assertEquals(before.getValue("recipient"), f.control().getValue("recipient")); assertContentEquals(key, Files.readAllBytes(f.root.resolve("joining/recipient/recipient.protected")))
        f.hook = { if (it == "GROUP_JOIN_PROOF_ATTEMPT_RETAINED") throw IOException("SYNTHETIC_UNKNOWN_PROOF_ATTEMPT") }
        assertFailsWith<IOException> { f.owner().prove("synthetic") { connections++; error("MUST_NOT_CONNECT") } }
        f.hook = {}; assertEquals(LanSyncGroupJoinStage.PROOF_ATTEMPTED, f.owner().profile()!!.stage)
        val next = invitation.issue("10.0.0.3", 44002)
        f.owner().refreshInvitation(next.code()) {}
        assertEquals(LanSyncGroupJoinStage.PROOF_ATTEMPTED, f.owner().profile()!!.stage)
        val alienGroup = SyncStoreId.random()
        val alien = next.copy(trust = next.trust.copy(group = alienGroup), bootstrap = next.bootstrap!!.copy(vault = next.bootstrap!!.vault.copy(group = alienGroup)))
        assertFails { f.owner().refreshInvitation(alien.code()) {} }
        assertEquals(before.getValue("operation"), f.control().getValue("operation"))
        assertContentEquals(key, Files.readAllBytes(f.root.resolve("joining/recipient/recipient.protected"))); assertEquals(0, connections)
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
