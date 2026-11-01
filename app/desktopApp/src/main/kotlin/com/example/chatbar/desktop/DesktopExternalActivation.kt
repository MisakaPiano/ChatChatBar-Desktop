package com.example.chatbar.desktop

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

internal enum class DesktopExternalActivationFailure { INPUT, RECEIVER_UNAVAILABLE, REFUSED, DELIVERY_UNCONFIRMED, LOCAL_STATE }
internal class DesktopExternalActivationException(val failure: DesktopExternalActivationFailure) : IllegalStateException(failure.name)

/** Pre-business refusal is not an unsafe writer shutdown. Tests replace presentation, not ingress. */
internal fun runDesktopExternalActivation(args: Array<String>, home: Path, local: Path,
    report: (DesktopExternalActivationFailure) -> Unit = { failure ->
        if (System.getenv("CCB_DESKTOP_ACTIVATION_PATH") == null && System.getenv("CCB_DESKTOP_READY_PATH") == null) {
            val message = "文件投递未完成或尚未确认（${failure.name}）。请先查看已打开程序的导入队列，不要重复打开同一批文件。原文件和用户资料未修改。"
            if (System.getProperty("os.name").startsWith("Windows")) DesktopNativeDialogBrand.show(messageBox = {
                com.sun.jna.Native.load("user32", StartupMessageBoxApi::class.java)
                    .MessageBoxW(null, com.sun.jna.WString(message), com.sun.jna.WString("ChatChatBar — 打开文件"), 0x10030)
            }) else System.err.println(message)
        }
    }, business: (DesktopExternalActivation) -> Unit) {
    var owner: DesktopExternalActivation? = null
    try {
        val paths = DesktopExternalActivation.paths(args)
        owner = DesktopExternalActivation.open(DesktopExternalActivation.directory(home, local), paths)
        DesktopInstalledProgram.activationQueued()
        if (owner == null) DesktopInstalledProgram.forwarded()
    } catch (failure: Exception) {
        try { owner?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
        val reason = (failure as? DesktopExternalActivationException)?.failure
            ?: if (failure is IllegalArgumentException) DesktopExternalActivationFailure.INPUT else DesktopExternalActivationFailure.LOCAL_STATE
        try { report(reason) } catch (_: Exception) { System.err.println("EXTERNAL_ACTIVATION_REFUSED: ${reason.name}") }
        throw DesktopStartupBlockedException(DesktopStartupDiagnostic.externalActivation())
    }
    owner?.use(business)
}

internal data class DesktopExternalFile(val id: String, val path: Path, val attempted: Boolean = false)
internal data class DesktopExternalFilesState(val files: List<DesktopExternalFile> = emptyList(), val activation: Long = 0,
    val accepting: Boolean = true, val error: String? = null, val canReload: Boolean = false)

/** One active owner. Only pending paths/receipt IDs survive restart; never passwords or import choices. */
internal class DesktopExternalFileQueue(private val journal: Path? = null) {
    val recordPath: Path? get() = journal
    private val mutable = MutableStateFlow(DesktopExternalFilesState())
    val state = mutable.asStateFlow()
    private val requests = LinkedHashSet<String>()
    private var stopped = false
    init { reload() }
    @Synchronized fun reload() {
        if (stopped) return
        try {
        if (journal != null && Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            DesktopArchiveFiles.safe(journal, false); check(Files.size(journal) <= 1048576)
            val data = Json.parseToJsonElement(Files.readString(journal)).jsonObject
            check(data["version"]?.jsonPrimitive?.int == 1)
            val files = data.getValue("files").jsonArray.map { value ->
                val item = value.jsonObject
                DesktopExternalFile(item.getValue("id").jsonPrimitive.content, Path.of(item.getValue("path").jsonPrimitive.content),
                    item["attempted"]?.jsonPrimitive?.boolean ?: false)
            }
            check(files.size <= 128 && files.all { it.path.isAbsolute && it.path.toString().length <= 32768 && it.id.matches(Regex("[a-f0-9-]{36}")) })
            check(files.map { it.id }.distinct().size == files.size)
            data.getValue("requests").jsonArray.forEach { requests.add(it.jsonPrimitive.content) }
            check(requests.size <= 1024 && requests.all { it.matches(Regex("[a-f0-9-]{36}")) })
            mutable.value = DesktopExternalFilesState(files = files)
        } else check(mutable.value.error == null)
        } catch (_: Exception) {
            requests.clear()
            mutable.value = DesktopExternalFilesState(accepting = false,
                error = "外部导入队列记录无法读取，原记录已保留。普通会话可继续使用；新外部文件暂不接受。", canReload = true)
        }
    }
    private fun publish(next: DesktopExternalFilesState, seen: Set<String> = requests) {
        journal?.let { path ->
            val body = buildJsonObject {
                put("version", 1)
                putJsonArray("files") { next.files.forEach { file -> add(buildJsonObject { put("id", file.id); put("path", file.path.toString()); put("attempted", file.attempted) }) } }
                putJsonArray("requests") { seen.forEach { add(JsonPrimitive(it)) } }
            }.toString()
            check(body.toByteArray().size <= 1048576)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(path, false)
            val temporary = path.resolveSibling("pending.${UUID.randomUUID()}.tmp")
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = java.nio.ByteBuffer.wrap(body.toByteArray())
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        val retained = seen.toList(); requests.clear(); requests.addAll(retained)
        mutable.value = next
    }
    @Synchronized fun submit(request: String, paths: List<Path>): Boolean {
        if (stopped) return false
        if (paths.isEmpty()) { mutable.update { it.copy(activation = it.activation + 1) }; return true }
        if (request in requests) return true
        if (!mutable.value.accepting || paths.size > 64 || mutable.value.files.size + paths.size > 128) return false
        require(request.matches(Regex("[a-f0-9-]{36}")))
        require(paths.all { it.isAbsolute && it.toString().length <= 32768 && '\u0000' !in it.toString() })
        val seen = LinkedHashSet(requests).apply { add(request); while (size > 1024) remove(first()) }
        publish(mutable.value.copy(files = mutable.value.files + paths.map { path -> DesktopExternalFile(UUID.randomUUID().toString(), path.normalize()) },
            activation = mutable.value.activation + 1), seen)
        return true
    }
    @Synchronized fun acknowledge(id: String): Boolean {
        if (mutable.value.files.firstOrNull()?.id != id) return false
        publish(mutable.value.copy(files = mutable.value.files.drop(1)), requests.toSet()); return true
    }
    @Synchronized fun markAttempted(id: String) {
        check(mutable.value.accepting && mutable.value.files.firstOrNull()?.id == id)
        publish(mutable.value.copy(files = mutable.value.files.mapIndexed { index, file -> if (index == 0) file.copy(attempted = true) else file }), requests.toSet())
    }
    @Synchronized fun stopAccepting() { stopped = true; mutable.update { it.copy(accepting = false) } }
    @Synchronized fun receiverFailed() {
        stopped = true
        mutable.update { it.copy(accepting = false, canReload = false,
            error = "外部文件接收已停止，请正常重启；现有资料和待处理文件保留。") }
    }
}

/** Loopback activation transports paths only. The selected data-root single-writer lock is separate. */
internal class DesktopExternalActivation private constructor(
    val queue: DesktopExternalFileQueue,
    private val directory: Path,
    private val channel: FileChannel,
    private val lease: FileLock,
    private val server: ServerSocket,
    private val token: String,
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var client: Socket? = null
    private val endpoint = directory.resolve("endpoint")
    private val thread = Thread({
        while (!closed.get()) {
            val accepted = try { server.accept() } catch (_: Exception) {
                if (!closed.get()) queue.receiverFailed()
                break
            }
            try {
                accepted.use { socket ->
                    client = socket; socket.soTimeout = 2000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    check(input.readInt() == PROTOCOL)
                    check(MessageDigest.isEqual(input.readText(128).toByteArray(), token.toByteArray()))
                    val request = input.readText(64)
                    val count = input.readInt(); check(count in 0..64)
                    var total = 0
                    val paths = (0 until count).map {
                        val raw = input.readText(131072); total += raw.toByteArray().size; check(total <= 1048576); Path.of(raw)
                    }
                    output.writeInt(if (queue.submit(request, paths)) 0 else 1); output.flush()
                }
            } catch (_: Exception) { /* Refused input never reaches the queue or a data writer. */ }
            finally { client = null }
        }
    }, "ccb-file-activation").apply { isDaemon = true }

    private fun start() {
        val temporary = directory.resolve("endpoint.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, "${server.localPort}\n$token\n", StandardOpenOption.CREATE_NEW)
        Files.move(temporary, endpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        thread.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            queue.stopAccepting(); server.close(); client?.close(); thread.join(2500)
            if (Files.exists(endpoint, LinkOption.NOFOLLOW_LINKS) && Files.readString(endpoint).split('\n').getOrNull(1) == token) Files.delete(endpoint)
        } finally { try { lease.release() } finally { channel.close() } }
    }

    companion object {
        private const val PROTOCOL = 0x43434231
        fun paths(args: Array<String>): List<Path> {
            if (args.isEmpty()) return emptyList()
            val files = if (args.first() == "--open-files") args.drop(1) else args.toList()
            require(files.size in 1..64) { "EXTERNAL_ARGUMENTS_REFUSED" }
            return files.map { raw -> Path.of(raw).also { require(it.isAbsolute && raw.length <= 32768 && '\u0000' !in raw) } }
        }
        fun directory(home: Path, local: Path): Path {
            val normalized = home.toAbsolutePath().normalize()
            val identity = if (normalized.parent?.fileName?.toString() == "versions") normalized.parent.parent else normalized
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toString().lowercase().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return local.resolve("ChatChatBarDesktopActivation").resolve(key)
        }
        /** Null means the existing owner acknowledged this invocation; never start a second writer. */
        fun open(directory: Path, paths: List<Path>): DesktopExternalActivation? {
            var ancestor = directory.toAbsolutePath().normalize()
            while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
            DesktopArchiveFiles.safe(ancestor, true)
            Files.createDirectories(directory)
            DesktopArchiveFiles.safe(directory, true)
            val channel = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val lease = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lease == null) {
                channel.close(); forward(directory, paths); return null
            }
            var server: ServerSocket? = null
            try {
                val listening = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { server = it }
                val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                val owner = DesktopExternalActivation(DesktopExternalFileQueue(directory.resolve("pending.json")), directory, channel, lease, listening, token)
                check(owner.queue.submit(UUID.randomUUID().toString(), paths))
                owner.start(); return owner
            } catch (error: Throwable) {
                try { server?.close() } finally { try { lease.release() } finally { channel.close() } }
                throw error
            }
        }
        private fun forward(directory: Path, paths: List<Path>) {
            val deadline = System.nanoTime() + 5_000_000_000L
            var socket: Socket? = null
            var token: String? = null
            // Retry only discovery/connect, before any request bytes have been dispatched.
            while (socket == null && System.nanoTime() < deadline) {
                try {
                    val file = directory.resolve("endpoint")
                    DesktopArchiveFiles.safe(file, false); check(Files.size(file) <= 160)
                    val lines = Files.readString(file).split('\n')
                    val port = lines[0].toInt(); check(port in 1..65535)
                    token = lines[1]; check(token!!.matches(Regex("[a-f0-9]{64}")))
                    val candidate = Socket(java.net.Proxy.NO_PROXY)
                    try { candidate.connect(java.net.InetSocketAddress("127.0.0.1", port), 400); candidate.soTimeout = 3000; socket = candidate }
                    catch (error: Exception) { candidate.close(); throw error }
                } catch (_: Exception) { Thread.sleep(50) }
            }
            if (socket == null) throw DesktopExternalActivationException(DesktopExternalActivationFailure.RECEIVER_UNAVAILABLE)
            socket!!.use { connection ->
                val output = DataOutputStream(connection.getOutputStream())
                val response = try {
                    output.writeInt(PROTOCOL); output.writeText(requireNotNull(token)); output.writeText(UUID.randomUUID().toString())
                    output.writeInt(paths.size); paths.forEach { output.writeText(it.toString()) }; output.flush()
                    DataInputStream(connection.getInputStream()).readInt()
                } catch (_: java.io.IOException) {
                    // An absent ACK is indeterminate, not permission to replay an accepted batch.
                    throw DesktopExternalActivationException(DesktopExternalActivationFailure.DELIVERY_UNCONFIRMED)
                }
                if (response != 0) throw DesktopExternalActivationException(DesktopExternalActivationFailure.REFUSED)
            }
        }
        private fun DataInputStream.readText(maximum: Int): String {
            val size = readInt(); check(size in 0..maximum)
            val bytes = ByteArray(size).also(::readFully)
            return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        private fun DataOutputStream.writeText(value: String) { val bytes = value.toByteArray(); writeInt(bytes.size); write(bytes) }
    }
}
