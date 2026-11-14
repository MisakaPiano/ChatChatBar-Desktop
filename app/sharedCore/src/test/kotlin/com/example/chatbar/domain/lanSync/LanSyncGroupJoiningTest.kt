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
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }
        fun owner() = LanSyncGroupJoining(root, workspace, platform, founder.joiningIdentity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }, { hook(it) })
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
        val projection = Files.readAllBytes(f.root.resolve("joining/projection.json"))
        f.hook = {}; f.put(f.data, f.card("physical-card").copy(basicSetting = "加入后继续编辑"))
        val later = Files.readAllBytes(local)
        assertEquals(LanSyncGroupJoinStage.READY, f.owner().activate(f.backend()).profile.stage)
        assertContentEquals(origin, Files.readAllBytes(f.root.resolve("joining/node/native/origin.protected")))
        assertContentEquals(projection, Files.readAllBytes(f.root.resolve("joining/projection.json")))
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
}
