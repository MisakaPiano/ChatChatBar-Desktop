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

/** Test-only frozen candidate overlay, source reviewed only: no local native execution.
 * Actual DesktopAppContainer, Windows DPAPI, ordinary repositories and default production Local LAN.
 * No Compose UI/packagedMain, custom Network, UDP success claim, APK installation or Android mutation.
 * Candidate87 debug instrumentation authors the return message through the real app repository.
 * This separate reverse scenario does not rerun the delivered86 UI acceptance. */
class DesktopV7ReverseSyntheticHostDraftTest {
    private class Roots(val runner: Path, val local: Path, val roaming: Path)
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun productionLanReceivesActualAndroidReverseMessageAndPng() {
        var phase = "native-guard"
        var failedPhase: String? = null
        var app: DesktopAppContainer? = null
        var roots: Roots? = null
        var live: Path? = null
        var offerOwned = false
        var imageHash: String? = null
        var peerApproved = false
        var controllerReadbackMatched = false
        var reverseDiagnostics: JsonObject? = null
        var reverseStep = "NOT_REACHED"
        val reverseObservations = mutableListOf<JsonObject>()
        val owned = mutableListOf<Path>()
        var reverseRelay: V7ReverseTcpRelay? = null
        var reverseHint: V7ReverseFixedHint? = null
        var isolatedRouteInstalled = false
        try {
            val safe = guardedRoots(); roots = safe
            val directory = safe.runner.resolve("ccb-v7-live")
            Files.createDirectories(directory)
            check(directory.toRealPath() == directory && directory.parent == safe.runner)
            live = directory
            check(!Files.exists(directory.resolve("live-offer.json"), NOFOLLOW_LINKS))
            check(!Files.exists(directory.resolve("reverse-expected.json"), NOFOLLOW_LINKS))
            check(!Files.exists(directory.resolve("windows-result.json"), NOFOLLOW_LINKS))
            check(!Files.exists(directory.resolve("windows-reverse-received.json"), NOFOLLOW_LINKS))
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
                    await(165_000) { !coordinator.state.value.busy && coordinator.state.value.peerConfirmation != null }
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

                    phase = "isolated-reverse-route"
                    val routePath = directory.resolve("android-isolated-route.json")
                    await(90_000) { Files.isRegularFile(routePath, NOFOLLOW_LINKS) }
                    check(Files.size(routePath) in 1L..2_048L)
                    val route = Json.parseToJsonElement(Files.readString(routePath, Charsets.UTF_8)).jsonObject
                    check(route.keys == setOf("kind", "sourceSha", "group", "device", "forwardPort"))
                    check(route["kind"]?.jsonPrimitive?.contentOrNull == "CCB_V7_HOST_ISOLATED_ROUTE")
                    check(route["sourceSha"]?.jsonPrimitive?.contentOrNull == SOURCE)
                    check(route["group"]?.jsonPrimitive?.contentOrNull == access.trust.group.value)
                    check(route["device"]?.jsonPrimitive?.contentOrNull == confirmation.fingerprint)
                    val forwardPort = checkNotNull(route["forwardPort"]?.jsonPrimitive?.intOrNull)
                    check(forwardPort in 1024..65535)
                    val hostAddress = LanSyncTls.parseAddress(invitation.host)
                    reverseRelay = V7ReverseTcpRelay(hostAddress, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), forwardPort)
                    // Test address hints only. Leave transport, Local, reader, TLS and all
                    // automatic discover/prefetch/native workers untouched. Avoid two Windows
                    // SO_REUSEADDR responders competing for the same UDP39238 traffic.
                    val discoveryField = LanSyncGroupCoordinator::class.java.getDeclaredField("discovery").apply { isAccessible = true }
                    val lifecycle = LanSyncGroupCoordinator::class.java.getDeclaredField("lifecycle").apply { isAccessible = true }.get(coordinator)
                    val replacement = LanSyncGroupDiscovery(access, LanSyncNetwork.Local) { _, _ -> }
                    val old = synchronized(lifecycle) {
                        val previous = discoveryField.get(coordinator) as LanSyncGroupDiscovery?
                        check(previous != null); discoveryField.set(coordinator, replacement); previous
                    }
                    old.close()
                    reverseHint = V7ReverseFixedHint(access.trust.group.value, confirmation.fingerprint,
                        reverseRelay!!.port, hostAddress)
                    isolatedRouteInstalled = true

                    phase = "ordinary-reverse-repository-message-and-png"
                    reverseStep = "WAIT_ORACLE_AND_MESSAGE"
                    val oraclePath = directory.resolve("reverse-expected.json")
                    val reverseStarted = System.nanoTime()
                    var lastReverseSample = 0L
                    var oracleEver = false
                    var ordinaryReverseEver = false
                    var readOnlyReverseEver = false
                    await(180_000) {
                        val oraclePresent = Files.isRegularFile(oraclePath, NOFOLLOW_LINKS)
                        val sessionPresent = current.chatRepository.getSession(SESSION) != null
                        val ordinary = current.chatRepository.getMessages(SESSION)
                        val readOnly = try { current.chatRepository.getMessagesReadOnly(SESSION) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        val ordinaryCount = ordinary.count { it.id == REVERSE_MESSAGE }
                        val readOnlyCount = readOnly?.count { it.id == REVERSE_MESSAGE }
                        oracleEver = oracleEver || oraclePresent
                        ordinaryReverseEver = ordinaryReverseEver || ordinaryCount > 0
                        readOnlyReverseEver = readOnlyReverseEver || (readOnlyCount ?: 0) > 0
                        val observed = buildJsonObject {
                            put("seconds", ((System.nanoTime() - reverseStarted) / 1_000_000_000L).coerceIn(0, 180))
                            put("oraclePresent", oraclePresent); put("oracleEverPresent", oracleEver)
                            put("repositorySessionPresent", sessionPresent)
                            put("ordinaryMessageCount", ordinary.size.coerceAtMost(100)); put("ordinaryCountCapped", ordinary.size > 100)
                            put("ordinaryReverseIdCount", ordinaryCount.coerceAtMost(100)); put("ordinaryReverseEverPresent", ordinaryReverseEver)
                            put("readOnlyStatus", if (readOnly == null) "READ_ERROR" else "READABLE")
                            put("readOnlyMessageCount", readOnly?.size?.coerceAtMost(100))
                            put("readOnlyCountCapped", readOnly?.let { it.size > 100 })
                            put("readOnlyReverseIdCount", readOnlyCount?.coerceAtMost(100)); put("readOnlyReverseEverPresent", readOnlyReverseEver)
                            put("groupBusy", coordinator.state.value.busy); put("groupPaused", coordinator.state.value.paused)
                            put("groupErrorPresent", coordinator.state.value.error != null)
                            put("nearbyLastSuccessPresent", coordinator.state.value.nearby.lastSuccessAt != null)
                            put("routeHintReplies", reverseHint!!.replies.get().coerceAtMost(100))
                            put("routeRelayAccepted", reverseRelay!!.accepted.get().coerceAtMost(100))
                            put("routeRelayConnected", reverseRelay!!.connected.get().coerceAtMost(100))
                            put("routeRelayBytesBothDirections", reverseRelay!!.toTarget.get() > 0 && reverseRelay!!.fromTarget.get() > 0)
                            put("waitState", when {
                                oraclePresent && ordinaryCount > 0 -> "BOTH_PRESENT"
                                oraclePresent -> "ORACLE_ONLY"
                                ordinaryCount > 0 -> "MESSAGE_ONLY"
                                else -> "MISSING_BOTH"
                            })
                        }
                        reverseDiagnostics = observed
                        if (System.nanoTime() - lastReverseSample >= 1_000_000_000L) {
                            lastReverseSample = System.nanoTime(); reverseObservations += observed
                            if (reverseObservations.size > 8) reverseObservations.removeAt(0)
                        }
                        // Preserve the original two-gate acceptance, deadline and ordinary path.
                        oraclePresent && ordinaryCount > 0
                    }
                    reverseStep = "ORACLE_VALIDATE"
                    check(Files.size(oraclePath) in 1L..16_384L)
                    val oracle = Json.parseToJsonElement(Files.readString(oraclePath, Charsets.UTF_8)).jsonObject
                    check(oracle.keys == setOf("kind", "sourceSha", "session", "message", "text", "imageSha256", "imageBytes", "width", "height"))
                    check(oracle["kind"]?.jsonPrimitive?.contentOrNull == "CCB_V7_ANDROID_REVERSE_ORACLE")
                    check(oracle["sourceSha"]?.jsonPrimitive?.contentOrNull == SOURCE)
                    check(oracle["session"]?.jsonPrimitive?.contentOrNull == SESSION)
                    check(oracle["message"]?.jsonPrimitive?.contentOrNull == REVERSE_MESSAGE)
                    check(oracle["text"]?.jsonPrimitive?.contentOrNull == REVERSE_TEXT)
                    val reverseHash = checkNotNull(oracle["imageSha256"]?.jsonPrimitive?.contentOrNull)
                    check(reverseHash.matches(Regex("[a-f0-9]{64}")) && reverseHash != expectedImageHash)
                    val reverseBytes = checkNotNull(oracle["imageBytes"]?.jsonPrimitive?.longOrNull)
                    check(reverseBytes in 1L..4_194_304L)
                    check(oracle["width"]?.jsonPrimitive?.intOrNull == 8 && oracle["height"]?.jsonPrimitive?.intOrNull == 8)
                    reverseStep = "REPOSITORY_VALIDATE"
                    val received = current.chatRepository.getMessages(SESSION)
                    check(received.size == 2 && received.map { it.id }.toSet() == setOf(MESSAGE, REVERSE_MESSAGE))
                    check(received.single { it.id == MESSAGE }.content == TEXT)
                    val reverse = received.single { it.id == REVERSE_MESSAGE }
                    check(reverse.content == REVERSE_TEXT && reverse.role == MessageRole.USER && reverse.images.size == 1)
                    reverseStep = "PNG_VALIDATE"
                    val actualImage = Path.of(reverse.images.single()).toAbsolutePath().normalize()
                    val imageRoot = data.resolve("images").toRealPath()
                    check(actualImage.startsWith(imageRoot) && Files.isRegularFile(actualImage, NOFOLLOW_LINKS))
                    check(actualImage.toRealPath() == actualImage && Files.size(actualImage) == reverseBytes)
                    check(lanSyncHash(Files.readAllBytes(actualImage)) == reverseHash)
                    val bitmap = checkNotNull(ImageIO.read(actualImage.toFile()))
                    check(bitmap.width == 8 && bitmap.height == 8)
                    for (y in 0..7) for (x in 0..7) check(bitmap.getRGB(x, y) ==
                        if ((x + y) % 2 == 0) 0xffff00ff.toInt() else 0xff00ffff.toInt())
                    check(lanSyncHash(Files.readAllBytes(image)) == expectedImageHash)
                    check(coordinator.cloudSettings.value.rows.isEmpty())
                    check(coordinator.state.value.cloud.lastSuccessAt == null)
                    controllerReadbackMatched = true
                    reverseStep = "ACK_WRITE"
                    writePrivateNew(directory.resolve("windows-reverse-received.json"), buildJsonObject {
                        put("kind", "CCB_V7_WINDOWS_REVERSE_RECEIVED"); put("received", true)
                    })
                    reverseStep = "COMPLETED"
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
            try { reverseHint?.close() }
                catch (_: Throwable) { if (failedPhase == null) failedPhase = "isolated-route-close" }
            try { reverseRelay?.close() }
                catch (_: Throwable) { if (failedPhase == null) failedPhase = "isolated-route-close" }
            // The live invitation is private coordination, NEVER an artifact. Do not delete controller readbacks.
            if (offerOwned) try { Files.deleteIfExists(checkNotNull(live).resolve("live-offer.json")) }
                catch (_: Throwable) { if (failedPhase == null) failedPhase = "private-offer-cleanup" }
            if (app == null && roots != null) for (path in owned.asReversed()) try { deleteOwned(path, roots!!.runner) }
                catch (_: Throwable) { if (failedPhase == null) failedPhase = "synthetic-root-cleanup" }
            live?.let { directory -> try { writePrivateNew(directory.resolve("windows-result.json"), buildJsonObject {
                put("kind", "CCB_V7_WINDOWS_REVERSE_SYNTHETIC_RESULT"); put("sourceSha", SOURCE)
                put("passed", failedPhase == null); put("phase", failedPhase ?: "complete")
                put("peerApproved", peerApproved); put("reverseMessageAndPngMatched", controllerReadbackMatched)
                put("reverseDiagnostics", reverseDiagnostics ?: JsonNull)
                put("reverseObservations", JsonArray(reverseObservations))
                put("reverseStep", reverseStep)
                put("session", SESSION); put("message", REVERSE_MESSAGE)
                imageHash?.let { put("forwardContextImageSha256", it) }
                put("productionNetworkLocal", true); put("udpDiscoveryProved", false)
                put("isolatedRouteMode", "ADB_FORWARD_TRANSPARENT_TLS"); put("isolatedRouteInstalled", isolatedRouteInstalled)
                put("packagedMainTested", false); put("humanAcceptance", false)
            }) } catch (_: Throwable) { if (failedPhase == null) failedPhase = "safe-result-write" } }
        }
        failedPhase?.let { throw AssertionError("SYNTHETIC_V7_WINDOWS_LIVE_PHASE_FAILED:$it") }
    }

    private fun guardedRoots(): Roots {
        check(System.getenv("GITHUB_ACTIONS") == "true")
        check(System.getenv("CCB_GROUP_UI_CONTROL") == "true")
        check(System.getenv("CCB_V7_LIVE_SYNTHETIC") == "true")
        check(System.getenv("CCB_V7_REVERSE_SYNTHETIC") == "true")
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
        private const val SOURCE = "40981ae4045114d1d2a076a82b02b7c7f5275cc7"
        private const val REVERSE_MESSAGE = "ccb-v7-reverse-message"
        private const val REVERSE_TEXT = "CCB-V7-synthetic-android-reverse"
        private const val CARD = "ccb-v7-lab-card"
        private const val SESSION = "ccb-v7-lab-session"
        private const val MESSAGE = "ccb-v7-lab-message"
        private const val TEXT = "CCB-V7-synthetic-message"
    }
}


/** Test-only byte relay; it never terminates TLS or reads application protocol fields. */
private class V7ReverseTcpRelay(bind: java.net.InetAddress, private val target: java.net.InetAddress,
    private val targetPort: Int, private val source: java.net.InetAddress? = null) : AutoCloseable {
    private val server = java.net.ServerSocket()
    private val lock = Any()
    private val sockets = mutableSetOf<java.net.Socket>()
    private val workers = mutableSetOf<Thread>()
    private val slots = java.util.concurrent.Semaphore(4)
    @Volatile private var closed = false
    val accepted = java.util.concurrent.atomic.AtomicInteger()
    val connected = java.util.concurrent.atomic.AtomicInteger()
    val toTarget = java.util.concurrent.atomic.AtomicLong()
    val fromTarget = java.util.concurrent.atomic.AtomicLong()
    val failures = java.util.concurrent.atomic.AtomicInteger()
    val port: Int get() = server.localPort
    private val listener: Thread
    init {
        check(targetPort in 1024..65535)
        try { server.bind(java.net.InetSocketAddress(bind, 0), 4); check(server.localPort in 1024..65535) }
        catch (failure: Throwable) { server.close(); throw failure }
        listener = Thread({
            while (!closed) try {
                val incoming = server.accept()
                // The synthetic route is same-host only; unrelated peers never enter
                // counters, worker slots, or the original authenticated Android listener.
                if (incoming.inetAddress != bind) { incoming.close(); continue }
                if (!slots.tryAcquire()) { incoming.close(); continue }
                accepted.incrementAndGet()
                synchronized(lock) { if (closed) { incoming.close(); slots.release() }
                    else {
                        sockets += incoming
                        val worker = Thread({ transfer(incoming) }, "ccb-test-relay").apply { isDaemon = true }
                        workers += worker; worker.start()
                    }
                }
            } catch (_: Exception) { if (!closed) failures.incrementAndGet() }
        }, "ccb-test-relay-listen").apply { isDaemon = true; start() }
    }
    private fun pump(input: java.net.Socket, output: java.net.Socket, count: java.util.concurrent.atomic.AtomicLong) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (!closed) {
            val size = input.getInputStream().read(buffer)
            if (size < 0) { runCatching { output.shutdownOutput() }; return }
            total += size
            check(total <= 32L * 1024 * 1024)
            output.getOutputStream().write(buffer, 0, size); output.getOutputStream().flush()
            count.addAndGet(size.toLong())
        }
    }
    private fun transfer(incoming: java.net.Socket) {
        val outgoing = java.net.Socket()
        var reverse: Thread? = null
        try {
            synchronized(lock) { check(!closed); sockets += outgoing }
            incoming.soTimeout = 15_000; outgoing.soTimeout = 15_000
            source?.let { outgoing.bind(java.net.InetSocketAddress(it, 0)) }
            outgoing.connect(java.net.InetSocketAddress(target, targetPort), 5_000)
            connected.incrementAndGet()
            val returnWorker = Thread({
                try { pump(outgoing, incoming, fromTarget) }
                catch (_: Exception) { if (!closed) failures.incrementAndGet() }
                finally { runCatching { incoming.close() }; runCatching { outgoing.close() }
                    synchronized(lock) { workers -= Thread.currentThread() } }
            }, "ccb-test-relay-return").apply { isDaemon = true }
            reverse = returnWorker
            synchronized(lock) { workers += returnWorker }; returnWorker.start()
            pump(incoming, outgoing, toTarget)
            reverse.join(1_000)
        } catch (_: Exception) { if (!closed) failures.incrementAndGet() }
        finally {
            runCatching { incoming.close() }; runCatching { outgoing.close() }
            reverse?.join(1_000)
            synchronized(lock) { sockets -= incoming; sockets -= outgoing; workers -= Thread.currentThread() }
            slots.release()
        }
    }
    override fun close() {
        closed = true; server.close()
        synchronized(lock) { sockets.toList() }.forEach { runCatching { it.close() } }
        val deadline = System.nanoTime() + 3_000_000_000L
        (listOf(listener) + synchronized(lock) { workers.toList() }).forEach {
            if (it !== Thread.currentThread()) it.join(((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1))
        }
        check(!listener.isAlive && synchronized(lock) { workers.none { it.isAlive } })
    }
}


/** Exactly one synthetic target's bounded address hint, never membership authority. */
private class V7ReverseFixedHint(private val group: String, private val device: String,
    private val port: Int, private val host: java.net.InetAddress) : AutoCloseable {
    private val socket = java.net.DatagramSocket(null)
    @Volatile private var closed = false
    val replies = java.util.concurrent.atomic.AtomicInteger()
    private val thread: Thread
    init {
        check(group.matches(Regex("[a-f0-9]{32}")) && device.matches(Regex("[a-f0-9]{64}")))
        check(port in 1024..65535 && host.isSiteLocalAddress && !host.isLoopbackAddress)
        socket.reuseAddress = false
        try { socket.bind(java.net.InetSocketAddress(LanSyncGroupDiscovery.PORT)); socket.soTimeout = 500 }
        catch (failure: Throwable) { socket.close(); throw failure }
        thread = Thread({
            while (!closed) try {
                val packet = java.net.DatagramPacket(ByteArray(385), 385)
                try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                if (packet.address != host || packet.length !in 1..384) continue
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                if (bytes.any { it.toInt() !in 32..126 }) continue
                val fields = bytes.toString(Charsets.US_ASCII).split(' ')
                if (fields.size != 4 || fields[0] != "CCB-GROUP-FIND-1" || fields[1] != group ||
                    fields[2] != device || !fields[3].matches(Regex("[a-f0-9]{64}"))) continue
                val response = "CCB-GROUP-HERE-1 $group $device $port ${fields[3]}".toByteArray(Charsets.US_ASCII)
                socket.send(java.net.DatagramPacket(response, response.size, packet.address, packet.port))
                replies.incrementAndGet()
            } catch (_: Exception) { if (!closed) break }
        }, "ccb-test-fixed-hint").apply { isDaemon = true; start() }
    }
    override fun close() { closed = true; socket.close(); thread.join(2_000); check(!thread.isAlive) }
}
