package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pre-fix diagnostic only: a passing test confirms a rejected legitimate choice. */
class MomentReanchorReproTest {
    private fun task(value: MomentTask) = LanSyncRecord("moment_tasks", value.id,
        LanSyncScope.normalize("moment_tasks", lanSyncJson.encodeToJsonElement(MomentTask.serializer(), value).jsonObject))
    private fun post(value: MomentPost) = LanSyncRecord("moment_posts", value.id,
        lanSyncJson.encodeToJsonElement(MomentPost.serializer(), value).jsonObject)

    @Test fun knownPreFixKeepPhoneSelectionIsRejectedWhileBothRemainsUnique() {
        val original = MomentPost("post", "historic-card", "historic-session", senderName = "Synthetic author",
            text = "original text", scheduledAt = 1, generatedAt = 1)
        val linked = MomentTask("task-t", original.characterCardId, original.sessionId, 1,
            status = MomentTaskStatus.COMPLETED, postId = original.id, createdAt = 1, updatedAt = 1)
        val base = LanSyncSnapshot(deviceId = "computer", records = listOf(task(linked), post(original)))
        val baseline = LanSyncBaseline("phone", "common-before-new-link", base.hashes(), ownerId = "computer")
        val computer = base.copy(records = base.records + task(linked.copy(id = "task-a")))
        val phone = base.copy(deviceId = "phone", records = listOf(task(linked), post(original.copy(text = "phone edit"))))
        val leftBefore = computer.toString(); val rightBefore = phone.toString()
        LanSyncScope.validateGraph(computer); LanSyncScope.validateGraph(phone)
        assertEquals(setOf("moment-task/task-a"), computer.units().keys)
        assertEquals(setOf("moment-task/task-t"), phone.units().keys)
        val comparison = LanSyncCompare.compare(computer, phone, baseline)
        assertEquals(setOf("moment-task/task-t"), comparison.conflicts.map { it.unit }.toSet())
        assertTrue("moment-task/task-a" in comparison.automatic)
        val choices = mapOf("moment-task/task-t" to LanSyncChoice.KEEP_PHONE)
        val refused = assertFailsWith<IllegalArgumentException> {
            LanSyncPlanner.plan(computer, phone, baseline, choices, "00000000-0000-0000-0000-000000000071")
        }
        assertEquals("SYNC_IDENTITIES", refused.message)
        val both = LanSyncPlanner.plan(computer, phone, baseline,
            choices.mapValues { LanSyncChoice.KEEP_BOTH }, "00000000-0000-0000-0000-000000000072")
        both.snapshot.validate(); LanSyncScope.validateGraph(both.snapshot)
        assertEquals(2, both.snapshot.records.count { it.type == "moment_posts" })
        assertEquals(3, both.snapshot.records.count { it.type == "moment_tasks" })
        assertEquals(both.snapshot.records.size, both.snapshot.records.map { it.key }.distinct().size)
        assertEquals(setOf("original text", "phone edit"), both.snapshot.records.filter { it.type == "moment_posts" }.map { it.text("text") }.toSet())
        assertEquals(leftBefore, computer.toString()); assertEquals(rightBefore, phone.toString())
        println("PRE_FIX_PRODUCT_FAILURE_REPRODUCED: KEEP_PHONE=SYNC_IDENTITIES; KEEP_BOTH=valid unique graph; inputs unchanged")
    }
}
