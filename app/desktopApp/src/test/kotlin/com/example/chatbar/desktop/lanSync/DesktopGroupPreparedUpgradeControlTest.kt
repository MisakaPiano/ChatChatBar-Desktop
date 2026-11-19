package com.example.chatbar.desktop.lanSync

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.desktop.security.WindowsSecretRootResolver
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Proposal only. Run the exact method twice in separate Windows test JVMs with -Xmx256m.
 * Stage this unchanged fixture into both exact-source Desktop test trees. Keep the same
 * runner account, absolute profile, and private DPAPI custody between SEED and RESUME.
 * Artifact only the selected method XML and safe report, never either scratch root.
 * This exercises the component. A separate control owns packaged Main, launcher and Program swap.
 */
class DesktopGroupPreparedUpgradeControlTest {
    private val base = "5c906e6d4a0f31a114326e35a331c4af4dfa6379"
    private val candidate = "8587b8771a86d4a0e809efaad298f8c27160ddf0"
    private val wire = Json { encodeDefaults = true }
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val session = "synthetic-v6-prepared"
    private val unit = "conversation/$session"
    private val mib = 1024L * 1024
    private var phase = "GUARD"
    private var primary: String? = null

    @Test fun seedOrResumeTheSamePreparedDesktopGroupWithRealDpapi() {
        try {
            check(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_OS") == "Windows")
            check(System.getProperty("os.name").startsWith("Windows"))
            check(System.getenv("CCB_V6_PREPARED_COMPONENT") == "true")
            val action = env("CCB_V6_PREPARED_ACTION")
            val source = env("CCB_V6_PREPARED_SOURCE")
            val seeding = action == "SEED"
            check(if (seeding) source == base else action == "RESUME" && source == candidate)
            val runner = Path.of(env("RUNNER_TEMP")).toRealPath()
            val root = owned(env("CCB_V6_PREPARED_ROOT"), runner)
            val privateRoot = owned(env("CCB_V6_PREPARED_PRIVATE"), runner)
            check(!root.startsWith(privateRoot) && !privateRoot.startsWith(root))
            val userData = owned(root.resolve("UserData").toString(), root)
            val local = owned(env("LOCALAPPDATA"), userData)
            check(local == userData.resolve("Local"))
            check(owned(env("APPDATA"), userData) == userData.resolve("Roaming"))
            for (name in listOf("TEMP", "TMP")) check(owned(env(name), userData) == userData.resolve("Temp"))
            val data = owned(local.resolve("ChatChatBarDesktop").toString(), local)
            val report = Path.of(env("CCB_V6_PREPARED_REPORT")).toAbsolutePath().normalize()
            check(report.parent == privateRoot && !Files.exists(report, NOFOLLOW_LINKS))
            val codePath = privateRoot.resolve("recovery.private.dpapi")
            val oraclePath = privateRoot.resolve("prepared-oracle.private.dpapi")
            val protector = WindowsDpapiSecretProtector()
            val heap = Runtime.getRuntime().maxMemory()
            val window = LanSyncMetadataBudget().maximumDecodedCost
            check(heap in (240 * mib)..(256 * mib) && window == heap / 4)
            val sync = DesktopLanSyncStartup.localRoot(data).toAbsolutePath().normalize()
            val credentials = WindowsSecretRootResolver.resolve(data).toAbsolutePath().normalize()
            check(sync.startsWith(local) && credentials.startsWith(local))
            check(!sync.startsWith(data) && !credentials.startsWith(data))
            val profileRoot = sync.resolve("group-profile")
            val original = if (seeding) {
                check(!Files.exists(codePath, NOFOLLOW_LINKS) && !Files.exists(oraclePath, NOFOLLOW_LINKS))
                null
            } else {
                phase = "READ_BASE_ORACLE"
                readProtected(protector, oraclePath) { wire.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
                    .also {
                        check(it.getValue("source").jsonPrimitive.content == base && it.getValue("window").jsonPrimitive.long == window)
                        check(it.getValue("dataRootSha256").jsonPrimitive.content == sha(data.toString().toByteArray(Charsets.UTF_8)))
                    }
            }
            runBlocking(edt) {
                phase = "OWNERSHIP"
                val resolved = DesktopDataDirectory.resolveRoot(explicitOverride = data) as DesktopDataRootResolution.Resolved
                check(resolved.appDataRoot.toRealPath() == data)
                val acquired = DesktopDataRootOwnership.acquire(resolved) as DesktopDataRootOwnershipResult.Acquired
                var canRelease = true
                try {
                    if (original != null) {
                        phase = "VERIFY_RETAINED_BEFORE_CONSTRUCTION"
                        check(protectedInventory(sync) == original.getValue("protected").jsonObject)
                        check(rawInventory(data, window).first == original.getValue("raw").jsonObject)
                    }
                    canRelease = false
                    val app = DesktopAppContainer(resolved)
                    val coordinator = app.lanSyncGroupCoordinator
                    var originalInventory: JsonObject? = null
                    var preparedBinding: String? = null
                    var rootDeliveries: String? = null
                    var profileIdentity: String? = null
                    var raw: JsonObject? = null
                    var rawSize = 0L
                    var receiptHash: String? = null
                    try {
                        coordinator.pauseAndDrain()
                        phase = "INITIALIZE"
                        app.initializePersistentState()
                        if (seeding) {
                            check(!app.hasLanSyncGroupEvidence())
                            phase = "SEED_SYNTHETIC_ENTITIES"
                            seed(data)
                        }
                        val measured = rawInventory(data, window)
                        raw = measured.first
                        if (original != null) check(raw == original.getValue("raw").jsonObject)
                        rawSize = measured.second
                        check(rawSize >= 24 * mib)
                        // The actual old decoder charges 8 per quoted UTF-16 character.
                        // Known pattern has 6 code units and 16 UTF-8 bytes per repetition.
                        check(96L * 16_384 * 6 * 8 > window)
                        if (seeding) {
                            phase = "CREATE_ON_BASE"
                            coordinator.createGroup()
                            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
                            val draft = coordinator.state.value
                            val code = checkNotNull(draft.recoveryCode)
                            val binding = checkNotNull(draft.setupBinding)
                            preparedBinding = binding
                            check(app.lanSyncGroupEnrollment.canAbandonUnactivated())
                            phase = "SAVE_CODE_WITH_DPAPI_BEFORE_CONFIRMATION"
                            writeProtected(protector, codePath, code.toByteArray(Charsets.UTF_8))
                            readProtected(protector, codePath) { check(it.toString(Charsets.UTF_8) == code) }
                            coordinator.recoverySaved(binding)
                            phase = "ACTUAL_BASE_METADATA_REFUSAL"
                            await { !coordinator.state.value.busy && coordinator.state.value.error != null }
                            check(coordinator.state.value.error?.endsWith("SYNC_METADATA_WORKING_BUDGET") == true)
                            val owner = app.lanSyncGroupEnrollment
                            val profile = checkNotNull(owner.profile())
                            check(profile.stage == LanSyncGroupEnrollmentStage.PREPARED && owner.runtimeAccess() == null)
                            check(!owner.canAbandonUnactivated())
                            check(runCatching {
                                owner.abandonUnactivated(LanSyncGroupDraftAbandonAdmission { error("UNEXPECTED_ABANDON_ADMISSION") })
                            }.exceptionOrNull()?.message == "SYNC_GROUP_PROFILE_LOCAL_HISTORY_EXISTS")
                            check(!Files.exists(profileRoot.resolve("abandon.protected"), NOFOLLOW_LINKS))
                            profileIdentity = profilePin(profile)
                            rootDeliveries = deliveryPin(profileRoot, code)
                            val sealedFiles = protectedInventory(sync)
                            originalInventory = sealedFiles
                            check(sealedFiles.keys.containsAll(setOf("identity.protected", "group-profile/profile.protected",
                                "group-profile/node/author/origin.protected", "group-profile/node/native/origin.protected",
                                "group-profile/node/resolutions/state.protected", "group-profile/custody/group.protected")))
                            coordinator.lockManagement()
                            check(coordinator.state.value.stage == LanSyncGroupPanelStage.PREPARED_LOCKED)
                            check(coordinator.state.value.invitation == null && coordinator.state.value.recoveryCode == null)
                        } else {
                            val retained = checkNotNull(original)
                            phase = "OPEN_PREPARED_ON_CANDIDATE"
                            check(app.hasLanSyncGroupEvidence())
                            coordinator.open()
                            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.PREPARED_LOCKED }
                            check(coordinator.state.value.paused && coordinator.state.value.recoveryCode == null)
                            val owner = app.lanSyncGroupEnrollment
                            check(!owner.canAbandonUnactivated() && owner.runtimeAccess() == null)
                            profileIdentity = profilePin(checkNotNull(owner.profile()))
                            check(profileIdentity == retained.getValue("profilePin").jsonPrimitive.content)
                            phase = "UNLOCK_SAME_DPAPI_CODE"
                            val code = readProtected(protector, codePath) { it.toString(Charsets.UTF_8) }
                            rootDeliveries = deliveryPin(profileRoot, code)
                            check(rootDeliveries == retained.getValue("rootDelivery").jsonPrimitive.content)
                            coordinator.unlockManagement(code)
                            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
                            val binding = checkNotNull(coordinator.state.value.setupBinding)
                            check(binding == retained.getValue("setupBinding").jsonPrimitive.content)
                            check(coordinator.state.value.recoveryCode == code && owner.runtimeAccess() == null)
                            phase = "COMPLETE_SAME_SETUP"
                            coordinator.recoverySaved(binding)
                            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
                            check(coordinator.state.value.error == null && coordinator.state.value.paused)
                            check(coordinator.state.value.invitation == null && coordinator.state.value.recoveryCode == null)
                            val ready = checkNotNull(owner.profile())
                            check(ready.stage == LanSyncGroupEnrollmentStage.READY && profilePin(ready) == profileIdentity)
                            val oldProtected = retained.getValue("protected").jsonObject
                            val readyProtected = protectedInventory(sync)
                            check(oldProtected.filterKeys { it != "group-profile/profile.protected" }.all { readyProtected[it.key] == it.value })
                            check(deliveryPin(profileRoot, code) == rootDeliveries)
                            phase = "VERIFY_REAL_LOCAL_SOURCE_AND_PUBLISH_OFFLINE"
                            val access = checkNotNull(owner.runtimeAccess())
                            val view = access.node.inspectLocal()
                            val plan = checkNotNull(view.plan)
                            val rows = plan.before.units().getValue(unit)
                            check(rows.size == 97 && rows.count { it.type == "chat_messages" } == 96)
                            val expected = (1..96).associate { index -> messageId(index) to sha(text(index).toByteArray(Charsets.UTF_8)) }
                            val actual = rows.asSequence().filter { it.type == "chat_messages" }.associate {
                                it.id to sha(it.body.getValue("content").jsonPrimitive.content.toByteArray(Charsets.UTF_8))
                            }
                            check(expected == actual)
                            val expectedHash = plan.before.hashes().getValue(unit)
                            val selected = plan.units.values.filter { it.stage == LanSyncGroupNativeStage.LOCAL_PROPOSAL }.map { it.unit }.toSet()
                            check(unit in selected)
                            val review = view.review(selected)
                            check(review.requiredBindings.isEmpty())
                            val committed = access.node.commit(review)
                            check(committed.entry.version.authorSequence == 1L)
                            check(committed.entry.version.changes.getValue(unit).valueHash == expectedHash)
                            val delivery = access.node.upload(committed.entry.request.operation, access.replica, access.localTarget)
                            check(delivery.metadata == committed.publication.metadata.descriptor && delivery.target == access.localTarget)
                            val native = access.node.inspectLocal().nativeState
                            check(native.pendingTransaction == null && native.units.getValue(unit).frontiers == setOf(committed.entry.version.reference()))
                            check(native.units.getValue(unit).valueHash == expectedHash)
                            receiptHash = delivery.metadata.sha256.hex
                        }
                        check(rawInventory(data, window).first == raw)
                    } catch (failure: Throwable) {
                        primary = phase
                        throw failure
                    } finally {
                        val previous = phase
                        phase = "CLOSE_REAL_APP"
                        var notices = 0
                        withContext(NonCancellable) {
                            withTimeout(60_000) {
                                closeDesktopWithLanSyncCompletionNotice(close = { app.close() }, showNotice = { notices++ })
                            }
                        }
                        // Hold the root ownership until AppContainer teardown is confirmed.
                        canRelease = true
                        check(notices == 0)
                        phase = previous
                    }
                    phase = "POST_CLOSE_EVIDENCE"
                    check(rawInventory(data, window).first == raw)
                    if (seeding) {
                        val protected = checkNotNull(originalInventory)
                        check(protectedInventory(sync) == protected)
                        val handoff = buildJsonObject {
                            put("source", base); put("window", window); put("dataRootSha256", sha(data.toString().toByteArray(Charsets.UTF_8)))
                            put("profilePin", checkNotNull(profileIdentity)); put("setupBinding", checkNotNull(preparedBinding))
                            put("rootDelivery", checkNotNull(rootDeliveries)); put("raw", checkNotNull(raw)); put("protected", protected)
                        }
                        writeProtected(protector, oraclePath, handoff.toString().toByteArray(Charsets.UTF_8))
                    }
                    Files.writeString(report, buildJsonObject {
                        put("kind", "CCB_V6_PREPARED_DPAPI_COMPONENT_V1"); put("sourceSha", source); put("action", action)
                        put("passed", true); put("realDpapi", true); put("realAppContainer", true)
                        put("baseMetadataWorkingBudgetObserved", seeding); put("samePreparedSetupResumed", !seeding)
                        put("packagedMainExercised", false); put("networkDeliveryExercised", false)
                        put("heapBytes", heap); put("recordWindow", window); put("rawFixtureBytes", rawSize)
                        put("aggregateQuotedCostLowerBound", 96L * 16_384 * 6 * 8)
                        put("rawFixtureSha256", sha(checkNotNull(raw).toString().toByteArray(Charsets.UTF_8)))
                        put("retainedProfileSha256", checkNotNull(profileIdentity)); receiptHash?.let { put("localReceiptSha256", it) }
                    }.toString(), CREATE_NEW, WRITE)
                } finally { if (canRelease) acquired.ownership.close() }
            }
        } catch (_: Throwable) {
            // Neither the arbitrary exception nor any cause contains safe caller-facing material.
            throw AssertionError("CCB_V6_PREPARED_COMPONENT:$phase:${primary ?: phase}").also { it.stackTrace = emptyArray() }
        }
    }

    private fun messageId(index: Int) = "v6-message-" + index.toString().padStart(3, '0')
    private fun text(index: Int) = "合成第 $index 段：" + "合成内容😀".repeat(16_384)

    private fun seed(data: Path) {
        val sessions = Files.createDirectories(data.resolve("entities/chat_sessions"))
        val messages = Files.createDirectories(data.resolve("entities/chat_messages"))
        val cards = Files.createDirectories(data.resolve("entities/character_cards"))
        val card = CharacterCard("synthetic-card", "合成归属卡", createdAt = 1, updatedAt = 1)
        Files.writeString(cards.resolve("synthetic-card.json"), wire.encodeToString(CharacterCard.serializer(), card), CREATE_NEW, WRITE)
        val value = ChatSession(session, "synthetic-card", "合成设备组会话", createdAt = 1, updatedAt = 1)
        Files.writeString(sessions.resolve("$session.json"), wire.encodeToString(ChatSession.serializer(), value), CREATE_NEW, WRITE)
        for (index in 1..96) {
            val id = messageId(index)
            val message = ChatMessage(id, session, MessageRole.USER, text(index), createdAt = index.toLong(), updatedAt = index.toLong())
            Files.writeString(messages.resolve("${session}_$id.json"), wire.encodeToString(ChatMessage.serializer(), message), CREATE_NEW, WRITE)
        }
    }

    private fun rawInventory(data: Path, window: Long): Pair<JsonObject, Long> {
        val files = listOf("entities/character_cards/synthetic-card.json", "entities/chat_sessions/$session.json") +
            (1..96).map { "entities/chat_messages/${session}_${messageId(it)}.json" }
        var size = 0L
        val raw = buildJsonObject {
            for (relative in files) {
                val path = data.resolve(relative)
                val bytes = Files.size(path)
                // Every decoded UTF-16 unit consumes <=128 of old cost and has >=1 raw byte
                // in these generated valid UTF-8/JSON files. All wire fields are shallow.
                check(bytes in 1..(8 * mib) && bytes * 128 < window)
                size = Math.addExact(size, bytes)
                put(relative, sha(path))
            }
        }
        return raw to size
    }

    private fun profilePin(profile: LanSyncGroupProfile): String = sha(buildJsonObject {
        put("group", profile.trust.group.value); put("genesis", profile.trust.genesis.id.value)
        put("genesisHash", profile.trust.genesis.bodyHash); put("rootCertificate", profile.trust.ownerCertificate)
        put("dailyDevice", profile.device); put("deliveryKind", profile.localReplica.kind.name); put("deliveryRoute", profile.localReplica.id.value)
        put("coverage", JsonArray(profile.coveredTypes.sorted().map(::JsonPrimitive)))
    }.toString().toByteArray(Charsets.UTF_8))

    private fun deliveryPin(profile: Path, code: String): String {
        val root = profile.resolve("local-replica")
        check(Files.isRegularFile(root.resolve("store.id"), NOFOLLOW_LINKS))
        val replica = FileCiphertextStore(root)
        val vault = checkNotNull(replica.metadata(SyncManagementSession.locate(code).key))
        val commits = replica.listCommits(null, 100)
        check(commits.entries.isNotEmpty() && commits.nextCursor == null)
        val records = listOf(vault) + commits.entries.map { checkNotNull(replica.metadata(it.key)) }
        return sha(records.joinToString("\n") { it.key.kind.name + ":" + it.key.id.value + ":" +
            it.descriptor.length + ":" + it.descriptor.sha256.hex }.toByteArray(Charsets.UTF_8))
    }

    private fun protectedInventory(sync: Path): JsonObject {
        val pins = sortedMapOf<String, String>()
        Files.walk(sync).use { entries -> entries.forEach { path ->
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(!attrs.isSymbolicLink && !attrs.isOther)
            if (attrs.isRegularFile) {
                val relative = sync.relativize(path).toString().replace('\\', '/')
                if (relative.endsWith(".protected") || relative.endsWith("/management-vault.bin") ||
                    relative.startsWith("group-profile/root-delivery/") && !relative.endsWith(".lock")) pins[relative] = sha(path)
            }
        } }
        return buildJsonObject { pins.forEach { (name, hash) -> put(name, hash) } }
    }

    private fun writeProtected(protector: WindowsDpapiSecretProtector, path: Path, bytes: ByteArray) {
        try {
            val cipher = ByteBuffer.wrap(protector.protect(bytes))
            FileChannel.open(path, CREATE_NEW, WRITE).use { file ->
                while (cipher.hasRemaining()) file.write(cipher)
                file.force(true)
            }
        } finally { bytes.fill(0) }
    }
    private fun <T> readProtected(protector: WindowsDpapiSecretProtector, path: Path, read: (ByteArray) -> T): T {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) in 1L..262_144L)
        val bytes = protector.unprotect(Files.readAllBytes(path))
        try { return read(bytes) } finally { bytes.fill(0) }
    }
    private fun sha(path: Path): String {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun env(key: String) = checkNotNull(System.getenv(key)).also { check(it.isNotBlank()) }
    private fun owned(raw: String, root: Path): Path {
        val path = Path.of(raw).toAbsolutePath().normalize()
        check(path != root && path.startsWith(root) && path.toRealPath() == path)
        generateSequence(path) { it.parent }.forEach {
            val attrs = Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(attrs.isDirectory && !attrs.isSymbolicLink && !attrs.isOther)
        }
        return path
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(180_000) {
        while (!predicate()) delay(100)
    }
}
