// Test-only seed84 companion; production inputs are frozen independently.
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

    @Test fun seedSyntheticRepositoryAndProtectedPeerIdentity(): Unit = runBlocking {
        val root = root(context(84))
        assertFalse("Existing proof must never be overwritten by a repeat seed", Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        Files.createDirectory(root)
        val data = Files.createDirectory(root.resolve("data")); val storage = JsonFileStorage(data)
        val imagePath = data.resolve("images/upgrade.png"); Files.createDirectories(imagePath.parent)
        val png = image(); Files.write(imagePath, png)
        val card = CharacterCard("upgrade-character", "合成升级保全角色", avatar = imagePath.toString(), createdAt = 1, updatedAt = 1)
        CharacterRepository(storage).save(card)
        storage.saveSingleton("player_setting", PlayerSetting(id = "synthetic-player", playerName = "合成升级玩家", globalPersona = "仅用于隔离升级验证", desktopAvatar = imagePath.toString(), updatedAt = 1), PlayerSetting.serializer())
        val encoded = NovelAiInlinePayloadStore(data.toFile()).retain(VIBE)
        assertTrue(encoded.startsWith(NovelAiInlinePayloadStore.PREFIX))
        NovelAiStudioStateRepository(storage).saveHistory(NovelAiGenerationHistoryEntry("upgrade-history",
            listOf(NovelAiGenerationHistoryImage(imagePath.toString(), 42)),
            NovelAiGenerationRecipe(basePrompt = "合成升级原文\n保留第二行", negativePrompt = "合成负面",
                imageGuidance = NovelAiImageGuidanceDraft(vibes = listOf(NovelAiVibeReferenceDraft("upgrade-vibe", encodedVibe = encoded)))), createdAt = 1))
        storage.saveEntity(LanSyncPostProcess.TYPE, "upgrade-result", NovelAiPostProcessResultRecord("upgrade-result", "b".repeat(64),
            "UPSCALE", "ORIGINAL", .5f, 0f, imagePath.toString(), lanSyncHash(png), 1), NovelAiPostProcessResultRecord.serializer())
        val state = store(root); val identity = state.identity()
        state.trust(LanSyncPeer(PEER, "合成可信电脑", "192.168.99.2", 42424))
        val transaction = UUID.randomUUID().toString()
        workspace(root).capture(identity.fingerprint).use { capture ->
            val plan = LanSyncPlan(transaction, capture.snapshot, emptyMap())
            state.complete(PEER, identity.fingerprint, plan)
            state.pending(LanSyncPending(PEER, plan.copy(transaction = UUID.randomUUID().toString()),
                capture.snapshot.hashes(), capture.snapshot.hashes(), ownerId = identity.fingerprint))
        }
        Files.write(root.resolve("state/sentinel.protected"), AndroidLanSyncKeyProtection(KEY_ALIAS).protect(SENTINEL.toByteArray()))
        // GROUP-UPGRADE CALL BEGIN
        val groupProof = seedActualAppGroup(context(84))
        // GROUP-UPGRADE CALL END
        val evidence = buildJsonObject {
            // GROUP-UPGRADE FIELD BEGIN
            put("groupProof", groupProof)
            // GROUP-UPGRADE FIELD END
            put("package", PACKAGE); put("seedVersionCode", 84); put("identity", identity.fingerprint)
            put("uid", android.os.Process.myUid())
            put("firstInstallTime", context(84).packageManager.getPackageInfo(PACKAGE, 0).firstInstallTime)
            put("completedTransaction", transaction)
            put("files", JsonObject(inventory(root).mapValues { JsonPrimitive(it.value) }))
        }
        Files.write(root.resolve("proof.json"), evidence.toString().toByteArray(Charsets.UTF_8))
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
    // GROUP-UPGRADE SEED BEGIN
    private suspend fun seedActualAppGroup(context: Context): JsonObject {
        val app = readyApplication(context)
        val owner = app.lanSyncOwner
        assertFalse("Fresh CI app group must never be replaced", Files.exists(appGroupRoot(context).resolve("group-profile"), LinkOption.NOFOLLOW_LINKS))
        // V5 returns Unit here. Wait for its durable sticky pause rather than invoking a newer API.
        owner.pauseBackground()
        kotlinx.coroutines.withTimeout(90_000) { owner.backgroundState.first { it.phase == AndroidLanSyncBackgroundPhase.PAUSED } }
        val enrollment = owner.groupEnrollment
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(),
            lanSyncHash("synthetic-v5-native-group-upgrade".toByteArray(Charsets.UTF_8)))
        enrollment.prepare(request, SyncRootGroupCreateAdmission { require(it == request) }).use { draft ->
            // The synthetic runner explicitly acknowledges local code custody; the code never leaves the draft/session.
            enrollment.activate(draft, LanSyncGroupRecoverySavedAdmission { require(it == draft.profile) })
        }
        assertEquals(LanSyncGroupEnrollmentStage.READY, requireNotNull(enrollment.profile()).stage)
        val access = requireNotNull(enrollment.runtimeAccess())
        val userFile = context.filesDir.canonicalFile.toPath().resolve("entities/character_cards/" + GROUP_CARD + ".json")
        assertFalse("A repeat group source seed must never overwrite native data", Files.exists(userFile, LinkOption.NOFOLLOW_LINKS))
        app.jsonFileStorage.saveEntity("character_cards", GROUP_CARD,
            CharacterCard(GROUP_CARD, "合成设备组升级角色", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
        val inspected = access.node.inspectLocal()
        assertTrue("The V5 source must actually capture this exact new unit", requireNotNull(inspected.plan).units.containsKey(GROUP_UNIT))
        val review = inspected.review(setOf(GROUP_UNIT))
        assertTrue(review.requiredBindings.isEmpty())
        val source = access.node.commit(review)
        assertEquals(setOf(GROUP_UNIT), source.entry.version.changes.keys)
        assertEquals(1L, source.entry.version.authorSequence)
        val receipt = access.node.upload(source.entry.request.operation, access.replica, access.localTarget)
        assertEquals(access.localTarget, receipt.target)
        var cursor: CommitCursor? = null
        do { cursor = access.node.discoverLocal(cursor).nextCursor } while (cursor != null)
        val secret = "synthetic-upgrade-route-only".toByteArray(Charsets.UTF_8)
        try {
            access.cloudRoutes().add(LanSyncGroupCloudDraft(GROUP_ROUTE_NAME, GROUP_ROUTE_ENDPOINT, GROUP_ROUTE_USER, secret, enabled = false))
        } finally { secret.fill(0) }
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(GROUP_DEFAULT_ALIAS))
        owner.groupCoordinator.open()
        val ready = kotlinx.coroutines.withTimeout(90_000) {
            owner.groupCoordinator.state.first { it.stage == LanSyncGroupPanelStage.READY && !it.busy }
        }
        assertTrue(ready.paused)
        val pins = publicGroupPins(context, access, source.entry.request.operation)
        val originals = groupInventory(context)
        assertTrue(originals.containsKey("identity.protected"))
        assertTrue(originals.containsKey("group-profile/profile.protected"))
        assertTrue(originals.containsKey("group-profile/app-runtime/cloud-connections/cloud-routes.protected"))
        return buildJsonObject {
            put("sourceSha", "e606bf2a2723fbc896323a8071cfd52dde99a4c6")
            put("publicPins", pins)
            put("originalFileHashes", JsonObject(originals.mapValues { JsonPrimitive(it.value) }))
        }
    }
    // GROUP-UPGRADE SEED END

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
