// QR_PANEL_FILE_OPTIN_BEGIN
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
// QR_PANEL_FILE_OPTIN_END
package com.example.chatbar.desktop.lanSync

// QR_PANEL_IMPORTS_BEGIN
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.jetbrains.skia.EncodedImageFormat
// QR_PANEL_IMPORTS_END
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
    private val candidate = "f5fc060546eb9479b2566375761903d383c538b1"
    private val wire = Json { encodeDefaults = true }
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val session = "synthetic-v6-prepared"
    private val unit = "conversation/$session"
    private val mib = 1024L * 1024
    private var phase = "GUARD"
    private var primary: String? = null
    private var lastClose: SafeCloseResult? = null

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
                        // LAN7_EXPECTED_CLOSE_BEGIN
                        closeObserved(app, allowExpectedNotice = false) { canRelease = true }
                        // LAN7_EXPECTED_CLOSE_END
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
                    // LAN7_COLD_BASELINE_BEGIN
                    if (!seeding) {
                        phase = "CAPTURE_AUTHENTICATED_COLD_BASELINE"
                        val code = readProtected(protector, codePath) { it.toString(Charsets.UTF_8) }
                        val cold = buildJsonObject {
                            put("source", candidate); put("profilePin", checkNotNull(profileIdentity))
                            put("descriptors", deliveryDescriptors(profileRoot, code))
                            put("replicaFiles", treeInventory(profileRoot.resolve("local-replica")))
                        }
                        writeProtected(protector, privateRoot.resolve("cold-oracle.private.dpapi"), cold.toString().toByteArray(Charsets.UTF_8))
                    }
                    // LAN7_COLD_BASELINE_END
                    Files.writeString(report, buildJsonObject {
                        put("kind", "CCB_V6_PREPARED_DPAPI_COMPONENT_V1"); put("sourceSha", source); put("action", action)
                        put("passed", true); put("realDpapi", true); put("realAppContainer", true)
                        closeFields()
                        put("baseMetadataWorkingBudgetObserved", seeding); put("samePreparedSetupResumed", !seeding)
                        put("packagedMainExercised", false); put("networkDeliveryExercised", false)
                        put("heapBytes", heap); put("recordWindow", window); put("rawFixtureBytes", rawSize)
                        put("aggregateQuotedCostLowerBound", 96L * 16_384 * 6 * 8)
                        put("rawFixtureSha256", sha(checkNotNull(raw).toString().toByteArray(Charsets.UTF_8)))
                        put("retainedProfileSha256", checkNotNull(profileIdentity)); receiptHash?.let { put("localReceiptSha256", it) }
                    }.toString(), CREATE_NEW, WRITE)
                } finally { if (canRelease) acquired.ownership.close() }
            }
        } catch (failure: Throwable) {
            // Neither the arbitrary exception nor any cause contains safe caller-facing material.
            throw AssertionError("CCB_V6_PREPARED_COMPONENT:$phase:${primary ?: phase}:${safeThrowableType(failure)}").also { it.stackTrace = emptyArray() }
        }
    }


    @Test fun coldReadyUsesOriginalDpapiCodeAndIssuesInvitation() {
        candidateCase("COLD_INVITE") { root, privateRoot, data, sync, protector ->
            val oracle = readProtected(protector, privateRoot.resolve("prepared-oracle.private.dpapi")) {
                wire.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject
            }
            val raw = rawInventory(data, LanSyncMetadataBudget().maximumDecodedCost).first
            check(raw == oracle.getValue("raw").jsonObject)
            val cold = readProtected(protector, privateRoot.resolve("cold-oracle.private.dpapi")) {
                wire.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject
            }
            check(cold.getValue("source").jsonPrimitive.content == candidate)
            check(cold.getValue("profilePin") == oracle.getValue("profilePin"))
            val pinnedReplicaFiles = cold.getValue("replicaFiles").jsonObject
            val replicaRoot = sync.resolve("group-profile/local-replica")
            val replicaBefore = treeInventory(replicaRoot)
            check(pinnedReplicaFiles.isNotEmpty() && pinnedReplicaFiles.all { replicaBefore[it.key] == it.value })
            val before = protectedInventory(sync)
            val immutable = oracle.getValue("protected").jsonObject.filterKeys { key ->
                key == "identity.protected" || key.endsWith("/origin.protected") ||
                    key.startsWith("group-profile/recipient/") || key == "group-profile/custody/group.protected" ||
                    key.endsWith("/management-vault.bin") || key.startsWith("group-profile/root-delivery/")
            }
            check(immutable.isNotEmpty() && immutable.all { before[it.key] == it.value })
            val code = readProtected(protector, privateRoot.resolve("recovery.private.dpapi")) { it.toString(Charsets.UTF_8) }
            // QR_PANEL_PROOF_VAR_BEGIN
            var qrProof: QrPanelProof? = null
            // QR_PANEL_PROOF_VAR_END
            withCandidateApp(data) { app, coordinator ->
                phase = "COLD_OPEN_READY"
                coordinator.open()
                await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
                check(coordinator.state.value.paused && coordinator.state.value.recoveryCode == null)
                val owner = app.lanSyncGroupEnrollment
                check(profilePin(checkNotNull(owner.profile())) == oracle.getValue("profilePin").jsonPrimitive.content)
                verifyDeliveryDescriptors(sync.resolve("group-profile"), cold.getValue("descriptors").jsonArray)
                phase = "RESUME_READY_FOR_INVITATION"
                coordinator.resumeIfConfigured()
                await { !coordinator.state.value.busy && !coordinator.state.value.paused && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
                // QR_PANEL_COLD_ACTIONS_BEGIN
                qrProof = verifyOriginalCodeAndRenderedInvitation(coordinator, code)
                coordinator.pauseAndDrain()
                check(!coordinator.state.value.managementUnlocked && coordinator.state.value.invitation == null && coordinator.state.value.recoveryCode == null)
                phase = "ISSUE_REAL_COORDINATOR_INVITATION"
                // QR_PANEL_COLD_ACTIONS_END
                check(rawInventory(data, LanSyncMetadataBudget().maximumDecodedCost).first == raw)
                check(immutable.all { protectedInventory(sync)[it.key] == it.value })
            }
            check(rawInventory(data, LanSyncMetadataBudget().maximumDecodedCost).first == raw)
            check(immutable.all { protectedInventory(sync)[it.key] == it.value })
            verifyDeliveryDescriptors(sync.resolve("group-profile"), cold.getValue("descriptors").jsonArray)
            val replicaAfter = treeInventory(replicaRoot)
            check(pinnedReplicaFiles.all { replicaAfter[it.key] == it.value })
            reportCandidate(privateRoot, "COLD_INVITE", buildJsonObject {
                put("coldReady", true); put("sameGroupAndDailyIdentity", true); put("originalDpapiRecoveryCodeUsed", true)
                put("coordinatorInvitationIssued", true); put("managementLockedAfterward", true)
                put("immutableProtectedOriginsRetained", true); put("originalSyntheticEntitiesUnchanged", true)
                put("originalAuthenticatedDeliveryObjectsRetained", true)
                put("profileSha256", oracle.getValue("profilePin").jsonPrimitive.content)
                // QR_PANEL_REPORT_BEGIN
                val pixels = checkNotNull(qrProof)
                put("originalRecoveryCodeEnteredInPanel", true); put("managementUnlockedThroughPanel", true)
                put("invitationCreatedThroughPanel", true); put("renderedQrDecodedMatchesLiveOffer", true)
                put("lockedPanelSecretsAbsent", true); put("sceneDisposalLocksManagement", true)
                put("renderedQrRasterSha256", pixels.rasterSha256); put("invitationPayloadSha256", pixels.payloadSha256)
                // QR_PANEL_REPORT_END
            })
        }
    }

    @Test fun archiveUnusedSetupThenVerifyColdArchive() {
        val action = env("CCB_V6_PREPARED_ACTION")
        check(action == "ARCHIVE" || action == "ARCHIVE_COLD")
        candidateCase(action) { root, privateRoot, data, sync, protector ->
            val oraclePath = privateRoot.resolve("archive-oracle.private.dpapi")
            val profile = sync.resolve("group-profile")
            var retained: JsonObject? = null
            withCandidateApp(data) { app, coordinator ->
                if (action == "ARCHIVE") {
                    check(!Files.exists(oraclePath, NOFOLLOW_LINKS) && !app.hasLanSyncGroupEvidence())
                    val owner = app.lanSyncGroupEnrollment
                    val entities = treeInventory(data.resolve("entities"))
                    phase = "CREATE_UNUSED_SYNTHETIC_SETUP"
                    coordinator.createGroup()
                    await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
                    check(owner.canAbandonUnactivated() && owner.runtimeAccess() == null)
                    val original = draftInventory(profile)
                    check(original.isNotEmpty())
                    coordinator.lockManagement()
                    check(coordinator.state.value.recoveryCode == null)
                    phase = "EXPLICIT_ARCHIVE_UNUSED_SETUP"
                    coordinator.abandonUnusedSetup()
                    await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.EMPTY }
                    check(coordinator.state.value.error == null && owner.profile() == null && !owner.canAbandonUnactivated())
                    val archives = Files.newDirectoryStream(profile.resolve("abandoned-drafts")).use { it.toList() }
                    check(archives.size == 1)
                    val archive = archives.single()
                    check(Files.isDirectory(archive, NOFOLLOW_LINKS) && !Files.isSymbolicLink(archive))
                    check(Files.isRegularFile(archive.resolve("completed.protected"), NOFOLLOW_LINKS))
                    val archivedBytes = treeInventory(archive)
                    check(original.all { archivedBytes[it.key] == it.value })
                    check(draftNames.all { !Files.exists(profile.resolve(it), NOFOLLOW_LINKS) })
                    check(treeInventory(data.resolve("entities")) == entities)
                    retained = buildJsonObject {
                        put("source", candidate); put("archive", archive.fileName.toString())
                        put("draft", original); put("entities", entities)
                        put("archiveInventory", treeInventory(archive))
                    }
                } else {
                    val owner = app.lanSyncGroupEnrollment
                    phase = "READ_ARCHIVE_DPAPI_ORACLE"
                    val original = readProtected(protector, oraclePath) { wire.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
                    check(original.getValue("source").jsonPrimitive.content == candidate)
                    val leaf = original.getValue("archive").jsonPrimitive.content
                    check(leaf.matches(Regex("[a-zA-Z0-9-]{1,128}")))
                    val archive = profile.resolve("abandoned-drafts").resolve(leaf)
                    check(treeInventory(archive) == original.getValue("archiveInventory").jsonObject)
                    phase = "COLD_OPEN_COMPLETED_ARCHIVE"
                    coordinator.open()
                    await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.EMPTY }
                    check(coordinator.state.value.error == null && coordinator.state.value.recoveryCode == null)
                    check(owner.profile() == null && !owner.canAbandonUnactivated())
                    check(draftNames.all { !Files.exists(profile.resolve(it), NOFOLLOW_LINKS) })
                    check(treeInventory(data.resolve("entities")) == original.getValue("entities").jsonObject)
                    retained = original
                }
            }
            val saved = checkNotNull(retained)
            if (action == "ARCHIVE") writeProtected(protector, oraclePath, saved.toString().toByteArray(Charsets.UTF_8))
            check(treeInventory(data.resolve("entities")) == saved.getValue("entities").jsonObject)
            check(treeInventory(profile.resolve("abandoned-drafts").resolve(saved.getValue("archive").jsonPrimitive.content)) == saved.getValue("archiveInventory").jsonObject)
            check(draftNames.all { !Files.exists(profile.resolve(it), NOFOLLOW_LINKS) })
            reportCandidate(privateRoot, action, buildJsonObject {
                put("unusedSetupOnly", true); put("explicitArchiveAction", action == "ARCHIVE")
                put("completedArchiveRetained", true); put("liveEntitiesUnchanged", true)
                put("coldEmptyStateVerified", action == "ARCHIVE_COLD")
                put("archiveSha256", sha(saved.getValue("archiveInventory").toString().toByteArray(Charsets.UTF_8)))
            })
        }
    }

    private fun deliveryDescriptors(profile: Path, code: String): JsonArray {
        val replica = FileCiphertextStore(profile.resolve("local-replica"))
        val vault = checkNotNull(replica.metadata(SyncManagementSession.locate(code).key))
        val commits = replica.listCommits(null, 100)
        check(commits.entries.isNotEmpty() && commits.nextCursor == null)
        val records = listOf(vault) + commits.entries
        return JsonArray(records.map { item ->
            check(replica.readTo(item.key, java.io.OutputStream.nullOutputStream(), item.descriptor) == item)
            buildJsonObject {
                put("kind", item.key.kind.name); put("id", item.key.id.value)
                put("length", item.descriptor.length); put("sha256", item.descriptor.sha256.hex)
            }
        })
    }
    private fun verifyDeliveryDescriptors(profile: Path, records: JsonArray) {
        check(records.isNotEmpty())
        val replica = FileCiphertextStore(profile.resolve("local-replica"))
        for (element in records) {
            val item = element.jsonObject
            check(item.keys == setOf("kind", "id", "length", "sha256"))
            val key = CiphertextObjectKey(CiphertextObjectKind.valueOf(item.getValue("kind").jsonPrimitive.content), SyncStoreId.parse(item.getValue("id").jsonPrimitive.content))
            val descriptor = CiphertextDescriptor(item.getValue("length").jsonPrimitive.long, CiphertextSha256.parse(item.getValue("sha256").jsonPrimitive.content))
            check(replica.readTo(key, java.io.OutputStream.nullOutputStream(), descriptor) == CiphertextObjectMetadata(key, descriptor))
        }
    }
    private val draftNames = setOf("creation", "recipient", "management-vault.bin", "profile.protected")
    private fun draftInventory(profile: Path): JsonObject = buildJsonObject {
        draftNames.sorted().forEach { name ->
            val path = profile.resolve(name)
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) treeInventory(path).forEach { (leaf, hash) -> put("$name/$leaf", hash) }
            else if (Files.exists(path, NOFOLLOW_LINKS)) put(name, sha(path))
        }
    }
    private fun treeInventory(root: Path): JsonObject {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return buildJsonObject { }
        check(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
        val result = sortedMapOf<String, String>()
        Files.walk(root).use { paths -> paths.forEach { path ->
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(!attrs.isSymbolicLink && !attrs.isOther)
            if (attrs.isRegularFile) result[root.relativize(path).toString().replace('\\', '/')] = sha(path)
        } }
        return buildJsonObject { result.forEach { (path, hash) -> put(path, hash) } }
    }
    private fun reportCandidate(privateRoot: Path, action: String, details: JsonObject) {
        val report = Path.of(env("CCB_V6_PREPARED_REPORT")).toAbsolutePath().normalize()
        check(report.parent == privateRoot && !Files.exists(report, NOFOLLOW_LINKS))
        Files.writeString(report, buildJsonObject {
            put("kind", "CCB_LAN6_LAN7_ADDITIONAL_DPAPI_COMPONENT_V1"); put("sourceSha", candidate); put("action", action)
            put("passed", true); put("realDpapi", true); put("realAppContainer", true)
            closeFields()
            put("packagedMainExercised", false); put("visibleUiExercised", false); put("networkDeliveryExercised", false)
            put("details", details)
        }.toString(), CREATE_NEW, WRITE)
    }
    private fun candidateCase(action: String, operation: suspend (Path, Path, Path, Path, WindowsDpapiSecretProtector) -> Unit) {
        try {
            check(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_OS") == "Windows")
            check(System.getProperty("os.name").startsWith("Windows"))
            check(env("CCB_V6_PREPARED_COMPONENT") == "true" && env("CCB_V6_PREPARED_SOURCE") == candidate)
            check(env("CCB_V6_PREPARED_ACTION") == action)
            val runner = Path.of(env("RUNNER_TEMP")).toRealPath()
            val root = owned(env("CCB_V6_PREPARED_ROOT"), runner)
            val privateRoot = owned(env("CCB_V6_PREPARED_PRIVATE"), runner)
            check(!root.startsWith(privateRoot) && !privateRoot.startsWith(root))
            val local = owned(env("LOCALAPPDATA"), root.resolve("UserData"))
            check(local == root.resolve("UserData/Local"))
            check(owned(env("APPDATA"), root.resolve("UserData")) == root.resolve("UserData/Roaming"))
            for (name in listOf("TEMP", "TMP")) check(owned(env(name), root.resolve("UserData")) == root.resolve("UserData/Temp"))
            val data = owned(local.resolve("ChatChatBarDesktop").toString(), local)
            val heap = Runtime.getRuntime().maxMemory()
            check(heap in (240 * mib)..(256 * mib) && LanSyncMetadataBudget().maximumDecodedCost == heap / 4)
            val sync = DesktopLanSyncStartup.localRoot(data).toAbsolutePath().normalize()
            check(sync.startsWith(local) && !sync.startsWith(data))
            runBlocking(edt) { operation(root, privateRoot, data, sync, WindowsDpapiSecretProtector()) }
        } catch (failure: Throwable) {
            throw AssertionError("CCB_V6_PREPARED_COMPONENT:$phase:${primary ?: phase}:${safeThrowableType(failure)}").also { it.stackTrace = emptyArray() }
        }
    }
    private suspend fun withCandidateApp(data: Path, operation: suspend (DesktopAppContainer, LanSyncGroupCoordinator) -> Unit) {
        phase = "OWNERSHIP"
        val resolved = DesktopDataDirectory.resolveRoot(explicitOverride = data) as DesktopDataRootResolution.Resolved
        val acquired = DesktopDataRootOwnership.acquire(resolved) as DesktopDataRootOwnershipResult.Acquired
        var canRelease = true
        try {
            canRelease = false
            val app = DesktopAppContainer(resolved)
            try {
                val coordinator = app.lanSyncGroupCoordinator
                coordinator.pauseAndDrain()
                phase = "INITIALIZE"
                app.initializePersistentState()
                operation(app, coordinator)
            } catch (failure: Throwable) {
                primary = phase
                throw failure
            } finally {
                val previous = phase
                phase = "CLOSE_REAL_APP"
                closeObserved(app, allowExpectedNotice = env("CCB_V6_PREPARED_ACTION") == "COLD_INVITE") { canRelease = true }
                phase = previous
            }
        } finally { if (canRelease) acquired.ownership.close() }
    }

    private data class SafeCloseResult(val notices: Int, val known: Boolean, val returned: Boolean)
    private fun JsonObjectBuilder.closeFields() {
        val result = checkNotNull(lastClose)
        put("closeReturned", result.returned); put("closeNoticeKnown", result.known); put("closeNoticeCount", result.notices)
        put("closeOutcome", if (result.notices == 0) "RETURNED_WITHOUT_NOTICE" else "RETURNED_WITH_EXPECTED_PENDING_SYNC_NOTICE")
    }
    private suspend fun closeObserved(app: DesktopAppContainer, allowExpectedNotice: Boolean, onReturned: () -> Unit) {
        phase = "CLOSE_REAL_APP_CALL"
        var notices = 0
        var knownNotice = true
        // RESUME_CLOSE_SAMPLING_BEGIN
        val sampler = if (env("CCB_V6_PREPARED_ACTION") == "RESUME") runCatching { CloseSampleObserver(app, candidate) }.getOrNull() else null
        try {
            withContext(NonCancellable) {
                withTimeout(60_000) {
                    closeDesktopWithLanSyncCompletionNotice(close = { app.close() }, showNotice = { message ->
                        notices++
                        // Main's presentation callback catches Throwable. Record first; validate outside it.
                        knownNotice = knownNotice && message == DesktopLanSyncIncompleteNotice.message
                    })
                }
            }
        } catch (failure: Throwable) {
            val type = safeThrowableType(failure)
            if (type == "DesktopLanSyncFinalFlushTimeoutException" || type == "TimeoutCancellationException") {
                // Observation failure must never replace the original close failure or release its ownership.
                runCatching { sampler?.writeTimeout(Path.of(env("CCB_V6_PREPARED_PRIVATE")).resolve("close-samples-resume.private.json"), type) }
            }
            throw failure
        } finally { runCatching { sampler?.close() } }
        // RESUME_CLOSE_SAMPLING_END
        onReturned() // Ownership may release only after the actual wrapper returned safely.
        phase = "VERIFY_CLOSE_NOTICE"
        check(knownNotice && notices in 0..(if (allowExpectedNotice) 1 else 0))
        lastClose = SafeCloseResult(notices, knownNotice, true)
    }
    // CLOSE_SAMPLE_OBSERVER_BEGIN: fixed-source read-only diagnostics; never a close retry.
    private class CloseSampleObserver(private val app: DesktopAppContainer, private val source: String) : AutoCloseable {
        private val started = System.nanoTime()
        private val lock = Any()
        @Volatile private var stopped = false
        private val samples = mutableListOf<JsonObject>()
        private var priorCpu = emptyMap<Long, Long>() // Internal only; IDs/names never enter JSON.
        private val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            java.util.concurrent.ThreadFactory { action -> Thread(action, "ccb-retained-close-observer").apply { isDaemon = true } })
        private val tasks = mutableListOf<java.util.concurrent.ScheduledFuture<*>>()
        private fun elapsed() = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L
        private data class SourceRule(val root: String, val file: String, val family: String, val methods: Set<String>)
        private val sourceRules = listOf(
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncWorkspace", "LanSyncWorkspace.kt", "SOURCE_CAPTURE", setOf("capture", "freeze", "hashFile", "copyHash", "mapResources")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncJsonFiles", "LanSyncJsonFiles.kt", "SOURCE_CAPTURE", setOf("readEntity", "readChecked", "writeChecked")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncScope", "LanSyncScope.kt", "SOURCE_CAPTURE", setOf("normalize", "validateRecord", "validateGraph")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncGroupDependencies", "LanSyncGroupDependencies.kt", "DEPENDENCY", setOf("inspect", "membership", "fetch")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncGroupInbox", "SyncGroupInbox.kt", "DEPENDENCY", setOf("reconcile", "discover", "spool")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncGroupNativeApplyOwner", "LanSyncGroupNativeApplyOwner.kt", "NATIVE_OWNER", setOf("state", "recordSource", "recover", "recoverOwned", "requireFreshSource", "load", "read", "write", "guarded")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncPinnedDeviceCertificate", "SyncDeviceSignatures.kt", "CRYPTO_FIXED", setOf("verifies")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncRecipientPublicKey", "SyncDeviceEncryptionKey.kt", "CRYPTO_FIXED", setOf("encrypt")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncDeviceEncryptionKey", "SyncDeviceEncryptionKey.kt", "CRYPTO_FIXED", setOf("decrypt", "open", "openKey", "protectedKeyset")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncGroupNativePlanner", "LanSyncGroupNativePlanner.kt", "DEPENDENCY", setOf("prepare", "restrict")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncGroupObjectCodec", "SyncGroupObjectCodec.kt", "DEPENDENCY", setOf("read", "readQueued", "decode", "freeze")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncGroupCustody", "SyncGroupCustody.kt", "CUSTODY_PROTECTION", setOf("ledger", "key", "openKey", "openEnvelope", "build", "read", "write")),
            SourceRule("com.example.chatbar.desktop.security.WindowsDpapiSecretProtector", "WindowsDpapiSecretProtector.kt", "CUSTODY_PROTECTION", setOf("protect", "unprotect", "invokeDpapi")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncDeviceLedger", "SyncDeviceLedger.kt", "LEDGER_VALIDATION", setOf("offer", "verifiedCopy", "admission", "validateChild", "hasKey")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncGroupSourceWriter", "LanSyncGroupSourceWriter.kt", "SOURCE_PUBLICATION", setOf("commit", "resume", "resumeOwned", "verifyCustody", "finish")),
            SourceRule("com.example.chatbar.domain.syncStore.SyncStreamingCipher", "SyncStreamingCipher.kt", "SOURCE_PUBLICATION", setOf("encrypt", "decrypt", "verify", "open", "copy")),
            SourceRule("com.example.chatbar.desktop.DesktopLanSync", "DesktopLanSync.kt", "FINAL_FLUSH", setOf("flushLocalAfterWriters", "closeAndDrain")),
            SourceRule("com.example.chatbar.desktop.DesktopLanSyncShutdownMaintenance", "DesktopLanSync.kt", "EXCLUSIVE_WORK", setOf("withExclusive")),
            SourceRule("com.example.chatbar.desktop.DesktopDataOperationCoordinator", "DesktopDataOperationCoordinator.kt", "EXCLUSIVE_WORK", setOf("withExclusiveMaintenance", "acquireExclusive", "closeAndDrain", "getState")),
            SourceRule("com.example.chatbar.desktop.DesktopAppContainer", "DesktopAppContainer.kt", "EXCLUSIVE_WORK", setOf("close", "exclusivePersistenceWork")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator", "LanSyncGroupCoordinator.kt", "FINAL_FLUSH", setOf("flushLocalAfterWriters", "cycle")),
            SourceRule("com.example.chatbar.domain.lanSync.LanSyncGroupNode", "LanSyncGroupNode.kt", "GROUP_NODE", setOf("inspectLocal", "inspectOwned", "discoverLocal", "exclusive", "serialized"))
        ).associateBy { it.root }
        init {
            try {
            executor.execute { runCatching {
                val bean = java.lang.management.ManagementFactory.getThreadMXBean()
                if (bean.isThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled)
                    priorCpu = bean.allThreadIds.sorted().take(256).associateWith { bean.getThreadCpuTime(it) }
            } }
            for (at in listOf(6_000L, 14_000L, 23_000L, 28_000L))
                tasks += executor.schedule(Runnable { observe(at) }, at, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (failure: Throwable) { executor.shutdownNow(); throw failure }
        }
        private fun family(frame: StackTraceElement): String? {
            val name = frame.className
            val root = name.substringBefore('$')
            val rule = sourceRules[root]
            if (rule != null && frame.fileName == rule.file) {
                val generated = frame.methodName in setOf("invoke", "invokeSuspend") && rule.methods.any { name.startsWith(root + '$' + it + '$') }
                val localCapture = root == "com.example.chatbar.domain.lanSync.LanSyncWorkspace" && frame.methodName.startsWith("capture" + '$')
                if (generated || localCapture || frame.methodName in rule.methods) return rule.family
            }
            return when {
                name.startsWith("sun.security.rsa.") -> "RSA_FRAME"
                name.startsWith("sun.security.ec.") || name.startsWith("java.security.Signature") || name.startsWith("com.google.crypto.tink.") -> "CRYPTO_FIXED"
                name.startsWith("kotlinx.coroutines.sync.Mutex") || name.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer") -> "MUTEX_OR_GATE"
                (name == "jdk.internal.misc.Unsafe" && frame.methodName == "park") || name == "java.util.concurrent.locks.LockSupport" -> "PARK_OR_WAIT"
                name.startsWith("sun.nio.ch.FileChannelImpl") && frame.methodName in setOf("lock", "tryLock") -> "FILE_LOCK_WAIT"
                name.startsWith("sun.nio.ch.FileDispatcherImpl") || name.startsWith("sun.nio.fs.WindowsNativeDispatcher") || name.startsWith("sun.nio.ch.FileChannelImpl") || name == "java.io.FileInputStream" || name == "java.io.FileOutputStream" -> "FILE_IO"
                else -> null
            }
        }
        private fun observe(at: Long) {
            if (stopped) return
            val seenAt = elapsed()
            val sample = runCatching {
                val bean = java.lang.management.ManagementFactory.getThreadMXBean()
                val allIds = bean.allThreadIds
                val ids = allIds.sorted().take(256).toLongArray()
                val cpuState = when { !bean.isThreadCpuTimeSupported -> "UNSUPPORTED"; !bean.isThreadCpuTimeEnabled -> "DISABLED"; else -> "AVAILABLE" }
                val currentCpu = if (cpuState == "AVAILABLE") ids.associateWith { bean.getThreadCpuTime(it) } else emptyMap()
                val information = bean.getThreadInfo(ids, 64)
                val matches = information.mapNotNull { info ->
                    info ?: return@mapNotNull null
                    val frames = info.stackTrace
                    // Require a fixed app frame; background JDK-only IO/crypto is not attributed to this close.
                    if (frames.none { it.className.startsWith("com.example.chatbar.") && family(it) != null }) return@mapNotNull null
                    val families = frames.mapNotNull { family(it) }.distinct().take(8)
                    val now = currentCpu[info.threadId]; val before = priorCpu[info.threadId]
                    val delta = if (now != null && before != null && before >= 0L && now >= before) now - before else null
                    buildJsonObject {
                        put("state", info.threadState.name); put("frameFamilies", buildJsonArray { families.forEach { add(JsonPrimitive(it)) } })
                        put("cpuDeltaNanos", delta?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
                priorCpu = currentCpu
                val heap = runCatching { java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage }.getOrNull()
                val collectors = runCatching { java.lang.management.ManagementFactory.getGarbageCollectorMXBeans() }.getOrNull()
                fun gc(totalTime: Boolean): Long? {
                    val values = collectors?.map { if (totalTime) it.collectionTime else it.collectionCount } ?: return null
                    if (values.isEmpty() || values.any { it < 0L }) return null
                    return values.fold(0L) { acc, n -> if (Long.MAX_VALUE - acc < n) return null else acc + n }
                }
                buildJsonObject {
                    put("targetMillis", at); put("elapsedMillis", seenAt); put("status", "OBSERVED"); put("cpuTimeState", cpuState)
                    put("threadScanTruncated", allIds.size > 256); put("observationsTruncated", matches.size > 12)
                    put("stackDepthLimitReached", information.any { it != null && it.stackTrace.size >= 64 })
                    val backup = app.automaticBackupRuntime.state.value
                    put("dataGate", app.dataOperationCoordinator.state.name); put("backupMode", backup.mode.name)
                    put("backupSchedulerRunning", backup.schedulerRunning)
                    put("heapUsedBytes", heap?.used?.takeIf { it >= 0L }?.let(::JsonPrimitive) ?: JsonNull)
                    put("heapMaxBytes", heap?.max?.takeIf { it >= 0L }?.let(::JsonPrimitive) ?: JsonNull)
                    put("gcCollections", gc(false)?.let(::JsonPrimitive) ?: JsonNull); put("gcMillis", gc(true)?.let(::JsonPrimitive) ?: JsonNull)
                    put("observations", buildJsonArray { matches.take(12).forEach { add(it) } })
                }
            }.getOrElse {
                buildJsonObject { put("targetMillis", at); put("elapsedMillis", seenAt); put("status", "UNAVAILABLE") }
            }
            synchronized(lock) { if (!stopped && samples.size < 4) samples += sample }
        }
        fun writeTimeout(path: Path, type: String) {
            val failedAt = elapsed()
            close()
            val completed = synchronized(lock) { samples.toList() }
            val value = buildJsonObject {
                put("kind", "CCB_RESUME_DIRECT_CLOSE_SAMPLING_V1"); put("sourceSha", source); put("action", "RESUME")
                put("scope", "THIS_TEST_JVM_MATCHED_FRAMES_ONLY"); put("failureType", type); put("failureElapsedMillis", failedAt)
                put("scheduledMillis", buildJsonArray { listOf(6_000L, 14_000L, 23_000L, 28_000L).forEach { add(JsonPrimitive(it)) } })
                put("samples", buildJsonArray { completed.forEach { add(it) } }); put("observerStopRequested", true)
                put("rawStackPersisted", false); put("threadNamesOrIdsExported", false); put("productMutationOrCloseRetry", false)
            }
            Files.writeString(path, value.toString(), CREATE_NEW, WRITE)
        }
        override fun close() {
            synchronized(lock) { stopped = true }
            tasks.forEach { it.cancel(true) }; executor.shutdownNow() // Only this daemon; never a product worker.
        }
    }
    // CLOSE_SAMPLE_OBSERVER_END
    private fun safeThrowableType(failure: Throwable): String {
        val name = failure.javaClass.simpleName
        return if (name in setOf("AssertionError", "IllegalStateException", "IllegalArgumentException", "IOException",
            "SecurityException", "CancellationException", "TimeoutCancellationException", "OutOfMemoryError",
            "DesktopLanSyncFinalFlushIncompleteException", "DesktopLanSyncFinalFlushTimeoutException")) name else "UNKNOWN"
    }
    // QR_PANEL_HELPER_BEGIN
    private data class QrPanelProof(val rasterSha256: String, val payloadSha256: String)
    private suspend fun verifyOriginalCodeAndRenderedInvitation(coordinator: LanSyncGroupCoordinator, originalCode: String): QrPanelProof {
        check(SwingUtilities.isEventDispatchThread())
        var renderedState: LanSyncGroupPanelState? = null // Observation only; never supplied back to the panel.
        val scene = ImageComposeScene(1000, 1600, coroutineContext = edt) {
            val current by coordinator.state.collectAsState()
            CompositionLocalProvider(LocalDesktopUiStrings provides DesktopUiStrings(DesktopUiLanguage.ZH_CN)) {
                SideEffect { renderedState = current }
                DesktopLanSyncGroupPanel(current, coordinator)
            }
        }
        var closed = false
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun qrNodes() = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("新设备扫描此邀请") == true }
        fun editor() = nodes().filter { it.config.contains(SemanticsActions.SetText) }.single()
        fun button(label: String): SemanticsNode {
            var node = nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true }.single()
            while (!node.config.contains(SemanticsActions.OnClick)) node = checkNotNull(node.parent)
            return node
        }
        suspend fun framesUntil(ready: () -> Boolean) = withTimeout(180_000L) {
            while (true) { scene.render(System.nanoTime()).close(); if (ready()) break; delay(16L) }
        }
        suspend fun onScreen(select: () -> SemanticsNode): SemanticsNode {
            repeat(16) {
                scene.render(System.nanoTime()).close()
                val node = select(); val bounds = node.boundsInRoot
                val position = node.positionInRoot; val size = node.size
                val rawRight = position.x + size.width; val rawBottom = position.y + size.height
                if (size.width > 0 && size.height > 0 && position.x >= 0f && position.y >= 0f && rawRight <= 1000f && rawBottom <= 1600f &&
                    kotlin.math.abs(bounds.left - position.x) < 0.5f && kotlin.math.abs(bounds.top - position.y) < 0.5f &&
                    kotlin.math.abs(bounds.width - size.width) < 0.5f && kotlin.math.abs(bounds.height - size.height) < 0.5f) return node
                var scroll: SemanticsNode? = node
                while (scroll != null && !scroll.config.contains(SemanticsActions.ScrollBy)) scroll = scroll.parent
                val dy = if (position.y < 0f) position.y - 24f else rawBottom - 1600f + 24f
                check(checkNotNull(scroll).config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy.coerceIn(-1200f, 1200f)))
                delay(16L)
            }
            error("QR_PANEL_TARGET_NOT_VISIBLE")
        }
        suspend fun click(label: String) {
            framesUntil {
                val labels = nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true }
                check(labels.size <= 1)
                labels.size == 1 && !button(label).config.contains(SemanticsProperties.Disabled)
            }
            val node = onScreen { button(label) }
            check(!node.config.contains(SemanticsProperties.Disabled))
            check(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        }
        suspend fun unlockThroughPanel(reopen: Boolean = false) {
            val prefix = if (reopen) "COLD_UI_REOPEN_" else "COLD_UI_"
            phase = prefix + "WAIT_EDITABLE"
            framesUntil { nodes().filter { it.config.contains(SemanticsActions.SetText) }.let { fields -> check(fields.size <= 1); fields.size == 1 && !fields.single().config.contains(SemanticsProperties.Disabled) } }
            phase = prefix + "SCROLL_EDITABLE"
            val field = onScreen { editor() }
            check(!field.config.contains(SemanticsProperties.Disabled))
            phase = prefix + "SET_ORIGINAL_CODE"
            check(checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(originalCode)))
            phase = prefix + "WAIT_UNLOCK_ENABLED"
            framesUntil { !button("解锁管理").config.contains(SemanticsProperties.Disabled) }
            phase = prefix + "CLICK_UNLOCK"
            click("解锁管理")
            phase = prefix + "WAIT_UNLOCK_RESULT"
            framesUntil { !coordinator.state.value.busy && coordinator.state.value.managementUnlocked }
            phase = prefix + "CHECK_UNLOCK_RESULT"
            check(coordinator.state.value.error == null)
        }
        fun noSecretText(secret: String) = nodes().none { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { secret in it.text } ||
                node.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(secret) == true
        }
        fun saveUiFailureObservation(failure: Throwable) {
            val state = coordinator.state.value
            val tree = if (closed) null else runCatching { nodes() }.getOrNull()
            val fields = tree?.filter { it.config.contains(SemanticsActions.SetText) }
            val labels = tree?.filter { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == "解锁管理" } == true }
            val enabledUnlock = runCatching { if (labels?.size == 1) !button("解锁管理").config.contains(SemanticsProperties.Disabled) else null }.getOrNull()
            val expectedInEditor = fields?.mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }?.let { values ->
                if (values.isEmpty()) null else values.any { it == originalCode }
            }
            val value = buildJsonObject {
                put("kind", "CCB_V7_WINDOWS_QR_UI_OBSERVATION_V1"); put("sourceSha", candidate); put("action", "COLD_INVITE")
                put("phase", phase); put("failureType", safeThrowableType(failure)); put("stateStage", state.stage.name)
                put("busy", state.busy); put("paused", state.paused); put("managementUnlocked", state.managementUnlocked)
                put("localManagementAvailable", state.localManagementAvailable); put("managementRecoveryAvailable", state.managementRecoveryAvailable)
                put("managementCanInvite", state.managementCanInvite); put("hasError", state.error != null)
                put("hasInvitation", state.invitation != null); put("hasRecoveryCode", state.recoveryCode != null); put("sceneClosed", closed)
                put("semanticsAvailable", tree != null)
                put("renderedStage", renderedState?.stage?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("renderedBusy", renderedState?.busy?.let(::JsonPrimitive) ?: JsonNull)
                put("renderedPaused", renderedState?.paused?.let(::JsonPrimitive) ?: JsonNull)
                put("renderedLocalManagementAvailable", renderedState?.localManagementAvailable?.let(::JsonPrimitive) ?: JsonNull)
                put("renderedManagementUnlocked", renderedState?.managementUnlocked?.let(::JsonPrimitive) ?: JsonNull)
                put("editorCount", fields?.size?.let(::JsonPrimitive) ?: JsonNull)
                put("enabledEditorCount", fields?.count { !it.config.contains(SemanticsProperties.Disabled) }?.let(::JsonPrimitive) ?: JsonNull)
                put("unlockLabelCount", labels?.size?.let(::JsonPrimitive) ?: JsonNull)
                put("unlockEnabled", enabledUnlock?.let(::JsonPrimitive) ?: JsonNull)
                put("editorEqualsOriginalCode", expectedInEditor?.let(::JsonPrimitive) ?: JsonNull)
                put("hasQrNode", tree?.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("新设备扫描此邀请") == true }?.let(::JsonPrimitive) ?: JsonNull)
            }
            Files.writeString(Path.of(env("CCB_V6_PREPARED_PRIVATE")).resolve("qr-ui-observation.private.json"), value.toString(), CREATE_NEW, WRITE)
        }
        try {
            phase = "COLD_UI_WAIT_LOCKED_PANEL"
            framesUntil { !coordinator.state.value.managementUnlocked && qrNodes().isEmpty() }
            unlockThroughPanel()
            phase = "COLD_PANEL_CREATE_INVITATION"
            click("邀请设备")
            framesUntil { !coordinator.state.value.busy && coordinator.state.value.invitation != null && qrNodes().size == 1 }
            check(coordinator.state.value.error == null)
            val liveOffer = checkNotNull(coordinator.state.value.invitation)
            val expected = liveOffer.code()
            phase = "COLD_PANEL_DECODE_RENDERED_QR"
            onScreen { qrNodes().single() }
            scene.render(System.nanoTime()).close()
            val bounds = qrNodes().single().boundsInRoot
            val left = kotlin.math.floor(bounds.left.toDouble()).toInt(); val top = kotlin.math.floor(bounds.top.toDouble()).toInt()
            val right = kotlin.math.ceil(bounds.right.toDouble()).toInt(); val bottom = kotlin.math.ceil(bounds.bottom.toDouble()).toInt()
            check(left >= 0 && top >= 0 && right <= 1000 && bottom <= 1600 && right > left && bottom > top)
            val png = scene.render(System.nanoTime()).use { image -> checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes } }
            var rasterHash: String? = null
            try {
                val captured = checkNotNull(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png)))
                try {
                    check(captured.width == 1000 && captured.height == 1600)
                    val width = right - left; val height = bottom - top
                    val pixels = captured.getRGB(left, top, width, height, null, 0, width)
                    try {
                        val source = RGBLuminanceSource(width, height, pixels)
                        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
                        // Do not use assertEquals here: mismatch diagnostics must never print either capability.
                        check(decoded.barcodeFormat == BarcodeFormat.QR_CODE && decoded.text == expected)
                        val raw = ByteBuffer.allocate(pixels.size * 4).apply { pixels.forEach { putInt(it) } }.array()
                        try { rasterHash = sha(raw) } finally { raw.fill(0) }
                    } finally { pixels.fill(0) }
                } finally { captured.flush() }
            } finally { png.fill(0) }
            check(coordinator.state.value.invitation == liveOffer)
            // QR_PRIVATE_PROCESS_HANDOFF_SLOT: suggestion only; no file/process/wait or Android action is enabled.
            phase = "COLD_PANEL_LOCK_PRIVACY"
            click("锁定管理")
            framesUntil { !coordinator.state.value.managementUnlocked && coordinator.state.value.invitation == null && coordinator.state.value.recoveryCode == null && qrNodes().isEmpty() }
            check(noSecretText(originalCode) && noSecretText(expected))
            check(editor().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty())
            // Reopen management, without issuing another invitation, so disposal is tested from an unlocked state.
            unlockThroughPanel(reopen = true)
            check(coordinator.state.value.managementUnlocked)
            phase = "COLD_PANEL_DISPOSE_PRIVACY"
            scene.close(); closed = true
            await { !coordinator.state.value.managementUnlocked && coordinator.state.value.invitation == null && coordinator.state.value.recoveryCode == null }
            check(coordinator.state.value.error == null)
            return QrPanelProof(checkNotNull(rasterHash), sha(expected.toByteArray(Charsets.UTF_8)))
        } catch (failure: Throwable) {
            runCatching { saveUiFailureObservation(failure) } // Optional observation never replaces the primary failure.
            throw failure
        } finally { if (!closed) scene.close() }
    }
    // QR_PANEL_HELPER_END
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
