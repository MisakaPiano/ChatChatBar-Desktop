package com.example.chatbar.lanSync

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetAddress
import kotlinx.serialization.json.*
import com.example.chatbar.domain.syncStore.SyncVersionState
import com.example.chatbar.domain.syncStore.SyncAuthenticatedGroupObject
import com.example.chatbar.domain.syncStore.SyncGroupObjectKind
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Test

/** Non-delivery debug app+test only. Real production LAN and app repositories;
 * instrumentation calls beginJoin, NOT ordinary Android invitation/paste UI proof. */
class LanV7ReverseSyntheticWriterTest {
    @Suppress("DEPRECATION")
    @Test fun appendOneMessageAndPngAfterProductionLanJoin(): Unit = runBlocking {
        var phase = "opt-in"
        var failed: String? = null
        var page: ActivityScenario<LanSyncActivity>? = null
        var progressRoot: java.nio.file.Path? = null
        var lastProgress = 0L
        var progressSequence = 0
        var initialSessionPresent: Boolean? = null
        var initialMessageExact: Boolean? = null
        var initialImageMatched: Boolean? = null
        var initialMessageCount: Int? = null
        var initialMessageCountCapped: Boolean? = null
        var appendAttempted = false
        var durableSessionVerified = false
        val appendCommitted = AtomicBoolean(false)
        var appendReturned = false
        var localReverseCount: Int? = null
        var oracleWritten = false
        var groupStage: String? = null
        var groupBusy: Boolean? = null
        var groupPaused: Boolean? = null
        var groupErrorPresent: Boolean? = null
        var diagnosticWriteFailed = false
        var progressJob: Job? = null
        var publicationJob: Job? = null
        var reverseRelay: V7ReverseTcpRelay? = null
        var listenerObserved = false
        var listenerStep = "NOT_REACHED"
        var listenerFailureClass: String? = null
        var listenerAccessAttached: Boolean? = null
        var listenerNodeRootRead: Boolean? = null
        var listenerPortPresent: Boolean? = null
        var listenerNearbyReady: Boolean? = null
        var listenerSocketOpen: Boolean? = null
        var listenerSamples = 0
        var readBackground: (() -> AndroidLanSyncBackgroundState)? = null
        var sourceSignedVersionObserved: Boolean? = null
        var sourceNativeFrontierObserved: Boolean? = null
        var sourceLocalReceiptObserved: Boolean? = null
        var publicationReadAvailable: Boolean? = null
        var readGroup: (() -> LanSyncGroupPanelState)? = null
        val progressLock = Any()
        val publicationLock = kotlinx.coroutines.sync.Mutex()
        fun report(force: Boolean = false) {
          synchronized(progressLock) {
            val root = progressRoot ?: return
            val now = System.nanoTime()
            if (!force && now - lastProgress < 1_000_000_000L) return
            lastProgress = now
            readGroup?.invoke()?.let { state ->
                groupStage = state.stage.name; groupBusy = state.busy; groupPaused = state.paused; groupErrorPresent = state.error != null
            }
            progressSequence = (progressSequence + 1).coerceAtMost(10_000)
            val background = readBackground?.invoke()
            val listenerDiagnostic = JSONObject().put("step", listenerStep)
                .put("firstFailureClass", listenerFailureClass ?: JSONObject.NULL)
                .put("accessAttached", listenerAccessAttached ?: JSONObject.NULL)
                .put("nodeRootRead", listenerNodeRootRead ?: JSONObject.NULL)
                .put("portPresent", listenerPortPresent ?: JSONObject.NULL)
                .put("nearbyReady", listenerNearbyReady ?: JSONObject.NULL)
                .put("socketBoundAndOpen", listenerSocketOpen ?: JSONObject.NULL)
                .put("samples", listenerSamples)
                .put("backgroundPhase", background?.phase?.name ?: JSONObject.NULL)
                .put("foregroundProtected", background?.foregroundProtected ?: JSONObject.NULL)
            val value = JSONObject().put("kind", "CCB_V7_ANDROID_REVERSE_PROGRESS").put("phase", phase)
                .put("sequence", progressSequence).put("firstFailurePhase", failed ?: JSONObject.NULL)
                .put("initialSessionPresent", initialSessionPresent ?: JSONObject.NULL)
                .put("initialMessageExact", initialMessageExact ?: JSONObject.NULL)
                .put("initialImageMatched", initialImageMatched ?: JSONObject.NULL)
                .put("initialMessageCount", initialMessageCount ?: JSONObject.NULL)
                .put("initialMessageCountCapped", initialMessageCountCapped ?: JSONObject.NULL)
                .put("appendAttempted", appendAttempted).put("appendCommitted", appendCommitted.get())
                .put("durableSessionVerified", durableSessionVerified)
                .put("appendReturned", appendReturned).put("localReverseCount", localReverseCount ?: JSONObject.NULL)
                .put("oracleWritten", oracleWritten).put("groupStage", groupStage ?: JSONObject.NULL)
                .put("groupBusy", groupBusy ?: JSONObject.NULL).put("groupPaused", groupPaused ?: JSONObject.NULL)
                .put("groupErrorPresent", groupErrorPresent ?: JSONObject.NULL).put("diagnosticWriteFailed", diagnosticWriteFailed)
                .put("listenerObserved", listenerObserved).put("listenerObservation", listenerDiagnostic)
                .put("isolatedRouteMode", "ADB_FORWARD_TRANSPARENT_TLS")
                .put("relayAccepted", reverseRelay?.accepted?.get()?.coerceAtMost(100) ?: JSONObject.NULL)
                .put("relayConnected", reverseRelay?.connected?.get()?.coerceAtMost(100) ?: JSONObject.NULL)
                .put("relayBytesBothDirections", reverseRelay?.let { it.toTarget.get() > 0 && it.fromTarget.get() > 0 } ?: JSONObject.NULL)
                .put("sourceSignedVersionObserved", sourceSignedVersionObserved ?: JSONObject.NULL)
                .put("sourceNativeFrontierObserved", sourceNativeFrontierObserved ?: JSONObject.NULL)
                .put("sourceLocalReceiptObserved", sourceLocalReceiptObserved ?: JSONObject.NULL)
                .put("publicationReadAvailable", publicationReadAvailable ?: JSONObject.NULL)
            try {
                val temporary = root.resolve("progress.tmp")
                Files.write(temporary, value.toString().toByteArray(Charsets.UTF_8))
                Files.move(temporary, root.resolve("progress.json"), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: Exception) { diagnosticWriteFailed = true }
          }
        }
        try {
            val args = InstrumentationRegistry.getArguments()
            check(args.getString("v7ReverseSynthetic") == "true" && args.getString("sourceSha") == SOURCE)
            val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
            readBackground = { app.lanSyncOwner.backgroundState.value }
            check(app.packageName == PACKAGE && app.packageManager.getPackageInfo(PACKAGE, 0).versionCode == 87)
            check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            phase = "startup"
            await(90_000) { app.lanSyncStartupReady }
            check(!app.lanSyncOwner.hasGroupEvidence())
            check(app.chatRepository.getSession(SESSION) == null)
            val files = app.filesDir.canonicalFile.toPath()
            val lab = Files.createDirectory(files.resolve("v7-reverse-lab"))
            check(lab.toRealPath().parent == files)
            progressRoot = lab; report(true)
            page = ActivityScenario.launch(LanSyncActivity::class.java)
            val coordinator = app.lanSyncOwner.groupCoordinator
            readGroup = { coordinator.state.value }
            // A commit callback is still observable if later repository work stalls.
            // This task reads atomics/projections and writes only the private safe progress file.
            progressJob = launch(Dispatchers.IO) { while (isActive) { report(); delay(1_000) } }
            fun observeGroup() { report() }
            await(90_000) { observeGroup(); coordinator.state.value.stage == LanSyncGroupPanelStage.EMPTY && !coordinator.state.value.busy }
            Files.write(lab.resolve("ready.json"), "{\"kind\":\"CCB_V7_ANDROID_READY\",\"ready\":true}".toByteArray(), CREATE_NEW, WRITE)
            phase = "private-synthetic-offer"
            report(true)
            val request = lab.resolve("offer.json")
            await(900_000) { report(); Files.isRegularFile(request, NOFOLLOW_LINKS) }
            check(Files.size(request) in 1L..20_000L)
            val raw = Files.readAllBytes(request)
            var code: String? = null
            var expectedInitialImageHash: String? = null
            try {
                val offer = JSONObject(raw.toString(Charsets.UTF_8))
                check(offer.keys().asSequence().toSet() == setOf("kind", "code", "session", "message", "text", "imageSha256"))
                check(offer.getString("kind") == "CCB_V7_LIVE_SYNTHETIC_OFFER")
                check(offer.getString("session") == SESSION && offer.getString("message") == "ccb-v7-lab-message")
                check(offer.getString("text") == "CCB-V7-synthetic-message")
                expectedInitialImageHash = offer.getString("imageSha256")
                check(expectedInitialImageHash!!.matches(Regex("[a-f0-9]{64}")))
                code = offer.getString("code")
                check(code!!.matches(Regex("CCB-GROUP-JOIN1:[A-Za-z0-9_-]{1,16384}")))
            } finally { raw.fill(0); Files.delete(request) }
            phase = "real-production-lan-join"
            report(true)
            coordinator.beginJoin(checkNotNull(code)); code = null
            await(180_000) {
                observeGroup()
                check(coordinator.state.value.error == null)
                coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy &&
                    !coordinator.state.value.paused && coordinator.state.value.devices.any { !it.thisDevice }
            }
            phase = "isolated-read-access"; listenerStep = "READ_ACCESS"; report(true)
            // Read the already-attached owner; never construct another node or change its network.
            val accessField = LanSyncGroupCoordinator::class.java.getDeclaredField("access").apply { isAccessible = true }
            val access = checkNotNull(accessField.get(coordinator) as LanSyncGroupRuntimeAccess?)
            listenerAccessAttached = true
            phase = "isolated-read-node-root"; listenerStep = "READ_NODE_ROOT"; report(true)
            val nodeRoot = checkNotNull(LanSyncGroupNode::class.java.getDeclaredField("root")
                .apply { isAccessible = true }.get(access.node) as java.nio.file.Path?)
            listenerNodeRootRead = true
            phase = "isolated-wait-production-listener"; listenerStep = "WAIT_ORIGINAL_LISTENER"; report(true)
            val lifecycle = checkNotNull(LanSyncGroupCoordinator::class.java.getDeclaredField("lifecycle")
                .apply { isAccessible = true }.get(coordinator))
            val portField = LanSyncGroupCoordinator::class.java.getDeclaredField("port").apply { isAccessible = true }
            val nearbyField = LanSyncGroupCoordinator::class.java.getDeclaredField("nearbyReady").apply { isAccessible = true }
            val transportField = LanSyncGroupCoordinator::class.java.getDeclaredField("transport").apply { isAccessible = true }
            val socketField = LanSyncGroupAppTransport::class.java.getDeclaredField("listener").apply { isAccessible = true }
            var observedPort: Int? = null
            // READY is published before the independent nearby worker starts its original listener.
            // Observe that worker for a bounded interval; no start/resume/open/probe is called here.
            await(30_000) {
                val snapshot = synchronized(lifecycle) {
                    check(accessField.get(coordinator) === access)
                    Triple(portField.get(coordinator) as Int?, nearbyField.getBoolean(coordinator),
                        transportField.get(coordinator) as LanSyncGroupAppTransport?)
                }
                val originalSocket = snapshot.third?.let { socketField.get(it) as javax.net.ssl.SSLServerSocket? }
                listenerSamples = (listenerSamples + 1).coerceAtMost(1_000)
                listenerPortPresent = snapshot.first != null
                listenerNearbyReady = snapshot.second
                listenerSocketOpen = originalSocket?.let { it.isBound && !it.isClosed && it.localPort == snapshot.first } ?: false
                val port = snapshot.first
                val ready = port != null && port in 1024..65535 && snapshot.second && listenerSocketOpen == true
                if (ready) observedPort = port
                report()
                ready
            }
            val ordinaryPort = checkNotNull(observedPort)
            listenerObserved = true; listenerStep = "ORIGINAL_LISTENER_READY"; report(true)
            phase = "isolated-reverse-route"; listenerStep = "RESOLVE_PRIVATE_ADDRESS"; report(true)
            val deviceAddress = LanSyncTls.parseAddress(LanSyncTls.localAddresses().single())
            // ADB connects only to this test relay. The app's original listener still sees
            // a real RFC1918 source, and still authenticates the original Windows certificate.
            listenerStep = "CREATE_LOOPBACK_RELAY"; report(true)
            reverseRelay = V7ReverseTcpRelay(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), deviceAddress, ordinaryPort, deviceAddress)
            listenerStep = "RELAY_READY"; report(true)
            val route = JSONObject().put("kind", "CCB_V7_ANDROID_ISOLATED_ROUTE").put("sourceSha", SOURCE)
                .put("group", access.trust.group.value).put("device", access.device).put("relayPort", reverseRelay!!.port)
            // This existing forward seed is pairing context, not another old86 acceptance.
            phase = "initial-repository-receive"; report(true)
            await(120_000) {
                observeGroup()
                initialSessionPresent = app.chatRepository.getSession(SESSION) != null
                val messages = app.chatRepository.getMessagesReadOnly(SESSION)
                initialMessageCount = messages.size.coerceAtMost(100)
                initialMessageCountCapped = messages.size > 100
                val incoming = messages.singleOrNull { it.id == "ccb-v7-lab-message" }
                initialMessageExact = incoming?.let { it.content == "CCB-V7-synthetic-message" && it.role == MessageRole.USER && it.images.size == 1 } ?: false
                val initialImage = incoming?.images?.singleOrNull()
                initialImageMatched = if (initialImage != null) try {
                    val image = java.nio.file.Paths.get(initialImage).toAbsolutePath().normalize()
                    if (!image.startsWith(files.resolve("images"))) null else Files.isRegularFile(image, NOFOLLOW_LINKS) &&
                        Files.size(image) in 1L..4_194_304L && lanSyncHash(Files.readAllBytes(image)) == expectedInitialImageHash
                } catch (_: Exception) { null } else null
                report()
                incoming != null
            }
            check(initialMessageExact == true && initialImageMatched == true)
            // Let the host replace only its hint responder after forward context is complete.
            Files.write(lab.resolve("route.json"), route.toString().toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
            listenerStep = "PRIVATE_ROUTE_WRITTEN"; report(true)
            check(app.chatRepository.getMessagesReadOnly(SESSION).none { it.id == MESSAGE })
            val beforeVersions = coordinator.view.value?.reconciliation?.authenticatedVersions.orEmpty()
                .mapNotNull { it.envelope.version?.reference() }.toSet()
            phase = "durable-imported-session"
            report(true)
            val durable = app.chatRepository.requireDurableSession(SESSION)
            check(durable.id == SESSION && durable.characterCardId == "ccb-v7-lab-card")
            durableSessionVerified = true
            phase = "ordinary-repository-append"
            report(true)
            val image = files.resolve("images/ccb-v7-reverse.png")
            Files.createDirectories(image.parent)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val png = try {
                for (y in 0..7) for (x in 0..7) bitmap.setPixel(x, y, if ((x + y) % 2 == 0) Color.MAGENTA else Color.CYAN)
                java.io.ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
                }
            } finally { bitmap.recycle() }
            Files.write(image, png, CREATE_NEW, WRITE)
            appendAttempted = true; report(true)
            val added = app.chatRepository.addMessage(ChatMessage(MESSAGE, SESSION, MessageRole.USER, TEXT,
                images = listOf(image.toString()), createdAt = 31L, updatedAt = 31L), onCommitted = { appendCommitted.set(true) })
            appendReturned = true; report(true)
            check(added.id == MESSAGE && added.sessionId == SESSION && added.content == TEXT && added.images == listOf(image.toString()))
            localReverseCount = app.chatRepository.getMessagesReadOnly(SESSION).count { it.id == MESSAGE }.coerceAtMost(100)
            report(true); check(localReverseCount == 1)
            check(Files.readAllBytes(image).contentEquals(png))
            // Passive only: no inspect/prepare/commit/upload/recover calls. A committed repo
            // row alone is not proof that normal automatic authoring/publication happened.
            suspend fun samplePublication() = publicationLock.withLock {
                    try {
                        val view = coordinator.view.value
                        val unit = "conversation/$SESSION"
                        val candidate = if (view == null) null else view.reconciliation.authenticatedVersions.firstOrNull { row ->
                            val version = row.envelope.version
                            version != null && version.author == access.device && version.reference() !in beforeVersions &&
                                version.changes[unit]?.content != null &&
                                view.reconciliation.history?.state(version.id) == SyncVersionState.ADMITTED &&
                                view.plan?.units?.get(unit)?.stage == LanSyncGroupNativeStage.UNCHANGED &&
                                view.plan?.units?.get(unit)?.currentHash == version.changes[unit]?.valueHash &&
                                view.plan?.before?.records?.any { record -> record.type == "chat_messages" &&
                                    record.body["id"]?.jsonPrimitive?.contentOrNull == MESSAGE &&
                                    record.body["content"]?.jsonPrimitive?.contentOrNull == TEXT } == true
                        }
                        sourceSignedVersionObserved = sourceSignedVersionObserved == true || candidate != null
                        if (candidate != null) {
                            val version = checkNotNull(candidate.envelope.version)
                            sourceNativeFrontierObserved = sourceNativeFrontierObserved == true ||
                                view!!.nativeState.units[unit]?.frontiers?.contains(version.reference()) == true
                            val receipt = existingLocalReceipt(nodeRoot, access, candidate)
                            sourceLocalReceiptObserved = sourceLocalReceiptObserved == true || receipt
                        } else {
                            if (sourceNativeFrontierObserved == null) sourceNativeFrontierObserved = false
                            if (sourceLocalReceiptObserved == null) sourceLocalReceiptObserved = false
                        }
                        publicationReadAvailable = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { publicationReadAvailable = false }
            }
            publicationJob = launch(Dispatchers.IO) {
                while (isActive) { samplePublication(); report(); delay(1_000) }
            }
            val oracle = JSONObject().put("kind", "CCB_V7_ANDROID_REVERSE_ORACLE").put("sourceSha", SOURCE)
                .put("session", SESSION).put("message", MESSAGE).put("text", TEXT)
                .put("imageSha256", lanSyncHash(png)).put("imageBytes", png.size).put("width", 8).put("height", 8)
            Files.write(lab.resolve("oracle.json"), oracle.toString().toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
            oracleWritten = true; report(true)
            phase = "normal-runtime-reverse-receipt"
            report(true)
            val ack = lab.resolve("windows-received.json")
            await(180_000) { observeGroup(); Files.isRegularFile(ack, NOFOLLOW_LINKS) }
            check(Files.size(ack) in 1L..512L)
            val response = JSONObject(Files.readAllBytes(ack).toString(Charsets.UTF_8))
            check(response.keys().asSequence().toSet() == setOf("kind", "received"))
            check(response.getString("kind") == "CCB_V7_WINDOWS_REVERSE_RECEIVED" && response.getBoolean("received"))
            samplePublication() // A final passive sample avoids accepting or failing on a stale one-second projection.
            check(sourceSignedVersionObserved == true && sourceNativeFrontierObserved == true && sourceLocalReceiptObserved == true)
            check(app.chatRepository.getMessagesReadOnly(SESSION).count { it.id == MESSAGE && it.content == TEXT } == 1)
            check(Files.readAllBytes(image).contentEquals(png))
        } catch (error: Throwable) { if (failed == null) {
            failed = phase
            listenerFailureClass = when (error) {
                is TimeoutCancellationException -> "TIMEOUT"
                is NoSuchFieldException -> "FIELD_UNAVAILABLE"
                is IllegalAccessException, is SecurityException -> "REFLECTION_REJECTED"
                is ClassCastException -> "TYPE_MISMATCH"
                is IllegalStateException -> "STATE_ASSERTION"
                is IllegalArgumentException -> "ARGUMENT_ASSERTION"
                is java.io.IOException -> "IO"
                else -> "OTHER"
            }
        } }
        finally {
            report(true)
            withContext(NonCancellable) { publicationJob?.cancelAndJoin(); progressJob?.cancelAndJoin() }
            try { reverseRelay?.close() } catch (_: Throwable) { if (failed == null) failed = "isolated-route-close" }
            try { page?.close() } catch (_: Throwable) { if (failed == null) failed = "activity-close" }
            report(true)
        }
        failed?.let { throw AssertionError("V7_REVERSE_ANDROID_PHASE_FAILED:$it") }
    }

    private suspend fun await(timeout: Long, ready: suspend () -> Boolean) = withTimeout(timeout) {
        while (!ready()) delay(100)
    }
    /** Original durable plan/receipt only; no journal getter, lock, recovery or file creation. */
    private fun existingLocalReceipt(nodeRoot: java.nio.file.Path, access: LanSyncGroupRuntimeAccess,
        candidate: SyncAuthenticatedGroupObject): Boolean {
        val directory = nodeRoot.resolve("source/publications")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return false
        check(nodeRoot.toRealPath() == nodeRoot && directory.toRealPath() == directory)
        check(Files.isDirectory(directory, NOFOLLOW_LINKS))
        fun read(path: java.nio.file.Path, maximum: Int): ByteArray {
            check(path.parent == directory && Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path)
            return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8_192)
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    check(output.size() <= maximum - size); output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
        }
        val paths = Files.newDirectoryStream(directory, "publication-*.json").use { rows ->
            rows.filter { it.fileName.toString().matches(Regex("publication-[a-f0-9]{32}\\.json")) }.also { check(it.size <= 64) }
        }
        return paths.any { path ->
            val raw = read(path, 1_048_576)
            val plan = Json.decodeFromString(LanSyncGroupPublishPlan.serializer(), raw.toString(Charsets.UTF_8))
            if (plan.kind != SyncGroupObjectKind.VERSION || plan.reference != candidate.envelope.version?.reference() ||
                plan.group != access.trust.group || plan.epoch != candidate.context.epoch || plan.keyCommitment != candidate.keyCommitment ||
                plan.metadata.key != candidate.context.key || plan.metadata.descriptor != candidate.metadata.descriptor) false
            else {
                val target = access.localTarget
                val receiptPath = directory.resolve("publication-receipt-${plan.metadata.operation.value}-${target.kind.name.lowercase(java.util.Locale.ROOT)}-${target.id.value}.json")
                if (!Files.exists(receiptPath, NOFOLLOW_LINKS)) false else {
                    val receipt = Json.decodeFromString(LanSyncGroupPublishReceipt.serializer(), read(receiptPath, 65_536).toString(Charsets.UTF_8))
                    receipt.target == target && receipt.metadata == candidate.metadata.descriptor && receipt.planHash == lanSyncHash(raw) &&
                        receipt.resourceCount == plan.blobs.size.toLong() && plan.keyDeliveries.isEmpty() && plan.revocationNotices.isEmpty() &&
                        receipt.recipientCount == 0L && receipt.keyDeliveryCount == 0L && receipt.revocationNoticeCount == 0L
                }
            }
        }
    }
    companion object {
        private const val SOURCE = "40981ae4045114d1d2a076a82b02b7c7f5275cc7"
        private const val PACKAGE = "com.example.chatbar.lansyncnext"
        private const val SESSION = "ccb-v7-lab-session"
        private const val MESSAGE = "ccb-v7-reverse-message"
        private const val TEXT = "CCB-V7-synthetic-android-reverse"
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
