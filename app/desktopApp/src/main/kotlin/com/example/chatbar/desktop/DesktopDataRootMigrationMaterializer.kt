package com.example.chatbar.desktop

import com.example.chatbar.data.root.AppDataRootInfrastructure
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class DesktopMigrationWarningKind {
    SOURCE_MIGRATION_WORKSPACE,
    INVALID_COMPLETED_SNAPSHOT,
    INTERNAL_SNAPSHOT_WORKSPACE,
    RESTORE_RECOVERY_WORKSPACE,
    PRUNE_WORKSPACE,
    MIGRATION_WORKSPACE,
    UNKNOWN_BACKUP_ENTRY,
}

data class DesktopMigrationWarning(
    val kind: DesktopMigrationWarningKind,
    val path: Path,
    val message: String,
)

enum class DesktopMigrationMaterializationFailureKind {
    PRECONDITION_FAILED,
    SOURCE_UNSAFE,
    SOURCE_ENUMERATION_FAILED,
    SOURCE_ENTRY_UNSAFE,
    BACKUPS_ROOT_UNSAFE,
    STAGING_FAILED,
    COPY_FAILED,
    STAGING_VALIDATION_FAILED,
    DESTINATION_CHANGED,
    INSTALL_FAILED,
    INSTALLED_VALIDATION_FAILED,
    ROLLBACK_INCOMPLETE,
    WORKSPACE_CLEANUP_FAILED,
}

data class DesktopMigrationMaterializationSummary(
    val activeFileCount: Int,
    val activeDirectoryCount: Int,
    val totalBytes: Long,
    val migratedSnapshotNames: List<String>,
)

sealed interface DesktopMigrationMaterializationResult {
    data class Materialized(
        val destinationRoot: Path,
        val summary: DesktopMigrationMaterializationSummary,
        val warnings: List<DesktopMigrationWarning>,
        val retainedWorkspace: Path? = null,
        val cleanupWarning: String? = null,
    ) : DesktopMigrationMaterializationResult

    data class Failure(
        val kind: DesktopMigrationMaterializationFailureKind,
        val message: String,
        val cause: Throwable? = null,
        val warnings: List<DesktopMigrationWarning> = emptyList(),
        val retainedWorkspace: Path? = null,
        val cleanupWarning: String? = null,
        val rollbackIncomplete: Boolean = false,
        val installedEntries: List<Path> = emptyList(),
        val rollbackFailures: List<Throwable> = emptyList(),
    ) : DesktopMigrationMaterializationResult
}

internal data class DesktopMigrationMaterializationHooks(
    val afterStagedEntry: (Path) -> Unit = {},
    val beforeStagingValidation: suspend (Path) -> Unit = {},
    val beforeInstallMove: (Int, Path, Path) -> Unit = { _, _, _ -> },
    val moveInstalledEntry: (Path, Path) -> Unit = ::moveMigrationEntryWithoutReplace,
    val beforeInstalledValidation: (Path) -> Unit = {},
    val moveRollbackEntry: (Path, Path) -> Unit = ::moveMigrationEntryWithoutReplace,
    val cleanupWorkspace: (Path) -> Unit = ::deleteMigrationTree,
)

/**
 * 将已静止的 source root 原始字节 materialize 到 D1 已持有 ownership 的 destination。
 * Caller 必须先通过 process-local coordinator 保证 source quiescence；本组件不申请 exclusive
 * maintenance，也不提交 bootstrap authority，更不关闭 [DesktopPreparedMigrationDestination]。
 *
 * copy/staging 允许 cancellation；第一个 install move 开始后，install、installed validation 与
 * rollback 位于 NonCancellable transaction boundary，避免取消留下无人处理的半安装目录。
 * `Materialized` 只是 destination materialization commit，不是 root-authority commit；source 始终
 * 只读并保留。Rollback 只按本 transaction 的 installed-entry ledger 操作，绝不扫描删除未知路径。
 */
class DesktopDataRootMigrationMaterializer internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val workspaceIdSupplier: () -> String,
    private val hooks: DesktopMigrationMaterializationHooks,
) {
    constructor() : this(
        ioDispatcher = Dispatchers.IO,
        workspaceIdSupplier = { UUID.randomUUID().toString() },
        hooks = DesktopMigrationMaterializationHooks(),
    )

    suspend fun materialize(
        sourceRoot: Path,
        preparedDestination: DesktopPreparedMigrationDestination,
    ): DesktopMigrationMaterializationResult = withContext(ioDispatcher) {
        val source = sourceRoot.toAbsolutePath().normalize()
        val expectedSource = preparedDestination.sourceRoot.toAbsolutePath().normalize()
        val destination = preparedDestination.destinationRoot.toAbsolutePath().normalize()
        if (!preparedDestination.isOpen()) {
            return@withContext failure(
                DesktopMigrationMaterializationFailureKind.PRECONDITION_FAILED,
                "Prepared migration destination ownership is no longer held",
            )
        }
        if (!source.windowsEquals(expectedSource)) {
            return@withContext failure(
                DesktopMigrationMaterializationFailureKind.PRECONDITION_FAILED,
                "Prepared destination belongs to a different migration source",
            )
        }
        inspectOrdinaryDirectory(source)?.let { issue ->
            return@withContext failure(
                DesktopMigrationMaterializationFailureKind.SOURCE_UNSAFE,
                "Migration source root is unsafe: $issue",
            )
        }
        revalidateEmptyDestination(destination)?.let { error ->
            return@withContext failure(
                DesktopMigrationMaterializationFailureKind.DESTINATION_CHANGED,
                error.message ?: "Migration destination changed after preparation",
                error,
            )
        }

        val workspaceName = migrationWorkspaceName(workspaceIdSupplier())
        val workspace = destination.resolve(workspaceName)
        val warnings = mutableListOf<DesktopMigrationWarning>()
        try {
            Files.createDirectory(workspace)
        } catch (error: Throwable) {
            return@withContext failure(
                DesktopMigrationMaterializationFailureKind.STAGING_FAILED,
                "Migration workspace could not be created",
                error,
                warnings,
                workspace.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) },
            )
        }
        try {
            createMigrationWorkspaceMarker(workspace)
        } catch (error: Throwable) {
            return@withContext preMutationFailure(
                workspace,
                DesktopMigrationMaterializationFailureKind.STAGING_FAILED,
                "Migration workspace marker could not be created",
                error,
                warnings,
            )
        }

        val staged = try {
            stageMigration(source, workspace, warnings)
        } catch (cancelled: CancellationException) {
            cleanupBeforeMutation(workspace, cancelled)
            throw cancelled
        } catch (error: MaterializationStageException) {
            return@withContext preMutationFailure(workspace, error.kind, error.message.orEmpty(), error, warnings)
        } catch (error: Throwable) {
            return@withContext preMutationFailure(
                workspace,
                DesktopMigrationMaterializationFailureKind.STAGING_FAILED,
                "Migration staging failed",
                error,
                warnings,
            )
        }

        withContext(NonCancellable + ioDispatcher) {
            installValidateAndFinalize(destination, workspace, staged, warnings)
        }
    }

    private suspend fun stageMigration(
        sourceRoot: Path,
        workspace: Path,
        warnings: MutableList<DesktopMigrationWarning>,
    ): StagedMigration {
        val activeRoot = Files.createDirectory(workspace.resolve(ACTIVE_DIRECTORY_NAME))
        val activeEntries = copyActivePayload(sourceRoot, activeRoot, warnings)
        val migratedSnapshots = copyValidSnapshots(sourceRoot, workspace, warnings)
        val manifest = MigrationManifest(
            formatVersion = FORMAT_VERSION,
            activeEntries = activeEntries.sortedBy(MigrationEntry::path),
            migratedSnapshotNames = migratedSnapshots.sorted(),
        )
        val manifestText = encodeManifest(manifest)
        Files.writeString(
            workspace.resolve(MANIFEST_FILE_NAME),
            manifestText,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        hooks.beforeStagingValidation(workspace)
        try {
            validateWorkspace(workspace, manifest, manifestText)
        } catch (error: MaterializationStageException) {
            throw error
        } catch (error: Throwable) {
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.STAGING_VALIDATION_FAILED,
                "Migration staging validation failed",
                error,
            )
        }
        return StagedMigration(manifest)
    }

    private suspend fun copyActivePayload(
        sourceRoot: Path,
        stagedActiveRoot: Path,
        warnings: MutableList<DesktopMigrationWarning>,
    ): List<MigrationEntry> {
        val entries = mutableListOf<MigrationEntry>()
        val coroutineContext = currentCoroutineContext()
        try {
            Files.walkFileTree(sourceRoot, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    coroutineContext.ensureActive()
                    if (directory == sourceRoot) {
                        requireSafeSourceEntry(directory, attributes, expectDirectory = true)
                        return FileVisitResult.CONTINUE
                    }
                    val relative = sourceRoot.relativize(directory).normalize()
                    if (AppDataRootInfrastructure.isRootOwnershipLock(relative)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (relative.nameCount == 1 && AppDataRootInfrastructure.isBackupsSubtree(relative)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (relative.nameCount == 1 &&
                        attributes.isDirectory &&
                        isConfirmedMigrationWorkspace(directory)
                    ) {
                        warnings += DesktopMigrationWarning(
                            DesktopMigrationWarningKind.SOURCE_MIGRATION_WORKSPACE,
                            directory,
                            "Source migration workspace was retained and excluded",
                        )
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    requireSafeSourceEntry(directory, attributes, expectDirectory = true)
                    val target = resolveContained(stagedActiveRoot, relative)
                    Files.createDirectory(target)
                    entries += MigrationEntry.Directory(portablePath(relative))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    coroutineContext.ensureActive()
                    val relative = sourceRoot.relativize(file).normalize()
                    if (AppDataRootInfrastructure.isRootOwnershipLock(relative) ||
                        (relative.nameCount == 1 && AppDataRootInfrastructure.isBackupsSubtree(relative))
                    ) {
                        return FileVisitResult.CONTINUE
                    }
                    requireSafeSourceEntry(file, attributes, expectDirectory = false)
                    val target = resolveContained(stagedActiveRoot, relative)
                    val copied = try {
                        copyAndHash(file, target, coroutineContext)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        throw MaterializationStageException(
                            DesktopMigrationMaterializationFailureKind.COPY_FAILED,
                            "Migration source file could not be copied: $file",
                            error,
                        )
                    }
                    entries += MigrationEntry.File(portablePath(relative), copied.size, copied.sha256)
                    hooks.afterStagedEntry(target)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    throw MaterializationStageException(
                        DesktopMigrationMaterializationFailureKind.SOURCE_ENUMERATION_FAILED,
                        "Migration source entry could not be read: $file",
                        error,
                    )
                }
            })
        } catch (error: MaterializationStageException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.SOURCE_ENTRY_UNSAFE,
                "Migration source payload contains an unsafe or unreadable entry",
                error,
            )
        }
        return entries
    }

    private suspend fun copyValidSnapshots(
        sourceRoot: Path,
        workspace: Path,
        warnings: MutableList<DesktopMigrationWarning>,
    ): List<String> {
        val coroutineContext = currentCoroutineContext()
        val sourceBackups = sourceRoot.resolve(BACKUPS_DIRECTORY_NAME)
        if (!Files.exists(sourceBackups, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        inspectOrdinaryDirectory(sourceBackups)?.let { issue ->
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.BACKUPS_ROOT_UNSAFE,
                "Source backups root is unsafe: $issue",
            )
        }

        val snapshotService = AppDataSnapshotService(sourceRoot)
        val listedSnapshots = try {
            snapshotService.listSnapshots().associateBy { it.directory.toAbsolutePath().normalize() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.BACKUPS_ROOT_UNSAFE,
                "Source backups root could not be inspected",
                error,
            )
        }
        val selected = mutableListOf<Path>()
        val children = try {
            Files.list(sourceBackups).use { stream -> stream.sorted().toList() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.BACKUPS_ROOT_UNSAFE,
                "Source backups root could not be enumerated",
                error,
            )
        }
        for (entry in children) {
            coroutineContext.ensureActive()
            val name = entry.fileName.toString()
            val warningKind = backupWorkspaceWarning(entry)
            if (warningKind != null) {
                warnings += DesktopMigrationWarning(warningKind, entry, "Backup workspace was retained and excluded")
                continue
            }
            val listed = listedSnapshots[entry.toAbsolutePath().normalize()]
            if (listed != null) {
                if (listed.validation.valid) {
                    selected.add(entry)
                } else {
                    warnings += DesktopMigrationWarning(
                        DesktopMigrationWarningKind.INVALID_COMPLETED_SNAPSHOT,
                        entry,
                        "Invalid completed snapshot was retained: ${listed.validation.issue}: ${listed.validation.reason}",
                    )
                }
            } else {
                warnings += DesktopMigrationWarning(
                    DesktopMigrationWarningKind.UNKNOWN_BACKUP_ENTRY,
                    entry,
                    "Unknown backup entry was retained and excluded",
                )
            }
        }
        if (selected.isEmpty()) return emptyList()

        val stagedBackups = Files.createDirectory(workspace.resolve(BACKUPS_DIRECTORY_NAME))
        for (snapshot in selected.sortedBy { it.fileName.toString() }) {
            val target = stagedBackups.resolve(snapshot.fileName.toString())
            try {
                copyDirectoryTree(snapshot, target, coroutineContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw MaterializationStageException(
                    DesktopMigrationMaterializationFailureKind.COPY_FAILED,
                    "Validated snapshot could not be copied: ${snapshot.fileName}",
                    error,
                )
            }
            hooks.afterStagedEntry(target)
            val validation = AppDataSnapshotService(workspace).validateSnapshot(target)
            if (!validation.valid) {
                throw MaterializationStageException(
                    DesktopMigrationMaterializationFailureKind.STAGING_VALIDATION_FAILED,
                    "Staged snapshot failed validation: ${snapshot.fileName}: ${validation.issue}: ${validation.reason}",
                )
            }
        }
        return selected.map { it.fileName.toString() }
    }

    private fun installValidateAndFinalize(
        destinationRoot: Path,
        workspace: Path,
        staged: StagedMigration,
        warnings: List<DesktopMigrationWarning>,
    ): DesktopMigrationMaterializationResult {
        val installed = mutableListOf<Path>()
        var mutationStarted = false
        try {
            val stagedActive = workspace.resolve(ACTIVE_DIRECTORY_NAME)
            val installSources = directChildren(stagedActive).toMutableList()
            val stagedBackups = workspace.resolve(BACKUPS_DIRECTORY_NAME)
            if (Files.exists(stagedBackups, LinkOption.NOFOLLOW_LINKS)) installSources.add(stagedBackups)
            installSources.sortedBy { it.fileName.toString() }.forEachIndexed { index, source ->
                val target = destinationRoot.resolve(source.fileName.toString())
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Migration install target already exists: $target")
                }
                mutationStarted = true
                hooks.beforeInstallMove(index, source, target)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Migration install target appeared before move: $target")
                }
                try {
                    hooks.moveInstalledEntry(source, target)
                    installed.add(target)
                } catch (error: Throwable) {
                    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) &&
                        Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        installed.add(target)
                    }
                    throw error
                }
            }
            validateInstalledWithHook(destinationRoot, workspace, staged.manifest)

            val summary = staged.manifest.summary()
            val cleanupError = cleanupWorkspace(workspace)
            return DesktopMigrationMaterializationResult.Materialized(
                destinationRoot = destinationRoot,
                summary = summary,
                warnings = warnings.toList(),
                retainedWorkspace = workspace.takeIf {
                    cleanupError != null && Files.exists(it, LinkOption.NOFOLLOW_LINKS)
                },
                cleanupWarning = cleanupError?.let {
                    "Materialization committed, but workspace cleanup failed: ${it.message ?: it::class.simpleName}"
                },
            )
        } catch (primary: Throwable) {
            if (!mutationStarted) {
                return preMutationFailure(
                    workspace,
                    DesktopMigrationMaterializationFailureKind.INSTALL_FAILED,
                    "Migration installation failed before any destination entry was installed",
                    primary,
                    warnings,
                )
            }
            val rollbackFailures = rollbackInstalled(workspace, installed)
            val remaining = installed.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            if (rollbackFailures.isNotEmpty() || remaining.isNotEmpty()) {
                return DesktopMigrationMaterializationResult.Failure(
                    kind = DesktopMigrationMaterializationFailureKind.ROLLBACK_INCOMPLETE,
                    message = "Migration materialization failed and destination rollback was incomplete",
                    cause = primary,
                    warnings = warnings.toList(),
                    retainedWorkspace = workspace.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) },
                    rollbackIncomplete = true,
                    installedEntries = remaining,
                    rollbackFailures = rollbackFailures,
                )
            }
            val cleanupError = cleanupWorkspace(workspace)
            return DesktopMigrationMaterializationResult.Failure(
                kind = if (primary is InstalledValidationException) {
                    DesktopMigrationMaterializationFailureKind.INSTALLED_VALIDATION_FAILED
                } else {
                    DesktopMigrationMaterializationFailureKind.INSTALL_FAILED
                },
                message = if (primary is InstalledValidationException) {
                    "Installed migration payload failed validation and was rolled back"
                } else {
                    "Migration installation failed and was rolled back"
                },
                cause = primary,
                warnings = warnings.toList(),
                retainedWorkspace = workspace.takeIf {
                    cleanupError != null && Files.exists(it, LinkOption.NOFOLLOW_LINKS)
                },
                cleanupWarning = cleanupError?.let {
                    "Rollback completed, but workspace cleanup failed: ${it.message ?: it::class.simpleName}"
                },
            )
        }
    }

    private fun validateWorkspace(
        workspace: Path,
        manifest: MigrationManifest,
        manifestText: String,
    ) {
        val expectedChildren = buildSet {
            add(WORKSPACE_MARKER_FILE_NAME)
            add(ACTIVE_DIRECTORY_NAME)
            add(MANIFEST_FILE_NAME)
            if (manifest.migratedSnapshotNames.isNotEmpty()) add(BACKUPS_DIRECTORY_NAME)
        }
        val actualChildren = directChildren(workspace).map { it.fileName.toString() }.toSet()
        if (actualChildren != expectedChildren) {
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.STAGING_VALIDATION_FAILED,
                "Migration workspace contains missing or unexpected entries",
            )
        }
        if (!hasValidMigrationWorkspaceMarker(workspace)) {
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.STAGING_VALIDATION_FAILED,
                "Migration workspace marker is missing, unsafe, or invalid",
            )
        }
        val actualManifest = Files.readString(workspace.resolve(MANIFEST_FILE_NAME))
        if (actualManifest != manifestText) {
            throw MaterializationStageException(
                DesktopMigrationMaterializationFailureKind.STAGING_VALIDATION_FAILED,
                "Migration manifest changed after it was written",
            )
        }
        validateTree(workspace.resolve(ACTIVE_DIRECTORY_NAME), manifest.activeEntries)
        validateSnapshots(workspace, manifest.migratedSnapshotNames)
    }

    private fun validateInstalled(
        destinationRoot: Path,
        workspace: Path,
        manifest: MigrationManifest,
    ) {
        try {
            val expectedTopLevel = manifest.activeEntries.mapNotNull { entry ->
                entry.path.substringBefore('/').takeIf(String::isNotEmpty)
            }.toMutableSet()
            if (manifest.migratedSnapshotNames.isNotEmpty()) expectedTopLevel += BACKUPS_DIRECTORY_NAME
            val actualTopLevel = directChildren(destinationRoot)
                .filterNot { it == workspace }
                .filterNot { entry ->
                    AppDataRootInfrastructure.isRootOwnershipLock(destinationRoot.relativize(entry))
                }
                .map { it.fileName.toString() }
                .toSet()
            if (actualTopLevel != expectedTopLevel) {
                throw IOException("Installed destination contains missing or unexpected top-level entries")
            }
            validateTree(
                destinationRoot,
                manifest.activeEntries,
                ignoredRootNames = setOf(BACKUPS_DIRECTORY_NAME, workspace.fileName.toString()),
            )
            validateSnapshots(destinationRoot, manifest.migratedSnapshotNames)
        } catch (error: Throwable) {
            if (error is InstalledValidationException) throw error
            throw InstalledValidationException("Installed destination validation failed", error)
        }
    }

    private fun validateInstalledWithHook(
        destinationRoot: Path,
        workspace: Path,
        manifest: MigrationManifest,
    ) {
        try {
            hooks.beforeInstalledValidation(destinationRoot)
            validateInstalled(destinationRoot, workspace, manifest)
        } catch (error: InstalledValidationException) {
            throw error
        } catch (error: Throwable) {
            throw InstalledValidationException("Installed destination validation failed", error)
        }
    }

    private fun validateTree(
        root: Path,
        expectedEntries: List<MigrationEntry>,
        ignoredRootNames: Set<String> = emptySet(),
    ) {
        val expected = expectedEntries.associateBy(MigrationEntry::path)
        val seen = mutableSetOf<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(directory).normalize()
                if (relative.nameCount == 1 && relative.fileName.toString() in ignoredRootNames) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (relative.nameCount == 1 &&
                    AppDataRootInfrastructure.isRootOwnershipLock(relative)
                ) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (directory != root) {
                    requireSafeValidationEntry(directory, attributes, expectDirectory = true)
                    val path = portablePath(relative)
                    if (expected[path] !is MigrationEntry.Directory || !seen.add(path)) {
                        throw IOException("Unexpected migration directory: $path")
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).normalize()
                if (relative.nameCount == 1 &&
                    AppDataRootInfrastructure.isRootOwnershipLock(relative)
                ) {
                    return FileVisitResult.CONTINUE
                }
                requireSafeValidationEntry(file, attributes, expectDirectory = false)
                val path = portablePath(relative)
                val expectedFile = expected[path] as? MigrationEntry.File
                    ?: throw IOException("Unexpected migration file: $path")
                if (!seen.add(path)) throw IOException("Duplicate migration entry: $path")
                if (Files.size(file) != expectedFile.size || sha256(file) != expectedFile.sha256) {
                    throw IOException("Migration file size/hash mismatch: $path")
                }
                return FileVisitResult.CONTINUE
            }
        })
        if (seen != expected.keys) {
            throw IOException("Migration tree is missing recorded entries: ${expected.keys - seen}")
        }
    }

    private fun validateSnapshots(root: Path, expectedNames: List<String>) {
        val backups = root.resolve(BACKUPS_DIRECTORY_NAME)
        if (expectedNames.isEmpty()) {
            if (Files.exists(backups, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Unexpected backups directory in migration materialization")
            }
            return
        }
        inspectOrdinaryDirectory(backups)?.let { throw IOException("Migrated backups root is unsafe: $it") }
        val actualNames = directChildren(backups).map { it.fileName.toString() }
        if (actualNames != expectedNames.sorted()) {
            throw IOException("Migrated snapshot set differs from manifest")
        }
        val service = AppDataSnapshotService(root)
        expectedNames.forEach { name ->
            val validation = service.validateSnapshot(backups.resolve(name))
            if (!validation.valid) {
                throw IOException("Migrated snapshot failed validation: $name: ${validation.issue}: ${validation.reason}")
            }
        }
    }

    private fun rollbackInstalled(workspace: Path, installed: List<Path>): List<Throwable> {
        val rollbackRoot = workspace.resolve(ROLLBACK_DIRECTORY_NAME)
        val failures = mutableListOf<Throwable>()
        try {
            if (!Files.exists(rollbackRoot, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(rollbackRoot)
        } catch (error: Throwable) {
            failures += error
            return failures
        }
        installed.asReversed().forEach { target ->
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@forEach
            try {
                hooks.moveRollbackEntry(target, rollbackRoot.resolve(target.fileName.toString()))
            } catch (error: Throwable) {
                failures += error
            }
        }
        return failures
    }

    private fun preMutationFailure(
        workspace: Path,
        kind: DesktopMigrationMaterializationFailureKind,
        message: String,
        cause: Throwable,
        warnings: List<DesktopMigrationWarning>,
    ): DesktopMigrationMaterializationResult.Failure {
        val cleanupError = cleanupWorkspace(workspace)
        return DesktopMigrationMaterializationResult.Failure(
            kind = if (cleanupError != null && kind == DesktopMigrationMaterializationFailureKind.STAGING_FAILED) {
                DesktopMigrationMaterializationFailureKind.WORKSPACE_CLEANUP_FAILED
            } else {
                kind
            },
            message = message,
            cause = cause,
            warnings = warnings.toList(),
            retainedWorkspace = workspace.takeIf {
                cleanupError != null && Files.exists(it, LinkOption.NOFOLLOW_LINKS)
            },
            cleanupWarning = cleanupError?.let {
                "Pre-install workspace cleanup failed: ${it.message ?: it::class.simpleName}"
            },
        )
    }

    private fun cleanupBeforeMutation(workspace: Path, primary: Throwable) {
        val cleanupError = cleanupWorkspace(workspace)
        if (cleanupError != null) primary.addSuppressed(cleanupError)
    }

    private fun cleanupWorkspace(workspace: Path): Throwable? = try {
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) hooks.cleanupWorkspace(workspace)
        null
    } catch (error: Throwable) {
        error
    }

    private fun revalidateEmptyDestination(destinationRoot: Path): IOException? = try {
        inspectOrdinaryDirectory(destinationRoot)?.let {
            return IOException("Migration destination is unsafe: $it")
        }
        val unexpected = directChildren(destinationRoot).firstOrNull { entry ->
            !AppDataRootInfrastructure.isRootOwnershipLock(destinationRoot.relativize(entry))
        }
        unexpected?.let { IOException("Migration destination contains an unexpected entry: ${it.fileName}") }
    } catch (error: IOException) {
        error
    } catch (error: Throwable) {
        IOException("Migration destination could not be revalidated", error)
    }

    private fun copyAndHash(source: Path, target: Path, coroutineContext: CoroutineContext): CopiedFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(source).buffered().use { input ->
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    size += count
                }
                output.flush()
            }
        }
        return CopiedFile(size, digest.hex())
    }

    private fun copyDirectoryTree(source: Path, target: Path, coroutineContext: CoroutineContext) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                coroutineContext.ensureActive()
                requireSafeSourceEntry(directory, attributes, expectDirectory = true)
                val destination = if (directory == source) {
                    target
                } else {
                    resolveContained(target, source.relativize(directory).normalize())
                }
                Files.createDirectory(destination)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                coroutineContext.ensureActive()
                requireSafeSourceEntry(file, attributes, expectDirectory = false)
                val relative = source.relativize(file).normalize()
                copyAndHash(file, resolveContained(target, relative), coroutineContext)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun encodeManifest(manifest: MigrationManifest): String = Json { prettyPrint = true }.encodeToString(
        buildJsonObject {
            put("formatVersion", manifest.formatVersion)
            put("activeEntries", buildJsonArray {
                manifest.activeEntries.forEach { entry ->
                    add(buildJsonObject {
                        put("path", entry.path)
                        when (entry) {
                            is MigrationEntry.Directory -> put("type", "DIRECTORY")
                            is MigrationEntry.File -> {
                                put("type", "FILE")
                                put("size", entry.size)
                                put("sha256", entry.sha256)
                            }
                        }
                    })
                }
            })
            put("migratedSnapshotNames", buildJsonArray {
                manifest.migratedSnapshotNames.forEach { add(JsonPrimitive(it)) }
            })
        },
    )

    private fun MigrationManifest.summary() = DesktopMigrationMaterializationSummary(
        activeFileCount = activeEntries.count { it is MigrationEntry.File },
        activeDirectoryCount = activeEntries.count { it is MigrationEntry.Directory },
        totalBytes = activeEntries.filterIsInstance<MigrationEntry.File>().sumOf(MigrationEntry.File::size),
        migratedSnapshotNames = migratedSnapshotNames,
    )

    private fun failure(
        kind: DesktopMigrationMaterializationFailureKind,
        message: String,
        cause: Throwable? = null,
        warnings: List<DesktopMigrationWarning> = emptyList(),
        retainedWorkspace: Path? = null,
    ) = DesktopMigrationMaterializationResult.Failure(
        kind = kind,
        message = message,
        cause = cause,
        warnings = warnings.toList(),
        retainedWorkspace = retainedWorkspace,
    )

    private data class StagedMigration(
        val manifest: MigrationManifest,
    )

    private data class MigrationManifest(
        val formatVersion: Int,
        val activeEntries: List<MigrationEntry>,
        val migratedSnapshotNames: List<String>,
    )

    private sealed interface MigrationEntry {
        val path: String

        data class Directory(override val path: String) : MigrationEntry

        data class File(
            override val path: String,
            val size: Long,
            val sha256: String,
        ) : MigrationEntry
    }

    private data class CopiedFile(val size: Long, val sha256: String)

    private class MaterializationStageException(
        val kind: DesktopMigrationMaterializationFailureKind,
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    private class InstalledValidationException(message: String, cause: Throwable) : IOException(message, cause)

    companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST_FILE_NAME = "migration-manifest.json"
        const val ACTIVE_DIRECTORY_NAME = "active"
        const val WORKSPACE_MARKER_FILE_NAME = ".ccb-desktop-migration-workspace"
        const val WORKSPACE_MARKER_TOKEN = "CCB_DESKTOP_MIGRATION_WORKSPACE_V1"
        private const val BACKUPS_DIRECTORY_NAME = AppDataRootInfrastructure.BACKUPS_DIRECTORY_NAME
        private const val ROLLBACK_DIRECTORY_NAME = "rollback"
    }
}

private fun inspectOrdinaryDirectory(path: Path): String? = try {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    when {
        attributes.isSymbolicLink -> "symbolic links are not allowed"
        attributes.isOther -> "ambiguous filesystem objects are not allowed"
        !attributes.isDirectory -> "an ordinary directory is required"
        else -> null
    }
} catch (error: Throwable) {
    "attributes could not be read: ${error.message ?: error::class.simpleName}"
}

private fun requireSafeSourceEntry(path: Path, attributes: BasicFileAttributes, expectDirectory: Boolean) {
    if (Files.isSymbolicLink(path) || attributes.isSymbolicLink || attributes.isOther) {
        throw IOException("Migration source contains a link or unsupported entry: $path")
    }
    if (expectDirectory && !attributes.isDirectory) throw IOException("Expected source directory: $path")
    if (!expectDirectory && !attributes.isRegularFile) throw IOException("Expected source regular file: $path")
}

private fun requireSafeValidationEntry(path: Path, attributes: BasicFileAttributes, expectDirectory: Boolean) {
    if (Files.isSymbolicLink(path) || attributes.isSymbolicLink || attributes.isOther) {
        throw IOException("Migration tree contains a link or unsupported entry: $path")
    }
    if (expectDirectory && !attributes.isDirectory) throw IOException("Expected migration directory: $path")
    if (!expectDirectory && !attributes.isRegularFile) throw IOException("Expected migration regular file: $path")
}

private fun resolveContained(root: Path, relative: Path): Path {
    if (relative.isAbsolute || relative.nameCount == 0 || relative.any { it.toString() in setOf(".", "..") }) {
        throw IOException("Unsafe migration relative path: $relative")
    }
    val normalizedRoot = root.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(relative).normalize()
    if (!resolved.startsWith(normalizedRoot)) throw IOException("Migration path escapes its root: $relative")
    return resolved
}

private fun portablePath(path: Path): String =
    path.iterator().asSequence().joinToString("/") { it.toString() }

private fun backupWorkspaceWarning(entry: Path): DesktopMigrationWarningKind? {
    val name = entry.fileName.toString()
    return when {
        name.startsWith(".snapshot-") && name.endsWith(".tmp") ->
            DesktopMigrationWarningKind.INTERNAL_SNAPSHOT_WORKSPACE
        name.startsWith(".restore-") && name.endsWith(".tmp") ->
            DesktopMigrationWarningKind.RESTORE_RECOVERY_WORKSPACE
        name.startsWith(".prune-") && name.endsWith(".tmp") ->
            DesktopMigrationWarningKind.PRUNE_WORKSPACE
        isMigrationWorkspaceName(name) -> if (isConfirmedMigrationWorkspace(entry)) {
            DesktopMigrationWarningKind.MIGRATION_WORKSPACE
        } else {
            DesktopMigrationWarningKind.UNKNOWN_BACKUP_ENTRY
        }
        else -> null
    }
}

private fun migrationWorkspaceName(id: String): String {
    val safeId = id.filter { it.isLetterOrDigit() || it == '-' }.take(64)
    require(safeId.isNotBlank()) { "Migration workspace ID must contain a letter, digit, or hyphen" }
    return ".migration-$safeId.tmp"
}

private fun isMigrationWorkspaceName(name: String): Boolean =
    name.startsWith(".migration-") && name.endsWith(".tmp") &&
        name.removePrefix(".migration-").removeSuffix(".tmp").isNotBlank()

private val migrationWorkspaceMarkerBytes =
    DesktopDataRootMigrationMaterializer.WORKSPACE_MARKER_TOKEN.toByteArray(StandardCharsets.UTF_8)

private fun createMigrationWorkspaceMarker(workspace: Path) {
    Files.write(
        workspace.resolve(DesktopDataRootMigrationMaterializer.WORKSPACE_MARKER_FILE_NAME),
        migrationWorkspaceMarkerBytes,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
}

/**
 * 只有受控目录名与专用 provenance marker 同时有效，才是 CCB migration workspace。
 * 名称相似但缺少/损坏 marker 的目录仍是 ordinary source payload，绝不能被静默丢弃。
 */
private fun isConfirmedMigrationWorkspace(directory: Path): Boolean =
    isMigrationWorkspaceName(directory.fileName.toString()) && hasValidMigrationWorkspaceMarker(directory)

private fun hasValidMigrationWorkspaceMarker(workspace: Path): Boolean = runCatching {
    val marker = workspace.resolve(DesktopDataRootMigrationMaterializer.WORKSPACE_MARKER_FILE_NAME)
    val attributes = Files.readAttributes(marker, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (Files.isSymbolicLink(marker) || attributes.isSymbolicLink || attributes.isOther || !attributes.isRegularFile) {
        return@runCatching false
    }
    if (attributes.size() != migrationWorkspaceMarkerBytes.size.toLong()) return@runCatching false
    val actual = ByteArray(migrationWorkspaceMarkerBytes.size)
    Files.newByteChannel(marker, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
        val buffer = ByteBuffer.wrap(actual)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return@runCatching false
        }
    }
    actual.contentEquals(migrationWorkspaceMarkerBytes)
}.getOrDefault(false)

private fun directChildren(directory: Path): List<Path> = Files.list(directory).use { stream ->
    stream.sorted(compareBy { it.fileName.toString() }).toList()
}

private fun moveMigrationEntryWithoutReplace(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

private fun deleteMigrationTree(root: Path) {
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (Files.isSymbolicLink(file) || attributes.isSymbolicLink || !attributes.isRegularFile) {
                throw IOException("Unsafe migration workspace file: $file")
            }
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink || attributes.isOther || !attributes.isDirectory) {
                throw IOException("Unsafe migration workspace directory: $directory")
            }
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    })
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.hex()
}

private fun MessageDigest.hex(): String = digest().joinToString("") { byte -> "%02x".format(byte) }
