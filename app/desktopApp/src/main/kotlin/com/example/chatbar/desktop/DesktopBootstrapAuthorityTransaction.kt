package com.example.chatbar.desktop

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface DesktopBootstrapAuthorityCommitResult {
    data class Committed(
        val destination: Path,
        val document: DesktopBootstrapDocument,
        val persistenceWarning: Throwable? = null,
        val closeWarning: Throwable? = null,
    ) : DesktopBootstrapAuthorityCommitResult

    data class Busy(
        val lockPath: Path,
        val sameJvm: Boolean,
        val cause: Throwable? = null,
    ) : DesktopBootstrapAuthorityCommitResult

    data class AuthorityChanged(
        val message: String,
        val currentBootstrap: DesktopBootstrapLoadResult? = null,
        val cause: Throwable? = null,
    ) : DesktopBootstrapAuthorityCommitResult

    data class UnsupportedSourceProvenance(
        val provenance: DesktopDataRootProvenance,
    ) : DesktopBootstrapAuthorityCommitResult

    data class AuthorityLockUnsafe(
        val lockPath: Path,
        val message: String,
        val cause: Throwable? = null,
    ) : DesktopBootstrapAuthorityCommitResult

    data class AuthorityLockOpenFailed(
        val lockPath: Path,
        val cause: Throwable,
    ) : DesktopBootstrapAuthorityCommitResult

    data class AuthorityLockAcquireFailed(
        val lockPath: Path,
        val cause: Throwable,
    ) : DesktopBootstrapAuthorityCommitResult

    data class CommitFailedPreCommit(
        val cause: Throwable,
    ) : DesktopBootstrapAuthorityCommitResult

    data class CommitIndeterminate(
        val message: String,
        val cause: Throwable? = null,
        val observedBootstrap: DesktopBootstrapLoadResult? = null,
    ) : DesktopBootstrapAuthorityCommitResult
}

/**
 * External bootstrap authority writer 的窄 transaction boundary。
 *
 * `<root>/.ccb-desktop.lock` 表示 selected root 的 writable process ownership；
 * `<bootstrap-dir>/ChatChatBarDesktop.bootstrap.lock` 只序列化 cooperating root-authority
 * writers。两者不能互相替代：different-root processes 可以共存，但写同一 bootstrap authority
 * 必须先取得本 transaction lock。未来 Portable toggle writer 也必须参与同一 authority lock。
 *
 * Bootstrap JSON 使用 atomic replace，因此不能直接锁 JSON inode；稳定 sibling lock 在 fresh load、
 * CAS-like source revalidation、save 与 readback 的整个范围内持续持有。
 */
class DesktopBootstrapAuthorityTransaction internal constructor(
    private val storeFactory: (Path) -> DesktopBootstrapSettingsStore,
    private val applicationHomeResolver: () -> DesktopApplicationHomeResult,
    private val portableRootResolver: DesktopPortableRootResolver,
) {
    constructor() : this(
        storeFactory = ::DesktopBootstrapSettingsStore,
        applicationHomeResolver = DesktopApplicationHome::resolve,
        portableRootResolver = DesktopPortableRootResolver(),
    )

    suspend fun commitCustomRoot(
        expectedSource: DesktopDataRootResolution.Resolved,
        destination: Path,
    ): DesktopBootstrapAuthorityCommitResult {
        if (!expectedSource.provenance.isBootstrapControlledMigrationSource()) {
            return DesktopBootstrapAuthorityCommitResult.UnsupportedSourceProvenance(
                expectedSource.provenance,
            )
        }
        if (!destination.isAbsolute) {
            return DesktopBootstrapAuthorityCommitResult.CommitIndeterminate(
                "Bootstrap authority destination must be absolute",
            )
        }

        val bootstrapPath = expectedSource.bootstrapPath.toAbsolutePath().normalize()
        val normalizedDestination = destination.toAbsolutePath().normalize()
        return when (val acquired = acquireLock(bootstrapPath)) {
            is AuthorityLockAcquisition.Busy -> DesktopBootstrapAuthorityCommitResult.Busy(
                lockPath = acquired.lockPath,
                sameJvm = acquired.sameJvm,
                cause = acquired.cause,
            )

            is AuthorityLockAcquisition.Failure -> when (acquired.kind) {
                AuthorityLockFailureKind.UNSAFE ->
                    DesktopBootstrapAuthorityCommitResult.AuthorityLockUnsafe(
                        lockPath = acquired.lockPath,
                        message = acquired.message,
                        cause = acquired.cause,
                    )

                AuthorityLockFailureKind.OPEN_FAILED ->
                    DesktopBootstrapAuthorityCommitResult.AuthorityLockOpenFailed(
                        lockPath = acquired.lockPath,
                        cause = checkNotNull(acquired.cause),
                    )

                AuthorityLockFailureKind.ACQUIRE_FAILED ->
                    DesktopBootstrapAuthorityCommitResult.AuthorityLockAcquireFailed(
                        lockPath = acquired.lockPath,
                        cause = checkNotNull(acquired.cause),
                    )
            }

            is AuthorityLockAcquisition.Acquired -> {
                var result: DesktopBootstrapAuthorityCommitResult
                try {
                    result = withContext(NonCancellable + Dispatchers.IO) {
                        commitWhileLocked(
                            expectedSource = expectedSource.copy(
                                appDataRoot = expectedSource.appDataRoot.toAbsolutePath().normalize(),
                                bootstrapPath = bootstrapPath,
                            ),
                            destination = normalizedDestination,
                            store = storeFactory(bootstrapPath),
                        )
                    }
                } catch (error: Exception) {
                    result = DesktopBootstrapAuthorityCommitResult.CommitIndeterminate(
                        message = "Bootstrap authority transaction failed unexpectedly",
                        cause = error,
                    )
                }
                try {
                    acquired.lock.close()
                } catch (closeError: Throwable) {
                    result = result.withCloseFailure(closeError)
                }
                result
            }
        }
    }

    private suspend fun commitWhileLocked(
        expectedSource: DesktopDataRootResolution.Resolved,
        destination: Path,
        store: DesktopBootstrapSettingsStore,
    ): DesktopBootstrapAuthorityCommitResult {
        val fresh = store.load()
        val freshDocument = validateExpectedSource(fresh, expectedSource)
            ?: return DesktopBootstrapAuthorityCommitResult.AuthorityChanged(
                message = "Bootstrap-controlled source authority changed before commit",
                currentBootstrap = fresh,
            )

        validateNoHigherPriorityPortableAuthority()?.let { return it }

        val nextSelection = DesktopDataRootSelection.custom(destination)
        val saveFailure = try {
            store.save(freshDocument, nextSelection)
            null
        } catch (error: Exception) {
            error
        }

        val readback = try {
            store.load()
        } catch (error: Exception) {
            return DesktopBootstrapAuthorityCommitResult.CommitIndeterminate(
                message = "Bootstrap authority could not be read after save attempt",
                cause = saveFailure?.also { it.addSuppressed(error) } ?: error,
            )
        }

        val committedDocument = readback.documentSelecting(destination)
        if (committedDocument != null) {
            return DesktopBootstrapAuthorityCommitResult.Committed(
                destination = destination,
                document = committedDocument,
                persistenceWarning = saveFailure,
            )
        }

        if (saveFailure != null && validateExpectedSource(readback, expectedSource) != null) {
            return DesktopBootstrapAuthorityCommitResult.CommitFailedPreCommit(saveFailure)
        }

        return DesktopBootstrapAuthorityCommitResult.CommitIndeterminate(
            message = if (saveFailure == null) {
                "Bootstrap authority readback did not contain the committed destination"
            } else {
                "Bootstrap authority state is ambiguous after save failure"
            },
            cause = saveFailure,
            observedBootstrap = readback,
        )
    }

    private fun validateExpectedSource(
        current: DesktopBootstrapLoadResult,
        expectedSource: DesktopDataRootResolution.Resolved,
    ): DesktopBootstrapDocument? {
        val defaultRoot = expectedSource.bootstrapPath.parent
            ?.resolve(DesktopDataDirectory.DIRECTORY_NAME)
            ?.toAbsolutePath()
            ?.normalize()
            ?: return null
        return when (expectedSource.provenance) {
            DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT ->
                (current as? DesktopBootstrapLoadResult.Missing)?.document
                    ?.takeIf { expectedSource.appDataRoot.windowsEquals(defaultRoot) }

            DesktopDataRootProvenance.BOOTSTRAP_DEFAULT ->
                (current as? DesktopBootstrapLoadResult.Loaded)?.document
                    ?.takeIf { document ->
                        document.selection.mode == DesktopDataRootMode.DEFAULT &&
                            expectedSource.appDataRoot.windowsEquals(defaultRoot)
                    }

            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM ->
                (current as? DesktopBootstrapLoadResult.Loaded)?.document
                    ?.takeIf { document ->
                        document.selection.mode == DesktopDataRootMode.CUSTOM &&
                            document.selection.customRoot?.windowsEquals(expectedSource.appDataRoot) == true
                    }

            DesktopDataRootProvenance.PORTABLE,
            DesktopDataRootProvenance.CLI_OVERRIDE,
            -> null
        }
    }

    private fun validateNoHigherPriorityPortableAuthority():
        DesktopBootstrapAuthorityCommitResult.AuthorityChanged? =
        when (val applicationHome = applicationHomeResolver()) {
            is DesktopApplicationHomeResult.Unavailable -> null
            is DesktopApplicationHomeResult.Failure ->
                DesktopBootstrapAuthorityCommitResult.AuthorityChanged(
                    message = "ApplicationHome authority could not be revalidated",
                    cause = applicationHome.cause,
                )

            is DesktopApplicationHomeResult.Available ->
                when (val portable = portableRootResolver.resolve(applicationHome.path)) {
                    DesktopPortableRootResolution.NoMarker -> null
                    is DesktopPortableRootResolution.PortableCandidate ->
                        DesktopBootstrapAuthorityCommitResult.AuthorityChanged(
                            "Portable authority appeared before bootstrap commit",
                        )

                    is DesktopPortableRootResolution.Failure ->
                        DesktopBootstrapAuthorityCommitResult.AuthorityChanged(
                            message = "Portable authority became unsafe before bootstrap commit: " +
                                portable.message,
                            cause = portable.cause,
                        )
                }
        }

    companion object {
        const val AUTHORITY_LOCK_FILE_NAME = "ChatChatBarDesktop.bootstrap.lock"

        internal fun acquireLock(bootstrapPath: Path): AuthorityLockAcquisition {
            val normalizedBootstrap = bootstrapPath.toAbsolutePath().normalize()
            val parent = normalizedBootstrap.parent
                ?: return AuthorityLockAcquisition.Failure(
                    AuthorityLockFailureKind.UNSAFE,
                    normalizedBootstrap,
                    "Bootstrap authority path must have a parent",
                )
            val lockPath = parent.resolve(AUTHORITY_LOCK_FILE_NAME).normalize()
            check(lockPath.parent == parent) {
                "Bootstrap authority lock must be a sibling of the bootstrap JSON"
            }

            inspectOrdinaryDirectory(parent)?.let { issue ->
                return AuthorityLockAcquisition.Failure(
                    AuthorityLockFailureKind.UNSAFE,
                    lockPath,
                    "Bootstrap authority directory is unsafe: ${issue.message}",
                    issue.cause,
                )
            }
            inspectLockTarget(lockPath)?.let { issue ->
                return AuthorityLockAcquisition.Failure(
                    AuthorityLockFailureKind.UNSAFE,
                    lockPath,
                    "Bootstrap authority lock target is unsafe: ${issue.message}",
                    issue.cause,
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
                return AuthorityLockAcquisition.Failure(
                    AuthorityLockFailureKind.OPEN_FAILED,
                    lockPath,
                    "Bootstrap authority lock could not be opened",
                    error,
                )
            }

            return try {
                if (channel.size() != 0L) {
                    channel.close()
                    AuthorityLockAcquisition.Failure(
                        AuthorityLockFailureKind.UNSAFE,
                        lockPath,
                        "Bootstrap authority lock must be empty",
                    )
                } else {
                    val lock = try {
                        channel.tryLock()
                    } catch (error: OverlappingFileLockException) {
                        closeAfterFailedLock(channel, error)
                        return AuthorityLockAcquisition.Busy(
                            lockPath = lockPath,
                            sameJvm = true,
                            cause = error,
                        )
                    }
                    if (lock == null) {
                        val closeFailure = closeAfterFailedLock(channel)
                        if (closeFailure == null) {
                            AuthorityLockAcquisition.Busy(lockPath, sameJvm = false)
                        } else {
                            AuthorityLockAcquisition.Failure(
                                AuthorityLockFailureKind.ACQUIRE_FAILED,
                                lockPath,
                                "Bootstrap authority lock contention cleanup failed",
                                closeFailure,
                            )
                        }
                    } else {
                        AuthorityLockAcquisition.Acquired(
                            BootstrapAuthorityLock(lockPath, channel, lock),
                        )
                    }
                }
            } catch (error: Exception) {
                closeAfterFailedLock(channel, error)
                AuthorityLockAcquisition.Failure(
                    AuthorityLockFailureKind.ACQUIRE_FAILED,
                    lockPath,
                    "Bootstrap authority lock could not be acquired",
                    error,
                )
            }
        }

        private fun inspectOrdinaryDirectory(path: Path): LockTargetIssue? {
            val attributes = try {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                return LockTargetIssue("directory is missing")
            } catch (error: Exception) {
                return LockTargetIssue("directory attributes could not be read", error)
            }
            return when {
                attributes.isSymbolicLink -> LockTargetIssue("symbolic links are not allowed")
                attributes.isOther -> LockTargetIssue("ambiguous filesystem objects are not allowed")
                !attributes.isDirectory -> LockTargetIssue("an ordinary directory is required")
                else -> null
            }
        }

        private fun inspectLockTarget(path: Path): LockTargetIssue? {
            val attributes = try {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                return null
            } catch (error: Exception) {
                return LockTargetIssue("attributes could not be read", error)
            }
            return when {
                attributes.isSymbolicLink -> LockTargetIssue("symbolic links are not allowed")
                attributes.isOther -> LockTargetIssue("ambiguous filesystem objects are not allowed")
                !attributes.isRegularFile -> LockTargetIssue("an ordinary regular file is required")
                attributes.size() != 0L -> LockTargetIssue("an existing lock file must be empty")
                else -> null
            }
        }

        private fun closeAfterFailedLock(
            channel: FileChannel,
            primary: Throwable? = null,
        ): Throwable? = try {
            channel.close()
            null
        } catch (closeError: Throwable) {
            primary?.addSuppressed(closeError)
            closeError
        }
    }
}

internal enum class AuthorityLockFailureKind {
    UNSAFE,
    OPEN_FAILED,
    ACQUIRE_FAILED,
}

internal sealed interface AuthorityLockAcquisition {
    data class Acquired(val lock: BootstrapAuthorityLock) : AuthorityLockAcquisition

    data class Busy(
        val lockPath: Path,
        val sameJvm: Boolean,
        val cause: Throwable? = null,
    ) : AuthorityLockAcquisition

    data class Failure(
        val kind: AuthorityLockFailureKind,
        val lockPath: Path,
        val message: String,
        val cause: Throwable? = null,
    ) : AuthorityLockAcquisition
}

internal class BootstrapAuthorityLock(
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
}

private data class LockTargetIssue(
    val message: String,
    val cause: Throwable? = null,
)

private fun DesktopBootstrapLoadResult.documentSelecting(
    destination: Path,
): DesktopBootstrapDocument? = (this as? DesktopBootstrapLoadResult.Loaded)?.document
    ?.takeIf { document ->
        document.selection.mode == DesktopDataRootMode.CUSTOM &&
            document.selection.customRoot?.windowsEquals(destination) == true
    }

private fun DesktopBootstrapAuthorityCommitResult.withCloseFailure(
    closeError: Throwable,
): DesktopBootstrapAuthorityCommitResult = when (this) {
    is DesktopBootstrapAuthorityCommitResult.Committed -> copy(closeWarning = closeError)
    is DesktopBootstrapAuthorityCommitResult.CommitFailedPreCommit -> {
        cause.addSuppressed(closeError)
        this
    }

    is DesktopBootstrapAuthorityCommitResult.CommitIndeterminate -> {
        cause?.addSuppressed(closeError)
        copy(cause = cause ?: closeError)
    }

    is DesktopBootstrapAuthorityCommitResult.AuthorityChanged -> {
        cause?.addSuppressed(closeError)
        copy(cause = cause ?: closeError)
    }

    else -> DesktopBootstrapAuthorityCommitResult.CommitIndeterminate(
        message = "Bootstrap authority transaction lock could not close",
        cause = closeError,
    )
}
