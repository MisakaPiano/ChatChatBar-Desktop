package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Actual signatures, full ciphertext and native unit/membership codecs; no provider or fake receipt. */
class PartialTargetExportTest {
    private class Founder(val root: Path) : AutoCloseable {
        val admin = LanSyncGroupAdministrationTest.Fixture(root)
        lateinit var access: LanSyncGroupRuntimeAccess
        fun authorSequence() = SyncAuthorJournal(admin.profile.resolve("node/author"), access.trust,
            Base64.getEncoder().encodeToString(admin.identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() }).state().committedSequence
        fun directory(label: String) = Files.createDirectory(root.resolve(label))
        suspend fun seed() { admin.seed(); access = requireNotNull(admin.service.runtimeAccess()); scan() }
        suspend fun scan() {
            var cursor: CommitCursor? = null
            do { cursor = access.node.discover(access.replica, cursor, 1).nextCursor } while (cursor != null)
        }
        suspend fun card(id: String, image: Boolean = false): LanSyncGroupSourceCommit {
            val avatar = if (image) admin.data.resolve("images/$id.png").also {
                Files.createDirectories(it.parent); Files.write(it, LanSyncPreviewSample.image())
            }.toString() else null
            val path = admin.data.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, "synthetic $id", avatar = avatar, createdAt = 1, updatedAt = 1))
            val commit = access.node.commit(access.node.inspect().review(setOf("character_cards/$id")))
            access.node.upload(commit.entry.request.operation, access.replica, access.localTarget); scan()
            return commit
        }
        suspend fun proof(work: Path, backend: CiphertextStore = access.reader,
            key: ((SyncStoreId) -> SyncStreamingCipher)? = null): LanSyncGroupPartialTargetExport {
            val custody = admin.custody(); val view = access.node.reconcileForPrefetch()
            return LanSyncGroupPartialTargetExport(access.trust, admin.identity.fingerprint, custody.ledger(), view,
                access.vault, access.vaultDescriptor, backend, custody::ledger, key ?: custody::key, custody,
                admin.workspace.resourceStore, { kind, path, record ->
                    assertEquals("images", kind); assertTrue(record.key.startsWith("character_cards/"))
                    assertContentEquals(LanSyncPreviewSample.image(), Files.readAllBytes(path))
                }, work)
        }
        override fun close() = admin.close()
    }
    private fun founder(work: suspend (Founder) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-partial-target-").toRealPath()
        try { Founder(root).use { it.seed(); work(it) } } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun without(store: CiphertextStore, keys: Set<CiphertextObjectKey>) = object : CiphertextStore by store {
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
            if (key in keys) null else store.readTo(key, destination, expected)
    }
    private fun refs(selection: LanSyncGroupPartialTargetSelection) = selection.completeUnitPackages.map { it.ref }.toSet()
    private fun exactBytes(source: CiphertextStore, objectRef: LanSyncGroupPartialObjectRef): ByteArray = ByteArrayOutputStream().use {
        assertEquals(objectRef.metadata(), source.readTo(objectRef.key, it, objectRef.descriptor)); it.toByteArray()
    }

    @Test fun missingHistoricalMediaKeepsTheOriginalScopeWhileTheSameAuthorsLaterIndependentUnitCanAdvance() = founder { f ->
        val first = f.card("a", image = true); val second = f.card("b")
        val view = f.access.node.reconcileForPrefetch()
        val original = view.authenticatedVersions.first { it.envelope.version?.reference() == first.entry.version.reference() }
        val imagePath = f.admin.data.resolve("images/a.png")
        val imageHash = LanSyncResourceStore.hashFile(imagePath, Files.size(imagePath))
        val image = original.envelope.blobs.getValue(imageHash)
        val backend = without(f.access.reader, setOf(image.key))
        val proof = f.proof(f.directory("partial-work"), backend)
        val sequence = f.authorSequence()
        val sourceOperations = f.access.node.sourceOperations()
        proof.prepareAvailable().use { prepared ->
            val selection = prepared.selection
            val a = LanSyncGroupPartialUnitRef(first.entry.version.reference(), "character_cards/a")
            val b = LanSyncGroupPartialUnitRef(second.entry.version.reference(), "character_cards/b")
            assertEquals(setOf(first.entry.version.reference(), second.entry.version.reference()), selection.scope.wantedVersionRefs)
            assertEquals(setOf(a, b), selection.scope.wantedUnitRefs)
            assertEquals(selection.scope.wantedVersionRefs, selection.metadataRefs)
            assertEquals(setOf(b), refs(selection)); assertFalse(selection.originalScopeComplete)
            assertTrue(selection.controlsPending.isEmpty())
            assertTrue(selection.membershipEvidence.any { it.ref == a && !it.derivedFromBody })
            assertTrue(selection.pending.any { it.version == a.version && it.unit == a.unit && it.reason == LanSyncGroupPartialPendingReason.UNIT_CONTENT_UNAVAILABLE })
            assertFalse(selection.exactObjects.any { it.location.key == image.key })
            assertEquals(setOf(first.entry.version.reference(), second.entry.version.reference()), selection.completeUnitPackages.single().metadataClosure)
            assertEquals(original.metadata.descriptor, selection.exactObjects.first { it.location.key == original.metadata.key }.location.descriptor)
            proof.validateAvailable(selection, prepared.source)
            val required = selection.completeUnitPackages.single().objects.first()
            assertFailsWith<IllegalArgumentException> { proof.validateAvailable(selection, without(prepared.source, setOf(required.key))) }
            // The original VERSION container is unchanged; it still names its unavailable old image.
            val raw = exactBytes(prepared.source, original.metadata.let { LanSyncGroupPartialObjectRef(it.key, it.descriptor) })
            assertEquals(original.metadata.descriptor.length, raw.size.toLong())
        }
        val complete = f.proof(f.directory("complete-work"))
        complete.prepareAvailable().use { prepared ->
            assertEquals(proof.scope, prepared.selection.scope); assertTrue(prepared.selection.originalScopeComplete)
            complete.validateAvailable(prepared.selection, prepared.source)
        }
        assertEquals(sequence, f.authorSequence())
        assertEquals(sourceOperations, f.access.node.sourceOperations())
        assertEquals("synthetic a", LanSyncJsonFiles.read(f.admin.data.resolve("entities/character_cards/a.json"), CharacterCard.serializer())?.name)
    }

    @Test fun missingRootAndAHistoricalRecipientCapsuleAllowVerifiedPrepositionButNeverBootstrapOrWholeScopeCompletion() = founder { f ->
        f.admin.rotateBeforeJoin(); f.access = requireNotNull(f.admin.service.runtimeAccess()); f.scan()
        val commit = f.card("available")
        val all = f.proof(f.directory("all-controls"))
        all.prepareAvailable().use { full ->
            assertTrue(full.selection.originalScopeComplete)
            val ledger = full.selection.exactObjects.single { it.role == LanSyncGroupPartialObjectRole.LEDGER }
            val capsule = full.selection.exactObjects.first { it.role == LanSyncGroupPartialObjectRole.CAPSULE && !it.slot.startsWith(all.scope.epoch.value) }
            val unavailable = setOf(ledger.location.key, capsule.location.key)
            val proof = f.proof(f.directory("missing-controls"), without(f.access.reader, unavailable))
            proof.prepareAvailable().use { partial ->
                val selection = partial.selection
                assertEquals(full.selection.scope, selection.scope)
                assertEquals(setOf("ledger", "capsule:${capsule.slot}"), selection.controlsPending)
                assertEquals(setOf(LanSyncGroupPartialUnitRef(commit.entry.version.reference(), "character_cards/available")), refs(selection))
                assertTrue(selection.exactObjects.any { it.role == LanSyncGroupPartialObjectRole.RESOURCE })
                assertFalse(selection.originalScopeComplete)
                proof.validateAvailable(selection, partial.source)
                assertFailsWith<IllegalArgumentException> { proof.validateAvailable(selection.copy(scope = selection.scope.copy(wantedControlSlots = emptySet())), partial.source) }
            }
        }
        assertFailsWith<IllegalStateException> {
            f.proof(f.directory("untrusted-key"), key = { SyncStreamingCipher.create(f.access.trust.group) })
        }
    }

    private class Signed(val root: Path) {
        val source = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(root.resolve("source")))
        private val recipient = source.recipients.getValue(source.owner.fingerprint)
        private val custodyRoot = Files.createDirectory(root.resolve("custody"))
        private val workspace = LanSyncWorkspace(Files.createDirectory(root.resolve("native-data")), Files.createDirectory(root.resolve("native-state")), { Path.of(it) })
        private val vault = SyncManagementVaultContext(1, source.cipher.group, SyncStoreId.random(), source.genesis.reference(), source.owner.fingerprint, recipient.publicKey.hash)
        private val descriptor = CiphertextDescriptor(1, CiphertextSha256.parse("0".repeat(64)))
        init {
            val admitted = custody().enroll(source.ledger.trustAnchor, source.ledger.history())
            val value = SyncGroupKeyDelivery.create(source.ledger, source.cipher, source.owner.fingerprint, SyncStoreId.random(), source.owner::signSyncGroupPayload)
            custody().rememberKey(value.openTentative(source.ledger.trustAnchor, recipient, CiphertextObjectKey(CiphertextObjectKind.COMMIT, value.context.objectId)).admit(admitted))
        }
        fun custody() = SyncGroupCustody(custodyRoot, source.cipher.group, recipient, { it.reversedArray() }, { it.reversedArray() })
        fun sign(row: SyncCausalVersion, actor: LanSyncTlsIdentity = source.sender) = row.copy(signature =
            Base64.getEncoder().encodeToString(actor.signSyncGroupPayload(row.signingBytes())))
        fun publish(row: SyncCausalVersion, bindings: Map<String, SyncEnvelopeBlobBinding> = emptyMap(),
            objectId: SyncStoreId = SyncStoreId.random()): SyncAuthenticatedGroupObject {
            val prepared = source.codec.enqueue(source.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, objectId),
                source.cipher, SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = row, blobs = bindings))
            source.outbox.upload(prepared.request.operationId, source.backend)
            return source.codec.read(source.backend, source.cipher, prepared.context, prepared.request.descriptor)
        }
        fun first(): SyncAuthenticatedGroupObject {
            val prepared = source.prepare(); prepared.operations.values.forEach { source.outbox.upload(it, source.backend) }
            source.outbox.upload(prepared.metadata.request.operationId, source.backend)
            return source.codec.read(source.backend, source.cipher, prepared.metadata.context, prepared.metadata.request.descriptor)
        }
        fun independent(): SyncAuthenticatedGroupObject {
            val path = source.data.resolve("entities/character_cards/b.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("b", "independent real body", createdAt = 1, updatedAt = 1))
            val workspace = LanSyncWorkspace(source.data, source.state, { Path.of(it) })
            return workspace.capture(source.owner.fingerprint).use { capture -> LanSyncGroupUnitCodec(source.unitRoot).prepare(capture, "character_cards/b").use { unit ->
                val bindings = listOf(unit.path to unit.decoded.plaintext, unit.membershipPath to unit.decoded.membership).associate { (path, identity) ->
                    val operation = SyncStoreId.random()
                    val binding = source.blobs.enqueue(source.outbox, operation, CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()), source.cipher, path, identity)
                    source.outbox.upload(operation, source.backend); identity.sha256 to binding
                }
                val version = sign(SyncCausalVersion(source.cipher.group, SyncStoreId.random(), source.genesis.reference(), source.owner.fingerprint,
                    1, null, emptySet(), mapOf("character_cards/b" to SyncUnitChange(unit.decoded.valueHash, unit.decoded.plaintext, emptySet(), unit.decoded.membership)), "unsigned"), source.owner)
                publish(version, bindings)
            } }
        }
        fun proof(values: List<SyncAuthenticatedGroupObject>, backend: CiphertextStore = source.backend): LanSyncGroupPartialTargetExport {
            val versions = values.map { requireNotNull(it.envelope.version) }
            val history = SyncCausalHistory(source.cipher.group, custody().ledger().admission(versions, values))
            versions.forEach { assertEquals(SyncVersionState.ADMITTED, history.offer(it)) }
            val reconciled = SyncInboxReconciliation(emptyList(), history, values, false, source.genesis.reference())
            return LanSyncGroupPartialTargetExport(source.ledger.trustAnchor, source.owner.fingerprint, custody().ledger(), reconciled, vault, descriptor,
                backend, { custody().ledger() }, { custody().key(it) }, custody(), workspace.resourceStore, { _, _, _ -> }, source.work)
        }
    }
    private fun signed(work: (Signed) -> Unit) {
        val root = Files.createTempDirectory("synthetic-partial-signed-history-").toRealPath()
        try { work(Signed(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun missingHistoricalMembershipAndLegacyBodyKeepRepeatedDeletionPendingWithoutBlockingAnIndependentFixedNamespace() = signed { f ->
        val original = f.first(); val first = requireNotNull(original.envelope.version); val unit = first.changes.keys.single()
        val independent = f.independent(); val b = requireNotNull(independent.envelope.version)
        fun tombstone(prior: SyncCausalVersion) = f.sign(prior.copy(id = SyncStoreId.random(), authorSequence = prior.authorSequence + 1,
            previousByAuthor = prior.reference(), parents = setOf(prior.reference()), changes = mapOf(unit to SyncUnitChange(null, null, setOf(prior.reference()))), signature = "unsigned"))
        val deletion = f.publish(tombstone(first)); val repeated = f.publish(tombstone(requireNotNull(deletion.envelope.version)))
        val membership = original.envelope.blobs.getValue(requireNotNull(first.changes.getValue(unit).membership).sha256)
        val proof = f.proof(listOf(original, deletion, repeated, independent), without(f.source.backend, setOf(membership.key)))
        proof.prepareAvailable().use { partial ->
            val selection = partial.selection
            assertEquals(setOf(LanSyncGroupPartialUnitRef(b.reference(), "character_cards/b")), refs(selection))
            listOf(deletion, repeated).forEach { value -> assertTrue(selection.pending.any {
                it.version == value.envelope.reference() && it.unit == unit && it.reason == LanSyncGroupPartialPendingReason.RECORD_HISTORY_UNAVAILABLE
            }) }
            assertTrue(selection.scope.wantedVersionRefs.contains(first.reference())); assertFalse(selection.originalScopeComplete)
            proof.validateAvailable(selection, partial.source)
        }
        val legacy = f.sign(first.copy(id = SyncStoreId.random(), changes = first.changes.mapValues { it.value.copy(membership = null) }, signature = "unsigned"))
        val body = requireNotNull(legacy.changes.getValue(unit).content)
        val legacyObject = f.publish(legacy, original.envelope.blobs - membership.plaintext.sha256)
        val legacyDelete = f.publish(tombstone(legacy))
        val legacyProof = f.proof(listOf(legacyObject, legacyDelete, independent), without(f.source.backend, setOf(original.envelope.blobs.getValue(body.sha256).key)))
        legacyProof.prepareAvailable().use { partial ->
            assertFalse(partial.selection.membershipEvidence.any { it.ref.version == legacy.reference() })
            assertEquals(setOf(LanSyncGroupPartialUnitRef(b.reference(), "character_cards/b")), refs(partial.selection))
            assertTrue(partial.selection.pending.any { it.version == legacyDelete.envelope.reference() && it.reason == LanSyncGroupPartialPendingReason.RECORD_HISTORY_UNAVAILABLE })
        }
    }

    @Test fun authorForkAndItsCausalDependantsStayPendingWhileTheOriginalForkedHistoryAndDisjointAuthorRemainIntact() = signed { f ->
        val original = f.first(); val first = requireNotNull(original.envelope.version)
        val fork = f.publish(f.sign(first.copy(id = SyncStoreId.random(), signature = "unsigned")), original.envelope.blobs)
        val independent = f.independent(); val b = requireNotNull(independent.envelope.version)
        val dependant = f.publish(f.sign(b.copy(id = SyncStoreId.random(), authorSequence = 2, previousByAuthor = b.reference(),
            parents = setOf(b.reference(), first.reference()), changes = b.changes.mapValues { it.value.copy(previous = setOf(b.reference())) }, signature = "unsigned"), f.source.owner), independent.envelope.blobs)
        val proof = f.proof(listOf(original, fork, independent, dependant))
        proof.prepareAvailable().use { partial ->
            val selection = partial.selection
            assertEquals(4, selection.scope.wantedVersionRefs.size); assertEquals(selection.scope.wantedVersionRefs, selection.metadataRefs)
            assertEquals(setOf(LanSyncGroupPartialUnitRef(b.reference(), "character_cards/b")), refs(selection))
            listOf(original, fork, dependant).forEach { value -> assertTrue(selection.pending.any {
                it.version == value.envelope.reference() && it.reason == LanSyncGroupPartialPendingReason.AUTHOR_FORK
            }) }
            assertFalse(selection.originalScopeComplete); proof.validateAvailable(selection, partial.source)
        }
    }

    @Test fun anAuthenticatedBadFirstContainerCannotBorrowAGoodContainersCompleteBindingProof() = signed { f ->
        val original = f.first(); val first = requireNotNull(original.envelope.version); val independent = f.independent()
        val bad = f.publish(first, original.envelope.blobs + independent.envelope.blobs, SyncStoreId.parse("0".repeat(32)))
        val proof = f.proof(listOf(bad, original, independent))
        proof.prepareAvailable().use { partial ->
            val selection = partial.selection
            assertEquals(2, selection.completeUnitPackages.size)
            assertFalse(selection.exactObjects.any { it.location.key == bad.metadata.key })
            assertTrue(selection.exactObjects.any { it.location.key == original.metadata.key })
            assertFalse(selection.pending.any { it.version == first.reference() && it.reason == LanSyncGroupPartialPendingReason.VERSION_BINDINGS_UNVERIFIED })
            assertEquals(original.metadata.key, selection.completeUnitPackages.single { it.ref.version == first.reference() }.container.key)
            proof.validateAvailable(selection, partial.source)
        }
    }
}
