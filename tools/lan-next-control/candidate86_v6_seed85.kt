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

import android.content.pm.PackageManager
import android.os.Process
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.LanSyncRecord
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import com.example.chatbar.domain.lanSync.lanSyncHash
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in, two separate runner invocations around a data-preserving85→86 cloud install. */
class LanDelivered85UpgradeTest {
    private val json = Json { encodeDefaults = true }

    @Suppress("DEPRECATION")
    private fun context(code: Int): Context {
        assertTrue("Only this explicit synthetic upgrade control may run fixtures",
            InstrumentationRegistry.getArguments().getString("lanDelivered85UpgradeFixture") == "true")
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

    private fun root(context: Context) = context.filesDir.canonicalFile.toPath().resolve("lan-delivered85-upgrade-proof-v1")
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
        val root = root(context(85))
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
        var parallelResources: Map<String, Long> = emptyMap()
        var parallelBodies: Map<String, Long> = emptyMap()
        workspace(root).capture(identity.fingerprint).use { capture ->
            parallelResources = capture.snapshot.resources.toMap()
            parallelBodies = Android85RetainedEvidence.bodyFiles(capture.snapshot.records)
            val plan = LanSyncPlan(transaction, capture.snapshot, emptyMap())
            state.complete(PEER, identity.fingerprint, plan)
            state.pending(LanSyncPending(PEER, plan.copy(transaction = UUID.randomUUID().toString()),
                capture.snapshot.hashes(), capture.snapshot.hashes(), ownerId = identity.fingerprint))
        }
        Files.write(root.resolve("state/sentinel.protected"), AndroidLanSyncKeyProtection(KEY_ALIAS).protect(SENTINEL.toByteArray()))
        // GROUP-UPGRADE CALL BEGIN
        val groupProof = seedActualAppGroup(context(85))
        // GROUP-UPGRADE CALL END
        val evidence = buildJsonObject {
            // GROUP-UPGRADE FIELD BEGIN
            put("parallelResources", JsonObject(parallelResources.mapValues { JsonPrimitive(it.value) }))
            put("parallelBodies", JsonObject(parallelBodies.mapValues { JsonPrimitive(it.value) }))
            put("groupProof", groupProof)
            // GROUP-UPGRADE FIELD END
            put("package", PACKAGE); put("seedVersionCode", 85); put("identity", identity.fingerprint)
            put("uid", android.os.Process.myUid())
            put("firstInstallTime", context(85).packageManager.getPackageInfo(PACKAGE, 0).firstInstallTime)
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
        // V6 returns Unit here. Wait for its durable sticky pause rather than invoking a newer API.
        owner.pauseBackground()
        kotlinx.coroutines.withTimeout(90_000) { owner.backgroundState.first { it.phase == AndroidLanSyncBackgroundPhase.PAUSED } }
        val enrollment = owner.groupEnrollment
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(),
            lanSyncHash("synthetic-v6-native-group-upgrade".toByteArray(Charsets.UTF_8)))
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
        val ordinaryChat = Android85RetainedEvidence.seedOrdinaryChat(app)
        val inspected = access.node.inspectLocal()
        assertTrue("The V6 source must actually capture this exact new unit", requireNotNull(inspected.plan).units.containsKey(GROUP_UNIT))
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
        val oldSnapshot = requireNotNull(inspected.plan).before
        val actualWorkspace = LanSyncWorkspace(app.filesDir.canonicalFile.toPath(), appGroupRoot(context).resolve("workspace"), { Paths.get(it) })
        val checkpoint = requireNotNull(actualWorkspace.recovery.read(source.entry.request.sourceCheckpoint))
        actualWorkspace.recovery.verify(checkpoint)
        val sealedSnapshot = actualWorkspace.recovery.snapshot(checkpoint)
        check(sealedSnapshot.hashes().getValue(GROUP_UNIT) == source.entry.version.changes.getValue(GROUP_UNIT).valueHash)
        val derivedExpected = buildJsonObject {
            put("workspaceBodies", JsonObject(Android85RetainedEvidence.bodyFiles(oldSnapshot.records).mapValues { JsonPrimitive(it.value) }))
            put("sealedCheckpointBodies", JsonObject(Android85RetainedEvidence.bodyFiles(sealedSnapshot.records).mapValues { JsonPrimitive(it.value) }))
            put("committedUnitBodies", JsonObject(Android85RetainedEvidence.bodyFiles(sealedSnapshot.units().getValue(GROUP_UNIT)).mapValues { JsonPrimitive(it.value) }))
        }
        val originals = groupInventory(context)
        assertTrue(originals.containsKey("identity.protected"))
        assertTrue(originals.containsKey("group-profile/profile.protected"))
        assertTrue(originals.containsKey("group-profile/app-runtime/cloud-connections/cloud-routes.protected"))
        return buildJsonObject {
            put("ordinaryChat", ordinaryChat)
            put("derivedExpected", derivedExpected)
            put("sourceSha", "5c906e6d4a0f31a114326e35a331c4af4dfa6379")
            put("publicPins", pins)
            put("originalFileHashes", JsonObject(originals.mapValues { JsonPrimitive(it.value) }))
        }
    }
    // GROUP-UPGRADE SEED END

    companion object {
        // GROUP-UPGRADE CONSTANTS BEGIN
        private const val GROUP_CARD = "delivered85-group-upgrade-character"
        private const val GROUP_UNIT = "character_cards/" + GROUP_CARD
        private const val GROUP_ROUTE_NAME = "synthetic stopped upgrade route"
        private const val GROUP_ROUTE_ENDPOINT = "https://127.0.0.1:1/ccb-upgrade/"
        private const val GROUP_ROUTE_USER = "synthetic-upgrade-user"
        private const val GROUP_DEFAULT_ALIAS = "chatbar-lan-sync-identity-v1"
        // GROUP-UPGRADE CONSTANTS END
        private const val PACKAGE = "com.example.chatbar.lansyncnext"
        private const val KEY_ALIAS = "ccb-lan-delivered85-upgrade-fixture-v1"
        private const val SIGNING_CERT = "801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7"
        private val PEER = "a".repeat(64)
        private val VIBE = "synthetic-upgrade-vibe-".repeat(250)
        private const val SENTINEL = "synthetic-upgrade-protected-value"
    }
}

internal object Android85RetainedEvidence {
    private const val PACKAGE = "com.example.chatbar.lansyncnext"
    private const val SIGNER = "801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7"
    private const val SESSION = "ccb-v6-retained85-conversation"
    private const val CARD = "delivered85-group-upgrade-character"
    private const val TITLE = "合成升级会话"
    private val messages = listOf(
        ChatMessage("upgrade-user", SESSION, MessageRole.USER, "合成正文第一行\n第二行", createdAt = 101, updatedAt = 101),
        ChatMessage("upgrade-assistant", SESSION, MessageRole.ASSISTANT, "复核原顺序和标点。", createdAt = 102, updatedAt = 102),
    )
    private val normalizedJson = Json { encodeDefaults = true }
    private val sha256 = Regex("[a-f0-9]{64}")

    @Suppress("DEPRECATION")
    private fun requireFixture(app: ChatBarApp, version: Int) {
        check(InstrumentationRegistry.getArguments().getString("lanDelivered85UpgradeFixture") == "true")
        check(app.lanSyncStartupReady)
        check(app.packageName == PACKAGE)
        val installed = app.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
        check(installed.versionCode == version)
        check(lanSyncHash(requireNotNull(installed.signatures).single().toByteArray()) == SIGNER)
    }

    suspend fun seedOrdinaryChat(app: ChatBarApp): JsonObject {
        requireFixture(app, 85)
        val root = app.filesDir.canonicalFile.toPath()
        check(ordinaryPaths().none { Files.exists(root.resolve(it), NOFOLLOW_LINKS) })
        check(app.chatRepository.getSession(SESSION) == null)
        app.chatRepository.createSession(ChatSession(SESSION, CARD, TITLE, createdAt = 100, updatedAt = 100))
        messages.forEach { app.chatRepository.addMessage(it) }
        return ordinaryChat(app)
    }

    suspend fun verifyOrdinaryChat(app: ChatBarApp, actualCandidateCode: Int, oldEvidence: JsonObject) {
        check(actualCandidateCode > 85)
        requireFixture(app, actualCandidateCode)
        check(ordinaryChat(app) == oldEvidence) { "Existing app conversation or its stored bytes changed" }
    }

    private fun ordinaryPaths(): List<String> =
        listOf("entities/chat_sessions/$SESSION.json") +
            messages.map { "entities/chat_messages/" + SESSION + "_" + it.id + ".json" }

    private suspend fun ordinaryChat(app: ChatBarApp): JsonObject {
        val repository = app.chatRepository
        val session = requireNotNull(repository.getSession(SESSION))
        check(session.characterCardId == CARD && session.title == TITLE)
        val received = repository.getMessagesReadOnly(SESSION)
        check(received.map { Triple(it.id, it.role, it.content) } == messages.map { Triple(it.id, it.role, it.content) })
        val root = app.filesDir.canonicalFile.toPath()
        val appInfo = app.packageManager.getPackageInfo(PACKAGE, 0)
        return buildJsonObject {
            put("uid", Process.myUid())
            put("firstInstallTime", appInfo.firstInstallTime)
            put("dataDirHash", hash(app.applicationInfo.dataDir))
            put("sessionId", session.id)
            put("titleHash", hash(session.title))
            put("messages", buildJsonArray {
                received.forEach { message ->
                    add(buildJsonObject {
                        put("id", message.id)
                        put("role", message.role.name)
                        put("bodyHash", hash(message.content))
                    })
                }
            })
            put("fileHashes", buildJsonObject { ordinaryPaths().forEach { put(it, fileHash(root.resolve(it))) } })
        }
    }

    /**
     * Use normalized bodies from the original owned captures and sealed units.
     * Encoding order matters for the content-addressed filename.
     */
    fun bodyFiles(records: List<LanSyncRecord>): Map<String, Long> = records.associate { record ->
        val bytes = normalizedJson.encodeToString(JsonObject.serializer(), record.body).toByteArray(Charsets.UTF_8)
        lanSyncHash(bytes) to bytes.size.toLong()
    }

    fun verifyParallelInventory(
        root: Path,
        old: Map<String, String>,
        originalProofSha256: String,
        resources: Map<String, Long>,
        bodies: Map<String, Long>,
    ) = verifyInventory(
        root,
        old + ("proof.json" to originalProofSha256),
        mapOf("capture-scratch/resources" to resources, "capture-scratch/decoded-records" to bodies),
        setOf("capture-scratch/decoded-records"),
    )

    fun verifyGroupInventory(
        root: Path,
        old: Map<String, String>,
        workspaceBodies: Map<String, Long>,
        sealedCheckpointBodies: Map<String, Long>,
        committedUnitBodies: Map<String, Long>,
    ) = verifyInventory(
        root,
        old,
        mapOf(
            "workspace/decoded-records" to workspaceBodies,
            "workspace/recovery/decoded-records" to sealedCheckpointBodies,
            "group-profile/node/preparations/decoded-records" to committedUnitBodies,
            "group-profile/node/source/preparations/decoded-records" to committedUnitBodies,
        ),
        setOf("workspace/decoded-records", "workspace/recovery/decoded-records",
            "group-profile/node/preparations/decoded-records",
            "group-profile/node/source/preparations/decoded-records"),
    )

    private fun verifyInventory(
        root: Path,
        old: Map<String, String>,
        newDirectories: Map<String, Map<String, Long>>,
        lockDirectories: Set<String>,
    ) {
        check(old.isNotEmpty() && old.values.all(sha256::matches))
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
        val observed = linkedMapOf<String, Path>()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                check(!Files.isSymbolicLink(path)) { "Unexpected symbolic link in isolated retained root" }
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    observed[root.relativize(path).toString().replace('\\', '/')] = path
                } else {
                    check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Unexpected filesystem entry in isolated retained root" }
                }
            }
        }
        old.forEach { (relative, hash) ->
            val file = requireNotNull(observed[relative]) { "Original file missing: $relative" }
            check(fileHash(file) == hash) { "Original bytes changed: $relative" }
        }
        (observed.keys - old.keys).forEach { relative ->
            val directory = relative.substringBeforeLast('/', "")
            val name = relative.substringAfterLast('/')
            val file = observed.getValue(relative)
            val admitted = requireNotNull(newDirectories[directory]) { "Unexpected new file: $relative" }
            if (name == "write.lock" && directory in lockDirectories) {
                check(admitted.isNotEmpty() && Files.size(file) == 0L) { "Unexpected cache lock: $relative" }
            } else {
                check(sha256.matches(name)) { "Unexpected derived filename: $relative" }
                val expectedLength = requireNotNull(admitted[name]) { "Unknown derived body or resource: $relative" }
                check(Files.size(file) == expectedLength && fileHash(file) == name) { "Derived bytes changed: $relative" }
            }
        }
    }

    private fun hash(text: String) = lanSyncHash(text.toByteArray(Charsets.UTF_8))

    private fun fileHash(path: Path): String {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS))
        val size = Files.size(path)
        return if (size == 0L) lanSyncHash(byteArrayOf()) else LanSyncWorkspace.hashFile(path, size)
    }
}
