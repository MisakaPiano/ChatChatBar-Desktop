package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.nio.file.attribute.FileTime
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT
import java.io.IOException

enum class DesktopPortableRootFailureKind {
    APPLICATION_HOME_UNSAFE,
    MARKER_UNSAFE,
    MARKER_UNREADABLE,
    MARKER_CONTENT_INVALID,
    USER_DATA_MISSING,
    USER_DATA_UNSAFE,
}

sealed interface DesktopPortableRootResolution {
    data object NoMarker : DesktopPortableRootResolution

    class PortableCandidate internal constructor(
        val applicationHome: Path,
        val markerPath: Path,
        val appDataRoot: Path,
    ) : DesktopPortableRootResolution

    data class Failure(
        val kind: DesktopPortableRootFailureKind,
        val message: String,
        val cause: Exception? = null,
    ) : DesktopPortableRootResolution
}

/**
 * Portable resolver 只检查 authority 与目录结构，必须保持 zero-write。临时 writable probe
 * 只属于 [DesktopPortableRootActivationValidator]，避免“解析路径”本身改变用户磁盘。
 * marker 与 UserData 始终按 ApplicationHome 的 direct child 解析，不持久化 absolute portable
 * path，因此完整移动 distribution 后仍会绑定到新位置。
 *
 * 公共 JDK 17 NIO 可拒绝 symbolic link、`isOther` 与非普通文件类型，但 Windows provider
 * 未对所有 junction/reparse-point 形态提供统一的跨平台分类；检测到任何歧义时都保守失败。
 */
class DesktopPortableRootResolver {
    fun resolve(applicationHome: Path): DesktopPortableRootResolution {
        if (!applicationHome.isAbsolute) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.APPLICATION_HOME_UNSAFE,
                message = "Desktop ApplicationHome must be absolute",
            )
        }
        val normalizedHome = applicationHome.normalize()
        readOrdinaryDirectoryAttributes(normalizedHome)?.let { failure ->
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.APPLICATION_HOME_UNSAFE,
                message = "Desktop ApplicationHome is unavailable or unsafe: $failure",
            )
        }

        val marker = normalizedHome.resolve(PORTABLE_MARKER_FILE_NAME).normalize()
        check(marker.parent == normalizedHome) { "Portable marker must be a direct child of ApplicationHome" }
        val markerAttributes = try {
            Files.readAttributes(
                marker,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return DesktopPortableRootResolution.NoMarker
        } catch (error: Exception) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.MARKER_UNREADABLE,
                message = "Portable marker could not be inspected",
                cause = error,
            )
        }
        if (markerAttributes.isSymbolicLink || markerAttributes.isOther || !markerAttributes.isRegularFile) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.MARKER_UNSAFE,
                message = "Portable marker must be an ordinary regular file",
            )
        }

        val markerContent = try {
            if (markerAttributes.size() > MAX_MARKER_BYTES) {
                return DesktopPortableRootResolution.Failure(
                    kind = DesktopPortableRootFailureKind.MARKER_CONTENT_INVALID,
                    message = "Portable marker content is invalid",
                )
            }
            Files.newByteChannel(
                marker,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                Channels.newInputStream(channel).use { input ->
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(input.readBytes()))
                        .toString()
                }
            }
        } catch (error: Exception) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.MARKER_UNREADABLE,
                message = "Portable marker could not be read as UTF-8",
                cause = error,
            )
        }
        if (markerContent != PORTABLE_MARKER_TOKEN &&
            markerContent != "$PORTABLE_MARKER_TOKEN\n" &&
            markerContent != "$PORTABLE_MARKER_TOKEN\r\n"
        ) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.MARKER_CONTENT_INVALID,
                message = "Portable marker token is unsupported",
            )
        }

        val userData = normalizedHome.resolve(PORTABLE_USER_DATA_DIRECTORY_NAME).normalize()
        check(userData.parent == normalizedHome) { "Portable UserData must be a direct child of ApplicationHome" }
        val userDataAttributes = try {
            Files.readAttributes(
                userData,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.USER_DATA_MISSING,
                message = "Portable UserData directory is missing",
            )
        } catch (error: Exception) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.USER_DATA_UNSAFE,
                message = "Portable UserData directory could not be inspected",
                cause = error,
            )
        }
        if (userDataAttributes.isSymbolicLink || userDataAttributes.isOther || !userDataAttributes.isDirectory) {
            return DesktopPortableRootResolution.Failure(
                kind = DesktopPortableRootFailureKind.USER_DATA_UNSAFE,
                message = "Portable UserData must be an ordinary directory",
            )
        }

        return DesktopPortableRootResolution.PortableCandidate(
            applicationHome = normalizedHome,
            markerPath = marker,
            appDataRoot = userData,
        )
    }

    private fun readOrdinaryDirectoryAttributes(path: Path): String? {
        val attributes = try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: Exception) {
            return error.message ?: error::class.simpleName ?: "inspection failed"
        }
        return when {
            attributes.isSymbolicLink -> "symbolic links are not allowed"
            attributes.isOther -> "ambiguous filesystem objects are not allowed"
            !attributes.isDirectory -> "an ordinary directory is required"
            else -> null
        }
    }

    companion object {
        const val PORTABLE_MARKER_FILE_NAME = "portable.flag"
        const val PORTABLE_USER_DATA_DIRECTORY_NAME = "UserData"
        const val PORTABLE_MARKER_TOKEN = "CCB_DESKTOP_PORTABLE_V1"
        private const val MAX_MARKER_BYTES = 128L
    }
}

enum class DesktopPortableActivationFailureKind {
    OWNERSHIP_REQUIRED,
    CANDIDATE_CHANGED,
    WRITE_PROBE_FAILED,
    PROBE_CLEANUP_FAILED,
}

sealed interface DesktopPortableActivationResult {
    data class Validated(val appDataRoot: Path) : DesktopPortableActivationResult

    data class Failure(
        val kind: DesktopPortableActivationFailureKind,
        val message: String,
        val retainedProbe: Path? = null,
        val cause: Exception? = null,
    ) : DesktopPortableActivationResult
}

/**
 * 一旦 portable marker 声明了 data-root authority，activation 失败绝不能继续 fallback 到
 * bootstrap/default root；否则应用可能打开另一份空数据，在用户看来等同于数据消失。
 */
class DesktopPortableRootActivationValidator internal constructor(
    private val resolver: DesktopPortableRootResolver,
    private val probeId: () -> String,
    private val writeProbe: (Path) -> Unit,
    private val deleteProbe: (Path) -> Unit,
) {
    constructor() : this(
        resolver = DesktopPortableRootResolver(),
        probeId = { UUID.randomUUID().toString() },
        writeProbe = ::writeAndFlushProbe,
        deleteProbe = Files::delete,
    )

    /** New explicit stage; legacy validate remains compatible until G1B switches Main. */
    internal fun validateOwned(
        candidate: DesktopPortableRootResolution.PortableCandidate,
        expected: List<DesktopPortablePathIdentity>?,
    ): DesktopPortableActivationResult {
        fun owned() = DesktopDataRootOwnership.isHeld(candidate.appDataRoot)
        fun unchanged(): Boolean = try {
            val current = resolver.resolve(candidate.applicationHome)
            current is DesktopPortableRootResolution.PortableCandidate &&
                current.appDataRoot == candidate.appDataRoot && current.markerPath == candidate.markerPath &&
                expected != null && captureDesktopPortableIdentity(current) == expected
        } catch (_: Exception) { false }
        if (!owned()) return DesktopPortableActivationResult.Failure(
            DesktopPortableActivationFailureKind.OWNERSHIP_REQUIRED, "Portable activation requires existing data-root ownership")
        if (!unchanged()) return DesktopPortableActivationResult.Failure(
            DesktopPortableActivationFailureKind.CANDIDATE_CHANGED, "Portable authority changed before activation")
        val result = validate(candidate)
        // Preserve a failed probe's original retained-evidence classification.
        if (result is DesktopPortableActivationResult.Failure) return result
        if (!owned()) return DesktopPortableActivationResult.Failure(
            DesktopPortableActivationFailureKind.OWNERSHIP_REQUIRED, "Portable ownership changed during activation")
        if (!unchanged()) return DesktopPortableActivationResult.Failure(
            DesktopPortableActivationFailureKind.CANDIDATE_CHANGED, "Portable authority changed during activation")
        return result
    }

    fun validate(
        candidate: DesktopPortableRootResolution.PortableCandidate,
    ): DesktopPortableActivationResult {
        val current = resolver.resolve(candidate.applicationHome)
        if (current !is DesktopPortableRootResolution.PortableCandidate ||
            current.markerPath != candidate.markerPath ||
            current.appDataRoot != candidate.appDataRoot
        ) {
            return DesktopPortableActivationResult.Failure(
                kind = DesktopPortableActivationFailureKind.CANDIDATE_CHANGED,
                message = "Portable root changed before activation validation",
            )
        }

        val probe = candidate.appDataRoot.resolve(".portable-write-probe-${probeId()}.tmp").normalize()
        if (probe.parent != candidate.appDataRoot) {
            return DesktopPortableActivationResult.Failure(
                kind = DesktopPortableActivationFailureKind.WRITE_PROBE_FAILED,
                message = "Portable write probe path is unsafe",
            )
        }

        try {
            writeProbe(probe)
        } catch (error: Exception) {
            var cleanupFailed = false
            try {
                Files.deleteIfExists(probe)
            } catch (cleanupError: Exception) {
                cleanupFailed = true
                error.addSuppressed(cleanupError)
            }
            return DesktopPortableActivationResult.Failure(
                kind = DesktopPortableActivationFailureKind.WRITE_PROBE_FAILED,
                message = "Portable UserData write probe failed",
                retainedProbe = probe.takeIf { cleanupFailed },
                cause = error,
            )
        }

        try {
            deleteProbe(probe)
        } catch (error: Exception) {
            return DesktopPortableActivationResult.Failure(
                kind = DesktopPortableActivationFailureKind.PROBE_CLEANUP_FAILED,
                message = "Portable UserData write probe cleanup failed",
                retainedProbe = probe,
                cause = error,
            )
        }
        return DesktopPortableActivationResult.Validated(candidate.appDataRoot)
    }
}

internal data class DesktopPortablePathIdentity(
    val volume: Long, val fileId: List<Byte>, val created: FileTime,
    val markerModified: FileTime?, val markerSize: Long?,
)

/** Metadata-only snapshot; folder mtime deliberately excludes ownership-lock creation. */
internal fun captureDesktopPortableIdentity(
    candidate: DesktopPortableRootResolution.PortableCandidate,
): List<DesktopPortablePathIdentity> = listOf(candidate.applicationHome, candidate.markerPath, candidate.appDataRoot).map { path ->
    if (!System.getProperty("os.name").startsWith("Windows")) throw IOException("Portable identity platform unsupported")
    val marker = path == candidate.markerPath
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attrs.isSymbolicLink || attrs.isOther || (if (marker) !attrs.isRegularFile else !attrs.isDirectory))
        throw IOException("Unsafe portable identity")
    val api = Kernel32.INSTANCE
    val handle = api.CreateFile(path.toString(), WinNT.FILE_READ_ATTRIBUTES,
        WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE, null,
        WinNT.OPEN_EXISTING, WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null)
    if (handle == WinBase.INVALID_HANDLE_VALUE) throw IOException("Portable identity unavailable")
    try {
        val basic = WinBase.FILE_BASIC_INFO()
        if (!api.GetFileInformationByHandleEx(handle, 0, basic.pointer, DWORD(basic.size().toLong())))
            throw IOException("Portable attributes unavailable")
        basic.read()
        if (basic.FileAttributes and 0x400 != 0 || (basic.FileAttributes and 0x10 != 0) != attrs.isDirectory)
            throw IOException("Unsafe portable attributes")
        Memory(24).use { info ->
            if (!api.GetFileInformationByHandleEx(handle, 18, info, DWORD(24))) throw IOException("Portable file identity unavailable")
            DesktopPortablePathIdentity(info.getLong(0), info.getByteArray(8,16).toList(), attrs.creationTime(),
                attrs.lastModifiedTime().takeIf { marker }, attrs.size().takeIf { marker })
        }
    } finally {
        if (!api.CloseHandle(handle)) throw IOException("Portable metadata handle close failed")
    }
}

private fun writeAndFlushProbe(path: Path) {
    FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    ).use { channel ->
        val bytes = ByteBuffer.wrap("CCB Desktop portable write probe".toByteArray(StandardCharsets.UTF_8))
        while (bytes.hasRemaining()) {
            channel.write(bytes)
        }
        channel.force(true)
    }
}
