package com.example.chatbar.desktop

import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal enum class DesktopUsageDimension(val label: String) {
    MODEL_ACCOUNT("模型与账户"), PROVIDER("供应商"), SESSION("会话"), REQUEST("请求"),
    ATTEMPT("网络尝试"), DAY("天"), WEEK("周"), MONTH("月")
}

internal data class DesktopUsageBreakdown(
    val key: List<String?>, val title: String, val totals: DesktopUsageTotals,
    val sample: DesktopUsageRecord, val firstAt: Long, val lastAt: Long,
)

internal data class DesktopUsageBreakdownPage(
    val dimension: DesktopUsageDimension = DesktopUsageDimension.MODEL_ACCOUNT,
    val page: Int = 0, val pages: Int = 1, val groups: Int = 0,
    val rows: List<DesktopUsageBreakdown> = emptyList(),
)

/** Reads already-custodied numeric metadata only; periods use physical-attempt start time. */
internal fun desktopUsageBreakdownPage(records: List<DesktopUsageRecord>, dimension: DesktopUsageDimension,
    requestedPage: Int, zone: ZoneId = ZoneId.systemDefault(), pageSize: Int = 10): DesktopUsageBreakdownPage {
    require(requestedPage >= 0 && pageSize in 1..100)
    fun period(record: DesktopUsageRecord): String {
        val date = Instant.ofEpochMilli(record.startedAt).atZone(zone).toLocalDate()
        return when (dimension) {
            DesktopUsageDimension.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            DesktopUsageDimension.MONTH -> YearMonth.from(date).toString()
            else -> date.toString()
        }
    }
    val groups = records.groupBy { row -> when (dimension) {
        DesktopUsageDimension.MODEL_ACCOUNT -> listOf(row.modelId, row.modelName, row.provider, row.accountId, row.media?.kind?.name)
        DesktopUsageDimension.PROVIDER -> listOf(row.provider)
        DesktopUsageDimension.SESSION -> listOf(row.sessionId)
        DesktopUsageDimension.REQUEST -> listOf(row.requestId)
        DesktopUsageDimension.ATTEMPT -> listOf(row.id)
        DesktopUsageDimension.DAY, DesktopUsageDimension.WEEK, DesktopUsageDimension.MONTH -> listOf(period(row))
    } }.map { (key, rows) ->
        val sample = rows.maxWith(compareBy<DesktopUsageRecord> { it.startedAt }.thenBy { it.revision })
        val title = when (dimension) {
            DesktopUsageDimension.MODEL_ACCOUNT -> sample.modelName
            DesktopUsageDimension.PROVIDER -> sample.provider ?: "供应商未标注"
            DesktopUsageDimension.SESSION -> sample.sessionId?.let { "会话 $it" } ?: "未关联会话"
            DesktopUsageDimension.REQUEST -> "请求 ${sample.requestId}"
            DesktopUsageDimension.ATTEMPT -> "网络尝试 ${sample.attempt}"
            DesktopUsageDimension.WEEK -> "${key.single()} 起的一周"
            else -> requireNotNull(key.single())
        }
        DesktopUsageBreakdown(key, title, summarizeDesktopUsage(rows), sample,
            rows.minOf { it.startedAt }, rows.maxOf { it.updatedAt })
    }
    fun compareKeys(first: List<String?>, second: List<String?>): Int {
        for (index in 0 until minOf(first.size, second.size)) {
            val order = compareValues(first[index], second[index])
            if (order != 0) return order
        }
        return first.size.compareTo(second.size)
    }
    val countOrder = dimension in setOf(DesktopUsageDimension.MODEL_ACCOUNT, DesktopUsageDimension.PROVIDER, DesktopUsageDimension.SESSION)
    val ordered = groups.sortedWith { first, second ->
        val primary = if (countOrder) second.totals.attempts.compareTo(first.totals.attempts)
            else second.firstAt.compareTo(first.firstAt)
        if (primary != 0) primary else compareKeys(first.key, second.key)
    }
    val pages = ((ordered.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val page = requestedPage.coerceAtMost(pages - 1)
    return DesktopUsageBreakdownPage(dimension, page, pages, ordered.size, ordered.drop(page * pageSize).take(pageSize))
}
