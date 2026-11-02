package com.example.chatbar.desktop

import java.nio.file.Files
import kotlin.test.*

class DesktopCommunityLoginReturnTest {
    private class Registry(var current: DesktopCommunityProtocolRecord? = null) : DesktopCommunityProtocolRegistry {
        var writes = 0; var removals = 0
        override fun read() = current
        override fun write(record: DesktopCommunityProtocolRecord, expected: DesktopCommunityProtocolRecord?) { check(current == expected); current = record; writes++ }
        override fun removeIfEqual(record: DesktopCommunityProtocolRecord) { if (current == record) { current = null; removals++ } }
    }
    @Test fun `temporary original scheme never replaces another handler and updates only its own versioned entry`() {
        val root = Files.createTempDirectory("ccb-community-protocol-")
        try {
            val home = Files.createDirectories(root.resolve("versions/build-1"))
            Files.write(home.resolve("ChatChatBarDesktop.exe"), byteArrayOf(1))
            val registry = Registry(DesktopCommunityProtocolRecord(null, "other-handler"))
            val endpoint = DesktopExternalActivation.directory(home, root.resolve("local"))
            val owner = DesktopCommunityProtocolReturn(home, true, registry, true, activationDirectory = endpoint)
            assertEquals(DesktopCommunityReturnStatus.OTHER_HANDLER, owner.prepare()); assertEquals(0, registry.writes)
            assertEquals("other-handler", registry.current!!.command)
            registry.current = null
            assertEquals(DesktopCommunityReturnStatus.READY, owner.prepare())
            val old = registry.current!!
            assertEquals(root.toString(), old.ownerRoot); assertTrue(old.command!!.contains("--community-auth \"$endpoint\" \"%1\""))
            val nextHome = Files.createDirectories(root.resolve("versions/build-2"))
            Files.write(nextHome.resolve("ChatChatBarDesktop.exe"), byteArrayOf(2))
            val next = DesktopCommunityProtocolReturn(nextHome, true, registry, true, activationDirectory = endpoint)
            assertEquals(DesktopCommunityReturnStatus.READY, next.prepare())
            val current = registry.current!!; assertNotEquals(old.command, current.command)
            owner.release(); assertEquals(current, registry.current)
            next.release(); assertNull(registry.current); assertEquals(1, registry.removals)
            assertEquals(DesktopCommunityReturnStatus.UNAVAILABLE, DesktopCommunityProtocolReturn(home, false, registry, true).prepare())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `authenticated warm callback has a separate memory-only purpose and never enters file receipts`() {
        val root = Files.createTempDirectory("ccb-community-auth-ipc-")
        try {
            val source = Files.write(root.resolve("synthetic.cbsave"), byteArrayOf(1, 2))
            val activation = requireNotNull(DesktopExternalActivation.open(root, listOf(source)))
            try {
                val callback = "chatbar://auth/callback?code=synthetic-authorization-code"
                val journal = Files.readAllBytes(requireNotNull(activation.queue.recordPath))
                assertFails { DesktopExternalActivation.forwardCommunityCallback(root, callback) }
                val accepted = mutableListOf<String>()
                activation.communityAuthReceiver = { synchronized(accepted) { accepted += it }; true }
                DesktopExternalActivation.forwardCommunityCallback(root, callback)
                assertEquals(listOf(callback), synchronized(accepted) { accepted.toList() })
                assertEquals(listOf(source), activation.queue.state.value.files.map { it.path })
                assertContentEquals(journal, Files.readAllBytes(requireNotNull(activation.queue.recordPath)))
                assertFalse(Files.readString(root.resolve("endpoint")).contains("synthetic"))
                assertFails { DesktopExternalActivation.forwardCommunityCallback(root, "https://example.invalid/auth#access_token=synthetic") }
                assertEquals(1, accepted.size)
            } finally { activation.close() }
        } finally { root.toFile().deleteRecursively() }
    }
}
