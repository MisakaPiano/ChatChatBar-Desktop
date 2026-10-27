package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import java.nio.file.Path
import kotlinx.coroutines.*

/** Relocation uses the verified merge publisher, never a byte-copy of an identity-bound fence.
 * Caller holds task maintenance. Source/old keys and historical backup workspaces remain retained.
 */
internal class DesktopBoundRootMigration(
    private val source: DesktopDataRootResolution.Resolved,
    private val gate: DesktopDataOperationCoordinator,
    private val createService: (Path) -> DesktopMergeImportService,
    private val maintenance: suspend (suspend () -> Unit, (DesktopMaintenanceResumeResult) -> Unit) -> Unit,
    private val beforeCapture: suspend () -> Unit,
    private val validateBinding: (Path) -> Unit,
    private val asset: (String) -> ByteArray,
    private val preparer: DesktopMigrationDestinationPreparer = DesktopMigrationDestinationPreparer(),
) {
    private enum class Stage { IDENTITY, DESTINATION, SCRATCH, EXPORT, INSPECTION, SOURCE_COMPARISON, PUBLICATION }
    private var retained: DesktopMergeImportService? = null

    suspend fun migrate(requested: Path): DesktopDataRootMigrationResult {
        val destination = requested.toAbsolutePath().normalize()
        var scratch: DesktopBackupScratch? = null
        var service: DesktopMergeImportService? = null
        var publication: DesktopMergeImportService.Publication? = null
        var evidence: DesktopMergeImportService.CommitEvidence? = null
        var resumed: DesktopMaintenanceResumeResult? = null
        var failure: Throwable? = null
        var cancelled: CancellationException? = null
        var cleanup: Throwable? = null
        var stage = Stage.IDENTITY
        try {
            check(retained == null && !gate.isRestartRequired) { "BOUND_ROOT_RESTART_REQUIRED" }
            // Validate the destination domain before placing any private preparation there.
            DesktopArchiveFiles.nativePath(destination)
            validateBinding(destination)
            stage = Stage.DESTINATION
            when (val prepared = preparer.prepare(source, destination)) {
                is DesktopMigrationDestinationResult.Prepared -> prepared.destination.close()
                is DesktopMigrationDestinationResult.Failure -> return DesktopDataRootMigrationResult.Failure(
                    DesktopDataRootMigrationFailureKind.PREPARATION, source.appDataRoot, destination, preparation = prepared)
            }
            stage = Stage.SCRATCH
            val owned = DesktopBackupScratch.create(requireNotNull(destination.parent), "root-relocation-").also { scratch = it }
            val workspace = owned.directory.resolve("work"); owned.directories(workspace)
            val active = createService(workspace).also { service = it }
            maintenance({
                beforeCapture()
                stage = Stage.EXPORT
                val archive = owned.directory.resolve("current.cbbackup")
                val expected = gate.withExclusiveMaintenance {
                    validateBinding(source.appDataRoot)
                    DesktopFullBackupMapping(source.appDataRoot, asset).export(owned.directory, 86,
                        StrictBackupZipReader.Limits().expanded, forMerge = true).use { snapshot ->
                        DesktopFullBackupWriter.export(snapshot.files, archive, null, snapshot.preferences)
                        owned.recordCreated(archive)
                        snapshot.proof.toMap()
                    }
                }
                stage = Stage.INSPECTION
                val preview = active.inspect(archive, null)
                // Both captures must describe the exact same source. Never resurrect stale/deleted data
                // by silently using merge conflict choices for a directory move.
                stage = Stage.SOURCE_COMPARISON
                check(preview.computer.proof == expected &&
                    preview.phone.source.inventory.mapValues { it.value.sha256 } == expected &&
                    preview.data.conflicts.isEmpty() && preview.credentialConflicts.isEmpty() &&
                    !preview.imagePreferencesChoiceRequired) { "BOUND_ROOT_SOURCE_CHANGED_RETRY" }
                stage = Stage.PUBLICATION
                publication = active.commit(preview, destination, emptyMap(), emptyMap(), true, false)
                evidence = active.committedEvidence
            }, { resumed = it })
        } catch (stop: CancellationException) { cancelled = stop }
        catch (error: Throwable) { failure = IllegalStateException("BOUND_ROOT_${stage.name}", error) }
        finally {
            withContext(NonCancellable) {
                val active = service
                if (active?.ownsPublishedDestination == true) {
                    retained = active
                    evidence = evidence ?: active.committedEvidence
                } else try { active?.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
                try { scratch?.close() } catch (error: Throwable) { cleanup = error }
            }
        }
        cancelled?.let { throw it }
        val proof = evidence
        if (publication == DesktopMergeImportService.Publication.COMMITTED &&
            proof?.authority is DesktopBootstrapAuthorityCommitResult.Committed &&
            resumed == DesktopMaintenanceResumeResult.RestartRequired && failure == null) {
            return DesktopDataRootMigrationResult.ProtectedSuccess(source.appDataRoot, destination, proof.snapshot,
                proof.authority, requireNotNull(resumed),
                cleanupWarning = cleanup?.let { "临时目录清理未确认；原目录和新目录均保留。" },
                retainedWorkspace = scratch?.directory.takeIf { cleanup != null })
        }
        val sealed = gate.isRestartRequired
        val committed = proof?.authority is DesktopBootstrapAuthorityCommitResult.Committed
        return DesktopDataRootMigrationResult.Failure(
            kind = if (sealed && committed) DesktopDataRootMigrationFailureKind.RUNTIME_RESUME
                else if (sealed) DesktopDataRootMigrationFailureKind.AUTHORITY_INDETERMINATE
                else if (resumed == null) DesktopDataRootMigrationFailureKind.PREPARATION
                else DesktopDataRootMigrationFailureKind.MATERIALIZATION,
            sourceRoot = source.appDataRoot, destinationRoot = destination,
            preMigrationSnapshot = proof?.snapshot, authorityCommit = proof?.authority, runtimeDisposition = resumed,
            authorityState = if (sealed && committed) DesktopDataRootMigrationAuthorityState.DESTINATION
                else if (sealed) DesktopDataRootMigrationAuthorityState.INDETERMINATE else DesktopDataRootMigrationAuthorityState.SOURCE,
            restartRequired = sealed, sourceStillAuthoritative = if (sealed && committed) false else if (sealed) null else true,
            destinationOwnershipRetained = retained != null, cause = failure ?: cleanup,
        )
    }

    suspend fun close() { retained?.close(); retained = null }
}
