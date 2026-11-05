package com.example.chatbar.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.nio.file.Files
import java.nio.file.Path

internal enum class DesktopCommunityReturnStatus { READY, OTHER_HANDLER, UNAVAILABLE }
internal interface DesktopCommunityLoginReturn {
    fun prepare(): DesktopCommunityReturnStatus
    fun release()
}
internal object UnavailableCommunityLoginReturn : DesktopCommunityLoginReturn {
    override fun prepare() = DesktopCommunityReturnStatus.UNAVAILABLE
    override fun release() = Unit
}

internal data class DesktopCommunityProtocolRecord(val ownerRoot: String?, val command: String?)
internal interface DesktopCommunityProtocolRegistry {
    fun read(): DesktopCommunityProtocolRecord?
    fun write(record: DesktopCommunityProtocolRecord, expected: DesktopCommunityProtocolRecord?)
    fun removeIfEqual(record: DesktopCommunityProtocolRecord)
}

/** Temporary original-CCB URI handler, owned only by this running image's pending login intent. */
internal class DesktopCommunityProtocolReturn(private val home: Path, private val receiverAvailable: Boolean,
    private val registry: DesktopCommunityProtocolRegistry = WindowsCommunityProtocolRegistry,
    private val platformAvailable: Boolean = System.getProperty("os.name").startsWith("Windows"),
    private val activationDirectory: Path? = null,
) : DesktopCommunityLoginReturn {
    private val root = home.toAbsolutePath().normalize().let { if (it.parent?.fileName?.toString() == "versions") it.parent.parent else it }
    private val executable = home.resolve("ChatChatBarDesktop.exe").toAbsolutePath().normalize()
    private var installed: DesktopCommunityProtocolRecord? = null
    override fun prepare(): DesktopCommunityReturnStatus {
        if (!receiverAvailable || !platformAvailable || activationDirectory == null) return DesktopCommunityReturnStatus.UNAVAILABLE
        return try {
            DesktopArchiveFiles.safe(executable, false)
            check(Files.isRegularFile(executable))
            check(executable.toString().none { it == '"' || it.code < 32 })
            DesktopExternalActivation.validateCommunityDirectory(home, activationDirectory)
            check(activationDirectory.toString().none { it == '"' || it.code < 32 })
            val current = registry.read()
            if (current != null && !owned(current)) return DesktopCommunityReturnStatus.OTHER_HANDLER
            val record = DesktopCommunityProtocolRecord(root.toString(), "\"$executable\" --community-auth \"$activationDirectory\" \"%1\"")
            installed = record
            registry.write(record, current)
            check(registry.read() == record)
            installed = record
            DesktopCommunityReturnStatus.READY
        } catch (_: Exception) {
            runCatching { release() }.onFailure { System.err.println("COMMUNITY_PROTOCOL_ROLLBACK_UNCONFIRMED") }
            DesktopCommunityReturnStatus.UNAVAILABLE
        }
    }
    private fun owned(record: DesktopCommunityProtocolRecord): Boolean {
        if (!record.ownerRoot.equals(root.toString(), ignoreCase = true)) return false
        val match = Regex("^\"([^\"]+)\" --community-auth \"([^\"]+)\" \"%1\"$").matchEntire(record.command.orEmpty()) ?: return false
        val raw = match.groupValues[1]
        val path = try { Path.of(raw).toAbsolutePath().normalize() } catch (_: Exception) { return false }
        try { DesktopExternalActivation.validateCommunityDirectory(home, Path.of(match.groupValues[2])) } catch (_: Exception) { return false }
        return path.startsWith(root) && path.fileName.toString() == "ChatChatBarDesktop.exe"
    }
    override fun release() {
        val record = installed ?: return
        registry.removeIfEqual(record)
        installed = null
    }
}

/** HKCR observes both user and machine handlers. An existing foreign handler is never shadowed. */
private object WindowsCommunityProtocolRegistry : DesktopCommunityProtocolRegistry {
    private const val path = "Software\\Classes\\chatbar"
    private const val owner = "ChatChatBarDesktopOwnerRoot"
    override fun read(): DesktopCommunityProtocolRecord? {
        if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, "chatbar")) return null
        fun value(key: String, name: String): String? = if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, key) &&
            Advapi32Util.registryValueExists(WinReg.HKEY_CLASSES_ROOT, key, name))
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CLASSES_ROOT, key, name) else null
        return DesktopCommunityProtocolRecord(value("chatbar", owner), value("chatbar\\shell\\open\\command", ""))
    }
    override fun write(record: DesktopCommunityProtocolRecord, expected: DesktopCommunityProtocolRecord?) {
        val before = read()
        check(before == expected)
        try {
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, path)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, path, owner, requireNotNull(record.ownerRoot))
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$path\\shell\\open\\command")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$path\\shell\\open\\command", "", requireNotNull(record.command))
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, path, "", "URL:ChatChatBar Community Login")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, path, "URL Protocol", "")
        } catch (error: Exception) {
            if (before == null) runCatching {
                val current = read()
                if (current != null && current.ownerRoot == record.ownerRoot && (current.command == null || current.command == record.command)) removeIfEqual(current)
            }
            throw error
        }
    }
    override fun removeIfEqual(record: DesktopCommunityProtocolRecord) {
        if (read() != record || !Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, path)) return
        listOf("$path\\shell\\open\\command", "$path\\shell\\open", "$path\\shell", path).forEach {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, it)) Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER, it)
        }
    }
}
