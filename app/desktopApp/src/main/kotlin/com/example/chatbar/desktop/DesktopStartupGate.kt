package com.example.chatbar.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Only fixed enum-derived codes cross the presentation boundary. No Throwable/path text. */
internal data class DesktopStartupDiagnostic private constructor(
    val code: String,
    val retainedProbe: Boolean = false,
) {
    companion object {
        fun preflight(code: DesktopIndexPreflightCode) = DesktopStartupDiagnostic("G0_${code.name}")
        fun selection(kind: DesktopDataRootResolutionFailureKind) = DesktopStartupDiagnostic("ROOT_${kind.name}")
        fun activation(kind: DesktopPortableActivationFailureKind, retained: Boolean) =
            DesktopStartupDiagnostic("PORTABLE_${kind.name}", retained)
        fun ownership(kind: DesktopDataRootOwnershipFailureKind) = DesktopStartupDiagnostic("OWNERSHIP_${kind.name}")
        fun cancelled() = DesktopStartupDiagnostic("STARTUP_CANCELLED")
    }
}

/** Safe refusal, never a signal that business writer drain failed. Created after lock cleanup. */
internal class DesktopStartupBlockedException(val diagnostic: DesktopStartupDiagnostic) :
    IllegalStateException("Desktop startup blocked: ${diagnostic.code}")

/**
 * Sole production admission sequence. G0 observes metadata, not transaction payloads. NO_DOMAIN
 * permits the existing startup only; it proves neither legacy vector safety nor whole-root safety.
 * runBlocking joins structured preflight IO before refusal/ownership release, including cancellation.
 */
internal class DesktopStartupGate(
    private val select: suspend () -> DesktopStartupRootSelection = { DesktopDataDirectory.selectRoot() },
    private val inspect: suspend (DesktopDataRootResolution.Resolved) -> DesktopIndexPreflightResult = {
        DesktopIndexStartupPreflight(it.appDataRoot).inspect()
    },
    private val activate: (DesktopStartupRootSelection.PortablePendingActivation) -> DesktopDataRootResolution = {
        DesktopDataDirectory.activatePortable(it)
    },
    private val presenter: DesktopStartupBlockedPresenter = PlatformDesktopStartupBlockedPresenter,
    private val acquire: (DesktopDataRootResolution.Resolved) -> DesktopDataRootOwnershipResult = DesktopDataRootOwnership::acquire,
    private val closeOwnership: (DesktopDataRootOwnership) -> Unit = DesktopDataRootOwnership::close,
    private val alreadyInUse: DesktopAlreadyInUsePresenter = PlatformAlreadyInUsePresenter,
    private val report: (String) -> Unit = System.err::println,
) {
    private class Refused(val diagnostic: DesktopStartupDiagnostic) : RuntimeException("Startup admission refused")

    fun run(business: (DesktopDataRootResolution.Resolved) -> Unit) {
        // Only preparation is caught here. Never classify a business/container failure as refusal.
        val selected = try {
            runBlocking { select() }
        } catch (_: CancellationException) {
            refuseWithoutOwnership(DesktopStartupDiagnostic.cancelled())
        } catch (_: Exception) {
            refuseWithoutOwnership(DesktopStartupDiagnostic.preflight(DesktopIndexPreflightCode.READ_ERROR))
        }
        if (selected is DesktopStartupRootSelection.Failed) {
            refuseWithoutOwnership(DesktopStartupDiagnostic.selection(selected.failure.kind))
        }
        // This identity is used ONLY to acquire ownership, never passed to business as activated.
        val ownershipTarget = when (selected) {
            is DesktopStartupRootSelection.Selected -> selected.root
            is DesktopStartupRootSelection.PortablePendingActivation -> DesktopDataRootResolution.Resolved(
                selected.appDataRoot, DesktopDataRootProvenance.PORTABLE, selected.bootstrapPath)
            is DesktopStartupRootSelection.Failed -> error("Unreachable refused selection")
        }
        try {
            runDesktopApplicationWithDataRootOwnership(ownershipTarget,
                acquireOwnership = { root ->
                    when (val result = acquire(root)) {
                        is DesktopDataRootOwnershipResult.Failure -> refuse(DesktopStartupDiagnostic.ownership(result.kind))
                        else -> result
                    }
                }, closeOwnership = closeOwnership, alreadyInUsePresenter = alreadyInUse) {
                val admittedRoot = try {
                    requireNoDomain(ownershipTarget)
                    if (selected is DesktopStartupRootSelection.PortablePendingActivation) {
                        when (val activated = activate(selected)) {
                            is DesktopDataRootResolution.Failed -> {
                                val failure = activated.portableActivationFailure
                                refuse(DesktopStartupDiagnostic.activation(
                                    failure?.kind ?: DesktopPortableActivationFailureKind.CANDIDATE_CHANGED,
                                    failure?.retainedProbe != null))
                            }
                            is DesktopDataRootResolution.Resolved -> {
                                // Fresh inspection under the SAME owner, after probe cleanup/identity validation.
                                requireNoDomain(activated)
                                activated
                            }
                        }
                    } else ownershipTarget
                } catch (refused: Refused) { throw refused }
                catch (_: CancellationException) { refuse(DesktopStartupDiagnostic.cancelled()) }
                catch (_: Exception) { refuse(DesktopStartupDiagnostic.preflight(DesktopIndexPreflightCode.READ_ERROR)) }
                // No picker, settings read, container or background service between proof and business.
                business(admittedRoot)
            }
        } catch (refused: Refused) {
            // The existing lifetime attaches a close failure to our suppression-enabled carrier.
            // Such a failure is NOT confirmed safe release, so retain existing unsafe-exit handling.
            if (refused.suppressed.isNotEmpty()) throw DesktopUnsafeShutdownException(refused)
            throw DesktopStartupBlockedException(refused.diagnostic)
        }
    }

    private fun requireNoDomain(root: DesktopDataRootResolution.Resolved) {
        val result = runBlocking { inspect(root) }
        if (result.code != DesktopIndexPreflightCode.NO_DOMAIN) refuse(DesktopStartupDiagnostic.preflight(result.code))
    }

    private fun present(diagnostic: DesktopStartupDiagnostic) {
        try { presenter.show(diagnostic) } catch (_: Throwable) {
            // Presentation cannot grant admission. Neither log function receives original exceptions.
            try { report("CCB STARTUP_BLOCKED ${diagnostic.code} PRESENTATION_FAILED") } catch (_: Throwable) { }
        }
    }

    private fun refuse(diagnostic: DesktopStartupDiagnostic): Nothing {
        present(diagnostic)
        throw Refused(diagnostic)
    }

    private fun refuseWithoutOwnership(diagnostic: DesktopStartupDiagnostic): Nothing {
        present(diagnostic)
        throw DesktopStartupBlockedException(diagnostic)
    }
}
