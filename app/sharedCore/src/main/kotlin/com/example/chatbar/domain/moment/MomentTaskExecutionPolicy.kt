package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus

/** Settings, target validity and platform-local admission remain additional execution guards. */
object MomentTaskExecutionPolicy {
    fun mayAutomaticallyExecute(task: MomentTask): Boolean =
        task.status == MomentTaskStatus.PENDING && !task.requiresLocalScheduleApproval
}
