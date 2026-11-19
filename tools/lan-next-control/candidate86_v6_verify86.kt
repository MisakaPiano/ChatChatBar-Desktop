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

    @Test fun verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade(): Unit = runBlocking {
        val root = root(context(86))
        assertTrue("The84 seed must survive installation; never recreate it here", Files.isRegularFile(root.resolve("proof.json")))
        val evidence = json.parseToJsonElement(String(Files.readAllBytes(root.resolve("proof.json")), Charsets.UTF_8)).jsonObject
        assertEquals(PACKAGE, evidence.getValue("package").jsonPrimitive.content)
        assertEquals(85, evidence.getValue("seedVersionCode").jsonPrimitive.int)
        assertEquals(evidence.getValue("uid").jsonPrimitive.int, android.os.Process.myUid())
        assertEquals(evidence.getValue("firstInstallTime").jsonPrimitive.long, context(86).packageManager.getPackageInfo(PACKAGE, 0).firstInstallTime)
        val expected = evidence.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val originalProofSha = lanSyncHash(Files.readAllBytes(root.resolve("proof.json")))
        val parallelResources = evidence.getValue("parallelResources").jsonObject.mapValues { it.value.jsonPrimitive.long }
        val parallelBodies = evidence.getValue("parallelBodies").jsonObject.mapValues { it.value.jsonPrimitive.long }

        // GROUP-UPGRADE CALL BEGIN
        val legacyBeforeGroupCheck = inventory(root).filterKeys { it != "proof.json" }
        verifyActualAppGroup(context(86), evidence.getValue("groupProof").jsonObject)
        assertEquals("Group read must not change legacy files", legacyBeforeGroupCheck, inventory(root).filterKeys { it != "proof.json" })
        // GROUP-UPGRADE CALL END
        Android85RetainedEvidence.verifyParallelInventory(root, expected, originalProofSha, parallelResources, parallelBodies)
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
        check(capturedResources == parallelResources)
        workspace(root).capture(identity.fingerprint).use { capture ->
            check(Android85RetainedEvidence.bodyFiles(capture.snapshot.records) == parallelBodies)
        }
        Android85RetainedEvidence.verifyParallelInventory(root, expected, originalProofSha, parallelResources, parallelBodies)
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
        assertEquals("5c906e6d4a0f31a114326e35a331c4af4dfa6379", evidence.getValue("sourceSha").jsonPrimitive.content)
        val expected = evidence.getValue("originalFileHashes").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertTrue(expected.isNotEmpty())
        val app = readyApplication(context)
        val owner = app.lanSyncOwner
        val derived = evidence.getValue("derivedExpected").jsonObject
        fun expectedBodies(name: String) = derived.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.long }
        val workspaceBodies = expectedBodies("workspaceBodies")
        val sealedBodies = expectedBodies("sealedCheckpointBodies")
        val committedBodies = expectedBodies("committedUnitBodies")
        Android85RetainedEvidence.verifyOrdinaryChat(app, 86, evidence.getValue("ordinaryChat").jsonObject)
        Android85RetainedEvidence.verifyGroupInventory(appGroupRoot(context), expected, workspaceBodies, sealedBodies, committedBodies)
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
            Android85RetainedEvidence.verifyGroupInventory(appGroupRoot(context), expected, workspaceBodies, sealedBodies, committedBodies)
            assertTrue("The original production alias must survive; no create-if-absent substitute", KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(GROUP_DEFAULT_ALIAS))
            assertEquals(LanSyncGroupEnrollmentStage.READY, requireNotNull(owner.groupEnrollment.profile()).stage)
            val access = requireNotNull(owner.groupEnrollment.runtimeAccess())
            val pins = evidence.getValue("publicPins").jsonObject
            val operation = SyncStoreId.parse(pins.getValue("sourceOperation").jsonPrimitive.content)
            assertEquals("Public group/key/author/native/cloud pins must remain exact", pins, publicGroupPins(context, access, operation))
            Android85RetainedEvidence.verifyOrdinaryChat(app, 86, evidence.getValue("ordinaryChat").jsonObject)
            Android85RetainedEvidence.verifyGroupInventory(appGroupRoot(context), expected, workspaceBodies, sealedBodies, committedBodies)
        } finally { page.close() }
    }
    // GROUP-UPGRADE VERIFY END

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
