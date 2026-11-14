package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** Real coordinator/native owners and a TLS-approved remote author; protection/file routes are synthetic. */
class LanSyncGroupCoordinatorResolutionTest {
    private val network = object : LanSyncNetwork {
        override fun addresses() = listOf("10.0.0.1")
        override fun accepts(address: InetAddress) = address.isLoopbackAddress
        override val discoveryEnabled = false
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
            LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint, 15_000) { _, port -> Socket("127.0.0.1", port) }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(10) }
    private class Scene(val f: LanSyncGroupAdministrationTest.Fixture, val network: LanSyncNetwork) {
        val unit = "character_cards/card"
        val card = f.data.resolve("entities/character_cards/card.json")
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) { }
        }
        val owners = mutableListOf<LanSyncGroupEnrollment>()
        val coordinators = mutableListOf<LanSyncGroupCoordinator>()
        fun owner() = LanSyncGroupEnrollment(f.profile, f.workspace, platform, f.identity,
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> }).also(owners::add)
        fun newCoordinator() = LanSyncGroupCoordinator({ true }, ::owner,
            { error("SYNTHETIC_FOUNDER_ONLY") }, network, "synthetic resolution PC").also(coordinators::add)
        lateinit var coordinator: LanSyncGroupCoordinator
        lateinit var access: LanSyncGroupRuntimeAccess
        lateinit var remote: LanSyncGroupNode
        lateinit var remoteData: Path
        lateinit var remoteAuthor: SyncAuthorJournal
        lateinit var left: SyncAuthorJournalEntry
        lateinit var right: LanSyncGroupSourceCommit
        lateinit var original: ByteArray
        lateinit var leftRaw: ByteArray
        fun author() = SyncAuthorJournal(f.profile.resolve("node/author"), access.trust,
            Base64.getEncoder().encodeToString(f.identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
        fun write(path: Path, name: String) {
            Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("card", name, createdAt = 1, updatedAt = 1))
        }
        suspend fun seed(await: suspend (() -> Boolean) -> Unit) {
            write(card, "base"); original = Files.readAllBytes(card)
            coordinator = newCoordinator(); coordinator.startIfConfigured()
            await { coordinator.view.value != null && !coordinator.state.value.busy }
            assertNull(coordinator.state.value.error)
            access = requireNotNull(owner().runtimeAccess())
            assertEquals(1L, author().state().committedSequence)
            val proof = f.prove()
            val member = f.service.prepareMember(proof.second, proof.first)
            f.service.addMember(f.service.resume(f.code), member.acknowledge(member.request.approvalBinding))

            val custody = SyncGroupCustody(Files.createDirectory(f.root.resolve("resolution-peer-custody")), access.trust.group,
                f.joiningKey, { it.reversedArray() }, { it.reversedArray() })
            val bootstrap = SyncGroupInbox(Files.createDirectory(f.root.resolve("resolution-peer-bootstrap")), access.replica, custody, f.joiningKey)
            var cursor: CommitCursor? = null
            do { cursor = bootstrap.discover(cursor, 1).nextCursor } while (cursor != null)
            val inspection = bootstrap.prepareEnrollment(proof.second.trust)
            assertEquals(SyncGroupBootstrapStage.READY, inspection.stage)
            val evidence = requireNotNull(inspection.evidence)
            custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
            remoteData = Files.createDirectory(f.root.resolve("resolution-peer-data"))
            val runtime = Files.createDirectory(f.root.resolve("resolution-peer-node"))
            val workspace = LanSyncWorkspace(remoteData, Files.createDirectory(f.root.resolve("resolution-peer-workspace")), { Path.of(it) })
            remote = LanSyncGroupNode(runtime, workspace, platform, f.joiningIdentity, evidence.trust, custody, f.joiningKey,
                access.replica, { it.reversedArray() }, { it.reversedArray() }, { emptySet() }, { _, _, _ -> })
            remote.initializeNewEnrollment()
            cursor = null
            do { cursor = remote.discover(access.replica, cursor, 1).nextCursor } while (cursor != null)
            remote.apply(requireNotNull(remote.inspect().plan))
            remoteAuthor = SyncAuthorJournal(runtime.resolve("author"), evidence.trust,
                Base64.getEncoder().encodeToString(f.joiningIdentity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
            assertEquals(0L, remoteAuthor.state().committedSequence)

            write(card, "local left"); leftRaw = Files.readAllBytes(card)
            coordinator.retry()
            await {
                val plan = coordinator.view.value?.plan
                !coordinator.state.value.busy && plan?.before?.records?.any { name(it) == "local left" } == true &&
                    plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED
            }
            assertNull(coordinator.state.value.error)
            assertEquals(2L, author().state().committedSequence)
            val leftOperation = access.node.sourceOperations().last()
            left = requireNotNull(author().entry(leftOperation))
            assertEquals(2L, left.version.authorSequence)
            assertNotNull(access.node.receipt(leftOperation, access.localTarget))
            write(remoteData.resolve("entities/character_cards/card.json"), "remote right")
            val review = remote.inspect().review(setOf(unit))
            right = remote.commit(review, review.requiredBindings)
            remote.upload(right.entry.request.operation, access.replica, SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, SyncStoreId.random()))
            coordinator.retry()
            await { !coordinator.state.value.busy && coordinator.view.value?.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.CONFLICT }
            assertNull(coordinator.state.value.error)
            assertContentEquals(leftRaw, Files.readAllBytes(card))
            assertEquals(2L, author().state().committedSequence)
            assertEquals(1L, remoteAuthor.state().committedSequence)
        }
        fun assertOriginal(raw: ByteArray, entries: List<LanSyncRecoveryEntry> = f.workspace.recovery.list()) {
            var found = false
            entries.forEach { entry ->
                f.workspace.recovery.verify(entry)
                f.workspace.recovery.originals(entry) { row, path ->
                    if (row.relative == "entities/character_cards/card.json" && Files.readAllBytes(path).contentEquals(raw)) found = true
                }
            }
            assertTrue(found, "An independent sealed original must retain these exact raw bytes")
        }
        suspend fun close() { coordinators.forEach { it.closeAndDrain() }; owners.forEach { it.close() } }
        companion object { fun name(record: LanSyncRecord) = record.body["name"]?.jsonPrimitive?.content }
    }
    private fun scene(work: suspend (Scene) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-coordinator-resolution-").toRealPath()
        val f = LanSyncGroupAdministrationTest.Fixture(root)
        val scene = Scene(f, network)
        try { f.seed(); scene.seed(::await); work(scene) }
        finally { scene.close(); f.close(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun laterPreparationAndDismissalAreInertThenExplicitHoldSurvivesOwnerReopenWithoutSigning() = scene { s ->
        val frozen = requireNotNull(s.coordinator.view.value)
        val operations = s.access.node.sourceOperations()
        val origin = Files.readAllBytes(s.f.profile.resolve("node/author/origin.protected"))
        s.coordinator.prepareResolution(frozen, mapOf(s.unit to LanSyncGroupResolutionChoice.Later))
        await { !s.coordinator.state.value.busy && s.coordinator.actionsReview.value?.resolution != null }
        val dismissed = requireNotNull(s.coordinator.actionsReview.value)
        val preview = requireNotNull(dismissed.resolution)
        assertSame(frozen, dismissed.resolutionBasis)
        assertTrue(preview.resolvedUnits.isEmpty())
        assertEquals(setOf(s.unit), preview.heldUnits)
        assertEquals(operations, s.access.node.sourceOperations())
        assertEquals(2L, s.author().state().committedSequence)
        assertContentEquals(s.leftRaw, Files.readAllBytes(s.card))
        assertContentEquals(origin, Files.readAllBytes(s.f.profile.resolve("node/author/origin.protected")))
        assertTrue(s.access.node.inspect().resolutions.isEmpty())
        s.coordinator.cancelActionReview()
        s.coordinator.confirmActionReview(dismissed.binding)
        await { !s.coordinator.state.value.busy && s.coordinator.state.value.error?.endsWith("SYNC_GROUP_REVIEW_DISMISSED") == true }
        assertTrue(s.access.node.inspect().resolutions.isEmpty())
        s.coordinator.prepareResolution(requireNotNull(s.coordinator.view.value), mapOf(s.unit to LanSyncGroupResolutionChoice.Later))
        await { !s.coordinator.state.value.busy && s.coordinator.actionsReview.value?.resolution != null }
        val confirmed = requireNotNull(s.coordinator.actionsReview.value)
        s.coordinator.confirmActionReview(confirmed.binding)
        await { !s.coordinator.state.value.busy && s.coordinator.actionsReview.value == null && s.unit in s.coordinator.view.value?.reservedUnits.orEmpty() }
        assertNull(s.coordinator.state.value.error)
        assertEquals(operations, s.access.node.sourceOperations())
        assertEquals(2L, s.author().state().committedSequence)
        s.coordinator.closeAndDrain()
        val reopened = s.newCoordinator(); reopened.startIfConfigured()
        await { !reopened.state.value.busy && s.unit in reopened.view.value?.reservedUnits.orEmpty() }
        assertNull(reopened.state.value.error)
        assertEquals(setOf(s.unit), requireNotNull(reopened.view.value).resolutions.single().heldUnits)
        assertEquals(operations, s.access.node.sourceOperations())
        assertEquals(2L, s.author().state().committedSequence)
        assertContentEquals(s.leftRaw, Files.readAllBytes(s.card))
        assertContentEquals(origin, Files.readAllBytes(s.f.profile.resolve("node/author/origin.protected")))
    }

    @Test fun keepRemoteAndCopyLocalExposeActualFrozenBodiesAndPublishExactlyOneResolutionRevision() = scene { s ->
        val frozen = requireNotNull(s.coordinator.view.value)
        val operations = s.access.node.sourceOperations()
        val choice = LanSyncGroupResolutionChoice.Keep(LanSyncGroupResolutionSource.Version(s.right.entry.version.reference()),
            mapOf("saved-local" to LanSyncGroupResolutionSource.Local))
        s.coordinator.prepareResolution(frozen, mapOf(s.unit to choice))
        await { !s.coordinator.state.value.busy && s.coordinator.actionsReview.value?.resolution != null }
        val action = requireNotNull(s.coordinator.actionsReview.value)
        val preview = requireNotNull(action.resolution)
        val details = preview.units.getValue(s.unit)
        assertSame(frozen, action.resolutionBasis)
        assertEquals(listOf("local left"), details.current.map { Scene.name(it) })
        assertEquals(setOf(s.left.version.reference(), s.right.entry.version.reference()), details.versions.keys)
        assertEquals(setOf("local left", "remote right"), details.versions.values.flatten().map { Scene.name(it) }.toSet())
        assertEquals(setOf("local left", "remote right"), details.proposed.map { Scene.name(it) }.toSet())
        assertEquals(2, preview.resolvedUnits.size)
        assertEquals(operations, s.access.node.sourceOperations())
        assertEquals(2L, s.author().state().committedSequence)
        assertContentEquals(s.leftRaw, Files.readAllBytes(s.card))
        val priorRecovery = s.f.workspace.recovery.list().map { it.transaction }.toSet()
        s.coordinator.confirmActionReview(action.binding)
        await { !s.coordinator.state.value.busy && s.coordinator.actionsReview.value == null }
        assertNull(s.coordinator.state.value.error)
        val after = s.access.node.sourceOperations()
        assertEquals(3, after.size)
        assertEquals(operations, after.take(2))
        val resolved = requireNotNull(s.author().entry(after.last()))
        assertEquals(3L, s.author().state().committedSequence)
        assertEquals(setOf(s.left.version.reference(), s.right.entry.version.reference()), resolved.version.changes.getValue(s.unit).previous)
        assertEquals(preview.resolvedUnits, resolved.version.changes.keys)
        assertNotNull(s.access.node.receipt(after.last(), s.access.localTarget))
        val current = requireNotNull(s.coordinator.view.value).plan?.before?.records.orEmpty()
        assertEquals(setOf("local left", "remote right"), current.map { Scene.name(it) }.toSet())
        assertEquals(2, current.map { it.key }.toSet().size)
        assertEquals("remote right", requireNotNull(LanSyncJsonFiles.read(s.card, CharacterCard.serializer())).name)
        s.assertOriginal(s.original); s.assertOriginal(s.leftRaw)
        s.assertOriginal(s.leftRaw, s.f.workspace.recovery.list().filter { it.transaction !in priorRecovery })
        val previousView = s.coordinator.view.value
        s.coordinator.resumeResolution(preview.operation)
        await { !s.coordinator.state.value.busy && s.coordinator.view.value !== previousView }
        assertNull(s.coordinator.state.value.error)
        assertEquals(after, s.access.node.sourceOperations())
        assertEquals(3L, s.author().state().committedSequence)
    }

    @Test fun rawOnlyEditAfterPreparingResolutionRefusesWithoutOverwritingOrAllocatingAnAuthorRevision() = scene { s ->
        val frozen = requireNotNull(s.coordinator.view.value)
        val operations = s.access.node.sourceOperations()
        s.coordinator.prepareResolution(frozen, mapOf(s.unit to LanSyncGroupResolutionChoice.Keep(
            LanSyncGroupResolutionSource.Version(s.right.entry.version.reference()))))
        await { !s.coordinator.state.value.busy && s.coordinator.actionsReview.value?.resolution != null }
        val action = requireNotNull(s.coordinator.actionsReview.value)
        val beforeRecovery = s.f.workspace.recovery.list()
        val old = requireNotNull(LanSyncJsonFiles.read(s.card, CharacterCard.serializer()))
        LanSyncJsonFiles.write(s.card, CharacterCard.serializer(), old.copy(updatedAt = old.updatedAt + 1))
        val laterRaw = Files.readAllBytes(s.card)
        assertFalse(laterRaw.contentEquals(s.leftRaw))
        s.coordinator.confirmActionReview(action.binding)
        await { !s.coordinator.state.value.busy && s.coordinator.state.value.error != null }
        assertTrue(requireNotNull(s.coordinator.state.value.error).endsWith("SYNC_GROUP_RESOLUTION_STALE"))
        assertSame(action, s.coordinator.actionsReview.value)
        assertContentEquals(laterRaw, Files.readAllBytes(s.card))
        assertEquals(operations, s.access.node.sourceOperations())
        assertEquals(2L, s.author().state().committedSequence)
        assertNull(s.author().state().pendingApproved)
        assertEquals(beforeRecovery, s.f.workspace.recovery.list())
        assertEquals(frozen.nativeState, s.access.node.inspect().nativeState)
        assertEquals(listOf("local left"), requireNotNull(action.resolution).units.getValue(s.unit).current.map { Scene.name(it) })
    }
}
