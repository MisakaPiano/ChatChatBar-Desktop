package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshot
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.SnapshotPurpose
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DesktopDataRootMigrationFailureKind {
    PREPARATION,
    RUNTIME_PAUSE,
    PREFLIGHT,
    SAFETY_SNAPSHOT,
    MATERIALIZATION,
    AUTHORITY_PRE_COMMIT,
    AUTHORITY_INDETERMINATE,
    RUNTIME_RESUME,
    RESTART_REQUIRED,
    CLOSED,
    INTERNAL,
}

enum class DesktopDataRootMigrationAuthorityState {
    SOURCE,
    DESTINATION,
    INDETERMINATE,
}

sealed interface DesktopDataRootMigrationResult {
    data class Success(
        val sourceRoot: Path,
        val destinationRoot: Path,
        val preMigrationSnapshot: AppDataSnapshot,
        val materialization: DesktopMigrationMaterializationResult.Materialized,
        val authorityCommit: DesktopBootstrapAuthorityCommitResult.Committed,
        val runtimeDisposition: DesktopMaintenanceResumeResult,
        val restartRequired: Boolean = true,
        val sourceRetained: Boolean = true,
        val destinationOwnershipRetained: Boolean = true,
    ) : DesktopDataRootMigrationResult

    data class Failure(
        val kind: DesktopDataRootMigrationFailureKind,
        val sourceRoot: Path,
        val destinationRoot: Path? = null,
        val preparation: DesktopMigrationDestinationResult.Failure? = null,
        val pauseFailure: DesktopAutomaticBackupRuntimeFailure? = null,
        val preflightFailure: DesktopMigrationMaterializationResult.Failure? = null,
        val preMigrationSnapshot: AppDataSnapshot? = null,
        val materialization: DesktopMigrationMaterializationResult? = null,
        val authorityCommit: DesktopBootstrapAuthorityCommitResult? = null,
        val runtimeDisposition: DesktopMaintenanceResumeResult? = null,
        val authorityState: DesktopDataRootMigrationAuthorityState =
            DesktopDataRootMigrationAuthorityState.SOURCE,
        val restartRequired: Boolean = false,
        val sourceStillAuthoritative: Boolean? = true,
        val destinationOwnershipRetained: Boolean = false,
        val cause: Throwable? = null,
        val destinationCloseFailure: Throwable? = null,
    ) : DesktopDataRootMigrationResult
}

internal data class DesktopDataRootMigrationDependencies(
    val prepareDestination: (
        DesktopDataRootResolution.Resolved,
        Path,
    ) -> DesktopMigrationDestinationResult,
    val preflight: suspend (
        Path,
        DesktopPreparedMigrationDestination,
    ) -> DesktopMigrationMaterializationPreflightResult,
    val createSafetySnapshot: (SnapshotPurpose) -> AppDataSnapshot,
    val materialize: suspend (
        Path,
        DesktopPreparedMigrationDestination,
    ) -> DesktopMigrationMaterializationResult,
    val commitAuthority: suspend (
        DesktopDataRootResolution.Resolved,
        Path,
    ) -> DesktopBootstrapAuthorityCommitResult,
    val closeDestination: (DesktopPreparedMigrationDestination) -> Unit =
        DesktopPreparedMigrationDestination::close,
)

/**
 * Desktop data-root migration 的单一 orchestration owner。
 *
 * Source root ownership 已由 Main 的最外层 application lifetime 持有；这里不得重新取得或暴露
 * source ownership。Destination ownership 由 D1 handle 持有。固定顺序是 destination prepare，
 * runtime pause/stop/join，然后才申请 coordinator exclusive；exclusive 内依次执行无写 preflight、
 * MANUAL safety snapshot、materialization 与 bootstrap authority commit。
 *
 * Authority `Committed` 或 `CommitIndeterminate` 后，commit classification、restart seal 与
 * destination ownership transfer 位于同一 NonCancellable critical section。旧 source runtime
 * 此后永不重新开放，destination ownership 保持到本 service 随 container shutdown 关闭。
 */
class DesktopDataRootMigrationService internal constructor(
    private val sourceResolution: DesktopDataRootResolution.Resolved,
    private val runtime: DesktopAutomaticBackupRuntime,
    private val coordinator: DesktopDataOperationCoordinator,
    private val dependencies: DesktopDataRootMigrationDependencies,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal constructor(
        sourceResolution: DesktopDataRootResolution.Resolved,
        runtime: DesktopAutomaticBackupRuntime,
        coordinator: DesktopDataOperationCoordinator,
        snapshotService: AppDataSnapshotService,
        destinationPreparer: DesktopMigrationDestinationPreparer = DesktopMigrationDestinationPreparer(),
        materializer: DesktopDataRootMigrationMaterializer = DesktopDataRootMigrationMaterializer(),
        authorityTransaction: DesktopBootstrapAuthorityTransaction = DesktopBootstrapAuthorityTransaction(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        sourceResolution = sourceResolution,
        runtime = runtime,
        coordinator = coordinator,
        dependencies = DesktopDataRootMigrationDependencies(
            prepareDestination = destinationPreparer::prepare,
            preflight = materializer::preflight,
            createSafetySnapshot = snapshotService::createSnapshot,
            materialize = materializer::materialize,
            commitAuthority = authorityTransaction::commitCustomRoot,
        ),
        ioDispatcher = ioDispatcher,
    )

    private val lifecycleMutex = Mutex()
    private var retainedDestination: DesktopPreparedMigrationDestination? = null
    private var restartSealed = false
    private var closed = false

    suspend fun migrate(requestedDestination: Path): DesktopDataRootMigrationResult =
        lifecycleMutex.withLock {
            when {
                closed -> return@withLock rejected(DesktopDataRootMigrationFailureKind.CLOSED)
                restartSealed -> return@withLock rejected(DesktopDataRootMigrationFailureKind.RESTART_REQUIRED)
            }
            migrateLocked(requestedDestination)
        }

    suspend fun close() = lifecycleMutex.withLock {
        if (closed) return@withLock
        closed = true
        val destination = retainedDestination
        retainedDestination = null
        destination?.let(dependencies.closeDestination)
    }

    private suspend fun migrateLocked(requestedDestination: Path): DesktopDataRootMigrationResult {
        val prepared = when (val preparation = try {
            dependencies.prepareDestination(sourceResolution, requestedDestination)
        } catch (error: Throwable) {
            return failure(
                kind = DesktopDataRootMigrationFailureKind.PREPARATION,
                destinationRoot = requestedDestination.toAbsolutePath().normalize(),
                cause = error,
            )
        }) {
            is DesktopMigrationDestinationResult.Prepared -> preparation.destination
            is DesktopMigrationDestinationResult.Failure -> return failure(
                kind = DesktopDataRootMigrationFailureKind.PREPARATION,
                destinationRoot = preparation.destinationRoot,
                preparation = preparation,
                cause = preparation.cause,
            )
        }

        val pause = try {
            runtime.pauseForMaintenance()
        } catch (cancelled: CancellationException) {
            closeBeforePauseCancellation(prepared, cancelled)
            throw cancelled
        } catch (error: Throwable) {
            val closeFailure = closePrepared(prepared, error)
            return failure(
                kind = DesktopDataRootMigrationFailureKind.RUNTIME_PAUSE,
                destinationRoot = prepared.destinationRoot,
                cause = error,
                destinationCloseFailure = closeFailure,
            )
        }
        if (pause is DesktopMaintenancePauseResult.Failed) {
            val closeFailure = closePrepared(prepared, pause.failure.error)
            return failure(
                kind = DesktopDataRootMigrationFailureKind.RUNTIME_PAUSE,
                destinationRoot = prepared.destinationRoot,
                pauseFailure = pause.failure,
                cause = pause.failure.error,
                destinationCloseFailure = closeFailure,
            )
        }

        var authorityMayHaveChanged = false
        try {
            val outcome = coordinator.withExclusiveMaintenance {
                when (val preflight = dependencies.preflight(sourceResolution.appDataRoot, prepared)) {
                    is DesktopMigrationMaterializationPreflightResult.Failure ->
                        return@withExclusiveMaintenance ExclusiveOutcome.Failed(
                            failure(
                                kind = DesktopDataRootMigrationFailureKind.PREFLIGHT,
                                destinationRoot = prepared.destinationRoot,
                                preflightFailure = preflight.failure,
                                cause = preflight.failure.cause,
                            ),
                        )

                    DesktopMigrationMaterializationPreflightResult.Ready -> Unit
                }

                val snapshot = try {
                    withContext(ioDispatcher) {
                        dependencies.createSafetySnapshot(SnapshotPurpose.MANUAL)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withExclusiveMaintenance ExclusiveOutcome.Failed(
                        failure(
                            kind = DesktopDataRootMigrationFailureKind.SAFETY_SNAPSHOT,
                            destinationRoot = prepared.destinationRoot,
                            cause = error,
                        ),
                    )
                }
                if (!snapshot.validation.valid) {
                    return@withExclusiveMaintenance ExclusiveOutcome.Failed(
                        failure(
                            kind = DesktopDataRootMigrationFailureKind.SAFETY_SNAPSHOT,
                            destinationRoot = prepared.destinationRoot,
                            preMigrationSnapshot = snapshot,
                            cause = IllegalStateException(
                                "Pre-migration snapshot is invalid: ${snapshot.validation.issue}: " +
                                    snapshot.validation.reason,
                            ),
                        ),
                    )
                }

                val materialization = dependencies.materialize(sourceResolution.appDataRoot, prepared)
                if (materialization is DesktopMigrationMaterializationResult.Failure) {
                    return@withExclusiveMaintenance ExclusiveOutcome.Failed(
                        failure(
                            kind = DesktopDataRootMigrationFailureKind.MATERIALIZATION,
                            destinationRoot = prepared.destinationRoot,
                            preMigrationSnapshot = snapshot,
                            materialization = materialization,
                            cause = materialization.cause,
                        ),
                    )
                }
                materialization as DesktopMigrationMaterializationResult.Materialized

                val authority = withContext(NonCancellable) {
                    val classified = try {
                        dependencies.commitAuthority(sourceResolution, prepared.destinationRoot)
                    } catch (error: Throwable) {
                        DesktopBootstrapAuthorityCommitResult.CommitIndeterminate(
                            message = "Bootstrap authority transaction threw after materialization",
                            cause = error,
                        )
                    }
                    if (classified.authorityMayHaveChanged()) {
                        authorityMayHaveChanged = true
                        requireRestart()
                        restartSealed = true
                        retainedDestination = prepared
                    }
                    classified
                }

                when (authority) {
                    is DesktopBootstrapAuthorityCommitResult.Committed -> ExclusiveOutcome.Committed(
                        snapshot = snapshot,
                        materialization = materialization,
                        authority = authority,
                    )

                    is DesktopBootstrapAuthorityCommitResult.CommitIndeterminate ->
                        ExclusiveOutcome.Indeterminate(
                            failure(
                                kind = DesktopDataRootMigrationFailureKind.AUTHORITY_INDETERMINATE,
                                destinationRoot = prepared.destinationRoot,
                                preMigrationSnapshot = snapshot,
                                materialization = materialization,
                                authorityCommit = authority,
                                authorityState = DesktopDataRootMigrationAuthorityState.INDETERMINATE,
                                restartRequired = true,
                                sourceStillAuthoritative = null,
                                destinationOwnershipRetained = true,
                                cause = authority.cause,
                            ),
                        )

                    else -> ExclusiveOutcome.Failed(
                        failure(
                            kind = DesktopDataRootMigrationFailureKind.AUTHORITY_PRE_COMMIT,
                            destinationRoot = prepared.destinationRoot,
                            preMigrationSnapshot = snapshot,
                            materialization = materialization,
                            authorityCommit = authority,
                            authorityState = DesktopDataRootMigrationAuthorityState.SOURCE,
                            restartRequired = false,
                            sourceStillAuthoritative = true,
                        ),
                    )
                }
            }

            val stabilization = stabilizeAfterExclusive(prepared, authorityMayHaveChanged)
            return outcome.toPublicResult(prepared, stabilization)
        } catch (cancelled: CancellationException) {
            val stabilization = withContext(NonCancellable) {
                stabilizeAfterExclusive(prepared, authorityMayHaveChanged)
            }
            stabilization.closeFailure?.let(cancelled::addSuppressed)
            (stabilization.resumeResult as? DesktopMaintenanceResumeResult.Failed)
                ?.failure?.error?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: Throwable) {
            val stabilization = withContext(NonCancellable) {
                stabilizeAfterExclusive(prepared, authorityMayHaveChanged)
            }
            stabilization.closeFailure?.let(error::addSuppressed)
            (stabilization.resumeResult as? DesktopMaintenanceResumeResult.Failed)
                ?.failure?.error?.let(error::addSuppressed)
            return failure(
                kind = DesktopDataRootMigrationFailureKind.INTERNAL,
                destinationRoot = prepared.destinationRoot,
                authorityState = if (authorityMayHaveChanged) {
                    DesktopDataRootMigrationAuthorityState.INDETERMINATE
                } else {
                    DesktopDataRootMigrationAuthorityState.SOURCE
                },
                restartRequired = authorityMayHaveChanged,
                sourceStillAuthoritative = if (authorityMayHaveChanged) null else true,
                destinationOwnershipRetained = authorityMayHaveChanged,
                runtimeDisposition = stabilization.resumeResult,
                cause = error,
                destinationCloseFailure = stabilization.closeFailure,
            )
        }
    }

    private suspend fun stabilizeAfterExclusive(
        prepared: DesktopPreparedMigrationDestination,
        authorityMayHaveChanged: Boolean,
    ): Stabilization {
        var closeFailure: Throwable? = null
        if (!authorityMayHaveChanged) {
            closeFailure = closePrepared(prepared, null)
        }
        val resumeResult = try {
            runtime.resumeAfterMaintenance()
        } catch (error: Throwable) {
            DesktopMaintenanceResumeResult.Failed(
                DesktopAutomaticBackupRuntimeFailure(
                    stage = DesktopAutomaticBackupRuntimeFailureStage.INVALID_STATE,
                    error = error.asException(),
                ),
            )
        }
        return Stabilization(closeFailure, resumeResult)
    }

    private fun ExclusiveOutcome.toPublicResult(
        prepared: DesktopPreparedMigrationDestination,
        stabilization: Stabilization,
    ): DesktopDataRootMigrationResult = when (this) {
        is ExclusiveOutcome.Committed -> {
            if (stabilization.resumeResult is DesktopMaintenanceResumeResult.RestartRequired) {
                DesktopDataRootMigrationResult.Success(
                    sourceRoot = sourceResolution.appDataRoot,
                    destinationRoot = prepared.destinationRoot,
                    preMigrationSnapshot = snapshot,
                    materialization = materialization,
                    authorityCommit = authority,
                    runtimeDisposition = stabilization.resumeResult,
                )
            } else {
                failure(
                    kind = DesktopDataRootMigrationFailureKind.RUNTIME_RESUME,
                    destinationRoot = prepared.destinationRoot,
                    preMigrationSnapshot = snapshot,
                    materialization = materialization,
                    authorityCommit = authority,
                    runtimeDisposition = stabilization.resumeResult,
                    authorityState = DesktopDataRootMigrationAuthorityState.DESTINATION,
                    restartRequired = true,
                    sourceStillAuthoritative = false,
                    destinationOwnershipRetained = true,
                    cause = stabilization.resumeResult.failureCause(),
                )
            }
        }

        is ExclusiveOutcome.Indeterminate -> failure.copy(
            runtimeDisposition = stabilization.resumeResult,
            cause = failure.cause ?: stabilization.resumeResult.failureCause(),
        )

        is ExclusiveOutcome.Failed -> {
            if (stabilization.resumeResult is DesktopMaintenanceResumeResult.Resumed) {
                failure.copy(destinationCloseFailure = stabilization.closeFailure)
            } else {
                failure.copy(
                    kind = DesktopDataRootMigrationFailureKind.RUNTIME_RESUME,
                    runtimeDisposition = stabilization.resumeResult,
                    cause = failure.cause ?: stabilization.resumeResult.failureCause(),
                    destinationCloseFailure = stabilization.closeFailure,
                )
            }
        }
    }

    private fun rejected(kind: DesktopDataRootMigrationFailureKind) = failure(
        kind = kind,
        restartRequired = restartSealed,
        sourceStillAuthoritative = if (restartSealed) null else true,
        destinationOwnershipRetained = retainedDestination != null,
    )

    private fun failure(
        kind: DesktopDataRootMigrationFailureKind,
        destinationRoot: Path? = null,
        preparation: DesktopMigrationDestinationResult.Failure? = null,
        pauseFailure: DesktopAutomaticBackupRuntimeFailure? = null,
        preflightFailure: DesktopMigrationMaterializationResult.Failure? = null,
        preMigrationSnapshot: AppDataSnapshot? = null,
        materialization: DesktopMigrationMaterializationResult? = null,
        authorityCommit: DesktopBootstrapAuthorityCommitResult? = null,
        runtimeDisposition: DesktopMaintenanceResumeResult? = null,
        authorityState: DesktopDataRootMigrationAuthorityState =
            DesktopDataRootMigrationAuthorityState.SOURCE,
        restartRequired: Boolean = false,
        sourceStillAuthoritative: Boolean? = true,
        destinationOwnershipRetained: Boolean = false,
        cause: Throwable? = null,
        destinationCloseFailure: Throwable? = null,
    ) = DesktopDataRootMigrationResult.Failure(
        kind = kind,
        sourceRoot = sourceResolution.appDataRoot,
        destinationRoot = destinationRoot,
        preparation = preparation,
        pauseFailure = pauseFailure,
        preflightFailure = preflightFailure,
        preMigrationSnapshot = preMigrationSnapshot,
        materialization = materialization,
        authorityCommit = authorityCommit,
        runtimeDisposition = runtimeDisposition,
        authorityState = authorityState,
        restartRequired = restartRequired,
        sourceStillAuthoritative = sourceStillAuthoritative,
        destinationOwnershipRetained = destinationOwnershipRetained,
        cause = cause,
        destinationCloseFailure = destinationCloseFailure,
    )

    private fun closeBeforePauseCancellation(
        prepared: DesktopPreparedMigrationDestination,
        cancelled: CancellationException,
    ) {
        closePrepared(prepared, cancelled)?.let(cancelled::addSuppressed)
    }

    private fun closePrepared(
        prepared: DesktopPreparedMigrationDestination,
        primary: Throwable?,
    ): Throwable? = try {
        dependencies.closeDestination(prepared)
        null
    } catch (closeError: Throwable) {
        if (primary != null && closeError !== primary) primary.addSuppressed(closeError)
        closeError
    }

    private sealed interface ExclusiveOutcome {
        data class Committed(
            val snapshot: AppDataSnapshot,
            val materialization: DesktopMigrationMaterializationResult.Materialized,
            val authority: DesktopBootstrapAuthorityCommitResult.Committed,
        ) : ExclusiveOutcome

        data class Indeterminate(
            val failure: DesktopDataRootMigrationResult.Failure,
        ) : ExclusiveOutcome

        data class Failed(
            val failure: DesktopDataRootMigrationResult.Failure,
        ) : ExclusiveOutcome
    }

    private data class Stabilization(
        val closeFailure: Throwable?,
        val resumeResult: DesktopMaintenanceResumeResult,
    )
}

private fun DesktopBootstrapAuthorityCommitResult.authorityMayHaveChanged(): Boolean =
    this is DesktopBootstrapAuthorityCommitResult.Committed ||
        this is DesktopBootstrapAuthorityCommitResult.CommitIndeterminate

private fun DesktopMaintenanceResumeResult.failureCause(): Throwable? =
    (this as? DesktopMaintenanceResumeResult.Failed)?.failure?.error

private fun Throwable.asException(): Exception = this as? Exception ?: RuntimeException(this)
