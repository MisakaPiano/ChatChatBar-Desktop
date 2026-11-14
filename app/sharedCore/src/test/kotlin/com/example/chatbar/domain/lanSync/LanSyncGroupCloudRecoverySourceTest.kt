package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.*

/** Empty independent recovery root and actual small synthetic HTTP; no enrolled daily credentials. */
class LanSyncGroupCloudRecoverySourceTest {
    private fun request(key: CiphertextObjectKey, bytes: ByteArray) = CiphertextPutRequest(key, SyncStoreId.random(),
        CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes))))
    private fun read(store: CiphertextStore, metadata: CiphertextObjectMetadata): ByteArray = ByteArrayOutputStream().use {
        assertEquals(metadata, store.readTo(metadata.key, it, metadata.descriptor)); it.toByteArray()
    }
    private class Fixture(val shared: WeakFixture) : AutoCloseable {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        val transport = WebDavHttpTransport(shared.server.url("/sync/").newBuilder().host("127.0.0.1").build(), client,
            { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        fun directory(name: String): Path = Files.createDirectory(shared.root.resolve(name))
        val whole = WeakWebDavAttemptStore(transport, directory("whole"), shared.route, WebDavIoBudget(minimumFreeBytes = 0))
        val parts = WeakWebDavChunkAssembly(transport, directory("parts"), shared.route, chunkBytes = 4096,
            budget = WebDavIoBudget(minimumFreeBytes = 0))
        fun source(name: String, checkActive: () -> Unit = {}): LanSyncGroupCloudRecoverySource {
            val root = directory(name)
            val route = SyncStoreId.random()
            assertNotEquals(shared.route, route)
            val whole = WeakWebDavAttemptStore(transport, Files.createDirectory(root.resolve("whole")), route,
                WebDavIoBudget(minimumFreeBytes = 0))
            val parts = WeakWebDavChunkAssembly(transport, Files.createDirectory(root.resolve("parts")), route,
                chunkBytes = 4096, budget = WebDavIoBudget(minimumFreeBytes = 0))
            return LanSyncGroupCloudRecoverySource(Files.createDirectory(root.resolve("reader")), whole, parts, checkActive)
        }
        override fun close() {
            transport.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun emptyProfileAuthenticatesVaultPastBadWholeClaimThenRecoversOriginalRootFromChunkOnlyHistory() = weakFixture { shared ->
        runBlocking { Fixture(shared).use { f ->
            LanSyncGroupAdministrationTest.Fixture(f.directory("original")).use { original ->
                original.seed(); original.rotateBeforeJoin()
                val access = requireNotNull(original.service.runtimeAccess())
                suspend fun scan() {
                    var cursor: CommitCursor? = null
                    do { cursor = access.node.discover(access.replica, cursor).nextCursor } while (cursor != null)
                }
                scan()
                val card = original.data.resolve("entities/character_cards/card.json")
                Files.createDirectories(card.parent)
                LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "synthetic cloud recovery original", createdAt = 1, updatedAt = 1))
                val sourceBytes = Files.readAllBytes(card)
                val first = access.node.commit(access.node.inspect().review(setOf("character_cards/card")))
                access.node.upload(first.entry.request.operation, access.replica, access.localTarget); scan()
                val vault = original.service.encryptedManagementBackup()
                val bad = ByteArray(47) { 3 }
                f.whole.appendAttempt(request(vault.key, bad), bad.inputStream())
                val bytes = vault.bytes()
                access.targetPublication(f.directory("original-cloud-target"), SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, shared.route))
                    .upload(f.parts)
                val oldLedger = original.custody().ledger().current()
                val offlineProfile = original.root.resolve("offline-old-profile")
                val offlineData = original.root.resolve("offline-old-data")
                Files.move(original.profile, offlineProfile); Files.move(original.data, offlineData)
                val empty = f.directory("empty-device")
                f.source("private-cloud-read").use { source ->
                    source.prepare(original.code).use { preview ->
                        assertEquals(vault.context, preview.vault)
                        assertTrue(preview.rejectedVaultCandidates > 0)
                        assertEquals(vault.context.genesis, preview.trust.genesis)
                        assertFalse(Files.exists(empty.resolve("management")))
                        assertFalse(Files.exists(empty.resolve("joining")))
                        assertFailsWith<CiphertextStoreException> {
                            source.backend.put(request(vault.key, bytes), bytes.inputStream())
                        }
                        val managementRoot = Files.createDirectory(empty.resolve("management"))
                        LanSyncGroupManagementRecovery(managementRoot).use { manager ->
                            var approvals = 0
                            val result = source.recover(preview, original.code, manager, LanSyncManagementRecoveryAdmission { trust ->
                                approvals++; assertEquals(preview.trust, trust)
                            })
                            assertEquals(1, approvals)
                            assertEquals(LanSyncManagementRecoveryStage.READY, result.stage)
                            assertEquals(oldLedger, manager.ledger().current())
                            assertFalse(Files.exists(empty.resolve("joining")), "Root recovery alone never creates a daily identity")
                            assertFalse(Files.exists(empty.resolve("entities")), "Root recovery never activates native user records")
                            val dailyRoot = Files.createDirectory(empty.resolve("daily"))
                            val data = Files.createDirectory(empty.resolve("native-data"))
                            val workspace = LanSyncWorkspace(data, Files.createDirectory(empty.resolve("native-work")), { Path.of(it) })
                            val identity = LanSyncTlsIdentity.create()
                            var gated = false
                            fun joining() = LanSyncGroupJoining(dailyRoot, workspace, object : LanSyncPlatform {
                                override suspend fun exclusive(work: suspend () -> Unit) {
                                    check(!gated); gated = true; try { work() } finally { gated = false }
                                }
                                override suspend fun reload(sessionIds: Set<String>) { check(gated) }
                            }, identity, { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
                            val daily = joining()
                            daily.prepareRecovery(manager.recoveryBootstrap().anchor) { }
                            assertNull(daily.pendingRoute())
                            val approval = daily.prepareRecoveryDevice(manager, "synthetic recovered daily device")
                            assertEquals(oldLedger, manager.ledger().current(), "Preparation never signs a root change")
                            manager.addRecoveryDevice(approval.acknowledge(approval.request.approvalBinding))
                            val control = manager.recoveryBootstrap()
                            daily.acceptRecoveryBootstrap(control.anchor)
                            val joined = source.withControl(preview, control) { input -> daily.activate(input) }
                            assertEquals(LanSyncGroupJoinStage.READY, joined.profile.stage)
                            val node = requireNotNull(joined.node)
                            node.apply(requireNotNull(node.inspect().plan))
                            val restored = Files.list(data.resolve("entities/character_cards")).use { it.toList().single() }
                            assertEquals("synthetic cloud recovery original", requireNotNull(LanSyncJsonFiles.read(restored, CharacterCard.serializer())).name)
                            val author = SyncAuthorJournal(dailyRoot.resolve("joining/node/author"), joined.profile.trust,
                                Base64.getEncoder().encodeToString(identity.certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
                            assertEquals(0L, author.state().committedSequence)
                            assertNotEquals(original.identity.fingerprint, identity.fingerprint)
                            assertEquals(oldLedger.epoch, manager.ledger().current().epoch)
                            manager.lock()
                            assertNotNull(joining().open())
                            assertNotNull(joining().runtimeAccess())
                            assertContentEquals(sourceBytes, Files.readAllBytes(offlineData.resolve("entities/character_cards/card.json")))
                            assertFalse(Files.exists(original.profile))
                            assertFalse(Files.exists(original.data))
                        }
                    }
                }
            }
        } }
    }

    @Test fun failedVaultAuthenticationAndForeignOrClosedPreviewCannotCreateManagementOrigins() = weakFixture { shared ->
        runBlocking { Fixture(shared).use { f ->
            LanSyncGroupAdministrationTest.Fixture(f.directory("original")).use { original ->
                original.seed()
                val vault = original.service.encryptedManagementBackup(); val bytes = vault.bytes()
                val bad = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                f.whole.appendAttempt(request(vault.key, bad), bad.inputStream())
                f.source("first-read").use { source ->
                    assertEquals("SYNC_GROUP_CLOUD_RECOVERY_VAULT_UNAVAILABLE",
                        assertFailsWith<IllegalStateException> { source.prepare(original.code) }.message)
                    f.parts.append(request(vault.key, bytes), bytes.inputStream())
                    val managerRoot = f.directory("unapproved-manager")
                    LanSyncGroupManagementRecovery(managerRoot).use { manager ->
                        source.prepare(original.code).use { preview ->
                            f.source("second-read").use { other ->
                                assertEquals("SYNC_GROUP_CLOUD_RECOVERY_PREVIEW_CHANGED", assertFailsWith<IllegalArgumentException> {
                                    other.recover(preview, original.code, manager, LanSyncManagementRecoveryAdmission { error("must not approve") })
                                }.message)
                            }
                            preview.close()
                            assertEquals("SYNC_GROUP_CLOUD_RECOVERY_PREVIEW_CLOSED", assertFailsWith<IllegalArgumentException> {
                                source.recover(preview, original.code, manager, LanSyncManagementRecoveryAdmission { error("must not approve") })
                            }.message)
                            assertFalse(Files.exists(managerRoot.resolve("profile.protected")))
                            assertFalse(Files.exists(managerRoot.resolve("root-actions")))
                        }
                    }
                }
            }
        } }
    }

    @Test fun cachedCiphertextStillStopsAtTheNextCallerChunkWhenTheRecoverySessionLocks() = weakFixture { shared ->
        Fixture(shared).use { f ->
            val bytes = ByteArray(256 * 1024) { it.toByte() }
            val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
            val request = request(key, bytes)
            f.whole.appendAttempt(request, bytes.inputStream())
            var permitted = true
            val refusal = IOException("SYNTHETIC_RECOVERY_LOCKED")
            f.source("guarded-read", { if (!permitted) throw refusal }).use { source ->
                val metadata = CiphertextObjectMetadata(key, request.descriptor)
                assertContentEquals(bytes, read(source.backend, metadata))
                var written = 0
                val sink = object : OutputStream() {
                    override fun write(value: Int) { written++; permitted = false }
                    override fun write(value: ByteArray, offset: Int, length: Int) { written += length; permitted = false }
                }
                assertSame(refusal, assertFailsWith<IOException> { source.backend.readTo(key, sink, request.descriptor) })
                assertTrue(written in 1 until bytes.size)
            }
        }
    }

    @Test fun typedSessionGuardFailureSurvivesTheInboxBoundaryWithoutBecomingBadRemoteEvidence() = weakFixture { shared ->
        runBlocking { Fixture(shared).use { f ->
            LanSyncGroupAdministrationTest.Fixture(f.directory("original")).use { original ->
                original.seed()
                val vault = original.service.encryptedManagementBackup(); val bytes = vault.bytes()
                f.parts.append(request(vault.key, bytes), bytes.inputStream())
                var permitted = true
                val refusal = CiphertextStoreException(CiphertextStoreFailure.TRANSIENT)
                val root = f.directory("interrupted-manager")
                f.source("interruptible-read", { if (!permitted) throw refusal }).use { source ->
                    source.prepare(original.code).use { preview ->
                        LanSyncGroupManagementRecovery(root).use { manager ->
                            assertSame(refusal, assertFailsWith<CiphertextStoreException> {
                                source.recover(preview, original.code, manager, LanSyncManagementRecoveryAdmission { permitted = false })
                            })
                            assertFalse(Files.exists(root.resolve("root-actions/origin.protected")))
                            assertFalse(Files.exists(root.resolve("custody/group.protected")))
                        }
                    }
                }
            }
        } }
    }
}
