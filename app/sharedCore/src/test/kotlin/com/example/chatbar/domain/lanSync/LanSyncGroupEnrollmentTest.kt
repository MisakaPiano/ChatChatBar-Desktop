package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Actual founder/profile/key/root publication/native node; synthetic platform protection only. */
class LanSyncGroupEnrollmentTest {
    private class Fixture(root: Path) {
        val profileRoot = Files.createDirectory(root.resolve("profile")); val data = Files.createDirectory(root.resolve("data"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { Path.of(it) })
        val identity = LanSyncTlsIdentity.create(); var gated = false; var failReady = false
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) { check(!gated); gated = true; try { work() } finally { gated = false } }
            override suspend fun reload(sessionIds: Set<String>) { check(gated) }
        }
        fun service(observe: (String) -> Unit = {}) = LanSyncGroupEnrollment(profileRoot, workspace, platform, identity, { bytes ->
            if (failReady && bytes.toString(Charsets.UTF_8).contains("\"stage\":\"READY\"")) throw IOException("SYNTHETIC_READY_PROTECTION_FAILED")
            bytes.reversedArray()
        }, { it.reversedArray() }, { _, _, _ -> }, observe)
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys, "a".repeat(64))
        fun card() {
            val path = data.resolve("entities/character_cards/card.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("card", "synthetic native profile", createdAt = 1, updatedAt = 1))
        }
    }
    private fun withFixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-enrollment-").toRealPath()
        try { work(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
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
}
