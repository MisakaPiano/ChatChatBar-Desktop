package com.example.chatbar.data.snapshot

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class AppDataSnapshot(
    val name: String,
    val directory: Path,
    val createdAt: Instant?,
    val validation: SnapshotValidation,
)

data class SnapshotRestoreResult(
    val restoredSnapshot: AppDataSnapshot,
    val preRestoreSnapshot: AppDataSnapshot,
    val retainedWorkspace: Path? = null,
    val cleanupWarning: String? = null,
)

class SnapshotRestoreException(
    message: String,
    val recoveryDirectory: Path? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

data class SnapshotValidation(
    val valid: Boolean,
    val issue: SnapshotValidationIssue? = null,
    val reason: String? = null,
) {
    companion object {
        val VALID = SnapshotValidation(valid = true)
    }
}

enum class SnapshotValidationIssue {
    INVALID_SNAPSHOT_DIRECTORY,
    MISSING_MANIFEST,
    MALFORMED_MANIFEST,
    UNSUPPORTED_FORMAT_VERSION,
    UNSAFE_ENTRY_PATH,
    RESERVED_ENTRY_PATH,
    MISSING_FILE,
    NOT_REGULAR_FILE,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    SYMBOLIC_LINK,
    UNRECORDED_FILE,
    IO_ERROR,
}

/**
 * Creates self-contained snapshots beneath [appDataRoot]/backups without changing source data.
 *
 * The caller must keep the app-data root quiescent for the entire [createSnapshot] call. This
 * service deliberately does not add a global lock around independent repositories or live writers.
 */
class AppDataSnapshotService(
    appDataRoot: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val idSupplier: () -> String = { UUID.randomUUID().toString() },
) {
    private val appDataRoot = appDataRoot.toAbsolutePath().normalize()
    private val backupsRoot = this.appDataRoot.resolve(BACKUPS_DIRECTORY_NAME)
    private val json = Json { prettyPrint = true }

    fun createSnapshot(): AppDataSnapshot {
        prepareSnapshotRoot()
        val createdAt = clock.instant()
        val snapshotName = completedSnapshotName(createdAt)
        val completedDirectory = backupsRoot.resolve(snapshotName)
        if (Files.exists(completedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Snapshot already exists: $snapshotName")
        }

        val stagingDirectory = backupsRoot.resolve(".snapshot-${UUID.randomUUID()}.tmp")
        Files.createDirectory(stagingDirectory)
        try {
            val payloadRoot = stagingDirectory.resolve(PAYLOAD_DIRECTORY_NAME)
            Files.createDirectory(payloadRoot)
            val entries = copySourceFiles(payloadRoot).sortedBy(SnapshotFileEntry::path)
            writeManifest(stagingDirectory, SnapshotManifest(FORMAT_VERSION, createdAt, entries))

            val validation = validateSnapshot(stagingDirectory)
            if (!validation.valid) {
                throw IOException("Staged snapshot validation failed: ${validation.reason}")
            }

            installCompletedSnapshot(stagingDirectory, completedDirectory)
            return AppDataSnapshot(snapshotName, completedDirectory, createdAt, validation)
        } finally {
            if (Files.exists(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(stagingDirectory)
            }
        }
    }

    fun listSnapshots(): List<AppDataSnapshot> {
        if (!Files.isDirectory(backupsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()

        return Files.list(backupsRoot).use { paths ->
            paths
                .filter { path ->
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(path) &&
                        !isInternalWorkspace(path.fileName.toString())
                }
                .map { directory ->
                    val manifest = readManifestOrNull(directory.resolve(MANIFEST_FILE_NAME))
                    AppDataSnapshot(
                        name = directory.fileName.toString(),
                        directory = directory,
                        createdAt = manifest?.createdAt,
                        validation = validateSnapshot(directory),
                    )
                }
                .sorted(
                    compareByDescending<AppDataSnapshot> { it.createdAt ?: Instant.MIN }
                        .thenByDescending(AppDataSnapshot::name),
                )
                .toList()
        }
    }

    /**
     * Restores a completed snapshot owned by this app-data root.
     *
     * The caller must keep the app-data root quiescent for the entire call. A completed safety
     * snapshot of the current state is created before active data is changed.
     */
    fun restoreSnapshot(snapshotDirectory: Path): SnapshotRestoreResult {
        val selectedSnapshot = requireRestorableCompletedSnapshot(snapshotDirectory)
        val manifest = readManifest(selectedSnapshot.directory.resolve(MANIFEST_FILE_NAME))
        val preRestoreSnapshot = createSnapshot()
        val workspace = backupsRoot.resolve(".restore-${UUID.randomUUID()}.tmp")
        val staging = workspace.resolve(RESTORE_STAGING_DIRECTORY_NAME)
        val recovery = workspace.resolve(RESTORE_RECOVERY_DIRECTORY_NAME)
        val recoveredEntryNames = mutableListOf<String>()
        val installedEntries = mutableListOf<Path>()
        var activeMutationStarted = false

        try {
            Files.createDirectory(workspace)
            Files.createDirectory(staging)
            Files.createDirectory(recovery)
            materializeSnapshotPayload(selectedSnapshot.directory.resolve(PAYLOAD_DIRECTORY_NAME), staging)
            requireValidPayload(staging, manifest, "Staged restore payload is invalid")

            val selectedRevalidation = validateSnapshot(selectedSnapshot.directory)
            if (!selectedRevalidation.valid) {
                throw SnapshotRestoreException(
                    "Selected snapshot changed during restore staging: ${selectedRevalidation.reason}",
                )
            }
            val revalidatedManifest = readManifest(selectedSnapshot.directory.resolve(MANIFEST_FILE_NAME))
            if (revalidatedManifest != manifest) {
                throw SnapshotRestoreException("Selected snapshot manifest changed during restore staging")
            }

            val currentEntries = activeTopLevelEntries()
            currentEntries.forEach { entry ->
                moveWithoutReplace(entry, recovery.resolve(entry.fileName))
                recoveredEntryNames.add(entry.fileName.toString())
                activeMutationStarted = true
            }

            val stagedEntries = directChildren(staging)
            stagedEntries.forEach { entry ->
                val installed = appDataRoot.resolve(entry.fileName)
                moveWithoutReplace(entry, installed)
                installedEntries.add(installed)
                activeMutationStarted = true
            }

            requireValidPayload(
                payloadRoot = appDataRoot,
                manifest = manifest,
                message = "Installed restore payload is invalid",
                excludedDirectory = backupsRoot,
            )
        } catch (error: Throwable) {
            if (activeMutationStarted) {
                rollbackActivePayload(
                    workspace = workspace,
                    recovery = recovery,
                    recoveredEntryNames = recoveredEntryNames,
                    installedEntries = installedEntries,
                    originalError = error,
                )
            } else {
                cleanupWorkspaceAfterPreMutationFailure(workspace, error)
            }
            throw error
        }

        // Commit point: the installed active payload has passed validation. Cleanup failures after
        // this line must never enter the pre-commit rollback path.
        val cleanupFailure = try {
            deleteTree(workspace)
            null
        } catch (error: Throwable) {
            error
        }
        return SnapshotRestoreResult(
            restoredSnapshot = selectedSnapshot,
            preRestoreSnapshot = preRestoreSnapshot,
            retainedWorkspace = workspace.takeIf {
                cleanupFailure != null && Files.exists(it, LinkOption.NOFOLLOW_LINKS)
            },
            cleanupWarning = cleanupFailure?.let { error ->
                "Restore committed, but transaction workspace cleanup was incomplete " +
                    "(${error::class.simpleName ?: "cleanup error"})"
            },
        )
    }

    fun validateSnapshot(snapshotDirectory: Path): SnapshotValidation {
        return try {
            validateSnapshotInternal(snapshotDirectory)
        } catch (error: Exception) {
            invalid(SnapshotValidationIssue.IO_ERROR, error.message ?: "Unable to validate snapshot")
        }
    }

    private fun validateSnapshotInternal(snapshotDirectory: Path): SnapshotValidation {
        val root = snapshotDirectory.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(root)) {
            return invalid(SnapshotValidationIssue.SYMBOLIC_LINK, "Snapshot directory is a symbolic link")
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return invalid(SnapshotValidationIssue.INVALID_SNAPSHOT_DIRECTORY, "Snapshot directory is missing")
        }

        val manifestPath = root.resolve(MANIFEST_FILE_NAME)
        if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return invalid(SnapshotValidationIssue.MISSING_MANIFEST, "Snapshot manifest is missing")
        }
        if (Files.isSymbolicLink(manifestPath) || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return invalid(SnapshotValidationIssue.NOT_REGULAR_FILE, "Snapshot manifest is not a regular file")
        }

        val manifest = try {
            readManifest(manifestPath)
        } catch (error: Exception) {
            return invalid(SnapshotValidationIssue.MALFORMED_MANIFEST, error.message ?: "Malformed manifest")
        }
        if (manifest.formatVersion != FORMAT_VERSION) {
            return invalid(
                SnapshotValidationIssue.UNSUPPORTED_FORMAT_VERSION,
                "Unsupported snapshot format version: ${manifest.formatVersion}",
            )
        }

        val payloadRoot = root.resolve(PAYLOAD_DIRECTORY_NAME).normalize()
        if (!Files.isDirectory(payloadRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(payloadRoot)) {
            return invalid(SnapshotValidationIssue.INVALID_SNAPSHOT_DIRECTORY, "Snapshot payload is missing")
        }

        return validatePayload(payloadRoot, manifest)
    }

    private fun prepareSnapshotRoot() {
        if (Files.isSymbolicLink(appDataRoot)) {
            throw IOException("App-data root cannot be a symbolic link")
        }
        Files.createDirectories(appDataRoot)
        if (Files.exists(backupsRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(backupsRoot)) {
            throw IOException("Backups directory cannot be a symbolic link")
        }
        Files.createDirectories(backupsRoot)
    }

    private fun copySourceFiles(payloadRoot: Path): List<SnapshotFileEntry> {
        val entries = mutableListOf<SnapshotFileEntry>()
        Files.walkFileTree(appDataRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory == backupsRoot) return FileVisitResult.SKIP_SUBTREE
                if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink) {
                    throw IOException("Symbolic links are not supported in app data: $directory")
                }
                val relative = appDataRoot.relativize(directory)
                Files.createDirectories(payloadRoot.resolve(relative))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file) || attributes.isSymbolicLink) {
                    throw IOException("Symbolic links are not supported in app data: $file")
                }
                if (!attributes.isRegularFile) {
                    throw IOException("Unsupported app-data entry: $file")
                }
                val relative = appDataRoot.relativize(file).normalize()
                val target = payloadRoot.resolve(relative)
                Files.copy(file, target)
                entries += SnapshotFileEntry(
                    path = portableRelativePath(relative),
                    size = Files.size(target),
                    sha256 = sha256(target),
                )
                return FileVisitResult.CONTINUE
            }
        })
        return entries
    }

    private fun requireRestorableCompletedSnapshot(snapshotDirectory: Path): AppDataSnapshot {
        if (Files.isSymbolicLink(appDataRoot) || Files.isSymbolicLink(backupsRoot)) {
            throw SnapshotRestoreException("App-data root and backups directory must not be symbolic links")
        }
        val root = snapshotDirectory.toAbsolutePath().normalize()
        val name = root.fileName?.toString()
            ?: throw SnapshotRestoreException("Selected snapshot path has no directory name")
        if (root.parent != backupsRoot || isInternalWorkspace(name)) {
            throw SnapshotRestoreException(
                "Restore source must be a completed snapshot directly under $backupsRoot",
            )
        }
        if (Files.isSymbolicLink(root)) {
            throw SnapshotRestoreException("Selected snapshot directory is a symbolic link")
        }

        val validation = validateSnapshot(root)
        if (!validation.valid) {
            throw SnapshotRestoreException(
                "Selected snapshot is invalid (${validation.issue}): ${validation.reason}",
            )
        }
        val manifest = readManifest(root.resolve(MANIFEST_FILE_NAME))
        return AppDataSnapshot(
            name = name,
            directory = root,
            createdAt = manifest.createdAt,
            validation = validation,
        )
    }

    private fun materializeSnapshotPayload(sourcePayload: Path, staging: Path) {
        Files.walkFileTree(sourcePayload, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink) {
                    throw IOException("Snapshot payload contains a symbolic link: $directory")
                }
                val relative = sourcePayload.relativize(directory).normalize()
                val target = staging.resolve(relative).normalize()
                if (!target.startsWith(staging)) {
                    throw IOException("Snapshot payload directory escapes staging: $relative")
                }
                Files.createDirectories(target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file) || attributes.isSymbolicLink) {
                    throw IOException("Snapshot payload contains a symbolic link: $file")
                }
                if (!attributes.isRegularFile) {
                    throw IOException("Snapshot payload contains a non-regular file: $file")
                }
                val relative = sourcePayload.relativize(file).normalize()
                val target = staging.resolve(relative).normalize()
                if (!target.startsWith(staging)) {
                    throw IOException("Snapshot payload file escapes staging: $relative")
                }
                Files.copy(file, target)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun requireValidPayload(
        payloadRoot: Path,
        manifest: SnapshotManifest,
        message: String,
        excludedDirectory: Path? = null,
    ) {
        val validation = validatePayload(payloadRoot, manifest, excludedDirectory)
        if (!validation.valid) {
            throw SnapshotRestoreException("$message (${validation.issue}): ${validation.reason}")
        }
    }

    private fun validatePayload(
        payloadRoot: Path,
        manifest: SnapshotManifest,
        excludedDirectory: Path? = null,
    ): SnapshotValidation {
        val recordedPaths = mutableSetOf<String>()
        for (entry in manifest.files) {
            if (!recordedPaths.add(entry.path)) {
                return invalid(SnapshotValidationIssue.MALFORMED_MANIFEST, "Duplicate entry: ${entry.path}")
            }
            val relative = safeRelativeEntry(entry.path)
                ?: return invalid(SnapshotValidationIssue.UNSAFE_ENTRY_PATH, "Unsafe entry path: ${entry.path}")
            if (isReservedBackupsPath(relative)) {
                return invalid(SnapshotValidationIssue.RESERVED_ENTRY_PATH, "Reserved entry path: ${entry.path}")
            }
            val file = payloadRoot.resolve(relative).normalize()
            if (!file.startsWith(payloadRoot)) {
                return invalid(SnapshotValidationIssue.UNSAFE_ENTRY_PATH, "Unsafe entry path: ${entry.path}")
            }
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(SnapshotValidationIssue.MISSING_FILE, "Snapshot file is missing: ${entry.path}")
            }
            if (Files.isSymbolicLink(file)) {
                return invalid(SnapshotValidationIssue.SYMBOLIC_LINK, "Snapshot file is a symbolic link: ${entry.path}")
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(SnapshotValidationIssue.NOT_REGULAR_FILE, "Snapshot entry is not a regular file: ${entry.path}")
            }
            if (Files.size(file) != entry.size) {
                return invalid(SnapshotValidationIssue.SIZE_MISMATCH, "Snapshot file size differs: ${entry.path}")
            }
            if (sha256(file) != entry.sha256) {
                return invalid(SnapshotValidationIssue.HASH_MISMATCH, "Snapshot file hash differs: ${entry.path}")
            }
        }

        return validateNoUnrecordedPayloadFiles(payloadRoot, recordedPaths, excludedDirectory)
            ?: SnapshotValidation.VALID
    }

    private fun activeTopLevelEntries(): List<Path> =
        directChildren(appDataRoot).filter { entry -> entry.toAbsolutePath().normalize() != backupsRoot }

    private fun directChildren(directory: Path): List<Path> {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(directory).use { children ->
            children.sorted(compareBy { it.fileName.toString() }).toList()
        }
    }

    private fun moveWithoutReplace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun rollbackActivePayload(
        workspace: Path,
        recovery: Path,
        recoveredEntryNames: List<String>,
        installedEntries: List<Path>,
        originalError: Throwable,
    ) {
        var rollbackError: Throwable? = null

        fun recordRollbackFailure(error: Throwable) {
            if (rollbackError == null) {
                rollbackError = error
            } else {
                rollbackError.addSuppressed(error)
            }
        }

        installedEntries.forEach { entry ->
            try {
                if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) deleteTree(entry)
            } catch (error: Throwable) {
                recordRollbackFailure(error)
            }
        }
        directChildren(recovery).forEach { entry ->
            try {
                moveWithoutReplace(entry, appDataRoot.resolve(entry.fileName))
            } catch (error: Throwable) {
                recordRollbackFailure(error)
            }
        }
        recoveredEntryNames.forEach { name ->
            if (!Files.exists(appDataRoot.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                recordRollbackFailure(IOException("Rollback did not restore active entry: $name"))
            }
        }

        if (rollbackError == null) {
            try {
                deleteTree(workspace)
            } catch (error: Throwable) {
                recordRollbackFailure(error)
            }
        }

        rollbackError?.let { failure ->
            originalError.addSuppressed(failure)
            throw SnapshotRestoreException(
                message = "Snapshot restore failed and rollback was incomplete; recovery evidence retained at $recovery",
                recoveryDirectory = recovery,
                cause = originalError,
            )
        }
    }

    private fun cleanupWorkspaceAfterPreMutationFailure(workspace: Path, originalError: Throwable) {
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return
        try {
            deleteTree(workspace)
        } catch (cleanupError: Throwable) {
            originalError.addSuppressed(cleanupError)
        }
    }

    private fun writeManifest(snapshotDirectory: Path, manifest: SnapshotManifest) {
        val content = buildJsonObject {
            put("formatVersion", manifest.formatVersion)
            put("createdAt", manifest.createdAt.toString())
            put("files", buildJsonArray {
                manifest.files.forEach { entry ->
                    add(buildJsonObject {
                        put("path", entry.path)
                        put("size", entry.size)
                        put("sha256", entry.sha256)
                    })
                }
            })
        }
        Files.writeString(snapshotDirectory.resolve(MANIFEST_FILE_NAME), json.encodeToString(content))
    }

    private fun readManifest(path: Path): SnapshotManifest {
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val formatVersion = root["formatVersion"]?.jsonPrimitive?.intOrNull
            ?: throw IOException("Manifest formatVersion is missing")
        val createdAtText = root["createdAt"]?.jsonPrimitive?.content
            ?: throw IOException("Manifest createdAt is missing")
        val createdAt = try {
            Instant.parse(createdAtText)
        } catch (error: Exception) {
            throw IOException("Manifest createdAt is invalid", error)
        }
        val files = root["files"]?.jsonArray?.map { element ->
            val entry = element.jsonObject
            val relativePath = entry["path"]?.jsonPrimitive?.content
                ?: throw IOException("Manifest entry path is missing")
            val size = entry["size"]?.jsonPrimitive?.longOrNull
                ?: throw IOException("Manifest entry size is missing")
            val hash = entry["sha256"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT)
                ?: throw IOException("Manifest entry sha256 is missing")
            if (size < 0 || !SHA256_PATTERN.matches(hash)) {
                throw IOException("Manifest entry metadata is invalid: $relativePath")
            }
            SnapshotFileEntry(relativePath, size, hash)
        } ?: throw IOException("Manifest files are missing")
        return SnapshotManifest(formatVersion, createdAt, files)
    }

    private fun readManifestOrNull(path: Path): SnapshotManifest? =
        runCatching { readManifest(path) }.getOrNull()

    private fun validateNoUnrecordedPayloadFiles(
        payloadRoot: Path,
        recordedPaths: Set<String>,
        excludedDirectory: Path? = null,
    ): SnapshotValidation? {
        var problem: SnapshotValidation? = null
        Files.walkFileTree(payloadRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (excludedDirectory != null && directory.toAbsolutePath().normalize() == excludedDirectory) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (directory != payloadRoot && (Files.isSymbolicLink(directory) || attributes.isSymbolicLink)) {
                    problem = invalid(
                        SnapshotValidationIssue.SYMBOLIC_LINK,
                        "Snapshot payload contains a symbolic link: ${payloadRoot.relativize(directory)}",
                    )
                    return FileVisitResult.TERMINATE
                }
                if (directory != payloadRoot) {
                    val relative = payloadRoot.relativize(directory).normalize()
                    if (isReservedBackupsPath(relative)) {
                        problem = invalid(
                            SnapshotValidationIssue.RESERVED_ENTRY_PATH,
                            "Snapshot payload contains reserved directory: ${portableRelativePath(relative)}",
                        )
                        return FileVisitResult.TERMINATE
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                val relative = portableRelativePath(payloadRoot.relativize(file).normalize())
                if (Files.isSymbolicLink(file) || attributes.isSymbolicLink) {
                    problem = invalid(
                        SnapshotValidationIssue.SYMBOLIC_LINK,
                        "Snapshot payload contains a symbolic link: $relative",
                    )
                    return FileVisitResult.TERMINATE
                }
                if (!attributes.isRegularFile) {
                    problem = invalid(
                        SnapshotValidationIssue.NOT_REGULAR_FILE,
                        "Snapshot payload contains a non-regular file: $relative",
                    )
                    return FileVisitResult.TERMINATE
                }
                if (relative !in recordedPaths) {
                    problem = invalid(
                        SnapshotValidationIssue.UNRECORDED_FILE,
                        "Snapshot payload contains an unrecorded file: $relative",
                    )
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }
        })
        return problem
    }

    private fun safeRelativeEntry(value: String): Path? {
        if (value.isBlank() || '\\' in value) return null
        val relative = try {
            Path.of(value)
        } catch (_: Exception) {
            return null
        }
        if (relative.isAbsolute || relative.nameCount == 0) return null
        if (relative.any { part -> part.toString() in setOf(".", "..") }) return null
        val normalized = relative.normalize()
        if (portableRelativePath(normalized) != value) return null
        return normalized
    }

    private fun isReservedBackupsPath(relative: Path): Boolean =
        relative.nameCount > 0 &&
            relative.getName(0).toString().equals(BACKUPS_DIRECTORY_NAME, ignoreCase = true)

    private fun installCompletedSnapshot(staging: Path, completed: Path) {
        try {
            Files.move(staging, completed, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging, completed)
        }
    }

    private fun completedSnapshotName(createdAt: Instant): String {
        val id = idSupplier().filter(Char::isLetterOrDigit).take(12)
        require(id.isNotBlank()) { "Snapshot ID must contain a letter or digit" }
        return "${SNAPSHOT_NAME_TIME_FORMAT.format(createdAt)}-$id"
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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun portableRelativePath(path: Path): String =
        path.iterator().asSequence().joinToString("/") { it.toString() }

    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun isInternalWorkspace(name: String): Boolean =
        (name.startsWith(".snapshot-") || name.startsWith(".restore-")) && name.endsWith(".tmp")

    private fun invalid(issue: SnapshotValidationIssue, reason: String) =
        SnapshotValidation(valid = false, issue = issue, reason = reason)

    private data class SnapshotManifest(
        val formatVersion: Int,
        val createdAt: Instant,
        val files: List<SnapshotFileEntry>,
    )

    private data class SnapshotFileEntry(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    companion object {
        const val FORMAT_VERSION = 1
        const val BACKUPS_DIRECTORY_NAME = "backups"
        const val PAYLOAD_DIRECTORY_NAME = "payload"
        const val MANIFEST_FILE_NAME = "snapshot-manifest.json"

        private const val RESTORE_STAGING_DIRECTORY_NAME = "staging"
        private const val RESTORE_RECOVERY_DIRECTORY_NAME = "recovery"

        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val SNAPSHOT_NAME_TIME_FORMAT = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC)
    }
}
