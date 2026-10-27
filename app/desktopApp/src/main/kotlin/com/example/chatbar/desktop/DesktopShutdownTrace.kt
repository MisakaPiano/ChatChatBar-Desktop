package com.example.chatbar.desktop

import java.time.Instant

/** Opt-in lifecycle diagnostics: fixed identifiers only, never exceptions, paths or user content. */
internal object DesktopShutdownTrace {
    private val enabled = System.getenv("CCB_SHUTDOWN_TRACE") == "1"

    fun record(stage: DesktopShutdownStage) {
        if (enabled) System.err.println("CCB_SHUTDOWN ${Instant.now()} ${stage.name}")
    }
}

internal enum class DesktopShutdownStage {
    CLOSE_REQUESTED, CLOSE_DEFERRED, COMPOSE_RETURNED,
    TASK_DRAIN_BEGIN, TASK_DRAIN_END, DRAFT_DRAIN_BEGIN, DRAFT_DRAIN_END,
    BACKUP_CLOSE_BEGIN, BACKUP_CLOSE_END, COORDINATOR_CLOSE_BEGIN, COORDINATOR_CLOSE_END,
    MIGRATION_CLOSE_BEGIN, MIGRATION_CLOSE_END, CONTAINER_CLOSED,
    PICKERS_CLOSED, OWNERSHIP_RETAINED, OWNERSHIP_RELEASED, MAIN_RETURNED,
    DRAIN_DEFERRED, SHUTDOWN_FAILED, SAFE_EXIT_HANDOFF,
}
