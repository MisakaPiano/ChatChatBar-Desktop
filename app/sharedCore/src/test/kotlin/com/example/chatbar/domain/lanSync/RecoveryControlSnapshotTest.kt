package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real management controls, independent ciphertext ownership and cold corruption refusal. */
class RecoveryControlSnapshotTest {
    private fun fixture(work: suspend (LanSyncGroupAdministrationTest.Fixture, LanSyncGroupManagementRecovery) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-recovery-control-snapshot-").toRealPath()
        try { LanSyncGroupAdministrationTest.Fixture(root).use { original ->
            original.seed(); original.rotateBeforeJoin()
            LanSyncGroupManagementRecovery(Files.createDirectory(root.resolve("manager"))).use { manager ->
                manager.recover(original.code, original.service.encryptedManagementBackup().bytes(),
                    FileCiphertextStore(original.profile.resolve("local-replica")), LanSyncManagementRecoveryAdmission { })
                work(original, manager)
            }
        } } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun entries(source: CiphertextStore): List<CiphertextObjectMetadata> {
        val result = mutableListOf<CiphertextObjectMetadata>(); var cursor: CommitCursor? = null
        do { val page = source.listCommits(cursor, 1); result += page.entries; cursor = page.nextCursor } while (cursor != null)
        return result
    }
    private fun bytes(source: CiphertextStore, row: CiphertextObjectMetadata): ByteArray = ByteArrayOutputStream().use {
        assertEquals(row, source.readTo(row.key, it, row.descriptor)); it.toByteArray()
    }
    private fun files(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }
        .toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) } }
    private fun unchanged(expected: Map<String, ByteArray>, root: Path) {
        val actual = files(root); assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, value) -> assertContentEquals(value, actual.getValue(name), name) }
    }

    @Test fun retainedCiphertextSurvivesIssuerLockAndColdOpenAndStillRequiresTheExistingCryptoBootstrap() = fixture { original, manager ->
        val issuer = manager.recoveryBootstrap()
        val selected = entries(issuer.source) + CiphertextObjectMetadata(issuer.anchor.vault.key, issuer.anchor.vaultDescriptor)
        val expected = selected.associateWith { bytes(issuer.source, it) }
        val directory = original.root.resolve("independent-controls")
        val retained = LanSyncGroupRecoveryControlSnapshot.retain(directory, issuer)
        val raw = files(directory)
        manager.lock(); manager.close()
        assertFailsWith<IllegalStateException> { issuer.source.metadata(issuer.anchor.vault.key) }
        assertEquals(issuer.anchor, retained.anchor)
        expected.forEach { (row, body) -> assertEquals(row, retained.source.metadata(row.key)); assertContentEquals(body, bytes(retained.source, row)) }
        val cold = LanSyncGroupRecoveryControlSnapshot.open(directory)
        assertEquals(retained.anchor, cold.anchor); assertEquals(entries(retained.source), entries(cold.source))
        expected.forEach { (row, body) -> assertContentEquals(body, bytes(cold.source, row)) }
        assertEquals(CiphertextStoreFailure.UNSUPPORTED, assertFailsWith<CiphertextStoreException> {
            cold.source.put(CiphertextPutRequest(selected.first().key, SyncStoreId.random(), selected.first().descriptor), expected.getValue(selected.first()).inputStream())
        }.failure)
        assertNull(cold.source.metadata(CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())))
        LanSyncGroupManagementRecovery(Files.createDirectory(original.root.resolve("second-manager"))).use { reopened ->
            val result = reopened.recover(original.code, expected.getValue(CiphertextObjectMetadata(issuer.anchor.vault.key, issuer.anchor.vaultDescriptor)),
                cold.source, LanSyncManagementRecoveryAdmission { assertEquals(issuer.anchor.trust.group, it.group) }, knownTrust = issuer.anchor.trust)
            assertEquals(LanSyncManagementRecoveryStage.READY, result.stage)
            assertEquals(issuer.anchor.trust.checkpoint, reopened.ledger().current().reference())
        }
        unchanged(raw, directory)
        assertFalse(Files.exists(directory.resolve("profile.protected"))); assertFalse(Files.exists(directory.resolve("root-actions")))
        assertFalse(Files.exists(directory.resolve("joining")))
        raw.values.forEach { assertFalse(it.toString(Charsets.ISO_8859_1).contains(original.code)) }
    }

    @Test fun missingReadyChangedManifestIdentityAndMissingCiphertextRefuseWithoutRepairingAnySnapshotOrigin() = fixture { original, manager ->
        val issuer = manager.recoveryBootstrap()
        for (damage in listOf("ready", "manifest", "identity", "missing-identity", "object")) {
            val directory = original.root.resolve("damaged-$damage")
            LanSyncGroupRecoveryControlSnapshot.retain(directory, issuer)
            when (damage) {
                "ready" -> Files.delete(directory.resolve("ready.json"))
                "manifest" -> {
                    val path = directory.resolve("manifest.json"); val value = Files.readAllBytes(path)
                    value[value.lastIndex] = (value.last().toInt() xor 1).toByte(); Files.write(path, value)
                }
                "identity" -> Files.write(directory.resolve("ciphertext/store.id"), SyncStoreId.random().value.toByteArray(Charsets.US_ASCII))
                "missing-identity" -> Files.delete(directory.resolve("ciphertext/store.id"))
                "object" -> Files.walk(directory.resolve("ciphertext")).use { paths ->
                    Files.delete(paths.filter { it.fileName.toString().endsWith(".obj") }.findFirst().orElseThrow())
                }
            }
            val damaged = files(directory)
            assertFailsWith<IllegalArgumentException> { LanSyncGroupRecoveryControlSnapshot.open(directory) }
            unchanged(damaged, directory)
            assertFailsWith<IllegalArgumentException> { LanSyncGroupRecoveryControlSnapshot.retain(directory, issuer) }
            unchanged(damaged, directory)
        }
        val good = original.root.resolve("already-retained")
        val retained = LanSyncGroupRecoveryControlSnapshot.retain(good, issuer); val saved = files(good)
        val changed = LanSyncGroupRecoveryBootstrap(issuer.anchor.copy(dataHeads = setOf(SyncVersionRef(SyncStoreId.random(), "9".repeat(64)))), issuer.source)
        assertEquals("SYNC_GROUP_RECOVERY_CONTROL_ANCHOR_CHANGED", assertFailsWith<IllegalArgumentException> {
            LanSyncGroupRecoveryControlSnapshot.retain(good, changed)
        }.message)
        assertEquals(retained.anchor, LanSyncGroupRecoveryControlSnapshot.open(good).anchor); unchanged(saved, good)
    }

    @Test fun interruptedRetentionLeavesNoReadyAndAnIndependentReadHonorsItsCallerGuardWithoutCapturingTheIssuer() = fixture { original, manager ->
        val issuer = manager.recoveryBootstrap()
        val failure = IOException("SYNTHETIC_CONTROL_COPY_INTERRUPTED"); val calls = AtomicInteger()
        val broken = LanSyncGroupRecoveryBootstrap(issuer.anchor, object : CiphertextStore by issuer.source {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                calls.incrementAndGet()
                return issuer.source.readTo(key, object : OutputStream() {
                    override fun write(value: Int) { destination.write(value); throw failure }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { destination.write(bytes, offset, length); throw failure }
                }, expected)
            }
        })
        val partial = original.root.resolve("interrupted-controls")
        assertSame(failure, assertFailsWith<IOException> { LanSyncGroupRecoveryControlSnapshot.retain(partial, broken) })
        assertFalse(Files.exists(partial.resolve("ready.json")))
        val retainedEvidence = files(partial); val before = calls.get()
        assertFailsWith<IllegalArgumentException> { LanSyncGroupRecoveryControlSnapshot.open(partial) }
        assertFailsWith<IllegalArgumentException> { LanSyncGroupRecoveryControlSnapshot.retain(partial, broken) }
        assertEquals(before, calls.get()); unchanged(retainedEvidence, partial)

        val active = AtomicBoolean(true); val guardFailure = CiphertextStoreException(CiphertextStoreFailure.TRANSIENT)
        val snapshot = LanSyncGroupRecoveryControlSnapshot.retain(original.root.resolve("guarded-controls"), issuer) {
            if (!active.get()) throw guardFailure
        }
        manager.lock()
        val output = object : OutputStream() {
            var writes = 0
            override fun write(value: Int) { writes++; active.set(false) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; active.set(false) }
        }
        assertSame(guardFailure, assertFailsWith<CiphertextStoreException> {
            snapshot.source.readTo(snapshot.anchor.vault.key, output, snapshot.anchor.vaultDescriptor)
        })
        assertEquals(1, output.writes)
        assertSame(guardFailure, assertFailsWith<CiphertextStoreException> { snapshot.source.listCommits() })
        assertSame(guardFailure, assertFailsWith<CiphertextStoreException> { snapshot.source.metadata(snapshot.anchor.vault.key) })
        active.set(true)
        assertEquals(snapshot.anchor.vaultDescriptor, snapshot.source.metadata(snapshot.anchor.vault.key)?.descriptor)
    }
}
