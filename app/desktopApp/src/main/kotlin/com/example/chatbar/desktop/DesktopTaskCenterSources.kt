package com.example.chatbar.desktop

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private fun DesktopTaskStatus.workPhase() = when (this) {
    DesktopTaskStatus.RUNNING -> DesktopWorkPhase.RUNNING
    DesktopTaskStatus.COMPLETED -> DesktopWorkPhase.COMPLETED
    DesktopTaskStatus.FAILED -> DesktopWorkPhase.FAILED
    DesktopTaskStatus.USER_STOPPED, DesktopTaskStatus.CANCELLED -> DesktopWorkPhase.CANCELLED
}

internal fun DesktopTaskCenter.watchRuntime(runtime: DesktopTaskRuntime) {
    val flow = combine(runtime.tasks, runtime.ownedWork, runtime.repairTasks, runtime.repairIdentities) { tasks, owned, repairs, identities ->
        tasks.map { task -> DesktopWorkRow(task.taskId, if (task.kind == DesktopTaskKind.REAL_CHAT) DesktopWorkKind.CHAT else DesktopWorkKind.IMAGE,
            task.status.workPhase(), task.createdAt, target = task.sessionId?.let { DesktopWorkTarget(DesktopPrimaryRoute.CHAT, sessionId = it, subjectId = task.targetMessageId) }
                ?: DesktopWorkTarget(DesktopPrimaryRoute.TOOLS), canStop = task.status == DesktopTaskStatus.RUNNING,
            canRetry = task.canRetry && task.status != DesktopTaskStatus.RUNNING) } +
        owned.map { entry -> DesktopWorkRow(entry.taskId,
            when (entry.kind) {
                DesktopOwnedWorkKind.DOCUMENT -> if (entry.sessionId != null) DesktopWorkKind.HISTORY_INDEX else DesktopWorkKind.DOCUMENT
                DesktopOwnedWorkKind.CARD_DESIGN -> DesktopWorkKind.CARD_DESIGN
                DesktopOwnedWorkKind.CREATIVE -> DesktopWorkKind.CREATIVE
            },
            entry.status.workPhase(), entry.createdAt,
            target = when (entry.kind) {
                DesktopOwnedWorkKind.DOCUMENT -> entry.sessionId?.let { DesktopWorkTarget(DesktopPrimaryRoute.CHAT, sessionId = it) }
                    ?: DesktopWorkTarget(DesktopPrimaryRoute.MANAGE, cardId = entry.characterId, subjectId = entry.documentId)
                DesktopOwnedWorkKind.CARD_DESIGN -> DesktopWorkTarget(DesktopPrimaryRoute.MANAGE,
                    draftSessionId = entry.draftSessionId, detail = DesktopWorkDetail.FORMAT_DRAFT)
                DesktopOwnedWorkKind.CREATIVE -> null // Shared owner also serves character/world-book/image/Moments work.
            }, canStop = entry.status == DesktopTaskStatus.RUNNING) } +
        repairs.map { (id, status) ->
            val identity = identities[id]
            val phase = when (status) {
                DesktopRepairStatus.RUNNING -> DesktopWorkPhase.RUNNING
                DesktopRepairStatus.APPLIED, DesktopRepairStatus.UNCHANGED, DesktopRepairStatus.RESTORED -> DesktopWorkPhase.COMPLETED
                DesktopRepairStatus.STOPPED, DesktopRepairStatus.CANCELLED -> DesktopWorkPhase.CANCELLED
                DesktopRepairStatus.COMMIT_UNCERTAIN, DesktopRepairStatus.CONFLICT -> DesktopWorkPhase.UNCONFIRMED
                else -> DesktopWorkPhase.FAILED
            }
            DesktopWorkRow(id, DesktopWorkKind.FORMAT_REPAIR, phase, 0,
                target = identity?.let { DesktopWorkTarget(DesktopPrimaryRoute.CHAT, sessionId = it.sessionId, subjectId = it.messageId) },
                canStop = status == DesktopRepairStatus.RUNNING)
        }
    }
    watch("runtime", flow, stop = { runtime.requestUserStop(it) }, retry = { runtime.retryImageTask(it) != null })
}

/** Session activity includes reads/manual actions; it is not proof of a paid request or completion. */
internal fun DesktopTaskCenter.watchMemory(controller: DesktopMemoryController) {
    val firstSeen = mutableMapOf<String, Long>()
    watch("memory", controller.coordinator.runningSessions.map { sessions ->
        firstSeen.keys.retainAll(sessions)
        sessions.sorted().map { session -> DesktopWorkRow(session, DesktopWorkKind.MEMORY, DesktopWorkPhase.RUNNING,
            firstSeen.getOrPut(session) { System.currentTimeMillis() },
            target = DesktopWorkTarget(DesktopPrimaryRoute.CHAT, sessionId = session, detail = DesktopWorkDetail.MEMORY), canStop = true) }
    }, stop = { session ->
        if (session !in controller.coordinator.runningSessions.value) false
        else { controller.coordinator.stopAndJoin(session); true }
    })
}
