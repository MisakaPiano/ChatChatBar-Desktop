package com.example.chatbar.domain.syncStoreProof

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Synthetic loopback DAV with streamed disk bodies, so the large proof never buffers its data set. */
internal class CloudFileDav(private val root: Path, private val authorization: String) : Closeable {
    private val executor = Executors.newFixedThreadPool(4)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
    val putBytes = AtomicLong()
    val storedPutBytes = AtomicLong()
    val getBytes = AtomicLong()
    val requests = AtomicLong()
    val authorizedRequests = AtomicLong()
    val interruptedPath = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val puts = ConcurrentHashMap<String, AtomicLong>()
    val putBodyBytes = ConcurrentHashMap<String, AtomicLong>()
    val gets = ConcurrentHashMap<String, AtomicLong>()
    val getCalls = ConcurrentHashMap<String, AtomicLong>()
    val interruptedGetPath = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val completedPaths = ConcurrentHashMap.newKeySet<String>()
    val interruptedExpectedBytes = AtomicLong()
    private val interruptAt = AtomicLong(Long.MAX_VALUE)
    private val interruptGetAt = AtomicLong(Long.MAX_VALUE)
    val endpoint get() = "http://127.0.0.1:${server.address.port}/sync/"

    init {
        require(root.isAbsolute && root.toRealPath() == root && Files.isDirectory(root, NOFOLLOW_LINKS))
        Files.createDirectory(root.resolve("sync"))
        server.executor = executor
        server.createContext("/sync/") { exchange ->
            exchange.use {
                requests.incrementAndGet()
                if (it.requestHeaders.getFirst("Authorization") != authorization) { empty(it, 401); return@use }
                authorizedRequests.incrementAndGet()
                try { handle(it) }
                catch (_: IOException) { /* Interrupted synthetic PUT/GET retains exactly its disk prefix. */ }
            }
        }
        server.start()
    }

    fun interruptNextPutAfter(totalWireBytes: Long) {
        require(totalWireBytes > putBytes.get())
        interruptAt.set(totalWireBytes)
    }
    fun interruptNextGetAfter(totalWireBytes: Long) {
        require(totalWireBytes > getBytes.get())
        interruptGetAt.set(totalWireBytes)
    }

    fun bodyPath(relative: String): Path {
        require(relative.startsWith("/sync/") && !relative.contains(".."))
        return root.resolve(relative.removePrefix("/")).also { require(it.normalize().startsWith(root.resolve("sync"))) }
    }

    private fun handle(exchange: HttpExchange) {
        val uri = exchange.requestURI
        require(uri.rawPath == uri.path && uri.rawQuery == null)
        val segments = uri.path.split('/').filter(String::isNotEmpty)
        require(segments.firstOrNull() == "sync" && segments.all {
            it != "." && it != ".." && it.matches(Regex("[a-z0-9.-]{1,96}"))
        })
        val path = segments.fold(root) { parent, segment -> parent.resolve(segment) }.normalize()
        require(path.startsWith(root.resolve("sync")))
        var checked = root
        for (segment in segments) {
            checked = checked.resolve(segment)
            require(!Files.isSymbolicLink(checked))
        }
        when (exchange.requestMethod) {
            "MKCOL" -> when {
                Files.isDirectory(path, NOFOLLOW_LINKS) -> empty(exchange, 405)
                !Files.isDirectory(path.parent, NOFOLLOW_LINKS) -> empty(exchange, 409)
                else -> { Files.createDirectory(path); empty(exchange, 201) }
            }
            "PUT" -> put(exchange, path)
            "GET" -> {
                getCalls.computeIfAbsent(uri.path) { AtomicLong() }.incrementAndGet()
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) { empty(exchange, 404); return }
                val count = Files.size(path)
                exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, count)
                exchange.responseBody.use { output -> Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        val before = getBytes.get(); val cut = interruptGetAt.get()
                        val allowed = minOf(read.toLong(), (cut - before).coerceAtLeast(0)).toInt()
                        if (allowed > 0) {
                            output.write(buffer, 0, allowed)
                            getBytes.addAndGet(allowed.toLong())
                            gets.computeIfAbsent(uri.path) { AtomicLong() }.addAndGet(allowed.toLong())
                        }
                        if (allowed < read || before + allowed >= cut) {
                            if (interruptGetAt.compareAndSet(cut, Long.MAX_VALUE)) {
                                interruptedGetPath.set(uri.path)
                                return // Declared full Content-Length remains; closing cuts the real response body.
                            }
                        }
                    }
                } }
            }
            "PROPFIND" -> {
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) { empty(exchange, 404); return }
                exchange.responseHeaders.set("Content-Type", "application/xml; charset=utf-8")
                exchange.sendResponseHeaders(207, 0)
                exchange.responseBody.bufferedWriter(Charsets.UTF_8).use { output ->
                    output.write("<d:multistatus xmlns:d=\"DAV:\">")
                    output.write(entry(uri.path.trimEnd('/') + "/", true, 0))
                    if (exchange.requestHeaders.getFirst("Depth") == "1") Files.newDirectoryStream(path).use { children ->
                        for (child in children) {
                            require(!Files.isSymbolicLink(child))
                            val directory = Files.isDirectory(child, NOFOLLOW_LINKS)
                            output.write(entry(uri.path.trimEnd('/') + "/" + child.fileName + if (directory) "/" else "",
                                directory, if (directory) 0 else Files.size(child)))
                        }
                    }
                    output.write("</d:multistatus>")
                }
            }
            else -> empty(exchange, 405)
        }
    }

    private fun put(exchange: HttpExchange, path: Path) {
        if (!Files.isDirectory(path.parent, NOFOLLOW_LINKS)) { empty(exchange, 409); return }
        val name = exchange.requestURI.path
        puts.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
        // This ordinary provider ignores conditional-create; the proof separately forbids nonce reuse.
        FileChannel.open(path, CREATE, TRUNCATE_EXISTING, WRITE, NOFOLLOW_LINKS).use { channel ->
            val output = java.nio.channels.Channels.newOutputStream(channel)
            val input = exchange.requestBody; val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer); if (read < 0) break
                val cut = interruptAt.get()
                val before = putBytes.getAndAdd(read.toLong())
                putBodyBytes.computeIfAbsent(name) { AtomicLong() }.addAndGet(read.toLong())
                val allowed = minOf(read.toLong(), (cut - before).coerceAtLeast(0)).toInt()
                if (allowed > 0) { output.write(buffer, 0, allowed); storedPutBytes.addAndGet(allowed.toLong()) }
                if (allowed < read || before + read >= cut) {
                    if (interruptAt.compareAndSet(cut, Long.MAX_VALUE)) {
                        channel.force(true); interruptedPath.set(name)
                        interruptedExpectedBytes.set(exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1)
                        return // Closing without a response simulates a real lost/partial HTTP upload.
                    }
                }
            }
            channel.force(true)
        }
        completedPaths.add(name)
        empty(exchange, 201)
    }

    private fun empty(exchange: HttpExchange, code: Int) { exchange.sendResponseHeaders(code, -1) }
    private fun entry(path: String, directory: Boolean, length: Long) =
        "<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>" +
            (if (directory) "<d:collection/>" else "") +
            "</d:resourcetype><d:getcontentlength>$length</d:getcontentlength></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
}
