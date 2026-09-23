package com.example.chatbar.desktop

import com.example.chatbar.data.root.AppDataRootInfrastructure
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

enum class DesktopMigrationDestinationFailureKind {
    UNSUPPORTED_SOURCE_PROVENANCE,
    PATH_INVALID,
    NETWORK_PATH_UNSUPPORTED,
    SOURCE_UNSAFE,
    DESTINATION_RELATION_UNSAFE,
    APPLICATION_HOME_UNSAFE,
    DESTINATION_PARENT_MISSING,
    DESTINATION_PARENT_UNSAFE,
    DESTINATION_CREATION_FAILED,
    DESTINATION_UNSAFE,
    DESTINATION_ALREADY_IN_USE,
    DESTINATION_OWNERSHIP_FAILED,
    DESTINATION_NOT_EMPTY,
    DESTINATION_INSPECTION_FAILED,
}

sealed interface DesktopMigrationDestinationResult {
    data class Prepared(
        val destination: DesktopPreparedMigrationDestination,
    ) : DesktopMigrationDestinationResult

    data class Failure(
        val kind: DesktopMigrationDestinationFailureKind,
        val sourceRoot: Path,
        val destinationRoot: Path,
        val message: String,
        val createdDestination: Boolean = false,
        val cause: Throwable? = null,
        val ownershipResult: DesktopDataRootOwnershipResult? = null,
    ) : DesktopMigrationDestinationResult
}

/**
 * 已完成 migration-only validation 且持续持有 destination root ownership 的 handle。
 * D1 不转移 root authority；caller 结束 future migration transaction 前必须保持本 handle 存活。
 */
class DesktopPreparedMigrationDestination internal constructor(
    val sourceRoot: Path,
    val destinationRoot: Path,
    val createdByPreparation: Boolean,
    private val ownership: DesktopDataRootOwnership,
) : AutoCloseable {
    private val closeMonitor = Any()
    private var closed = false

    internal fun isOpen(): Boolean = synchronized(closeMonitor) { !closed }

    override fun close() {
        synchronized(closeMonitor) {
            if (closed) return
            try {
                ownership.close()
            } finally {
                closed = true
            }
        }
    }
}

/**
 * 只负责 future migration destination 的创建前验证、单目录创建、ownership 与持锁后的空目录复核。
 * 它不会复制 payload、清理目录或改变 bootstrap authority；normal startup 的 root creation policy
 * 仍由 [DesktopDataRootOwnership] 独立决定。
 *
 * public JDK 17 可以拒绝 observable symbolic link / `isOther`，但不能证明识别所有 Windows
 * junction/reparse alias。能检测到 identity 歧义时 fail safe，不宣称提供完整 canonical identity。
 */
class DesktopMigrationDestinationPreparer internal constructor(
    private val applicationHomeResolver: () -> DesktopApplicationHomeResult,
    private val ownershipAcquire: (DesktopDataRootResolution.Resolved) -> DesktopDataRootOwnershipResult,
) {
    constructor() : this(
        applicationHomeResolver = DesktopApplicationHome::resolve,
        ownershipAcquire = DesktopDataRootOwnership::acquire,
    )

    fun prepare(
        source: DesktopDataRootResolution.Resolved,
        requestedDestination: Path,
    ): DesktopMigrationDestinationResult {
        val sourceRoot = source.appDataRoot.toAbsolutePath().normalize()
        val destinationRoot = requestedDestination.toAbsolutePath().normalize()

        if (!source.provenance.isBootstrapControlledMigrationSource()) {
            return failure(
                DesktopMigrationDestinationFailureKind.UNSUPPORTED_SOURCE_PROVENANCE,
                sourceRoot,
                destinationRoot,
                "Migration v1 supports only bootstrap-controlled source roots",
            )
        }
        if (!source.appDataRoot.isAbsolute || !requestedDestination.isAbsolute) {
            return failure(
                DesktopMigrationDestinationFailureKind.PATH_INVALID,
                sourceRoot,
                destinationRoot,
                "Migration source and destination must be absolute paths",
            )
        }
        if (sourceRoot.isUncPath() || destinationRoot.isUncPath()) {
            return failure(
                DesktopMigrationDestinationFailureKind.NETWORK_PATH_UNSUPPORTED,
                sourceRoot,
                destinationRoot,
                "Migration v1 does not support UNC or network roots",
            )
        }
        inspectOrdinaryDirectory(sourceRoot, allowMissing = false)?.let { issue ->
            return failure(
                DesktopMigrationDestinationFailureKind.SOURCE_UNSAFE,
                sourceRoot,
                destinationRoot,
                "Migration source root is unsafe: ${issue.reason}",
                cause = issue.cause,
            )
        }
        validateRelations(sourceRoot, destinationRoot)?.let { message ->
            return failure(
                DesktopMigrationDestinationFailureKind.DESTINATION_RELATION_UNSAFE,
                sourceRoot,
                destinationRoot,
                message,
            )
        }
        validateApplicationHome(destinationRoot)?.let { issue ->
            return failure(
                DesktopMigrationDestinationFailureKind.APPLICATION_HOME_UNSAFE,
                sourceRoot,
                destinationRoot,
                issue.reason,
                cause = issue.cause,
            )
        }

        var createdDestination = false
        when (val destinationStatus = inspectOrdinaryDirectory(destinationRoot, allowMissing = true)) {
            null -> Unit
            PathIssue.Missing -> {
                val parent = destinationRoot.parent ?: return failure(
                    DesktopMigrationDestinationFailureKind.DESTINATION_PARENT_MISSING,
                    sourceRoot,
                    destinationRoot,
                    "Migration destination must have an existing parent",
                )
                when (val parentStatus = inspectOrdinaryDirectory(parent, allowMissing = true)) {
                    null -> Unit
                    PathIssue.Missing -> return failure(
                        DesktopMigrationDestinationFailureKind.DESTINATION_PARENT_MISSING,
                        sourceRoot,
                        destinationRoot,
                        "Migration destination parent does not exist",
                    )

                    is PathIssue.Unsafe -> return failure(
                        DesktopMigrationDestinationFailureKind.DESTINATION_PARENT_UNSAFE,
                        sourceRoot,
                        destinationRoot,
                        "Migration destination parent is unsafe: ${parentStatus.reason}",
                        cause = parentStatus.cause,
                    )
                }
                try {
                    Files.createDirectory(destinationRoot)
                    createdDestination = true
                } catch (error: Exception) {
                    return failure(
                        DesktopMigrationDestinationFailureKind.DESTINATION_CREATION_FAILED,
                        sourceRoot,
                        destinationRoot,
                        "Migration destination directory could not be created",
                        cause = error,
                    )
                }
            }

            is PathIssue.Unsafe -> return failure(
                DesktopMigrationDestinationFailureKind.DESTINATION_UNSAFE,
                sourceRoot,
                destinationRoot,
                "Migration destination is unsafe: ${destinationStatus.reason}",
                cause = destinationStatus.cause,
            )
        }

        val selectedDestination = DesktopDataRootResolution.Resolved(
            appDataRoot = destinationRoot,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = source.bootstrapPath,
        )
        val ownershipResult = ownershipAcquire(selectedDestination)
        val ownership = when (ownershipResult) {
            is DesktopDataRootOwnershipResult.Acquired -> ownershipResult.ownership
            is DesktopDataRootOwnershipResult.AlreadyInUse -> return failure(
                DesktopMigrationDestinationFailureKind.DESTINATION_ALREADY_IN_USE,
                sourceRoot,
                destinationRoot,
                "Migration destination is already owned by another Desktop process",
                createdDestination,
                ownershipResult.cause,
                ownershipResult,
            )

            is DesktopDataRootOwnershipResult.Failure -> return failure(
                DesktopMigrationDestinationFailureKind.DESTINATION_OWNERSHIP_FAILED,
                sourceRoot,
                destinationRoot,
                "Migration destination ownership failed: ${ownershipResult.message}",
                createdDestination,
                ownershipResult.cause,
                ownershipResult,
            )
        }

        val validationFailure = inspectOrdinaryDirectory(
            destinationRoot,
            allowMissing = false,
        )?.let { issue ->
            failure(
                    DesktopMigrationDestinationFailureKind.DESTINATION_UNSAFE,
                    sourceRoot,
                    destinationRoot,
                    "Migration destination changed after ownership acquisition: ${issue.reason}",
                    createdDestination,
                    issue.cause,
                )
        } ?: validateRelations(sourceRoot, destinationRoot)?.let { message ->
            failure(
                    DesktopMigrationDestinationFailureKind.DESTINATION_RELATION_UNSAFE,
                    sourceRoot,
                    destinationRoot,
                    message,
                    createdDestination,
                )
        } ?: validateEmptyWhileOwned(destinationRoot)?.let { issue ->
            failure(
                    issue.kind,
                    sourceRoot,
                    destinationRoot,
                    issue.message,
                    createdDestination,
                    issue.cause,
                )
        }
        if (validationFailure != null) {
            return try {
                ownership.close()
                validationFailure
            } catch (closeError: Throwable) {
                val primary = validationFailure.cause
                    ?: IllegalStateException(validationFailure.message)
                primary.addSuppressed(closeError)
                validationFailure.copy(cause = primary)
            }
        }

        return DesktopMigrationDestinationResult.Prepared(
            DesktopPreparedMigrationDestination(
                sourceRoot = sourceRoot,
                destinationRoot = destinationRoot,
                createdByPreparation = createdDestination,
                ownership = ownership,
            ),
        )
    }

    private fun validateRelations(sourceRoot: Path, destinationRoot: Path): String? {
        if (sourceRoot.windowsEquals(destinationRoot)) {
            return "Migration source and destination must be different roots"
        }
        if (destinationRoot.isLexicallyInside(sourceRoot)) {
            return "Migration destination cannot be inside the source root"
        }
        if (sourceRoot.isLexicallyInside(destinationRoot)) {
            return "Migration source cannot be inside the destination root"
        }
        if (Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(destinationRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            val sameFile = try {
                Files.isSameFile(sourceRoot, destinationRoot)
            } catch (error: Exception) {
                return "Migration source/destination filesystem identity could not be verified: ${error.message}"
            }
            if (sameFile) return "Migration source and destination resolve to the same filesystem object"
        }
        return null
    }

    private fun validateApplicationHome(destinationRoot: Path): PathIssue.Unsafe? =
        when (val applicationHome = applicationHomeResolver()) {
            is DesktopApplicationHomeResult.Unavailable -> null
            is DesktopApplicationHomeResult.Failure -> PathIssue.Unsafe(
                "ApplicationHome could not be resolved safely for migration destination validation",
                applicationHome.cause,
            )

            is DesktopApplicationHomeResult.Available -> {
                val home = applicationHome.path.toAbsolutePath().normalize()
                if (destinationRoot.windowsEquals(home) || destinationRoot.isLexicallyInside(home)) {
                    PathIssue.Unsafe(
                        "Migration destination cannot be ApplicationHome or its descendant",
                    )
                } else {
                    null
                }
            }
        }

    private fun validateEmptyWhileOwned(destinationRoot: Path): EmptyIssue? = try {
        Files.newDirectoryStream(destinationRoot).use { entries ->
            val unexpected = entries.firstOrNull { entry ->
                val relative = destinationRoot.relativize(entry)
                !AppDataRootInfrastructure.isRootOwnershipLock(relative)
            }
            unexpected?.let {
                EmptyIssue(
                    DesktopMigrationDestinationFailureKind.DESTINATION_NOT_EMPTY,
                    "Migration destination is not empty: ${it.fileName}",
                )
            }
        }
    } catch (error: Exception) {
        EmptyIssue(
            DesktopMigrationDestinationFailureKind.DESTINATION_INSPECTION_FAILED,
            "Migration destination contents could not be inspected",
            error,
        )
    }

    private fun failure(
        kind: DesktopMigrationDestinationFailureKind,
        sourceRoot: Path,
        destinationRoot: Path,
        message: String,
        createdDestination: Boolean = false,
        cause: Throwable? = null,
        ownershipResult: DesktopDataRootOwnershipResult? = null,
    ) = DesktopMigrationDestinationResult.Failure(
        kind = kind,
        sourceRoot = sourceRoot,
        destinationRoot = destinationRoot,
        message = message,
        createdDestination = createdDestination,
        cause = cause,
        ownershipResult = ownershipResult,
    )

    private data class EmptyIssue(
        val kind: DesktopMigrationDestinationFailureKind,
        val message: String,
        val cause: Throwable? = null,
    )
}

internal fun DesktopDataRootProvenance.isBootstrapControlledMigrationSource(): Boolean =
    this == DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT ||
        this == DesktopDataRootProvenance.BOOTSTRAP_DEFAULT ||
        this == DesktopDataRootProvenance.BOOTSTRAP_CUSTOM

internal fun Path.windowsEquals(other: Path): Boolean {
    val left = toAbsolutePath().normalize().toString()
    val right = other.toAbsolutePath().normalize().toString()
    return left.equals(right, ignoreCase = isWindowsPlatform())
}

internal fun Path.isLexicallyInside(parent: Path): Boolean {
    val child = toAbsolutePath().normalize()
    val normalizedParent = parent.toAbsolutePath().normalize()
    if (child.root?.toString()?.equals(
            normalizedParent.root?.toString(),
            ignoreCase = isWindowsPlatform(),
        ) != true || child.nameCount <= normalizedParent.nameCount
    ) {
        return false
    }
    return (0 until normalizedParent.nameCount).all { index ->
        child.getName(index).toString().equals(
            normalizedParent.getName(index).toString(),
            ignoreCase = isWindowsPlatform(),
        )
    }
}

internal fun Path.isUncPath(): Boolean {
    if (!isWindowsPlatform()) return false
    val text = toAbsolutePath().normalize().toString()
    val rootText = root?.toString().orEmpty()
    return text.startsWith("\\\\") || rootText.startsWith("\\\\")
}

private fun isWindowsPlatform(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private sealed interface PathIssue {
    val reason: String
    val cause: Throwable?

    data object Missing : PathIssue {
        override val reason = "directory is missing"
        override val cause: Throwable? = null
    }

    data class Unsafe(
        override val reason: String,
        override val cause: Throwable? = null,
    ) : PathIssue
}

private fun inspectOrdinaryDirectory(path: Path, allowMissing: Boolean): PathIssue? {
    val attributes = try {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    } catch (_: NoSuchFileException) {
        return if (allowMissing) PathIssue.Missing else PathIssue.Unsafe("directory is missing")
    } catch (error: Exception) {
        return PathIssue.Unsafe("attributes could not be read", error)
    }
    return when {
        attributes.isSymbolicLink -> PathIssue.Unsafe("symbolic links are not allowed")
        attributes.isOther -> PathIssue.Unsafe("ambiguous filesystem objects are not allowed")
        !attributes.isDirectory -> PathIssue.Unsafe("an ordinary directory is required")
        else -> null
    }
}
