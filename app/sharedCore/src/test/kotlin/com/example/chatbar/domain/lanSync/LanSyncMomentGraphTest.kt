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
    private fun common(snapshot: LanSyncSnapshot) = LanSyncBaseline("phone", "common", snapshot.hashes(),
        ownerId = "computer", coveredTypes = snapshot.coveredTypes, componentRecords = LanSyncMoments.components(snapshot))

    @Test fun lowerIdDuplicateLinkAndConcurrentPostEditRemainOneResolvableWholeComponent() {
        val originalTask = pending.copy(id = "t", postId = posted.id)
        val base = LanSyncSnapshot(deviceId = "computer", records = listOf(task(originalTask), post(posted)))
        val pc = base.copy(records = base.records + task(originalTask.copy(id = "a")))
        val phone = base.copy(deviceId = "phone", records = listOf(task(originalTask), post(posted.copy(text = "手机后续编辑"))))
        val baseline = common(base)
        val comparison = LanSyncCompare.compare(pc, phone, baseline)
        assertEquals(listOf("moment-task/t"), comparison.conflicts.map { it.unit })
        assertTrue(comparison.automatic.isEmpty())
        for (choice in listOf(LanSyncChoice.KEEP_PHONE, LanSyncChoice.KEEP_BOTH)) {
            val plan = LanSyncPlanner.plan(pc, phone, baseline, mapOf("moment-task/t" to choice), "synthetic-$choice")
            plan.snapshot.validate(); LanSyncScope.validateGraph(plan.snapshot)
            assertEquals(plan.snapshot.records.size, plan.snapshot.records.map { it.key }.distinct().size)
            val tasks = plan.snapshot.records.filter { it.type == "moment_tasks" }
            val posts = plan.snapshot.records.filter { it.type == "moment_posts" }.associateBy { it.id }
            assertTrue(tasks.all { it.text("postId") in posts })
            if (choice == LanSyncChoice.KEEP_PHONE) {
                assertEquals(2, plan.snapshot.records.size); assertEquals("手机后续编辑", posts.values.single().text("text"))
            } else { assertEquals(3, tasks.size); assertEquals(2, posts.size) }
            assertTrue("moment_posts/post" in plan.resolvedRecordKeys)
            val nextBase = common(plan.snapshot)
            val repeat = LanSyncCompare.compare(plan.snapshot, plan.snapshot.copy(deviceId = "phone"), nextBase)
            assertTrue(repeat.conflicts.isEmpty())
        }
        assertEquals("SYNC_COMPONENT_HISTORY_MISSING", assertFailsWith<IllegalArgumentException> {
            LanSyncCompare.compare(pc, phone, baseline.copy(componentRecords = emptyMap()))
        }.message)
    }

    @Test fun commonMembershipKeepsDeletedTaskLinkSoPostDeletionIsNotMistakenForAFreshAddition() {
        val base = LanSyncSnapshot(deviceId = "computer", records = listOf(task(pending.copy(postId = posted.id)), post(posted)))
        val pc = base.copy(records = listOf(post(posted.copy(text = "电脑编辑后保留"))))
        val phone = base.copy(deviceId = "phone", records = emptyList())
        val baseline = common(base)
        val conflict = LanSyncCompare.compare(pc, phone, baseline).conflicts.single()
        assertEquals("moment-task/task", conflict.unit)
        assertTrue(conflict.phoneDeleted)
        val plan = LanSyncPlanner.plan(pc, phone, baseline, mapOf(conflict.unit to LanSyncChoice.KEEP_PHONE), "selected-delete")
        assertTrue(plan.snapshot.records.isEmpty())
        assertTrue("moment_posts/post" in plan.resolvedRecordKeys)
    }

    @Test fun joiningTwoCommonGroupsReviewsTheUnionAndKeepBothCopiesEveryLinkOnlyOnce() {
        val other = posted.copy(id = "other-post")
        val firstTask = pending.copy(postId = posted.id)
        val secondTask = pending.copy(id = "other-task", postId = other.id)
        val base = LanSyncSnapshot(deviceId = "computer", records = listOf(task(firstTask), task(secondTask), post(posted), post(other)))
        val pc = base.copy(records = listOf(task(firstTask), task(secondTask.copy(postId = posted.id)), post(posted), post(other)))
        val phone = base.copy(deviceId = "phone")
        val baseline = common(base)
        val comparison = LanSyncCompare.compare(pc, phone, baseline)
        assertEquals(1, comparison.conflicts.size)
        assertTrue(comparison.automatic.isEmpty())
        val unit = comparison.conflicts.single().unit
        val plan = LanSyncPlanner.plan(pc, phone, baseline, mapOf(unit to LanSyncChoice.KEEP_BOTH), "selected-both")
        assertEquals(8, plan.snapshot.records.size)
        assertEquals(8, plan.snapshot.records.map { it.key }.distinct().size)
        val posts = plan.snapshot.records.filter { it.type == "moment_posts" }.map { it.id }.toSet()
        assertTrue(plan.snapshot.records.filter { it.type == "moment_tasks" }.all { it.text("postId") in posts })
        LanSyncScope.validateGraph(plan.snapshot)
    }

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
