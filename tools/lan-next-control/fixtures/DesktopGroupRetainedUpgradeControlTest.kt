package com.example.chatbar.desktop.lanSync

import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.desktop.security.WindowsSecretRootResolver
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Separate real AppContainer/DPAPI component process; never a packaged-Main launch. */
class DesktopGroupRetainedUpgradeControlTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val oldSource = "e606bf2a2723fbc896323a8071cfd52dde99a4c6"
    private val candidateSource = "a6bb882d7cef9aceba3fda3b0d33329f9d687d8f"
    private var phase = "GUARD"

    @Test
    fun seedOrVerifyRealGroupInRetainedIndependentUserData() {
        try {
            check(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_OS") == "Windows")
            check(System.getProperty("os.name").startsWith("Windows"))
            check(System.getenv("CCB_GROUP_RETAINED_CONTROL") == "true")
            val source = env("CCB_GROUP_RETAINED_SOURCE")
            val action = env("CCB_GROUP_RETAINED_ACTION")
            val stage = env("CCB_GROUP_RETAINED_STAGE")
            check(source in setOf(oldSource, candidateSource))
            check((action == "SEED" && source == oldSource && stage == "lan5-seed") ||
                (action == "VERIFY" && ((source == oldSource && stage in setOf("lan5-populated", "lan5-warm-control")) ||
                    (source == candidateSource && stage in setOf("lan6-upgraded", "lan6-cold-reopen")))))
            val runner = Path.of(env("RUNNER_TEMP")).toRealPath()
            val root = owned(env("CCB_GROUP_RETAINED_ROOT"), runner)
            val privateRoot = owned(env("CCB_GROUP_RETAINED_PRIVATE"), runner)
            check(!root.startsWith(privateRoot) && !privateRoot.startsWith(root))
            val userData = root.resolve("UserData").toRealPath()
            val local = userData.resolve("Local").toRealPath()
            check(Path.of(env("LOCALAPPDATA")).toRealPath() == local)
            check(Path.of(env("APPDATA")).toRealPath() == userData.resolve("Roaming").toRealPath())
            check(Path.of(env("TEMP")).toRealPath() == userData.resolve("Temp").toRealPath())
            check(Path.of(env("TMP")).toRealPath() == userData.resolve("Temp").toRealPath())
            val data = local.resolve("ChatChatBarDesktop").toRealPath()
            val report = Path.of(env("CCB_GROUP_RETAINED_REPORT")).toAbsolutePath().normalize()
            check(report.parent.toRealPath() == privateRoot && !Files.exists(report, NOFOLLOW_LINKS))
            val oraclePath = privateRoot.resolve("group-oracle.private.json")
            val protector = WindowsDpapiSecretProtector()
            runBlocking(edt) {
                val resolved = DesktopDataDirectory.resolveRoot(explicitOverride = data)
                    as DesktopDataRootResolution.Resolved
                check(resolved.appDataRoot.toRealPath() == data)
                val sync = DesktopLanSyncStartup.localRoot(data).toAbsolutePath().normalize()
                val credentials = WindowsSecretRootResolver.resolve(data).toAbsolutePath().normalize()
                check(sync.startsWith(local) && credentials.startsWith(local))
                check(!sync.startsWith(data) && !credentials.startsWith(data))
                val original = if (action == "VERIFY") readOracle(oraclePath) else null
                if (original != null) {
                    phase = "PROTECTED_PRECONSTRUCT"
                    check(protectedInventory(local) == original.getValue("protected").jsonObject)
                } else check(!Files.exists(oraclePath, NOFOLLOW_LINKS))
                phase = "OWNERSHIP"
                val acquired = DesktopDataRootOwnership.acquire(resolved) as DesktopDataRootOwnershipResult.Acquired
                var teardownConfirmed = false
                try {
                    // The default constructor installs the actual Windows secret store and bundled assets.
                    val app = DesktopAppContainer(resolved)
                    var closed = false
                    var notices = 0
                    try {
                        val coordinator = app.lanSyncGroupCoordinator
                        if (action == "VERIFY") coordinator.pauseAndDrain()
                        phase = "INITIALIZE"
                        app.initializePersistentState()
                        if (action == "SEED") {
                            check(!app.hasLanSyncGroupEvidence())
                            phase = "CREATE_REAL_GROUP"
                            coordinator.createGroup()
                            await { coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE &&
                                !coordinator.state.value.busy }
                            val draft = coordinator.state.value
                            check(draft.error == null && draft.managementUnlocked)
                            phase = "SAVE_RECOVERY_DPAPI_BEFORE_ACK"
                            val bytes = checkNotNull(draft.recoveryCode).toByteArray(Charsets.UTF_8)
                            try {
                                val cipher = protector.protect(bytes)
                                val custody = privateRoot.resolve("recovery-custody.private.protected")
                                Files.write(custody, cipher, CREATE_NEW, WRITE)
                                val recovered = protector.unprotect(Files.readAllBytes(custody))
                                try { check(recovered.contentEquals(bytes)) } finally { recovered.fill(0) }
                            } finally { bytes.fill(0) }
                            coordinator.recoverySaved(checkNotNull(draft.setupBinding))
                            phase = "WAIT_ORDINARY_INITIAL_PUBLICATION"
                            // Observe only. No Node.inspect/upload/retry or native apply advances publication here.
                            await {
                                val panel = coordinator.state.value
                                val view = coordinator.view.value
                                panel.stage == LanSyncGroupPanelStage.READY && !panel.busy && !panel.managementUnlocked &&
                                    view != null && view.nativeState.completedSequence == 1L &&
                                    view.nativeState.pendingTransaction == null && panel.local.lastSuccessAt != null
                            }
                            coordinator.pauseAndDrain()
                            phase = "ADD_DISABLED_REAL_PROTECTED_ROUTE"
                            val access = checkNotNull(app.lanSyncGroupEnrollment.runtimeAccess())
                            val entropy = ByteArray(32).also(SecureRandom()::nextBytes)
                            val password = Base64.getEncoder().encode(entropy)
                            entropy.fill(0)
                            try {
                                access.cloudRoutes().add(LanSyncGroupCloudDraft("synthetic upgrade disabled route",
                                    "https://example.invalid/ccb-retained-upgrade/", "synthetic-retained-user", password, enabled = false))
                                // Use the default AppContainer account adapter and its actual WindowsSecretStore.
                                app.desktopSecretStore.save(DesktopCredentialKey.FishAudioApiKey, password.toString(Charsets.UTF_8))
                                check(app.desktopSecretStore.load(DesktopCredentialKey.FishAudioApiKey) == password.toString(Charsets.UTF_8))
                                check(!containsPlaintext(userData, password))
                            } finally { password.fill(0) }
                        } else {
                            phase = "OPEN_EXISTING_WHILE_PAUSED"
                            check(app.hasLanSyncGroupEvidence())
                            coordinator.open()
                            await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy }
                        }
                        phase = "READ_REAL_DAILY_OWNER_NATIVE_AND_ROUTE"
                        check(coordinator.state.value.paused && !coordinator.state.value.managementUnlocked)
                        check(coordinator.state.value.recoveryCode == null && coordinator.state.value.error == null)
                        val access = checkNotNull(app.lanSyncGroupEnrollment.runtimeAccess())
                        val profile = checkNotNull(app.lanSyncGroupEnrollment.profile())
                        check(profile.stage == LanSyncGroupEnrollmentStage.READY && profile.trust == access.trust)
                        check(profile.device == access.device)
                        // Public certificate bytes are already validated by the actual enrollment/runtime owners.
                        // Compare their SHA256 fingerprints; do not reach sharedCore's internal decode API.
                        val rootFingerprint = sha(Base64.getDecoder().decode(access.trust.ownerCertificate))
                        val dailyFingerprint = sha(Base64.getDecoder().decode(access.certificate))
                        check(dailyFingerprint == access.device && rootFingerprint != access.device)
                        val ledger = access.ledger().current()
                        check(ledger.members[access.device]?.active == true && ledger.members[access.device]?.certificate == access.certificate)
                        val author = SyncAuthorJournal(sync.resolve("group-profile/node/author"), access.trust,
                            access.certificate, protector::protect, protector::unprotect).state()
                        check(author.committedSequence == 1L && author.pendingApproved == null)
                        val version = checkNotNull(author.last).version
                        check(version.author == access.device && version.group == access.trust.group)
                        val view = access.node.inspectLocal(recoverNative = false)
                        val native = view.nativeState
                        check(native.completedSequence == 1L && native.pendingTransaction == null && native.units.isNotEmpty())
                        check(native.units.values.any { version.reference() in it.frontiers })
                        val rows = access.cloudRoutes().rows()
                        check(rows.size == 1 && !rows.single().enabled)
                        check(rows.single().endpoint == "https://example.invalid/ccb-retained-upgrade/")
                        check(rows.single().username == "synthetic-retained-user")
                        val routePath = access.work.resolve("cloud-connections/cloud-routes.protected")
                        val routeCipher = Files.readAllBytes(routePath)
                        val routePlain = protector.unprotect(routeCipher)
                        val routePlainHash = try { sha(routePlain) } finally { routePlain.fill(0) }
                        val credential = checkNotNull(app.desktopSecretStore.load(DesktopCredentialKey.FishAudioApiKey))
                            .toByteArray(Charsets.UTF_8)
                        val credentialHash = try { check(credential.isNotEmpty()); sha(credential) } finally { credential.fill(0) }
                        val graph = native.units.toSortedMap().entries.joinToString("\n") { (unit, state) ->
                            unit + ":" + (state.valueHash ?: "null") + ":" +
                                state.frontiers.sortedWith(compareBy({ it.id.value }, { it.bodyHash }))
                                    .joinToString(",") { it.id.value + ":" + it.bodyHash }
                        }
                        val logical = buildJsonObject {
                            put("group", access.trust.group.value); put("genesisId", access.trust.genesis.id.value)
                            put("genesisHash", access.trust.genesis.bodyHash); put("rootCertificateSha256", sha(access.trust.ownerCertificate.toByteArray()))
                            put("dailyCertificateSha256", sha(access.certificate.toByteArray())); put("dailyDevice", access.device)
                            put("ledgerHash", ledger.bodyHash()); put("ledgerMembers", ledger.members.size)
                            put("authorSequence", author.committedSequence); put("authorId", version.reference().id.value)
                            put("authorHash", version.reference().bodyHash); put("nativeSequence", native.completedSequence)
                            put("nativeUnits", native.units.size); put("nativeGraphSha256", sha(graph.toByteArray()))
                            put("routeId", rows.single().id.value); put("routeCipherSha256", sha(routeCipher)); put("routeOpaquePlainSha256", routePlainHash)
                            put("defaultCredentialPlainSha256", credentialHash)
                        }
                        val protected = protectedInventory(local)
                        check(protected.isNotEmpty() && protected.keys.any { it.endsWith("identity.protected") } &&
                            protected.keys.any { it.endsWith("cloud-routes.protected") })
                        check(protected.keys.any { it.endsWith(".ccbsecret") })
                        if (original != null) {
                            check(logical == original.getValue("logical").jsonObject)
                            check(protected == original.getValue("protected").jsonObject)
                        }
                        phase = "CLOSE_REAL_APP_AND_VERIFY_OBSERVER_DID_NOT_REWRITE"
                        withTimeout(60_000) {
                            closeDesktopWithLanSyncCompletionNotice(close = { app.close() }, showNotice = { notices++ })
                        }
                        closed = true
                        teardownConfirmed = true
                        check(notices <= 1 && protectedInventory(local) == protected)
                        if (original == null) writeNew(oraclePath, buildJsonObject { put("logical", logical); put("protected", protected) })
                        phase = "SAFE_REPORT"
                        writeNew(report, buildJsonObject {
                            put("kind", "CCB_REAL_WINDOWS_GROUP_RETAINED_COMPONENT_V1"); put("passed", true)
                            put("action", action); put("stage", stage); put("sourceSha", source)
                            put("packagedMainLaunch", false); put("realAppContainer", true); put("realWindowsDpapi", true)
                            put("rootAndDailyDistinct", true); put("authorSequence", author.committedSequence)
                            put("nativeSequence", native.completedSequence); put("nativeUnitCount", native.units.size)
                            put("groupIdentityDigest", sha(logical.toString().toByteArray()))
                            put("protectedFileCount", protected.size); put("protectedInventorySha256", sha(protected.toString().toByteArray()))
                            put("routeDisabled", true); put("routeDpapiUnprotected", true); put("protectedBytesRetained", true)
                            put("defaultCredentialReadBack", true)
                            put("observerProtectedBytesUnchangedOnClose", true); put("componentPaused", true)
                            put("durablePauseClaimed", false); put("closeNoticeCount", notices)
                            put("recoveryInputUsedByVerifier", false); put("networkDeliveryVerified", false)
                        })
                    } finally {
                        if (!closed) withContext(NonCancellable) {
                            withTimeout(60_000) { closeDesktopWithLanSyncCompletionNotice(close = { app.close() }, showNotice = {}) }
                            teardownConfirmed = true
                        }
                    }
                } finally {
                    // A failed/unconfirmed teardown retains ownership until this test JVM dies.
                    if (teardownConfirmed) acquired.ownership.close()
                }
            }
        } catch (failure: Throwable) {
            // Original message/cause/suppressed data must never reach Gradle XML or logs.
            throw AssertionError("GROUP_RETAINED_COMPONENT:" + phase + ":" + failure.javaClass.simpleName)
                .also { it.stackTrace = emptyArray() }
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(150_000) {
        while (!predicate()) delay(100)
    }
    private fun env(key: String) = checkNotNull(System.getenv(key)).also { check(it.isNotBlank()) }
    private fun owned(raw: String, runner: Path): Path {
        val value = Path.of(raw).toRealPath()
        check(value.startsWith(runner) && value != runner)
        var current: Path? = value
        while (current != null) { check(!Files.isSymbolicLink(current)); current = current.parent }
        return value
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun readOracle(path: Path): JsonObject {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) < 262144)
        return Json.parseToJsonElement(Files.readString(path)).jsonObject.also { check(it.keys == setOf("logical", "protected")) }
    }
    private fun writeNew(path: Path, value: JsonObject) = Files.writeString(path, value.toString(), CREATE_NEW, WRITE)
    private fun protectedInventory(local: Path): JsonObject {
        val entries = sortedMapOf<String, String>()
        Files.walk(local).use { paths -> paths.forEach { path ->
            check(!Files.isSymbolicLink(path))
            if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                val relative = local.relativize(path).toString().replace('\\', '/')
                val selected = relative.endsWith(".protected") || relative.endsWith(".ccbsecret") || relative.endsWith("/management-vault.bin") ||
                    ((relative.contains("/group-profile/recipient/") || relative.contains("/group-profile/creation/")) && !relative.endsWith(".lock"))
                if (selected) {
                    check(Files.size(path) <= 16 * 1024 * 1024)
                    entries[relative] = sha(Files.readAllBytes(path))
                }
            }
        } }
        return buildJsonObject { entries.forEach { (key, digest) -> put(key, digest) } }
    }
    private fun containsPlaintext(root: Path, secret: ByteArray): Boolean {
        var found = false
        Files.walk(root).use { paths -> paths.forEach { path ->
            check(!Files.isSymbolicLink(path))
            if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                // Include runtime Temp files without inventing a file-size/business-data ceiling.
                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var tail = ByteArray(0)
                    try {
                        while (!found) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            val window = ByteArray(tail.size + count)
                            tail.copyInto(window)
                            buffer.copyInto(window, tail.size, 0, count)
                            tail.fill(0)
                            try {
                                var offset = 0
                                while (!found && offset + secret.size <= window.size) {
                                    var index = 0
                                    while (index < secret.size && window[offset + index] == secret[index]) index++
                                    if (index == secret.size) found = true
                                    offset++
                                }
                                tail = window.copyOfRange(maxOf(0, window.size - secret.size + 1), window.size)
                            } finally { window.fill(0) }
                        }
                    } finally { buffer.fill(0); tail.fill(0) }
                }
            }
        } }
        return found
    }
}
