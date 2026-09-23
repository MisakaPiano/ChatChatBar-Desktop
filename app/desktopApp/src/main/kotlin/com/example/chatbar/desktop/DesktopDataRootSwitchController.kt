package com.example.chatbar.desktop

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DesktopDataRootSwitchState {
    val currentRoot: Path
    val provenance: DesktopDataRootProvenance

    data class Idle(
        override val currentRoot: Path,
        override val provenance: DesktopDataRootProvenance,
        val supported: Boolean,
        val unsupportedReason: String? = null,
    ) : DesktopDataRootSwitchState

    data class CandidateSelected(
        override val currentRoot: Path,
        override val provenance: DesktopDataRootProvenance,
        val destinationRoot: Path,
    ) : DesktopDataRootSwitchState

    data class Migrating(
        override val currentRoot: Path,
        override val provenance: DesktopDataRootProvenance,
        val destinationRoot: Path,
        val closeDeferred: Boolean = false,
    ) : DesktopDataRootSwitchState

    data class RetryableFailure(
        override val currentRoot: Path,
        override val provenance: DesktopDataRootProvenance,
        val destinationRoot: Path,
        val result: DesktopDataRootMigrationResult.Failure,
    ) : DesktopDataRootSwitchState

    data class RestartRequired(
        override val currentRoot: Path,
        override val provenance: DesktopDataRootProvenance,
        val destinationRoot: Path?,
        val nextStartRoot: Path?,
        val result: DesktopDataRootMigrationResult?,
        val unexpectedFailure: Throwable? = null,
        val cancelledAfterRestartSeal: Boolean = false,
    ) : DesktopDataRootSwitchState
}

/**
 * Narrow user-facing adapter for the already coordinated Desktop migration service.
 *
 * The startup [resolvedRoot] remains the current running root for this controller's lifetime.
 * A committed destination is reported separately as the next-start root; this class never hot-swaps
 * a container or releases migration-held destination ownership.
 */
class DesktopDataRootSwitchController internal constructor(
    private val resolvedRoot: DesktopDataRootResolution.Resolved,
    private val directoryPicker: DesktopDirectoryPicker,
    private val isRestartRequired: () -> Boolean,
    private val migrate: suspend (Path) -> DesktopDataRootMigrationResult,
) {
    private val stateLock = Any()
    private val initialState = idleState()
    private val _state = MutableStateFlow<DesktopDataRootSwitchState>(initialState)
    val state: StateFlow<DesktopDataRootSwitchState> = _state.asStateFlow()

    fun chooseDestination() {
        val mayChoose = synchronized(stateLock) {
            when (_state.value) {
                is DesktopDataRootSwitchState.Idle,
                is DesktopDataRootSwitchState.RetryableFailure,
                -> isSupportedProvenance(resolvedRoot.provenance)
                else -> false
            }
        }
        if (!mayChoose) return
        val selected = directoryPicker.pickDirectory() ?: run {
            synchronized(stateLock) { _state.value = idleState() }
            return
        }
        synchronized(stateLock) {
            if (_state.value is DesktopDataRootSwitchState.Idle ||
                _state.value is DesktopDataRootSwitchState.RetryableFailure
            ) {
                _state.value = DesktopDataRootSwitchState.CandidateSelected(
                    currentRoot = resolvedRoot.appDataRoot,
                    provenance = resolvedRoot.provenance,
                    destinationRoot = selected.toAbsolutePath().normalize(),
                )
            }
        }
    }

    fun cancelCandidate() {
        synchronized(stateLock) {
            if (_state.value is DesktopDataRootSwitchState.CandidateSelected) {
                _state.value = idleState()
            }
        }
    }

    fun retryCandidate() {
        synchronized(stateLock) {
            val failure = _state.value as? DesktopDataRootSwitchState.RetryableFailure ?: return
            _state.value = DesktopDataRootSwitchState.CandidateSelected(
                currentRoot = failure.currentRoot,
                provenance = failure.provenance,
                destinationRoot = failure.destinationRoot,
            )
        }
    }

    suspend fun confirmMigration() {
        val destination = synchronized(stateLock) {
            val candidate = _state.value as? DesktopDataRootSwitchState.CandidateSelected
                ?: return
            _state.value = DesktopDataRootSwitchState.Migrating(
                currentRoot = candidate.currentRoot,
                provenance = candidate.provenance,
                destinationRoot = candidate.destinationRoot,
            )
            candidate.destinationRoot
        }

        val result = try {
            migrate(destination)
        } catch (cancelled: CancellationException) {
            synchronized(stateLock) {
                _state.value = if (isRestartRequired()) {
                    DesktopDataRootSwitchState.RestartRequired(
                        currentRoot = resolvedRoot.appDataRoot,
                        provenance = resolvedRoot.provenance,
                        destinationRoot = destination,
                        nextStartRoot = null,
                        result = null,
                        cancelledAfterRestartSeal = true,
                    )
                } else {
                    idleState()
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            synchronized(stateLock) {
                _state.value = DesktopDataRootSwitchState.RestartRequired(
                    currentRoot = resolvedRoot.appDataRoot,
                    provenance = resolvedRoot.provenance,
                    destinationRoot = destination,
                    nextStartRoot = null,
                    result = null,
                    unexpectedFailure = error,
                )
            }
            return
        }

        synchronized(stateLock) {
            _state.value = classify(destination, result)
        }
    }

    /** Returns false while migration stabilization owns the application lifetime. */
    fun requestWindowClose(): Boolean = synchronized(stateLock) {
        val current = _state.value
        if (current is DesktopDataRootSwitchState.Migrating) {
            _state.value = current.copy(closeDeferred = true)
            false
        } else {
            true
        }
    }

    /** Terminal UI delegates exit to Compose so the existing container/ownership finally blocks run. */
    fun requestExit(exitApplication: () -> Unit): Boolean {
        val allowed = synchronized(stateLock) {
            _state.value is DesktopDataRootSwitchState.RestartRequired
        }
        if (allowed) exitApplication()
        return allowed
    }

    private fun classify(
        destination: Path,
        result: DesktopDataRootMigrationResult,
    ): DesktopDataRootSwitchState = when (result) {
        is DesktopDataRootMigrationResult.Success -> restartRequired(destination, result)
        is DesktopDataRootMigrationResult.Failure -> if (result.isRetryableFromRunningSource()) {
            DesktopDataRootSwitchState.RetryableFailure(
                currentRoot = resolvedRoot.appDataRoot,
                provenance = resolvedRoot.provenance,
                destinationRoot = result.destinationRoot ?: destination,
                result = result,
            )
        } else {
            restartRequired(result.destinationRoot ?: destination, result)
        }
    }

    private fun restartRequired(
        destination: Path?,
        result: DesktopDataRootMigrationResult,
    ) = DesktopDataRootSwitchState.RestartRequired(
        currentRoot = resolvedRoot.appDataRoot,
        provenance = resolvedRoot.provenance,
        destinationRoot = destination,
        nextStartRoot = when (result) {
            is DesktopDataRootMigrationResult.Success -> destination
            is DesktopDataRootMigrationResult.Failure -> destination.takeIf {
                result.authorityState == DesktopDataRootMigrationAuthorityState.DESTINATION
            }
        },
        result = result,
    )

    private fun idleState(): DesktopDataRootSwitchState.Idle {
        val reason = when (resolvedRoot.provenance) {
            DesktopDataRootProvenance.PORTABLE ->
                "Portable data authority is controlled by portable.flag and cannot be changed here."
            DesktopDataRootProvenance.CLI_OVERRIDE ->
                "A temporary CLI data-root override cannot be changed persistently here."
            else -> null
        }
        return DesktopDataRootSwitchState.Idle(
            currentRoot = resolvedRoot.appDataRoot,
            provenance = resolvedRoot.provenance,
            supported = reason == null,
            unsupportedReason = reason,
        )
    }

    private fun DesktopDataRootMigrationResult.Failure.isRetryableFromRunningSource(): Boolean {
        if (
            restartRequired ||
            sourceStillAuthoritative != true ||
            destinationOwnershipRetained ||
            destinationCloseFailure != null
        ) {
            return false
        }
        val eligibleKind = kind in setOf(
            DesktopDataRootMigrationFailureKind.PREPARATION,
            DesktopDataRootMigrationFailureKind.PREFLIGHT,
            DesktopDataRootMigrationFailureKind.SAFETY_SNAPSHOT,
            DesktopDataRootMigrationFailureKind.MATERIALIZATION,
            DesktopDataRootMigrationFailureKind.AUTHORITY_PRE_COMMIT,
        )
        if (!eligibleKind) return false
        return when (kind) {
            DesktopDataRootMigrationFailureKind.PREPARATION -> runtimeDisposition == null
            else -> runtimeDisposition is DesktopMaintenanceResumeResult.Resumed
        }
    }

    private companion object {
        fun isSupportedProvenance(provenance: DesktopDataRootProvenance): Boolean = when (provenance) {
            DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT,
            DesktopDataRootProvenance.BOOTSTRAP_DEFAULT,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            -> true
            DesktopDataRootProvenance.PORTABLE,
            DesktopDataRootProvenance.CLI_OVERRIDE,
            -> false
        }
    }
}
