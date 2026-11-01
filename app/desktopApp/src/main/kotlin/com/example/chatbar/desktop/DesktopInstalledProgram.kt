package com.example.chatbar.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption

/** Only installed programs participate; portable/application data authority is unchanged. */
internal object DesktopInstalledProgram {
    private var lease: WinNT.HANDLE? = null

    fun acquire() {
        val home = System.getProperty(DesktopApplicationHome.PROPERTY_NAME)?.let(Path::of) ?: return
        val manifest = home.resolve("install-manifest.json")
        if (!Files.isRegularFile(manifest)) return
        check(System.getProperty("os.name").startsWith("Windows"))
        val handle = Kernel32.INSTANCE.CreateFile(manifest.toString(), WinNT.GENERIC_READ,
            WinNT.FILE_SHARE_READ, null, WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null)
        check(handle != WinBase.INVALID_HANDLE_VALUE) { "INSTALLED_PROGRAM_LEASE_UNAVAILABLE" }
        lease = handle
    }

    fun ready(environment: Map<String, String> = System.getenv()) {
        val raw = environment["CCB_DESKTOP_READY_PATH"] ?: return
        val nonce = environment["CCB_DESKTOP_READY_NONCE"] ?: error("INSTALL_READY_NONCE_MISSING")
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        val home = Path.of(System.getProperty(DesktopApplicationHome.PROPERTY_NAME)).toAbsolutePath().normalize()
        val target = Path.of(raw).toAbsolutePath().normalize()
        check(home.parent.fileName.toString() == "versions" &&
            target == home.parent.parent.resolve("receipts/$nonce.ready")) { "INSTALL_READY_PATH_REFUSED" }
        check(lease != null) { "INSTALL_PROGRAM_LEASE_REQUIRED" }
        val identity = DesktopBuildIdentity.load()
        val temporary = target.resolveSibling("$nonce.pending")
        Files.writeString(temporary, "$nonce\n${identity.version}\n${identity.commit}\n${ProcessHandle.current().pid()}\n",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
    }

    fun release() { lease?.let { Kernel32.INSTANCE.CloseHandle(it) }; lease = null }

    /** This acknowledges queue delivery only, never import completion or a new-version activation. */
    fun activationQueued(environment: Map<String, String> = System.getenv()) {
        val raw = environment["CCB_DESKTOP_ACTIVATION_PATH"] ?: return
        val nonce = requireNotNull(environment["CCB_DESKTOP_ACTIVATION_NONCE"])
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        val home = Path.of(System.getProperty(DesktopApplicationHome.PROPERTY_NAME)).toAbsolutePath().normalize()
        val target = Path.of(raw).toAbsolutePath().normalize()
        check(lease != null && home.parent.fileName.toString() == "versions" &&
            target == home.parent.parent.resolve("receipts/$nonce.activation"))
        val temporary = target.resolveSibling("$nonce.activation.pending")
        Files.writeString(temporary, "$nonce\nQUEUED\n", StandardOpenOption.CREATE_NEW)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
    }

    fun forwarded(environment: Map<String, String> = System.getenv()) {
        val raw = environment["CCB_DESKTOP_FORWARD_PATH"] ?: return
        val nonce = requireNotNull(environment["CCB_DESKTOP_FORWARD_NONCE"])
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        val home = Path.of(System.getProperty(DesktopApplicationHome.PROPERTY_NAME)).toAbsolutePath().normalize()
        val target = Path.of(raw).toAbsolutePath().normalize()
        check(lease != null && home.parent.fileName.toString() == "versions" &&
            target == home.parent.parent.resolve("receipts/$nonce.forwarded"))
        val temporary = target.resolveSibling("$nonce.forwarded.pending")
        Files.writeString(temporary, "$nonce\nFORWARDED\n", StandardOpenOption.CREATE_NEW)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
    }
}
