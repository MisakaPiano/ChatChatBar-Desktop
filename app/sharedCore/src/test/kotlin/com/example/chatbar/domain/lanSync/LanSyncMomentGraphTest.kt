package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncMomentGraphTest {
    private fun task(value: MomentTask) = LanSyncRecord("moment_tasks", value.id,
        LanSyncScope.normalize("moment_tasks", lanSyncJson.encodeToJsonElement(MomentTask.serializer(), value).jsonObject))
    private fun post(value: MomentPost) = LanSyncRecord("moment_posts", value.id,
        lanSyncJson.encodeToJsonElement(MomentPost.serializer(), value).jsonObject)
    private val pending = MomentTask("task", "missing-card", "missing-session", 1, createdAt = 1)
    private val posted = MomentPost("post", pending.characterCardId, pending.sessionId, senderName = "历史作者",
        text = "原文保持", scheduledAt = 1, generatedAt = 1)

    @Test fun pendingToReservedOrCompletedKeepsTaskAnchorAndHistoricalLinksStayOutsideChat() {
        val before = LanSyncSnapshot(deviceId = "synthetic", records = listOf(task(pending)))
        val after = before.copy(records = listOf(task(pending.copy(status = MomentTaskStatus.COMPLETED, postId = posted.id)), post(posted)))
        assertEquals(setOf("moment-task/task"), before.units().keys)
        assertEquals(before.units().keys, after.units().keys)
        LanSyncScope.validateGraph(before); LanSyncScope.validateGraph(after)
        val history = after.copy(records = after.records + task(pending.copy(id = "another-task", postId = posted.id)) +
            post(posted.copy(id = "standalone")))
        assertEquals(setOf("moment-task/another-task", "moment-post/standalone"), history.units().keys)
        assertEquals(3, history.units().getValue("moment-task/another-task").size)
        LanSyncScope.validateGraph(history)
    }

    @Test fun wireNeverTransfersLocalExecutionApprovalAndImageVersionInconsistencyRefuses() {
        val raw = lanSyncJson.encodeToJsonElement(MomentTask.serializer(), pending).jsonObject
        val record = LanSyncRecord("moment_tasks", pending.id, raw)
        assertFails { LanSyncScope.validateGraph(LanSyncSnapshot(deviceId = "synthetic", records = listOf(record))) }
        assertEquals(JsonPrimitive(true), task(pending).body["requiresLocalScheduleApproval"])
        val versions = listOf(MomentImageVersion("one", "images/one.png", imagePrompt = "一", createdAt = 1),
            MomentImageVersion("two", "images/two.png", imagePrompt = "二", createdAt = 2))
        val valid = posted.copy(imagePath = "images/two.png", imagePrompt = "二", desktopImageVersions = versions)
        LanSyncScope.validateRecord(post(valid))
        assertFails { LanSyncScope.validateRecord(post(valid.copy(imagePrompt = "选中字段不一致"))) }
        assertFails { LanSyncScope.validateRecord(post(valid.copy(desktopImageVersions = versions + versions.first()))) }
        assertEquals(listOf("one", "two"), valid.desktopImageVersions.map { it.id })
    }
}
