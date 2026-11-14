package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Real enrolled application/custody/catalog owners; account strings and protection are synthetic. */
class LanSyncGroupCoordinatorCloudSettingsTest {
    private class Fixture(val root: Path) {
        val profile = Files.createDirectory(root.resolve("profile"))
        val data = Files.createDirectory(root.resolve("data"))
        val card = data.resolve("entities/character_cards/card.json")
        val identity = LanSyncTlsIdentity.create()
        val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { Path.of(it) })
        val networkCalls = AtomicInteger()
        @Volatile var onCloudProtect: ((ByteArray) -> Unit)? = null
        @Volatile var onCloudUnprotect: ((ByteArray) -> Unit)? = null
        val releases = mutableListOf<CountDownLatch>()
        private val marker = "\"retired\"".toByteArray(Charsets.US_ASCII)
        private fun catalog(bytes: ByteArray) = bytes.firstOrNull() == '{'.code.toByte() &&
            (0..(bytes.size - marker.size).coerceAtLeast(-1)).any { start -> marker.indices.all { bytes[start + it] == marker[it] } }
        fun protect(bytes: ByteArray): ByteArray {
            if (catalog(bytes)) onCloudProtect?.invoke(bytes)
            return bytes.reversedArray()
        }
        fun unprotect(bytes: ByteArray): ByteArray {
            val plain = bytes.reversedArray()
            try { if (catalog(plain)) onCloudUnprotect?.invoke(plain); return plain }
            catch (failure: Throwable) { plain.fill(0); throw failure }
        }
        private val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) { }
        }
        val enrollment = LanSyncGroupEnrollment(profile, workspace, platform, identity, ::protect, ::unprotect, { _, _, _ -> })
        private val network = object : LanSyncNetwork {
            override val discoveryEnabled = false
            override fun addresses(): List<String> { networkCalls.incrementAndGet(); return listOf("10.0.0.1") }
            override fun accepts(address: InetAddress): Boolean { networkCalls.incrementAndGet(); return address.isLoopbackAddress }
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
                networkCalls.incrementAndGet(); error("SYNTHETIC_CLOUD_CONFIG_MUST_NOT_CONNECT")
            }
        }
        val coordinator = LanSyncGroupCoordinator({ Files.exists(profile.resolve("profile.protected")) || Files.exists(profile.resolve("creation")) },
            { enrollment }, { error("SYNTHETIC_FOUNDER_ONLY") }, network, "synthetic cloud settings device",
            cloudWorkAllowed = { false }, cloudWork = { false })
        lateinit var access: LanSyncGroupRuntimeAccess
        suspend fun ready(await: suspend (() -> Boolean) -> Unit) {
            Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "synthetic preserved native original", createdAt = 1, updatedAt = 1))
            coordinator.createGroup()
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && !coordinator.state.value.busy }
            coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
            await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy && coordinator.view.value != null }
            assertNull(coordinator.state.value.error)
            access = requireNotNull(enrollment.runtimeAccess())
            assertEquals(1, access.node.sourceOperations().size)
        }
        fun author() = SyncAuthorJournal(profile.resolve("node/author"), access.trust,
            Base64.getEncoder().encodeToString(identity.certificate.encoded), ::protect, ::unprotect)
        suspend fun close() {
            releases.forEach(CountDownLatch::countDown)
            coordinator.closeAndDrain(); enrollment.close()
        }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(10) }
    private fun fixture(work: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-coordinator-cloud-settings-").toRealPath()
        val f = Fixture(root)
        try { f.ready(::await); work(f) } finally { f.close(); check(root.toFile().deleteRecursively()) }
    }
    private fun draft(password: ByteArray, name: String = "synthetic private drive") =
        LanSyncGroupCloudDraft(name, "https://synthetic.invalid/private/", "synthetic user", password, enabled = true)
    private suspend fun open(f: Fixture) {
        f.coordinator.openCloudSettings()
        await { f.coordinator.cloudSettings.value.visible && !f.coordinator.cloudSettings.value.busy &&
            f.coordinator.cloudSettings.value.revision == 0L && f.coordinator.cloudSettings.value.error == null }
    }

    @Test fun realProtectedCrudCopiesPasswordSynchronouslyRequiresExplicitEnableAndLeavesDailySourceUntouched() = fixture { f ->
        val operations = f.access.node.sourceOperations()
        val entry = requireNotNull(f.author().entry(operations.single()))
        val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
        val raw = Files.readAllBytes(f.card)
        val dailyError = f.coordinator.state.value.error
        val expected = "synthetic password copied before callback returns".toByteArray(Charsets.UTF_8)
        var protectedPlain: ByteArray? = null
        f.onCloudProtect = { protectedPlain = it }
        open(f)
        val submitted = expected.copyOf()
        f.coordinator.saveCloudRoute(draft(submitted))
        submitted.fill(0)
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.rows.size == 1 }
        assertTrue(submitted.all { it == 0.toByte() })
        assertTrue(requireNotNull(protectedPlain).all { it == 0.toByte() })
        val row = f.coordinator.cloudSettings.value.rows.single()
        assertFalse(row.enabled, "Saving always starts stopped, even when the submitted draft requested enable")
        assertTrue(f.coordinator.cloudSettings.value.canEnable)
        assertTrue(f.coordinator.cloudSettings.value.progress.isEmpty())
        val catalog = f.access.cloudRoutes()
        assertEquals(listOf(row), catalog.rows())
        val file = f.profile.resolve("app-runtime/cloud-connections/cloud-routes.protected")
        val persisted = Files.readAllBytes(file).toString(Charsets.UTF_8)
        assertFalse(persisted.contains(row.endpoint)); assertFalse(persisted.contains(row.username))
        assertFalse(persisted.contains(expected.toString(Charsets.UTF_8)))

        f.coordinator.setCloudRouteEnabled(row.id, true)
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.error == null &&
            f.coordinator.cloudSettings.value.rows.singleOrNull()?.enabled == true }
        // Platform permission remains unavailable; enabled intent is not network delivery.
        assertTrue(catalog.rows().single().enabled)
        catalog.credentials(row.id).use { credentials ->
            var borrowed: ByteArray? = null
            credentials.withPassword { borrowed = it; assertContentEquals(expected, it) }
            assertTrue(requireNotNull(borrowed).all { it == 0.toByte() })
        }
        f.coordinator.refreshCloudSettings()
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.rows.singleOrNull()?.enabled == true }
        f.coordinator.setCloudRouteEnabled(SyncStoreId.random(), true)
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.error?.endsWith("SYNC_GROUP_CLOUD_ROUTES_NOT_FOUND") == true }
        assertEquals(dailyError, f.coordinator.state.value.error)
        f.coordinator.setCloudRouteEnabled(row.id, false)
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.error == null &&
            f.coordinator.cloudSettings.value.rows.singleOrNull()?.enabled == false }
        assertFalse(catalog.rows().single().enabled)
        f.coordinator.removeCloudRoute(row.id)
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.rows.isEmpty() }
        assertTrue(catalog.rows().isEmpty())
        val replacementPassword = expected.copyOf()
        f.coordinator.saveCloudRoute(draft(replacementPassword))
        replacementPassword.fill(0)
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.rows.size == 1 }
        assertNotEquals(row.id, f.coordinator.cloudSettings.value.rows.single().id)
        assertFalse(f.coordinator.cloudSettings.value.rows.single().enabled)
        f.coordinator.lockManagement()
        assertFalse(f.coordinator.cloudSettings.value.visible)
        assertTrue(f.coordinator.cloudSettings.value.rows.isEmpty())
        assertNull(f.coordinator.cloudSettings.value.error)
        assertEquals(operations, f.access.node.sourceOperations())
        assertEquals(1L, f.author().state().committedSequence)
        assertEquals(entry, f.author().entry(operations.single()))
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertContentEquals(raw, Files.readAllBytes(f.card))
        assertTrue(f.workspace.completedRecoveries(f.identity.fingerprint).isEmpty())
        assertEquals(dailyError, f.coordinator.state.value.error)
        assertNull(f.coordinator.state.value.cloud.lastSuccessAt)
        assertEquals(0, f.networkCalls.get())
        expected.fill(0)
    }

    @Test fun closeAndDrainRejectsLateRefreshAndCancelsQueuedPasswordSaveWithoutRepublishingUiOrDisk() = fixture { f -> coroutineScope {
        open(f)
        val originalPassword = "synthetic first retained secret".toByteArray(Charsets.UTF_8)
        f.coordinator.saveCloudRoute(draft(originalPassword, "synthetic retained route"))
        originalPassword.fill(0)
        await { !f.coordinator.cloudSettings.value.busy && f.coordinator.cloudSettings.value.rows.size == 1 }
        val row = f.coordinator.cloudSettings.value.rows.single()
        val file = f.profile.resolve("app-runtime/cloud-connections/cloud-routes.protected")
        val retained = Files.readAllBytes(file)
        val operations = f.access.node.sourceOperations()
        val origin = Files.readAllBytes(f.profile.resolve("node/author/origin.protected"))
        f.coordinator.closeCloudSettings()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1).also(f.releases::add)
        var plain: ByteArray? = null
        f.onCloudUnprotect = {
            plain = it; entered.complete(Unit)
            var interrupted = false
            try {
                while (true) try {
                    check(release.await(20, TimeUnit.SECONDS)) { "SYNTHETIC_CLOUD_GATE_NOT_RELEASED" }; break
                } catch (_: InterruptedException) { interrupted = true }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        f.coordinator.openCloudSettings()
        withTimeout(20_000) { entered.await() }
        val queuedPassword = "synthetic queued secret must never persist".toByteArray(Charsets.UTF_8)
        f.coordinator.saveCloudRoute(draft(queuedPassword, "synthetic queued route"))
        queuedPassword.fill(0)
        f.coordinator.closeCloudSettings()
        assertFalse(f.coordinator.cloudSettings.value.visible)
        assertFalse(f.coordinator.cloudSettings.value.busy)
        assertTrue(f.coordinator.cloudSettings.value.rows.isEmpty())
        val closing = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.closeAndDrain() }
        assertFalse(closing.isCompleted, "Terminal close must await the still running local catalog reader")
        release.countDown()
        withTimeout(20_000) { closing.await() }
        f.onCloudUnprotect = null
        assertTrue(requireNotNull(plain).all { it == 0.toByte() })
        assertTrue(queuedPassword.all { it == 0.toByte() })
        val closed = f.coordinator.cloudSettings.value
        assertFalse(closed.visible); assertFalse(closed.busy)
        assertTrue(closed.rows.isEmpty()); assertTrue(closed.progress.isEmpty())
        assertNull(closed.error); assertNull(closed.notice)
        assertEquals(listOf(row), f.access.cloudRoutes().rows())
        assertContentEquals(retained, Files.readAllBytes(file))
        f.coordinator.openCloudSettings()
        f.coordinator.saveCloudRoute(draft(ByteArray(0), "synthetic forbidden post-close route"))
        assertEquals(closed, f.coordinator.cloudSettings.value)
        assertEquals(listOf(row), f.access.cloudRoutes().rows())
        assertEquals(operations, f.access.node.sourceOperations())
        assertEquals(1L, f.author().state().committedSequence)
        assertContentEquals(origin, Files.readAllBytes(f.profile.resolve("node/author/origin.protected")))
        assertEquals(0, f.networkCalls.get())
    } }
}
