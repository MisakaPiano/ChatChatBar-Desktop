package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** Actual founder/profile/key/root publication/native node; synthetic platform protection only. */
class LanSyncGroupEnrollmentTest {
    private class Fixture(root: Path) {
        val profileRoot = Files.createDirectory(root.resolve("profile")); val data = Files.createDirectory(root.resolve("data"))
        val workspaceRoot = Files.createDirectory(root.resolve("workspace"))
        val workspace = LanSyncWorkspace(data, workspaceRoot, { Path.of(it) })
        val identity = LanSyncTlsIdentity.create(); var gated = false; var failReady = false; var failNativeHead = false
        val services = mutableListOf<LanSyncGroupEnrollment>()
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }
        fun service(metadataBudget: LanSyncMetadataBudget? = null, observe: (String) -> Unit = {}) = LanSyncGroupEnrollment(profileRoot,
            metadataBudget?.let { LanSyncWorkspace(data, workspaceRoot, { Path.of(it) }, metadataBudget = it) } ?: workspace,
            platform, identity, { bytes ->
            if (failNativeHead && bytes.toString(Charsets.UTF_8).let { it.contains("\"kind\":\"HEAD\"") && it.contains("CCB-SYNC-NATIVE-CONTROL/1") }) {
                failNativeHead = false; throw IOException("SYNTHETIC_INITIAL_HEAD_PROTECTION_FAILED")
            }
            if (failReady && bytes.toString(Charsets.UTF_8).contains("\"stage\":\"READY\"")) throw IOException("SYNTHETIC_READY_PROTECTION_FAILED")
            bytes.reversedArray()
        }, { it.reversedArray() }, { _, _, _ -> }, observe).also(services::add)
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys, "a".repeat(64))
        fun card() {
            val path = data.resolve("entities/character_cards/card.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("card", "synthetic native profile", createdAt = 1, updatedAt = 1))
        }
    }
    private fun withFixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-enrollment-").toRealPath()
        val fixture = Fixture(root)
        try { work(fixture) } finally { fixture.services.forEach { it.close() }; check(root.toFile().deleteRecursively()) }
    }

    @Test fun aggregateInlineMetadataAboveWorkingWindowActivatesEveryRecordAndColdReopensTheSameGroup() = withFixture { f ->
        val cards = listOf("a", "b").map { id -> CharacterCard(id, "synthetic-$id-" + "x".repeat(2048), createdAt = 1, updatedAt = 1) }
        val paths = cards.associate { card ->
            val path = f.data.resolve("entities/character_cards/${card.id}.json")
            Files.createDirectories(path.parent); LanSyncJsonFiles.write(path, CharacterCard.serializer(), card)
            card.id to path
        }
        val original = paths.mapValues { Files.readAllBytes(it.value) }
        val costs = paths.mapValues { LanSyncMetadataBudget(maximumDecodedCost = 1024 * 1024).validate(it.value) }
        val window = costs.values.max() + costs.values.min() / 2
        val budget = LanSyncMetadataBudget(maximumDecodedCost = window)
        assertTrue(costs.values.all { it < window })
        assertTrue(costs.values.fold(0L, Math::addExact) > window)
        paths.forEach { (id, path) -> assertEquals(costs.getValue(id), budget.validate(path)) }
        val owner = f.service(metadataBudget = budget)
        val draft = owner.prepare(f.request, SyncRootGroupCreateAdmission {})
        val prepared = draft.profile; val code = draft.recoveryCode()
        var saved = 0
        val node = owner.activate(draft, LanSyncGroupRecoverySavedAdmission { assertEquals(prepared, it); saved++ })
        assertEquals(1, saved)
        val view = node.inspect(); val before = requireNotNull(view.plan).before
        assertEquals(LanSyncScope.schemas.keys, before.coveredTypes)
        assertEquals(2, before.records.size)
        assertEquals(paths.keys, before.records.filter { it.type == "character_cards" }.map { it.id }.toSet())
        before.records.forEach { record ->
            val expected = lanSyncJson.parseToJsonElement(original.getValue(record.id).toString(Charsets.UTF_8)).jsonObject
            assertEquals(LanSyncScope.normalize(record.type, expected), record.body)
        }
        assertEquals(0L, view.nativeState.completedSequence)
        assertTrue(view.nativeState.units.isEmpty()); assertNull(view.nativeState.pendingTransaction)
        assertEquals(LanSyncGroupEnrollmentStage.READY, owner.profile()?.stage)
        assertEquals(prepared.trust, owner.profile()?.trust)
        val pins = listOf("profile.protected", "creation/genesis.protected", "management-vault.bin", "recipient/recipient.protected",
            "custody/group.protected", "root-actions/origin.protected", "node/author/origin.protected",
            "node/native/origin.protected", "node/native/head.protected", "node/resolutions/state.protected")
            .associateWith { Files.readAllBytes(f.profileRoot.resolve(it)) }
        owner.close()
        // New workspace/enrollment owners use the same paths and identity; this is a JVM-owner reopen.
        val cold = f.service(metadataBudget = budget)
        val opened = requireNotNull(cold.open()); val reopened = opened.inspect()
        assertEquals(prepared.trust, cold.profile()?.trust)
        assertEquals(prepared.device, cold.profile()?.device)
        assertEquals(before, requireNotNull(reopened.plan).before)
        assertEquals(0L, reopened.nativeState.completedSequence)
        pins.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.profileRoot.resolve(name))) }
        original.forEach { (id, bytes) -> assertContentEquals(bytes, Files.readAllBytes(paths.getValue(id))) }
        val unlocked = cold.resume(code)
        assertEquals(code, unlocked.recoveryCode()); assertEquals(prepared.trust, unlocked.profile.trust)
        unlocked.close()
    }

    @Test fun singleRecordWorkingBudgetFailureRetainsPreparedIdentityAndColdRetryNeverResetsItsOrigins() = withFixture { f ->
        f.card()
        val entity = f.data.resolve("entities/character_cards/card.json")
        val original = Files.readAllBytes(entity)
        val cost = LanSyncMetadataBudget(maximumDecodedCost = 1024 * 1024).validate(entity)
        assertTrue(cost > 1)
        val tight = LanSyncMetadataBudget(maximumDecodedCost = cost - 1)
        assertEquals("SYNC_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> { tight.validate(entity) }.message)
        val owner = f.service(metadataBudget = tight)
        val draft = owner.prepare(f.request, SyncRootGroupCreateAdmission {})
        val prepared = draft.profile; val code = draft.recoveryCode()
        val before = listOf("profile.protected", "creation/genesis.protected", "management-vault.bin", "recipient/recipient.protected")
            .associateWith { Files.readAllBytes(f.profileRoot.resolve(it)) }
        assertEquals("SYNC_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> {
            owner.activate(draft, LanSyncGroupRecoverySavedAdmission { assertEquals(prepared, it) })
        }.message)
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, owner.profile()?.stage)
        assertEquals(prepared.trust, owner.profile()?.trust)
        assertEquals(code, draft.recoveryCode())
        before.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.profileRoot.resolve(name))) }
        val origins = listOf("custody/group.protected", "root-actions/origin.protected", "node/author/origin.protected",
            "node/native/origin.protected", "node/native/head.protected", "node/resolutions/state.protected")
            .associateWith { Files.readAllBytes(f.profileRoot.resolve(it)) }
        val published = listOf("local-replica", "root-delivery", "root-queues").flatMap { name ->
            Files.walk(f.profileRoot.resolve(name)).use { paths -> paths.filter {
                Files.isRegularFile(it) && !it.fileName.toString().endsWith(".lock")
            }.toList() }
        }.associate { f.profileRoot.relativize(it).toString() to Files.readAllBytes(it) }
        assertTrue(published.isNotEmpty())
        assertFalse(owner.canAbandonUnactivated())
        assertEquals("SYNC_GROUP_PROFILE_LOCAL_HISTORY_EXISTS", assertFailsWith<IllegalArgumentException> {
            owner.abandonUnactivated(LanSyncGroupDraftAbandonAdmission { error("SYNTHETIC_NEVER_APPROVE_HISTORY_DISCARD") })
        }.message)
        assertFalse(Files.exists(f.profileRoot.resolve("abandon.protected")))
        assertContentEquals(original, Files.readAllBytes(entity))
        draft.close(); owner.close()
        // Only this synthetic single-record guard is relaxed to test lifecycle retry. The aggregate
        // contract above retains its unchanged working window; this is not a product cap fix.
        val cold = f.service(metadataBudget = LanSyncMetadataBudget(maximumDecodedCost = cost + 1))
        assertFails { cold.resume("synthetic-invalid-recovery-code") }
        val resumed = cold.resume(code)
        assertEquals(code, resumed.recoveryCode()); assertEquals(prepared, resumed.profile)
        val node = cold.activate(resumed, LanSyncGroupRecoverySavedAdmission { assertEquals(prepared, it) })
        assertEquals(LanSyncGroupEnrollmentStage.READY, cold.profile()?.stage)
        assertEquals(prepared.trust, cold.profile()?.trust); assertEquals(prepared.device, cold.profile()?.device)
        val view = node.inspect(); val records = requireNotNull(view.plan).before.records
        assertEquals(setOf("character_cards/card"), records.map { it.key }.toSet())
        assertEquals(0L, view.nativeState.completedSequence); assertTrue(view.nativeState.units.isEmpty())
        assertNull(view.nativeState.pendingTransaction)
        (before - "profile.protected" + origins).forEach { (name, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(f.profileRoot.resolve(name)))
        }
        published.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.profileRoot.resolve(name))) }
        assertContentEquals(original, Files.readAllBytes(entity))
    }

    @Test fun unsupportedPartialCaptureScopeRefusesBeforeAdmissionOrAnyKeyAndGroupMaterial() = withFixture { f ->
        val service = f.service(); var admissions = 0
        assertEquals("SYNC_GROUP_PROFILE_PARTIAL_SCOPE_UNSUPPORTED", assertFailsWith<IllegalArgumentException> {
            service.prepare(f.request.copy(coveredTypes = setOf("character_cards")), SyncRootGroupCreateAdmission { admissions++ })
        }.message)
        assertEquals(0, admissions); assertNull(service.profile())
        Files.newDirectoryStream(f.profileRoot).use { entries -> assertEquals(setOf("profile.lock"), entries.map { it.fileName.toString() }.toSet()) }
        assertNotNull(service.prepare(f.request, SyncRootGroupCreateAdmission {}))
    }

    @Test fun oneConversationAboveTheRecordWindowPreservesExactSourceUnitCheckpointAndColdNativeState() = withFixture { f ->
        val unit = "conversation/session"
        val sessionFile = f.data.resolve("entities/chat_sessions/session.json")
        Files.createDirectories(sessionFile.parent)
        LanSyncJsonFiles.write(sessionFile, ChatSession.serializer(), ChatSession("session", "card", "合成会话",
            createdAt = 1, updatedAt = 1))
        val messages = (1..5).map { index ->
            ChatMessage("message-$index", "session", MessageRole.USER, "第 $index 段：" + "合成内容😀".repeat(512),
                createdAt = index.toLong(), updatedAt = index.toLong())
        }
        val messagePaths = messages.map { message ->
            val path = f.data.resolve("entities/chat_messages/${message.sessionId}_${message.id}.json")
            Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, ChatMessage.serializer(), message)
            path
        }
        val uncapped = LanSyncMetadataBudget(maximumDecodedCost = 1024 * 1024)
        val rawCosts = (messagePaths + listOf(sessionFile)).map(uncapped::validate)
        val budget = LanSyncMetadataBudget(maximumDecodedCost = rawCosts.max() + 1024)
        assertTrue(rawCosts.sum() > budget.maximumDecodedCost)
        val owner = f.service(metadataBudget = budget)
        val draft = owner.prepare(f.request, SyncRootGroupCreateAdmission {})
        val node = owner.activate(draft, LanSyncGroupRecoverySavedAdmission {})
        val view = node.inspect()
        val before = requireNotNull(view.plan).before
        val expectedRows = before.units().getValue(unit).sortedBy { it.key }
        assertEquals(messages.size + 1, expectedRows.size)
        assertEquals(messages.associate { it.id to it.content },
            expectedRows.filter { it.type == "chat_messages" }.associate { it.id to it.text("content") })

        val operation = SyncStoreId.random()
        val commit = node.commit(view.review(setOf(unit)), operation = operation)
        val change = commit.entry.version.changes.getValue(unit)
        val identity = requireNotNull(change.content)
        val path = requireNotNull(f.workspace.resourceStore.completed(identity.sha256, identity.length))
        assertEquals("SYNC_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> { budget.validate(path) }.message)
        val usage = Files.newBufferedReader(path).use { LanSyncRecordPreflight(it, budget, listOf("records")).validate() }
        assertEquals(expectedRows.size, usage.records)
        assertTrue(usage.largestRecordCost in 1..budget.maximumDecodedCost)
        assertTrue(usage.indexCost in 1..budget.maximumIndexCost)
        val codec = LanSyncGroupUnitCodec(Files.createDirectory(f.workspaceRoot.resolve("unit-readback")), metadataBudget = budget)
        val unitBody = codec.readCached(f.workspace.resourceStore, unit, change)
        assertEquals(expectedRows, unitBody.payload.records)
        assertEquals(before.hashes().getValue(unit), unitBody.valueHash)
        assertEquals(LanSyncGroupMembership.identity(expectedRows), unitBody.membership)
        assertEquals(identity.sha256, LanSyncResourceStore.hashFile(path, identity.length))

        val runtime = requireNotNull(owner.runtimeAccess())
        val receipt = node.upload(operation, runtime.replica, runtime.localTarget)
        assertEquals(commit.publication.metadata.descriptor, receipt.metadata)
        assertEquals(runtime.localTarget, receipt.target)
        val checkpoint = requireNotNull(f.workspace.recovery.read(commit.entry.request.sourceCheckpoint))
        owner.close()
        val cold = f.service(metadataBudget = budget)
        val reopened = requireNotNull(cold.open())
        val state = reopened.inspect().nativeState
        assertEquals(1L, state.completedSequence)
        assertNull(state.pendingTransaction)
        assertEquals(setOf(commit.entry.version.reference()), state.units.getValue(unit).frontiers)
        assertEquals(unitBody.valueHash, state.units.getValue(unit).valueHash)
        val history = reopened.browseRecovery(checkpoint.transaction).units.getValue(unit).sortedBy { it.key }
        assertEquals(expectedRows, history)
        assertEquals(before, requireNotNull(reopened.inspect().plan).before)
    }

    @Test fun preparedGroupNeedsSavedCodeAndNormalReopenUsesOnlyDailyCustody() = withFixture { f ->
        val service = f.service(); assertNull(service.profile()); assertNull(service.open())
        assertFailsWith<IllegalStateException> { service.prepare(f.request, SyncRootGroupCreateAdmission { error("SYNTHETIC_NOT_APPROVED") }) }
        Files.newDirectoryStream(f.profileRoot).use { entries -> assertEquals(setOf("profile.lock"), entries.map { it.fileName.toString() }.toSet()) }
        val draft = service.prepare(f.request, SyncRootGroupCreateAdmission {})
        val code = draft.recoveryCode(); assertEquals(LanSyncGroupEnrollmentStage.PREPARED, service.profile()?.stage)
        assertEquals("SYNC_GROUP_PROFILE_ENROLLMENT_INCOMPLETE", assertFailsWith<IllegalArgumentException> { service.open() }.message)
        assertFailsWith<IllegalStateException> { service.activate(draft, LanSyncGroupRecoverySavedAdmission { error("SYNTHETIC_CODE_NOT_SAVED") }) }
        assertFalse(Files.exists(f.profileRoot.resolve("custody")))
        draft.close(); assertFailsWith<IllegalStateException> { draft.recoveryCode() }
        val resumed = f.service().resume(code)
        val node = f.service().activate(resumed, LanSyncGroupRecoverySavedAdmission {})
        assertEquals(LanSyncGroupEnrollmentStage.READY, service.profile()?.stage)
        assertFalse(service.canAbandonUnactivated())
        assertEquals("SYNC_GROUP_PROFILE_ALREADY_ACTIVE", assertFailsWith<IllegalArgumentException> {
            service.abandonUnactivated(LanSyncGroupDraftAbandonAdmission { error("SYNTHETIC_NEVER_APPROVE_ACTIVE_DISCARD") })
        }.message)
        assertFailsWith<IllegalStateException> { resumed.recoveryCode() }
        assertEquals(LanSyncGroupNodeStage.READY, node.inspect().stage)
        val local = FileCiphertextStore(f.profileRoot.resolve("local-replica"))
        val vaultContext = SyncManagementSession.locate(code)
        assertNotNull(local.metadata(vaultContext.key))
        assertFalse(local.listCommits(null, 100).entries.any { it.key == vaultContext.key })
        f.card(); val view = node.inspect(); val commit = node.commit(view.review(setOf("character_cards/card")))
        assertEquals(f.identity.fingerprint, commit.entry.version.author); assertEquals(1L, commit.entry.version.authorSequence)
        val reopened = requireNotNull(f.service().open())
        assertEquals(LanSyncGroupNodeStage.READY, reopened.inspect().stage)
        assertNotEquals(f.identity.fingerprint, SyncDeviceSignatures.decode(draft.profile.trust.ownerCertificate).fingerprint)
        val profileBytes = Files.readAllBytes(f.profileRoot.resolve("profile.protected")).reversedArray().toString(Charsets.UTF_8)
        assertFalse(profileBytes.contains(code)); assertFalse(profileBytes.contains("CCB-MGMT1."))
        val origin = f.profileRoot.resolve("node/author/origin.protected"); Files.delete(origin)
        assertFails { f.service().open() }; assertFalse(Files.exists(origin))
    }

    @Test fun interruptedRootPreparationResumesTheSameGenesisAndNativeOrigins() = withFixture { f ->
        val draft = f.service().prepare(f.request, SyncRootGroupCreateAdmission {}); val code = draft.recoveryCode()
        val genesis = Files.readAllBytes(f.profileRoot.resolve("creation/genesis.protected"))
        assertFailsWith<IOException> { f.service { if (it == "ROOT_DELIVERY_SPEC_RETAINED") throw IOException("SYNTHETIC_ROOT_PREPARATION_REPLY_LOST") }
            .activate(draft, LanSyncGroupRecoverySavedAdmission {}) }
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, f.service().profile()?.stage)
        val authorOrigin = Files.readAllBytes(f.profileRoot.resolve("node/author/origin.protected"))
        draft.close()
        val resumed = f.service().resume(code)
        val node = f.service().activate(resumed, LanSyncGroupRecoverySavedAdmission {})
        assertEquals(LanSyncGroupNodeStage.READY, node.inspect().stage)
        assertContentEquals(genesis, Files.readAllBytes(f.profileRoot.resolve("creation/genesis.protected")))
        assertContentEquals(authorOrigin, Files.readAllBytes(f.profileRoot.resolve("node/author/origin.protected")))
    }

    @Test fun failedReadyProtectionKeepsPreparedStateAndLostReadyNotificationNeedsNoAdmin() = withFixture { f ->
        val draft = f.service().prepare(f.request, SyncRootGroupCreateAdmission {}); val code = draft.recoveryCode()
        f.failReady = true
        assertFailsWith<IOException> { f.service().activate(draft, LanSyncGroupRecoverySavedAdmission {}) }
        assertEquals(LanSyncGroupEnrollmentStage.PREPARED, f.service().profile()?.stage)
        f.failReady = false; draft.close()
        val resumed = f.service().resume(code)
        assertFailsWith<IOException> { f.service { if (it == "GROUP_PROFILE_READY") throw IOException("SYNTHETIC_READY_REPLY_LOST") }
            .activate(resumed, LanSyncGroupRecoverySavedAdmission {}) }
        assertFailsWith<IllegalStateException> { resumed.recoveryCode() }
        assertEquals(LanSyncGroupEnrollmentStage.READY, f.service().profile()?.stage)
        assertEquals(LanSyncGroupNodeStage.READY, requireNotNull(f.service().open()).inspect().stage)
    }
    @Test fun firstReturnFailuresCanExplicitlyArchiveOnlyUnusedSetupAndStartAgainWithoutChangingEntities() {
        for (point in listOf("APPROVED_GROUP_CREATION_RETAINED", "GROUP_GENESIS_RETAINED", "GROUP_MANAGEMENT_VAULT_RETAINED", "GROUP_PROFILE_PREPARED")) {
            withFixture { f ->
                f.card(); val entity = f.data.resolve("entities/character_cards/card.json"); val before = Files.readAllBytes(entity)
                val owner = f.service { if (it == point) throw IOException("SYNTHETIC_FIRST_RETURN_INTERRUPTION") }
                assertFailsWith<IOException> { owner.prepare(f.request, SyncRootGroupCreateAdmission {}) }
                val retained = Files.walk(f.profileRoot).use { paths -> paths.filter {
                    Files.isRegularFile(it) && it.fileName.toString() != "profile.lock"
                }.toList().associate { f.profileRoot.relativize(it).toString() to Files.readAllBytes(it) } }
                assertTrue(owner.canAbandonUnactivated())
                val archive = owner.abandonUnactivated(LanSyncGroupDraftAbandonAdmission {})
                assertTrue(archive.startsWith(f.profileRoot.resolve("abandoned-drafts")))
                retained.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(archive.resolve(name))) }
                assertContentEquals(before, Files.readAllBytes(entity)); assertNull(owner.profile())
                val fresh = f.service().prepare(f.request.copy(operation = SyncStoreId.random()), SyncRootGroupCreateAdmission {})
                assertTrue(fresh.recoveryCode().startsWith("CCB-MGMT1.")); fresh.close()
                assertContentEquals(before, Files.readAllBytes(entity))
            }
        }
    }
    @Test fun interruptedUnusedSetupArchiveCompletesTheRetainedDecisionWithoutDeletingEvidence() = withFixture { f ->
        var interrupt = false
        val owner = f.service { if (interrupt && it == "UNACTIVATED_GROUP_ENTRY_RETAINED") throw IOException("SYNTHETIC_ARCHIVE_REPLY_LOST") }
        val draft = owner.prepare(f.request, SyncRootGroupCreateAdmission {}); interrupt = true
        assertFailsWith<IOException> { owner.abandonUnactivated(LanSyncGroupDraftAbandonAdmission {}) }
        assertFailsWith<IllegalStateException> { draft.recoveryCode() }
        assertTrue(Files.exists(f.profileRoot.resolve("abandon.protected")))
        val archive = f.service().abandonUnactivated(LanSyncGroupDraftAbandonAdmission { error("SYNTHETIC_NO_REAPPROVAL") })
        assertTrue(Files.exists(archive.resolve("creation/intent.protected")))
        assertTrue(Files.exists(archive.resolve("completed.protected"))); assertNull(f.service().profile())
    }
    @Test fun interruptedInitialHeadCompletesTheSameOriginButNormalReopenNeverRebuildsAUsedHead() = withFixture { f ->
        val owner = f.service(); val draft = owner.prepare(f.request, SyncRootGroupCreateAdmission {}); val code = draft.recoveryCode()
        f.failNativeHead = true
        assertFailsWith<IOException> { owner.activate(draft, LanSyncGroupRecoverySavedAdmission {}) }
        val origin = f.profileRoot.resolve("node/native/origin.protected"); val original = Files.readAllBytes(origin)
        val head = f.profileRoot.resolve("node/native/head.protected"); assertFalse(Files.exists(head))
        assertFalse(owner.canAbandonUnactivated())
        assertEquals("SYNC_GROUP_PROFILE_LOCAL_HISTORY_EXISTS", assertFailsWith<IllegalArgumentException> {
            owner.abandonUnactivated(LanSyncGroupDraftAbandonAdmission {})
        }.message)
        draft.close(); val reopened = f.service()
        val node = reopened.activate(reopened.resume(code), LanSyncGroupRecoverySavedAdmission {})
        assertContentEquals(original, Files.readAllBytes(origin)); assertTrue(Files.exists(head))
        f.card(); node.commit(node.inspect().review(setOf("character_cards/card")))
        Files.delete(head)
        assertFails { f.service().open() }; assertFalse(Files.exists(head))
        assertFails { node.initializeNewEnrollment() }; assertFalse(Files.exists(head))
    }
    @Test fun backgroundLockInvalidatesCapturedDraftAndASeparateCodeUnlockCanContinueSetup() = withFixture { f ->
        val owner = f.service(); val draft = owner.prepare(f.request, SyncRootGroupCreateAdmission {}); val code = draft.recoveryCode()
        owner.lockManagement()
        assertFailsWith<IllegalStateException> { draft.recoveryCode() }
        assertFailsWith<IllegalStateException> { owner.activate(draft, LanSyncGroupRecoverySavedAdmission {}) }
        assertFalse(Files.exists(f.profileRoot.resolve("custody")))
        val node = owner.activate(owner.resume(code), LanSyncGroupRecoverySavedAdmission {})
        owner.lockManagement(); assertEquals(LanSyncGroupNodeStage.READY, node.inspect().stage)
        assertNotNull(owner.open())
    }
}
