// Test-only verify85 companion; production inputs are frozen independently.
package com.example.chatbar.lanSync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Opt-in, two separate runner invocations around a data-preserving84→85 cloud install. */
class LanDelivered84UpgradeTest {
    private val json = Json { encodeDefaults = true }

    @Suppress("DEPRECATION")
    private fun context(code: Int): Context {
        assertTrue("Only this explicit synthetic upgrade control may run fixtures",
            InstrumentationRegistry.getArguments().getString("lanDelivered84UpgradeFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(PACKAGE, context.packageName)
        assertEquals(code, context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
        val signatures = requireNotNull(context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_SIGNATURES).signatures)
        assertEquals(SIGNING_CERT, lanSyncHash(signatures.single().toByteArray()))
        return context
    }

    private fun image(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun root(context: Context) = context.filesDir.canonicalFile.toPath().resolve("lan-delivered84-upgrade-proof-v1")
    private fun inventory(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toArray().map { it as Path }
            .associate { root.relativize(it).toString().replace('\\', '/') to lanSyncHash(Files.readAllBytes(it)) }
    }
    private fun store(root: Path): LanSyncStateStore {
        val protection = AndroidLanSyncKeyProtection(KEY_ALIAS)
        return LanSyncStateStore(root.resolve("state"), protection::protect, protection::unprotect)
    }
    private fun workspace(root: Path) = LanSyncWorkspace(root.resolve("data"), root.resolve("capture-scratch"), { Paths.get(it) })

    @Test fun verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade(): Unit = runBlocking {
        val root = root(context(85))
        assertTrue("The84 seed must survive installation; never recreate it here", Files.isRegularFile(root.resolve("proof.json")))
        val evidence = json.parseToJsonElement(String(Files.readAllBytes(root.resolve("proof.json")), Charsets.UTF_8)).jsonObject
        assertEquals(PACKAGE, evidence.getValue("package").jsonPrimitive.content)
        assertEquals(84, evidence.getValue("seedVersionCode").jsonPrimitive.int)
        assertEquals(evidence.getValue("uid").jsonPrimitive.int, android.os.Process.myUid())
        assertEquals(evidence.getValue("firstInstallTime").jsonPrimitive.long, context(85).packageManager.getPackageInfo(PACKAGE, 0).firstInstallTime)
        val expected = evidence.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
        // GROUP-UPGRADE CALL BEGIN
        val legacyBeforeGroupCheck = inventory(root).filterKeys { it != "proof.json" }
        verifyActualAppGroup(context(85), evidence.getValue("groupProof").jsonObject)
        assertEquals("Group read must not change legacy files", legacyBeforeGroupCheck, inventory(root).filterKeys { it != "proof.json" })
        // GROUP-UPGRADE CALL END
        assertEquals(expected, inventory(root).filterKeys { it != "proof.json" })
        assertTrue("Existing Keystore alias is required; new-key creation is not upgrade proof",
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KEY_ALIAS))
        val state = store(root); val identity = state.identity()
        assertEquals(evidence.getValue("identity").jsonPrimitive.content, identity.fingerprint)
        assertEquals(listOf(LanSyncPeer(PEER, "合成可信电脑", "192.168.99.2", 42424)), state.peers())
        val baseline = state.baseline(PEER, identity.fingerprint)
        assertEquals(evidence.getValue("completedTransaction").jsonPrimitive.content, baseline.revision)
        val pending = requireNotNull(state.pending(PEER))
        assertEquals(identity.fingerprint, pending.ownerId)
        assertEquals(baseline.hashes, pending.expectedLocal)
        assertArrayEquals(SENTINEL.toByteArray(), AndroidLanSyncKeyProtection(KEY_ALIAS).unprotect(Files.readAllBytes(root.resolve("state/sentinel.protected"))))
        val data = root.resolve("data"); val storage = JsonFileStorage(data)
        val cards = CharacterRepository(storage).getAll()
        assertEquals("合成升级保全角色", cards.single().name)
        val settings = SettingsRepository(storage); settings.refreshPlayerSettingFromStorage()
        assertEquals("合成升级玩家", settings.playerSetting.first().playerName)
        val studio = NovelAiStudioStateRepository(storage); studio.initialize()
        val history = studio.history.first().single()
        assertEquals("合成升级原文\n保留第二行", history.recipe.basePrompt)
        assertEquals(VIBE, NovelAiInlinePayloadStore(data.toFile()).resolve(requireNotNull(history.recipe.imageGuidance.vibes.single().encodedVibe)))
        assertArrayEquals(image(), Files.readAllBytes(Paths.get(history.images.single().path)))
        val processed = requireNotNull(storage.loadEntity(LanSyncPostProcess.TYPE, "upgrade-result", NovelAiPostProcessResultRecord.serializer()))
        processed.validateMetadata()
        assertEquals(processed.imageSha256, lanSyncHash(Files.readAllBytes(Paths.get(processed.imagePath))))
        val capturedResources = workspace(root).capture(identity.fingerprint).use { capture ->
            assertEquals(baseline.hashes, capture.snapshot.hashes())
            capture.snapshot.resources.toMap()
        }
        // The original inventory must survive unchanged. Only this capture's
        // content-addressed resource cache may add files, never arbitrary scratch.
        val after = inventory(root).filterKeys { it != "proof.json" }
        expected.forEach { (path, hash) -> assertEquals("Original changed: " + path, hash, after[path]) }
        Files.walk(root).use { paths ->
            paths.forEach { assertFalse("Unexpected symbolic link: " + it, Files.isSymbolicLink(it)) }
        }
        (after.keys - expected.keys).forEach { path ->
            val hash = path.removePrefix("capture-scratch/resources/")
            assertTrue("Unexpected new file: " + path,
                path == "capture-scratch/resources/" + hash && hash.matches(Regex("[a-f0-9]{64}")))
            val length = requireNotNull(capturedResources[hash]) { "Resource absent from this capture: " + path }
            val resource = root.resolve(path)
            assertEquals("Resource length: " + path, length, Files.size(resource))
            assertEquals("Resource bytes/name: " + path, hash, LanSyncWorkspace.hashFile(resource, length))
            assertEquals(hash, after.getValue(path))
        }
    }

    // GROUP-UPGRADE ADDITION BEGIN: public app factory only; no private state is exported.
    private suspend fun readyApplication(context: Context): com.example.chatbar.ChatBarApp {
        val app = context.applicationContext as com.example.chatbar.ChatBarApp
        kotlinx.coroutines.withTimeout(90_000) {
            while (!app.lanSyncStartupReady) kotlinx.coroutines.delay(25)
        }
        return app
    }
    private fun appGroupRoot(context: Context): Path =
        context.noBackupFilesDir.canonicalFile.toPath().resolve("lan-sync-v1")
    private fun groupInventory(context: Context): Map<String, String> {
        val root = appGroupRoot(context)
        assertTrue(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        Files.walk(root).use { paths -> paths.forEach { assertFalse("Unexpected group link", Files.isSymbolicLink(it)) } }
        return inventory(root)
    }
    private fun groupAuthor(context: Context, access: LanSyncGroupRuntimeAccess): SyncAuthorJournal {
        val directory = appGroupRoot(context).resolve("group-profile/node/author")
        assertTrue(Files.isRegularFile(directory.resolve("origin.protected"), LinkOption.NOFOLLOW_LINKS))
        val protection = AndroidLanSyncKeyProtection()
        return SyncAuthorJournal(directory, access.trust, access.certificate, protection::protect, protection::unprotect)
    }
    private suspend fun publicGroupPins(context: Context, access: LanSyncGroupRuntimeAccess, operation: SyncStoreId): JsonObject {
        val author = groupAuthor(context, access)
        val state = author.state()
        val entry = requireNotNull(author.entry(operation))
        assertEquals(1L, state.committedSequence)
        assertNull(state.pendingApproved)
        assertEquals(entry, state.last)
        assertEquals(GROUP_UNIT, entry.version.changes.keys.single())
        val current = access.ledger().current()
        val member = current.members.getValue(access.device)
        assertTrue(member.active)
        assertEquals(access.certificate, member.certificate)
        assertEquals(access.trust.group, current.group)
        val inspected = access.node.inspectLocal(recoverNative = false)
        assertEquals(LanSyncGroupNodeStage.READY, inspected.stage)
        val applied = inspected.nativeState.units.getValue(GROUP_UNIT)
        assertEquals(entry.version.changes.getValue(GROUP_UNIT).valueHash, applied.valueHash)
        assertEquals(setOf(entry.version.reference()), applied.frontiers)
        assertEquals(listOf(operation), access.node.sourceOperations())
        val receipt = requireNotNull(access.node.receipt(operation, access.localTarget))
        assertEquals(access.localTarget, receipt.target)
        val row = access.cloudRoutes().rows().single()
        assertFalse(row.enabled)
        assertEquals(GROUP_ROUTE_NAME, row.name)
        assertEquals(GROUP_ROUTE_ENDPOINT, row.endpoint)
        assertEquals(GROUP_ROUTE_USER, row.username)
        val userFile = context.filesDir.canonicalFile.toPath().resolve("entities/character_cards/" + GROUP_CARD + ".json")
        assertTrue(Files.isRegularFile(userFile, LinkOption.NOFOLLOW_LINKS))
        val keyHistory = access.ledger().history().sortedBy { it.sequence }.joinToString("\n") {
            it.reference().id.value + ":" + it.reference().bodyHash + ":" + it.epoch.value + ":" + it.keyCommitment
        }
        return buildJsonObject {
            put("format", 1)
            put("group", access.trust.group.value)
            put("genesisId", access.trust.genesis.id.value); put("genesisHash", access.trust.genesis.bodyHash)
            put("rootCertificateHash", lanSyncHash(access.trust.ownerCertificate.toByteArray(Charsets.UTF_8)))
            put("daily", access.device); put("dailyCertificateHash", lanSyncHash(access.certificate.toByteArray(Charsets.UTF_8)))
            put("recipientPublicKeyHash", lanSyncHash(member.recipientKey.toByteArray(Charsets.UTF_8)))
            put("ledgerId", current.reference().id.value); put("ledgerHash", current.reference().bodyHash)
            put("ledgerSequence", current.sequence); put("epoch", current.epoch.value); put("keyCommitment", current.keyCommitment)
            put("ledgerKeyHistoryHash", lanSyncHash(keyHistory.toByteArray(Charsets.UTF_8)))
            put("localReplicaKind", access.localTarget.kind.name); put("localReplicaId", access.localTarget.id.value)
            put("sourceOperation", operation.value); put("authorSequence", state.committedSequence)
            put("authorVersionId", entry.version.reference().id.value); put("authorVersionHash", entry.version.reference().bodyHash)
            put("localReceiptPlanHash", receipt.planHash); put("localReceiptResourceCount", receipt.resourceCount)
            put("localReceiptMetadataBytes", receipt.metadata.length); put("localReceiptMetadataHash", receipt.metadata.sha256.hex)
            put("nativeSequence", inspected.nativeState.completedSequence); put("nativeValueHash", applied.valueHash)
            put("routeId", row.id.value); put("routeEnabled", row.enabled)
            put("routeName", row.name); put("routeEndpoint", row.endpoint); put("routeUsername", row.username)
            put("appEntityHash", lanSyncHash(Files.readAllBytes(userFile)))
        }
    }
    // GROUP-UPGRADE ADDITION END
    // GROUP-UPGRADE VERIFY BEGIN
    private suspend fun verifyActualAppGroup(context: Context, evidence: JsonObject) {
        assertEquals("e606bf2a2723fbc896323a8071cfd52dde99a4c6", evidence.getValue("sourceSha").jsonPrimitive.content)
        val expected = evidence.getValue("originalFileHashes").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertTrue(expected.isNotEmpty())
        val app = readyApplication(context)
        val owner = app.lanSyncOwner
        // Sticky pause intentionally prevents no-page auto-open. Use the real page's read-only open,
        // never resume the user-paused worker and never manufacture a READY marker.
        val page = androidx.test.core.app.ActivityScenario.launch(LanSyncActivity::class.java)
        try {
            val ready = kotlinx.coroutines.withTimeout(90_000) {
                owner.groupCoordinator.state.first { it.stage == LanSyncGroupPanelStage.READY && !it.busy }
            }
            assertTrue(ready.paused)
            assertFalse(ready.recoveryJoining)
            assertFalse(ready.canResumeExternalRecovery)
            assertFalse(ready.managementUnlocked)
            assertEquals("Original app-owned group files must survive install and normal page opening", expected, groupInventory(context))
            assertTrue("The original production alias must survive; no create-if-absent substitute", KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(GROUP_DEFAULT_ALIAS))
            assertEquals(LanSyncGroupEnrollmentStage.READY, requireNotNull(owner.groupEnrollment.profile()).stage)
            val access = requireNotNull(owner.groupEnrollment.runtimeAccess())
            val pins = evidence.getValue("publicPins").jsonObject
            val operation = SyncStoreId.parse(pins.getValue("sourceOperation").jsonPrimitive.content)
            assertEquals("Public group/key/author/native/cloud pins must remain exact", pins, publicGroupPins(context, access, operation))
            assertEquals("Inspection must preserve every original protected group byte", expected, groupInventory(context))
        } finally { page.close() }
    }
    // GROUP-UPGRADE VERIFY END

    companion object {
        // GROUP-UPGRADE CONSTANTS BEGIN
        private const val GROUP_CARD = "delivered84-group-upgrade-character"
        private const val GROUP_UNIT = "character_cards/" + GROUP_CARD
        private const val GROUP_ROUTE_NAME = "synthetic stopped upgrade route"
        private const val GROUP_ROUTE_ENDPOINT = "https://127.0.0.1:1/ccb-upgrade/"
        private const val GROUP_ROUTE_USER = "synthetic-upgrade-user"
        private const val GROUP_DEFAULT_ALIAS = "chatbar-lan-sync-identity-v1"
        // GROUP-UPGRADE CONSTANTS END
        private const val PACKAGE = "com.example.chatbar.lansyncnext"
        private const val KEY_ALIAS = "ccb-lan-delivered84-upgrade-fixture-v1"
        private const val SIGNING_CERT = "801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7"
        private val PEER = "a".repeat(64)
        private val VIBE = "synthetic-upgrade-vibe-".repeat(250)
        private const val SENTINEL = "synthetic-upgrade-protected-value"
    }
}
