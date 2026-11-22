package com.example.chatbar.desktop.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.WindowsSecretRootResolver
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.domain.syncStore.SyncAuthenticatedGroupObject
import com.example.chatbar.domain.syncStore.SyncVersionState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Comparator
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Test-only f5fc overlay, source reviewed only: no local native execution.
 * Actual DesktopAppContainer, Windows DPAPI, ordinary repositories and default production Local LAN.
 * No Compose UI/packagedMain, custom Network, UDP success claim, APK installation or Android mutation.
 * Android UI/controller must independently attest its actual read-only message/image observations. */
class DesktopV7LiveSyntheticHostDraftTest {
    private class Roots(val runner: Path, val local: Path, val roaming: Path)
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun liveProductionLanInvitesActualAndroidAndRetainsSyntheticMessageAndImage() {
        var phase = "native-guard"
        var failedPhase: String? = null
        var app: DesktopAppContainer? = null
        var roots: Roots? = null
        var live: Path? = null
        var offerOwned = false
        var imageHash: String? = null
        var peerApproved = false
        var controllerReadbackMatched = false
        val owned = mutableListOf<Path>()
        try {
            val safe = guardedRoots(); roots = safe
            val directory = safe.runner.resolve("ccb-v7-live")
            Files.createDirectories(directory)
            check(directory.toRealPath() == directory && directory.parent == safe.runner)
            live = directory
            check(!Files.exists(directory.resolve("live-offer.json"), NOFOLLOW_LINKS))
            check(!Files.exists(directory.resolve("android-readback.json"), NOFOLLOW_LINKS))
            check(!Files.exists(directory.resolve("windows-result.json"), NOFOLLOW_LINKS))
            runBlocking(edt) {
                try {
                    phase = "isolated-app-root"
                    val parent = Files.createTempDirectory(safe.runner, "ccb-v7-live-app-").toRealPath()
                    check(parent.parent == safe.runner); owned.add(parent)
                    val data = Files.createDirectory(parent.resolve("data"))
                    val resolved = DesktopDataRootResolution.Resolved(data,
                        DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
                    val syncRoot = DesktopLanSyncStartup.localRoot(data).toAbsolutePath().normalize()
                    val credentials = WindowsSecretRootResolver.resolve(data).toAbsolutePath().normalize()
                    check(syncRoot.startsWith(safe.local) && credentials.startsWith(safe.local))
                    check(!Files.exists(syncRoot, NOFOLLOW_LINKS) && !Files.exists(credentials, NOFOLLOW_LINKS))
                    owned.add(syncRoot); owned.add(credentials)
                    val current = DesktopAppContainer(resolvedRoot = resolved, bundledAssets = { path ->
                        check(path == "presets/manifest.json")
                        "{\"entries\":[]}".toByteArray(Charsets.UTF_8)
                    })
                    app = current
                    current.initializePersistentState()
                    check(!current.hasLanSyncGroupEvidence())
                    val coordinator = current.lanSyncGroupCoordinator

                    phase = "new-synthetic-group"
                    coordinator.createGroup()
                    await(90_000) { !coordinator.state.value.busy &&
                        coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
                    var recoveryCode: String? = checkNotNull(coordinator.state.value.recoveryCode)
                    coordinator.recoverySaved(checkNotNull(coordinator.state.value.setupBinding))
                    await(90_000) { !coordinator.state.value.busy &&
                        coordinator.state.value.stage == LanSyncGroupPanelStage.READY && coordinator.view.value != null }
                    check(coordinator.state.value.error == null)

                    phase = "ordinary-repository-message-and-real-png"
                    val png = realPng()
                    val image = data.resolve("images/ccb-v7-lab.png")
                    Files.createDirectories(image.parent); Files.write(image, png)
                    val expectedImageHash = lanSyncHash(png); imageHash = expectedImageHash
                    current.characterRepository.save(CharacterCard(CARD, "CCB synthetic LAN card",
                        createdAt = 1L, updatedAt = 1L, momentsEnabled = false))
                    current.chatRepository.createSession(ChatSession(SESSION, CARD,
                        "CCB synthetic LAN session", createdAt = 10L, updatedAt = 10L))
                    current.chatRepository.addMessage(ChatMessage(MESSAGE, SESSION, MessageRole.USER, TEXT,
                        images = listOf(image.toString()), createdAt = 11L, updatedAt = 11L))

                    phase = "automatic-authenticated-source-and-exact-local-receipt"
                    var authenticated: SyncAuthenticatedGroupObject? = null
                    await(120_000) {
                        val view = coordinator.view.value
                        val candidate = if (view == null) null else view.reconciliation.authenticatedVersions.firstOrNull { row ->
                            val version = row.envelope.version
                            version != null && version.changes["conversation/$SESSION"]?.content != null &&
                                view.reconciliation.history?.state(version.id) == SyncVersionState.ADMITTED &&
                                view.nativeState.units["conversation/$SESSION"]?.frontiers?.contains(version.reference()) == true &&
                                view.plan?.before?.records?.any { record -> record.type == "chat_messages" &&
                                    record.body["id"]?.jsonPrimitive?.contentOrNull == MESSAGE &&
                                    record.body["content"]?.jsonPrimitive?.contentOrNull == TEXT } == true
                        }
                        if (candidate != null && coordinator.state.value.local.lastSuccessAt != null) {
                            authenticated = candidate; true
                        } else false
                    }
                    val actual = checkNotNull(authenticated)
                    val access = checkNotNull(current.lanSyncGroupEnrollment.runtimeAccess())
                    check(checkNotNull(actual.envelope.version).author == access.device)
                    await(30_000) { access.node.sourceOperations().any { operation ->
                        val receipt = access.node.receipt(operation, access.localTarget)
                        receipt != null && receipt.target == access.localTarget && receipt.metadata == actual.metadata.descriptor
                    } }
                    check(Files.readAllBytes(image).contentEquals(png))

                    phase = "wait-for-real-android-preflight"
                    val readyPath = directory.resolve("android-ready.json")
                    await(300_000) { Files.isRegularFile(readyPath, NOFOLLOW_LINKS) }
                    check(Files.size(readyPath) in 1L..512L)
                    val androidReady = Json.parseToJsonElement(Files.readString(readyPath, Charsets.UTF_8)).jsonObject
                    check(androidReady.keys == setOf("kind", "ready"))
                    check(androidReady["kind"]?.jsonPrimitive?.contentOrNull == "CCB_V7_ANDROID_READY")
                    check(androidReady["ready"]?.jsonPrimitive?.booleanOrNull == true)
                    phase = "production-local-invitation"
                    coordinator.unlockManagement(checkNotNull(recoveryCode)); recoveryCode = null
                    await(45_000) { !coordinator.state.value.busy && coordinator.state.value.managementUnlocked }
                    coordinator.createInvitation()
                    await(45_000) { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
                    check(coordinator.state.value.error == null)
                    val invitation = checkNotNull(coordinator.state.value.invitation)
                    // Local default chooses/validates the actual host private address; no alias or Network override.
                    check(!LanSyncTls.parseAddress(invitation.host).isLoopbackAddress)
                    check(LanSyncTls.parseAddress(invitation.host).isSiteLocalAddress)
                    writePrivateNew(directory.resolve("live-offer.json"), buildJsonObject {
                        put("kind", "CCB_V7_LIVE_SYNTHETIC_OFFER"); put("code", invitation.code())
                        put("session", SESSION); put("message", MESSAGE); put("text", TEXT); put("imageSha256", expectedImageHash)
                    })
                    offerOwned = true

                    phase = "real-android-proof-and-explicit-member-confirmation"
                    await(110_000) { !coordinator.state.value.busy && coordinator.state.value.peerConfirmation != null }
                    val confirmation = checkNotNull(coordinator.state.value.peerConfirmation)
                    check(confirmation.fingerprint.matches(Regex("[a-f0-9]{64}")))
                    check(confirmation.name.isNotBlank())
                    check(confirmation.fingerprint != access.device)
                    coordinator.acceptPeer(confirmation.binding)
                    await(90_000) { !coordinator.state.value.busy &&
                        coordinator.state.value.devices.any { it.id == confirmation.fingerprint && !it.thisDevice } }
                    check(coordinator.state.value.error == null)
                    check(access.ledger().current().members[confirmation.fingerprint]?.active == true)
                    peerApproved = true

                    phase = "independent-android-readonly-body-and-image-readback"
                    val response = directory.resolve("android-readback.json")
                    await(180_000) { Files.isRegularFile(response, NOFOLLOW_LINKS) }
                    check(Files.size(response) in 1L..16_384L)
                    val readback = Json.parseToJsonElement(Files.readString(response, Charsets.UTF_8)).jsonObject
                    check(readback["kind"]?.jsonPrimitive?.contentOrNull == "CCB_V7_ANDROID_READBACK")
                    check(readback["session"]?.jsonPrimitive?.contentOrNull == SESSION)
                    check(readback["message"]?.jsonPrimitive?.contentOrNull == MESSAGE)
                    check(readback["text"]?.jsonPrimitive?.contentOrNull == TEXT)
                    check(readback["imageSha256"]?.jsonPrimitive?.contentOrNull == expectedImageHash)
                    check(readback["received"]?.jsonPrimitive?.booleanOrNull == true)
                    controllerReadbackMatched = true
                    val retained = current.chatRepository.getMessages(SESSION)
                    check(retained.size == 1 && retained.single().id == MESSAGE && retained.single().content == TEXT)
                    check(lanSyncHash(Files.readAllBytes(image)) == expectedImageHash)
                    check(coordinator.cloudSettings.value.rows.isEmpty())
                    check(coordinator.state.value.cloud.lastSuccessAt == null)
                    phase = "normal-app-close"
                    withTimeout(60_000) { current.close() }; app = null
                } catch (_: Throwable) { failedPhase = phase }
                finally { withContext(NonCancellable) {
                    try { app?.close(); app = null }
                    catch (_: Throwable) { if (failedPhase == null) failedPhase = "app-close-cleanup" }
                } }
            }
        } catch (_: Throwable) { if (failedPhase == null) failedPhase = phase }
        finally {
            // The live invitation is private coordination, NEVER an artifact. Do not delete controller readbacks.
            if (offerOwned) try { Files.deleteIfExists(checkNotNull(live).resolve("live-offer.json")) }
                catch (_: Throwable) { if (failedPhase == null) failedPhase = "private-offer-cleanup" }
            if (app == null && roots != null) for (path in owned.asReversed()) try { deleteOwned(path, roots!!.runner) }
                catch (_: Throwable) { if (failedPhase == null) failedPhase = "synthetic-root-cleanup" }
            live?.let { directory -> try { writePrivateNew(directory.resolve("windows-result.json"), buildJsonObject {
                put("kind", "CCB_V7_WINDOWS_LIVE_SYNTHETIC_RESULT"); put("sourceSha", SOURCE)
                put("passed", failedPhase == null); put("phase", failedPhase ?: "complete")
                put("peerApproved", peerApproved); put("controllerReadbackMatched", controllerReadbackMatched)
                put("session", SESSION); put("message", MESSAGE)
                imageHash?.let { put("imageSha256", it) }
                put("productionNetworkLocal", true); put("udpDiscoveryProved", false)
                put("packagedMainTested", false); put("humanAcceptance", false)
            }) } catch (_: Throwable) { if (failedPhase == null) failedPhase = "safe-result-write" } }
        }
        failedPhase?.let { throw AssertionError("SYNTHETIC_V7_WINDOWS_LIVE_PHASE_FAILED:$it") }
    }

    private fun guardedRoots(): Roots {
        check(System.getenv("GITHUB_ACTIONS") == "true")
        check(System.getenv("CCB_GROUP_UI_CONTROL") == "true")
        check(System.getenv("CCB_V7_LIVE_SYNTHETIC") == "true")
        check(System.getenv("CCB_V7_SOURCE_SHA") == SOURCE)
        check(System.getProperty("os.name").startsWith("Windows"))
        fun existing(name: String) = Path.of(checkNotNull(System.getenv(name))).toRealPath().also {
            check(it.isAbsolute && Files.isDirectory(it, NOFOLLOW_LINKS))
        }
        val runner = existing("RUNNER_TEMP"); val local = existing("LOCALAPPDATA"); val roaming = existing("APPDATA")
        check(local != runner && roaming != runner && local.startsWith(runner) && roaming.startsWith(runner))
        check(!local.startsWith(roaming) && !roaming.startsWith(local))
        check(!Files.exists(local.resolve(WindowsSecretRootResolver.DIRECTORY_NAME), NOFOLLOW_LINKS))
        check(!Files.exists(local.resolve("ChatChatBarDesktopLanSync"), NOFOLLOW_LINKS))
        return Roots(runner, local, roaming)
    }
    private fun realPng(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..7) for (x in 0..7) image.setRGB(x, y, if ((x + y) % 2 == 0) 0xff1478c8.toInt() else 0xfff0c040.toInt())
        return ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "png", output)); output.toByteArray() }
    }
    private suspend fun await(timeoutMillis: Long, ready: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!ready()) delay(100L)
    }
    private fun writePrivateNew(path: Path, value: JsonObject) {
        check(!Files.exists(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        val temporary = Files.createTempFile(path.parent, ".v7-private-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, path, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun deleteOwned(path: Path, runner: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        val target = path.toRealPath(); check(target != runner && target.startsWith(runner))
        Files.walk(target).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
            check(it.toAbsolutePath().normalize().startsWith(target) && !Files.isSymbolicLink(it)); Files.delete(it)
        } }
    }
    companion object {
        private const val SOURCE = "f5fc060546eb9479b2566375761903d383c538b1"
        private const val CARD = "ccb-v7-lab-card"
        private const val SESSION = "ccb-v7-lab-session"
        private const val MESSAGE = "ccb-v7-lab-message"
        private const val TEXT = "CCB-V7-synthetic-message"
    }
}
