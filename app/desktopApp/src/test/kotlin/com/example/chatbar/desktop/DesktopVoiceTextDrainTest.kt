package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.test.*

class DesktopVoiceTextDrainTest {
    @Test fun `owned auxiliary stream cancellation drains real socket and executor before return`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val opened = CountDownLatch(1); val eof = AtomicBoolean(false)
        val thread = Thread {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val byte = input.read(); check(byte >= 0); header.append(byte.toChar()); check(header.length < 8192)
                }
                val length = header.toString().lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                check(input.readNBytes(length).size == length)
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n3\r\n:\n\n\r\n").toByteArray())
                socket.getOutputStream().flush(); opened.countDown()
                eof.set(input.read() == -1)
            }
        }.also { it.name = "synthetic-voice-sse-drain"; it.start() }
        val transport = OpenAiStreamingTransport { true }
        val model = ModelConfig("synthetic", "synthetic", "http://127.0.0.1:${server.localPort}", "", "mock", createdAt = 1)
        val collector = launch(Dispatchers.IO) {
            transport.streamFishVoice(listOf(ChatApiMessage.text("system", "synthetic fixture"), ChatApiMessage.text("user", "synthetic")), model).collect()
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { opened.await(5, TimeUnit.SECONDS) })
            withTimeout(5000) { collector.cancelAndJoin(); transport.closeAndDrain() }
            withContext(Dispatchers.IO) { thread.join(5000) }
            assertFalse(thread.isAlive); assertTrue(eof.get())
        } finally { collector.cancelAndJoin(); transport.closeAndDrain(); server.close(); thread.join(5000) }
    }
}
