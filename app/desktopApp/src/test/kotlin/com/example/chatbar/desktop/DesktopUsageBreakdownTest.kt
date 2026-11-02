package com.example.chatbar.desktop

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopUsageBreakdownTest {
    private fun row(model: String = "model", at: String = "2021-01-01T00:00:00Z") = DesktopUsageRecord(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1, DesktopUsageSource.CHAT,
        sessionId = "session", modelId = model, modelName = model, provider = "provider", accountId = "account",
        startedAt = Instant.parse(at).toEpochMilli(), updatedAt = Instant.parse(at).toEpochMilli(),
        outcome = DesktopUsageOutcome.COMPLETED, counters = DesktopUsageCounters(prompt = 1))

    @Test fun `all persisted groups and requests remain reachable beyond the former first thirty`() = runBlocking {
        val root = Files.createTempDirectory("usage-breakdown-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            val rows = (0 until 37).map { row("model-$it").copy(accountId = "account-$it") }
            rows.forEach { store.save(it) }
            val file = root.resolve("diagnostics/provider-usage/${rows.first().id}.json")
            val original = Files.readAllBytes(file)
            val found = mutableSetOf<String>()
            for (page in 0..3) {
                owner.refresh(dimension = DesktopUsageDimension.MODEL_ACCOUNT, page = page)
                val state = owner.state.value
                assertNull(state.error); assertFalse(state.loading)
                assertEquals(37, state.totals?.requests)
                assertEquals(37, state.breakdown.groups); assertEquals(4, state.breakdown.pages)
                assertEquals(page, state.breakdown.page)
                found += state.breakdown.rows.map { it.sample.modelId }
            }
            assertEquals(rows.map { it.modelId }.toSet(), found)
            owner.refresh(dimension = DesktopUsageDimension.REQUEST, page = 100)
            assertEquals(3, owner.state.value.breakdown.page)
            val selected = owner.state.value.breakdown.rows.last().sample.requestId
            owner.refresh(DesktopUsageQuery(requestId = selected), DesktopUsageDimension.ATTEMPT, 100)
            val selectedState = owner.state.value
            assertEquals(1, selectedState.totals?.attempts)
            assertEquals(0, selectedState.breakdown.page)
            assertEquals(selected, selectedState.breakdown.rows.single().sample.requestId)
            assertContentEquals(original, Files.readAllBytes(file))
        } finally { owner.closeAndDrain(); gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun `periods use chosen local timezone and ISO Monday weeks across calendar year and month`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val rows = listOf(row(at = "2020-12-31T23:30:00Z"), row(at = "2021-01-03T23:30:00Z"), row(at = "2021-01-31T23:30:00Z"))
        fun keys(dimension: DesktopUsageDimension) = desktopUsageBreakdownPage(rows, dimension, 0, zone).rows.map { it.key.single() }.toSet()
        assertEquals(setOf("2021-01-01", "2021-01-04", "2021-02-01"), keys(DesktopUsageDimension.DAY))
        assertEquals(setOf("2020-12-28", "2021-01-04", "2021-02-01"), keys(DesktopUsageDimension.WEEK))
        assertEquals(setOf("2021-01", "2021-02"), keys(DesktopUsageDimension.MONTH))
        val utc = desktopUsageBreakdownPage(rows, DesktopUsageDimension.DAY, 0, ZoneId.of("UTC"))
        assertTrue(utc.rows.any { it.key.single() == "2020-12-31" })
    }

    @Test fun `same logical request retries and latest revision keep separate attempts unknown attribution and measured zero`() {
        val first = row().copy(outcome = DesktopUsageOutcome.RETRIED, provider = null, accountId = null, sessionId = null,
            counters = DesktopUsageCounters(prompt = 0))
        val next = first.copy(id = UUID.randomUUID().toString(), attempt = 2, outcome = DesktopUsageOutcome.COMPLETED,
            counters = DesktopUsageCounters(), startedAt = first.startedAt + 1000, updatedAt = first.updatedAt + 1000)
        val revision = next.copy(revision = 2, counters = DesktopUsageCounters(completion = 4))
        val records = listOf(first, next, revision)
        val requests = desktopUsageBreakdownPage(records, DesktopUsageDimension.REQUEST, 0)
        assertEquals(1, requests.groups)
        assertEquals(2, requests.rows.single().totals.attempts)
        assertEquals(0L, requests.rows.single().totals.prompt.value)
        assertEquals(1, requests.rows.single().totals.prompt.unreportedAttempts)
        val attempts = desktopUsageBreakdownPage(records, DesktopUsageDimension.ATTEMPT, 0)
        assertEquals(2, attempts.groups)
        assertEquals(2L, attempts.rows.first().sample.revision)
        assertEquals(4L, attempts.rows.first().totals.completion.value)
        assertEquals("供应商未标注", desktopUsageBreakdownPage(records, DesktopUsageDimension.PROVIDER, 0).rows.single().title)
        assertEquals("未关联会话", desktopUsageBreakdownPage(records, DesktopUsageDimension.SESSION, 0).rows.single().title)
        val accountA = row()
        val accountB = accountA.copy(id = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString(), accountId = "account-b")
        val accounts = desktopUsageBreakdownPage(listOf(accountA, accountB), DesktopUsageDimension.MODEL_ACCOUNT, 0)
        assertEquals(2, accounts.groups)
        assertEquals(setOf("account", "account-b"), accounts.rows.map { it.sample.accountId }.toSet())
        val firstLabel = row("a, b").copy(modelName = "c")
        val secondLabel = row("a").copy(modelName = "b, c")
        val labels = listOf(firstLabel, secondLabel)
        assertEquals(desktopUsageBreakdownPage(labels, DesktopUsageDimension.MODEL_ACCOUNT, 0, pageSize = 1).rows.single().key,
            desktopUsageBreakdownPage(labels.reversed(), DesktopUsageDimension.MODEL_ACCOUNT, 0, pageSize = 1).rows.single().key)
        val empty = desktopUsageBreakdownPage(emptyList(), DesktopUsageDimension.REQUEST, 100)
        assertEquals(0, empty.groups); assertEquals(0, empty.page); assertTrue(empty.rows.isEmpty())
        assertFailsWith<IllegalArgumentException> { desktopUsageBreakdownPage(records, DesktopUsageDimension.DAY, -1) }
    }
}
