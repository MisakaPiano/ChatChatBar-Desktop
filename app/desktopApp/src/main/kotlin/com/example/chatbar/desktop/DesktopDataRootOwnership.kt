package com.example.chatbar.desktop

import com.example.chatbar.data.root.AppDataRootInfrastructure
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

enum class DesktopDataRootOwnershipFailureKind {
    ROOT_MISSING,
    ROOT_UNSAFE,
    LOCK_TARGET_UNSAFE,
    LOCK_OPEN_FAILED,
    LOCK_ACQUIRE_FAILED,
}

sealed interface DesktopDataRootOwnershipResult {
    data class Acquired(
        val ownership: DesktopDataRootOwnership,
    ) : DesktopDataRootOwnershipResult

    data class AlreadyInUse(
        val appDataRoot: Path,
        val lockPath: Path,
        val sameJvm: Boolean,
        val cause: Exception? = null,
    ) : DesktopDataRootOwnershipResult

    data class Failure(
        val kind: DesktopDataRootOwnershipFailureKind,
        val appDataRoot: Path,
        val lockPath: Path,
        val message: String,
        val cause: Exception? = null,
    ) : DesktopDataRootOwnershipResult
}

internal class DesktopDataRootOwnershipException(
    val result: DesktopDataRootOwnershipResult,
) : IllegalStateException(result.startupFailureMessage(), result.startupFailureCause()) {
    init {
        require(result !is DesktopDataRootOwnershipResult.Acquired) {
            "Acquired ownership is not a startup failure"
        }
    }
}

private fun DesktopDataRootOwnershipResult.startupFailureMessage(): String = when (this) {
    is DesktopDataRootOwnershipResult.Acquired -> "Desktop data-root ownership was acquired"
    is DesktopDataRootOwnershipResult.AlreadyInUse ->
        "Desktop data root is already in use: $appDataRoot"
    is DesktopDataRootOwnershipResult.Failure ->
        "Desktop data-root ownership failed ($kind): $message"
}

private fun DesktopDataRootOwnershipResult.startupFailureCause(): Throwable? = when (this) {
    is DesktopDataRootOwnershipResult.Acquired -> null
    is DesktopDataRootOwnershipResult.AlreadyInUse -> cause
    is DesktopDataRootOwnershipResult.Failure -> cause
}

/**
 * selected appDataRoot 的 cooperative single-writer ownership。文件存在不代表 ownership：stale
 * zero-length lock file 是正常状态，实际 ownership 只由进程持续持有的 [FileChannel] 与 [FileLock]
 * 表示。
 *
 * Windows 允许持锁文件被 rename/delete，因此 CCB 自身在 ownership 存续期间绝不能移动或删除
 * lock artifact。未来 snapshot / restore / migration 必须把它视为 reserved infrastructure，而不是
 * user payload。
 *
 * 本类只负责 cross-process ownership，与 C1 的 process-local coordinator 是两个独立边界。
 */
class DesktopDataRootOwnership private constructor(
    val appDataRoot: Path,
    val lockPath: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closeMonitor = Any()
    private var closed = false

    override fun close() {
        synchronized(closeMonitor) {
            if (closed) return
            closed = true

            var failure: Throwable? = null
            try {
                lock.release()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                channel.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            failure?.let { throw it }
        }
    }

    companion object {
        const val LOCK_FILE_NAME = AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME

        fun acquire(
            selectedRoot: DesktopDataRootResolution.Resolved,
        ): DesktopDataRootOwnershipResult {
            val appDataRoot = selectedRoot.appDataRoot.toAbsolutePath().normalize()
            val lockPath = appDataRoot.resolve(LOCK_FILE_NAME).normalize()
            check(lockPath.parent == appDataRoot) { "Desktop data-root lock must be a direct child" }

            when (val rootStatus = inspectRoot(appDataRoot)) {
                RootStatus.Missing -> {
                    if (!selectedRoot.provenance.allowsRootCreation()) {
                        return failure(
                            DesktopDataRootOwnershipFailureKind.ROOT_MISSING,
                            appDataRoot,
                            lockPath,
                            "Selected Desktop data root does not exist",
                        )
                    }
                    try {
                        Files.createDirectories(appDataRoot)
                    } catch (error: Exception) {
                        return failure(
                            DesktopDataRootOwnershipFailureKind.ROOT_UNSAFE,
                            appDataRoot,
                            lockPath,
                            "Default Desktop data root could not be created",
                            error,
                        )
                    }
                    when (val createdStatus = inspectRoot(appDataRoot)) {
                        RootStatus.OrdinaryDirectory -> Unit
                        RootStatus.Missing -> return failure(
                            DesktopDataRootOwnershipFailureKind.ROOT_MISSING,
                            appDataRoot,
                            lockPath,
                            "Created Desktop data root is missing",
                        )

                        is RootStatus.Unsafe -> return failure(
                            DesktopDataRootOwnershipFailureKind.ROOT_UNSAFE,
                            appDataRoot,
                            lockPath,
                            "Created Desktop data root is unsafe: ${createdStatus.reason}",
                            createdStatus.cause,
                        )
                    }
                }

                RootStatus.OrdinaryDirectory -> Unit
                is RootStatus.Unsafe -> return failure(
                    DesktopDataRootOwnershipFailureKind.ROOT_UNSAFE,
                    appDataRoot,
                    lockPath,
                    "Selected Desktop data root is unsafe: ${rootStatus.reason}",
                    rootStatus.cause,
                )
            }

            inspectLockTarget(lockPath)?.let { unsafe ->
                return failure(
                    DesktopDataRootOwnershipFailureKind.LOCK_TARGET_UNSAFE,
                    appDataRoot,
                    lockPath,
                    "Desktop data-root lock target is unsafe: ${unsafe.reason}",
                    unsafe.cause,
                )
            }

            val channel = try {
                FileChannel.open(
                    lockPath,
                    setOf<OpenOption>(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                )
            } catch (error: Exception) {
                return failure(
                    DesktopDataRootOwnershipFailureKind.LOCK_OPEN_FAILED,
                    appDataRoot,
                    lockPath,
                    "Desktop data-root lock file could not be opened",
                    error,
                )
            }

            return try {
                if (channel.size() != 0L) {
                    channel.close()
                    failure(
                        DesktopDataRootOwnershipFailureKind.LOCK_TARGET_UNSAFE,
                        appDataRoot,
                        lockPath,
                        "Desktop data-root lock target must be empty",
                    )
                } else {
                    val lock = try {
                        channel.tryLock()
                    } catch (error: OverlappingFileLockException) {
                        closeAfterFailedAcquisition(channel, error)
                        return DesktopDataRootOwnershipResult.AlreadyInUse(
                            appDataRoot = appDataRoot,
                            lockPath = lockPath,
                            sameJvm = true,
                            cause = error,
                        )
                    }
                    if (lock == null) {
                        val closeFailure = closeAfterFailedAcquisition(channel)
                        if (closeFailure != null) {
                            failure(
                                DesktopDataRootOwnershipFailureKind.LOCK_ACQUIRE_FAILED,
                                appDataRoot,
                                lockPath,
                                "Desktop data-root lock attempt could not be cleaned up",
                                closeFailure,
                            )
                        } else {
                            DesktopDataRootOwnershipResult.AlreadyInUse(
                                appDataRoot = appDataRoot,
                                lockPath = lockPath,
                                sameJvm = false,
                            )
                        }
                    } else {
                        DesktopDataRootOwnership(appDataRoot, lockPath, channel, lock)
                            .let(DesktopDataRootOwnershipResult::Acquired)
                    }
                }
            } catch (error: Exception) {
                closeAfterFailedAcquisition(channel, error)
                failure(
                    DesktopDataRootOwnershipFailureKind.LOCK_ACQUIRE_FAILED,
                    appDataRoot,
                    lockPath,
                    "Desktop data-root lock could not be acquired",
                    error,
                )
            }
        }

        private fun DesktopDataRootProvenance.allowsRootCreation(): Boolean =
            this == DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT ||
                this == DesktopDataRootProvenance.BOOTSTRAP_DEFAULT

        private fun inspectRoot(path: Path): RootStatus {
            val attributes = try {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                return RootStatus.Missing
            } catch (error: Exception) {
                return RootStatus.Unsafe("attributes could not be read", error)
            }
            return when {
                attributes.isSymbolicLink -> RootStatus.Unsafe("symbolic links are not allowed")
                attributes.isOther -> RootStatus.Unsafe("ambiguous filesystem objects are not allowed")
                !attributes.isDirectory -> RootStatus.Unsafe("an ordinary directory is required")
                else -> RootStatus.OrdinaryDirectory
            }
        }

        private fun inspectLockTarget(path: Path): UnsafeTarget? {
            val attributes = try {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                return null
            } catch (error: Exception) {
                return UnsafeTarget("attributes could not be read", error)
            }
            return when {
                attributes.isSymbolicLink -> UnsafeTarget("symbolic links are not allowed")
                attributes.isOther -> UnsafeTarget("ambiguous filesystem objects are not allowed")
                !attributes.isRegularFile -> UnsafeTarget("an ordinary regular file is required")
                attributes.size() != 0L -> UnsafeTarget("an existing lock file must be empty")
                else -> null
            }
        }

        private fun closeAfterFailedAcquisition(
            channel: FileChannel,
            primary: Exception? = null,
        ): Exception? = try {
            channel.close()
            null
        } catch (closeError: Exception) {
            primary?.addSuppressed(closeError)
            closeError
        }

        private fun failure(
            kind: DesktopDataRootOwnershipFailureKind,
            appDataRoot: Path,
            lockPath: Path,
            message: String,
            cause: Exception? = null,
        ) = DesktopDataRootOwnershipResult.Failure(
            kind = kind,
            appDataRoot = appDataRoot,
            lockPath = lockPath,
            message = message,
            cause = cause,
        )

        private sealed interface RootStatus {
            data object Missing : RootStatus
            data object OrdinaryDirectory : RootStatus
            data class Unsafe(val reason: String, val cause: Exception? = null) : RootStatus
        }

        private data class UnsafeTarget(
            val reason: String,
            val cause: Exception? = null,
        )
    }
}
