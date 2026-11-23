package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.SyncStoreId
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanSyncGroupNearbyStatusTest {
    private class Loopback : LanSyncNetwork {
        @Volatile var routes = emptyMap<String, Int>()
        override val discoveryEnabled = false
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override fun discoverGroup(group: SyncStoreId, local: String, fingerprint: String) =
            routes[fingerprint]?.let { LanSyncPeer(fingerprint, "合成设备", addresses().single(), it) }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer) = connectOwned(identity, peer) {}
        override fun connectOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit): SSLSocket =
            LanSyncTls.connectOwnedWithDial(identity, peer.host, peer.port, peer.fingerprint, 5_000, own) { socket, _, port ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
            }
    }

    private class Platform : LanSyncPlatform {
        private val gate = Mutex()
        override suspend fun exclusive(work: suspend () -> Unit) { gate.withLock { work() } }
        override suspend fun reload(sessionIds: Set<String>) = Unit
    }

    private class Peer(root: Path) {
        val identity = LanSyncTlsIdentity.create()
        private val profile = Files.createDirectory(root.resolve("profile"))
        val data = Files.createDirectory(root.resolve("data"))
        private val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { Path.of(it) })
        val joining = LanSyncGroupJoining(profile, workspace, Platform(), identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(25)
    }

    private fun cardFile(data: Path, id: String) = data.resolve("entities/character_cards/$id.json")

    @Test
    fun mixedSameNameMembersKeepBothObservationsAndColdStartDoesNotInventReachability() = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-nearby-status-").toRealPath()
        val network = Loopback()
        val members = mutableListOf<LanSyncGroupAppTransport>()
        val owners = mutableListOf<LanSyncGroupCoordinator>()
        try {
            LanSyncGroupAdministrationTest.Fixture(Files.createDirectory(root.resolve("founder"))).use { founder ->
                founder.seed()
                val code = founder.code
                fun coordinator(cold: Boolean = false) = LanSyncGroupCoordinator(
                    { Files.isRegularFile(founder.profile.resolve("profile.protected")) },
                    { if (cold) LanSyncGroupEnrollment(founder.profile, founder.workspace, Platform(), founder.identity,
                        { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }) else founder.service },
                    { error("SYNTHETIC_FOUNDER_MUST_NOT_OPEN_JOINING") }, network, "合成管理设备",
                    initiallyPaused = cold).also(owners::add)
                val live = coordinator()
                live.open()
                await { !live.state.value.busy && live.state.value.stage == LanSyncGroupPanelStage.READY }

                suspend fun unlock() {
                    live.unlockManagement(code)
                    await { !live.state.value.busy && live.state.value.managementCanInvite }
                }
                suspend fun join(peer: Peer, cardId: String): Int {
                    unlock()
                    live.createInvitation()
                    await { !live.state.value.busy && live.state.value.invitation != null }
                    peer.joining.prepare(requireNotNull(live.state.value.invitation).code()) { it.validate() }
                    peer.joining.proveUsing("同名设备", network)
                    await { !live.state.value.busy && live.state.value.peerConfirmation?.fingerprint == peer.identity.fingerprint }
                    assertTrue(live.state.value.devices.none { it.id == peer.identity.fingerprint })
                    live.acceptPeer(requireNotNull(live.state.value.peerConfirmation).binding)
                    await { !live.state.value.busy && live.state.value.devices.any { it.id == peer.identity.fingerprint } }
                    val activation = peer.joining.activate(peer.joining.bootstrapStore(network))
                    assertEquals(LanSyncGroupJoinStage.READY, activation.profile.stage, activation.waitingCode)
                    val access = requireNotNull(peer.joining.runtimeAccess())
                    val card = cardFile(peer.data, cardId)
                    Files.createDirectories(card.parent)
                    LanSyncJsonFiles.write(card, CharacterCard.serializer(),
                        CharacterCard(cardId, "来自 $cardId 的合成资料", createdAt = 1, updatedAt = 1))
                    val source = access.node.inspect()
                    val operation = access.node.commit(source.review(setOf("character_cards/$cardId"))).entry.request.operation
                    access.node.upload(operation, access.replica, access.localTarget)
                    return LanSyncGroupAppTransport(access, network) { _, _ -> }.also(members::add).start()
                }

                val a = Peer(Files.createDirectory(root.resolve("a")))
                val b = Peer(Files.createDirectory(root.resolve("b")))
                val aPort = join(a, "synthetic-a")
                val bPort = join(b, "synthetic-b")
                val address = mapOf(a.identity.fingerprint to aPort, b.identity.fingerprint to bPort)
                val source = mapOf(a.identity.fingerprint to "synthetic-a", b.identity.fingerprint to "synthetic-b")
                val orderedIds = founder.custody().ledger().current().members.keys.filter { it in address }
                assertEquals(2, orderedIds.size)

                // Run with the wrong certificate route in each position of the signed membership listing.
                for ((index, blocked) in orderedIds.withIndex()) {
                    val healthy = orderedIds.single { it != blocked }
                    network.routes = address + (blocked to address.getValue(healthy))
                    live.onNetworkAvailable()
                    await {
                        val devices = live.state.value.devices.associateBy { it.id }
                        devices[healthy]?.nearby?.stage == LanSyncGroupNearbyStage.INDEX_READ &&
                            devices[blocked]?.nearby?.stage == LanSyncGroupNearbyStage.INDEX_FAILED
                    }
                    val state = live.state.value
                    val rows = state.devices.filter { it.id in address }.associateBy { it.id }
                    assertTrue(rows.values.all { it.name == "同名设备" && it.status == "已加入" })
                    assertNotNull(rows.getValue(healthy).nearby?.lastSuccessAt)
                    if (index == 0) assertNull(rows.getValue(blocked).nearby?.lastSuccessAt)
                    else assertNotNull(rows.getValue(blocked).nearby?.lastSuccessAt)
                    assertTrue(state.nearby.text.contains("已读取 1 台") && state.nearby.text.contains("1 台连接或读取未完成"))
                    val cardId = source.getValue(healthy)
                    val received = cardFile(founder.data, cardId)
                    await { Files.isRegularFile(received) }
                    assertEquals("来自 $cardId 的合成资料", LanSyncJsonFiles.read(received, CharacterCard.serializer())?.name)
                    assertFalse(state.toString().contains(healthy))
                    assertFalse(state.toString().contains("同名设备"))
                    assertFalse(state.nearby.text.contains("10.0.0.1"))
                }

                network.routes = address
                live.onNetworkAvailable()
                await {
                    live.state.value.devices.count { it.id in address && it.nearby?.stage == LanSyncGroupNearbyStage.INDEX_READ } == address.size
                }
                assertTrue(live.state.value.nearby.text.contains("已读取 2 台"))
                assertFalse(live.state.value.nearby.text.contains("未完成"))

                val removed = orderedIds.first()
                val retained = orderedIds.last()
                // Keep the removed endpoint advertised and online. The signed revocation removes it from ordinary reads.
                unlock()
                live.prepareRevocation(removed)
                await { !live.state.value.busy && live.state.value.revocation?.fingerprint == removed }
                live.confirmRevocation(requireNotNull(live.state.value.revocation).binding)
                await { !live.state.value.busy && live.state.value.devices.any { it.id == removed && it.status == "已撤销" } }
                assertFalse(founder.custody().ledger().current().members.getValue(removed).active)
                val retainedAccess = requireNotNull(founder.service.runtimeAccess())
                val removedEndpoint = LanSyncPeer(removed, "同名设备", network.addresses().single(), address.getValue(removed))
                val refused = assertFailsWith<IllegalArgumentException> {
                    retainedAccess.remote(removedEndpoint, network).listCommits(null)
                }
                assertEquals("SYNC_GROUP_RUNTIME_PEER_NOT_ACTIVE", refused.message)
                live.onNetworkAvailable()
                await { live.state.value.nearby.text.contains("已读取 1 台") }
                assertNull(live.state.value.devices.single { it.id == removed }.nearby)
                assertEquals(LanSyncGroupNearbyStage.INDEX_READ, live.state.value.devices.single { it.id == retained }.nearby?.stage)
                live.closeAndDrain()
                owners.remove(live)

                val cold = coordinator(cold = true)
                cold.open()
                await { cold.state.value.stage == LanSyncGroupPanelStage.READY && !cold.state.value.busy }
                val coldState = cold.state.value
                val restored = coldState.devices.single { it.id == retained }
                assertEquals("同名设备", restored.name)
                assertEquals(LanSyncGroupDeviceNearby(), restored.nearby)
                assertNull(coldState.nearby.lastSuccessAt)
                assertNull(coldState.devices.single { it.id == removed }.nearby)
                assertFalse(coldState.managementUnlocked)

                network.routes = emptyMap()
                cold.resumeIfConfigured()
                await { cold.state.value.devices.single { it.id == retained }.nearby?.stage == LanSyncGroupNearbyStage.NOT_DISCOVERED }
                val missing = cold.state.value.devices.single { it.id == retained }.nearby
                assertNull(missing?.lastSuccessAt)
                assertTrue(requireNotNull(missing).stage.label.contains("未发现"))
            }
        } finally {
            owners.forEach { it.closeAndDrain() }
            members.forEach { it.closeAndDrain() }
            check(root.toFile().deleteRecursively())
        }
    }
}
